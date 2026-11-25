import Foundation

/*
 * ttokttok-pet `src/etl/queries.js`(courses, assignments, announcements, grades, api)와
 * `src/etl/files.js`(files 목록)의 판정 로직. 네트워크 없이 테스트할 수 있게 JSON → 결과 변환만 둔다.
 */
public enum Queries {
    public static let STATUSES = ["upcoming", "missing", "submitted", "all"]
    public static let LIST_LIMIT = 100
    public static let GRADED_LIMIT = 500
    public static let ANNOUNCEMENT_LIMIT = 1200
    public static let DEFAULT_ANNOUNCEMENT_DAYS = 7
    public static let MAX_ANNOUNCEMENT_DAYS = 60

    // ---- 과목 ---------------------------------------------------------------

    /// 이름이 있고 날짜로 닫히지 않은 강의(지난 학기 포함).
    public static func usableCourses(_ list: [JSONObject]) -> [JSONObject] {
        list.filter { $0.str("name") != nil && !$0.optBoolean("access_restricted_by_date", false) }
    }

    public static func courseRow(_ c: JSONObject) -> JSONObject {
        let enr = c.optJSONArray("enrollments").flatMap { arr in
            (0..<arr.length()).compactMap { arr.optJSONObject($0) }.first { $0.optString("type") == "student" }
        } ?? JSONObject()
        return JSONObject()
            .put("course_id", c.optLong("id"))
            .put("name", c.str("name"))
            .put("term", c.obj("term")?.str("name") ?? JSONObject.NULL)
            .put("current_score", enr.opt("computed_current_score") ?? JSONObject.NULL)
            .put("current_grade", enr.opt("computed_current_grade") ?? JSONObject.NULL)
    }

    /// 모든 학기 과목. 이번 학기를 먼저 두고 `current`로 표시한다.
    public static func coursesResult(_ list: [JSONObject]) -> JSONObject {
        let current = Set(EtlLogic.currentCourses(list).map { $0.optLong("id") })
        let rows = usableCourses(list).map { courseRow($0).put("current", current.contains($0.optLong("id"))) }
        let isCurrent = { (r: JSONObject) in r.optBoolean("current") }
        return JSONObject().put("courses", JSONArray(rows.filter(isCurrent) + rows.filter { !isCurrent($0) }))
    }

    /// [course]가 없으면 이번 학기 과목, 있으면 모든 학기에서 그 과목 하나(없으면 오류).
    public static func coursesFor(_ list: [JSONObject], _ course: Int64?) throws -> [JSONObject] {
        guard let course else { return EtlLogic.currentCourses(list) }
        let picked = usableCourses(list).filter { $0.optLong("id") == course }
        if picked.isEmpty { throw EtlException("수강 중인 과목에서 ID \(course) 를 찾을 수 없습니다. etl_courses로 과목 ID를 확인하세요.") }
        return picked
    }

    // ---- 과제 ---------------------------------------------------------------

    public static func isGraded(_ a: JSONObject) -> Bool {
        guard let s = a.obj("submission") else { return false }
        return s.optString("workflow_state") == "graded" && s.has("score") && !s.isNull("score")
    }

    public static func isPublished(_ a: JSONObject) -> Bool { !a.has("published") || a.optBoolean("published", true) }

    public static func matchesStatus(_ a: JSONObject, _ status: String, _ now: Date) throws -> Bool {
        switch status {
        case "all": return true
        case "submitted": return EtlLogic.isSubmitted(a)
        case "missing": return EtlLogic.isMissing(a, now)
        case "upcoming": return !EtlLogic.isSubmitted(a) && EtlLogic.parseInstant(a.str("due_at")).map { !($0 < now) } == true
        default: throw EtlException("알 수 없는 과제 상태입니다: \(status) (upcoming, missing, submitted, all)")
        }
    }

