import Foundation
import Network

/// 루프백 HTTP 요청의 순수 처리(새로 만듦, Kotlin `ChatGptOAuth.readRequestLine`·`respond`의 iOS 판).
public enum LoopbackHTTP {
    public static let maxHeaderBytes = 32 * 1024

    public struct RequestHead: Sendable, Hashable {
        public var method: String
        public var target: String
    }

    /// 헤더 끝(`\r\n\r\n` 또는 `\n\n`)까지 받았는가.
    public static func headerComplete(_ data: Data) -> Bool {
        let b = [UInt8](data)
        guard b.count >= 2 else { return false }
        for i in 1..<b.count where b[i] == 0x0A {
            if b[i - 1] == 0x0A { return true }
            if i >= 3 && b[i - 1] == 0x0D && b[i - 2] == 0x0A { return true }
        }
        return false
    }

    /// 요청 줄(`GET /auth/callback?… HTTP/1.1`)을 해석한다. 헤더가 너무 크거나 형식이 틀리면 nil.
    public static func parseRequestHead(_ data: Data) -> RequestHead? {
        guard data.count <= maxHeaderBytes else { return nil }
        // 바이트로 자른다(Swift 문자열에서는 "\r\n"이 한 글자라 "\n"으로 나뉘지 않는다).
        var bytes = [UInt8](data)
        if let nl = bytes.firstIndex(of: 0x0A) { bytes = Array(bytes[..<nl]) }
        if bytes.last == 0x0D { bytes.removeLast() }
        guard bytes.allSatisfy({ $0 >= 0x20 && $0 < 0x7F }) else { return nil }
        let line = String(decoding: bytes, as: UTF8.self)
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count == 3, parts[2].hasPrefix("HTTP/1.") else { return nil }
        return RequestHead(method: String(parts[0]), target: String(parts[1]))
    }

    public enum Page: Sendable, Hashable { case success, cancelled, invalid }

    /// 브라우저에 보여 줄 짧은 한국어 페이지(외부 자원 없음, 캐시 안 함).
    public static func response(_ page: Page) -> Data {
        let text: String
        let status: String
        switch page {
        case .success: text = "로그인을 확인했어요. 영민이 앱으로 돌아가 주세요."; status = "200 OK"
        case .cancelled: text = "로그인을 취소했어요. 영민이 앱으로 돌아가 주세요."; status = "200 OK"
        case .invalid: text = "로그인 요청을 확인하지 못했어요. 영민이 앱에서 다시 시도해 주세요."; status = "400 Bad Request"
        }
        let body = Data("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>영민이 로그인</title><p>\(text)</p>".utf8)
        let head = "HTTP/1.1 \(status)\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\n\r\n"
        return Data(head.utf8) + body
    }
}

/// 콜백 요청을 받았을 때의 판단.
public enum LoopbackDecision: Sendable, Hashable {
    /// 우리 로그인의 콜백이 아니다(400 페이지를 보이고 계속 기다린다).
    case ignore
    /// 콜백을 받았다. 보여 줄 페이지와 함께 기다리기를 끝낸다.
    case finish(LoopbackHTTP.Page)
}

public enum LoopbackError: Error, Sendable, Hashable {
    case cannotListen
    case cancelled
    case timedOut
}

