import Foundation
import CryptoKit

/*
 * 과목 게시판(LearningX) — Kotlin `etl/Board.kt`의 순수 로직(`BoardLogic`)과 메모리 세션(`BoardSession`).
 *
 * eTL 과목 메뉴의 "게시판"은 Canvas 토론이 아니라 같은 호스트의 LTI 도구(LearningX)이고, JSON API가
 * /learningx/api/v1/learningx_board/ 아래에 있다. 이 API는 Canvas 토큰이 아니라 LearningX 자체 토큰
 * (브라우저가 xn_api_token 쿠키로 갖는 JWT, 약 2시간)을 요구한다. 브라우저와 같은 방법으로 받는다([BoardClient]).
 *
 * 실행 링크·폼·LearningX 토큰은 ETLKit 밖으로 나가지 않는다(어떤 결과나 오류 문구·로그에도 넣지 않는다).
 */

/// 순수 로직(네트워크 없음).
public enum BoardLogic {
    public static let BASE = EtlClient.BASE_URL
    public static let LTI_PATH = "/learningx/lti/"
    private static let LAUNCH_PATH = KRegex("^/courses/\\d+/external_tools/sessionless_launch$")
    private static let BOARD_LABEL = KRegex("게시판|board", .ignoreCase)
    private static let TOOL_ID = KRegex("^context_external_tool_(\\d+)$")
    public static let POST_LIMIT = 8000
    public static let COMMENT_LIMIT = 2000
    public static let DESCRIPTION_LIMIT = 500

    /// Canvas가 돌려준 LTI 실행 폼. [fields]는 폼에 있던 순서 그대로의 (이름, 값).
    public struct LaunchForm: @unchecked Sendable {
        public let action: String
        public let fields: [(String, String)]
        public init(action: String, fields: [(String, String)]) {
            self.action = action
            self.fields = fields
        }
    }

    /// 과목 탭 중 "게시판" 외부 도구의 ID. 없으면 nil.
    public static func boardToolId(_ tabs: Any?) -> Int64? {
        guard let arr = tabs as? JSONArray else { return nil }
        for i in 0..<arr.length() {
            guard let t = arr.optJSONObject(i) else { continue }
            if t.optString("type") != "external" { continue }
            if !BOARD_LABEL.containsMatchIn(t.optString("label")) { continue }
            if let m = TOOL_ID.find(t.optString("id")) { return Int64(m.groupValues[1]) }
        }
        return nil
    }

