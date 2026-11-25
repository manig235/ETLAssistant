import Foundation

/*
 * 수업 시간표. ttokttok-pet `src/etl/timetable.js`를 옮긴 것이다.
 *
 * eTL(Canvas)에는 수업 시간이 없지만, 과목마다 강의계획서 본문이 서울대 공개 수강편람의 그 과목을 가리킨다.
 * 수강편람의 상세 조회(cc101ajax/cc103ajax)는 검색 페이지에서 받은 세션 쿠키만 있으면 수업 요일·시간·강의실을 JSON으로 준다.
 * 로그인이 필요 없고 **eTL 토큰은 수강편람에 절대 보내지 않는다**(보내는 것은 공개 과목 코드뿐).
 * 주간 시간표는 하루 동안(또는 수강 과목이 바뀔 때까지) 캐시한다.
 * 하루의 수업 = 주간 시간표의 그 요일 수업 + 그날 eTL 캘린더 일정(교수가 보강 등을 올리는 곳).
 */

public enum TimetableLogic {
    /// `DayOfWeek.value - 1` 순서의 요일 글자.
    public static let WEEKDAYS = "월화수목금토일"
    /// [WEEKDAYS]를 한 글자씩.
    public static let weekdayLabels = WEEKDAYS.map { String($0) }
    public static let REF_KEYS = ["openSchyy", "openShtmFg", "openDetaShtmFg", "sbjtCd", "ltNo", "sbjtSubhCd"]
    private static let REF_VALUE = KRegex("^[\\w.-]{1,40}$")
    private static let TIME = KRegex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private static let MEETING = KRegex("^\\s*([일월화수목금토])\\((\\d{1,2}:\\d{2})~(\\d{1,2}:\\d{2})\\)")

    public struct Meeting: Sendable, Hashable, Codable {
        public var day: String
        public var start: String
        public var end: String
        public var room: String?
        public init(_ day: String, _ start: String, _ end: String, _ room: String?) {
            self.day = day
            self.start = start
            self.end = end
            self.room = room
        }
    }

    public struct CourseMeetings: Sendable, Hashable, Codable {
        public var courseId: Int64
        public var name: String
        public var meetings: [Meeting]
        public var professor: String?
        public var credits: Double?
        public init(_ courseId: Int64, _ name: String, _ meetings: [Meeting], _ professor: String? = nil, credits: Double? = nil) {
            self.courseId = courseId
            self.name = name
            self.meetings = meetings
            self.professor = professor
            self.credits = credits
        }
    }

    public struct Weekly: Sendable, Hashable, Codable {
        public var ids: String
        /// 받은 시각(epoch 밀리초).
        public var fetchedAt: Int64
        public var courses: [CourseMeetings]
        public var errors: [String]
        public var metadataVersion: Int
        public init(_ ids: String, _ fetchedAt: Int64, _ courses: [CourseMeetings], _ errors: [String], metadataVersion: Int = 2) {
            self.ids = ids
            self.fetchedAt = fetchedAt
            self.courses = courses
            self.errors = errors
            self.metadataVersion = metadataVersion
        }
    }

    public struct CatalogCourse: Sendable, Hashable, Codable {
        public var meetings: [Meeting]
        public var professor: String?
        public var metadataError: String?
        public var meetingError: String?
        public var credits: Double?
        public init(_ meetings: [Meeting], _ professor: String?, metadataError: String? = nil, meetingError: String? = nil, credits: Double? = nil) {
            self.meetings = meetings
            self.professor = professor
            self.metadataError = metadataError
            self.meetingError = meetingError
            self.credits = credits
        }
    }

    public struct ClassRow: Sendable, Hashable, Codable {
        public var start: String
        public var end: String
        public var course: String
        public var courseId: Int64?
        public var room: String?
        /// "정규" | "보강" | "일정"
        public var kind: String
        public var note: String?
        public init(_ start: String, _ end: String, _ course: String, _ courseId: Int64?, _ room: String?, _ kind: String, _ note: String? = nil) {
            self.start = start
            self.end = end
            self.course = course
            self.courseId = courseId
            self.room = room
            self.kind = kind
            self.note = note
        }
    }

    /// 사용자가 넣은 보강·휴강. 휴강은 [start]가 있으면 그 시각 수업만 뺀다.
    public struct Change: Sendable, Hashable, Codable {
        public var id: Int64
        public var date: DayDate
        /// "보강" | "휴강"
        public var kind: String
        public var course: String
        public var courseId: Int64?
        public var start: String?
        public var end: String?
        public var room: String?
        public var note: String?
        /// 공지 알림의 "시간표에 반영"으로 넣었으면 그 공지 ID(같은 공지로 두 번 넣지 않게).
        public var sourceAnnouncementId: Int64?

        public init(_ id: Int64, _ date: DayDate, _ kind: String, _ course: String, courseId: Int64? = nil, start: String? = nil,
                    end: String? = nil, room: String? = nil, note: String? = nil, sourceAnnouncementId: Int64? = nil) {
            self.id = id
            self.date = date
            self.kind = kind
            self.course = course
            self.courseId = courseId
            self.start = start
            self.end = end
            self.room = room
            self.note = note
            self.sourceAnnouncementId = sourceAnnouncementId
        }
    }

    public struct Day: Sendable, Hashable {
        public var date: DayDate
        public var weekday: String
        public var classes: [ClassRow]
        public var cancelled: [ClassRow]
        public init(_ date: DayDate, _ weekday: String, _ classes: [ClassRow], _ cancelled: [ClassRow]) {
            self.date = date
            self.weekday = weekday
            self.classes = classes
            self.cancelled = cancelled
        }
    }

    private static let SQUASH = KRegex("[\\s·.:()\\-_]")
    private static func squash(_ s: String?) -> String { SQUASH.replace((s ?? "").lowercased(), "") }