/// `127.0.0.1`에서 콜백 요청 하나를 받는 작은 HTTP 서버(새로 만듦, Network.framework `NWListener`).
/// 앱은 `start()`로 포트를 받은 뒤 그 포트로 `SIWCAttempt`를 만들고 브라우저를 연다. 그다음 `waitForCallback`.
public final class LoopbackCallbackServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "kr.snu.youngmin.loopback")
    private let requestedPort: UInt16
    private var listener: NWListener?
    private var continuation: CheckedContinuation<String, Error>?
    private var decide: (@Sendable (String) -> LoopbackDecision)?
    private var early: [(NWConnection, String)] = []
    private var finished = false
    private let lock = NSLock()

    /// - Parameter port: 0이면 빈 포트를 아무거나 쓴다.
    public init(port: UInt16 = 0) { self.requestedPort = port }

    /// 듣기 시작하고 실제 포트를 돌려준다.
    public func start() async throws -> UInt16 {
        let params = NWParameters.tcp
        // acceptLocalOnly는 NECP 정책 등록이 실패하면(시뮬레이터에서 SO_NECP_LISTENUUID 오류) 모든 연결을 끊는다.
        // 127.0.0.1에만 묶으므로 밖에서는 어차피 닿을 수 없다.
        params.allowLocalEndpointReuse = false
        let port = NWEndpoint.Port(rawValue: requestedPort) ?? .any
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: NWEndpoint.Host("127.0.0.1"), port: port)
        let l: NWListener
        do { l = try NWListener(using: params) } catch { throw LoopbackError.cannotListen }
        lock.withLock { listener = l }
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<UInt16, Error>) in
            let once = OnceFlag() // 상태 처리기는 `queue`(직렬)에서만 불린다
            l.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if once.take() { cont.resume(returning: l.port?.rawValue ?? 0) }
                case .failed, .cancelled:
                    if once.take() { cont.resume(throwing: LoopbackError.cannotListen) }
                default: break
                }
            }
            l.start(queue: queue)
        }
    }

    /// 먼저 `preferred`(문서 예시 1455)를 써 보고, 못 쓰면 빈 포트를 쓰는 서버를 만든다.
    public static func startPreferring(_ preferred: UInt16 = SIWC.preferredPort) async throws -> (LoopbackCallbackServer, UInt16) {
        let first = LoopbackCallbackServer(port: preferred)
        if let p = try? await first.start() { return (first, p) }
        first.stop()
        let any = LoopbackCallbackServer(port: 0)
        return (any, try await any.start())
    }

    /// 콜백 하나를 기다린다. `decide`가 `.finish`를 돌려준 요청의 대상(`/auth/callback?…`)을 돌려준다.
    /// `timeout`초가 지나거나 `stop()`하면 오류.
    public func waitForCallback(timeout: TimeInterval = 15 * 60, decide: @escaping @Sendable (String) -> LoopbackDecision) async throws -> String {
        let result = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
                let waiting: [(NWConnection, String)]? = lock.withLock {
                    if finished { return nil }
                    self.decide = decide
                    self.continuation = cont
                    defer { early.removeAll() }
                    return early
                }
                guard let waiting else {
                    cont.resume(throwing: LoopbackError.cancelled)
                    return
                }
                // 기다리기 전에 먼저 온 요청을 이제 처리한다.
                queue.async { [weak self] in waiting.forEach { self?.dispatch($0.0, $0.1) } }
                queue.asyncAfter(deadline: .now() + timeout) { [weak self] in self?.complete(.failure(LoopbackError.timedOut)) }
            }
        } onCancel: {
            self.stop()
        }
        return result
    }

    /// SIWC 콜백 판단: state가 맞는 `/auth/callback`만 받는다(코드 → 성공 페이지, 거절 → 취소 페이지).
    public static func siwcDecision(expectedState: String) -> @Sendable (String) -> LoopbackDecision {
        { target in
            switch SIWCCallback.parse(target: target, expectedState: expectedState) {
            case nil: return .ignore
            case .code?: return .finish(.success)
            case .denied?: return .finish(.cancelled)
            case .error?: return .finish(.invalid)
            }
        }
    }

    /// 듣기를 멈춘다(기다리던 쪽은 `.cancelled`).
    public func stop() {
        if !lock.withLock({ finished }) { DiagLog.logger.notice("loopback: stopped") }
        complete(.failure(LoopbackError.cancelled))
    }

    private func complete(_ r: Result<String, Error>) {
        let (cont, l): (CheckedContinuation<String, Error>?, NWListener?) = lock.withLock {
            if finished { return (nil, nil) }
            finished = true
            let c = continuation
            continuation = nil
            let l = listener
            listener = nil
            return (c, l)
        }
        l?.cancel()
        let leftovers: [(NWConnection, String)] = lock.withLock { defer { early.removeAll() }; return early }
        leftovers.forEach { reply($0.0, .invalid) }
        if let cont { cont.resume(with: r) }
    }

    private func accept(_ c: NWConnection) {
        DiagLog.logger.notice("loopback: connection accepted")
        c.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 5) { c.cancel() }
        receive(c, Data())
    }

    private func receive(_ c: NWConnection, _ buffer: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self else { c.cancel(); return }
            var buf = buffer
            if let data { buf.append(data) }
            if buf.count > LoopbackHTTP.maxHeaderBytes || error != nil {
                self.reply(c, .invalid)
                return
            }
            if LoopbackHTTP.headerComplete(buf) || isComplete {
                self.handle(c, buf)
            } else {
                self.receive(c, buf)
            }
        }
    }

    private func handle(_ c: NWConnection, _ data: Data) {
        guard let head = LoopbackHTTP.parseRequestHead(data), head.method == "GET" else { reply(c, .invalid); return }
        let parked: Bool = lock.withLock {
            if decide == nil && !finished { early.append((c, head.target)); return true }
            return false
        }
        if !parked { dispatch(c, head.target) }
    }

    private func dispatch(_ c: NWConnection, _ target: String) {
        let decide = lock.withLock { self.decide }
        let decision = decide?(target) ?? .ignore
        DiagLog.logger.notice("loopback: request \(target.components(separatedBy: "?").first ?? "", privacy: .public) -> \(String(describing: decision), privacy: .public)")
        switch decision {
        case .ignore:
            reply(c, .invalid)
        case .finish(let page):
            reply(c, page)
            complete(.success(target))
        }
    }

    private func reply(_ c: NWConnection, _ page: LoopbackHTTP.Page) {
        c.send(content: LoopbackHTTP.response(page), completion: .contentProcessed { _ in c.cancel() })
    }
}

/// 한 번만 참을 돌려준다.
final class OnceFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func take() -> Bool { lock.withLock { if done { return false }; done = true; return true } }
}
