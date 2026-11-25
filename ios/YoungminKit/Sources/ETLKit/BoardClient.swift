import Foundation
import os

private let boardLog = Logger(subsystem: "kr.snu.etlassistant.ios", category: "board")

/// 게시판 요청 하나의 응답(본문은 [BoardClient.MAX_HTML_CHARS] 바이트 남짓까지만 읽는다).
public struct BoardHTTPResponse: Sendable {
    public let code: Int
    public let body: String
    /// `Location` 헤더(리다이렉트).
    public let location: String?
    /// `Set-Cookie` 헤더들(쿠키마다 하나).
    public let setCookies: [String]

    public init(code: Int, body: String, location: String? = nil, setCookies: [String] = []) {
        self.code = code
        self.body = body
        self.location = location
        self.setCookies = setCookies
    }
}

/// 게시판 HTTP 계층. 리다이렉트를 따라가지 않고(3xx를 그대로 돌려준다) 쿠키·캐시를 쓰지 않는다. 테스트는 흉내 낸 구현을 넣는다.
public protocol BoardTransport: Sendable {
    func send(_ request: URLRequest) async throws -> BoardHTTPResponse
}

/// URLSession 구현(ephemeral, 리다이렉트 안 따라감, 쿠키 저장소 없음).
public final class URLSessionBoardTransport: BoardTransport, @unchecked Sendable {
    private let session: URLSession

    public static let shared = URLSessionBoardTransport()

    public init(session: URLSession? = nil) {
        self.session = session ?? {
            let c = URLSessionConfiguration.ephemeral
            c.timeoutIntervalForRequest = TimeInterval(BoardClient.READ_TIMEOUT_MS) / 1000
            c.httpCookieStorage = nil
            c.httpShouldSetCookies = false
            c.httpCookieAcceptPolicy = .never
            c.urlCache = nil
            c.requestCachePolicy = .reloadIgnoringLocalCacheData
            return URLSession(configuration: c, delegate: NoRedirectDelegate(), delegateQueue: nil)
        }()
    }

    public func send(_ request: URLRequest) async throws -> BoardHTTPResponse {
        let (bytes, resp) = try await session.bytes(for: request)
        let http = resp as? HTTPURLResponse
        var data = Data()
        data.reserveCapacity(1 << 14)
        for try await b in bytes {
            data.append(b)
            if data.count > BoardClient.MAX_HTML_CHARS { break }
        }
        bytes.task.cancel()
        return BoardHTTPResponse(code: http?.statusCode ?? 0,
                                 body: String(decoding: data, as: UTF8.self),
                                 location: http?.value(forHTTPHeaderField: "Location"),
                                 setCookies: BoardLogic.splitSetCookie(http?.value(forHTTPHeaderField: "Set-Cookie")))
    }
}

/// 과목 게시판 조회(Kotlin `BoardClient`). [etl]은 Canvas API(탭, 실행 링크)에 쓰고, 그 밖의 요청은 eTL 호스트([EtlClient.origin])에만 보낸다.
///
///   1. GET  /api/v1/courses/<cid>/tabs                         게시판 탭 → 도구 ID
///   2. GET  /api/v1/courses/<cid>/external_tools/sessionless_launch?id=<도구>   → 일회용 실행 링크(verifier 포함)
///   3. GET  그 링크                                             → Canvas가 자동 제출하는 LTI 폼
///   4. POST 그 폼을 /learningx/lti/… 로                         → Set-Cookie: xn_api_token=<JWT>
///
/// 4단계가 앱이 eTL에 보내는 **유일한 POST**다: eTL 자체 호스트의 LearningX로, Canvas가 서명한 필드만 담은 고정 LTI 실행.
/// 그 뒤는 전부 GET이다. 글을 읽어도 "읽음" 처리는 하지 않는다(게시판 화면은 별도 POST로 보내지만 앱은 보내지 않는다).
/// 오류 문구·로그에는 경로·실행 링크·토큰을 넣지 않는다. LearningX가 토큰을 거절해도 eTL 토큰 만료(401)로 보이지 않게 한다.
public final class BoardClient: @unchecked Sendable {
    private let etl: EtlClient
    private let owner: String
    private let transport: BoardTransport
    private let now: @Sendable () -> Int64
    private let base: String

    /// - Parameters:
    ///   - owner: [BoardSession.ownerOf] (eTL 토큰 해시).
    ///   - now: 현재 시각(ms).
    public init(_ etl: EtlClient, owner: String, transport: BoardTransport = URLSessionBoardTransport.shared,
                now: @escaping @Sendable () -> Int64 = { Date().epochMilli }) {
        self.etl = etl
        self.owner = owner
        self.transport = transport
        self.now = now
        self.base = etl.origin
    }

    private struct Course {
        let id: Int64
        let tool: Int64
        let base: String
        var url: String { "\(base)/courses/\(id)/external_tools/\(tool)" }
    }

    private var api: String { base + BoardClient.API_PATH }