    /// 사용자가 쓴 과목명이 이 과목인지: 공백·문장부호를 빼고 한쪽이 다른 쪽을 포함하면 같다("현실공학" ↔ "가상현실공학").
    public static func sameCourse(_ written: String?, _ course: String?) -> Bool {
        let a = squash(written)
        let b = squash(shortName(course))
        return !a.isEmpty && !b.isEmpty && (a.contains(b) || b.contains(a))
    }

    private static func matches(_ c: Change, _ courseId: Int64, _ name: String) -> Bool {
        if let cid = c.courseId { return cid == courseId }
        return sameCourse(c.course, name)
    }

    private static func rowLess(_ x: ClassRow, _ y: ClassRow) -> Bool {
        if x.start != y.start { return x.start.utf16.lexicographicallyPrecedes(y.start.utf16) }
        return x.end.utf16.lexicographicallyPrecedes(y.end.utf16)
    }

    /// 그날: 정규 수업(휴강 제외) + 보강 + eTL 일정, 시작순. 휴강으로 빠진 수업은 [Day.cancelled].
    public static func day(_ weekly: Weekly?, _ date: DayDate, _ changes: [Change] = [], _ events: [ClassRow] = []) -> Day {
        let wd = weekdayOf(date)
        let todays = changes.filter { $0.date == date }
        let cancels = todays.filter { $0.kind == "휴강" }
        var classes: [ClassRow] = []
        var cancelled: [ClassRow] = []
        for c in weekly?.courses ?? [] {
            for m in c.meetings {
                if m.day != wd { continue }
                let row = ClassRow(m.start, m.end, shortName(c.name), c.courseId, m.room, "정규")
                if let off = cancels.first(where: { matches($0, c.courseId, c.name) && ($0.start == nil || $0.start == m.start) }) {
                    var r = row
                    r.note = off.note
                    cancelled.append(r)
                } else {
                    classes.append(row)
                }
            }
        }
        for ch in todays where ch.kind == "보강" && ch.start != nil && ch.end != nil {
            let course = weekly?.courses.first { matches(ch, $0.courseId, $0.name) }
            classes.append(ClassRow(ch.start!, ch.end!, course.map { shortName($0.name) } ?? (ch.course.kIsBlank ? "보강" : ch.course),
                                    course?.courseId ?? ch.courseId, ch.room, "보강", ch.note))
        }
        classes += events
        return Day(date, wd, stableSorted(classes, by: rowLess), stableSorted(cancelled, by: rowLess))
    }

    public static func changesToJson(_ list: [Change]) -> String {
        let arr = JSONArray()
        for c in list {
            let o = JSONObject().put("id", c.id).put("date", c.date.description).put("kind", c.kind).put("course", c.course)
                .put("course_id", c.courseId ?? JSONObject.NULL).put("start", c.start ?? JSONObject.NULL).put("end", c.end ?? JSONObject.NULL)
                .put("room", c.room ?? JSONObject.NULL).put("note", c.note ?? JSONObject.NULL)
            if let src = c.sourceAnnouncementId { o.put("src_ann", src) }
            arr.put(o)
        }
        return arr.toString()
    }

    public static func changesFromJson(_ s: String?) -> [Change] {
        guard let s, let arr = try? JSONArray(s) else { return [] }
        return (0..<arr.length()).compactMap { i -> Change? in
            guard let o = arr.optJSONObject(i) else { return nil }
            let kind = o.optString("kind")
            guard kind == "보강" || kind == "휴강" else { return nil }
            guard let date = DayDate(iso: o.optString("date")) else { return nil }
            let start = normTime(o.str("start"))
            let end = normTime(o.str("end"))
            if kind == "보강" && (start == nil || end == nil) { return nil }
            let cid = o.optLong("course_id")
            let src = o.optLong("src_ann", 0)
            return Change(o.optLong("id"), date, kind, o.optString("course"), courseId: cid > 0 ? cid : nil, start: start, end: end,
                          room: o.str("room"), note: o.str("note"), sourceAnnouncementId: src > 0 ? src : nil)
        }
    }

    /// 입력 확인: 문제가 없으면 nil.
    public static func validateChange(_ kind: String, _ course: String, _ start: String?, _ end: String?) -> String? {
        if course.kIsBlank { return "과목을 고르거나 입력하세요" }
        if kind == "보강" {
            guard let s = normTime(start), let e = normTime(end) else { return "보강은 시작·끝 시각이 필요해요" }
            if s >= e { return "끝 시각이 시작보다 늦어야 해요" }
        }
        return nil
    }

    /// `9:30` → `09:30`, 아니면 nil.
    public static func normTime(_ s: String?) -> String? {
        guard let s, let m = TIME.find(s.kTrim), let h = Int(m.groupValues[1]) else { return nil }
        return String(format: "%02d:%@", h, m.groupValues[2])
    }

    private static let TERM_PREFIX = KRegex("^\\s*\\d{4}-\\d\\s+")
    private static let SECTION_SUFFIX = KRegex("\\s*\\(\\d{3}\\)\\s*$")

    /// "2026-2 가상현실공학 (001)" → "가상현실공학".
    public static func shortName(_ name: String?) -> String {
        let s = SECTION_SUFFIX.replace(TERM_PREFIX.replace(name ?? "", ""), "").kTrim
        return s.isEmpty ? (name ?? "") : s
    }

    private static let CATALOG_LINK = KRegex("sugang\\.snu\\.ac\\.kr/sugang/cc/cc10[13]\\.action\\?([^\"'\\s<>]+)")

    /// 강의계획서 HTML 안의 수강편람 과목 참조([REF_KEYS] 여섯 개). 없거나 이상하면 nil.
    public static func catalogRef(_ html: String?) -> [String: String]? {
        guard let html else { return nil }
        let text = html.replacingOccurrences(of: "&amp;", with: "&", options: .caseInsensitive)
            .replacingOccurrences(of: "&#38;", with: "&")
            .replacingOccurrences(of: "&#x26;", with: "&", options: .caseInsensitive)
        guard let m = CATALOG_LINK.find(text) else { return nil }
        var q: [String: String] = [:]
        for kv in m.groupValues[1].split(separator: "&", omittingEmptySubsequences: false) {
            guard let i = kv.firstIndex(of: "="), i > kv.startIndex else { continue }
            q[String(kv[..<i])] = String(kv[kv.index(after: i)...])
        }
        var ref: [String: String] = [:]
        for k in REF_KEYS {
            guard let v = q[k] ?? (k == "sbjtSubhCd" ? "000" : nil) else { return nil }
            if !REF_VALUE.matches(v) { return nil }
            ref[k] = v
        }
        return ref
    }

