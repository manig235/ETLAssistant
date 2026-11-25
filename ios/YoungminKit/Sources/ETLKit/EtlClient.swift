import Foundation
import CryptoKit
import os

private let etlLog = Logger(subsystem: "kr.snu.etlassistant.ios", category: "etl")

/// eTL 오류. [kind]는 로그·판정용 짧은 분류(`timeout`, `network`, `http-404`, `forbidden`, `auth`, `parse`, `error`)이며
/// 토큰이나 URL을 담지 않는다.
open class EtlException: Error, LocalizedError, CustomStringConvertible, @unchecked Sendable {
    public let message: String
    public let kind: String

    public init(_ message: String, kind: String = "error") {
        self.message = message
        self.kind = kind
    }

    public var errorDescription: String? { message }
    public var description: String { message }
}

/// eTL이 토큰을 거부했다(401, 토큰 문제).
public final class EtlAuthException: EtlException, @unchecked Sendable {
    public init(_ message: String) { super.init(message, kind: "auth") }
}

/// 공정한(FIFO) 비동기 세마포어. 앱 전체 eTL 동시 요청 제한에 쓴다.
public actor AsyncSemaphore {
    private var permits: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init(_ permits: Int) { self.permits = permits }

    public func acquire() async {
        if permits > 0 {
            permits -= 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    public func release() {
        if waiters.isEmpty {
            permits += 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}

/// eTL(Canvas) 읽기 전용 클라이언트. **GET만** 보낸다.
///
/// - 토큰은 [origin]에만 보낸다. 페이지 링크나 리다이렉트가 다른 곳을 가리키면 토큰 없이 처리하거나 중단한다.
/// - 오류 메시지에는 경로(쿼리 제외)와 오류 종류만 넣는다. 토큰이나 서명 URL은 절대 넣지 않는다.
/// - JSON 요청은 앱 전체에서 동시에 [MAX_CONCURRENT]개까지만 보낸다([gate]).
public final class EtlClient: @unchecked Sendable {

    private let tokenValue: String
    public let origin: String
    public let fake: FakeEtl?
    private let session: URLSession

    /// [token]이 형식에 맞지 않으면 [EtlException](토큰은 메시지에 넣지 않는다).
    /// [session]은 JSON 요청용(기본: 리다이렉트를 따라가지 않는 공용 세션).
    public init(_ token: String, origin: String = EtlClient.BASE_URL, session: URLSession? = nil, fake: FakeEtl? = nil) throws {
        if !EtlClient.TOKEN_RE.matches(token) { throw EtlException("eTL 토큰이 없거나 형식이 올바르지 않아요") }
        self.tokenValue = token
        self.fake = fake ?? ((StudyDataProfile.isDemo || token == FakeEtl.token) ? .shared : nil)
        self.origin = self.fake == nil ? origin : FakeEtl.origin
        self.session = session ?? EtlClient.jsonSession
    }

    public struct Download: Sendable, Hashable {
        public let bytes: Int64
        public let sha256: String
        public init(bytes: Int64, sha256: String) {
            self.bytes = bytes
            self.sha256 = sha256
        }
    }

    /// `/api/v1/...` 경로 하나를 GET한다. 결과는 [JSONObject] 또는 [JSONArray].
    public func get(_ path: String) async throws -> Any {
        try await requestJson(resolveApi(path)).0
    }

    public func getObject(_ path: String) async throws -> JSONObject {
        guard let o = try await get(path) as? JSONObject else { throw EtlException("객체가 아닌 응답: \(EtlClient.pathOnly(path))") }
        return o
    }

    /// Link 헤더의 rel="next"를 따라 최대 [MAX_PAGES]쪽까지 모은다.
    public func getAll(_ path: String) async throws -> [JSONObject] {
        var out: [JSONObject] = []
        var url = try resolveApi(path)
        for _ in 0..<EtlClient.MAX_PAGES {
            let (data, link) = try await requestJson(url)
            guard let arr = data as? JSONArray else { throw EtlException("목록이 아닌 응답: \(EtlClient.pathOnly(url))") }
            for i in 0..<arr.length() { if let o = arr.optJSONObject(i) { out.append(o) } }
            guard let next = EtlClient.nextLink(link) else { return out }
            guard let abs = EtlClient.resolve(url, next) else { throw EtlException("다음 페이지 링크를 해석할 수 없어요") }
            if !EtlClient.sameOrigin(abs, origin) { throw EtlException("다음 페이지 링크가 eTL 밖을 가리켜요") } // 토큰 유출 방지
            url = abs
        }
        return out
    }

    /// eTL 파일 URL을 [destination] 파일로 내려받는다(있으면 덮어쓴다).
    /// 리다이렉트마다 Authorization은 eTL origin 요청에만 붙인다(서명된 스토리지 URL에는 붙이지 않는다).
    public func download(_ rawUrl: String, to destination: URL, maxBytes: Int64 = EtlClient.MAX_DOWNLOAD) async throws -> Download {
        guard let url = EtlClient.resolve("\(origin)/", rawUrl) else { throw EtlException("eTL 파일 주소를 해석할 수 없어요") }
        if !EtlClient.sameOrigin(url, origin) { throw EtlException("다운로드 주소가 eTL이 아니에요") }
        if let fake { return try fake.download(url, to: destination, maxBytes: maxBytes) }
        let shownPath = URLComponents(string: url)?.percentEncodedPath ?? "?"
        let req = try open(url, withToken: true, json: false, readTimeoutMs: EtlClient.DOWNLOAD_READ_TIMEOUT_MS)
        let delegate = DownloadDelegate(origin: origin, token: tokenValue, shownPath: shownPath, destination: destination, maxBytes: maxBytes)
        return try await delegate.run(req)
    }

    // ---- 내부 ---------------------------------------------------------------

    private func resolveApi(_ path: String) throws -> String {
        if !path.hasPrefix("/api/v1/") { throw EtlException("/api/v1/ 경로만 요청할 수 있어요") }
        return origin + path
    }

    private func open(_ url: String, withToken: Bool, json: Bool, readTimeoutMs: Int = EtlClient.READ_TIMEOUT_MS) throws -> URLRequest {
        // 인증을 붙이는 마지막 지점에서도 origin을 확인한다.
        if withToken && !EtlClient.sameOrigin(url, origin) { throw EtlException("eTL 밖으로 인증 정보를 보낼 수 없어요") }
        guard let u = URL(string: url) else { throw EtlException("eTL 주소를 해석할 수 없어요") }
        var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: TimeInterval(readTimeoutMs) / 1000)
        r.httpMethod = "GET"
        r.httpShouldHandleCookies = false
        if json { r.setValue("application/json", forHTTPHeaderField: "Accept") }
        if withToken { r.setValue("Bearer \(tokenValue)", forHTTPHeaderField: "Authorization") }
        return r
    }

    private func requestJson(_ url: String) async throws -> (Any, String?) {
        await EtlClient.gate.acquire()
        do {
            let r = try await requestJsonUngated(url)
            await EtlClient.gate.release()
            return r
        } catch {
            await EtlClient.gate.release()
            throw error
        }
    }

    private func requestJsonUngated(_ url: String) async throws -> (Any, String?) {
        try Task.checkCancellation()
        if let fake { return (try fake.get(url), nil) }
        let path = EtlClient.pathOnly(url)
        let req = try open(url, withToken: true, json: true)
        let data: Data
        let resp: URLResponse
        do {
            (data, resp) = try await session.data(for: req)
        } catch {
            if error is CancellationError || Task.isCancelled { throw CancellationError() }
            etlLog.notice("GET \(path, privacy: .public) -> error \(EtlClient.errorName(error), privacy: .public)")
            throw EtlException("eTL 연결 실패: \(path) (\(EtlClient.errorName(error)))", kind: EtlClient.ioKind(error))
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        etlLog.notice("GET \(path, privacy: .public) -> \(code)")
        if code == 401 {
            let text = String(decoding: data.prefix(EtlClient.MAX_ERROR_BODY), as: UTF8.self).lowercased()
            // 서버 본문은 토큰·서명 URL을 반사할 수 있으므로 정해 둔 진단 문구만 사용한다.
            let hint = EtlClient.errorHint(text)
            if EtlClient.isPermissionDenied(text) { throw EtlException("권한 없음(401): \(path)\(hint)", kind: "forbidden") }
            throw EtlAuthException(EtlClient.AUTH_REJECTED + hint + " [\(path)]")
        }
        if !(200...299).contains(code) { throw EtlException("eTL 요청 실패(\(code)): \(path)", kind: "http-\(code)") }
        if data.count > EtlClient.MAX_JSON_BODY { throw EtlException("eTL 응답이 너무 커요: \(path)", kind: "parse") }
        let body = String(decoding: data, as: UTF8.self)
        let link = (resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "Link")
        return (try EtlClient.parseBody(body, path), link)
    }

    // ---- companion ----------------------------------------------------------

    public static let BASE_URL = "https://myetl.snu.ac.kr"
    public static let MAX_PAGES = 20
    public static let MAX_REDIRECTS = 5
    /// 연결 대기(URLSession에는 연결만의 시간 제한이 없어 요청 대기 시간으로 함께 다룬다).
    public static let CONNECT_TIMEOUT_MS = 8_000
    public static let READ_TIMEOUT_MS = 10_000
    /// 파일 내려받기는 크고 느릴 수 있어 읽기 대기를 길게 둔다.
    public static let DOWNLOAD_READ_TIMEOUT_MS = 30_000
    /// 앱 전체에서 동시에 보내는 eTL JSON 요청 수.
    public static let MAX_CONCURRENT = 3
    static let gate = AsyncSemaphore(MAX_CONCURRENT)
    public static let MAX_DOWNLOAD: Int64 = 300 << 20
    static let MAX_JSON_BODY = 16 * 1024 * 1024
    static let MAX_ERROR_BODY = 64 * 1024
    static let AUTH_REJECTED = "eTL이 토큰을 거부했어요(401): 만료됐거나 올바르지 않아요"
    private static let TOKEN_RE = KRegex("[\\u0021-\\u007e]+")
    private static let XSSI = KRegex("^\\s*while\\(1\\);")

    /// 리다이렉트를 따라가지 않고(3xx는 그대로 응답), 쿠키·캐시를 쓰지 않는 JSON 세션.
    static let jsonSession: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = TimeInterval(READ_TIMEOUT_MS) / 1000
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpMaximumConnectionsPerHost = MAX_CONCURRENT
        return URLSession(configuration: c, delegate: NoRedirectDelegate(), delegateQueue: nil)
    }()

    /// 같은 동시 요청 한도 안에서 [block]을 실행한다(게시판처럼 이 클라이언트 밖에서 eTL 호스트에 보내는 요청용).
    public static func gated<T>(_ block: () async throws -> T) async rethrows -> T {
        await gate.acquire()
        do {
            let r = try await block()
            await gate.release()
            return r
        } catch {
            await gate.release()
            throw error
        }
    }

    /// 오류 분류: 시간 초과면 "timeout", 그 밖은 "network".
    public static func ioKind(_ e: Error) -> String {
        if let u = e as? URLError, u.code == .timedOut { return "timeout" }
        return "network"
    }

    /// 메시지에 넣는 짧은 오류 이름(URL이나 본문을 담지 않는다).
    static func errorName(_ e: Error) -> String {
        if let u = e as? URLError { return u.code == .timedOut ? "SocketTimeoutException" : "URLError \(u.code.rawValue)" }
        return String(describing: type(of: e))
    }

    /// 401 본문이 "토큰 문제"가 아니라 "이 동작/자원에 대한 권한 없음"인지.
    /// eTL은 오류를 한국어로도 돌려주므로 두 언어를 모두 본다. 본문에 token/토큰이 들어 있으면 토큰 문제로 본다.
    public static func isPermissionDenied(_ lowerBody: String) -> Bool {
        let mentionsToken = lowerBody.contains("token") || lowerBody.contains("토큰")
        let mentionsDenied = lowerBody.contains("not authorized") || lowerBody.contains("권한")
        return mentionsDenied && !mentionsToken
    }

    /// 서버가 반사한 임의의 본문을 모델·로그에 보내지 않고, 알려진 오류 종류만 안내한다.
    public static func errorHint(_ lowerBody: String) -> String {
        if isPermissionDenied(lowerBody) { return " — 서버 메시지: 사용자에게 이 동작을 수행할 권한이 없음" }
        if lowerBody.contains("token") || lowerBody.contains("토큰") { return " — 서버 메시지: 토큰 인증 실패" }
        return ""
    }

    private static func defaultPort(_ scheme: String) -> Int {
        switch scheme.lowercased() {
        case "https": return 443
        case "http": return 80
        default: return -1
        }
    }

    /// 스킴·호스트·포트가 같고 사용자 정보가 없는가.
    public static func sameOrigin(_ rawUrl: String, _ origin: String = BASE_URL) -> Bool {
        guard let url = URLComponents(string: rawUrl), let base = URLComponents(string: origin),
              let scheme = url.scheme, let baseScheme = base.scheme,
              let host = url.host, let baseHost = base.host, !host.isEmpty else { return false }
        if url.user != nil || url.password != nil { return false }
        return scheme.lowercased() == baseScheme.lowercased() && host.lowercased() == baseHost.lowercased() &&
            (url.port ?? defaultPort(scheme)) == (base.port ?? defaultPort(baseScheme))
    }

    /// [relative]를 [base] 기준으로 해석한 절대 URL(`URL(URL(base), relative)`).
    static func resolve(_ base: String, _ relative: String) -> String? {
        guard let b = URL(string: base), let u = URL(string: relative, relativeTo: b), u.absoluteURL.scheme != nil else { return nil }
        return u.absoluteURL.absoluteString
    }

    /// `while(1);` 접두어를 떼고 JSON으로 읽는다. 결과는 [JSONObject] 또는 [JSONArray]. 본문은 오류에 넣지 않는다.
    public static func parseBody(_ body: String, _ path: String) throws -> Any {
        let s = XSSI.replace(body, "").kTrim
        do {
            return s.hasPrefix("[") ? try JSONArray(s) : try JSONObject(s)
        } catch {
            throw EtlException("읽을 수 없는 eTL 응답: \(path)", kind: "parse")
        }
    }

    /// 쿼리를 뺀 경로.
    public static func pathOnly(_ url: String) -> String {
        if url.hasPrefix("/") { return url.substringBefore("?") }
        guard let c = URLComponents(string: url), c.scheme != nil else { return "?" }
        return c.percentEncodedPath
    }

    private static let LINK_PART = KRegex("\\s*<([^>]*)>(.*)")
    private static let REL_NEXT = KRegex("rel\\s*=\\s*\"?([^\";]*\\bnext\\b[^\";]*)\"?", .ignoreCase)

    /// Link 헤더에서 rel="next" 주소를 꺼낸다.
    public static func nextLink(_ header: String?) -> String? {
        guard let header, !header.isEmpty else { return nil }
        for part in header.split(separator: ",", omittingEmptySubsequences: false) {
            guard let m = LINK_PART.find(String(part)) else { continue }
            if REL_NEXT.containsMatchIn(m.groupValues[2]) { return m.groupValues[1] }
        }
        return nil
    }
}

/// 리다이렉트를 따라가지 않는다(3xx를 그대로 응답으로 받는다).
final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// 파일 내려받기 하나. 리다이렉트를 검사하고 교차 origin이면 Authorization을 뺀다. 크기 상한을 넘으면 중단한다.
final class DownloadDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let origin: String
    private let token: String
    private let shownPath: String
    private let destination: URL
    private let maxBytes: Int64

    private let lock = NSLock()
    private var redirects = 0
    private var failure: Error?
    private var bytes: Int64 = 0
    private var hasher = SHA256()
    private var handle: FileHandle?
    private var continuation: CheckedContinuation<EtlClient.Download, Error>?

    init(origin: String, token: String, shownPath: String, destination: URL, maxBytes: Int64) {
        self.origin = origin
        self.token = token
        self.shownPath = shownPath
        self.destination = destination
        self.maxBytes = maxBytes
    }

    func run(_ request: URLRequest) async throws -> EtlClient.Download {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = TimeInterval(EtlClient.DOWNLOAD_READ_TIMEOUT_MS) / 1000
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: c, delegate: self, delegateQueue: queue)
        defer { session.finishTasksAndInvalidate() }
        let task = session.dataTask(with: request)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<EtlClient.Download, Error>) in
                lock.withLock { continuation = cont }
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    private func fail(_ e: Error, _ task: URLSessionTask) {
        lock.withLock { if failure == nil { failure = e } }
        task.cancel()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        redirects += 1
        if redirects > EtlClient.MAX_REDIRECTS {
            fail(EtlException("리다이렉트가 너무 많아요: \(shownPath)"), task)
            completionHandler(nil)
            return
        }
        guard let next = request.url, next.scheme?.lowercased() == "https",
              let comps = URLComponents(url: next, resolvingAgainstBaseURL: true), comps.user == nil, comps.password == nil else {
            fail(EtlException("안전하지 않은 리다이렉트를 거부했어요: \(shownPath)"), task)
            completionHandler(nil)
            return
        }
        var r = request
        // 토큰은 eTL origin에만. 서명된 스토리지 URL로 가면 Authorization을 뺀다.
        if EtlClient.sameOrigin(next.absoluteString, origin) {
            r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else {
            r.setValue(nil, forHTTPHeaderField: "Authorization")
        }
        r.setValue(nil, forHTTPHeaderField: "Cookie")
        r.httpShouldHandleCookies = false
        completionHandler(r)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let http = response as? HTTPURLResponse
        let code = http?.statusCode ?? 0
        let onEtl = response.url.map { EtlClient.sameOrigin($0.absoluteString, origin) } ?? false
        if (300...399).contains(code) {
            fail(EtlException("리다이렉트 위치가 없어요: \(shownPath)"), dataTask)
            completionHandler(.cancel)
            return
        }
        if code == 401 && onEtl {
            fail(EtlAuthException(EtlClient.AUTH_REJECTED), dataTask)
            completionHandler(.cancel)
            return
        }
        if !(200...299).contains(code) {
            fail(EtlException("다운로드 실패(\(code)): \(shownPath)", kind: "http-\(code)"), dataTask)
            completionHandler(.cancel)
            return
        }
        do {
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: destination.path, contents: nil)
            handle = try FileHandle(forWritingTo: destination)
            try handle?.truncate(atOffset: 0)
            completionHandler(.allow)
        } catch {
            fail(EtlException("다운로드 실패: \(shownPath) (\(EtlClient.errorName(error)))", kind: "network"), dataTask)
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if lock.withLock({ failure != nil }) { return }
        bytes += Int64(data.count)
        if bytes > maxBytes {
            fail(EtlException("파일이 \(maxBytes >> 20)MB보다 커요: \(shownPath)"), dataTask)
            return
        }
        hasher.update(data: data)
        do {
            try handle?.write(contentsOf: data)
        } catch {
            fail(EtlException("다운로드 실패: \(shownPath) (\(EtlClient.errorName(error)))", kind: "network"), dataTask)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? handle?.close()
        handle = nil
        let (cont, stored) = lock.withLock { () -> (CheckedContinuation<EtlClient.Download, Error>?, Error?) in
            let c = continuation
            continuation = nil
            return (c, failure)
        }
        guard let cont else { return }
        if let stored {
            cont.resume(throwing: stored)
        } else if let error {
            if (error as? URLError)?.code == .cancelled {
                cont.resume(throwing: CancellationError())
            } else {
                cont.resume(throwing: EtlException("다운로드 실패: \(shownPath) (\(EtlClient.errorName(error)))", kind: EtlClient.ioKind(error)))
            }
        } else {
            let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
            cont.resume(returning: EtlClient.Download(bytes: bytes, sha256: hex))
        }
    }
}
