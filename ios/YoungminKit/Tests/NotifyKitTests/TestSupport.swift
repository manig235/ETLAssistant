import Foundation
import ETLKit
@testable import NotifyKit

let seoul = TimeZone(identifier: "Asia/Seoul")!
let utcZone = TimeZone(identifier: "UTC")!
let newYork = TimeZone(identifier: "America/New_York")!
let tokyo = TimeZone(identifier: "Asia/Tokyo")!

/// java.time의 나노초 대신 쓰는 아주 작은 간격. Date(Double)는 2026년 근처에서 ~0.1µs 해상도라 1ns는 사라진다.
let tiny: TimeInterval = 0.001

/// `Instant.parse("2026-11-19T03:00:00Z")`.
func instant(_ iso: String) -> Date { EtlLogic.parseInstant(iso)! }

/// `LocalDateTime.parse("2026-11-24T12:00").atZone(zone).toInstant()`.
func local(_ s: String, _ zone: TimeZone = seoul) -> Date {
    let date = DayDate.parse(String(s.prefix(10)))!
    let hm = s.dropFirst(11).split(separator: ":").map { Int($0)! }
    return ZoneClock.instant(date, hm[0], hm[1], hm.count > 2 ? hm[2] : 0, zone)
}

/// KST 현지 시각.
func kst(_ s: String) -> Date { local(s, seoul) }

/// `LocalDate.parse`.
func d(_ s: String) -> DayDate { DayDate.parse(s)! }

extension Date {
    func h(_ hours: Double) -> Date { addingTimeInterval(hours * 3600) }
    func secs(_ s: Double) -> Date { addingTimeInterval(s) }
}

func jsonList(_ json: String) -> [JSONObject] {
    let a = try! JSONArray(json)
    return (0..<a.length()).map { try! a.getJSONObject($0) }
}

/// 실제 네트워크 대신 응답을 꾸며 주는 URLProtocol. 호스트별로 처리기를 등록한다(테스트마다 고유 호스트).
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) -> (Int, String)?
    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]

    static func register(_ host: String, _ h: @escaping Handler) { lock.withLock { handlers[host] = h } }

    /// 처리기가 nil을 돌려주면 연결 실패로 본다.
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

/// 고유한 가짜 eTL 주소와 그 클라이언트.
func stubClient(_ handler: @escaping StubURLProtocol.Handler) throws -> (EtlClient, String) {
    let host = "stub-\(UUID().uuidString.lowercased()).test"
    StubURLProtocol.register(host, handler)
    let origin = "https://\(host)"
    return (try EtlClient("token123", origin: origin, session: StubURLProtocol.session()), origin)
}