    private static let PAREN = KRegex("\\([^)]*\\)")

    /// "301-103(무선랜제공)" → "301-103".
    public static func cleanRoom(_ s: String?) -> String? {
        guard let s else { return nil }
        return PAREN.replace(s, "").kTrim.nilIfEmpty
    }

    private static let PROFESSOR_ROLE = KRegex(
        "\\s*[（(][^()（）]*(?:교수|강사|교원|대표|담당|책임|instructor|professor)[^()（）]*[)）]", .ignoreCase)

    /// 이름은 유지하고 `(대표교수)` 같은 괄호 속 역할 표기만 뺀다.
    public static func cleanProfessor(_ name: String?) -> String? {
        guard let name else { return nil }
        return PROFESSOR_ROLE.replace(EtlLogic.htmlToText(name, 200), "").kTrim.nilIfBlank
    }

    /// 수강편람 강의계획서의 교수명. 비어 있는 필드는 상세 정보의 교수명으로 보완한다.
    public static func professor(_ detail: JSONObject?) -> String? {
        for key in ["LISTTAB03", "LISTTAB01"] {
            if let p = detail?.optJSONObject(key)?.str("profNm"), let c = cleanProfessor(p) { return c }
        }
        return nil
    }

    /// 수강편람이 명시한 학점만 사용한다. 수업 시간이나 과목명으로 추측하지 않는다.
    public static func credits(_ detail: JSONObject?) -> Double? {
        guard let s = detail?.optJSONObject("LISTTAB01")?.str("openPnt"), let d = Double(s.kTrim), d.isFinite, d >= 0, d <= 30 else { return nil }
        return d
    }

    /// 일부 조회만 실패하면 이미 알던 시간을 보존하고, 새로 확인한 교수 정보는 반영한다.
    public static func refreshedCourse(_ id: Int64, _ name: String, _ details: CatalogCourse?, _ previous: CourseMeetings?) -> CourseMeetings {
        CourseMeetings(id, name,
                       (details != nil && details!.meetingError == nil) ? details!.meetings : (previous?.meetings ?? []),
                       details?.professor ?? previous?.professor, credits: details?.credits ?? previous?.credits)
    }

    /// cc101ajax / cc103ajax 응답의 수업들.
    public static func parseMeetings(_ detail: JSONObject?) -> [Meeting] {
        var out: [Meeting] = []
        if let summary = detail?.optJSONObject("LISTTAB01")?.str("lsnTmtablFormaSmryCtnt"), !summary.kIsBlank {
            for part in summary.split(separator: "/", omittingEmptySubsequences: false) {
                let fields = part.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
                guard let m = MEETING.find(fields.first ?? ""),
                      let s = normTime(m.groupValues[2]), let e = normTime(m.groupValues[3]) else { continue }
                out.append(Meeting(m.groupValues[1], s, e, cleanRoom(fields.count > 2 ? fields[2] : nil)))
            }
            if !out.isEmpty { return out }
        }
        let times = detail?.optJSONArray("ltTime") ?? JSONArray()
        let rooms = detail?.optJSONArray("ltRoom") ?? JSONArray()
        for i in 0..<times.length() {
            guard let m = MEETING.find(times.optString(i)),
                  let s = normTime(m.groupValues[2]), let e = normTime(m.groupValues[3]) else { continue }
            out.append(Meeting(m.groupValues[1], s, e, cleanRoom(rooms.optString(i, "").nilIfEmpty)))
        }
        return out
    }

    public static func weekdayOf(_ date: DayDate) -> String { weekdayLabels[date.dayOfWeek.rawValue - 1] }

    // ---- 강의계획서(수강편람 cc103ajax) ----------------------------------------

    /// 수강편람 강의계획서 첨부 하나. [no]·[seq]로 [attachmentUrl]을 만든다.
    public struct SyllabusAttachment: Sendable, Hashable, Codable {
        public var name: String
        public var lang: String
        public var no: String
        public var seq: Int
        public init(_ name: String, _ lang: String, _ no: String, _ seq: Int) {
            self.name = name
            self.lang = lang
            self.no = no
            self.seq = seq
        }
    }

    private static let ATTACH_NO = KRegex("^[\\w-]{1,64}$")
    private static let ATTACH_BASE = "https://shine.snu.ac.kr/com/download/noflt/downloadWebFile.action"

    /// 수강편람 화면이 쓰는 공개 첨부 다운로드 주소. 번호가 이상하면 nil.
    public static func attachmentUrl(_ a: SyllabusAttachment) -> String? {
        if !ATTACH_NO.matches(a.no) || !(0...999).contains(a.seq) { return nil }
        return "\(ATTACH_BASE)?cscLocale=ko_KR&attcFileNo=\(a.no)&attcFileDetaNo=\(a.seq)"
    }

    private static let SIZE_SUFFIX = KRegex("\\s*\\(\\s*[\\d.,]+\\s*[KMG]?B\\s*\\)\\s*$", .ignoreCase)

    /// "[430.309A]Syllabus.docx (31.73KB)" → "[430.309A]Syllabus.docx".
    public static func attachmentName(_ s: String) -> String { SIZE_SUFFIX.replace(s, "").kTrim }