    /// Java `"${u.protocol}://${u.authority}"`. 사용자 정보가 있으면 nil(그런 주소는 받지 않는다).
    public static func origin(_ url: URL) -> String? {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: true), let scheme = c.scheme, let host = c.percentEncodedHost,
              !host.isEmpty, c.user == nil, c.password == nil else { return nil }
        return "\(scheme)://\(host)" + (c.port.map { ":\($0)" } ?? "")
    }

    /// [url]이 [base](eTL) 위의 주소인가(스킴·호스트·포트가 같고 사용자 정보 없음).
    public static func isOn(_ url: String?, _ base: String = BASE) -> Bool {
        guard let url, let u = URL(string: url) else { return false }
        return origin(u) == base
    }

    /// 실행 링크가 eTL의 sessionless_launch 경로인지.
    public static func isLaunchUrl(_ url: String?, base: String = BASE) -> Bool {
        guard let url, let u = URL(string: url), origin(u) == base,
              let path = URLComponents(url: u, resolvingAgainstBaseURL: true)?.percentEncodedPath else { return false }
        return LAUNCH_PATH.matches(path)
    }

    private static let ENTITY = KRegex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|#39);", .ignoreCase)

    /// HTML 속성값의 문자 참조를 푼다.
    public static func decodeAttr(_ s: String) -> String {
        ENTITY.replace(s) { m in
            let n = m.groupValues[1].lowercased()
            switch n {
            case "amp": return "&"
            case "lt": return "<"
            case "gt": return ">"
            case "quot": return "\""
            case "apos", "#39": return "'"
            default:
                let digits = n.dropFirst()
                let code: UInt32? = digits.first == "x" ? UInt32(digits.dropFirst(), radix: 16) : UInt32(digits)
                if let code, let scalar = Unicode.Scalar(code) { return String(Character(scalar)) }
                return m.value
            }
        }
    }

    private static func attr(_ attrs: String, _ name: String) -> String? {
        let re = KRegex("(?:^|\\s)\(name)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", .ignoreCase)
        guard let m = re.find(attrs) else { return nil }
        guard let v = m.groups[1] ?? m.groups[2] ?? m.groups[3] else { return nil }
        return decodeAttr(v)
    }

    private static let FORM = KRegex("<form\\b([^>]*)>([\\s\\S]*?)</form>", .ignoreCase)
    private static let INPUT = KRegex("<input\\b([^>]*)>", .ignoreCase)

    /// Canvas 실행 페이지에서 eTL 호스트의 LearningX로 POST하는 첫 폼. HTML은 읽기만 한다(실행하지 않는다).
    public static func launchForm(_ html: String, base: String = BASE) -> LaunchForm? {
        for m in FORM.findAll(html) {
            guard let baseUrl = URL(string: base + "/"),
                  let action = URL(string: attr(m.groupValues[1], "action") ?? "", relativeTo: baseUrl)?.absoluteURL.standardized,
                  origin(action) == base,
                  let path = URLComponents(url: action, resolvingAgainstBaseURL: true)?.percentEncodedPath,
                  path.hasPrefix(LTI_PATH) else { continue }
            var fields: [(String, String)] = []
            for input in INPUT.findAll(m.groupValues[2]) {
                guard let name = attr(input.groupValues[1], "name") else { continue }
                fields.append((name, attr(input.groupValues[1], "value") ?? ""))
            }
            return LaunchForm(action: action.absoluteString, fields: fields)
        }
        return nil
    }

    /// JWT의 만료 시각(ms). 서명은 LearningX가 확인하므로 payload만 읽는다.
    public static func jwtExpiryMillis(_ token: String) -> Int64? {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count > 1 else { return nil }
        var b64 = String(parts[1])
        while b64.hasSuffix("=") { b64.removeLast() }
        if b64.contains("+") || b64.contains("/") { return nil } // URL-safe 알파벳만
        b64 = b64.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let o = try? JSONObject(String(decoding: data, as: UTF8.self)) else { return nil }
        guard o.has("exp"), !o.isNull("exp"), let exp = try? o.getDouble("exp"), exp.isFinite, abs(exp) < 9e15 else { return nil }
        return Int64(exp * 1000)
    }

    /// 한 헤더로 합쳐진 Set-Cookie(`a=1; Path=/, b=2; Expires=Wed, 21 Oct 2026 …`)를 쿠키마다 나눈다.
    /// URLSession은 같은 이름의 헤더를 쉼표로 합친다. Expires 안의 쉼표 뒤에는 `이름=`이 오지 않으므로 그 앞에서만 나눈다.
    public static func splitSetCookie(_ header: String?) -> [String] {
        guard let header, !header.isEmpty else { return [] }
        return SET_COOKIE_SPLIT.split(header).map { $0.kTrim }.filter { !$0.isEmpty }
    }

    private static let SET_COOKIE_SPLIT = KRegex(",(?=\\s*[!#$%&'*+\\-.^_`|~0-9A-Za-z]+=)")

    /// Set-Cookie 헤더들에서 [name] 쿠키 값.
    public static func cookieValue(_ setCookies: [String], _ name: String) -> String? {
        for c in setCookies {
            let pair = c.substringBefore(";")
            guard let eq = pair.firstIndex(of: "="), eq > pair.startIndex else { continue }
            if String(pair[..<eq]).kTrim == name {
                let raw = String(pair[pair.index(after: eq)...]).kTrim
                return raw.replacingOccurrences(of: "+", with: " ").removingPercentEncoding
            }
        }
        return nil
    }

    /// Java `URLEncoder.encode(s, "UTF-8")`(공백은 `+`).
    public static func urlEncode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            switch b {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"):
                out.append(Character(Unicode.Scalar(b)))
            case UInt8(ascii: " "):
                out.append("+")
            default:
                out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    public static func formBody(_ fields: [(String, String)]) -> String {
        fields.map { urlEncode($0.0) + "=" + urlEncode($0.1) }.joined(separator: "&")
    }

    private static func author(_ p: JSONObject?) -> String? { p?.str("user_name") ?? p?.str("nickname") }
    private static func kst(_ iso: String?) -> String? { EtlLogic.parseInstant(iso).map { EtlLogic.fmtKst($0) } }
    private static func validId(_ v: Int64) -> Bool { v > 0 }
    private static func optIntOrNull(_ o: JSONObject, _ key: String) -> Any { o.has(key) ? o.optInt(key) : JSONObject.NULL }

    /// LearningX가 준 글 링크가 eTL 안이면 그대로, 아니면 [fallback].
    public static func postLink(_ p: JSONObject?, _ fallback: String, base: String = BASE) -> String {
        guard let raw = p?.str("post_url"), let u = URL(string: raw), origin(u) == base else { return fallback }
        return u.absoluteString
    }

    public static func boardsResult(_ courseId: Int64, _ list: Any?, _ url: String) -> JSONObject {
        let rows = JSONArray()
        if let arr = list as? JSONArray {
            for i in 0..<arr.length() {
                guard let b = arr.optJSONObject(i) else { continue }
                rows.put(JSONObject()
                    .put("board_id", b.optLong("id"))
                    .put("title", b.str("title") ?? JSONObject.NULL)
                    .put("anonymous", b.optString("type") == "anonymous")
                    .put("description", EtlLogic.htmlToText(b.str("description"), DESCRIPTION_LIMIT))
                    .put("posts", optIntOrNull(b, "total_post_count"))
                    .put("unread", optIntOrNull(b, "unread_post_count"))
                    .put("latest_post", kst(b.str("latest_post_created_at")) ?? JSONObject.NULL))
            }
        }
        return JSONObject().put("course_id", courseId).put("boards", rows).put("url", url)
    }

    public static func postsResult(_ courseId: Int64, _ boardId: Int64, _ page: Int, _ out: Any?, _ url: String) -> JSONObject {
        let o = out as? JSONObject ?? JSONObject()
        let items = o.optJSONArray("items") ?? JSONArray()
        let pg = o.optJSONObject("pagination") ?? JSONObject()
        let rows = JSONArray()
        for i in 0..<items.length() {
            guard let p = items.optJSONObject(i) else { continue }
            rows.put(JSONObject()
                .put("post_id", p.optLong("id"))
                .put("title", p.str("title") ?? JSONObject.NULL)
                .put("author", author(p) ?? JSONObject.NULL)
                .put("notice", p.optBoolean("is_notice", false))
                .put("reply", p.optInt("depth", 0) > 0)
                .put("secret", p.optBoolean("is_secret", false))
                .put("attachments", p.optInt("attachment_count", 0))
                .put("comments", p.optInt("comment_count", 0))
                .put("views", optIntOrNull(p, "view_count"))
                .put("created", kst(p.str("created_at")) ?? JSONObject.NULL))
        }
        return JSONObject()
            .put("course_id", courseId)
            .put("board_id", boardId)
            .put("page", pg.has("current_page") ? pg.optInt("current_page") : page)
            .put("last_page", optIntOrNull(pg, "last_page"))
            .put("total", optIntOrNull(pg, "total"))
            .put("posts", rows)
            .put("url", url)
    }

    /// 첨부 하나: 이름, 크기, Canvas 파일 ID. LearningX가 준 링크(서명 포함)는 넘기지 않는다.
    private static func attachmentRow(_ a: JSONObject) -> JSONObject {
        let size: Any = a.has("filesize") ? a.optLong("filesize") : a.has("size") ? a.optLong("size") as Any : JSONObject.NULL
        let fid = a.optLong("canvas_file_id")
        return JSONObject()
            .put("name", a.str("filename") ?? a.str("display_name") ?? JSONObject.NULL)
            .put("size", size)
            .put("file_id", validId(fid) ? fid as Any : JSONObject.NULL)
    }

    private static func commentRow(_ c: JSONObject) -> JSONObject {
        let o = JSONObject()
            .put("author", author(c) ?? JSONObject.NULL)
            .put("text", EtlLogic.htmlToText(c.str("content") ?? c.str("comment") ?? c.str("body"), COMMENT_LIMIT))
            .put("created", kst(c.str("created_at")) ?? JSONObject.NULL)
        if let children = c.optJSONArray("children"), children.length() > 0 {
            let r = JSONArray()
            for i in 0..<children.length() { if let ch = children.optJSONObject(i) { r.put(commentRow(ch)) } }
            o.put("replies", r)
        }
        return o
    }

    private static func neighbour(_ p: JSONObject?) -> Any {
        guard let p, validId(p.optLong("id")) else { return JSONObject.NULL }
        return JSONObject()
            .put("post_id", p.optLong("id"))
            .put("title", p.str("title") ?? JSONObject.NULL)
            .put("author", author(p) ?? JSONObject.NULL)
            .put("created", kst(p.str("created_at")) ?? JSONObject.NULL)
    }

    public static func postResult(_ courseId: Int64, _ boardId: Int64, _ postId: Int64, _ p: JSONObject, _ url: String,
                                  base: String = BASE) -> JSONObject {
        let atts = JSONArray()
        if let arr = p.optJSONArray("attachments") {
            for i in 0..<arr.length() { if let a = arr.optJSONObject(i) { atts.put(attachmentRow(a)) } }
        }
        let comments = JSONArray()
        if let arr = p.optJSONArray("comments") {
            for i in 0..<arr.length() { if let c = arr.optJSONObject(i) { comments.put(commentRow(c)) } }
        }
        let id = validId(p.optLong("id")) ? p.optLong("id") : postId
        let out = JSONObject()
            .put("course_id", courseId)
            .put("board_id", boardId)
            .put("post_id", id)
            .put("title", p.str("title") ?? JSONObject.NULL)
            .put("author", author(p) ?? JSONObject.NULL)
            .put("notice", p.optBoolean("is_notice", false))
            .put("created", kst(p.str("created_at")) ?? JSONObject.NULL)
            .put("updated", kst(p.str("updated_at")) ?? JSONObject.NULL)
            .put("views", optIntOrNull(p, "view_count"))
            .put("text", EtlLogic.htmlToText(p.str("content"), POST_LIMIT))
            .put("attachments", atts)
            .put("comments", comments)
            .put("previous", neighbour(p.optJSONObject("prev_post")))
            .put("next", neighbour(p.optJSONObject("next_post")))
            .put("url", postLink(p, url, base: base))
        if atts.length() > 0 {
            out.put("attachment_note", "첨부파일은 etl_download에 file_id와 함께 course_id=\(courseId), board_id=\(boardId), post_id=\(id) 를 넘겨 받는다.")
        }
        return out
    }

    /// 글에 Canvas 파일 [fileId]가 첨부되어 있는가.
    public static func hasAttachment(_ p: JSONObject, _ fileId: Int64) -> Bool {
        guard let arr = p.optJSONArray("attachments") else { return false }
        for i in 0..<arr.length() where arr.optJSONObject(i)?.optLong("canvas_file_id") == fileId { return true }
        return false
    }
}

