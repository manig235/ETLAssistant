import Foundation

/// 도구 실행기: (도구 이름·인자) → 결과. 사용자가 멈추면 Task가 취소되므로 오래 걸리는 도구는 `Task.isCancelled`를 확인한다.
public typealias ToolExecutor = @Sendable (ToolCall) async -> ToolOutput

/// OpenAI Responses API를 직접 부르는 AI 엔진(새로 만듦, Kotlin `CodexSession`의 턴·스트리밍·중단·도구 상태 줄을 대신한다).
///
/// - `POST /v1/responses`에 `stream: true`, `store: false`로 보내고 SSE를 읽는다. 대화 항목은 앱이 들고 있다가
///   턴마다 통째로 보낸다(`previous_response_id`는 쓰지 않는다).
/// - 모델이 함수를 부르면 `ToolExecutor`로 실행하고 `function_call_output`을 붙여 다시 부른다(`maxRounds`까지).
/// - `interrupt()`로 지금 턴을 멈춘다. 모든 턴은 `.turnEnded`로 끝나고, 그 안의 `history`를 저장했다가 다음 턴에 넘긴다.
public actor ResponsesEngine {
    private let auth: AuthProvider
    private let transport: HTTPTransport
    private let endpoint: URL
    private let retryDelays: [Double]
    private let statusLabel: @Sendable (String) -> String
    private var current: Task<Void, Never>?
    private var turnSerial = 0

    /// - Parameter retryDelays: 스트림이 열리기 전 일시 오류(5xx·503 계열, 연결 실패)를 다시 시도하기 전 기다리는 초. 개수만큼 재시도한다.
    public init(auth: AuthProvider, transport: HTTPTransport = URLSessionTransport(), endpoint: URL = ResponsesRequest.endpoint,
                retryDelays: [Double] = [1, 3], statusLabel: @escaping @Sendable (String) -> String = { ActivityTracker.forTool($0) }) {
        self.auth = auth
        self.transport = transport
        self.endpoint = endpoint
        self.retryDelays = retryDelays
        self.statusLabel = statusLabel
    }

    public var isBusy: Bool { current != nil }

    /// 한 턴을 시작한다. `history`는 지난 턴의 `TurnResult.history`(없으면 빈 배열), `input`은 이번 입력 항목
    /// (보통 `ResponsesInput.userMessage(…)` 하나). 이벤트 스트림은 `.turnEnded` 뒤에 끝난다.
    public func send(history: [ChatJSON], input: [ChatJSON], config: TurnConfig, cancellation: TurnCancellation? = nil,
                     tools: @escaping ToolExecutor) -> AsyncStream<ResponsesEvent> {
        let (stream, cont) = AsyncStream<ResponsesEvent>.makeStream()
        if current != nil {
            let err = ResponsesError(.invalidResponse, message: "이전 답변이 끝날 때까지 기다려 주세요")
            cont.yield(.turnEnded(TurnResult(status: .failed(err), responseId: nil, usage: nil, history: history, finalText: "")))
            cont.finish()
            return stream
        }
        turnSerial += 1
        let serial = turnSerial
        let task = Task { [weak self] in
            guard let self else { cont.finish(); return }
            await self.runTurn(history: history, input: input, config: config, executor: tools, cont: cont)
            await self.clear(serial)
        }
        current = task
        cancellation?.onCancel { task.cancel() }
        cont.onTermination = { reason in
            if case .cancelled = reason { task.cancel() }
        }
        return stream
    }

    /// 지금 턴을 멈춘다(진행 중인 요청과 도구 실행을 취소한다). 턴은 `.turnEnded(.interrupted)`로 끝난다.
    public func interrupt() {
        current?.cancel()
    }

    private func clear(_ serial: Int) {
        if serial == turnSerial { current = nil }
    }

    // MARK: - 턴

    /// 한 턴 동안의 화면 상태(엔진 액터 안에서만 쓴다).
    private final class TurnState {
        let cont: AsyncStream<ResponsesEvent>.Continuation
        let tracker = ActivityTracker()
        var lastLabel: String?? = .none
        var announced = Set<String>()
        var acc = ResponsesRoundAccumulator()
        var requestId: String?

        init(_ cont: AsyncStream<ResponsesEvent>.Continuation) { self.cont = cont }

        func setActivity(_ label: String?) {
            if case .some(let l) = lastLabel, l == label { return }
            lastLabel = .some(label)
            cont.yield(.activity(label))
        }

        func handle(_ e: ResponsesStreamEvent) {
            acc.apply(e)
            switch e {
            case .textDelta(let id, let d):
                cont.yield(.textDelta(itemId: id, delta: d))
                setActivity(tracker.textDelta())
            case .outputItemDone(let item, _):
                announceIfMessage(item)
            case .reasoningDelta(let id, let d):
                cont.yield(.reasoningDelta(itemId: id, delta: d))
            default:
                break
            }
        }

        func announceIfMessage(_ item: ChatJSON) {
            guard item["type"]?.string == "message", let id = item["id"]?.string, !announced.contains(id) else { return }
            announced.insert(id)
            cont.yield(.textDone(itemId: id, text: ResponsesRoundAccumulator.messageText(item)))
            setActivity(tracker.messageDone())
        }

        /// 끊긴 회차에서 살릴 항목: 다 받은 메시지와, 받다 만 글(어시스턴트 메시지로).
        func salvage() -> [ChatJSON] {
            let done = acc.doneItems.keys.sorted().compactMap { acc.doneItems[$0] }.filter { $0["type"]?.string == "message" }
            let doneIds = Set(done.compactMap { $0["id"]?.string })
            let partial = acc.texts.keys.sorted().filter { !doneIds.contains($0) && !(acc.texts[$0] ?? "").isEmpty }.map { id -> ChatJSON in
                ["type": "message", "role": "assistant", "content": [["type": "output_text", "text": .string(acc.texts[id] ?? "")]]]
            }
            return done + partial
        }
    }

    private func runTurn(history: [ChatJSON], input: [ChatJSON], config: TurnConfig, executor: @escaping ToolExecutor,
                         cont: AsyncStream<ResponsesEvent>.Continuation) async {
        let state = TurnState(cont)
        state.setActivity(state.tracker.turnStarted())
        var items = ConversationHistory.trim(ConversationHistory.seal(history) + input)
        var usage: ResponsesUsage?
        var responseId: String?
        var finalText = ""

        func end(_ status: TurnStatus) {
            state.tracker.turnEnded()
            state.setActivity(nil)
            cont.yield(.turnEnded(TurnResult(status: status, responseId: responseId, usage: usage,
                                             history: ConversationHistory.seal(items), finalText: finalText)))
            cont.finish()
        }

        for round in 0..<config.maxRounds {
            if Task.isCancelled { end(.interrupted); return }
            let forceAnswer = config.maxRounds > 1 && round == config.maxRounds - 1
            let body = ResponsesRequest.body(config: config, input: items, auth: auth.kind, forceAnswer: forceAnswer)
            state.acc = ResponsesRoundAccumulator()
            do {
                try await streamRound(body: body, state: state)
            } catch {
                items += state.salvage()
                if Self.isCancellation(error) || Task.isCancelled { end(.interrupted); return }
                end(.failed(Self.wrap(error, requestId: state.requestId)))
                return
            }
            let acc = state.acc
            responseId = acc.responseId ?? responseId
            if let u = acc.usage { usage = (usage ?? ResponsesUsage()) + u }
            switch acc.terminal {
            case nil:
                items += state.salvage()
                if Task.isCancelled { end(.interrupted); return }
                end(.failed(ResponsesError(.transport, message: "답변을 받는 중에 연결이 끊겼어요", requestId: state.requestId)))
                return
            case .failed(let code, let message, let param)?:
                DiagLog.logger.notice("responses: stream failed code=\(code ?? "-", privacy: .public) param=\(param ?? "-", privacy: .public) msg=\(String(message.prefix(300)), privacy: .public)")
                items += state.salvage()
                end(.failed(ResponsesError(.stream, code: code, message: message, param: param, requestId: state.requestId)))
                return
            case .incomplete(let reason)?:
                items += acc.outputItems
                finalText = acc.outputItems.map(ResponsesRoundAccumulator.messageText).joined()
                acc.outputItems.forEach(state.announceIfMessage)
                end(.incomplete(reason: reason))
                return
            case .completed?:
                break
            }
            let outputs = acc.outputItems
            outputs.forEach(state.announceIfMessage)
            items += outputs
            cont.yield(.roundCompleted(responseId: acc.responseId, usage: acc.usage))
            let text = outputs.map(ResponsesRoundAccumulator.messageText).joined()
            if !text.isEmpty { finalText = text }
            let calls = ResponsesRoundAccumulator.functionCalls(in: outputs)
            if calls.isEmpty { end(.completed); return }
            if forceAnswer { end(.failed(ResponsesError(.tooManyRounds, message: "too many tool rounds"))); return }

            await execute(calls, config: config, executor: executor, state: state, items: &items)
            if Task.isCancelled { end(.interrupted); return }
        }
        end(.failed(ResponsesError(.tooManyRounds, message: "too many tool rounds")))
    }

    private func execute(_ calls: [ToolCall], config: TurnConfig, executor: @escaping ToolExecutor, state: TurnState, items: inout [ChatJSON]) async {
        if config.parallelToolExecution && calls.count > 1 {
            for call in calls {
                state.setActivity(state.tracker.toolStarted(call.callId, call.name))
                state.cont.yield(.toolStarted(call, label: statusLabel(call.name)))
            }
            let results = await withTaskGroup(of: (Int, ToolOutput).self) { group -> [Int: ToolOutput] in
                for (i, call) in calls.enumerated() { group.addTask { (i, await executor(call)) } }
                var out: [Int: ToolOutput] = [:]
                for await (i, o) in group { out[i] = o }
                return out
            }
            for (i, call) in calls.enumerated() {
                if let o = results[i] { items.append(ResponsesInput.functionCallOutput(callId: call.callId, output: o)) }
                state.setActivity(state.tracker.toolFinished(call.callId))
                state.cont.yield(.toolFinished(call))
            }
            return
        }
        for call in calls {
            if Task.isCancelled { return }
            state.setActivity(state.tracker.toolStarted(call.callId, call.name))
            state.cont.yield(.toolStarted(call, label: statusLabel(call.name)))
            let output = await executor(call)
            items.append(ResponsesInput.functionCallOutput(callId: call.callId, output: output))
            state.setActivity(state.tracker.toolFinished(call.callId))
            state.cont.yield(.toolFinished(call))
        }
    }

    // MARK: - 한 회차

    private func streamRound(body: ChatJSON, state: TurnState) async throws {
        var attempt = 0
        var refreshed = false
        while true {
            try Task.checkCancellation()
            let token: String
            do { token = try await auth.bearerToken(forceRefresh: refreshed) } catch let e as AuthError {
                throw ResponsesError(.auth, message: e.userMessage)
            }
            try Task.checkCancellation()
            let request = ResponsesRequest.urlRequest(body: body, token: token, endpoint: endpoint)
            let response: HTTPLineResponse
            do {
                response = try await transport.lines(for: request)
            } catch {
                if Self.isCancellation(error) { throw CancellationError() }
                if attempt < retryDelays.count {
                    try await Self.pause(retryDelays[attempt])
                    attempt += 1
                    continue
                }
                throw ResponsesError(.transport, message: error.localizedDescription)
            }
            state.requestId = response.header("x-request-id")
            guard (200..<300).contains(response.status) else {
                var data = Data()
                do {
                    for try await line in response.lines {
                        data.append(contentsOf: Array(line.utf8))
                        data.append(0x0A)
                        if data.count > 1_048_576 { break }
                    }
                } catch {
                    if Self.isCancellation(error) { throw CancellationError() }
                }
                let err = ResponsesRequest.httpError(status: response.status, body: data, requestId: state.requestId)
                if response.status == 401 && !refreshed && auth.kind == .chatGPT {
                    refreshed = true
                    continue
                }
                if err.isRetryable && attempt < retryDelays.count {
                    try await Self.pause(retryDelays[attempt])
                    attempt += 1
                    continue
                }
                throw err
            }
            var parser = SSEParser()
            do {
                for try await line in response.lines {
                    try Task.checkCancellation()
                    if let m = parser.push(line: line), let e = ResponsesStreamEvent.decode(m) {
                        state.handle(e)
                        if state.acc.isFinished { return } // 끝 이벤트 뒤에는 연결을 기다리지 않는다
                    }
                }
                if let m = parser.finish(), let e = ResponsesStreamEvent.decode(m) { state.handle(e) }
            } catch {
                if Self.isCancellation(error) || Task.isCancelled { throw CancellationError() }
                throw ResponsesError(.transport, message: error.localizedDescription, requestId: state.requestId)
            }
            return
        }
    }

    private static func pause(_ seconds: Double) async throws {
        if seconds > 0 { try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
    }

    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let u = error as? URLError, u.code == .cancelled { return true }
        return false
    }

    private static func wrap(_ error: Error, requestId: String?) -> ResponsesError {
        if let e = error as? ResponsesError { return e }
        if let e = error as? AuthError { return ResponsesError(.auth, message: e.userMessage) }
        return ResponsesError(.transport, message: error.localizedDescription, requestId: requestId)
    }
}
