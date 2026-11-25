import Foundation
import Testing
@testable import ETLKit

/// Kotlin `BoardTimetableTest`의 게시판 부분 + iOS BoardClient 흐름(가짜 HTTP, 실제 네트워크 없음).
/// BoardSession은 앱 전체에 하나뿐이라 이 묶음은 차례로 돈다.
@Suite(.serialized) struct BoardTests {
    private let base = EtlClient.BASE_URL

    private func jwt(_ exp: Int64) -> String {
        func enc(_ s: String) -> String {
            Data(s.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return [enc("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"), enc("{\"role\":\"student\",\"iat\":\(exp - 7200),\"exp\":\(exp)}"), "sig"].joined(separator: ".")
    }

    private func launchPage(_ base: String) -> String {
        """
        <!doctype html><html><body>
        <form action="\(base)/learningx/lti/learningx_board/boards" method="POST" id="tool_form">
          <input type="hidden" name="oauth_signature" value="private-oauth-signature">
          <input type="hidden" name="context_title" value="2026-2 &quot;제어&quot; &amp; 공학">
          <input type="hidden" name="custom_canvas_course_id" value="305930">
        </form><script>document.getElementById('tool_form').submit()</script></body></html>
        """
    }

    private func fields(_ f: BoardLogic.LaunchForm) -> [String: String] {
        Dictionary(f.fields, uniquingKeysWith: { a, _ in a })
    }

    // MARK: Kotlin 사례

    @Test func launchFormPicksLearningxFormAndDecodesValues() throws {
        let f = try #require(BoardLogic.launchForm(launchPage(base)))
        #expect(f.action == "\(base)/learningx/lti/learningx_board/boards")
        #expect(fields(f)["context_title"] == "2026-2 \"제어\" & 공학")
        #expect(fields(f)["oauth_signature"] == "private-oauth-signature")
        #expect(BoardLogic.formBody(f.fields).contains("context_title=2026-2+%22%EC%A0%9C%EC%96%B4%22+%26+%EA%B3%B5%ED%95%99"))
    }

    @Test func launchFormRejectsOtherHostsAndPaths() {
        #expect(BoardLogic.launchForm(launchPage(base).replacingOccurrences(of: "\(base)/learningx/lti/", with: "https://evil.example/learningx/lti/")) == nil)
        #expect(BoardLogic.launchForm(launchPage(base).replacingOccurrences(of: "/learningx/lti/", with: "/login/")) == nil)
        #expect(BoardLogic.launchForm("<html>no form</html>") == nil)
        // 점 경로로 LTI 경로를 벗어나는 action도 받지 않는다.
        #expect(BoardLogic.launchForm(launchPage(base).replacingOccurrences(of: "/learningx/lti/learningx_board/boards", with: "/learningx/lti/../../login")) == nil)
    }

    @Test func jwtExpiryAndCookie() {
        #expect(BoardLogic.jwtExpiryMillis(jwt(1_800_000_000)) == 1_800_000_000_000)
        #expect(BoardLogic.jwtExpiryMillis("not-a-jwt") == nil)
        let cookies = ["WMONID=abc; Path=/", "xn_api_token=\(jwt(1_800_000_000)); Path=/; HttpOnly"]
        #expect(BoardLogic.cookieValue(cookies, "xn_api_token") == jwt(1_800_000_000))
        #expect(BoardLogic.cookieValue(cookies, "missing") == nil)
    }

    @Test func boardToolAndLaunchUrl() {
        let tabs = jsonArray(#"[{"id":"home","type":"internal","label":"홈"},{"id":"context_external_tool_85","type":"external","label":"게시판"},{"id":"context_external_tool_48","type":"external","label":"강의/출결"}]"#)
        #expect(BoardLogic.boardToolId(tabs) == 85)
        #expect(BoardLogic.boardToolId(jsonArray(#"[{"id":"context_external_tool_48","type":"external","label":"강의/출결"}]"#)) == nil)
        #expect(BoardLogic.isLaunchUrl("\(base)/courses/305930/external_tools/sessionless_launch?verifier=x"))
        #expect(!BoardLogic.isLaunchUrl("https://evil.example/courses/305930/external_tools/sessionless_launch?verifier=x"))
        #expect(!BoardLogic.isLaunchUrl("\(base)/login/session_token?x=1"))
        #expect(!BoardLogic.isLaunchUrl("https://user@myetl.snu.ac.kr/courses/305930/external_tools/sessionless_launch?verifier=x"))
    }

    @Test func boardsAndPostsMapping() throws {
        let boards = BoardLogic.boardsResult(305930, jsonArray(#"[{"id":153242,"type":"anonymous","title":"아무거나 게시판","description":"<p>무기명 게시판</p>","total_post_count":18,"unread_post_count":2,"latest_post_created_at":"2026-10-15T17:19:18.000000Z"}]"#), "u")
        let b = try boards.getJSONArray("boards").getJSONObject(0)
        #expect(try b.getLong("board_id") == 153242)
        #expect(try b.getBoolean("anonymous"))
        #expect(try b.getString("description") == "무기명 게시판")
        #expect(try b.getInt("unread") == 2)
        #expect(try b.getString("latest_post") == "10-16(금) 02:19")

        let posts = BoardLogic.postsResult(305930, 153242, 1, jsonObject(#"""
            {"items":[
            {"id":779536,"depth":0,"user_name":"조교","title":"중간고사 공지","is_notice":true,"view_count":89,"attachment_count":0,"comment_count":0,"created_at":"2026-10-13T13:15:56.000000Z"},
            {"id":781528,"depth":1,"user_name":"익명","title":"Re: 질문","is_notice":false,"attachment_count":1,"comment_count":2,"created_at":"2026-10-15T17:19:18.000000Z"}],
            "pagination":{"total":21,"current_page":1,"last_page":2}}
            """#), "u")
        #expect(try posts.getInt("last_page") == 2)
        let rows = try posts.getJSONArray("posts")
        #expect(try rows.getJSONObject(0).getBoolean("notice"))
        #expect(try rows.getJSONObject(1).getBoolean("reply"))
        #expect(rows.getJSONObjectOrNil(1)?.isNull("views") == true)
    }

    @Test func postHidesSignedAttachmentLinks() throws {
        let p = jsonObject("""
            {"id":779536,"user_name":"조교","title":"중간고사 공지","is_notice":true,
            "content":"<p>일시: <strong>10월 22일</strong></p><p>장소: 301동</p>",
            "attachments":[{"id":787697,"filename":"좌석표.pdf","filesize":1234,"url":"\(base)/files/5/download?verifier=private-verifier-value","canvas_file_id":5}],
            "comments":[{"user_name":"학생","content":"<p>감사합니다</p>","created_at":"2026-10-14T00:00:00.000000Z"}],
            "post_url":"\(base)/learningx/redirect/courses/305930/external_tools/85?post_id=779536",
            "prev_post":{"id":777092,"title":"중간고사","user_name":"학생","created_at":"2026-10-11T12:56:04.000000Z"},"next_post":null}
            """)
        let r = BoardLogic.postResult(305930, 153242, 779536, p, "fallback")
        #expect(!r.toString().contains("private-verifier-value"))
        #expect(try r.getJSONArray("attachments").getJSONObject(0).getLong("file_id") == 5)
        #expect(try r.getString("text").contains("10월 22일"))
        #expect(try r.getJSONArray("comments").getJSONObject(0).getString("text") == "감사합니다")
        #expect(try r.getString("url") == "\(base)/learningx/redirect/courses/305930/external_tools/85?post_id=779536")
        #expect(try r.getJSONObject("previous").getLong("post_id") == 777092)
        #expect(r.isNull("next"))
        #expect(BoardLogic.hasAttachment(p, 5))
        #expect(!BoardLogic.hasAttachment(p, 6))
        #expect(BoardLogic.postLink(jsonObject(#"{"post_url":"https://evil.example/x"}"#), "fallback") == "fallback")
        #expect(try r.getString("attachment_note").contains("course_id=305930, board_id=153242, post_id=779536"))
    }

    // MARK: iOS 보조

    @Test func setCookieSplitAndEncoding() {
        let joined = "WMONID=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT, xn_api_token=a.b.c; Path=/; HttpOnly"
        let parts = BoardLogic.splitSetCookie(joined)
        #expect(parts.count == 2)
        #expect(BoardLogic.cookieValue(parts, "xn_api_token") == "a.b.c")
        #expect(BoardLogic.cookieValue(["x=%E2%9C%93+y"], "x") == "✓ y")
        #expect(BoardLogic.cookieValue(["x=%ZZ"], "x") == nil)
        #expect(BoardLogic.urlEncode("a b*~/가") == "a+b*%7E%2F%EA%B0%80")
        #expect(BoardLogic.decodeAttr("&#x41;&#66;&lt;&apos;&#39;&bogus;") == "AB<''&bogus;")
        #expect(BoardSession.ownerOf("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test func urlSessionTransportReadsStatusLocationAndCookies() async throws {
        let host = "board-\(UUID().uuidString.lowercased()).test"
        BoardStubProtocol.register(host) { req in
            #expect(req.value(forHTTPHeaderField: "Cookie") == nil)
            return (302, "", ["Location": "/next", "Set-Cookie": "a=1; Path=/, xn_api_token=tok.en; HttpOnly"])
        }
        let t = URLSessionBoardTransport(session: BoardStubProtocol.session())
        let r = try await t.send(URLRequest(url: URL(string: "https://\(host)/x")!))
        #expect(r.code == 302)
        #expect(r.location == "/next")
        #expect(BoardLogic.cookieValue(r.setCookies, "xn_api_token") == "tok.en")
    }

    // MARK: BoardClient 흐름

    private static let token = "tok-SECRET-123"
    private static let nowMs: Int64 = 1_700_000_000_000

    private struct Setup {
        let host: String
        let origin: String
        let transport: FakeBoardTransport
        let client: BoardClient
        let owner: String
    }

    /// 가짜 eTL(탭·실행 링크)과 가짜 LearningX. [launchUrl]이 nil이면 정상 실행 링크.
    private func setup(tabs: String? = nil, launchUrl: ((String) -> String)? = nil) throws -> Setup {
        let host = "board-\(UUID().uuidString.lowercased()).test"
        let origin = "https://\(host)"
        let tabsJson = tabs ?? #"[{"id":"home","type":"internal","label":"홈"},{"id":"context_external_tool_85","type":"external","label":"게시판"}]"#
        let launch = launchUrl?(origin) ?? "\(origin)/courses/305930/external_tools/sessionless_launch?verifier=SECRET-VERIFIER"
        BoardStubProtocol.register(host) { req in
            #expect(req.httpMethod == "GET") // Canvas API에는 GET만
            let path = req.url?.path ?? ""
            if path == "/api/v1/courses/305930/tabs" { return (200, tabsJson, [:]) }
            if path == "/api/v1/courses/305930/external_tools/sessionless_launch" {
                #expect(req.url?.query == "id=85&launch_type=course_navigation")
                return (200, "{\"url\":\"\(launch)\"}", [:])
            }
            return (404, "{}", [:])
        }
        let etl = try EtlClient(BoardTests.token, origin: origin, session: BoardStubProtocol.session())
        let transport = FakeBoardTransport(origin: origin, page: launchPage(origin), jwt: jwt(1_800_000_000))
        let owner = BoardSession.ownerOf(BoardTests.token + host)
        BoardSession.clear()
        return Setup(host: host, origin: origin, transport: transport,
                     client: BoardClient(etl, owner: owner, transport: transport, now: { BoardTests.nowMs }), owner: owner)
    }

    @Test func launchOncePostOnlyTheSignedFieldsAndReuseToken() async throws {
        let s = try setup()
        let boards = try await s.client.boards(305930)
        #expect(boards.toString().contains("아무거나 게시판"))
        #expect(boards.optString("url") == "\(s.origin)/courses/305930/external_tools/85")
        let posts = try await s.client.posts(305930, 153242, 0, "중간 고사")
        #expect(posts.optInt("page") == 1)
        let post = try await s.client.post(305930, 153242, 779536)
        #expect(post.optString("title") == "중간고사 공지")
        #expect(try await s.client.attachmentTitle(305930, 153242, 779536, 5) == "중간고사 공지")
        await #expect(throws: EtlException.self) { try await s.client.attachmentTitle(305930, 153242, 779536, 6) }

        let reqs = s.transport.requests
        let posts1 = reqs.filter { $0.httpMethod == "POST" }
        #expect(posts1.count == 1) // 실행은 한 번, 토큰은 메모리에서 다시 쓴다
        let lti = try #require(posts1.first)
        #expect(lti.url?.absoluteString == "\(s.origin)/learningx/lti/learningx_board/boards")
        #expect(lti.value(forHTTPHeaderField: "Content-Type") == "application/x-www-form-urlencoded")
        #expect(lti.value(forHTTPHeaderField: "Authorization") == nil)
        let form = try #require(BoardLogic.launchForm(launchPage(s.origin), base: s.origin))
        #expect(lti.httpBody.map { String(decoding: $0, as: UTF8.self) } == BoardLogic.formBody(form.fields))
        // 모든 요청은 eTL 호스트로만, 실행 링크에는 eTL 토큰을 붙이지 않는다.
        for r in reqs {
            #expect(r.url?.host == s.host)
            #expect(r.value(forHTTPHeaderField: "Authorization") != "Bearer \(BoardTests.token)")
            #expect(r.httpShouldHandleCookies == false)
        }
        let api = reqs.filter { $0.url?.path.hasPrefix(BoardClient.API_PATH) == true }
        #expect(api.count == 5)
        #expect(api.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer \(jwt(1_800_000_000))" })
        #expect(api[1].url?.query == "page=1&filter=title&keyword=%EC%A4%91%EA%B0%84+%EA%B3%A0%EC%82%AC")
        // 결과에는 LearningX 토큰·실행 서명이 없다.
        for out in [boards, posts, post] {
            #expect(!out.toString().contains(jwt(1_800_000_000)))
            #expect(!out.toString().contains("SECRET-VERIFIER"))
        }
        #expect(BoardSession.get(s.owner, BoardTests.nowMs) == jwt(1_800_000_000))
        #expect(BoardSession.get("someone-else", BoardTests.nowMs) == nil)
        BoardSession.clear()
        #expect(BoardSession.get(s.owner, BoardTests.nowMs) == nil)
    }

    @Test func rejectedTokenRelaunchesOnceThenGivesUp() async throws {
        let s = try setup()
        s.transport.rejectApi = 1
        _ = try await s.client.boards(305930)
        #expect(s.transport.requests.filter { $0.httpMethod == "POST" }.count == 2)

        let t = try setup()
        t.transport.rejectApi = 100
        do {
            _ = try await t.client.boards(305930)
            Issue.record("거절되어야 한다")
        } catch let e as EtlException {
            #expect(e.message == BoardClient.REJECTED)
            #expect(!(e is EtlAuthException)) // eTL 토큰 만료로 보이지 않게
            #expect(!e.message.contains(jwt(1_800_000_000)))
        }
        #expect(t.transport.requests.filter { $0.httpMethod == "POST" }.count == 2)
    }

    @Test func launchFailuresNeverLeaveTheEtlHost() async throws {
        // 게시판 탭이 없다
        let a = try setup(tabs: #"[{"id":"context_external_tool_48","type":"external","label":"강의/출결"}]"#)
        await #expect { try await a.client.boards(305930) } throws: { ($0 as? EtlException)?.message == BoardClient.NO_BOARD }
        #expect(a.transport.requests.isEmpty)

        // 실행 링크가 eTL 밖
        let b = try setup(launchUrl: { _ in "https://evil.example/courses/305930/external_tools/sessionless_launch?verifier=v" })
        await #expect { try await b.client.boards(305930) } throws: { ($0 as? EtlException)?.message == BoardClient.LAUNCH_FAILED }
        #expect(b.transport.requests.isEmpty)

        // 실행 페이지가 eTL 밖으로 리다이렉트
        let c = try setup()
        c.transport.redirectTo = "https://evil.example/steal"
        await #expect { try await c.client.boards(305930) } throws: { e in
            let m = (e as? EtlException)?.message ?? ""
            return m == BoardClient.LAUNCH_FAILED && !m.contains("SECRET-VERIFIER")
        }
        #expect(c.transport.requests.allSatisfy { $0.url?.host == c.host })
        #expect(c.transport.requests.filter { $0.httpMethod == "POST" }.isEmpty)

        // 잘못된 인자
        await #expect { try await c.client.posts(305930, 0, 1, "") } throws: { ($0 as? EtlException)?.message == "board_id 가 필요합니다(양의 정수)" }
        await #expect { try await c.client.boards(0) } throws: { ($0 as? EtlException)?.message == "course_id 가 필요합니다(양의 정수)" }
    }

    @Test func networkErrorsNameOnlyTheStep() async throws {
        let s = try setup()
        s.transport.fail = URLError(.timedOut)
        do {
            _ = try await s.client.boards(305930)
            Issue.record("실패해야 한다")
        } catch let e as EtlException {
            #expect(e.message == "eTL 응답 시간 초과: 게시판 실행")
            #expect(e.kind == "timeout")
        }
    }
}

extension JSONArray {
    fileprivate func getJSONObjectOrNil(_ i: Int) -> JSONObject? { optJSONObject(i) }
}

/// 가짜 LearningX(eTL 호스트의 실행 페이지·LTI·게시판 API).
final class FakeBoardTransport: BoardTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let origin: String
    private let page: String
    private let jwt: String
    private var _requests: [URLRequest] = []
    private var _rejectApi = 0
    private var _redirectTo: String?
    private var _fail: Error?

    init(origin: String, page: String, jwt: String) {
        self.origin = origin
        self.page = page
        self.jwt = jwt
    }

    var requests: [URLRequest] { lock.withLock { _requests } }
    /// 게시판 API가 앞의 몇 번을 401로 거절할지.
    var rejectApi: Int {
        get { lock.withLock { _rejectApi } }
        set { lock.withLock { _rejectApi = newValue } }
    }
    var redirectTo: String? {
        get { lock.withLock { _redirectTo } }
        set { lock.withLock { _redirectTo = newValue } }
    }
    var fail: Error? {
        get { lock.withLock { _fail } }
        set { lock.withLock { _fail = newValue } }
    }

    func send(_ request: URLRequest) async throws -> BoardHTTPResponse {
        let (fail, redirect) = lock.withLock { () -> (Error?, String?) in
            _requests.append(request)
            return (_fail, _redirectTo)
        }
        if let fail { throw fail }
        let path = request.url?.path ?? ""
        switch (request.httpMethod ?? "GET", path) {
        case ("GET", "/courses/305930/external_tools/sessionless_launch"):
            return BoardHTTPResponse(code: 302, body: "", location: redirect ?? "/courses/305930/external_tools/85/launch")
        case ("GET", "/courses/305930/external_tools/85/launch"):
            return BoardHTTPResponse(code: 200, body: page)
        case ("POST", "/learningx/lti/learningx_board/boards"):
            return BoardHTTPResponse(code: 200, body: "<html></html>", setCookies: ["WMONID=x; Path=/", "xn_api_token=\(jwt); Path=/; HttpOnly"])
        default:
            break
        }
        guard path.hasPrefix(BoardClient.API_PATH) else { return BoardHTTPResponse(code: 404, body: "") }
        let reject = lock.withLock { () -> Bool in
            if _rejectApi > 0 { _rejectApi -= 1; return true }
            return false
        }
        if reject || request.value(forHTTPHeaderField: "Authorization") != "Bearer \(jwt)" { return BoardHTTPResponse(code: 401, body: "{}") }
        let rest = String(path.dropFirst(BoardClient.API_PATH.count))
        switch rest {
        case "/courses/305930/boards":
            return BoardHTTPResponse(code: 200, body: #"[{"id":153242,"type":"anonymous","title":"아무거나 게시판","total_post_count":18}]"#)
        case "/courses/305930/boards/153242/posts":
            return BoardHTTPResponse(code: 200, body: #"{"items":[{"id":779536,"title":"중간고사 공지","is_notice":true}],"pagination":{"current_page":1,"last_page":1,"total":1}}"#)
        case "/courses/305930/boards/153242/posts/779536":
            return BoardHTTPResponse(code: 200, body: """
                {"id":779536,"title":"중간고사 공지","content":"<p>본문</p>","attachments":[{"filename":"좌석표.pdf","canvas_file_id":5,"url":"\(origin)/files/5/download?verifier=SECRET-VERIFIER"}],"post_url":"\(origin)/learningx/redirect/x"}
                """)
        default:
            return BoardHTTPResponse(code: 404, body: "{}")
        }
    }
}

/// 실제 네트워크 대신 응답을 꾸며 주는 URLProtocol(호스트별 처리기).
final class BoardStubProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) -> (Int, String, [String: String])
    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]

    static func register(_ host: String, _ h: @escaping Handler) { lock.withLock { handlers[host] = h } }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [BoardStubProtocol.self]
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let h = BoardStubProtocol.lock.withLock({ BoardStubProtocol.handlers[request.url?.host ?? ""] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let (code, body, headers) = h(request)
        var all = ["Content-Type": "application/json"]
        for (k, v) in headers { all[k] = v }
        let resp = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: all)!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
