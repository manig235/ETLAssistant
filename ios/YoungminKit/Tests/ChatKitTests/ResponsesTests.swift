import Foundation
import Testing
@testable import ChatKit

@Suite struct SSEParserTests {
    @Test func parsesEventsDataAndComments() {
        let text = ": keep-alive\nevent: response.output_text.delta\ndata: {\"a\":1}\n\nevent: x\ndata: line1\ndata:line2\nid: 7\n\n"
        let m = SSEParser.parse(text)
        #expect(m.count == 2)
        #expect(m[0] == SSEMessage(event: "response.output_text.delta", data: "{\"a\":1}"))
        #expect(m[1].data == "line1\nline2")
        #expect(m[1].id == "7")
    }

    @Test func handlesCRLFAndMissingTrailingBlankLine() {
        let m = SSEParser.parse("data: one\r\n\r\ndata: two")
        #expect(m.map(\.data) == ["one", "two"])
    }

    @Test func eventWithoutDataIsDropped() {
        #expect(SSEParser.parse("event: ping\n\n").isEmpty)
    }

    @Test func splitterKeepsEmptyLinesAcrossChunks() {
        var s = SSELineSplitter()
        var lines = s.push(Data("data: a\r".utf8))
        lines += s.push(Data("\n\r\ndata: 한".utf8))
        lines += s.push(Data("글\n".utf8))
        #expect(lines == ["data: a", "", "data: 한글"])
    }

    @Test func decodesTextDeltasAndCompletion() {
        let events = SSEParser.parse(Fixtures.textStream()).compactMap(ResponsesStreamEvent.decode)
        #expect(events.contains(.textDelta(itemId: "msg_1", delta: "이번 주 ")))
        #expect(events.contains(.textDone(itemId: "msg_1", text: "이번 주 마감은 2개예요.")))
        var acc = ResponsesRoundAccumulator()
        events.forEach { acc.apply($0) }
        #expect(acc.terminal == .completed)
        #expect(acc.responseId == "resp_1")
        #expect(acc.texts["msg_1"] == "이번 주 마감은 2개예요.")
        #expect(acc.outputItems.map { $0["type"]?.string } == ["reasoning", "message"])
        #expect(acc.usage == ResponsesUsage(inputTokens: 120, outputTokens: 30, totalTokens: 150, cachedTokens: 64, reasoningTokens: 12))
    }