    /// 과제 하나의 요약(ttokttok-pet과 같은 필드).
    public static func summary(_ a: JSONObject, _ course: JSONObject, _ now: Date) -> JSONObject {
        let sub = a.obj("submission") ?? JSONObject()
        let due = EtlLogic.parseInstant(a.str("due_at"))
        let types = a.optJSONArray("submission_types").map { arr in (0..<arr.length()).map { arr.optString($0) } } ?? []
        return JSONObject()
            .put("course", course.str("name"))
            .put("course_id", course.optLong("id"))
            .put("assignment_id", a.optLong("id"))
            .put("name", a.str("name") ?? JSONObject.NULL)
            .put("due", due.map { EtlLogic.fmtKst($0) } ?? JSONObject.NULL)
            .put("remaining", EtlLogic.remaining(due, now))
            .put("points", jsonNullIf(!a.has("points_possible") || a.isNull("points_possible"), a.opt("points_possible")))
            .put("submission_types", JSONArray(types))
            .put("online_submission", EtlLogic.isOnline(a))
            .put("submitted", EtlLogic.isSubmitted(a))
            .put("missing", sub.optBoolean("missing", false))
            .put("late", sub.optBoolean("late", false))
            .put("graded", sub.optString("workflow_state") == "graded")
            .put("score", jsonNullIf(!sub.has("score") || sub.isNull("score"), sub.opt("score")))
            .put("url", a.str("html_url") ?? JSONObject.NULL)
    }

    private static func dueOf(_ a: JSONObject) -> Date? { EtlLogic.parseInstant(a.str("due_at")) }

    /// 마감순(마감 없음은 끝).
    private static func dueLess(_ x: Date?, _ y: Date?) -> Bool {
        switch (x, y) {
        case let (x?, y?): return x < y
        case (_?, nil): return true
        default: return false
        }
    }

    /// 상태(와 upcoming이면 [days]일 이내)로 거르고 마감순(마감 없음은 끝), 최대 [limit]개.
    public static func statusList(_ results: [CourseAssignments.CourseResult], _ status: String, _ days: Int?, _ now: Date,
                                  limit: Int = LIST_LIMIT) throws -> JSONObject {
        if !STATUSES.contains(status) { throw EtlException("알 수 없는 과제 상태입니다: \(status) (upcoming, missing, submitted, all)") }
        let horizon: Date? = (status == "upcoming" && days != nil && days! > 0) ? now.plusSeconds(Int64(days!) * 86400) : nil
        var candidates: [(JSONObject, JSONObject)] = []
        for r in results { for a in r.assignments where isPublished(a) { candidates.append((r.course, a)) } }
        var filtered: [(JSONObject, JSONObject)] = []
        for (c, a) in candidates {
            guard try matchesStatus(a, status, now) else { continue }
            if let horizon { guard let d = dueOf(a), !(d > horizon) else { continue } }
            filtered.append((c, a))
        }
        let picked = stableSorted(filtered) { dueLess(dueOf($0.1), dueOf($1.1)) }
        let out = JSONObject()
            .put("now", EtlLogic.fmtKst(now))
            .put("status", status)
            .put("count", picked.count)
            .put("assignments", JSONArray(picked.prefix(limit).map { summary($0.1, $0.0, now) }))
        let errors = results.compactMap { r in r.error.map { "\(r.courseName): \($0)" } }
        if !errors.isEmpty { out.put("errors", JSONArray(errors)) }
        return out
    }

