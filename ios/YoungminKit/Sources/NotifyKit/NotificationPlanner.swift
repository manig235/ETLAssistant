import Foundation
import ETLKit

/// [NotificationPlanner.plan]의 결과.
/// - notices: 띄울 알림들. 호출자는 **실제로 띄운 뒤** 각 [Notice.firedKeys]를 [NotifyState.fired]에 기록한다.
/// - silentKeys: 알림 없이 "본 것"으로만 기록할 키들(첫 실행의 기존 공지 등).
/// - markBaselineDone: true면 호출자가 [NotifyState.announcementBaselineDone]을 true로 바꾼다.
public struct Plan: Hashable, Sendable {
    public var notices: [Notice]
    public var silentKeys: [String]
    public var markBaselineDone: Bool

    public init(_ notices: [Notice], _ silentKeys: [String] = [], _ markBaselineDone: Bool = false) {
        self.notices = notices
        self.silentKeys = silentKeys
        self.markBaselineDone = markBaselineDone
    }

    public init(notices: [Notice], silentKeys: [String] = [], markBaselineDone: Bool = false) {
        self.init(notices, silentKeys, markBaselineDone)
    }
}

/// 알림 문구 공통 도구. eTL에서 온 문자열은 신뢰할 수 없는 입력이므로 알림에 넣기 전에 반드시 [clean]/[cut]을 거친다.
/// (Kotlin에서는 `internal`이지만 화면에서도 같은 표기를 쓰도록 공개한다.)
public enum NoticeText {
    public static let TITLE_MAX = 80
    public static let LINE_MAX = 140
    static let COURSE_MAX = 30
    static let NAME_MAX = 50
    public static let ETL_PREFIX = "https://myetl.snu.ac.kr/"
    private static let WEEKDAYS = ["월", "화", "수", "목", "금", "토", "일"]

    /// 방향 제어·폭 없는 공백 등 화면을 속일 수 있는 문자. 지운다.
    private static let INVISIBLE: Set<UInt32> = [0x200B, 0x200C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2060, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF]

    /// 제어문자(개행 포함)와 각종 공백을 한 칸 공백으로 줄이고 앞뒤를 다듬는다.
    public static func clean(_ s: String?) -> String {
        guard let s, !s.isEmpty else { return "" }
        var out = String.UnicodeScalarView()
        var lastSpace = true
        for u in s.unicodeScalars {
            if INVISIBLE.contains(u.value) { continue }
            let cat = u.properties.generalCategory
            let space = u.isISOControl || cat == .spaceSeparator || cat == .lineSeparator || cat == .paragraphSeparator
            if space {
                if !lastSpace { out.append(" ") }
                lastSpace = true
            } else {
                out.append(u)
                lastSpace = false
            }
        }
        return String(out).kTrimEnd
    }

    /// [max]자(UTF-16, 말줄임표 포함)로 자른다. 서로게이트 쌍을 가운데서 자르지 않는다.
    public static func cut(_ s: String, _ max: Int) -> String {
        let u = Array(s.utf16)
        if u.count <= max { return s }
        var end = Swift.max(max - 1, 0)
        if end > 0 && UTF16.isLeadSurrogate(u[end - 1]) { end -= 1 }
        return String(decoding: u[0..<end], as: UTF16.self).kTrimEnd + "…"
    }

    public static func safe(_ s: String?, _ max: Int) -> String { cut(clean(s), max) }

    public static func course(_ a: String?) -> String { safe(a, COURSE_MAX) }
    public static func name(_ a: String?) -> String { safe(a, NAME_MAX) }

    /// https://myetl.snu.ac.kr/ 로 시작하고 공백·제어문자가 없을 때만 통과.
    public static func safeUrl(_ url: String?) -> String? {
        guard let u = url?.kTrim else { return nil }
        if !u.hasPrefix(ETL_PREFIX) { return nil }
        // Java isISOControl / isWhitespace(char 단위).
        if u.unicodeScalars.contains(where: { $0.isISOControl || ($0.isKotlinWhitespace && ![0x00A0, 0x2007, 0x202F].contains($0.value)) }) { return nil }
        return u
    }

    /// 내 일정은 eTL로 보내지 않는다. eTL 과제 링크가 없거나 부적절하면 ID로 복원한다.
    public static func assignmentUrl(_ a: AssignmentItem) -> String? {
        if a.courseId <= 0 { return nil }
        if let u = safeUrl(a.url) { return u }
        return a.assignmentId > 0 ? "\(ETL_PREFIX)courses/\(a.courseId)/assignments/\(a.assignmentId)" : "\(ETL_PREFIX)courses/\(a.courseId)"
    }

