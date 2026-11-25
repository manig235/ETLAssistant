#if YOUNGMIN_NATIVE_CODEX
import ChatKit
import ETLKit
import Foundation

/// Only the application target links Rust. The pure Swift package stays portable.
actor NativeCodexTransport: CodexTransport {
    nonisolated let messages: AsyncStream<ChatJSON>
    private let continuation: AsyncStream<ChatJSON>.Continuation
    private var handle: UInt64 = 0
    private var polling: Task<Void, Never>?

    init() {
        let (stream, continuation) = AsyncStream<ChatJSON>.makeStream()
        messages = stream
        self.continuation = continuation
    }

    func start() async throws {
        guard handle == 0 else { return }
        let home = AppPaths.directory("codex").path
        handle = home.withCString { home in AppPaths.root.path.withCString { ym_codex_start(home, $0) } }
        guard handle != 0 else { throw CodexError("앱 내부 Codex를 시작하지 못했어요") }
        polling = Task { [weak self] in
            while !Task.isCancelled {
                await self?.drain()
                do { try await Task.sleep(nanoseconds: 20_000_000) } catch { return }
            }
        }
    }

    private func drain() {
        guard handle != 0 else { return }
        // Limit each batch so stop/send can run even during a fast stream.
        for _ in 0..<128 {
            guard let pointer = ym_codex_poll(handle) else { return }
            let text = String(cString: pointer)
            ym_codex_free_string(pointer)
            if let message = try? ChatJSON(parsing: text) { continuation.yield(message) }
        }
    }

    func send(_ message: ChatJSON) async throws {
        guard handle != 0 else { throw CodexError("Codex 연결이 종료됐어요") }
        let result = message.jsonString.withCString { ym_codex_send(handle, $0) }
        guard result == 0 else { throw CodexError(result == 1 ? "Codex가 요청을 처리하는 중이에요. 잠시 뒤 다시 시도해 주세요" : "Codex 연결이 종료됐어요") }
    }

    func stop() async {
        polling?.cancel(); polling = nil
        if handle != 0 { ym_codex_stop(handle); handle = 0 }
        continuation.finish()
    }
}
#endif