    /// 과목별 현재 점수와 채점된 과제(마감순).
    public static func gradesResult(_ courses: [JSONObject], _ results: [CourseAssignments.CourseResult], _ now: Date) -> JSONObject {
        var all: [(JSONObject, JSONObject)] = []
        for r in results { for a in r.assignments where isPublished(a) && isGraded(a) { all.append((r.course, a)) } }
        let graded = stableSorted(all) { dueLess(dueOf($0.1), dueOf($1.1)) }.prefix(GRADED_LIMIT)
        var byCourse: [Int64: [(JSONObject, JSONObject)]] = [:]
        for g in graded { byCourse[g.0.optLong("id"), default: []].append(g) }
        let rows = courses.map { c in
            courseRow(c).put("graded_assignments", JSONArray((byCourse[c.optLong("id")] ?? []).map { pair -> JSONObject in
                let s = summary(pair.1, c, now)
                return JSONObject().put("name", s.opt("name")).put("score", s.opt("score")).put("points", s.opt("points")).put("due", s.opt("due"))
            }))
        }
        let out = JSONObject().put("courses", JSONArray(rows))
        let errors = results.compactMap { r in r.error.map { "\(r.courseName): \($0)" } }
        if !errors.isEmpty { out.put("errors", JSONArray(errors)) }
        return out
    }

    // ---- 공지 ---------------------------------------------------------------

    public static func announcementRows(_ raw: [JSONObject], _ names: [Int64: String]) -> JSONArray {
        let withTime = raw.map { ($0, EtlLogic.parseInstant($0.str("posted_at"))) }
        // 최신순(시각 없음은 끝, Kotlin `sortedByDescending { it ?: Instant.MIN }`).
        let sorted = stableSorted(withTime) { x, y in
            switch (x.1, y.1) {
            case let (a?, b?): return a > b
            case (_?, nil): return true
            default: return false
            }
        }
        let rows = sorted.map { (it, posted) -> JSONObject in
            let cid = Int64((it.str("context_code") ?? "course_0").substringAfterLast("_")) ?? 0
            return JSONObject()
                .put("course", names[cid] ?? String(cid))
                .put("title", it.str("title") ?? JSONObject.NULL)
                .put("posted", posted.map { EtlLogic.fmtKst($0) } ?? JSONObject.NULL)
                .put("author", it.obj("author")?.str("display_name") ?? JSONObject.NULL)
                .put("text", EtlLogic.htmlToText(it.str("message"), ANNOUNCEMENT_LIMIT))
                .put("url", it.str("html_url") ?? it.str("url") ?? JSONObject.NULL)
        }
        return JSONArray(rows)
    }

    /// 공지 종류(알림 배지와 "내 일정에 등록"/"시간표에 반영" 초안이 함께 쓴다).
    public enum AnnCategory: String, CaseIterable, Sendable, Hashable, Codable {
        case EXAM, ASSIGNMENT, VIDEO, MAKEUP, CANCEL

        public var badge: String {
            switch self {
            case .EXAM: return "시험 공지"
            case .ASSIGNMENT: return "과제 공지"
            case .VIDEO: return "동영상 강의"
            case .MAKEUP: return "보강 공지"
            case .CANCEL: return "휴강 공지"
            }
        }

        public var label: String { badge.hasSuffix(" 공지") ? String(badge.dropLast(3)) : badge }
        public var timetable: Bool { self == .MAKEUP || self == .CANCEL }
    }

    private static let CANCEL_WORDS = KRegex("휴강|결강")

    /// 공지 종류. 우선순위는 시험 > 과제 > 동영상 강의 > 보강 > 휴강이고 제목에 있는 말이 먼저다
    /// (제목: 시험 → 과제 → 동영상 → 본문까지 보강 → 휴강 → 본문의 시험·과제·동영상). 해당 없으면 nil.
    public static func announcementCategory(_ title: String?, _ text: String?) -> AnnCategory? {
        let s = "\(title ?? "") \(text ?? "")"
        let exam = examMention(title, text)
        let assign = exam == .NONE && isAssignmentAnnouncement(title, text)
        let video = exam == .NONE && !assign && isVideoLecture(title, text)
        let t = title ?? ""
        if exam == .TITLE { return .EXAM }
        if assign && ASSIGN.containsMatchIn(t) { return .ASSIGNMENT }
        if video && VIDEO.containsMatchIn(t) { return .VIDEO }
        if s.contains("보강") { return .MAKEUP }
        if CANCEL_WORDS.containsMatchIn(s) { return .CANCEL }
        if exam == .BODY { return .EXAM }
        if assign { return .ASSIGNMENT }
        if video { return .VIDEO }
        return nil
    }