    public static func announcementUrl(_ a: AnnouncementItem) -> String {
        if let u = safeUrl(a.url) { return u }
        if a.courseId > 0 && a.id > 0 { return "\(ETL_PREFIX)courses/\(a.courseId)/discussion_topics/\(a.id)" }
        if a.courseId > 0 { return "\(ETL_PREFIX)courses/\(a.courseId)" }
        return ETL_PREFIX
    }

    /// 한 강의의 묶음은 강의 화면으로, 여러 강의는 eTL 홈으로 연결한다. 내 일정만 있으면 앱을 연다.
    public static func collectionUrl(_ courseIds: [Int64]) -> String? {
        let etl = distinct(courseIds.filter { $0 > 0 })
        switch etl.count {
        case 0: return nil
        case 1: return "\(ETL_PREFIX)courses/\(etl[0])"
        default: return ETL_PREFIX
        }
    }

    /// KST 고정 `11/23(월) 15:00`.
    public static func dateLabel(_ t: Date?) -> String {
        guard let t else { return "마감일 없음" }
        let p = EtlLogic.kstParts(t)
        return String(format: "%02d/%02d(%@) %02d:%02d", p.day.month, p.day.day, WEEKDAYS[p.day.dayOfWeek.rawValue - 1], p.hour, p.minute)
    }

    /// 지정한 시간대 기준 `11/19(목)`.
    public static func dayLabel(_ t: Date, _ zone: TimeZone) -> String {
        let d = ZoneClock.localDate(t, zone)
        return String(format: "%02d/%02d(%@)", d.month, d.day, WEEKDAYS[d.dayOfWeek.rawValue - 1])
    }

    /// `• 11/23(월) 15:00 · 강의 — 과제 (1일 2시간 남음)` + 오프라인이면 ` (오프라인)`.
    public static func dueLine(_ a: AssignmentItem, _ now: Date, _ prefix: String = "") -> String {
        let rem = EtlLogic.remaining(a.due, now)
        return "• \(prefix)\(dateLabel(a.due)) · \(course(a.courseName)) — \(name(a.name)) (\(rem))" + (a.online ? "" : " (오프라인)")
    }

    public static func annLine(_ a: AnnouncementItem) -> String { "• [\(course(a.courseName))] \(safe(a.title, NAME_MAX + 30))" }

    /// [lines]를 [max]줄까지 이어 붙이고, 넘치면 `외 N건`을 덧붙인다.
    public static func joinLimited(_ lines: [String], _ max: Int) -> String {
        if lines.count <= max { return lines.joined(separator: "\n") }
        return lines.prefix(max).joined(separator: "\n") + "\n외 \(lines.count - max)건"
    }
}

/// 마감·공지 알림 계획(순수 함수). [state]는 읽기만 한다.
public enum NotificationPlanner {
    private static let DEADLINE_BIG_LINES = 6
    private static let ANN_BIG_LINES = 8
    private static let ANN_INDIVIDUAL_MAX = 3
    private static let ANN_BODY_MAX = 300

    public static func plan(_ snapshot: Snapshot, _ state: NotifyState, _ now: Date, _ settings: NotifySettings, _ zone: TimeZone = .current) -> Plan {
        let quiet = Quiet.isQuiet(ZoneClock.minuteOfDay(now, zone), settings)
        var notices: [Notice] = []
        var silent: [String] = []
        var baseline = false

        if !quiet && settings.deadlineAlerts { notices += deadlineNotices(snapshot, state, now, settings) }

        if settings.announcementAlerts {
            if !state.announcementBaselineDone {
                // 첫 확인: 기존 공지는 알리지 않고 "본 것"으로만 기록한다(방해 금지와 무관).
                silent += distinct(snapshot.announcements.map { "ann:\($0.id)" })
                baseline = true
            } else if !quiet {
                notices += announcementNotices(snapshot, state)
            }
        }
        return Plan(notices, silent, baseline)
    }

    // ---- 마감 ---------------------------------------------------------------

    private struct PairKey: Hashable { let c: Int64; let a: Int64 }