    public static func syllabusAttachments(_ detail: JSONObject?) -> [SyllabusAttachment] {
        guard let arr = detail?.optJSONArray("LISTTAB03_LIST_ATTACH") else { return [] }
        var out: [SyllabusAttachment] = []
        for i in 0..<arr.length() {
            guard let a = arr.optJSONObject(i) else { continue }
            for (lang, p) in [("ko", "kor"), ("en", "eng")] {
                guard let name = a.str("\(p)FileNm").map(attachmentName)?.nilIfEmpty, let no = a.str("\(p)AttachNo") else { continue }
                out.append(SyllabusAttachment(name, lang, no, a.optInt("\(p)AttachDetaNo", 0)))
            }
        }
        return out
    }

    private static func koEn(_ o: JSONObject?, _ ko: String, _ en: String, _ limit: Int = 4000) -> String? {
        guard let o else { return nil }
        return EtlLogic.htmlToText(o.str(ko)?.nilIfBlank ?? o.str(en), limit).nilIfBlank
    }

    private static let MARKS = [("attendance", "출석"), ("homeWork", "과제"), ("mid", "중간고사"), ("final", "기말고사"), ("quiz", "퀴즈"), ("attitude", "태도"), ("etc", "기타")]

    /// cc103ajax(수강편람 '강의계획서' 탭) 응답을 비서가 읽기 쉬운 JSON으로. 장애학생 지원 같은 공통 문구는 뺀다.
    /// 첨부는 번호(no)를 숨기고 index로만 알려 준다.
    public static func syllabusJson(_ detail: JSONObject) -> JSONObject {
        let t1 = detail.optJSONObject("LISTTAB01")
        let t3 = detail.optJSONObject("LISTTAB03")
        let plan = detail.optJSONArray("LISTTAB03_LIST_PLAN")?.optJSONObject(0)
        let o = JSONObject()
        if let t1 {
            o.put("course", [t1.str("sbjtNm"), t1.str("ltNo").map { "(\($0))" }].compactMap { $0 }.joined(separator: " "))
                .put("course_code", t1.str("sbjtCd"))
        }
        if let p = professor(detail) { o.put("professor", p) }
        if let e = t3?.str("profEmail") { o.put("professor_email", e) }
        if let c = t1?.str("openPnt") { o.put("credits", c) }
        if let l = t1?.str("lsnProgLang") { o.put("language", l) }
        if let times = detail.optJSONArray("ltTime") {
            let rooms = detail.optJSONArray("ltRoom")
            o.put("meetings", JSONArray((0..<times.length()).map { i -> String in
                times.optString(i) + (rooms?.optString(i, "").nilIfEmpty.map { " \($0)" } ?? "")
            }))
        }
        if let g = koEn(t3, "ltPurp", "ltEngPurp") { o.put("goals", g) }
        if let p = koEn(plan, "ltPlanCtnt", "ltPlanEngCtnt", 8000) { o.put("plan", p) }
        if let m = detail.optJSONObject("LISTTAB03_LIST_MRKS") {
            let g = JSONArray()
            for (k, label) in MARKS {
                let pct = m.optInt(k, 0)
                let remark = EtlLogic.htmlToText(m.str("\(k)Remk"), 300).nilIfBlank
                if pct > 0 || remark != nil {
                    let row = JSONObject().put("item", label).put("percent", pct)
                    if let remark { row.put("note", remark) }
                    g.put(row)
                }
            }
            if g.length() > 0 { o.put("grading", g) }
        }
        if let n = koEn(t3, "etcMrksApprMthd", "etcMrksApprEngMthd") { o.put("grading_note", n) }
        let gradingType = [t3?.str("mrksRelevalYnNm"), t3?.str("mrksGvMthdFgNm")].compactMap { $0 }
        if !gradingType.isEmpty { o.put("grading_type", gradingType.joined(separator: ", ")) }
        if let a = koEn(plan, "attendRegulKorCtnt", "attendRegulEngCtnt", 600) { o.put("attendance_rule", a) }
        if let weeks = detail.optJSONArray("LISTTAB03_LIST_WEEK_PLAN") {
            let w = JSONArray()
            for i in 0..<weeks.length() {
                guard let r = weeks.optJSONObject(i), let text = koEn(r, "wekClsfLtPlanCtnt", "wekClsfLtPlanEngCtnt", 800) else { continue }
                w.put(JSONObject().put("week", r.opt("ltPlanDocWekClsfSeq") ?? (i + 1)).put("plan", text))
            }
            if w.length() > 0 { o.put("weekly_plan", w) }
        }
        var books: [String] = []
        if let b = koEn(t3, "teachmAndRefBookList", "teachmAndRefBookEngList", 1500) { books.append(b) }
        if let arr = detail.optJSONArray("LISTTAB03_LIST_TEACHM_BOOK") {
            for i in 0..<arr.length() {
                guard let b = arr.optJSONObject(i) else { continue }
                let line = [b.str("ltTeachmFgNm").map { "[\($0)]" }, b.str("ltTeachmNm"), b.str("ltTeachmAuthNm"), b.str("ltTeachmPubcoNm"), b.str("ltTeachmPublshYy")]
                    .compactMap { $0 }.joined(separator: " ")
                if let l = line.nilIfBlank { books.append(l) }
            }
        }
        if !books.isEmpty { o.put("textbooks", JSONArray(books)) }
        if let pre = t3?.str("preCptnSbjtNm")?.nilIfBlank { o.put("prerequisites", pre) }
        if let oh = koEn(t3, "stdIntrvPl", "stdIntrvEngPl", 500) {
            o.put("office_hours", oh)
        } else if let oh = t1?.str("stdIntrvPosbTmList")?.nilIfBlank {
            o.put("office_hours", oh)
        }
        if let ai = koEn(t3, "genrAiUtlzKorCtnt", "genrAiUtlzEngCtnt", 1500) { o.put("generative_ai_policy", ai) }
        if let tm = koEn(t3, "etcLsnMthd", "etcLsnEngMthd", 500) { o.put("teaching_method_note", tm) }
        if let rm = koEn(t3, "ltPlanDocRemk", "ltPlanDocEngRemk", 1500) { o.put("remarks", rm) }
        let atts = syllabusAttachments(detail)
        if !atts.isEmpty {
            o.put("attachments", JSONArray(atts.enumerated().map { i, a in JSONObject().put("index", i).put("name", a.name).put("lang", a.lang) }))
        }
        return o
    }