    /// 알림 제목 배지: "시험 공지", "과제 공지", "동영상 강의", "보강 공지", "휴강 공지", 그 밖은 "새 공지".
    public static func announcementBadge(_ title: String?, _ text: String?) -> String {
        announcementCategory(title, text)?.badge ?? "새 공지"
    }

    private static let VIDEO = KRegex("동영상\\s*강의|녹화\\s*강의|영상\\s*강의|\\bVOD\\b|온라인\\s*강의|사전\\s*녹화|강의\\s*영상|녹화본", .ignoreCase)
    /// 동영상 강의를 시청하라는 말(단순 자료 링크 공지를 거른다).
    private static let VIDEO_ACTION = KRegex("시청|기한|기간|까지|대체|출석\\s*인정|수강\\s*기간", .ignoreCase)
    /// 그날 대면 수업이 없다는 단서.
    private static let NO_CLASS = KRegex("대면\\s*수업\\s*(은|이)?\\s*(없|휴강|대체)|대체\\s*(합니다|해요|됩니다|수업)|수업\\s*(은|이)?\\s*없|휴강", .ignoreCase)

    /// 동영상 강의 공지인가: 동영상 강의 말과 시청·기한·대체 같은 말이 함께 있을 때(시험·과제 공지가 아닐 때).
    public static func isVideoLecture(_ title: String?, _ text: String?) -> Bool {
        let s = "\(title ?? "") \(text ?? "")"
        return VIDEO.containsMatchIn(s) && VIDEO_ACTION.containsMatchIn(s) && !EXAM_RESULT.containsMatchIn(title ?? "")
    }

    /// 동영상 강의 공지에 "그날 대면 수업 없음/대체" 단서가 있는가.
    public static func replacesClass(_ text: String?) -> Bool { NO_CLASS.containsMatchIn(text ?? "") }

    public enum ExamMention: String, Sendable, Hashable, Codable { case NONE, TITLE, BODY }

    private static let EXAM = KRegex("시험|중간\\s*고사|기말\\s*고사|퀴즈|\\bquiz(zes)?\\b|\\bexam(s|ination)?\\b|\\bmid-?terms?\\b|\\bfinals\\b", .ignoreCase)
    /// 시험 결과 공지("중간고사 성적 공개")를 가르는 말.
    private static let EXAM_RESULT = KRegex("성적|점수|채점|해설|이의\\s*신청|결과|답안|\\bgrades?\\b|\\bscores?\\b|\\bresults?\\b", .ignoreCase)
    /// 일정을 알리는 말. "안내"·"공지"는 성적 안내에도 쓰여서 넣지 않는다.
    private static let EXAM_SCHEDULE = KRegex("일정|일시|날짜|장소|고사장|강의실|범위|시간|실시|시행|응시|\\bdate\\b|\\btime\\b|\\broom\\b|\\blocation\\b|\\bschedule\\b", .ignoreCase)

    /// 시험 공지인가(키워드만 본다; 날짜는 뽑지 않는다). 제목에 시험 말이 있으면 시험 공지(제목이 성적·채점 같은 결과 공지이고
    /// 일정 말이 없으면 제외). 본문에만 있으면 본문에 일정 말(일시·장소·범위 등)도 있을 때만.
    public static func examMention(_ title: String?, _ text: String?) -> ExamMention {
        let t = title ?? ""
        let b = text ?? ""
        if EXAM.containsMatchIn(t) {
            return (EXAM_RESULT.containsMatchIn(t) && !EXAM_SCHEDULE.containsMatchIn(t)) ? .NONE : .TITLE
        }
        if EXAM.containsMatchIn(b) && EXAM_SCHEDULE.containsMatchIn(b) && !EXAM_RESULT.containsMatchIn(t) { return .BODY }
        return .NONE
    }

