import Foundation
import ETLKit

/// 비서 도구가 앱 데이터(내 마감·시간표)를 바꾸기 전에 받는 승인(Kotlin `WriteApprovalGate`).
///
/// 승인 하나는 **도구 호출 하나**와 **지금 화면에 붙은 채팅(host) 하나**에만 묶인다. 모델이 쓴 "승인했다"는 글은 아무 효력이 없고,
/// 화면이 `decide`로 알린 버튼만 센다.
///
/// - 화면이 없으면(`attachHost` 전, `detachHost` 뒤) 쓰기는 바로 거절된다.
/// - 새 host가 붙으면 이전 host의 대기 요청은 모두 거절된다. 다른 host의 토큰, 지어낸 요청 ID, 같은 요청에 두 번 답하기는 승인이 되지 않는다.
/// - [timeout](기본 90초) 안에 답이 없거나, 기다리는 Task가 취소되거나, `cancelAll`이 불리면 거절된다.
/// - 승인된 뒤에도 실행 직전에 같은 잠금 안에서 host·요청·시간을 다시 확인하고, 그 안에서 짧은 로컬 쓰기([action])를 한다.
///
/// Kotlin은 스레드를 막고 기다리지만 Swift에서는 `execute`가 `async`다. 앱의 `WriteApprovalSource` 계약(동기 메서드)을
/// 그대로 따르도록 actor 대신 잠금으로 지키는 `final class`로 만들었다(actor로는 동기 요구 사항을 채울 수 없다).
public final class WriteApprovalGate: @unchecked Sendable {
    /// 화면에 띄울 승인 요청(Kotlin `Request`). 앱의 `WriteApprovalRequest`와 필드가 같다.
    public struct Request: Identifiable, Hashable, Sendable {
        public let id: String
        public let operation: String
        public let details: String
        public let destructive: Bool
        /// 요청을 낸 대화([ToolOrigin.chatId], 모르면 nil). 대화마다 따로 답하므로 확인 창은 그 대화를 볼 때만 뜬다.
        public let origin: String?
        public init(id: String, operation: String, details: String, destructive: Bool, origin: String? = nil) {
            self.id = id
            self.operation = operation
            self.details = details
            self.destructive = destructive
            self.origin = origin
        }
    }

    /// 거절됨(Kotlin `Denied`).
    public struct Denied: Error, LocalizedError, CustomStringConvertible, Sendable {
        public let message = "변경을 승인하지 않아 실행하지 않았어요. 다시 실행하려면 사용자가 새로 요청해야 해요."
        public init() {}
        public var errorDescription: String? { message }
        public var description: String { message }
    }

    /// 기본 대기 시간(초).
    public static let DEFAULT_TIMEOUT: TimeInterval = 90

    private final class Waiting: @unchecked Sendable {
        let request: Request
        let host: String
        let deadline: UInt64
        var done = false
        var approved = false
        var continuation: CheckedContinuation<Void, Never>?
        init(_ request: Request, _ host: String, _ deadline: UInt64) {
            self.request = request
            self.host = host
            self.deadline = deadline
        }
    }

    public let timeout: TimeInterval
    private let lock = NSRecursiveLock()
    private var host: String?
    private var waiting: [String: Waiting] = [:]
    private var order: [String] = []
    private var changeHandler: (@Sendable () -> Void)?

    public init(timeout: TimeInterval = WriteApprovalGate.DEFAULT_TIMEOUT) {
        self.timeout = timeout
    }

    private var timeoutNanos: UInt64 { UInt64(max(0, timeout) * 1_000_000_000) }
    private static func now() -> UInt64 { DispatchTime.now().uptimeNanoseconds }