    /// 그날 수업(보강·휴강 반영): [day]의 classes.
    public static func dayClasses(_ weekly: Weekly?, _ date: DayDate, _ events: [ClassRow] = [], _ changes: [Change] = []) -> [ClassRow] {
        day(weekly, date, changes, events).classes
    }

    public static func classesJson(_ rows: [ClassRow]) -> JSONArray {
        let arr = JSONArray()
        for r in rows {
            let o = JSONObject().put("start", r.start).put("end", r.end).put("course", r.course)
                .put("course_id", r.courseId ?? JSONObject.NULL).put("room", r.room ?? JSONObject.NULL)
                .put("kind", r.kind)
            if let note = r.note { o.put("note", note) }
            arr.put(o)
        }
        return arr
    }

    /// 요일별 주간 시간표(월~일, 수업 있는 요일만).
    public static func weekJson(_ weekly: Weekly) -> JSONObject {
        let days = JSONObject()
        for d in weekdayLabels {
            let rows = weekly.courses.flatMap { c in
                c.meetings.filter { $0.day == d }.map { ClassRow($0.start, $0.end, shortName(c.name), c.courseId, $0.room, "정규") }
            }
            let sorted = stableSorted(rows, by: rowLess)
            if !sorted.isEmpty { days.put(d, classesJson(sorted)) }
        }
        return days
    }

    /// 브리핑용 한 줄씩: `• 17:00~18:15 전기에너지변환 (301-103)`.
    public static func briefLines(_ rows: [ClassRow]) -> [String] {
        rows.map { r in
            "• \(r.start)~\(r.end) \(r.course)" + (r.room.map { " (\($0))" } ?? "") + (r.kind != "정규" ? " [\(r.kind)]" : "")
        }
    }

    public static func toJson(_ w: Weekly) -> String {
        let courses = JSONArray()
        for c in w.courses {
            let ms = JSONArray()
            for m in c.meetings { ms.put(JSONObject().put("day", m.day).put("start", m.start).put("end", m.end).put("room", m.room ?? JSONObject.NULL)) }
            courses.put(JSONObject().put("course_id", c.courseId).put("name", c.name).put("professor", c.professor ?? JSONObject.NULL)
                .put("credits", c.credits ?? JSONObject.NULL).put("meetings", ms))
        }
        return JSONObject()
            .put("version", w.metadataVersion)
            .put("ids", w.ids)
            .put("fetchedAt", w.fetchedAt)
            .put("errors", JSONArray(w.errors))
            .put("courses", courses)
            .toString()
    }

    public static func fromJson(_ s: String?) -> Weekly? {
        guard let s, let o = try? JSONObject(s) else { return nil }
        let version = o.optInt("version")
        guard (1...2).contains(version) else { return nil }
        let errs = o.optJSONArray("errors")
        let cs = o.optJSONArray("courses")
        let courses = (0..<(cs?.length() ?? 0)).compactMap { i -> CourseMeetings? in
            guard let c = cs?.optJSONObject(i) else { return nil }
            let ms = c.optJSONArray("meetings") ?? JSONArray()
            let meetings = (0..<ms.length()).compactMap { j -> Meeting? in
                guard let m = ms.optJSONObject(j) else { return nil }
                return Meeting(m.optString("day"), m.optString("start"), m.optString("end"), m.str("room"))
            }
            let cr = c.optDouble("credits", .nan)
            return CourseMeetings(c.optLong("course_id"), c.optString("name"), meetings, cleanProfessor(c.str("professor")),
                                  credits: (cr.isFinite && cr >= 0 && cr <= 30) ? cr : nil)
        }
        return Weekly(o.optString("ids"), o.optLong("fetchedAt"), courses,
                      (0..<(errs?.length() ?? 0)).map { errs!.optString($0) }, metadataVersion: version)
    }
}

// MARK: - 수강편람 조회

/// 수강편람 오류(사용자에게 보여 줄 문구).
public struct CatalogError: Error, LocalizedError, CustomStringConvertible, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
    public var description: String { message }
}

/// 공개 수강편람 조회(로그인 없음, eTL 토큰 없음). sugang.snu.ac.kr과 강의계획서 첨부용 shine.snu.ac.kr(GET)에만 보낸다.
public enum CatalogClient {
    private static let SUGANG = "https://sugang.snu.ac.kr"
    private static let SEARCH_PATH = "/sugang/cc/cc100InterfaceSrch.action"
    private static let DETAIL_PATH = "/sugang/cc/cc101ajax.action"
    private static let SYLLABUS_PATH = "/sugang/cc/cc103ajax.action"
    private static let TIMEOUT_MS = 15_000