    public static func isExamAnnouncement(_ title: String?, _ text: String?) -> Bool { examMention(title, text) != .NONE }

    /// 과제 공지의 말. "제출"·"마감"은 과제 말과 함께 볼 때만 쓴다.
    private static let ASSIGN = KRegex("과제|보고서|레포트|리포트|프로젝트|\\bhomework\\b|\\bhw\\s*#?\\d*\\b|\\bassignments?\\b|\\bprojects?\\b|\\breport\\b", .ignoreCase)
    private static let ASSIGN_DUE = KRegex("제출|마감|기한|까지|\\bdue\\b|\\bdeadline\\b|\\bsubmit", .ignoreCase)
    private static let ANSWER = KRegex("정답")

    /// 과제 공지인가. 시험 공지가 아니고, 제목에 과제 말이 있거나(성적·채점 같은 결과 공지는 제외)
    /// 본문에 과제 말과 제출·마감 말이 함께 있을 때. 키워드만 본다.
    public static func isAssignmentAnnouncement(_ title: String?, _ text: String?) -> Bool {
        if isExamAnnouncement(title, text) { return false }
        let t = title ?? ""
        let b = text ?? ""
        // "과제 성적/점수/채점/해설/정답 공개" 같은 결과 공지는 뺀다.
        if EXAM_RESULT.containsMatchIn(t) || ANSWER.containsMatchIn(t) { return false }
        if ASSIGN.containsMatchIn(t) { return true }
        return ASSIGN.containsMatchIn(b) && ASSIGN_DUE.containsMatchIn(b)
    }

    /// 준비물·지참물·허용 물품을 말하는 줄. 키워드만 보고 날짜나 물품을 해석하지 않는다.
    private static let SUPPLIES = KRegex("준비물|지참|가져\\s*오|가져\\s*와|챙겨|허용|불허|사용\\s*(가능|불가)|계산기|신분증|학생증|사인펜|요약지|치팅|cheat\\s*sheet|\\bbring\\b|\\ballowed\\b|\\bcalculators?\\b", .ignoreCase)
    private static let SENTENCE_END = KRegex("(?<=[.!?。])\\s+")
    private static let WS = KRegex("\\s+")

    /// 시험 공지 본문에서 준비물 관련 줄을 최대 [maxLines]줄, 줄마다 [maxChars]자로 뽑는다(알림 본문에 덧붙인다).
    /// 줄바꿈이 없는 긴 본문은 문장(". ", "다.") 단위로 나눠 본다. 같은 줄은 한 번만.
    public static func suppliesLines(_ text: String?, maxLines: Int = 2, maxChars: Int = 60) -> [String] {
        let parts = (text ?? "").split(separator: "\n", omittingEmptySubsequences: false).flatMap { SENTENCE_END.split(String($0)) }
        var seen = Set<String>()
        var out: [String] = []
        for p in parts {
            let line = WS.replace(p, " ").kTrim.kTrimStart(["-", "*", "•", "·", "※", " "])
            guard !line.isEmpty, SUPPLIES.containsMatchIn(line), seen.insert(line).inserted else { continue }
            out.append(line)
            if out.count >= maxLines { break }
        }
        return out.map { $0.kLength > maxChars ? $0.kTake(maxChars - 1) + "…" : $0 }
    }

    // ---- etl_get --------------------------------------------------------------

    private static let CONVERSATION = KRegex("^/api/v1/conversations/\\d+(\\?|$)")
    private static let AUTO_MARK = KRegex("[?&]auto_mark_as_read=")

    /// 쪽지를 읽으면 eTL이 읽음으로 바꾸므로 `auto_mark_as_read=false`를 붙인다(보기만 하고 eTL을 바꾸지 않는다).
    public static func readOnlyPath(_ p: String) -> String {
        if !CONVERSATION.containsMatchIn(p) || AUTO_MARK.containsMatchIn(p) { return p }
        return p + (p.contains("?") ? "&" : "?") + "auto_mark_as_read=false"
    }

