import Foundation
import ETLKit

public typealias AnnCategory = Queries.AnnCategory

/// 공지에서 "내 일정에 등록"/"시간표에 반영". 시험·과제·동영상 강의 공지는 내 마감 추가 창을,
/// 보강·휴강 공지는 시간표의 보강·휴강 추가 창을 미리 채워 연다. AI 없이 키워드와 정해진 날짜 모양만 본다.
/// 날짜(+시각)가 **정확히 하나**만 잡힐 때만 미리 넣고, 아니면 비운다(지어내지 않음).
public struct ScheduleDraft: Hashable, Sendable {
    public var announcementId: Int64
    public var kind: MyKind
    public var title: String
    public var course: String
    public var memo: String
    public var supplies: String
    /// 공지에서 찾은 시작(시험·보강)·마감(과제·동영상) 시각. 하나만 확실할 때만.
    public var start: Date?
    /// 공지에서 찾은 끝 시각(시험·보강).
    public var end: Date?
    /// "✨ 비서가 공지 읽고 채우기"를 누르면 채팅 입력창에 채울 요청(공지 ID 포함, 보내지는 않는다).
    public var prefill: String
    public var category: AnnCategory
    /// 휴강·보강·동영상 강의의 그날(시각 없이도). 하나만 확실할 때만.
    public var day: DayDate?
    /// 동영상 강의 공지에 "대면 수업 없음/대체" 단서가 있어 그날을 휴강으로 표시할 수 있는가.
    public var replacesClass: Bool

    public init(_ announcementId: Int64, _ kind: MyKind, _ title: String, _ course: String, _ memo: String, supplies: String = "",
                start: Date? = nil, end: Date? = nil, prefill: String = "", category: AnnCategory? = nil, day: DayDate? = nil, replacesClass: Bool = false) {
        self.announcementId = announcementId
        self.kind = kind
        self.title = title
        self.course = course
        self.memo = memo
        self.supplies = supplies
        self.start = start
        self.end = end
        self.prefill = prefill
        self.category = category ?? (kind == .EXAM ? .EXAM : .ASSIGNMENT)
        self.day = day
        self.replacesClass = replacesClass
    }

    public var foundDate: Bool { start != nil || (category.timetable && day != nil) }
    public var timetable: Bool { category.timetable }
    public var actionLabel: String { timetable ? ScheduleDrafts.TIMETABLE_ACTION : ScheduleDrafts.ACTION }

    public func toJson() -> String {
        JSONObject().put("ann", announcementId).put("kind", kind.id).put("title", title).put("course", course).put("memo", memo)
            .put("supplies", supplies).put("start", start?.epochMilli ?? JSONObject.NULL).put("end", end?.epochMilli ?? JSONObject.NULL)
            .put("prefill", prefill).put("cat", category.rawValue).put("day", day?.description ?? JSONObject.NULL).put("replaces", replacesClass).toString()
    }

    public static func fromJson(_ s: String?) -> ScheduleDraft? {
        guard let s, let o = try? JSONObject(s) else { return nil }
        let kind = MyKind.parse(o.optString("kind")) ?? .DEADLINE
        return ScheduleDraft(
            o.optLong("ann"), kind,
            o.optString("title").kTake(MyDeadlines.TITLE_MAX), o.optString("course").kTake(MyDeadlines.COURSE_MAX), o.optString("memo").kTake(MyDeadlines.MEMO_MAX),
            supplies: o.optString("supplies").kTake(MyDeadlines.SUPPLIES_MAX),
            start: o.isNull("start") ? nil : Date(epochMilli: o.optLong("start")),
            end: o.isNull("end") ? nil : Date(epochMilli: o.optLong("end")),
            prefill: o.optString("prefill").kTake(500),
            category: AnnCategory(rawValue: o.optString("cat")) ?? (kind == .EXAM ? .EXAM : .ASSIGNMENT),
            day: (!o.has("day") || o.isNull("day")) ? nil : DayDate.parse(o.optString("day")),
            replacesClass: o.optBoolean("replaces", false)
        )
    }
}

