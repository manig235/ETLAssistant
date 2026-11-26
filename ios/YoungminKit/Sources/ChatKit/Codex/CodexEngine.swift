import Foundation

/// Runs conversation turns in the embedded official engine. Only app-provided
/// dynamic tools are executed by Swift; unsupported server requests are rejected.
public actor CodexEngine {
    public static let threadMarker = "youngmin_codex_thread"
    private let client: CodexClient
    private var current: Task<Void, Never>?
    private var serial = 0

    public init(client: CodexClient) { self.client = client }

    public func send(history: [ChatJSON], input: [ChatJSON], config: TurnConfig, cancellation: TurnCancellation? = nil,
                     tools: @escaping ToolExecutor) -> AsyncStream<ResponsesEvent> {
        let (stream, output) = AsyncStream<ResponsesEvent>.makeStream()
        guard current == nil else {
            output.yield(.turnEnded(TurnResult(status: .failed(ResponsesError(.invalidResponse, message: "이전 답변이 끝날 때까지 기다려 주세요")), responseId: nil, usage: nil, history: history, finalText: "")))
            output.finish(); return stream
        }
        serial += 1
        let turnSerial = serial
        let task = Task { [weak self] in
            guard let self else { output.finish(); return }
            await self.run(history: history, input: input, config: config, tools: tools, output: output)
            await self.clear(turnSerial)
        }
        current = task
        cancellation?.onCancel { task.cancel() }
        output.onTermination = { reason in if case .cancelled = reason { task.cancel() } }
        return stream
    }

    private func clear(_ value: Int) { if serial == value { current = nil } }

    private func run(history: [ChatJSON], input: [ChatJSON], config: TurnConfig, tools: @escaping ToolExecutor,
                     output: AsyncStream<ResponsesEvent>.Continuation) async {
        let (subscription, events) = await client.subscribe()
        defer {
            Task { await client.cancelEvents(subscription) }
            current = nil
            output.yield(.activity(nil)); output.finish()
        }
        output.yield(.activity(ActivityTracker.THINKING))
        var threadID: String?
        var turnID: String?
        var savedHistory = history
        var texts: [String: String] = [:]
        var textOrder: [String] = []
        var finished = false
        func end(_ status: TurnStatus) {
            guard !finished else { return }; finished = true
            output.yield(.turnEnded(TurnResult(status: status, responseId: turnID, usage: nil,
                                                history: savedHistory, finalText: textOrder.compactMap { texts[$0] }.joined(separator: "\n"))))
        }
        do {
            try Task.checkCancellation()
            var resumed: ChatJSON?
            let existing = history.first { $0["type"]?.string == Self.threadMarker }?["thread_id"]?.string
            if let existing {
                do {
                    resumed = try await client.request("thread/resume", ["threadId": .string(existing), "model": .string(config.model), "developerInstructions": .string(config.instructions ?? ""), "excludeTurns": true])
                } catch let error as CodexError where Self.rejectsThread(error) {
                    // The saved ID would fail on every later message too. Continue in a new
                    // thread; timeouts and closed connections still fail and keep the ID.
                    output.yield(.notice(Self.contextLost))
                }
            }
            let result: ChatJSON
            if let resumed {
                result = resumed
            } else {
                let definitions: [ChatJSON] = config.tools.map {
                    ["type": "function", "name": .string($0.name), "description": .string($0.description), "inputSchema": $0.parameters]
                }
                result = try await client.request("thread/start", [
                    "model": .string(config.model), "developerInstructions": .string(config.instructions ?? ""),
                    "dynamicTools": .array(definitions), "approvalPolicy": "never", "sandbox": "read-only",
                ])
            }
            guard let id = result["thread"]?["id"]?.string else { throw CodexError("Codex 대화를 만들지 못했어요") }
            threadID = id
            // Keep a replaced thread's ID after the current one so deleting the
            // conversation still removes its stored engine history.
            let replaced = history.filter { $0["type"]?.string == Self.threadMarker && $0["thread_id"]?.string != id }
            savedHistory = [["type": .string(Self.threadMarker), "thread_id": .string(id)]] + replaced
            var userInput = Self.userInput(input)
            let context = Self.legacyContext(history)
            if !context.isEmpty { userInput.insert(["type": "text", "text": .string(context), "text_elements": []], at: 0) }
            try Task.checkCancellation()
            var params: ChatJSON = ["threadId": .string(id), "input": .array(userInput)]
            if let effort = config.effort { params = params.setting("effort", .string(effort)) }
            // Once accepted by the engine this mutation must yield its turn ID,
            // even when the UI cancels, so it can always be interrupted below.
            let client = self.client
            let starting = Task { try await client.request("turn/start", params) }
            let started = try await starting.value
            turnID = started["turn"]?["id"]?.string
            try Task.checkCancellation()
            for await event in events {
                try Task.checkCancellation()
                let method = event["method"]?.string ?? ""
                let p = event["params"] ?? [:]
                if method == "youngmin/fatal" { throw CodexError(p["message"]?.string ?? "Codex 실행이 중단됐어요") }
                if let requestID = event["id"], p["threadId"] == nil {
                    try await client.reject(id: requestID, message: "이 iOS 앱에서 지원하지 않는 요청입니다")
                    continue
                }
                guard p["threadId"]?.string == id else { continue }
                if let eventTurn = p["turnId"]?.string, let turnID, eventTurn != turnID { continue }
                switch method {
                case "item/agentMessage/delta":
                    guard let item = p["itemId"]?.string, let delta = p["delta"]?.string else { continue }
                    if texts[item] == nil { textOrder.append(item) }
                    texts[item, default: ""] += delta
                    output.yield(.activity(nil)); output.yield(.textDelta(itemId: item, delta: delta))
                case "item/completed":
                    let item = p["item"] ?? [:]
                    if item["type"]?.string == "agentMessage", let itemID = item["id"]?.string, let text = item["text"]?.string {
                        if texts[itemID] == nil { textOrder.append(itemID) }
                        texts[itemID] = text
                        output.yield(.textDone(itemId: itemID, text: text))
                    }
                case "item/reasoning/summaryTextDelta", "item/reasoning/textDelta":
                    if let item = p["itemId"]?.string, let delta = p["delta"]?.string { output.yield(.reasoningDelta(itemId: item, delta: delta)) }
                case "item/tool/call":
                    guard let requestID = event["id"], let name = p["tool"]?.string, let callID = p["callId"]?.string else { continue }
                    let call = ToolCall(callId: callID, name: name, namespace: p["namespace"]?.string,
                                        arguments: (p["arguments"] ?? [:]).jsonString)
                    output.yield(.activity(ActivityTracker.forTool(name)))
                    output.yield(.toolStarted(call, label: ActivityTracker.forTool(name)))
                    // The existing executor includes eTL write approvals.
                    let result = await tools(call)
                    try Task.checkCancellation()
                    var content: [ChatJSON] = [["type": "inputText", "text": .string(result.text)]]
                    content += result.images.map { ["type": "inputImage", "imageUrl": .string($0.dataURL)] }
                    try await client.respond(id: requestID, result: ["success": true, "contentItems": .array(content)])
                    output.yield(.toolFinished(call)); output.yield(.activity(ActivityTracker.THINKING))
                case "turn/completed":
                    let turn = p["turn"] ?? [:]
                    guard turn["id"]?.string == turnID else { continue }
                    switch turn["status"]?.string {
                    case "completed": end(.completed)
                    case "interrupted": end(.interrupted)
                    default: end(.failed(ResponsesError(.stream, message: turn["error"]?["message"]?.string ?? "답변을 만들지 못했어요")))
                    }
                    return
                default:
                    if let requestID = event["id"] { try await client.reject(id: requestID, message: "이 iOS 앱에서 지원하지 않는 작업입니다") }
                }
            }
            if Task.isCancelled { throw CancellationError() }
            throw CodexError("Codex 연결이 종료됐어요")
        } catch {
            if Task.isCancelled || error is CancellationError {
                if let threadID, let turnID {
                    let client = self.client
                    _ = try? await Task { try await client.request("turn/interrupt", ["threadId": .string(threadID), "turnId": .string(turnID)], timeout: 10) }.value
                }
                end(.interrupted)
            } else {
                let message = (error as? CodexError)?.message ?? "Codex 요청에 실패했어요"
                end(.failed(ResponsesError(.stream, message: message)))
            }
        }
    }

    static let contextLost = "이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요."
    /// Code the bridge uses when its engine connection has closed (not a rejection).
    static let bridgeClosedCode = -32000

    /// The engine answered thread/resume with an error about this thread itself.
    static func rejectsThread(_ error: CodexError) -> Bool {
        guard let code = error.code else { return false }
        return code != bridgeClosedCode
    }

    static func userInput(_ messages: [ChatJSON]) -> [ChatJSON] {
        messages.flatMap { message in
            (message["content"]?.array ?? []).compactMap { item -> ChatJSON? in
                switch item["type"]?.string {
                case "input_text": return ["type": "text", "text": item["text"] ?? "", "text_elements": []]
                case "input_image": return ["type": "image", "url": item["image_url"] ?? ""]
                default: return nil
                }
            }
        }
    }

    static func legacyContext(_ history: [ChatJSON]) -> String {
        let messages = history.compactMap { item -> String? in
            guard let role = item["role"]?.string, role == "user" || role == "assistant" else { return nil }
            let text = item["content"]?.array?.compactMap { $0["text"]?.string }.joined(separator: "\n") ?? ""
            return text.isEmpty ? nil : "\(role): \(text)"
        }
        guard !messages.isEmpty else { return "" }
        return "다음은 이전 대화 기록입니다. 참고 자료로 읽어 주세요.\n<previous_conversation>\n" + messages.joined(separator: "\n") + "\n</previous_conversation>"
    }
}
