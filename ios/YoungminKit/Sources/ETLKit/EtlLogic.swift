import Foundation

// MARK: - JSON 보조 (Kotlin EtlLogic의 확장 함수 str / obj / objects / strList)

public extension JSONObject {
    /// org.json의 optString은 JSON null을 "null" 문자열로 돌려주므로 직접 처리한다.
    func str(_ key: String) -> String? { (!has(key) || isNull(key)) ? nil : optString(key) }

    func obj(_ key: String) -> JSONObject? { (!has(key) || isNull(key)) ? nil : optJSONObject(key) }

    func strList(_ key: String) -> [String] {
        guard let a = optJSONArray(key) else { return [] }
        return (0..<a.length()).compactMap { a.isNull($0) ? nil : a.optString($0) }
    }
}

public extension JSONArray {
    func objects() -> [JSONObject] { (0..<length()).compactMap { optJSONObject($0) } }
}

/// eTL(Canvas) 응답을 해석하는 순수 로직.
public enum EtlLogic {

    /// 한국 표준시(UTC+9 고정, `Asia/Seoul`).
    public static let kst: TimeZone = TimeZone(identifier: "Asia/Seoul") ?? TimeZone(secondsFromGMT: 9 * 3600)!
    /// Kotlin 이름 그대로(`EtlLogic.KST`).
    public static var KST: TimeZone { kst }
    /// KST 그레고리력 달력.
    public static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = kst
        c.locale = Locale(identifier: "ko_KR")
        return c
    }()

    private static let WEEKDAYS = ["월", "화", "수", "목", "금", "토", "일"]
    private static let SUBMITTED: Set<String> = ["submitted", "graded", "pending_review"]
    private static let OFFLINE: Set<String> = ["none", "on_paper", "not_graded"]

    // ---- 학기 ---------------------------------------------------------------

    private static let SEASONS: [(KRegex, Int)] = [(KRegex("1학기"), 1), (KRegex("하계|여름"), 2), (KRegex("2학기"), 3), (KRegex("동계|겨울"), 4)]
    private static let YEAR = KRegex("(\\d{4})\\s*(?:년|-)")

    public static func termRank(_ name: String?) -> Int? {
        guard let s = name else { return nil }
        guard let y = YEAR.find(s).flatMap({ Int($0.groupValues[1]) }) else { return nil }
        guard let season = SEASONS.first(where: { $0.0.containsMatchIn(s) })?.1 else { return nil }
        return y * 10 + season
    }

    /// 가장 최신 학기 강의만 남긴다. 학기를 알 수 없으면 전부 남긴다.
    public static func currentCourses(_ courses: [JSONObject]) -> [JSONObject] {
        let usable = courses.filter { !($0.str("name") ?? "").isEmpty && !$0.optBoolean("access_restricted_by_date", false) }
        let ranks = usable.map { termRank($0.obj("term")?.str("name")) }
        guard let latest = ranks.compactMap({ $0 }).max() else { return usable }
        return usable.enumerated().filter { ranks[$0.offset] == latest }.map(\.element)
    }

    // ---- 과제 판정 ----------------------------------------------------------

    public static func isSubmitted(_ a: JSONObject) -> Bool {
        guard let sub = a.obj("submission") else { return false }
        return sub.str("workflow_state").map { SUBMITTED.contains($0) } == true || !(sub.str("submitted_at") ?? "").isEmpty
    }

    public static func isOnline(_ a: JSONObject) -> Bool { a.strList("submission_types").contains { !OFFLINE.contains($0) } }

    public static func isMissing(_ a: JSONObject, _ now: Date) -> Bool {
        if isSubmitted(a) { return false }
        if a.obj("submission")?.optBoolean("missing", false) == true { return true }
        guard let due = parseInstant(a.str("due_at")) else { return false }
        return due < now && isOnline(a)
    }

    // ---- 시간 ---------------------------------------------------------------

    private static let ISO = KRegex("^(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?([Zz]|[+-]\\d{2}:\\d{2}(?::\\d{2})?)$")

    /// ISO-8601 시각(`Instant.parse`, 안 되면 `OffsetDateTime.parse`). 해석할 수 없으면 nil.
    public static func parseInstant(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty, let m = ISO.find(iso) else { return nil }
        let g = m.groupValues
        guard let y = Int(g[1]), let mo = Int(g[2]), let d = Int(g[3]), let h = Int(g[4]), let mi = Int(g[5]) else { return nil }
        let s = Int(g[6]) ?? 0
        guard let day = DayDate(year: y, month: mo, day: d), h < 24, mi < 60, s < 60 else { return nil }
        var offset = 0
        let z = g[8]
        if z != "Z" && z != "z" {
            let parts = z.dropFirst().split(separator: ":").compactMap { Int($0) }
            guard parts.count >= 2, parts[0] <= 18, parts[1] < 60, (parts.count < 3 || parts[2] < 60) else { return nil }
            offset = parts[0] * 3600 + parts[1] * 60 + (parts.count > 2 ? parts[2] : 0)
            if z.hasPrefix("-") { offset = -offset }
        }
        var frac = 0.0
        if !g[7].isEmpty { frac = Double("0." + g[7]) ?? 0 }
        let secs = day.epochDay * 86_400 + Int64(h * 3600 + mi * 60 + s) - Int64(offset)
        return Date(timeIntervalSince1970: TimeInterval(secs) + frac)
    }

    /// KST 시·분(내부용).
    public static func kstParts(_ t: Date) -> (day: DayDate, hour: Int, minute: Int, second: Int) {
        let local = (t.timeIntervalSince1970 + 9 * 3600).rounded(.down)
        let days = (local / 86_400).rounded(.down)
        let sod = Int(local - days * 86_400)
        return (DayDate(epochDay: Int64(days)), sod / 3600, (sod % 3600) / 60, sod % 60)
    }

    /// `11-19(목) 23:59`. nil이면 "마감일 없음".
    public static func fmtKst(_ t: Date?) -> String {
        guard let t else { return "마감일 없음" }
        let p = kstParts(t)
        let wd = WEEKDAYS[p.day.dayOfWeek.rawValue - 1]
        return String(format: "%02d-%02d(%@) %02d:%02d", p.day.month, p.day.day, wd, p.hour, p.minute)
    }

    /// "1일 2시간 남음", "3시간 5분 남음", "10분 지남". nil이면 "".
    public static func remaining(_ t: Date?, _ now: Date) -> String {
        guard let t else { return "" }
        let secs = t.epochSecond - now.epochSecond
        let a = abs(secs)
        let d = a / 86400
        let h = (a % 86400) / 3600
        let m = (a % 3600) / 60
        let body = d > 0 ? "\(d)일 \(h)시간" : (h > 0 ? "\(h)시간 \(m)분" : "\(m)분")
        return body + (secs >= 0 ? " 남음" : " 지남")
    }

    // ---- 텍스트 -------------------------------------------------------------

    private static let ENTITY = KRegex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

    private static func unescapeHtml(_ s: String) -> String {
        ENTITY.replace(s) { m in
            let g = m.groupValues[1]
            if g.hasPrefix("#x") || g.hasPrefix("#X") {
                return Int(g.dropFirst(2), radix: 16).map(cp) ?? m.value
            } else if g.hasPrefix("#") {
                return Int(g.dropFirst(1)).map(cp) ?? m.value
            }
            switch g {
            case "amp": return "&"
            case "lt": return "<"
            case "gt": return ">"
            case "quot": return "\""
            case "apos": return "'"
            case "nbsp": return " "
            default: return m.value
            }
        }
    }

    private static func cp(_ code: Int) -> String {
        guard code >= 0, code <= 0x10FFFF else { return "" }
        guard let u = Unicode.Scalar(UInt32(code)) else { return "\u{FFFD}" } // 홀로 선 대리 코드
        return String(Character(u))
    }

    private static let SCRIPT = KRegex("<(script|style)\\b.*?</\\1\\s*>", [.dotMatchesAll, .ignoreCase])
    private static let BREAK = KRegex("<\\s*(br|/p|/div|/li|/h\\d|/tr)\\b[^>]*>", .ignoreCase)
    private static let LI = KRegex("<li\\b[^>]*>", .ignoreCase)
    private static let TAG = KRegex("<[^>]+>")
    private static let SPACES = KRegex("[ \\t\\u00a0]+")
    private static let LINE_SPACES = KRegex(" *\\n *")
    private static let MANY_LINES = KRegex("\\n{3,}")

    /// HTML → 글. [limit](UTF-16 글자)을 넘으면 자르고 "…"을 붙인다.
    public static func htmlToText(_ html: String?, _ limit: Int = 600) -> String {
        guard let html, !html.isEmpty else { return "" }
        var s = html
        s = SCRIPT.replace(s, "")
        s = BREAK.replace(s, "\n")
        s = LI.replace(s, "- ")
        s = unescapeHtml(TAG.replace(s, ""))
        s = SPACES.replace(s, " ")
        s = LINE_SPACES.replace(s, "\n")
        s = MANY_LINES.replace(s, "\n\n").kTrim
        return s.kLength <= limit ? s : s.kTake(limit) + "…"
    }

    // ---- 파일 이름 / 참조 ----------------------------------------------------

    private static let UNSAFE_NAME = KRegex("[\\u0000-\\u001f/\\\\:*?\"<>|]")

    /// 경로 구분자와 제어문자를 없애고, 앞쪽 점을 지우고, 180자로 자른다.
    public static func safeName(_ name: String?, _ fallback: String) -> String {
        var n = (name ?? "").kTrim
        n = n.substringAfterLast("/").substringAfterLast("\\")
        n = UNSAFE_NAME.replace(n, "_").kTrimStart(["."])
        n = n.kLength > 180 ? n.kTake(180) : n
        return n.isEmpty ? fallback : n
    }

    private static let FILE_REF = KRegex("/files/(\\d+)")

    /// "12345", "/courses/1/files/12345", 전체 URL → 12345
    public static func fileId(_ ref: String) throws -> Int64 {
        let s = ref.kTrim
        if !s.isEmpty, s.allSatisfy({ $0.isASCII && $0.isNumber }) {
            guard let n = Int64(s) else { throw EtlException("파일 ID나 eTL 파일 URL(…/files/<id>)을 주세요") }
            return n
        }
        guard let m = FILE_REF.find(s), let n = Int64(m.groupValues[1]) else {
            throw EtlException("파일 ID나 eTL 파일 URL(…/files/<id>)을 주세요")
        }
        return n
    }

    private static let SAFE_PATH = KRegex("^/api/v1/[\\w\\-./?=&\\[\\]%:,]*$")
    private static let TOKENS_PATH = KRegex("(^|/)tokens?(/|$)", .ignoreCase)
    private static let LOGIN_PATH = KRegex("^/api/v1/login(/|$)", .ignoreCase)
    private static let SESSIONLESS = KRegex("sessionless_launch", .ignoreCase)

    /// etl_get이 허용하는 경로인지. `..`는 막는다(상위 경로로 빠져나가는 것 방지).
    public static func isAllowedGetPath(_ path: String) -> Bool {
        if path.kLength > 600 || !SAFE_PATH.matches(path) || path.contains("..") || path.contains("//") { return false }
        // java.net.URLDecoder: '+'는 공백, 잘못된 %는 예외.
        guard let decoded = path.substringBefore("?").replacingOccurrences(of: "+", with: " ").removingPercentEncoding else { return false }
        if decoded.contains("..") || decoded.contains("//") || decoded.contains("\\") { return false }
        // 토큰 경로, 로그인(session_token은 사용자로 로그인되는 링크를 준다), 외부 도구 실행(일회용 로그인 링크)은 거부한다.
        return !TOKENS_PATH.containsMatchIn(decoded) && !LOGIN_PATH.containsMatchIn(decoded) && !SESSIONLESS.containsMatchIn(decoded)
    }

    private static let SIGNED_PARAMETERS = ["verifier", "sf_verifier", "download_frd_token", "session_token", "access_token", "refresh_token", "id_token", "api_key", "token", "signature", "sig", "x-amz-signature", "x-amz-credential", "x-amz-security-token", "x-goog-signature", "x-goog-credential", "key-pair-id", "policy"]
    // 공지·과제 HTML의 href에서는 &가 &amp; / &#38; / &#x26;으로 바뀌기도 한다.
    private static let SIGNED = KRegex("([?&](?:(?:amp|#0*38|#x0*26);)*(?:\(SIGNED_PARAMETERS.map(KRegex.escape).joined(separator: "|")))=)(?:&(?:#[0-9]+|#x[0-9a-f]+|[a-z][a-z0-9]*);|[^&#\"'<>\\s])+", .ignoreCase)
    private static func secretKey(_ key: String) -> String {
        key.lowercased().replacingOccurrences(of: "_", with: "").replacingOccurrences(of: "-", with: "")
    }
    private static let SECRET_KEYS: Set<String> = Set((SIGNED_PARAMETERS + ["apikey", "authorization", "client_secret", "password", "secret", "xn_api_token"]).map(secretKey))
    private static let FEED = KRegex("(/feeds/[a-z_]+/)[^\\s\"?#]+", .ignoreCase)

    /// 서명 링크(파일 verifier, session_token)와 피드 주소의 비밀 부분을 ***로 가린다. 모델에게 주기 전에 쓴다.
    public static func unsignText(_ s: String) -> String {
        FEED.replace(SIGNED.replace(s) { $0.groupValues[1] + "***" }) { $0.groupValues[1] + "***" }
    }

    /// JSON 전체(문자열 값)에 [unsignText]를 적용한 사본.
    public static func unsign(_ v: Any?) -> Any? {
        switch v {
        case let s as String: return unsignText(s)
        case let a as JSONArray:
            let out = JSONArray()
            for i in 0..<a.length() { out.put(unsign(a.opt(i))) }
            return out
        case let o as JSONObject:
            let out = JSONObject()
            for k in o.keys() { out.put(k, SECRET_KEYS.contains(secretKey(k)) ? "***" : (unsign(o.opt(k)) ?? JSONObject.NULL)) }
            return out
        default: return v
        }
    }

    /// 모든 도구 결과의 마지막 경계. 알려진 인증 원문은 본문·에러에 반사되어도 모델에 넘기지 않는다.
    public static func modelText(_ value: Any, _ secrets: [String] = []) -> String {
        let known = secrets.filter { !$0.isEmpty }
        func text(_ s: String) -> String { known.reduce(s) { $0.replacingOccurrences(of: $1, with: "***") } }
        func scrub(_ v: Any?) -> Any? {
            switch v {
            case let s as String: return text(s)
            case let a as JSONArray:
                let out = JSONArray()
                for i in 0..<a.length() { out.put(scrub(a.opt(i))) }
                return out
            case let o as JSONObject:
                let out = JSONObject()
                for k in o.keys() { out.put(text(k), scrub(o.opt(k)) ?? JSONObject.NULL) }
                return out
            default: return v
            }
        }
        return kotlinToString(scrub(unsign(value)) ?? JSONObject.NULL)
    }

    /// Kotlin `Any.toString()`: 문자열은 그대로, JSON은 JSON 문자열.
    public static func kotlinToString(_ v: Any) -> String {
        if let s = v as? String { return s }
        if let o = v as? JSONObject { return o.toString() }
        if let a = v as? JSONArray { return a.toString() }
        if v is JSONNull { return "null" }
        if let b = v as? Bool { return b ? "true" : "false" }
        return String(describing: v)
    }

    // ---- 제출 판정 ----------------------------------------------------------

    public struct Evaluation: Sendable, Hashable {
        public var submitted: Bool
        public var due: Date?
        public var submittedAt: Date?
        public var problems: [String]
        public var warnings: [String]

        public init(submitted: Bool, due: Date?, submittedAt: Date?, problems: [String], warnings: [String]) {
            self.submitted = submitted
            self.due = due
            self.submittedAt = submittedAt
            self.problems = problems
            self.warnings = warnings
        }
    }

    /// 늦은 제출 문구. 제출 직후 점검([SubmissionCheck])은 이것만 있으면 알리지 않는다(사용자가 이미 안다).
    public static let LATE_FLAGGED = "지각 제출로 표시됨"
    public static let LATE_AFTER_DUE = "마감 후 제출됨"

    /// 첨부 파일 검사를 뺀 제출 판정. a = 과제, s = submissions/self.
    public static func evaluateSubmission(_ a: JSONObject, _ s: JSONObject, _ now: Date) -> Evaluation {
        let due = parseInstant(s.str("cached_due_date") ?? a.str("due_at"))
        let subAt = parseInstant(s.str("submitted_at"))
        let allowedTypes = a.strList("submission_types")
        var problems: [String] = []
        var warnings: [String] = []

        let submitted = s.str("workflow_state").map { SUBMITTED.contains($0) } == true || subAt != nil
        if !submitted {
            if isOnline(a) {
                problems.append("제출 기록 없음" + ((due != nil && due! < now) ? " (마감 지남)" : ""))
            } else {
                let types = allowedTypes.joined(separator: ", ")
                warnings.append("온라인 제출 과제가 아님 (\(types.isEmpty ? "?" : types)) — eTL로 확인 불가")
            }
        }
        if s.optBoolean("late", false) {
            problems.append(LATE_FLAGGED)
        } else if submitted, let due, let subAt, subAt > due {
            problems.append(LATE_AFTER_DUE)
        }
        if s.optBoolean("missing", false) { problems.append("eTL에 '누락'으로 표시됨") }
        if s.optBoolean("excused", false) { warnings.append("면제(excused) 처리됨") }
        if submitted, let st = s.str("submission_type"), !allowedTypes.isEmpty, !allowedTypes.contains(st) {
            problems.append("제출 방식 \(st) 은(는) 허용되지 않음 (\(allowedTypes.joined(separator: ", ")))")
        }
        return Evaluation(submitted: submitted, due: due, submittedAt: subAt, problems: problems, warnings: warnings)
    }

    /// 첨부 하나에 대한 정적 검사(0바이트, 확장자). 문제 문구를 돌려준다.
    public static func checkAttachment(_ att: JSONObject, _ allowedExt: [String]) -> [String] {
        var out: [String] = []
        let name = att.str("display_name") ?? att.str("filename") ?? ""
        if att.has("size") && !att.isNull("size") && att.optLong("size", -1) == 0 { out.append("빈 파일(0바이트): \(name)") }
        let ext = name.contains(".") ? name.substringAfterLast(".").lowercased() : ""
        if !allowedExt.isEmpty && !allowedExt.contains(ext) {
            out.append("허용되지 않은 확장자 .\(ext): \(name) (허용: \(allowedExt.joined(separator: ", ")))")
        }
        return out
    }
}