public enum ScheduleDrafts {
    public static let ACTION = "내 일정에 등록"
    public static let TIMETABLE_ACTION = "시간표에 반영"
    public static let ASSIGNMENT_HINT = "eTL 과제로 안 올라온 마감이면 등록해 두세요."
    public static let FOUND_HINT = "공지에서 찾은 날짜예요. 맞는지 확인해 주세요."
    public static let NOT_FOUND_HINT = "공지에서 날짜를 하나로 정하지 못했어요. 날짜와 시각을 직접 골라 주세요."
    public static let ASK_HINT = "공지에서 날짜를 찾지 못했어요. 비서가 공지를 읽고 채워 드릴까요?"
    public static let ASK_LINK = "✨ 비서가 공지 읽고 채우기"

    /// 시험 공지면 시험, 과제·동영상 공지면 마감, 그 밖(보강·휴강 포함)은 nil.
    public static func kindOf(_ title: String?, _ text: String?) -> MyKind? {
        switch Queries.announcementCategory(title, text) {
        case .EXAM?: return .EXAM
        case .ASSIGNMENT?, .VIDEO?: return .DEADLINE
        default: return nil
        }
    }

    /// 공지 하나로 만든 초안. 시험·과제·동영상 강의·보강·휴강 공지가 아니면 nil.
    public static func fromAnnouncement(_ a: AnnouncementItem) -> ScheduleDraft? {
        guard let cat = Queries.announcementCategory(a.title, a.text) else { return nil }
        let kind: MyKind = cat == .EXAM ? .EXAM : .DEADLINE
        let course = TimetableLogic.shortName(a.courseName).kTake(MyDeadlines.COURSE_MAX)
        let found = single(a.text, a.postedAt) ?? single(a.title, a.postedAt)
        let days = distinct(findDays(a.title, a.postedAt) + findDays(a.text, a.postedAt))
        let link = NoticeText.announcementUrl(a)
        let cleaned = cleanTitle(a.title, a.courseName, course)
        let rawTitle: String
        switch cat {
        case .VIDEO: rawTitle = "[동영상] " + [course, cleaned].filter { !$0.isEmpty }.joined(separator: " ")
        case .EXAM: rawTitle = cleaned.isEmpty ? "시험" : cleaned
        case .ASSIGNMENT: rawTitle = cleaned.isEmpty ? "과제" : cleaned
        case .MAKEUP, .CANCEL: rawTitle = cleaned
        }
        let day: DayDate?
        switch cat {
        case .MAKEUP: day = found.map { DayDate($0.0) } ?? (days.count == 1 ? days[0] : nil)
        case .CANCEL: day = days.count == 1 ? days[0] : nil
        // 동영상: 대면 수업 날짜. 날짜가 하나면 그날, 둘이고 하나가 시청 기한이면 다른 날(앞 날).
        case .VIDEO:
            if days.count == 1 { day = days[0] }
            else if days.count == 2, let f = found { day = days.first { $0 != DayDate(f.0) } }
            else { day = nil }
        default: day = nil
        }
        let memo: String
        switch cat {
        case .ASSIGNMENT, .VIDEO: memo = (course.isEmpty ? "" : "\(course) · ") + "공지: \(link)"
        default: memo = "공지: \(link)"
        }
        return ScheduleDraft(
            a.id, kind, rawTitle.kTake(MyDeadlines.TITLE_MAX), course, memo.kTake(MyDeadlines.MEMO_MAX),
            supplies: cat == .EXAM ? MyDeadlines.normalizeSupplies(Queries.suppliesLines(a.text).joined(separator: ", ")) : "",
            start: cat == .CANCEL ? nil : found?.0,
            end: (cat == .EXAM || cat == .MAKEUP) ? found?.1 : nil,
            prefill: prefill(a, cat),
            category: cat,
            day: day,
            replacesClass: cat == .VIDEO && Queries.replacesClass(a.text) && day != nil
        )
    }