    private static func deadlineNotices(_ snapshot: Snapshot, _ state: NotifyState, _ now: Date, _ settings: NotifySettings) -> [Notice] {
        let windows = distinct(settings.deadlineWindowsMinutes.filter { $0 > 0 }).sorted()
        if windows.isEmpty { return [] }

        var groups: [Int: [AssignmentItem]] = [:]
        var keys: [Int: [String]] = [:]
        var seen = Set<PairKey>()
        for a in snapshot.assignments {
            guard let due = a.due else { continue }
            if a.submitted || a.missing || !(due > now) { continue }
            if !seen.insert(PairKey(c: a.courseId, a: a.assignmentId)).inserted { continue }
            let left = due.timeIntervalSince(now)
            guard let w = windows.first(where: { left <= Double($0) * 60 }) else { continue }
            if isFired(state, a, w) { continue }
            groups[w, default: []].append(a)
            var ks = keys[w, default: []]
            func add(_ k: String) { if !ks.contains(k) { ks.append(k) } }
            add(key(a, w))
            for x in windows where x > w { add(key(a, x)) } // 더 느슨한 창은 다시 알리지 않는다
            keys[w] = ks
        }

        return groups.keys.sorted().map { w in
            let items = stableSorted(groups[w]!) { x, y in
                if x.due != y.due { return lessNullsFirst(x.due, y.due) }
                if x.courseName != y.courseName { return x.courseName.kLess(y.courseName) }
                return x.assignmentId < y.assignmentId
            }
            let fired = keys[w]!
            return items.count == 1 ? single(items[0], now, fired) : group(items, w, now, fired)
        }
    }

    /// 중복 방지 키(분 단위).
    private static func key(_ a: AssignmentItem, _ w: Int) -> String { "deadline-m:\(a.courseId):\(a.assignmentId):\(w)" }

    /// 이미 알렸는가. 시간 단위로 떨어지는 창은 예전 키로 기록된 것도 인정한다.
    private static func isFired(_ state: NotifyState, _ a: AssignmentItem, _ w: Int) -> Bool {
        state.fired[key(a, w)] != nil || (w % 60 == 0 && state.fired["deadline:\(a.courseId):\(a.assignmentId):\(w / 60)"] != nil)
    }

    /// 다음 마감 알림 시점(어떤 과제가 아직 알리지 않은 창에 들어가는 가장 이른 시각). 없으면 nil.
    /// [state]는 이번 확인에서 띄운 키까지 기록된 상태를 넘긴다.
    public static func nextDeadlineCheckAt(_ snapshot: Snapshot, _ state: NotifyState, _ now: Date, _ settings: NotifySettings) -> Date? {
        if !settings.deadlineAlerts { return nil }
        let windows = distinct(settings.deadlineWindowsMinutes.filter { $0 > 0 })
        var best: Date?
        for a in snapshot.assignments {
            guard let due = a.due else { continue }
            if a.submitted || a.missing || !(due > now) { continue }
            for w in windows {
                if isFired(state, a, w) { continue }
                let t = due.addingTimeInterval(-Double(w) * 60)
                if t > now && (best == nil || t < best!) { best = t }
            }
        }
        return best
    }

    private static func single(_ a: AssignmentItem, _ now: Date, _ fired: [String]) -> Notice {
        let title = NoticeText.cut("마감 임박 · " + NoticeText.clean(a.courseName), NoticeText.TITLE_MAX)
        let text = NoticeText.cut("\(NoticeText.name(a.name)) · \(NoticeText.dateLabel(a.due)) (\(EtlLogic.remaining(a.due, now)))" + (a.online ? "" : " (오프라인)"), NoticeText.LINE_MAX)
        let mine = a.courseId == MyDeadlines.COURSE_ID
        return Notice(.DEADLINE, title, text, bigText: NoticeText.dueLine(a, now), url: NoticeText.assignmentUrl(a),
                      tag: "deadline:\(a.courseId):\(a.assignmentId)", firedKeys: fired, agenda: mine, mineItemId: mine ? a.assignmentId : nil)
    }

    private static func group(_ items: [AssignmentItem], _ w: Int, _ now: Date, _ fired: [String]) -> Notice {
        let first = items[0]
        let head = "\(NoticeText.course(first.courseName)) — \(NoticeText.name(first.name)) (\(EtlLogic.remaining(first.due, now)))"
        let text = NoticeText.cut(head + " 외 \(items.count - 1)건", NoticeText.LINE_MAX)
        return Notice(.DEADLINE, NoticeText.cut("마감 임박 \(items.count)건 · \(NotifySettings.windowLabel(w)) 이내", NoticeText.TITLE_MAX), text,
                      bigText: NoticeText.joinLimited(items.map { NoticeText.dueLine($0, now) }, DEADLINE_BIG_LINES),
                      url: NoticeText.collectionUrl(items.map(\.courseId)), tag: "deadline:wm\(w)", firedKeys: fired,
                      // 내 마감만 묶였으면 내 일정을 연다(eTL 과제가 섞이면 eTL).
                      agenda: items.allSatisfy { $0.courseId == MyDeadlines.COURSE_ID })
    }

