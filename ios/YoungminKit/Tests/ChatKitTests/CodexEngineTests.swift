import Foundation
import Testing
@testable import ChatKit

private actor TurnTransport: CodexTransport {
    enum Scenario { case text, tool, waitForInterrupt, unsupported }
    nonisolated let messages: AsyncStream<ChatJSON>
    private let output: AsyncStream<ChatJSON>.Continuation
    private let scenario: Scenario
    /// JSON-RPC error returned for thread/resume (the real engine failed this way without its state DB).
    private let resumeError: ChatJSON?
    private var sent: [ChatJSON] = []
    init(_ scenario: Scenario = .text, resumeError: ChatJSON? = nil) {
        let (stream, output) = AsyncStream<ChatJSON>.makeStream()
        self.messages = stream; self.output = output; self.scenario = scenario; self.resumeError = resumeError
    }
    func start() async throws {}
    func stop() async { output.finish() }
    func requests() -> [ChatJSON] { sent }
    func send(_ m: ChatJSON) async throws {
        sent.append(m)
        let id = m["id"] ?? .null
        switch m["method"]?.string {
        case "thread/resume" where resumeError != nil: output.yield(["id": id, "error": resumeError!])
        case "thread/start", "thread/resume": output.yield(["id": id, "result": ["thread": ["id": "native-thread"]]])
        case "turn/start":
            output.yield(["id": id, "result": ["turn": ["id": "turn-1"]]])
            switch scenario {
            case .text: complete()
            case .tool:
                output.yield(["id": 73, "method": "item/tool/call", "params": ["threadId": "native-thread", "turnId": "turn-1", "callId": "call-1", "tool": "etl_courses", "arguments": ["term": "2026"]]])
            case .unsupported:
                output.yield(["id": 74, "method": "item/commandExecution/requestApproval", "params": ["threadId": "native-thread", "turnId": "turn-1"]])
            case .waitForInterrupt: break
            }
        case "turn/interrupt": output.yield(["id": id, "result": [:]])
        default:
            if m["result"] != nil || m["error"] != nil { complete() }
        }
    }
    private func complete() {
        output.yield(["method": "item/agentMessage/delta", "params": ["threadId": "native-thread", "turnId": "turn-1", "itemId": "reply", "delta": "안녕하세요"]])
        output.yield(["method": "item/completed", "params": ["threadId": "native-thread", "turnId": "turn-1", "item": ["type": "agentMessage", "id": "reply", "text": "안녕하세요"]]])
        output.yield(["method": "turn/completed", "params": ["threadId": "native-thread", "turn": ["id": "turn-1", "status": "completed"]]])
    }
}