    /// 비서에게 맡길 요청: 공지 ID를 붙여 비서가 source_announcement_id로 남기게 한다.
    public static func prefill(_ a: AnnouncementItem, _ cat: AnnCategory) -> String {
        let named = "\(NoticeText.course(a.courseName)) ‘\(NoticeText.safe(a.title, 60))’ 공지 보고 "
        let ask: String
        switch cat {
        case .EXAM: return NotificationPlanner.examPrefill([a]) + " (공지 ID \(a.id))"
        case .ASSIGNMENT: return NotificationPlanner.assignmentPrefill([a]) + " (공지 ID \(a.id))"
        case .VIDEO: ask = "동영상 강의 시청 기한을 내 일정에 넣고, 대면 수업이 없으면 시간표에 휴강으로 표시해줘"
        case .MAKEUP: ask = "보강을 시간표에 넣어줘"
        case .CANCEL: ask = "휴강을 시간표에 넣어줘"
        }
        return "\(named)\(ask) (공지 ID \(a.id))"
    }

    private static let PREFIX = KRegex("^\\s*(?:[\\[(【][^\\])】]{0,30}[\\])】]\\s*)+")
    private static let NOTICE_WORD = KRegex("^\\s*(?:공지|안내|알림|필독|중요)\\s*[:：-]?\\s*")

    /// 공지 제목 정리: 앞의 `[과목명]`·`(공지)` 같은 괄호 머리말, "공지:" 머리말, 앞의 과목명을 뗀다.
    public static func cleanTitle(_ title: String, _ courseName: String, _ shortCourse: String) -> String {
        var t = NoticeText.clean(title)
        let trimSet: Set<Character> = [" ", "-", ":", "·", "|"]
        for _ in 0..<3 {
            t = NOTICE_WORD.replace(PREFIX.replace(t, ""), "")
            for c in [NoticeText.clean(courseName), shortCourse].filter({ $0.kLength >= 2 }) {
                if t.hasPrefix(c) { t = String(t.dropFirst(c.count).drop(while: { trimSet.contains($0) })) }
            }
        }
        return t.kTrim
    }

    // ---- 날짜·시각 찾기 ---------------------------------------------------------

    private static let WD = "(?:\\s*\\(?\\s*[월화수목금토일](?:요일)?\\s*\\)?)?"
    private static let TIME = "(?:(오전|오후|AM|PM|am|pm)\\s*)?(\\d{1,2})(?::(\\d{2})|\\s*시(?:\\s*(\\d{1,2})\\s*분)?(?:\\s*(반))?)"
    private static let DATE_PATTERNS: [KRegex] = [
        // 2026-11-28, 2026.11.28, 2026/11/28
        KRegex("(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})\\.?"),
        // 2026년 11월 28일, 11월 28일
        KRegex("(?:(\\d{4})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"),
        // 11/28, 11.28.
        KRegex("(?<![\\d.])()(\\d{1,2})\\s*[/.]\\s*(\\d{1,2})\\.?(?![\\d])"),
    ]
    private static let TIME_RE = KRegex("^\(WD)\\s*,?\\s*\(TIME)(?:\\s*[~∼〜\\-–]\\s*\(TIME))?")
    private static let SHORT_DATE_TAIL = KRegex("^\(WD)\\s*(?:\\d|[(（]|오전|오후|수업|휴강|보강|강의|$)")

    private static func overlaps(_ a: Range<String.Index>, _ b: Range<String.Index>) -> Bool { a.lowerBound < b.upperBound && b.lowerBound < a.upperBound }

    /// 공지 본문의 날짜(시각 없이도) 전부. 연도가 없으면 [posted] 기준 가장 가까운 미래.
    public static func findDays(_ text: String?, _ posted: Date) -> [DayDate] {
        let s = text ?? ""
        let postedDate = DayDate(posted)
        var out: [DayDate] = []
        var used: [Range<String.Index>] = []
        for (idx, re) in DATE_PATTERNS.enumerated() {
            for m in re.findAll(s) {
                if used.contains(where: { overlaps($0, m.range) }) { continue }
                // MM/DD·MM.DD 모양은 뒤에 요일이나 시각이 붙을 때만(점수·소수와 헷갈리지 않게).
                if idx == 2 && !SHORT_DATE_TAIL.containsMatchIn(String(s[m.range.upperBound...]).kTake(12)) { continue }
                guard let mo = Int(m.groupValues[2]), let d = Int(m.groupValues[3]),
                      let date = dateOf(Int(m.groupValues[1]), mo, d, postedDate) else { continue }
                used.append(m.range)
                if !out.contains(date) { out.append(date) }
            }
        }
        return out
    }