/// LearningX 토큰을 앱 메모리에만 둔다(저장하지 않는다). [owner]는 eTL 토큰의 해시라서, 토큰이 바뀌면 이전 사람의 LearningX 토큰을 쓰지 않는다.
/// eTL 연결 해제 때 앱이 `EtlDisconnect.Stores(extraCleanup: [{ BoardSession.clear() }])`로 지운다.
public enum BoardSession {
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var owner: String?
        var token: String?
        var until: Int64 = 0
    }

    private static let state = State()

    /// 실행이 겹치지 않게(동시에 여러 요청이 와도 한 번만 실행한다).
    public static let launchLock = AsyncSemaphore(1)

    public static func get(_ owner: String, _ now: Int64) -> String? {
        state.lock.withLock { state.owner == owner && now < state.until ? state.token : nil }
    }

    public static func set(_ owner: String, _ token: String, _ until: Int64) {
        state.lock.withLock {
            state.owner = owner
            state.token = token
            state.until = until
        }
    }

    public static func drop(_ owner: String) {
        state.lock.withLock {
            if state.owner == owner {
                state.owner = nil
                state.token = nil
                state.until = 0
            }
        }
    }

    /// eTL 연결 해제: 누구 것이든 지운다.
    public static func clear() {
        state.lock.withLock {
            state.owner = nil
            state.token = nil
            state.until = 0
        }
    }

    public static func ownerOf(_ etlToken: String) -> String {
        SHA256.hash(data: Data(etlToken.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