@Suite("Embedded Codex turns", .timeLimit(.minutes(1)))
struct CodexEngineTests {
    @Test func apiKeyTurnsAreImportedWhenReturningToNativeChat() async throws {
        let transport = TurnTransport()
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        let history: [ChatJSON] = [
            ["type": .string(CodexEngine.threadMarker), "thread_id": "native-thread"],
            ResponsesInput.userMessage("API 키로 나눈 대화"),
            ["type": "message", "role": "assistant", "content": [["type": "output_text", "text": "이전 답변"]]],
        ]
        for await event in await engine.send(history: history, input: [ResponsesInput.userMessage("이어서")], config: TurnConfig(model: "gpt-5.5"), tools: { _ in ToolOutput(text: "") }) {
            if case .turnEnded(let result) = event { #expect(result.history.count == 1) }
        }
        let requests = await transport.requests()
        #expect(requests.contains { $0["method"]?.string == "thread/resume" })
        let turn = requests.first { $0["method"]?.string == "turn/start" }
        #expect(turn?["params"]?["input"]?[0]?["text"]?.string?.contains("API 키로 나눈 대화") == true)
        await client.shutdown()
    }

    @Test func streamAndResumeNativeConversation() async throws {
        let transport = TurnTransport()
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        var history: [ChatJSON] = []
        var text = ""
        for await event in await engine.send(history: [], input: [ResponsesInput.userMessage("안녕")], config: TurnConfig(model: "gpt-5.5"), tools: { _ in ToolOutput(text: "") }) {
            if case .turnEnded(let result) = event { history = result.history; text = result.finalText; #expect(result.status == .completed) }
        }
        #expect(text == "안녕하세요")
        #expect(history.first?["thread_id"]?.string == "native-thread")
        for await _ in await engine.send(history: history, input: [ResponsesInput.userMessage("이어서")], config: TurnConfig(model: "gpt-5.5"), tools: { _ in ToolOutput(text: "") }) {}
        let requests = await transport.requests()
        #expect(requests.contains { $0["method"]?.string == "thread/resume" })
        await client.shutdown()
    }

    @Test func rejectedResumeContinuesInANewThreadOnce() async throws {
        let transport = TurnTransport(resumeError: ["code": -32600, "message": "thread history is unavailable"])
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        let old = "019a0000-0000-7000-8000-000000000001"
        var notices: [String] = []
        var result: TurnResult?
        for await event in await engine.send(history: [["type": .string(CodexEngine.threadMarker), "thread_id": .string(old)]],
                                             input: [ResponsesInput.userMessage("두 번째 질문")], config: TurnConfig(model: "gpt-5.5"), tools: { _ in ToolOutput(text: "") }) {
            if case .notice(let text) = event { notices.append(text) }
            if case .turnEnded(let r) = event { result = r }
        }
        #expect(result?.status == .completed)
        #expect(notices == [CodexEngine.contextLost])
        // The next message resumes the new thread; the old ID is kept for conversation deletion.
        #expect(result?.history.compactMap { $0["thread_id"]?.string } == ["native-thread", old])
        let methods = (await transport.requests()).compactMap { $0["method"]?.string }
        #expect(methods.prefix(3) == ["thread/resume", "thread/start", "turn/start"])
        await client.shutdown()
    }

    @Test func closedEngineDuringResumeKeepsTheThread() async throws {
        let transport = TurnTransport(resumeError: ["code": -32000, "message": "Codex 연결이 종료됐어요"])
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        let marker: ChatJSON = ["type": .string(CodexEngine.threadMarker), "thread_id": "native-thread"]
        var result: TurnResult?
        for await event in await engine.send(history: [marker], input: [ResponsesInput.userMessage("이어서")], config: TurnConfig(model: "gpt-5.5"), tools: { _ in ToolOutput(text: "") }) {
            if case .notice = event { Issue.record("A closed connection must not replace the thread") }
            if case .turnEnded(let r) = event { result = r }
        }
        if case .some(.failed) = result?.status {} else { Issue.record("Expected a failed turn") }
        #expect(result?.history.first?["thread_id"]?.string == "native-thread")
        #expect(!(await transport.requests()).contains { $0["method"]?.string == "thread/start" })
        await client.shutdown()
    }

    @Test func appToolReceivesArgumentsAndReturnsItsResult() async throws {
        let transport = TurnTransport(.tool)
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        let definition = FunctionTool(name: "etl_courses", description: "courses", parameters: ["type": "object"])
        for await _ in await engine.send(history: [], input: [ResponsesInput.userMessage("과목")], config: TurnConfig(model: "gpt-5.5", tools: [definition]), tools: { call in
            #expect(call.name == "etl_courses")
            #expect(call.argumentsJSON?["term"]?.string == "2026")
            return ToolOutput(text: "수강 과목 두 개")
        }) {}
        let requests = await transport.requests()
        let response = requests.first { $0["id"]?.int == 73 }
        #expect(response?["result"]?["contentItems"]?[0]?["text"]?.string == "수강 과목 두 개")
        #expect(response?["result"]?["success"]?.bool == true)
        await client.shutdown()
    }

    @Test func unsupportedNativeActionsAreRejected() async throws {
        let transport = TurnTransport(.unsupported)
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        for await _ in await engine.send(history: [], input: [ResponsesInput.userMessage("안녕")], config: TurnConfig(model: "gpt-5.5"), tools: { _ in
            Issue.record("Unsupported request reached app tools")
            return ToolOutput(text: "")
        }) {}
        let requests = await transport.requests()
        #expect(requests.first { $0["id"]?.int == 74 }?["error"] != nil)
        await client.shutdown()
    }

    @Test func cancellationInterruptsTheAcceptedTurn() async throws {
        let transport = TurnTransport(.waitForInterrupt)
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        let cancellation = TurnCancellation()
        let stream = await engine.send(history: [], input: [ResponsesInput.userMessage("안녕")], config: TurnConfig(model: "gpt-5.5"), cancellation: cancellation, tools: { _ in ToolOutput(text: "") })
        while !(await transport.requests()).contains(where: { $0["method"]?.string == "turn/start" }) { await Task.yield() }
        cancellation.cancel()
        for await event in stream {
            if case .turnEnded(let result) = event { #expect(result.status == .interrupted) }
        }
        #expect((await transport.requests()).contains { $0["method"]?.string == "turn/interrupt" })
        await client.shutdown()
    }
}