    /// 리다이렉트를 따라가지 않고, 공용 쿠키 저장소를 쓰지 않는 세션(쿠키는 직접 받아 직접 보낸다).
    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = TimeInterval(TIMEOUT_MS) / 1000
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c, delegate: NoRedirectDelegate(), delegateQueue: nil)
    }()

    /// 오류를 사용자 문구로(Kotlin의 IOException → netError, 그 밖은 메시지 그대로).
    public static func message(_ e: Error) -> String {
        if let c = e as? CatalogError { return c.message }
        if let u = e as? URLError { return u.code == .timedOut ? "수강편람 응답 시간 초과" : "수강편람 연결 실패" }
        return (e as? LocalizedError)?.errorDescription ?? String(describing: e)
    }

    private static func netError(_ e: Error) -> CatalogError { CatalogError(message(e)) }

    /// 참조마다 수업 시간·교수 정보 또는 오류. 한 번에 하나씩 묻는다.
    public static func fetch(_ refs: [[String: String]]) async -> [Result<TimetableLogic.CatalogCourse, Error>] {
        if refs.isEmpty { return [] }
        let cookie: String
        do {
            cookie = try await session()
        } catch {
            let err = netError(error)
            return refs.map { _ in .failure(err) }
        }
        var out: [Result<TimetableLogic.CatalogCourse, Error>] = []
        for ref in refs {
            do {
                let syllabus: Result<JSONObject, Error>
                do { syllabus = .success(try await post(SYLLABUS_PATH, ref, cookie)) } catch { syllabus = .failure(error) }
                var detail = try? syllabus.get()
                var meetings = TimetableLogic.parseMeetings(detail)
                var meetingError: String?
                if meetings.isEmpty {
                    let fallback: Result<JSONObject, Error>
                    do { fallback = .success(try await post(DETAIL_PATH, ref, cookie)) } catch { fallback = .failure(error) }
                    switch (fallback, syllabus) {
                    case (.success(let d), _):
                        detail = d
                        meetings = TimetableLogic.parseMeetings(d)
                    case (.failure(let e), .failure):
                        throw e
                    default:
                        meetingError = "수업 시간을 가져오지 못했어요"
                    }
                }
                let syl = try? syllabus.get()
                let professor = TimetableLogic.professor(syl) ?? TimetableLogic.professor(detail)
                let credits = TimetableLogic.credits(syl) ?? TimetableLogic.credits(detail)
                let failedSyllabus: Bool = { if case .failure = syllabus { return true } else { return false } }()
                out.append(.success(TimetableLogic.CatalogCourse(meetings, professor,
                    metadataError: (failedSyllabus && professor == nil) ? "교수 정보를 가져오지 못했어요" : nil,
                    meetingError: meetingError, credits: credits)))
            } catch {
                out.append(.failure(netError(error)))
            }
        }
        return out
    }

    /// 수강편람 '강의계획서' 탭(cc103ajax)의 원본 응답. 실패하면 사용자에게 보여 줄 문구를 담은 [CatalogError].
    public static func syllabus(_ ref: [String: String]) async throws -> JSONObject {
        do {
            return try await post(SYLLABUS_PATH, ref, try await session())
        } catch {
            throw netError(error)
        }
    }

    /// 강의계획서 첨부(shine.snu.ac.kr 공개 다운로드)를 [destination]에 쓴다. 로그인·eTL 토큰 없이 GET만 보낸다.
    /// 리다이렉트는 따라가지 않고, [maxBytes]를 넘으면 중단한다. 받은 바이트 수를 돌려준다.
    public static func downloadAttachment(_ url: String, to destination: URL, maxBytes: Int64) async throws -> Int64 {
        guard url.hasPrefix("https://shine.snu.ac.kr/"), let u = URL(string: url) else { throw CatalogError("수강편람 첨부 주소가 아니에요") }
        var req = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: TimeInterval(TIMEOUT_MS) / 1000)
        req.httpMethod = "GET"
        req.httpShouldHandleCookies = false
        let (bytes, resp) = try await session.bytes(for: req)
        let http = resp as? HTTPURLResponse
        let code = http?.statusCode ?? 0
        if !(200...299).contains(code) { throw CatalogError("첨부파일 응답 오류(\(code))") }
        if (http?.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased().hasPrefix("text/html") {
            throw CatalogError("첨부파일 대신 웹 페이지가 왔어요")
        }
        let fm = FileManager.default
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        fm.createFile(atPath: destination.path, contents: nil)
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }
        try handle.truncate(atOffset: 0)
        var total: Int64 = 0
        var buffer = Data()
        buffer.reserveCapacity(64 * 1024)
        for try await b in bytes {
            buffer.append(b)
            total += 1
            if total > maxBytes { throw CatalogError("첨부파일이 너무 커요") }
            if buffer.count >= 64 * 1024 {
                try handle.write(contentsOf: buffer)
                buffer.removeAll(keepingCapacity: true)
            }
        }
        if !buffer.isEmpty { try handle.write(contentsOf: buffer) }
        return total
    }

    /// 검색 페이지를 GET해 세션 쿠키를 받는다(지금은 302로 메인으로 보내지만 쿠키는 준다. 상세 조회는 이 쿠키가 없으면 302).
    private static func session() async throws -> String {
        let url = URL(string: SUGANG + SEARCH_PATH)!
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: TimeInterval(TIMEOUT_MS) / 1000)
        req.httpMethod = "GET"
        req.httpShouldHandleCookies = false
        let (_, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { return "" }
        var fields: [String: String] = [:]
        for (k, v) in http.allHeaderFields {
            if let k = k as? String, let v = v as? String { fields[k] = v }
        }
        return HTTPCookie.cookies(withResponseHeaderFields: fields, for: url)
            .map { "\($0.name)=\($0.value)" }.filter { !$0.kIsBlank }.joined(separator: "; ")
    }

    /// `java.net.URLEncoder.encode(s, "UTF-8")`.
    static func formEncode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            switch b {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"):
                out.unicodeScalars.append(Unicode.Scalar(b))
            case UInt8(ascii: " "):
                out += "+"
            default:
                out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    private static func post(_ path: String, _ ref: [String: String], _ cookie: String) async throws -> JSONObject {
        var pairs: [(String, String)] = TimetableLogic.REF_KEYS.compactMap { k in ref[k].map { (k, $0) } }
        for k in ref.keys.sorted() where !TimetableLogic.REF_KEYS.contains(k) { pairs.append((k, ref[k]!)) }
        pairs.removeAll { $0.0 == "t_profPersNo" }
        pairs.append(("t_profPersNo", ""))
        let body = pairs.map { "\($0.0)=" + formEncode($0.1) }.joined(separator: "&")
        var req = URLRequest(url: URL(string: SUGANG + path)!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: TimeInterval(TIMEOUT_MS) / 1000)
        req.httpMethod = "POST"
        req.httpShouldHandleCookies = false
        req.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
        req.setValue(SUGANG + SEARCH_PATH, forHTTPHeaderField: "Referer")
        if !cookie.isEmpty { req.setValue(cookie, forHTTPHeaderField: "Cookie") }
        req.httpBody = Data(body.utf8)
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if !(200...299).contains(code) { throw CatalogError("수강편람 응답 오류(\(code))") }
        do {
            return try JSONObject(String(decoding: data, as: UTF8.self))
        } catch {
            throw CatalogError("수강편람 응답을 해석할 수 없어요")
        }
    }
}

