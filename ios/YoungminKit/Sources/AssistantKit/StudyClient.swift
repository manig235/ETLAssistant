import ETLKit
import Foundation
#if canImport(Darwin)
import Darwin
#endif

/// 공개 기출 자료 사이트([StudySites])에 GET만 보내는 클라이언트(Kotlin `study/StudyClient.kt`).
///
/// - 허용 목록 호스트·HTTPS만. 리다이렉트는 따라가지 않고 직접 검사해 옮긴다([StudyLogic.redirectTarget], 최대 3번).
/// - 쿠키 저장소·캐시를 쓰지 않고 토큰·인증 헤더를 보내지 않는다. User-Agent는 개인 정보 없는 일반 값이다.
/// - 이름이 사설망·루프백 주소로 풀리면 보내지 않는다([StudyLogic.isPublicAddress]).
/// - 응답 크기 상한(JSON·HTML 2MB, PDF 30MB)과 시간 제한, 앱 전체 동시 요청 수([StudyLogic.MAX_CONCURRENT])를 둔다.
public final class StudyClient: @unchecked Sendable {
    public typealias Resolver = @Sendable (String) async -> [[UInt8]]

    public enum Fetched: Sendable {
        case page(url: String, html: String)
        case pdf(url: String, bytes: Int64, file: URL)
    }

    private let session: URLSession
    private let resolve: Resolver
    private static let gate = AsyncGate(StudyLogic.MAX_CONCURRENT)

    /// - Parameters:
    ///   - session: 테스트에서 가짜 응답 세션을 넣는다(기본: 쿠키·캐시 없는 ephemeral 세션, 리다이렉트 안 따라감).
    ///   - resolve: 호스트 → 주소 바이트(기본: getaddrinfo).
    public init(session: URLSession? = nil, resolve: @escaping Resolver = StudyClient.systemResolve) {
        self.session = session ?? StudyClient.defaultSession
        self.resolve = resolve
    }

