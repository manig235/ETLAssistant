import Foundation
import Testing
@testable import ChatKit

private actor TurnTransport: CodexTransport {
    enum Scenario { case text, tool, waitForInterrupt, unsupported }
    nonisolated let messages: AsyncStream<ChatJSON>
    private let output: AsyncStream<ChatJSON>.Continuation
    private let scenario: Scenario
    private var sent: [ChatJSON] = []
    init(_ scenario: Scenario = .text) {
        let (stream, output) = AsyncStream<ChatJSON>.makeStream()
        self.messages = stream; self.output = output; self.scenario = scenario
    }
    func start() async throws {}
    func stop() async { output.finish() }
    func requests() -> [ChatJSON] { sent }
    func send(_ m: ChatJSON) async throws {
        sent.append(m)
        let id = m["id"] ?? .null
        switch m["method"]?.string {
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