    private func open(_ courseId: Int64) async throws -> Course {
        if courseId <= 0 { throw EtlException("course_id 가 필요합니다(양의 정수)") }
        let tabs: Any
        do {
            tabs = try await etl.get("/api/v1/courses/\(courseId)/tabs")
        } catch let e as EtlAuthException {
            throw e
        } catch is EtlException {
            throw EtlException(BoardClient.NO_BOARD, kind: "no-board")
        }
        guard let tool = BoardLogic.boardToolId(tabs) else { throw EtlException(BoardClient.NO_BOARD, kind: "no-board") }
        return Course(id: courseId, tool: tool, base: base)
    }

    public func boards(_ courseId: Int64) async throws -> JSONObject {
        let c = try await open(courseId)
        return BoardLogic.boardsResult(c.id, try await apiGet(c, "/courses/\(c.id)/boards"), c.url)
    }

    public func posts(_ courseId: Int64, _ boardId: Int64, _ page: Int, _ keyword: String) async throws -> JSONObject {
        if boardId <= 0 { throw EtlException("board_id 가 필요합니다(양의 정수)") }
        let c = try await open(courseId)
        let p = min(max(page, 1), 1000)
        let q = "page=\(p)&filter=title&keyword=" + BoardLogic.urlEncode(keyword.kTake(100))
        return BoardLogic.postsResult(c.id, boardId, p, try await apiGet(c, "/courses/\(c.id)/boards/\(boardId)/posts?\(q)"), c.url)
    }

    public func post(_ courseId: Int64, _ boardId: Int64, _ postId: Int64) async throws -> JSONObject {
        if boardId <= 0 { throw EtlException("board_id 가 필요합니다(양의 정수)") }
        if postId <= 0 { throw EtlException("post_id 가 필요합니다(양의 정수)") }
        let c = try await open(courseId)
        guard let p = try await apiGet(c, "/courses/\(c.id)/boards/\(boardId)/posts/\(postId)") as? JSONObject else {
            throw EtlException("게시판 응답을 해석할 수 없습니다", kind: "parse")
        }
        return BoardLogic.postResult(c.id, boardId, postId, p, c.url, base: base)
    }

    /// 파일 [fileId]가 그 글의 첨부인지 확인하고 글 제목을 돌려준다. 아니면 오류.
    public func attachmentTitle(_ courseId: Int64, _ boardId: Int64, _ postId: Int64, _ fileId: Int64) async throws -> String? {
        let c = try await open(courseId)
        guard let p = try await apiGet(c, "/courses/\(c.id)/boards/\(boardId)/posts/\(postId)") as? JSONObject else {
            throw EtlException("게시판 응답을 해석할 수 없습니다", kind: "parse")
        }
        if !BoardLogic.hasAttachment(p, fileId) {
            throw EtlException("이 게시글에 파일 ID \(fileId) 인 첨부가 없습니다. etl_board_post의 attachments에서 file_id를 확인하세요.")
        }
        return p.has("title") && !p.isNull("title") ? p.optString("title") : nil
    }

    // MARK: LearningX API

    private static let DIGITS = KRegex("\\d+")

    /// 토큰을 거절하면(400/401) 한 번만 다시 실행한다.
    private func apiGet(_ c: Course, _ pathAndQuery: String) async throws -> Any {
        let what = BoardClient.DIGITS.replace(pathAndQuery.substringBefore("?"), "#")
        for _ in 0..<2 {
            let xn = try await token(c)
            let r = try await http(api + pathAndQuery, "GET", ["Authorization": "Bearer \(xn)", "Accept": "application/json"], nil, what)
            switch r.code {
            case 400, 401:
                BoardSession.drop(owner)
            case 403, 404:
                throw EtlException("게시판에서 이 항목을 찾을 수 없거나 볼 권한이 없습니다(\(r.code)).", kind: "http-\(r.code)")
            case 200...299:
                do {
                    return try EtlClient.parseBody(r.body, what)
                } catch {
                    throw EtlException("게시판 응답을 해석할 수 없습니다: \(what)", kind: "parse")
                }
            default:
                throw EtlException("게시판 요청 실패(\(r.code)): \(what)", kind: "http-\(r.code)")
            }
        }
        throw EtlException(BoardClient.REJECTED, kind: "board-rejected")
    }

    private func token(_ c: Course) async throws -> String {
        if let t = BoardSession.get(owner, now()) { return t }
        await BoardSession.launchLock.acquire()
        do {
            if let t = BoardSession.get(owner, now()) {
                await BoardSession.launchLock.release()
                return t
            }
            let t = try await launch(c)
            await BoardSession.launchLock.release()
            return t
        } catch {
            await BoardSession.launchLock.release()
            throw error
        }
    }

    private static let PRINTABLE = KRegex("[\\u0021-\\u007e]+")

