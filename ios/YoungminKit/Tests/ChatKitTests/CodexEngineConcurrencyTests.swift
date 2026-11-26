import Foundation
import Testing
@testable import ChatKit

/// 가짜 엔진: 대화마다 다른 스레드를 만들고, 두 턴이 모두 시작되면 두 스레드의 이벤트를 섞어 보낸다.
/// 두 번째 스레드는 앱 도구를 한 번 부르고, 그 결과를 받은 뒤에야 끝난다. 스레드 없는 서버 요청도 하나 끼운다.
private actor TwoThreadTransport: CodexTransport {
    nonisolated let messages: AsyncStream<ChatJSON>
    private let output: AsyncStream<ChatJSON>.Continuation
    private var sent: [ChatJSON] = []
    private var threads = 0
    private(set) var started: [String] = []

    init() {
        let (stream, output) = AsyncStream<ChatJSON>.makeStream()
        self.messages = stream; self.output = output
    }

    func start() async throws {}
    func stop() async { output.finish() }
    func requests() -> [ChatJSON] { sent }

    func send(_ m: ChatJSON) async throws {
        sent.append(m)
        let id = m["id"] ?? .null
        switch m["method"]?.string {
        case "thread/start":
            threads += 1
            output.yield(["id": id, "result": ["thread": ["id": .string("thread-\(threads)")]]])
        case "turn/start":
            let thread = m["params"]?["threadId"]?.string ?? ""
            output.yield(["id": id, "result": ["turn": ["id": .string("turn-\(thread)")]]])
            started.append(thread)
            if started.count == 2 { interleave() }
        default:
            // 두 번째 스레드의 도구 결과를 받으면 그 턴을 끝낸다.
            if m["id"]?.int == 91, m["result"] != nil { complete(started[1]) }
        }
    }

    private func delta(_ thread: String) -> ChatJSON {
        ["method": "item/agentMessage/delta", "params": ["threadId": .string(thread), "turnId": .string("turn-\(thread)"),
                                                          "itemId": .string("msg-\(thread)"), "delta": .string("답-\(thread)")]]
    }

    private func interleave() {
        let first = started[0], second = started[1]
        output.yield(["id": 99, "method": "item/tool/requestUserInput", "params": [:]])
        output.yield(delta(second))
        output.yield(["id": 91, "method": "item/tool/call", "params": ["threadId": .string(second), "turnId": .string("turn-\(second)"),
                                                                       "callId": "call-91", "tool": "etl_courses", "arguments": [:]]])
        output.yield(delta(first))
        complete(first)
    }

    private func complete(_ thread: String) {
        output.yield(["method": "item/completed", "params": ["threadId": .string(thread), "turnId": .string("turn-\(thread)"),
                                                             "item": ["type": "agentMessage", "id": .string("msg-\(thread)"), "text": .string("답-\(thread)")]]])
        output.yield(["method": "turn/completed", "params": ["threadId": .string(thread), "turn": ["id": .string("turn-\(thread)"), "status": "completed"]]])
    }
}

private final class ToolCalls: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [String: Int] = [:]
    func add(_ who: String) { lock.withLock { calls[who, default: 0] += 1 } }
    func count(_ who: String) -> Int { lock.withLock { calls[who] ?? 0 } }
}

@Suite("Embedded Codex concurrent turns", .timeLimit(.minutes(1)))
struct CodexEngineConcurrencyTests {
    private struct Outcome { let thread: String?; let text: String; let deltas: String; let status: TurnStatus? }

    private static func collect(_ stream: AsyncStream<ResponsesEvent>) async -> Outcome {
        var deltas = ""
        var result: TurnResult?
        for await event in stream {
            switch event {
            case .textDelta(_, let d): deltas += d
            case .turnEnded(let r): result = r
            default: break
            }
        }
        return Outcome(thread: result?.history.first?["thread_id"]?.string, text: result?.finalText ?? "", deltas: deltas, status: result?.status)
    }

    @Test func twoConversationsAnswerAtOnceWithoutMixing() async throws {
        let transport = TwoThreadTransport()
        let client = CodexClient(transport: transport)
        let engine = CodexEngine(client: client)
        let calls = ToolCalls()
        let config = TurnConfig(model: "gpt-5.5", tools: [FunctionTool(name: "etl_courses", description: "courses", parameters: ["type": "object"])])
        // 대화 A가 답하는 중에 대화 B에서도 보낸다. 엔진은 거절하지 않는다.
        let streamA = await engine.send(history: [], input: [ResponsesInput.userMessage("A")], config: config, tools: { _ in
            calls.add("A"); return ToolOutput(text: "A 도구")
        })
        let streamB = await engine.send(history: [], input: [ResponsesInput.userMessage("B")], config: config, tools: { _ in
            calls.add("B"); return ToolOutput(text: "B 도구")
        })
        async let a = Self.collect(streamA)
        async let b = Self.collect(streamB)
        let (ra, rb) = await (a, b)

        #expect(ra.status == .completed)
        #expect(rb.status == .completed)
        let threadA = try #require(ra.thread)
        let threadB = try #require(rb.thread)
        #expect(threadA != threadB)
        // 각 대화는 자기 스레드의 글만 받는다.
        #expect(ra.text == "답-\(threadA)")
        #expect(rb.text == "답-\(threadB)")
        #expect(ra.deltas == "답-\(threadA)")
        #expect(rb.deltas == "답-\(threadB)")
        // 도구 호출은 그 호출을 낸 스레드의 대화 실행기로만 간다.
        let second = await transport.started[1]
        let callerIsA = threadA == second
        #expect(calls.count("A") == (callerIsA ? 1 : 0))
        #expect(calls.count("B") == (callerIsA ? 0 : 1))
        let sent = await transport.requests()
        #expect(sent.first { $0["id"]?.int == 91 }?["result"]?["contentItems"]?[0]?["text"]?.string == (callerIsA ? "A 도구" : "B 도구"))
        // 스레드 없는 서버 요청은 한 번만 거절한다(두 턴이 함께 봐도).
        #expect(sent.filter { $0["id"]?.int == 99 && $0["error"] != nil }.count == 1)
        await client.shutdown()
    }
}
