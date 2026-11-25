import Foundation

/// 전송 준비부터 스트리밍까지 한 턴의 중단 요청을 기억한다.
/// 엔진이 시작하기 전에 눌러도 요청이 사라지지 않으며, 턴마다 새 인스턴스를 쓴다.
public final class TurnCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var handler: (@Sendable () -> Void)?

    public init() {}

    public var isCancelled: Bool { lock.withLock { cancelled } }

    public func cancel() {
        let callback = lock.withLock { () -> (@Sendable () -> Void)? in
            cancelled = true
            let callback = handler
            handler = nil
            return callback
        }
        callback?()
    }

    func onCancel(_ callback: @escaping @Sendable () -> Void) {
        let runNow = lock.withLock {
            if cancelled { return true }
            handler = callback
            return false
        }
        if runNow { callback() }
    }
}