    @Test func decodesFunctionCallArgumentDeltas() {
        let stream = Fixtures.toolStream(calls: [("call_1", "etl_digest", #"{"days":7}"#)], namespace: "youngmin")
        var acc = ResponsesRoundAccumulator()
        var deltas = ""
        for e in SSEParser.parse(stream).compactMap(ResponsesStreamEvent.decode) {
            if case .functionArgumentsDelta(let id, let d) = e { #expect(id == "fc_call_1"); deltas += d }
            if case .functionArgumentsDone = e { #expect(acc.pendingCalls["fc_call_1"]?.arguments == #"{"days":7}"#) }
            acc.apply(e)
        }
        #expect(deltas == #"{"days":7}"#)
        let calls = ResponsesRoundAccumulator.functionCalls(in: acc.outputItems)
        #expect(calls == [ToolCall(callId: "call_1", name: "etl_digest", namespace: "youngmin", arguments: #"{"days":7}"#, itemId: "fc_call_1")])
        #expect(calls[0].argumentsJSON?["days"]?.int == 7)
    }

    @Test func decodesErrorsAndFailures() {
        let err = ResponsesStreamEvent.decode(SSEMessage(event: "error", data: #"{"type":"error","code":"server_error","message":"boom","param":null,"sequence_number":3}"#))
        #expect(err == .error(code: "server_error", message: "boom", param: nil))
        let nested = ResponsesStreamEvent.decode(SSEMessage(data: #"{"type":"error","error":{"code":"x","message":"y","param":"z"}}"#))
        #expect(nested == .error(code: "x", message: "y", param: "z"))
        var acc = ResponsesRoundAccumulator()
        SSEParser.parse(Fixtures.failedStream(code: "subscription_sharing_usage_limit_exceeded", message: "limit"))
            .compactMap(ResponsesStreamEvent.decode).forEach { acc.apply($0) }
        #expect(acc.terminal == .failed(code: "subscription_sharing_usage_limit_exceeded", message: "limit", param: nil))
        let noCode = ResponsesStreamEvent.decode(SSEMessage(data: #"{"type":"response.failed","response":{"id":"r","error":null}}"#))!
        var a2 = ResponsesRoundAccumulator()
        a2.apply(noCode)
        #expect(a2.terminal == .failed(code: "unknown_error", message: "", param: nil))
    }

    @Test func incompleteAndUnknownEvents() {
        let inc = ResponsesStreamEvent.decode(SSEMessage(data: #"{"type":"response.incomplete","response":{"id":"r","incomplete_details":{"reason":"max_output_tokens"},"output":[]}}"#))!
        var acc = ResponsesRoundAccumulator()
        acc.apply(inc)
        #expect(acc.terminal == .incomplete(reason: "max_output_tokens"))
        #expect(ResponsesStreamEvent.decode(SSEMessage(event: "response.content_part.done", data: #"{"type":"response.content_part.done"}"#)) == .other(type: "response.content_part.done"))
        #expect(ResponsesStreamEvent.decode(SSEMessage(data: "[DONE]")) == nil)
        // type이 없으면 SSE 이벤트 이름을 쓴다
        #expect(ResponsesStreamEvent.decode(SSEMessage(event: "response.output_text.delta", data: #"{"item_id":"m","delta":"d"}"#)) == .textDelta(itemId: "m", delta: "d"))
    }
}

@Suite struct ResponsesRequestTests {
    private let tool = FunctionTool(name: "etl_digest", description: "이번 주 요약", parameters: ["type": "object", "properties": [:], "additionalProperties": false])

    @Test func bodyHasRequiredFieldsAndOmitsUnsupported() {
        let cfg = TurnConfig(model: "gpt-x", effort: "high", instructions: "지침", tools: [tool])
        let b = ResponsesRequest.body(config: cfg, input: [ResponsesInput.userMessage("안녕")], auth: .apiKey, forceAnswer: false)
        #expect(b["store"] == false)
        #expect(b["stream"] == true)
        #expect(b["model"] == "gpt-x")
        #expect(b["instructions"] == "지침")
        #expect(b["reasoning"] == ["effort": "high"])
        #expect(b["tool_choice"] == "auto")
        #expect(b["tools"]?[0]?["type"] == "function")
        #expect(b["include"] == ["reasoning.encrypted_content"])
        for k in ["previous_response_id", "max_output_tokens", "temperature", "metadata", "user", "background", "truncation"] { #expect(b[k] == nil) }
    }

    @Test func chatGPTPacksToolsInNamespace() {
        let cfg = TurnConfig(model: "m", tools: [tool])
        let b = ResponsesRequest.body(config: cfg, input: [], auth: .chatGPT, forceAnswer: true)
        #expect(b["tools"]?[0]?["type"] == "namespace")
        #expect(b["tools"]?[0]?["name"] == "youngmin")
        #expect(b["tools"]?[0]?["tools"]?[0]?["name"] == "etl_digest")
        #expect(b["tool_choice"] == "none")
        #expect(b["reasoning"] == nil)
        let plain = ResponsesRequest.body(config: TurnConfig(model: "m", tools: [tool], toolPackaging: .plain), input: [], auth: .chatGPT, forceAnswer: false)
        #expect(plain["tools"]?[0]?["type"] == "function")
    }

    @Test func userMessageWithImageAndFile() {
        let m = ResponsesInput.userMessage("봐줘", images: [InputImage(data: Data([1, 2, 3]), mime: "image/jpeg")],
                                           files: [InputFile(filename: "a.pdf", data: Data([4]), mime: "application/pdf")])
        let c = m["content"]!.array!
        #expect(m["role"] == "user")
        #expect(c[0] == ["type": "input_text", "text": "봐줘"])
        #expect(c[1] == ["type": "input_image", "image_url": "data:image/jpeg;base64,AQID"])
        #expect(c[2] == ["type": "input_file", "filename": "a.pdf", "file_data": "data:application/pdf;base64,BA=="])
        #expect(ResponsesInput.developerMessage("x")["role"] == "developer")
    }

    @Test func toolOutputShapes() {
        #expect(ToolOutput(text: "ok").outputJSON == "ok")
        let rich = ToolOutput(text: "1쪽", images: [InputImage(data: Data([0]), mime: "image/png")]).outputJSON
        #expect(rich[0] == ["type": "input_text", "text": "1쪽"])
        #expect(rich[1]?["type"] == "input_image")
        #expect(ResponsesInput.functionCallOutput(callId: "c1", output: ToolOutput(text: "r")) == ["type": "function_call_output", "call_id": "c1", "output": "r"])
    }

    @Test func httpErrorParsing() {
        let e = ResponsesRequest.httpError(status: 400, body: Data(#"{"error":{"code":"subscription_sharing_unsupported_capability","message":"no","param":"tools[0]"}}"#.utf8), requestId: "req_9")
        #expect(e.code == "subscription_sharing_unsupported_capability")
        #expect(e.param == "tools[0]")
        #expect(e.requestId == "req_9")
        #expect(e.userMessage.contains("tools[0]"))
        let d = ResponsesRequest.httpError(status: 503, body: Data(#"{"detail":"direct routing unavailable"}"#.utf8), requestId: nil)
        #expect(d.message == "direct routing unavailable")
        #expect(d.isRetryable)
        #expect(ResponsesRequest.httpError(status: 401, body: Data(), requestId: nil).needsSignIn)
    }

    @Test func sealAddsMissingOutputsAndDropsDanglingReasoning() {
        let items: [ChatJSON] = [
            ResponsesInput.userMessage("q"),
            ["type": "reasoning", "id": "rs_1"],
            ["type": "function_call", "call_id": "c1", "name": "etl_get", "arguments": "{}"],
            ["type": "reasoning", "id": "rs_2"],
        ]
        let s = ConversationHistory.seal(items)
        #expect(s.map { $0["type"]?.string } == ["message", "reasoning", "function_call", "function_call_output"])
        #expect(s[3]["output"]?.string == ConversationHistory.interruptedOutput)
        #expect(ConversationHistory.seal(s) == s)
    }

    @Test func compactAndTrimOldTurns() {
        let img = InputImage(data: Data(repeating: 7, count: 30_000), mime: "image/png")
        var items: [ChatJSON] = []
        for i in 0..<4 {
            items.append(ResponsesInput.userMessage("q\(i)", images: [img]))
            items.append(["type": "message", "role": "assistant", "content": [["type": "output_text", "text": .string("a\(i)")]]])
        }
        let compact = ConversationHistory.compactAttachments(items, keepLastTurns: 2)
        #expect(compact[0]["content"]?[1] == ["type": "input_text", "text": "[이전에 보낸 사진]"])
        #expect(compact[6]["content"]?[1]?["type"] == "input_image")
        // 넘치면 먼저 사진을 마지막 턴에만 남기고 글로 바꾼다 → 답변 글은 모든 턴에 남는다.
        let trimmed = ConversationHistory.trim(items, maxBytes: 50_000)
        #expect(trimmed.count == 8)
        #expect(trimmed[4]["content"]?[1] == ["type": "input_text", "text": "[이전에 보낸 사진]"])
        #expect(trimmed[6]["content"]?[1]?["type"] == "input_image")
        // 그래도 넘치면 오래된 턴부터 뺀다(마지막 턴은 남긴다).
        let tight = ConversationHistory.trim(items, maxBytes: 1_000)
        #expect(tight.first?["content"]?[0]?["text"] == "q3")
        #expect(tight.count == 2)
    }
}

@Suite struct ResponsesEngineTests {
    private let tool = FunctionTool(name: "etl_digest", description: "요약", parameters: ["type": "object", "properties": [:]])

    @Test func textTurnStreamsAndCompletes() async throws {
        let t = StubTransport(streams: [.init(body: Fixtures.textStream())])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [])
        let input = [ResponsesInput.userMessage("이번 주 마감?")]
        let events = await collect(await engine.send(history: [], input: input, config: TurnConfig(model: "gpt-x", instructions: "지침"),
                                                     tools: { _ in ToolOutput(text: "") }))
        var deltas: [String] = []
        var done: [String] = []
        var activities: [String?] = []
        for e in events {
            switch e {
            case .textDelta(_, let d): deltas.append(d)
            case .textDone(let id, let text): done.append("\(id):\(text)")
            case .activity(let a): activities.append(a)
            default: break
            }
        }
        #expect(deltas == ["이번 주 ", "마감은 2개예요."])
        #expect(done == ["msg_1:이번 주 마감은 2개예요."])
        #expect(activities == [ActivityTracker.THINKING, nil, ActivityTracker.THINKING, nil])
        let r = try #require(turnResult(events))
        #expect(r.status == .completed)
        #expect(r.responseId == "resp_1")
        #expect(r.finalText == "이번 주 마감은 2개예요.")
        #expect(r.usage?.totalTokens == 150)
        #expect(r.history.map { $0["type"]?.string } == ["message", "reasoning", "message"])
        let req = t.streamRequests[0]
        #expect(req.value(forHTTPHeaderField: "Authorization") == "Bearer tok-1")
        #expect(req.url == ResponsesRequest.endpoint)
        #expect(t.streamBodies[0]["tools"] == nil)
        #expect(await engine.isBusy == false)
    }

    @Test func toolLoopExecutesAndSendsOutputsBack() async throws {
        let t = StubTransport(streams: [
            .init(body: Fixtures.toolStream(calls: [("call_1", "etl_digest", #"{"days":7}"#), ("call_2", "etl_download", #"{"id":3}"#)], namespace: "youngmin")),
            .init(body: Fixtures.textStream(id: "resp_2", msg: "msg_2", parts: ["2개예요."])),
        ])
        let auth = StubAuth(.chatGPT)
        let engine = ResponsesEngine(auth: auth, transport: t, retryDelays: [])
        let calls = CallLog()
        let events = await collect(await engine.send(history: [], input: [ResponsesInput.userMessage("q")], config: TurnConfig(model: "m", tools: [tool])) { call in
            calls.add(call)
            return ToolOutput(text: "결과-\(call.name)")
        })
        #expect(calls.names == ["etl_digest", "etl_download"])
        #expect(calls.all[0].namespace == "youngmin")
        var labels: [String] = []
        var activities: [String?] = []
        for e in events {
            if case .toolStarted(_, let l) = e { labels.append(l) }
            if case .activity(let a) = e { activities.append(a) }
        }
        #expect(labels == ["조회하는 중…", "다운로드하는 중…"])
        #expect(activities.contains("조회하는 중…") && activities.contains("다운로드하는 중…"))
        let r = try #require(turnResult(events))
        #expect(r.status == .completed)
        #expect(r.usage?.totalTokens == 300)
        #expect(t.streamBodies.count == 2)
        let second = t.streamBodies[1]["input"]!.array!
        #expect(second.map { $0["type"]?.string } == ["message", "function_call", "function_call", "function_call_output", "function_call_output"])
        #expect(second[1]["namespace"] == "youngmin")
        #expect(second[3] == ["type": "function_call_output", "call_id": "call_1", "output": "결과-etl_digest"])
        #expect(t.streamBodies[0]["tools"]?[0]?["type"] == "namespace")
        #expect(r.history.last?["type"] == "message")
    }

    @Test func lastRoundForcesAnswerAndStopsRunawayLoops() async throws {
        let t = StubTransport(streams: [.init(body: Fixtures.toolStream(calls: [("c1", "etl_get", "{}")])),
                                        .init(body: Fixtures.toolStream(calls: [("c2", "etl_get", "{}")]))])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [])
        let events = await collect(await engine.send(history: [], input: [ResponsesInput.userMessage("q")],
                                                     config: TurnConfig(model: "m", tools: [tool], maxRounds: 2)) { _ in ToolOutput(text: "r") })
        #expect(t.streamBodies[0]["tool_choice"] == "auto")
        #expect(t.streamBodies[1]["tool_choice"] == "none")
        let r = try #require(turnResult(events))
        guard case .failed(let e) = r.status else { Issue.record("expected failure"); return }
        #expect(e.kind == .tooManyRounds)
        // 짝 없는 호출에는 결과가 붙어 다음 턴에 보낼 수 있다
        let calls = r.history.filter { $0["type"] == "function_call" }.count
        let outputs = r.history.filter { $0["type"] == "function_call_output" }.count
        #expect(calls == outputs)
    }

    @Test func unauthorizedRetriesOnceWithRefreshedToken() async throws {
        let t = StubTransport(streams: [
            .init(status: 401, body: #"{"error":{"code":"subscription_sharing_invalid_user","message":"x"}}"#),
            .init(body: Fixtures.textStream()),
        ])
        let auth = StubAuth(.chatGPT)
        let engine = ResponsesEngine(auth: auth, transport: t, retryDelays: [])
        let r = try #require(turnResult(await collect(await engine.send(history: [], input: [], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") })))
        #expect(r.status == .completed)
        #expect(auth.forced == 1)
        #expect(t.streamRequests[1].value(forHTTPHeaderField: "Authorization") == "Bearer tok-refreshed")
    }

    @Test func nonRetryableHttpErrorFailsWithCodeAndRequestId() async throws {
        let t = StubTransport(streams: [
            .init(status: 400, headers: ["X-Request-Id": "req_42"], body: #"{"error":{"code":"subscription_sharing_unsupported_capability","message":"bad","param":"input[0].content[1]"}}"#),
        ])
        let engine = ResponsesEngine(auth: StubAuth(.chatGPT), transport: t, retryDelays: [0, 0])
        let r = try #require(turnResult(await collect(await engine.send(history: [], input: [ResponsesInput.userMessage("q")], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") })))
        guard case .failed(let e) = r.status else { Issue.record("expected failure"); return }
        #expect(e.status == 400)
        #expect(e.code == "subscription_sharing_unsupported_capability")
        #expect(e.param == "input[0].content[1]")
        #expect(e.requestId == "req_42")
        #expect(t.streamRequests.count == 1)
        #expect(r.history.count == 1) // 사용자 메시지는 남는다
    }

    @Test func serviceUnavailableIsRetriedBeforeStreamOpens() async throws {
        let t = StubTransport(streams: [
            .init(status: 503, body: #"{"detail":"unavailable"}"#),
            .init(body: Fixtures.textStream()),
        ])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [0])
        let r = try #require(turnResult(await collect(await engine.send(history: [], input: [], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") })))
        #expect(r.status == .completed)
        #expect(t.streamRequests.count == 2)
    }

    @Test func streamFailureReportsPlanError() async throws {
        let t = StubTransport(streams: [.init(body: Fixtures.failedStream(code: "subscription_sharing_usage_limit_exceeded", message: "limit"))])
        let engine = ResponsesEngine(auth: StubAuth(.chatGPT), transport: t, retryDelays: [])
        let r = try #require(turnResult(await collect(await engine.send(history: [], input: [], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") })))
        guard case .failed(let e) = r.status else { Issue.record("expected failure"); return }
        #expect(e.kind == .stream)
        #expect(e.userMessage.contains("사용 한도"))
    }

    @Test func streamEndingWithoutCompletionIsAnError() async throws {
        let body = sse([("response.output_text.delta", #"{"type":"response.output_text.delta","item_id":"m1","delta":"반쯤"}"#)])
        let t = StubTransport(streams: [.init(body: body)])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [])
        let r = try #require(turnResult(await collect(await engine.send(history: [], input: [], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") })))
        guard case .failed(let e) = r.status else { Issue.record("expected failure"); return }
        #expect(e.kind == .transport)
        #expect(r.history.last?["content"]?[0]?["text"] == "반쯤") // 받은 만큼은 남긴다
    }

    @Test func cancellationBeforeEngineStartsDoesNotSendRequest() async throws {
        let t = StubTransport(streams: [.init(body: Fixtures.textStream())])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [])
        let cancellation = TurnCancellation()
        cancellation.cancel() // 모델 목록 조회 중 사용자가 중단한 경우.
        let input = [ResponsesInput.userMessage("보내지 말아 줘")]
        let stream = await engine.send(history: [], input: input, config: TurnConfig(model: "m"), cancellation: cancellation) { _ in
            Issue.record("cancelled turn must not execute tools")
            return ToolOutput(text: "")
        }
        let result = try #require(turnResult(await collect(stream)))
        #expect(result.status == .interrupted)
        #expect(result.history == input)
        #expect(t.streamRequests.isEmpty)
    }

    @Test func cancellationStopsTheTurnAndKeepsPartialText() async throws {
        let body = sse([
            ("response.created", #"{"type":"response.created","response":{"id":"resp_i","status":"in_progress","output":[]}}"#),
            ("response.output_text.delta", #"{"type":"response.output_text.delta","item_id":"m1","delta":"생각해 보면"}"#),
        ])
        let t = StubTransport(streams: [.init(body: body, hang: true)])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [])
        let cancellation = TurnCancellation()
        let stream = await engine.send(history: [], input: [ResponsesInput.userMessage("q")], config: TurnConfig(model: "m"),
                                       cancellation: cancellation) { _ in ToolOutput(text: "") }
        var events: [ResponsesEvent] = []
        for await e in stream {
            events.append(e)
            if case .textDelta = e { cancellation.cancel() }
        }
        let r = try #require(turnResult(events))
        #expect(r.status == .interrupted)
        #expect(r.history.map { $0["role"]?.string } == ["user", "assistant"])
        #expect(await engine.isBusy == false)
    }

    @Test func interruptDuringToolExecution() async throws {
        let t = StubTransport(streams: [.init(body: Fixtures.toolStream(calls: [("c1", "etl_get", "{}"), ("c2", "etl_get", "{}")]))])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [])
        let stream = await engine.send(history: [], input: [ResponsesInput.userMessage("q")], config: TurnConfig(model: "m", tools: [tool])) { _ in
            await engine.interrupt()
            return ToolOutput(text: "첫 결과")
        }
        let r = try #require(turnResult(await collect(stream)))
        #expect(r.status == .interrupted)
        let outputs = r.history.filter { $0["type"] == "function_call_output" }.map { $0["output"]?.string }
        #expect(Set(outputs) == ["첫 결과", ConversationHistory.interruptedOutput])
        #expect(outputs.count == 2)
        #expect(t.streamRequests.count == 1)
    }

    @Test func busyEngineRejectsSecondTurn() async throws {
        let t = StubTransport(streams: [.init(body: Fixtures.textStream(), hang: true)])
        let engine = ResponsesEngine(auth: StubAuth(), transport: t, retryDelays: [])
        let first = await engine.send(history: [], input: [], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") }
        let second = try #require(turnResult(await collect(await engine.send(history: [], input: [], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") })))
        guard case .failed = second.status else { Issue.record("expected busy failure"); return }
        await engine.interrupt()
        let r = try #require(turnResult(await collect(first)))
        // 끝까지 받은 뒤 멈춘 경우: 완료로 끝날 수도, 중단으로 끝날 수도 있다
        #expect(r.status == .completed || r.status == .interrupted)
    }

    @Test func authFailureEndsTurn() async throws {
        let engine = ResponsesEngine(auth: APIKeyAuthProvider(secrets: InMemoryChatSecretStore()), transport: StubTransport(), retryDelays: [])
        let r = try #require(turnResult(await collect(await engine.send(history: [], input: [], config: TurnConfig(model: "m")) { _ in ToolOutput(text: "") })))
        guard case .failed(let e) = r.status else { Issue.record("expected failure"); return }
        #expect(e.kind == .auth)
        #expect(e.userMessage == "먼저 로그인해야 해요")
    }

    @Test func modelListClientParsesResponse() async throws {
        let t = StubTransport(data: { r in
            #expect(r.url == ModelListClient.endpoint)
            #expect(r.value(forHTTPHeaderField: "Authorization") == "Bearer tok-1")
            return (200, Data(#"{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"}]}"#.utf8))
        })
        let list = try await ModelListClient.fetch(auth: StubAuth(.chatGPT), transport: t)
        #expect(list.map(\.model) == ["gpt-6.1-sol"])
    }
}

final class CallLog: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [ToolCall] = []
    func add(_ c: ToolCall) { lock.withLock { calls.append(c) } }
    var all: [ToolCall] { lock.withLock { calls } }
    var names: [String] { all.map(\.name) }
}