    private func locked<R>(_ body: () throws -> R) rethrows -> R {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    private func changed() {
        let h = locked { changeHandler }
        h?()
    }

    /// 대기 목록이 바뀌면 불릴 처리기(Kotlin `listeners`; 앱 계약에 맞춰 하나만). 아무 스레드에서나 불린다.
    public func setChangeHandler(_ handler: (@Sendable () -> Void)?) {
        locked { changeHandler = handler }
    }

    /// 채팅 화면이 보이면 부른다. 이전 host의 대기 요청은 모두 거절된다.
    public func attachHost() -> String {
        let id = UUID().uuidString
        locked {
            cancelLocked()
            host = id
        }
        changed()
        return id
    }

    /// 채팅 화면이 사라지면 부른다. 지금 host가 아니면(옛 화면) 아무것도 하지 않는다.
    public func detachHost(_ hostId: String) {
        let detached: Bool = locked {
            guard host == hostId else { return false }
            host = nil
            cancelLocked()
            return true
        }
        if detached { changed() }
    }

    /// 오류로 턴이 끝나거나 사용자가 멈추면 대기 요청을 모두 거절한다.
    public func cancelAll() {
        locked { cancelLocked() }
        changed()
    }

    /// 대화 하나의 답변이 끝나거나 멈춤: 그 대화의 요청과 대화를 모르는 요청만 거절한다(다른 대화의 확인은 그대로).
    public func cancel(origin: String?) {
        let any: Bool = locked {
            let hit = waiting.values.filter { !$0.done && ($0.request.origin == nil || $0.request.origin == origin) }
            for w in hit {
                w.approved = false
                resolveLocked(w)
                removeLocked(w.request.id)
            }
            return !hit.isEmpty
        }
        if any { changed() }
    }

    private func cancelLocked() {
        for w in waiting.values {
            w.approved = false
            resolveLocked(w)
        }
        waiting.removeAll()
        order.removeAll()
    }

    private func resolveLocked(_ w: Waiting) {
        if w.done { return }
        w.done = true
        w.continuation?.resume()
        w.continuation = nil
    }

    private func removeLocked(_ id: String) {
        if waiting.removeValue(forKey: id) != nil { order.removeAll { $0 == id } }
    }

    /// 아직 답하지 않은 요청(들어온 순서).
    public func pending() -> [Request] {
        locked { order.compactMap { waiting[$0] }.filter { !$0.done }.map(\.request) }
    }

    /// 화면의 버튼. 오래된 창, 다른 host의 토큰, 이미 답한 요청은 승인이 되지 않는다(false).
    @discardableResult
    public func decide(hostId: String, requestId: String, approved: Bool) -> Bool {
        let accepted: Bool = locked {
            guard let w = waiting[requestId], host == hostId, w.host == hostId, !w.done else { return false }
            w.approved = approved && Self.now() < w.deadline
            resolveLocked(w)
            return true
        }
        if accepted { changed() }
        return accepted
    }

    /// 시간 초과·취소: 승인 없이 끝낸다.
    private func expire(_ w: Waiting) {
        locked {
            w.approved = false
            resolveLocked(w)
        }
    }

    /// 승인을 받으면 [action]을 실행한다. 승인된 미리보기([details])와 이 클로저는 앱 메모리 안에서만 묶이고 도구 인자로 오가지 않는다.
    /// 거절·시간 초과·취소·host 변경이면 [Denied]를 던지고 [action]은 실행하지 않는다.
    public func execute<T>(_ operation: String, _ details: String, destructive: Bool = false, _ action: () throws -> T) async throws -> T {
        let origin = ToolOrigin.chatId
        let w: Waiting = try locked {
            guard let owner = host else { throw Denied() }
            let request = Request(id: UUID().uuidString, operation: operation, details: details, destructive: destructive, origin: origin)
            let w = Waiting(request, owner, Self.now() + timeoutNanos)
            waiting[request.id] = w
            order.append(request.id)
            return w
        }
        changed()
        defer {
            locked { removeLocked(w.request.id) }
            changed()
        }
        let nanos = timeoutNanos
        let timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: nanos)
            self?.expire(w)
        }
        defer { timer.cancel() }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                locked {
                    if w.done { c.resume() } else { w.continuation = c }
                }
            }
        } onCancel: {
            self.expire(w)
        }
        // host 분리·취소와 짧은 로컬 쓰기를 같은 잠금 안에서 처리한다. 분리된 채팅의 승인은 살아남지 못한다.
        return try locked {
            guard w.approved, host == w.host, waiting[w.request.id] === w, Self.now() < w.deadline else { throw Denied() }
            removeLocked(w.request.id)
            return try action()
        }
    }
}

/// 앱 전체에서 쓰는 승인 게이트(Kotlin `object AiWriteApproval`). 화면만 답할 수 있고 도구는 요청만 만든다.
public enum AiWriteApproval {
    public static let gate = WriteApprovalGate()

    public static func attachHost() -> String { gate.attachHost() }
    public static func detachHost(_ id: String) { gate.detachHost(id) }
    public static func cancelAll() { gate.cancelAll() }
    public static func cancel(origin: String?) { gate.cancel(origin: origin) }
    public static func pending() -> [WriteApprovalGate.Request] { gate.pending() }
    @discardableResult
    public static func decide(hostId: String, requestId: String, approved: Bool) -> Bool {
        gate.decide(hostId: hostId, requestId: requestId, approved: approved)
    }

    /// [WriteApprovalGate.execute]와 같되 거절을 [EtlException](모델에게 그대로 보이는 문구)으로 바꾼다.
    public static func execute<T>(_ operation: String, _ details: String, destructive: Bool = false,
                                  on gate: WriteApprovalGate = AiWriteApproval.gate, _ action: () throws -> T) async throws -> T {
        do {
            return try await gate.execute(operation, details, destructive: destructive, action)
        } catch let e as WriteApprovalGate.Denied {
            throw EtlException(e.message)
        }
    }
}