    private static func hour(_ ampm: String, _ h: Int) -> Int? {
        let a = ampm.lowercased()
        guard (0...24).contains(h) else { return nil }
        let v: Int
        if a == "오후" || a == "pm" { v = h < 12 ? h + 12 : h }
        else if a == "오전" || a == "am" { v = h == 12 ? 0 : h }
        else { v = h }
        return (0...23).contains(v) ? v : nil
    }

    private static func time(_ g: [String], _ base: Int) -> DayTime? {
        func at(_ i: Int) -> String? { i < g.count ? g[i] : nil }
        guard let h = at(base + 1).flatMap({ Int($0) }) else { return nil }
        let m = at(base + 2).flatMap { Int($0) } ?? at(base + 3).flatMap { Int($0) } ?? (at(base + 4) == "반" ? 30 : 0)
        guard let hh = hour(at(base) ?? "", h) else { return nil }
        return (0...59).contains(m) ? DayTime(hour: hh, minute: m) : nil
    }

    /// 본문에서 "날짜 + 시각"을 모두 찾는다(시각 없는 날짜는 세지 않는다). 연도가 없으면 [posted] 날짜(KST) 기준 가장 가까운 미래.
    /// 끝 시각("19:00~21:00")이 있으면 함께. 같은 시각은 하나로 센다.
    public static func findAll(_ text: String?, _ posted: Date) -> [(Date, Date?)] {
        let s = text ?? ""
        let postedDate = DayDate(posted)
        var keys: [Date] = []
        var out: [Date: Date?] = [:]
        var used: [Range<String.Index>] = []
        for re in DATE_PATTERNS {
            for m in re.findAll(s) {
                if used.contains(where: { overlaps($0, m.range) }) { continue }
                guard let mo = Int(m.groupValues[2]), let d = Int(m.groupValues[3]),
                      let date = dateOf(Int(m.groupValues[1]), mo, d, postedDate) else { continue }
                guard let tm = TIME_RE.find(String(s[m.range.upperBound...])) else { continue }
                let g = tm.groupValues
                guard let start = time(g, 1) else { continue }
                let end = time(g, 6)
                used.append(m.range)
                let st = date.atTime(start.hour, start.minute)
                let en: Date? = end.flatMap { e in
                    let t = date.atTime(e.hour, e.minute)
                    return t > st ? t : nil
                }
                if let existing = out[st] {
                    if existing == nil { out[st] = .some(en) }
                } else {
                    keys.append(st)
                    out[st] = .some(en)
                }
            }
        }
        return keys.map { ($0, out[$0]!) }
    }

    /// 정확히 하나일 때만.
    public static func single(_ text: String?, _ posted: Date) -> (Date, Date?)? {
        let all = findAll(text, posted)
        return all.count == 1 ? all[0] : nil
    }

    private static func dateOf(_ y: Int?, _ mo: Int, _ d: Int, _ posted: DayDate) -> DayDate? {
        if !(1...12).contains(mo) || !(1...31).contains(d) { return nil }
        if let y { return DayDate(year: y, month: mo, day: d) }
        if let same = DayDate(year: posted.year, month: mo, day: d), !(same < posted) { return same }
        return DayDate(year: posted.year + 1, month: mo, day: d)
    }

    /// 여러 공지 중 첫 시험·과제 공지의 초안(묶음 알림용).
    public static func firstOf(_ items: [AnnouncementItem]) -> ScheduleDraft? {
        for a in items { if let d = fromAnnouncement(a) { return d } }
        return nil
    }
}