// MARK: - 저장소

/// 사용자가 넣은 보강·휴강(앱 내부 저장소, Kotlin prefs `timetable_changes`의 `items`). 30일 지난 항목은 저장할 때 정리한다.
public final class TimetableChangeStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSRecursiveLock()

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "timetable_changes")) { self.prefs = prefs }

    public func load() -> [TimetableLogic.Change] {
        lock.withLock { TimetableLogic.changesFromJson(prefs.string("items")) }
    }

    public func save(_ list: [TimetableLogic.Change], today: DayDate = DayDate.today()) {
        lock.withLock {
            let cutoff = today.minusDays(30)
            let kept = stableSorted(list.filter { !$0.date.isBefore(cutoff) }) { a, b in
                if a.date != b.date { return a.date < b.date }
                return (a.start ?? "").utf16.lexicographicallyPrecedes((b.start ?? "").utf16)
            }
            prefs.set(TimetableLogic.changesToJson(kept), for: "items")
        }
        EtlEvents.dataChanged()
    }

    public func add(_ c: TimetableLogic.Change) {
        lock.withLock { save(load().filter { $0.id != c.id } + [c]) }
    }

    public func delete(_ id: Int64) {
        lock.withLock { save(load().filter { $0.id != id }) }
    }
}

/// 주간 시간표 캐시(앱 내부 저장소, Kotlin prefs `timetable`의 `weekly`·`custom`).
public final class TimetableStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSRecursiveLock()

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "timetable")) { self.prefs = prefs }

    /// 수강편람에서 받은 시간표(직접 추가한 수업 제외).
    public func loadCatalog() -> TimetableLogic.Weekly? {
        lock.withLock { TimetableLogic.fromJson(prefs.string("weekly")) }
    }

    /// 직접 추가한 수업(음수 ID).
    public func customCourses() -> [TimetableLogic.CourseMeetings] {
        lock.withLock { (TimetableLogic.fromJson(prefs.string("custom"))?.courses ?? []).compactMap(CustomTimetableLogic.normalize) }
    }

    public func withCustom(_ w: TimetableLogic.Weekly?) -> TimetableLogic.Weekly? {
        lock.withLock { CustomTimetableLogic.merge(w, customCourses()) }
    }

    /// 수강편람 시간표 + 직접 추가한 수업.
    public func load() -> TimetableLogic.Weekly? { lock.withLock { withCustom(loadCatalog()) } }

    public func save(_ w: TimetableLogic.Weekly) {
        lock.withLock {
            var copy = w
            copy.courses = w.courses.filter { $0.courseId >= 0 }
            prefs.set(TimetableLogic.toJson(copy), for: "weekly")
        }
        EtlEvents.dataChanged()
    }

    /// 직접 추가한 수업을 저장(같은 ID는 바꾼다). 올바르지 않으면 [CustomTimetableLogic.validationError] 문구로 오류.
    public func saveCustom(_ course: TimetableLogic.CourseMeetings) throws {
        guard let valid = CustomTimetableLogic.normalize(course) else {
            throw EtlException(CustomTimetableLogic.validationError(course) ?? "수업 정보를 확인해 주세요")
        }
        lock.withLock { saveCustomList(customCourses().filter { $0.courseId != valid.courseId } + [valid]) }
    }

    public func deleteCustom(_ id: Int64) {
        lock.withLock { saveCustomList(customCourses().filter { $0.courseId != id }) }
    }

    /// eTL 연결 해제 때 수강편람 시간표만 지운다(직접 넣은 수업은 남긴다).
    public func clearCatalog() {
        lock.withLock { prefs.remove("weekly") }
        EtlEvents.dataChanged()
    }

    private func saveCustomList(_ courses: [TimetableLogic.CourseMeetings]) {
        prefs.set(TimetableLogic.toJson(TimetableLogic.Weekly("", 0, courses, [])), for: "custom")
        EtlEvents.dataChanged()
    }
}

// MARK: - 조회

public enum Timetable {
    public static let CACHE_MS: Int64 = 24 * 3600 * 1000
    private static let CONTEXTS_PER_REQUEST = 10

    /// 지금(epoch 밀리초).
    public static func nowMs() -> Int64 { Date().epochMilli }

    /// 이번 학기 과목과 수업. 하루 동안, 또는 수강 과목이 바뀔 때까지 캐시를 쓴다. 조회에 실패한 과목은 이전에 알던 수업을 남긴다.
    public static func weekly(_ etl: EtlClient, _ store: TimetableStore, nowMs: Int64 = Timetable.nowMs(), force: Bool = false) async throws -> TimetableLogic.Weekly {
        let list = EtlLogic.currentCourses(try await etl.getAll("/api/v1/courses?enrollment_state=active&include[]=term&include[]=syllabus_body&per_page=100"))
        let ids = list.map { $0.optLong("id") }.sorted().map(String.init).joined(separator: ",")
        let cached = store.loadCatalog()
        if !force, let cached, cached.metadataVersion >= 2, cached.ids == ids, nowMs >= cached.fetchedAt, nowMs - cached.fetchedAt < CACHE_MS {
            return store.withCustom(cached)!
        }

        let refs = list.map { TimetableLogic.catalogRef($0.str("syllabus_body")) }
        let asked = refs.indices.filter { refs[$0] != nil }
        let answers = await CatalogClient.fetch(asked.map { refs[$0]! })
        var courses: [TimetableLogic.CourseMeetings] = []
        var errors: [String] = []
        for (i, c) in list.enumerated() {
            let id = c.optLong("id")
            let name = c.str("name") ?? ""
            let previous = cached?.courses.first { $0.courseId == id }
            let got: Result<TimetableLogic.CatalogCourse, Error>
            if let k = asked.firstIndex(of: i) { got = answers[k] } else { got = .failure(CatalogError("강의계획서에 수강편람 링크가 없어요")) }
            switch got {
            case .success(let it):
                courses.append(TimetableLogic.refreshedCourse(id, name, it, previous))
                for error in [it.metadataError, it.meetingError].compactMap({ $0 }) { errors.append("\(TimetableLogic.shortName(name)): \(error)") }
            case .failure(let e):
                errors.append("\(TimetableLogic.shortName(name)): \(CatalogClient.message(e))")
                courses.append(TimetableLogic.refreshedCourse(id, name, nil, previous))
            }
        }
        let result = TimetableLogic.Weekly(ids, nowMs, courses, errors)
        store.save(result)
        return store.withCustom(result)!
    }