    static let defaultSession: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = TimeInterval(StudyLogic.READ_TIMEOUT_MS) / 1000
        c.timeoutIntervalForResource = TimeInterval(StudyLogic.PDF_READ_TIMEOUT_MS) / 1000 * 4
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.httpCookieAcceptPolicy = .never
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [:]
        return URLSession(configuration: c, delegate: StudyNoRedirect(), delegateQueue: nil)
    }()

    /// 검색 API(JSON)를 GET한다. [url]은 [StudyLogic]이 만든 주소만 쓴다.
    public func getJson(_ url: String) async throws -> String {
        guard url.hasPrefix("https://"), let u = URL(string: url), StudySites.isSearchHost(u.host) else {
            throw EtlException("허용되지 않은 검색 주소예요")
        }
        return try await gated { () async throws -> String in
            let (bytes, resp) = try await self.start(u, accept: "application/json", timeoutMs: StudyLogic.READ_TIMEOUT_MS)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if !(200...299).contains(code) { throw EtlException("검색 응답 오류(\(code))") }
            let data = try await Self.readBounded(bytes, resp, limit: StudyLogic.MAX_JSON_BYTES, tooBig: "검색 응답이 너무 커요")
            return String(decoding: data, as: UTF8.self)
        }
    }

    /// [rawUrl]을 받는다. HTML이면 글(2MB까지), PDF면 [pdfTarget]에 쓴다(30MB까지, `%PDF`로 시작해야 함). 그 밖의 형식은 받지 않는다.
    public func fetch(_ rawUrl: String, pdfTarget: URL) async throws -> Fetched {
        var url = try StudyLogic.checkFetchUrl(rawUrl)
        return try await gated { () async throws -> Fetched in
            for hop in 0...StudyLogic.MAX_REDIRECTS {
                guard let u = URL(string: url) else { throw EtlException("주소를 읽을 수 없어요") }
                let (bytes, resp) = try await self.start(u, accept: "text/html,application/pdf;q=0.9", timeoutMs: StudyLogic.PDF_READ_TIMEOUT_MS)
                let http = resp as? HTTPURLResponse
                let code = http?.statusCode ?? 0
                if (300...399).contains(code) {
                    if hop == StudyLogic.MAX_REDIRECTS { throw EtlException("이동이 너무 많아요") }
                    url = try StudyLogic.redirectTarget(url, http?.value(forHTTPHeaderField: "Location"))
                    continue
                }
                if code == 404 { throw EtlException("자료를 찾을 수 없어요(404). study_search로 주소를 다시 확인하세요") }
                if !(200...299).contains(code) { throw EtlException("자료 응답 오류(\(code))") }
                let type = http?.value(forHTTPHeaderField: "Content-Type")
                switch StudyLogic.kindOf(type) {
                case .html:
                    let data = try await Self.readBounded(bytes, resp, limit: StudyLogic.MAX_HTML_BYTES, tooBig: "페이지가 너무 커요")
                    return .page(url: url, html: String(decoding: data, as: UTF8.self))
                case .pdf:
                    return .pdf(url: url, bytes: try await Self.savePdf(bytes, resp, to: pdfTarget), file: pdfTarget)
                case .other:
                    let shown = type.map { $0.substringBeforeChar(";") } ?? "형식 모름"
                    throw EtlException("PDF나 웹 페이지가 아니라서 받지 않았어요(\(shown))")
                }
            }
            throw EtlException("이동이 너무 많아요")
        }
    }

    // MARK: 내부

    private func start(_ u: URL, accept: String, timeoutMs: Int) async throws -> (URLSession.AsyncBytes, URLResponse) {
        guard let host = u.host else { throw EtlException("주소를 읽을 수 없어요") }
        let addrs = await resolve(host)
        if addrs.isEmpty { throw EtlException("공개 자료 사이트 주소를 찾지 못했어요") }
        if addrs.contains(where: { !StudyLogic.isPublicAddress($0) }) { throw EtlException("사설망 주소로 풀리는 곳에는 요청하지 않아요") }
        var req = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: TimeInterval(timeoutMs) / 1000)
        req.httpMethod = "GET"
        req.httpShouldHandleCookies = false
        req.setValue(StudyLogic.USER_AGENT, forHTTPHeaderField: "User-Agent")
        req.setValue(accept, forHTTPHeaderField: "Accept")
        return try await session.bytes(for: req)
    }

    private func gated<T>(_ body: () async throws -> T) async throws -> T {
        try await Self.gate.acquire()
        defer { Task { await Self.gate.release() } }
        do {
            return try await body()
        } catch let e as EtlException {
            throw e
        } catch is CancellationError {
            throw CancellationError()
        } catch let e as URLError {
            throw EtlException(e.code == .timedOut ? "공개 자료 사이트 응답 시간 초과" : "공개 자료 사이트 연결 실패")
        } catch {
            throw EtlException("공개 자료 사이트 연결 실패")
        }
    }

    static func readBounded(_ bytes: URLSession.AsyncBytes, _ resp: URLResponse, limit: Int, tooBig: String) async throws -> Data {
        if resp.expectedContentLength > Int64(limit) { throw EtlException(tooBig) }
        var data = Data()
        data.reserveCapacity(min(limit, 64 * 1024))
        var chunk = [UInt8]()
        chunk.reserveCapacity(16 * 1024)
        for try await b in bytes {
            chunk.append(b)
            if chunk.count >= 16 * 1024 {
                if data.count + chunk.count > limit { throw EtlException(tooBig) }
                data.append(contentsOf: chunk)
                chunk.removeAll(keepingCapacity: true)
            }
        }
        if data.count + chunk.count > limit { throw EtlException(tooBig) }
        data.append(contentsOf: chunk)
        return data
    }

    static func savePdf(_ bytes: URLSession.AsyncBytes, _ resp: URLResponse, to target: URL) async throws -> Int64 {
        if resp.expectedContentLength > StudyLogic.MAX_PDF_BYTES { throw EtlException("PDF가 너무 커요(30MB 초과)") }
        let fm = FileManager.default
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = target.deletingLastPathComponent().appendingPathComponent(target.lastPathComponent + ".part")
        fm.createFile(atPath: tmp.path, contents: nil)
        do {
            let handle = try FileHandle(forWritingTo: tmp)
            var total: Int64 = 0
            var checked = false
            var buffer = Data()
            buffer.reserveCapacity(64 * 1024)
            do {
                for try await b in bytes {
                    buffer.append(b)
                    total += 1
                    if total > StudyLogic.MAX_PDF_BYTES { throw EtlException("PDF가 너무 커요(30MB 초과)") }
                    if !checked && buffer.count >= 4 {
                        if !StudyLogic.looksLikePdf(buffer) { throw EtlException("PDF 형식이 아니에요") }
                        checked = true
                    }
                    if buffer.count >= 64 * 1024 {
                        try handle.write(contentsOf: buffer)
                        buffer.removeAll(keepingCapacity: true)
                    }
                }
                if !buffer.isEmpty { try handle.write(contentsOf: buffer) }
                try handle.close()
            } catch {
                try? handle.close()
                throw error
            }
            if total == 0 || !checked { throw EtlException(total == 0 ? "빈 파일이에요" : "PDF 형식이 아니에요") }
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
            try fm.moveItem(at: tmp, to: target)
            return total
        } catch {
            try? fm.removeItem(at: tmp)
            throw error
        }
    }

    /// getaddrinfo로 이름을 풀어 주소 바이트를 돌려준다(실패하면 빈 목록).
    public static let systemResolve: Resolver = { host in
        await Task.detached { () -> [[UInt8]] in
            var hints = addrinfo()
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            var res: UnsafeMutablePointer<addrinfo>?
            guard getaddrinfo(host, "443", &hints, &res) == 0, let first = res else { return [] }
            defer { freeaddrinfo(first) }
            var out: [[UInt8]] = []
            var p: UnsafeMutablePointer<addrinfo>? = first
            while let ai = p {
                if ai.pointee.ai_family == AF_INET, let sa = ai.pointee.ai_addr {
                    sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin in
                        var a = sin.pointee.sin_addr
                        out.append(withUnsafeBytes(of: &a) { Array($0) })
                    }
                } else if ai.pointee.ai_family == AF_INET6, let sa = ai.pointee.ai_addr {
                    sa.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { sin6 in
                        var a = sin6.pointee.sin6_addr
                        out.append(withUnsafeBytes(of: &a) { Array($0) })
                    }
                }
                p = ai.pointee.ai_next
            }
            return out
        }.value
    }
}

/// 리다이렉트를 따라가지 않는다(3xx 응답을 그대로 돌려받아 [StudyLogic.redirectTarget]으로 검사한다).
final class StudyNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// 동시에 [limit]개까지만 들여보내는 비동기 문(앱 전체 공개 자료 요청 수 제한).
actor AsyncGate {
    private let limit: Int
    private var inUse = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ limit: Int) { self.limit = limit }

    func acquire() async throws {
        if inUse < limit {
            inUse += 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
        // 자리를 받은 뒤 취소됐으면 자리를 돌려주고 끝낸다.
        if Task.isCancelled {
            release()
            throw CancellationError()
        }
    }

    func release() {
        if waiters.isEmpty {
            inUse = max(0, inUse - 1)
        } else {
            waiters.removeFirst().resume()
        }
    }
}
