import Foundation
import ETLKit
import NotifyKit
@testable import AssistantKit

/// 스레드에 안전한 정수 세기.
final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    @discardableResult func inc() -> Int { lock.withLock { n += 1; return n } }
    var value: Int { lock.withLock { n } }
}

/// 대기 목록 변경 알림을 받아 새 요청을 한 번씩만 꺼낸다(Kotlin `observeRequests`: 같은 요청이 여러 번 보여도 한 번만).
final class RequestObserver: @unchecked Sendable {
    private let lock = NSLock()
    private var seen = Set<String>()
    private var queue: [WriteApprovalGate.Request] = []
    private weak var gate: WriteApprovalGate?

    init(_ gate: WriteApprovalGate) {
        self.gate = gate
        gate.setChangeHandler { [weak self] in self?.poke() }
    }

    /// 변경 알림을 한 번 더 흉내 낸다(반복 알림이 새 요청처럼 보이면 안 된다).
    func poke() {
        guard let gate else { return }
        let list = gate.pending()
        lock.withLock { for r in list where seen.insert(r.id).inserted { queue.append(r) } }
    }

    func next(timeout: TimeInterval = 2) async -> WriteApprovalGate.Request? {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if let r = lock.withLock({ queue.isEmpty ? nil : queue.removeFirst() }) { return r }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return nil
    }
}

/// 실제 네트워크 대신 응답을 꾸며 주는 URLProtocol. 호스트별로 처리기를 등록한다(테스트마다 고유 호스트).
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) -> (Int, String)?
    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]

    static func register(_ host: String, _ h: @escaping Handler) { lock.withLock { handlers[host] = h } }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let h = StubURLProtocol.lock.withLock { StubURLProtocol.handlers[request.url?.host ?? ""] }
        guard let (code, body) = h?(request) else {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let resp = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

/// 메모리 저장소와 임시 폴더로 만든 도구 상자. [handler]가 있으면 가짜 eTL 호스트로 보낸다.
func makeToolbox(token: String? = "tok-SECRET-123", gate: WriteApprovalGate = WriteApprovalGate(),
                 mine: MyDeadlineStore = MyDeadlineStore(InMemoryStore()),
                 now: Date = EtlLogic.parseInstant("2026-11-25T09:00:00+09:00")!,
                 handler: StubURLProtocol.Handler? = nil) -> Toolbox {
    let host = "stub-\(UUID().uuidString.lowercased()).test"
    if let handler { StubURLProtocol.register(host, handler) }
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("assistantkit-\(UUID().uuidString)", isDirectory: true)
    return Toolbox(tokens: { token },
                   makeClient: { try EtlClient($0, origin: "https://\(host)", session: StubURLProtocol.session()) },
                   approvals: gate, myDeadlines: mine,
                   timetableStore: TimetableStore(InMemoryStore()), timetableChanges: TimetableChangeStore(InMemoryStore()),
                   workspace: dir.appendingPathComponent("etl-files", isDirectory: true),
                   userDirectory: dir.appendingPathComponent("user", isDirectory: true),
                   clock: { now })
}

/// 도구 결과 JSON.
func parsed(_ text: String) -> JSONObject { (try? JSONObject(text)) ?? JSONObject() }