    /// 캐시가 하루 안이면 네트워크 없이 그대로, 아니면 [weekly]로 새로 받는다(알림 확인·브리핑용).
    public static func cachedOrFetch(_ etl: EtlClient, _ store: TimetableStore, nowMs: Int64 = Timetable.nowMs()) async throws -> TimetableLogic.Weekly {
        if let w = store.load(), nowMs - w.fetchedAt < CACHE_MS { return w }
        return try await weekly(etl, store, nowMs: nowMs)
    }

    /// 그날(KST) eTL 캘린더 일정(종일 일정 제외)을 kind "일정"인 수업으로.
    public static func calendarClasses(_ etl: EtlClient, _ courses: [TimetableLogic.CourseMeetings], _ date: DayDate) async throws -> [TimetableLogic.ClassRow] {
        var keys: [String] = []
        var names: [String: TimetableLogic.CourseMeetings] = [:]
        for c in courses {
            let k = "course_\(c.courseId)"
            if names[k] == nil { keys.append(k) }
            names[k] = c
        }
        var out: [TimetableLogic.ClassRow] = []
        let hm = { (t: Date) -> String in
            let p = EtlLogic.kstParts(t)
            return String(format: "%02d:%02d", p.hour, p.minute)
        }
        var i = 0
        while i < keys.count {
            let chunk = keys[i..<min(i + CONTEXTS_PER_REQUEST, keys.count)]
            i += CONTEXTS_PER_REQUEST
            let ctx = chunk.map { "context_codes[]=\($0)" }.joined(separator: "&")
            for e in try await etl.getAll("/api/v1/calendar_events?type=event&start_date=\(date)&end_date=\(date)&per_page=100&\(ctx)") {
                if e.optBoolean("all_day", false) { continue }
                guard let start = EtlLogic.parseInstant(e.str("start_at")) else { continue }
                let end = EtlLogic.parseInstant(e.str("end_at")) ?? start
                if DayDate(start) != date { continue }
                let c = e.str("context_code").flatMap { names[$0] }
                out.append(TimetableLogic.ClassRow(hm(start), hm(end), c.map { TimetableLogic.shortName($0.name) } ?? (e.str("context_name") ?? ""),
                                                   c?.courseId, e.str("location_name"), "일정", e.str("title")?.kTake(100)))
            }
        }
        return out
    }
}

// MARK: - 직접 추가한 수업

/// 직접 추가한 주간 수업은 음수 ID를 사용해 eTL 과목과 구분한다.
public enum CustomTimetableLogic {
    private static let HHMM = KRegex("\\d{2}:\\d{2}")

    /// `LocalTime.parse`가 받아 주는 HH:mm(00:00~23:59)인가.
    private static func validTime(_ s: String) -> Bool {
        guard HHMM.matches(s) else { return false }
        let p = s.split(separator: ":")
        guard let h = Int(p[0]), let m = Int(p[1]) else { return false }
        return h < 24 && m < 60
    }

    public static func validationError(_ course: TimetableLogic.CourseMeetings) -> String? {
        if course.courseId >= 0 { return "직접 추가한 수업만 수정할 수 있어요" }
        if course.name.kIsBlank { return "과목명을 입력해 주세요" }
        if course.name.kTrim.kLength > 80 { return "과목명은 80자까지 입력할 수 있어요" }
        if course.meetings.isEmpty { return "요일을 선택해 주세요" }
        if course.meetings.contains(where: { $0.day.kLength != 1 || !TimetableLogic.WEEKDAYS.contains($0.day) }) { return "요일을 확인해 주세요" }
        if course.meetings.contains(where: { !validTime($0.start) || !validTime($0.end) || !($0.end > $0.start) }) {
            return "종료 시간은 시작 시간보다 늦어야 해요"
        }
        return nil
    }

    public static func normalize(_ course: TimetableLogic.CourseMeetings) -> TimetableLogic.CourseMeetings? {
        if validationError(course) != nil { return nil }
        var out = course
        out.name = course.name.kTrim
        out.professor = TimetableLogic.cleanProfessor(course.professor).map { $0.kTake(80) }
        var meetings: [TimetableLogic.Meeting] = []
        for m in course.meetings {
            var n = m
            n.room = m.room.map { $0.kTrim.kTake(80) }.flatMap { $0.nilIfBlank }
            if !meetings.contains(n) { meetings.append(n) }
        }
        let index = { (d: String) -> Int in TimetableLogic.weekdayLabels.firstIndex(of: d) ?? -1 }
        out.meetings = stableSorted(meetings) { a, b in
            if index(a.day) != index(b.day) { return index(a.day) < index(b.day) }
            return a.start < b.start
        }
        return out
    }

    public static func merge(_ catalog: TimetableLogic.Weekly?, _ custom: [TimetableLogic.CourseMeetings]) -> TimetableLogic.Weekly? {
        var seen = Set<Int64>()
        let local = custom.compactMap(normalize).filter { seen.insert($0.courseId).inserted }
        if catalog == nil && local.isEmpty { return nil }
        var base = catalog ?? TimetableLogic.Weekly("", 0, [], [])
        base.courses = base.courses.filter { $0.courseId >= 0 } + local
        return base
    }
}
