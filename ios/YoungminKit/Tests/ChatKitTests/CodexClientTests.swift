import Foundation
import Testing
@testable import ChatKit

private actor MemoryCodexTransport: CodexTransport {
    nonisolated let messages: AsyncStream<ChatJSON>
    private let output: AsyncStream<ChatJSON>.Continuation
    private var requests: [ChatJSON] = []
    private let echo: Bool
    init(echo: Bool = true) {
        let (stream, output) = AsyncStream<ChatJSON>.makeStream()
        messages = stream; self.output = output; self.echo = echo
    }
    func start() async throws {}
    func stop() async { output.finish() }
    func send(_ message: ChatJSON) async throws {
        requests.append(message)
        if echo, let id = message["id"], message["method"] != nil {
            output.yield(["id": id, "result": message["params"] ?? .null])
        }
    }
    func emit(_ message: ChatJSON) { output.yield(message) }
    func count() -> Int { requests.count }
    func last() -> ChatJSON? { requests.last }
}

@Suite("Embedded Codex JSON-RPC")
struct CodexClientTests {
    @Test func concurrentRequestsKeepTheirResponses() async throws {
        let transport = MemoryCodexTransport()
        let client = CodexClient(transport: transport)
        async let a = client.request("account/read", ["value": "account"])
        async let b = client.request("model/list", ["value": "models"])
        let (account, models) = try await (a, b)
        #expect(account["value"]?.string == "account")
        #expect(models["value"]?.string == "models")
        await client.shutdown()
    }

    @Test func timeoutDoesNotWaitForTheEngine() async throws {
        let client = CodexClient(transport: MemoryCodexTransport(echo: false))
        do {
            _ = try await client.request("account/read", timeout: 0.01)
            Issue.record("Missing timeout")
        } catch let error as CodexError { #expect(error.message.contains("시간")) }
        await client.shutdown()
    }

    @Test func cancelPendingRequest() async throws {
        let transport = MemoryCodexTransport(echo: false)
        let client = CodexClient(transport: transport)
        let task = Task { try await client.request("account/read") }
        while await transport.count() == 0 { await Task.yield() }
        task.cancel()
        do { _ = try await task.value; Issue.record("Missing cancellation") }
        catch { #expect(error is CancellationError) }
        await client.shutdown()
    }

    @Test func serverRequestKeepsItsNumericID() async throws {
        let transport = MemoryCodexTransport(echo: false)
        let client = CodexClient(transport: transport)
        let stream = await client.events()
        try await client.connect()
        await transport.emit(["id": 41, "method": "item/tool/call", "params": ["name": "etl_courses"]])
        var iterator = stream.makeAsyncIterator()
        let event = await iterator.next()
        #expect(event?["id"]?.int == 41)
        try await client.respond(id: 41, result: ["success": true])
        let reply = await transport.last()
        #expect(reply?["id"]?.int == 41)
        #expect(reply?["result"]?["success"]?.bool == true)
        await client.shutdown()
    }

    @Test func fatalFailureFinishesWaitingRequests() async throws {
        let transport = MemoryCodexTransport(echo: false)
        let client = CodexClient(transport: transport)
        let task = Task { try await client.request("model/list") }
        while await transport.count() == 0 { await Task.yield() }
        await transport.emit(["method": "youngmin/fatal", "params": ["message": "engine stopped"]])
        do { _ = try await task.value; Issue.record("Missing engine failure") }
        catch let error as CodexError { #expect(error.message == "engine stopped") }
        await client.shutdown()
    }
}