    // ---- 공지 ---------------------------------------------------------------

    private static func announcementNotices(_ snapshot: Snapshot, _ state: NotifyState) -> [Notice] {
        let fresh = stableSorted(distinctBy(snapshot.announcements.filter { state.fired["ann:\($0.id)"] == nil }) { $0.id }) { x, y in
            if x.postedAt != y.postedAt { return x.postedAt > y.postedAt }
            return x.id > y.id
        }
        if fresh.isEmpty { return [] }

        if fresh.count <= ANN_INDIVIDUAL_MAX {
            return fresh.map { a in
                let body = NoticeText.clean(a.text)
                // 시험 공지면 준비물·지참 줄(최대 2줄, 60자)을 본문 앞에 붙인다.
                let supplies = Queries.isExamAnnouncement(a.title, a.text)
                    ? Queries.suppliesLines(a.text).map { NoticeText.clean($0) }.filter { !$0.isEmpty } : []
                let parts = [
                    supplies.isEmpty ? nil : "준비물·지참: " + supplies.joined(separator: "\n"),
                    body.isEmpty ? nil : NoticeText.cut(body, ANN_BODY_MAX),
                ].compactMap { $0 }
                let big = parts.joined(separator: "\n\n").nilIfEmpty
                let n = Notice(.ANNOUNCEMENT,
                               NoticeText.cut(NoticeText.clean(a.courseName) + " · " + Queries.announcementBadge(a.title, a.text), NoticeText.TITLE_MAX),
                               NoticeText.safe(a.title, NoticeText.LINE_MAX), bigText: big, url: NoticeText.announcementUrl(a),
                               tag: "ann:\(a.id)", firedKeys: ["ann:\(a.id)"])
                return withExamAction(n, [a])
            }
        }
        let lines = fresh.map { a -> String in
            let badge = Queries.announcementBadge(a.title, a.text)
            return badge == "새 공지" ? NoticeText.annLine(a) : NoticeText.annLine(a).replacingFirst("• ", "• (\(badge)) ")
        }
        let n = Notice(.ANNOUNCEMENT, "새 공지 \(fresh.count)건",
                       NoticeText.cut(lines[0].removingPrefix("• ") + " 외 \(fresh.count - 1)건", NoticeText.LINE_MAX),
                       bigText: NoticeText.joinLimited(lines, ANN_BIG_LINES),
                       url: NoticeText.collectionUrl(fresh.map(\.courseId)) ?? NoticeText.ETL_PREFIX,
                       tag: "ann:summary", firedKeys: fresh.map { "ann:\($0.id)" })
        return [withExamAction(n, fresh)]
    }

    /// 시험·과제 공지면 "내 일정에 등록" 초안을 단다. 묶음이면 첫 시험·과제 공지로.
    private static func withExamAction(_ n: Notice, _ items: [AnnouncementItem]) -> Notice {
        guard let d = ScheduleDrafts.firstOf(items) else { return n }
        var c = n
        c.draft = d.toJson()
        return c
    }

    /// `{과목} ‘{제목}’ 공지 보고 과제 마감(날짜·시간·제출 방법) 내 일정에 넣어줘`.
    public static func assignmentPrefill(_ items: [AnnouncementItem]) -> String {
        let named = items.prefix(2).map { "\(NoticeText.course($0.courseName)) ‘\(NoticeText.safe($0.title, 60))’" }.joined(separator: ", ")
        let more = items.count > 2 ? " 외 \(items.count - 2)개" : ""
        return "\(named)\(more) 공지 보고 과제 마감(날짜·시간·제출 방법) 내 일정에 넣어줘"
    }

    public static let EXAM_ACTION = "일정 정리 부탁"

    /// `{과목} ‘{제목}’ 공지 보고 시험 일정(날짜·시간·장소) 내 일정에 넣어줘`.
    public static func examPrefill(_ exams: [AnnouncementItem]) -> String {
        let named = exams.prefix(2).map { "\(NoticeText.course($0.courseName)) ‘\(NoticeText.safe($0.title, 60))’" }.joined(separator: ", ")
        let more = exams.count > 2 ? " 외 \(exams.count - 2)개" : ""
        return "\(named)\(more) 공지 보고 시험 일정(날짜·시간·장소·준비물) 내 일정에 넣어줘"
    }
}
