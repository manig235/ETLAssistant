import Foundation

/// The app transports these messages through memory to the embedded Rust engine.
public protocol CodexTransport: Sendable {
    var messages: AsyncStream<ChatJSON> { get }
    func start() async throws
    func send(_ message: ChatJSON) async throws
    func stop() async
}

public struct CodexError: Error, Sendable, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
}

/// JSON-RPC request routing shared by authentication, model selection and chat.
public actor CodexClient {
    private let transport: any CodexTransport
    private var receiving: Task<Void, Never>?
    private var connected = false
    private var starting: Task<Void, Error>?
    private var pending: [String: CheckedContinuation<ChatJSON, Error>] = [:]
    private var deadlines: [String: Task<Void, Never>] = [:]
    private var subscribers: [UUID: AsyncStream<ChatJSON>.Continuation] = [:]

    public init(transport: any CodexTransport) { self.transport = transport }

    public func connect() async throws {
        guard !connected else { return }
        if let starting { return try await starting.value }
        let messages = transport.messages
        receiving = Task { [weak self] in
            for await message in messages { await self?.receive(message) }
            await self?.failAll(CodexError("Codex 연결이 종료됐어요"))
        }
        let transport = self.transport
        let task = Task { try await transport.start() }
        starting = task
        do { try await task.value; connected = true; starting = nil }
        catch { starting = nil; receiving?.cancel(); receiving = nil; throw error }
    }

    public func events() -> AsyncStream<ChatJSON> {
        subscribe().1
    }

    public func subscribe() -> (UUID, AsyncStream<ChatJSON>) {
        let id = UUID()
        let (stream, continuation) = AsyncStream<ChatJSON>.makeStream()
        subscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in Task { await self?.unsubscribe(id) } }
        return (id, stream)
    }

    private func unsubscribe(_ id: UUID) { subscribers.removeValue(forKey: id) }
    public func cancelEvents(_ id: UUID) { subscribers.removeValue(forKey: id)?.finish() }

    public func request(_ method: String, _ params: ChatJSON = [:], timeout: Double = 90) async throws -> ChatJSON {
        try Task.checkCancellation()
        try await connect()
        let id = UUID().uuidString
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pending[id] = continuation
                deadlines[id] = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: UInt64(max(0.001, timeout) * 1_000_000_000)) }
                    catch { return }
                    await self?.fail(id, CodexError("Codex 응답을 기다리다 시간이 지났어요"))
                }
                Task { [weak self] in await self?.submit(id, method, params) }
            }
        } onCancel: { Task { await self.fail(id, CancellationError()) } }
    }

    private func submit(_ id: String, _ method: String, _ params: ChatJSON) async {
        guard pending[id] != nil else { return }
        do { try await transport.send(["id": .string(id), "method": .string(method), "params": params]) }
        catch { fail(id, error) }
    }

    public func respond(id: ChatJSON, result: ChatJSON) async throws {
        try await transport.send(["id": id, "result": result])
    }

    public func reject(id: ChatJSON, message: String) async throws {
        try await transport.send(["id": id, "error": ["code": -32601, "message": .string(message)]])
    }

    private func receive(_ message: ChatJSON) {
        if let id = message["id"]?.string, message["method"] == nil, let continuation = pending.removeValue(forKey: id) {
            deadlines.removeValue(forKey: id)?.cancel()
            if let error = message["error"] {
                continuation.resume(throwing: CodexError(error["message"]?.string ?? "Codex 요청에 실패했어요"))
            } else { continuation.resume(returning: message["result"] ?? .null) }
            return
        }
        if message["method"]?.string == "youngmin/fatal" {
            failAll(CodexError(message["params"]?["message"]?.string ?? "Codex 실행이 중단됐어요"))
        }
        for subscriber in subscribers.values { subscriber.yield(message) }
    }

    private func fail(_ id: String, _ error: Error) {
        deadlines.removeValue(forKey: id)?.cancel()
        pending.removeValue(forKey: id)?.resume(throwing: error)
    }

    private func failAll(_ error: Error) {
        let continuations = Array(pending.values)
        pending.removeAll()
        deadlines.values.forEach { $0.cancel() }
        deadlines.removeAll()
        continuations.forEach { $0.resume(throwing: error) }
    }

    public func shutdown() async {
        receiving?.cancel(); receiving = nil
        starting?.cancel(); starting = nil
        connected = false
        failAll(CodexError("Codex 연결이 종료됐어요"))
        subscribers.values.forEach { $0.finish() }
        subscribers.removeAll()
        await transport.stop()
    }
}