    /// 위 2~4단계.
    private func launch(_ c: Course) async throws -> String {
        let failed = EtlException(BoardClient.LAUNCH_FAILED, kind: "board-launch")
        let link: JSONObject?
        do {
            link = try await etl.get("/api/v1/courses/\(c.id)/external_tools/sessionless_launch?id=\(c.tool)&launch_type=course_navigation") as? JSONObject
        } catch let e as EtlAuthException {
            throw e
        } catch is EtlException {
            throw failed
        }
        let url = link?.optString("url")
        guard let url, BoardLogic.isLaunchUrl(url, base: base) else { throw failed }
        guard let form = BoardLogic.launchForm(try await getPage(url), base: base) else { throw failed }
        let r = try await http(form.action, "POST",
                               ["Content-Type": "application/x-www-form-urlencoded", "Accept": "text/html"],
                               BoardLogic.formBody(form.fields), "게시판 실행")
        guard let xn = BoardLogic.cookieValue(r.setCookies, "xn_api_token"), BoardClient.PRINTABLE.matches(xn) else { throw failed }
        let at = now()
        let until = BoardLogic.jwtExpiryMillis(xn).map { $0 - BoardClient.EXPIRY_MARGIN_MS } ?? (at + BoardClient.FALLBACK_TTL_MS)
        if until <= at { throw failed }
        BoardSession.set(owner, xn, until)
        return xn
    }

    /// eTL 호스트 안에서만 리다이렉트를 따라가며 GET한다(토큰 없이: 실행 링크 자체가 일회용 서명을 갖는다).
    private func getPage(_ start: String) async throws -> String {
        let failed = EtlException(BoardClient.LAUNCH_FAILED, kind: "board-launch")
        var current = start
        for _ in 0...BoardClient.MAX_REDIRECTS {
            let r = try await http(current, "GET", ["Accept": "text/html"], nil, "게시판 실행")
            if (300...399).contains(r.code) {
                guard let cur = URL(string: current), let loc = r.location,
                      let next = URL(string: loc, relativeTo: cur)?.absoluteURL,
                      BoardLogic.origin(next) == base else { throw failed }
                current = next.absoluteString
                continue
            }
            if !(200...299).contains(r.code) || r.body.utf16.count > BoardClient.MAX_HTML_CHARS { throw failed }
            return r.body
        }
        throw failed
    }

    /// eTL 호스트로만 보낸다. 오류 문구에는 [what]만 쓴다(URL에는 서명이 있을 수 있다).
    /// POST는 LearningX LTI 실행 경로(`/learningx/lti/…`)에만 보낸다.
    private func http(_ url: String, _ method: String, _ headers: [String: String], _ body: String?, _ what: String) async throws -> BoardHTTPResponse {
        guard etl.fake == nil else { throw EtlException("가상 eTL에서는 외부 게시판에 접속하지 않아요", kind: "fake-unsupported") }
        guard let u = URL(string: url), BoardLogic.origin(u) == base else { throw EtlException(BoardClient.LAUNCH_FAILED, kind: "board-launch") }
        if method != "GET" {
            let path = URLComponents(url: u, resolvingAgainstBaseURL: true)?.percentEncodedPath ?? ""
            guard method == "POST", path.hasPrefix(BoardLogic.LTI_PATH) else { throw EtlException(BoardClient.LAUNCH_FAILED, kind: "board-launch") }
        }
        var req = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: TimeInterval(BoardClient.READ_TIMEOUT_MS) / 1000)
        req.httpMethod = method
        req.httpShouldHandleCookies = false
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let body { req.httpBody = Data(body.utf8) }
        let transport = self.transport
        let request = req
        return try await EtlClient.gated {
            do {
                let r = try await transport.send(request)
                boardLog.notice("\(method, privacy: .public) board \(what, privacy: .public) -> \(r.code)")
                return r
            } catch {
                if error is CancellationError || Task.isCancelled || (error as? URLError)?.code == .cancelled { throw CancellationError() }
                if let e = error as? EtlException { throw e }
                let timeout = (error as? URLError)?.code == .timedOut
                throw EtlException(timeout ? "eTL 응답 시간 초과: \(what)" : "eTL 연결 실패: \(what)", kind: EtlClient.ioKind(error))
            }
        }
    }

    // MARK: companion

    public static let API_PATH = "/learningx/api/v1/learningx_board"
    public static let API = BoardLogic.BASE + API_PATH
    public static let MAX_REDIRECTS = 3
    /// 게시글 JSON도 이 한도 안에서 읽는다.
    public static let MAX_HTML_CHARS = 2 * 1024 * 1024
    public static let READ_TIMEOUT_MS = 20_000
    public static let EXPIRY_MARGIN_MS: Int64 = 5 * 60 * 1000
    public static let FALLBACK_TTL_MS: Int64 = 30 * 60 * 1000
    public static let NO_BOARD = "이 과목에는 게시판 메뉴가 없습니다. eTL에서 과목 메뉴를 확인해 주세요."
    public static let LAUNCH_FAILED = "게시판에 연결하지 못했습니다. 잠시 후 다시 시도하고, 계속되면 eTL에서 게시판을 직접 열어 주세요."
    public static let REJECTED = "게시판이 접속을 거절했습니다. 잠시 후 다시 시도하고, 계속되면 eTL에서 게시판을 직접 열어 주세요."
}