    /// 응답이 [maxChars]를 넘으면 목록은 들어가는 만큼만 남기고 그 사실을 적는다. 객체면 오류.
    public static func capAnswer(_ path: String, _ data: Any, _ maxChars: Int) throws -> JSONObject {
        let size = { (v: Any) -> Int in EtlLogic.kotlinToString(v).kLength }
        if size(data) <= maxChars { return JSONObject().put("path", path).put("data", data) }
        guard let arr = data as? JSONArray else {
            throw EtlException("응답이 너무 큽니다. include[]를 줄이거나 더 좁은 경로로 다시 조회하세요.")
        }
        var lo = 0
        var hi = arr.length()
        let slice = { (n: Int) -> JSONArray in
            let s = JSONArray()
            for i in 0..<n { s.put(arr.opt(i)) }
            return s
        }
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            if size(slice(mid)) <= maxChars { lo = mid } else { hi = mid - 1 }
        }
        return JSONObject().put("path", path).put("data", slice(lo))
            .put("truncated", JSONObject().put("shown", lo).put("total", arr.length()))
    }

    // ---- 강의자료 목록 ---------------------------------------------------------

    private static let ATTRS = KRegex("\\b(?:href|src|data-api-endpoint)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))", .ignoreCase)
    private static let SCOPED_FILE = KRegex("^/(?:api/v1/)?courses/(\\d+)/files/(\\d+)(?:/(?:download|preview))?/?$")
    private static let GLOBAL_FILE = KRegex("^/(?:api/v1/)?files/(\\d+)(?:/(?:download|preview))?/?$")
    private static let AMP = KRegex("&amp;", .ignoreCase)

    /// HTML 안의 eTL 파일 링크(이 과목 또는 전역 /files/<id>)의 파일 ID들. HTML은 읽기만 한다.
    public static func linkedFiles(_ html: String?, _ courseId: Int64) -> [Int64] {
        guard let html else { return [] }
        var ids: [Int64] = []
        guard let base = URL(string: EtlClient.BASE_URL + "/") else { return [] }
        for m in ATTRS.findAll(html) {
            guard let rawAttr = m.groups[1] ?? m.groups[2] ?? m.groups[3] else { continue }
            let raw = AMP.replace(rawAttr, "&")
            guard let u = URL(string: raw, relativeTo: base)?.absoluteURL,
                  let c = URLComponents(url: u, resolvingAgainstBaseURL: true), let scheme = c.scheme, let host = c.percentEncodedHost else { continue }
            let authority = (c.percentEncodedUser.map { $0 + "@" } ?? "") + host + (c.port.map { ":\($0)" } ?? "")
            if "\(scheme)://\(authority)" != EtlClient.BASE_URL || c.user != nil { continue }
            let path = c.percentEncodedPath
            let scoped = SCOPED_FILE.find(path)
            let global = GLOBAL_FILE.find(path)
            let id: String?
            if let scoped, Int64(scoped.groupValues[1]) == courseId { id = scoped.groupValues[2] } else { id = global?.groupValues[1] }
            if let n = id.flatMap({ Int64($0) }), n > 0, !ids.contains(n) { ids.append(n) }
        }
        return ids
    }

    public struct FileRef: @unchecked Sendable {
        public var id: Int64
        public var meta: JSONObject?
        public var source: String
        public var labels: [String: String]
        public var title: String?

        public init(id: Int64, meta: JSONObject?, source: String, labels: [String: String], title: String? = nil) {
            self.id = id
            self.meta = meta
            self.source = source
            self.labels = labels
            self.title = title
        }
    }

    /// 과제 설명·첨부, 내 제출 첨부, 공지 본문·첨부에 걸린 파일.
    public static func attachedFiles(_ assignments: [JSONObject], _ announcements: [JSONObject], _ courseId: Int64) -> [FileRef] {
        var out: [FileRef] = []
        func add(_ item: JSONObject?, _ source: String, _ labels: [String: String], _ html: String?) {
            var atts: [JSONObject] = []
            if let arr = item?.optJSONArray("attachments") { for i in 0..<arr.length() { if let o = arr.optJSONObject(i) { atts.append(o) } } }
            if let o = item?.optJSONObject("attachment") { atts.append(o) }
            for f in atts { let id = f.optLong("id"); if id > 0 { out.append(FileRef(id: id, meta: f, source: source, labels: labels)) } }
            for id in linkedFiles(html, courseId) { out.append(FileRef(id: id, meta: nil, source: source, labels: labels)) }
        }
        for a in assignments {
            if !isPublished(a) { continue }
            let labels = a.str("name").map { ["assignment": $0] } ?? [:]
            add(a, "assignment", labels, a.str("description"))
            add(a.obj("submission"), "submission", labels, nil)
        }
        for n in announcements { add(n, "announcement", n.str("title").map { ["announcement": $0] } ?? [:], n.str("message")) }
        return out
    }

    /// 모듈 항목 중 File: (파일 ID, 제목, 모듈 이름).
    public static func moduleFiles(_ modules: [JSONObject]) -> [FileRef] {
        modules.flatMap { m -> [FileRef] in
            let items = m.optJSONArray("items") ?? JSONArray()
            return (0..<items.length()).compactMap { items.optJSONObject($0) }
                .filter { $0.optString("type") == "File" && $0.optLong("content_id") > 0 }
                .map { FileRef(id: $0.optLong("content_id"), meta: nil, source: "module", labels: m.str("name").map { ["module": $0] } ?? [:], title: $0.str("title")) }
        }
    }

    private static let COURSE_FILES_PREFIX = KRegex("^course files/?")

    public static func folderNames(_ folders: [JSONObject]) -> [Int64: String?] {
        var out: [Int64: String?] = [:]
        for f in folders {
            let full = f.str("full_name") ?? f.str("name") ?? ""
            out[f.optLong("id")] = .some(COURSE_FILES_PREFIX.replace(full, "").nilIfEmpty)
        }
        return out
    }

    /// 받지 않는 이유(없으면 nil): 동영상, 잠김, 숨김.
    public static func skipReason(_ f: JSONObject?) -> String? {
        guard let f else { return "파일 정보를 읽지 못했습니다. 받기 요청 때 다시 확인합니다." }
        let type = (f.str("content-type") ?? f.str("content_type") ?? "").lowercased()
        if type.hasPrefix("video/") || f.optString("mime_class") == "video" { return "동영상은 받지 않습니다. eTL에서 보세요." }
        if f.optBoolean("locked_for_user", false) { return "잠긴 파일입니다. 열리는 시각 이후에 받을 수 있습니다." }
        if f.optBoolean("hidden_for_user", false) { return "숨겨진 파일입니다. eTL에서 확인해 주세요." }
        return nil
    }

    /// 과목 파일 목록(받지 않는다): 파일 탭, 모듈, 과제·공지 첨부와 본문 링크, 내 제출 첨부를 합친다.
    /// [details]는 파일 탭에 없던 참조 파일의 메타(id → JSON). [saved]는 이미 작업 폴더에 있는 파일 ID.
    public static func filesResult(
        _ courseId: Int64,
        _ courseName: String?,
        _ listed: [JSONObject]?,
        _ folders: [JSONObject],
        _ modules: [FileRef],
        _ attached: [FileRef],
        _ details: [Int64: JSONObject],
        _ saved: Set<Int64>,
        _ errors: [String]
    ) -> JSONObject {
        let folderOf = folderNames(folders)
        var moduleOf: [Int64: String?] = [:]
        for m in modules { moduleOf[m.id] = .some(m.labels["module"]) }
        var rowOrder: [Int64] = []
        var rows: [Int64: JSONObject] = [:]
        func row(_ f: JSONObject?, _ id: Int64, _ name: String, _ source: String, _ labels: [(String, String?)]) -> JSONObject {
            let o = JSONObject().put("id", id).put("name", name)
                .put("size", jsonNullIf(!(f != nil && f!.has("size") && !f!.isNull("size")), f?.optLong("size")))
                .put("content_type", f?.str("content-type") ?? f?.str("content_type") ?? JSONObject.NULL)
                .put("updated_at", EtlLogic.parseInstant(f?.str("updated_at")).map { EtlLogic.fmtKst($0) } ?? JSONObject.NULL)
                .put("source", source)
            for (k, v) in labels { if let v, !v.kIsBlank { o.put(k, v) } }
            let skip = skipReason(f)
            if let skip { o.put("skip", skip) }
            o.put("downloaded", skip == nil && saved.contains(id))
            o.put("url", "\(EtlClient.BASE_URL)/courses/\(courseId)/files/\(id)")
            return o
        }
        for f in listed ?? [] {
            let id = f.optLong("id")
            guard id > 0, rows[id] == nil else { continue }
            rowOrder.append(id)
            rows[id] = row(f, id, f.str("display_name") ?? f.str("filename") ?? "파일 \(id)", "files",
                           [("folder", folderOf[f.optLong("folder_id")] ?? nil), ("module", moduleOf[id] ?? nil)])
        }
        var refOrder: [Int64] = []
        var refs: [Int64: FileRef] = [:]
        for m in modules { if refs[m.id] == nil { refOrder.append(m.id) }; refs[m.id] = m }
        for r in attached {
            if let prev = refs[r.id] {
                var merged = prev
                merged.meta = prev.meta ?? r.meta
                merged.labels = r.labels.merging(prev.labels) { _, p in p }
                refs[r.id] = merged
            } else {
                refOrder.append(r.id)
                refs[r.id] = r
            }
        }
        for id in refOrder {
            let ref = refs[id]!
            let labels = ref.labels.keys.sorted().map { ($0, ref.labels[$0]) }
            if let existing = rows[ref.id] {
                for (k, v) in labels { if let v, !v.kIsBlank, !existing.has(k) { existing.put(k, v) } }
                continue
            }
            let f = details[ref.id] ?? ref.meta
            rowOrder.append(ref.id)
            rows[ref.id] = row(f, ref.id, f?.str("display_name") ?? f?.str("filename") ?? ref.title ?? "파일 \(ref.id)", ref.source, labels)
        }
        let order = ["files": 0, "module": 0, "assignment": 0, "announcement": 0, "submission": 1]
        let ko = Locale(identifier: "ko_KR")
        let label = { (r: JSONObject) -> String in
            ["folder", "module", "announcement", "assignment"].lazy.compactMap { r.optString($0).nilIfEmpty }.first ?? ""
        }
        let cmp = { (a: String, b: String) -> ComparisonResult in a.compare(b, locale: ko) }
        let list = stableSorted(rowOrder.map { rows[$0]! }) { x, y in
            let ox = order[x.optString("source")] ?? 0
            let oy = order[y.optString("source")] ?? 0
            if ox != oy { return ox < oy }
            let l = cmp(label(x), label(y))
            if l != .orderedSame { return l == .orderedAscending }
            return cmp(x.optString("name"), y.optString("name")) == .orderedAscending
        }
        let out = JSONObject()
            .put("course", courseName ?? JSONObject.NULL)
            .put("course_id", courseId)
            .put("count", list.count)
            .put("downloaded_count", list.filter { $0.optBoolean("downloaded") }.count)
            .put("files", JSONArray(list))
        if list.isEmpty {
            out.put("note", "이 과목에서 받을 수 있는 파일을 찾지 못했습니다. 자료가 게시판에 있으면 etl_boards로 찾아보고, 주차학습(LearningX)에만 있으면 eTL에서 직접 확인해야 합니다.")
        }
        if !errors.isEmpty { out.put("errors", JSONArray(errors)) }
        return out
    }
}
