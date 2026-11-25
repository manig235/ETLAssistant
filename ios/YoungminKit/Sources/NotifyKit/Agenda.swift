import Foundation
import ETLKit

/*
 * "내 일정" 화면: eTL 과제와 내 마감. 수업은 시간표에서 보여 준다.
 * eTL 과제는 알림 확인 때 받은 결과를 [SnapshotCache]에 저장해 두고 화면을 열면 바로 보여 준다.
 */

/// 마지막으로 받은 eTL 과제 목록(내 마감은 넣지 않는다). [takenAt]은 epoch millis.
public struct CachedSnapshot: Hashable, Sendable {
    public var takenAt: Int64
    public var assignments: [AssignmentItem]
    public init(_ takenAt: Int64, _ assignments: [AssignmentItem]) {
        self.takenAt = takenAt
        self.assignments = assignments
    }
}

public enum SnapshotCacheLogic {
    public static func toJson(_ s: CachedSnapshot) -> String {
        let arr = JSONArray()
        for a in s.assignments {
            let o = JSONObject().put("cid", a.courseId).put("course", a.courseName).put("aid", a.assignmentId).put("name", a.name)
                .put("due", a.due?.epochMilli ?? JSONObject.NULL).put("online", a.online).put("points", a.points ?? JSONObject.NULL)
                .put("url", a.url ?? JSONObject.NULL).put("submitted", a.submitted).put("missing", a.missing).put("score", a.score ?? JSONObject.NULL)
                .put("subAt", a.submittedAt?.epochMilli ?? JSONObject.NULL)
            if let c = a.check { o.put("check", checkJson(c)) }
            arr.put(o)
        }
        return JSONObject().put("version", 1).put("takenAt", s.takenAt).put("assignments", arr).toString()
    }

    public static func fromJson(_ s: String?) -> CachedSnapshot? {
        guard let s, let o = try? JSONObject(s) else { return nil }
        if o.optInt("version") != 1 { return nil }
        let arr = o.optJSONArray("assignments") ?? JSONArray()
        let items: [AssignmentItem] = (0..<arr.length()).compactMap { i in
            guard let a = arr.optJSONObject(i) else { return nil }
            return AssignmentItem(
                a.optLong("cid"), a.optString("course"), a.optLong("aid"), a.optString("name"),
                a.isNull("due") ? nil : Date(epochMilli: a.optLong("due")), a.optBoolean("online", true),
                a.isNull("points") ? nil : a.optDouble("points"), a.isNull("url") ? nil : a.optString("url"),
                a.optBoolean("submitted"), a.optBoolean("missing"), a.isNull("score") ? nil : a.optDouble("score"),
                (!a.has("subAt") || a.isNull("subAt")) ? nil : Date(epochMilli: a.optLong("subAt")),
                a.optJSONObject("check").map(checkFrom)
            )
        }
        return CachedSnapshot(o.optLong("takenAt"), items)
    }

    private static func checkJson(_ c: SubmitCheck) -> JSONObject {
        let o = JSONObject().put("late", c.late).put("problems", JSONArray(c.problems)).put("attKnown", c.attachmentsKnown)
        if let f = c.files { o.put("files", f) }
        return o
    }

    private static func checkFrom(_ o: JSONObject) -> SubmitCheck {
        let p = o.optJSONArray("problems") ?? JSONArray()
        return SubmitCheck(late: o.optBoolean("late"), problems: (0..<p.length()).map { p.optString($0) }, attachmentsKnown: o.optBoolean("attKnown", true),
                           files: (o.has("files") && !o.isNull("files")) ? o.optInt("files") : nil)
    }
}

/// 받은 eTL 과제 목록 캐시(Kotlin prefs `etl_snapshot`의 `snapshot`). 저장할 때 [EtlEvents.dataChanged]를 보낸다.
public final class SnapshotCache: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSRecursiveLock()

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "etl_snapshot")) { self.prefs = prefs }

    public func load() -> CachedSnapshot? { lock.withLock { SnapshotCacheLogic.fromJson(prefs.string("snapshot")) } }

    /// 일부 과목 조회가 실패했으면 그 과목은 이전 값을 남긴다(빈 목록으로 덮어 사라지지 않게).
    public func save(_ s: Snapshot) {
        let changed: Bool = lock.withLock {
            let items = s.assignments.filter { $0.courseId != MyDeadlines.COURSE_ID }
            if items.isEmpty && !s.errors.isEmpty { return false }
            let failedCourses = Set(s.errors.map { $0.substringBefore(":") })
            let kept = (load()?.assignments ?? []).filter { old in failedCourses.contains(old.courseName) && !items.contains { $0.courseId == old.courseId } }
            prefs.set(SnapshotCacheLogic.toJson(CachedSnapshot(s.takenAt.epochMilli, items + kept)), for: "snapshot")
            return true
        }
        if changed { EtlEvents.dataChanged() }
    }
}

/// 캘린더 일정 하나(Kotlin `calendar/CalendarRead.kt`의 `CalEvent`). iOS 1단계에서는 캘린더 연동이 없어
/// 화면이 EventKit 일정을 넘길 때만 쓴다. 종일 일정은 [start]·[end]가 KST 자정이고 [startDate]~[endDate](포함)가 날짜다.
public struct CalEvent: Hashable, Sendable {
    public var eventId: Int64
    public var calendarId: Int64
    public var calendarName: String
    public var color: Int
    public var title: String
    public var location: String
    public var description: String
    public var start: Date
    public var end: Date
    public var allDay: Bool
    public var startDate: DayDate
    public var endDate: DayDate
    public var recurring: Bool

    public init(eventId: Int64, calendarId: Int64, calendarName: String, color: Int, title: String, location: String, description: String,
                start: Date, end: Date, allDay: Bool, startDate: DayDate, endDate: DayDate, recurring: Bool) {
        self.eventId = eventId
        self.calendarId = calendarId
        self.calendarName = calendarName
        self.color = color
        self.title = title
        self.location = location
        self.description = description
        self.start = start
        self.end = end
        self.allDay = allDay
        self.startDate = startDate
        self.endDate = endDate
        self.recurring = recurring
    }

    public var key: String { "cal-\(eventId)-\(start.epochMilli)" }
}

/// Kotlin `CalendarReadLogic`의 표시 부분(시각 표기·브리핑 줄).
public enum CalendarReadLogic {
    /// `10:00~11:30`, 종일이면 `종일`, 여러 날 종일이면 `11/24~11/26 종일`, 날을 넘기면 `10:00~11/25 02:00`.
    public static func timeLabel(_ e: CalEvent) -> String {
        if e.allDay {
            return e.startDate == e.endDate ? "종일" : "\(e.startDate.monthValue)/\(e.startDate.dayOfMonth)~\(e.endDate.monthValue)/\(e.endDate.dayOfMonth) 종일"
        }
        let s = EtlLogic.kstParts(e.start)
        let en = EtlLogic.kstParts(e.end)
        let hs = String(format: "%02d:%02d", s.hour, s.minute)
        if e.end == e.start { return hs }
        let he = String(format: "%02d:%02d", en.hour, en.minute)
        if en.day == s.day || (en.hour == 0 && en.minute == 0 && en.day == s.day.plusDays(1)) { return "\(hs)~\(he)" }
        return "\(hs)~\(en.day.monthValue)/\(en.day.dayOfMonth) \(he)"
    }

    /// 브리핑 "오늘 일정" 줄: 오늘(KST)과 겹치는 일정, 종일 먼저.
    public static func briefLines(_ events: [CalEvent], _ today: DayDate) -> [String] {
        let start = today.startOfDay
        let end = today.plusDays(1).startOfDay
        let picked = events.filter { ($0.end > start && $0.start < end) || ($0.start == $0.end && !($0.start < start) && $0.start < end) }
        return stableSorted(picked) { a, b in
            if a.allDay != b.allDay { return a.allDay }
            if a.start != b.start { return a.start < b.start }
            return a.title.kLess(b.title)
        }.map { e in
            "• \(timeLabel(e)) · \(NoticeText.cut(e.title, 50))" + (e.location.isEmpty ? "" : " · \(NoticeText.cut(e.location, 30))")
        }
    }
}

public enum Agenda {
    public enum Kind: Int, CaseIterable, Hashable, Sendable { case ASSIGNMENT = 0, MINE, EXAM, CALENDAR }

    /// [CALENDAR]는 "내 캘린더 일정 보기"가 켜져 있을 때만 화면에 보인다.
    public enum Filter: CaseIterable, Hashable, Sendable {
        case ALL, ASSIGNMENT, MINE, EXAM, CALENDAR
        public var label: String {
            switch self {
            case .ALL: return "전체"
            case .ASSIGNMENT: return "과제"
            case .MINE: return "내 마감"
            case .EXAM: return "시험"
            case .CALENDAR: return "캘린더"
            }
        }
    }

    /// 같은 이름·시각의 과제도 서로 다른 행으로 유지한다.
    public struct Row: Hashable, Sendable {
        public var id: String
        public var kind: Kind
        public var title: String
        public var subtitle: String
        public var at: Date
        /// 제출했거나 완료한 항목(숨기기 대상).
        public var done: Bool
        public var overdue: Bool
        public var url: String?
        /// 내 마감이면 그 항목(반복 마감이면 회차). 고칠 때는 저장된 원래 항목을 다시 찾는다.
        public var mine: MyDeadline?
        /// eTL 과제면 그 과제("제출 확인"에 쓴다).
        public var assignment: AssignmentItem?
        /// 제출했지만 제출 정보 점검에서 문제가 나온 과제. 제출·완료를 숨겨도 보인다.
        public var attention: Bool
        /// 캘린더에서 읽은 일정이면 그 일정.
        public var calendar: CalEvent?
        /// 시각 대신 보일 문구(시험 `10:00~12:00`, 캘린더 `종일`). nil이면 [at]과 남은 시간.
        public var timeText: String?

        public init(_ id: String, _ kind: Kind, _ title: String, _ subtitle: String, _ at: Date, done: Bool = false, overdue: Bool = false,
                    url: String? = nil, mine: MyDeadline? = nil, assignment: AssignmentItem? = nil, attention: Bool = false,
                    calendar: CalEvent? = nil, timeText: String? = nil) {
            self.id = id
            self.kind = kind
            self.title = title
            self.subtitle = subtitle
            self.at = at
            self.done = done
            self.overdue = overdue
            self.url = url
            self.mine = mine
            self.assignment = assignment
            self.attention = attention
            self.calendar = calendar
            self.timeText = timeText
        }
    }

    public struct Group: Hashable, Sendable {
        public var date: DayDate
        public var rows: [Row]
        public init(_ date: DayDate, _ rows: [Row]) {
            self.date = date
            self.rows = rows
        }
    }

    public struct Schedule: Hashable, Sendable {
        public var today: DayDate
        public var urgent: [Row]
        public var overdue: [Row]
        public var upcoming: [Group]
        public var later: [Group]

        public init(today: DayDate, urgent: [Row], overdue: [Row], upcoming: [Group], later: [Group]) {
            self.today = today
            self.urgent = urgent
            self.overdue = overdue
            self.upcoming = upcoming
            self.later = later
        }

        public var lastDay: DayDate { today.plusDays(6) }
        public var upcomingCount: Int { upcoming.reduce(0) { $0 + $1.rows.count } }
        public var laterCount: Int { later.reduce(0) { $0 + $1.rows.count } }
    }

    /// 오늘~6일 뒤의 전체 마감과 이후 마감을 날짜별로 묶는다.
    /// 24시간 내 미완료 마감은 요약에도 넣으며 전체 목록에서 빼지 않는다.
    /// 지난 미완료 마감은 따로 유지하고, [hideDone]이면 앞으로의 제출·완료 항목도 뺀다.
    public static func build(_ assignments: [AssignmentItem], _ mine: [MyDeadline], _ now: Date, _ zone: TimeZone,
                             _ filter: Filter = .ALL, hideDone: Bool = true, calendar: [CalEvent] = []) -> Schedule {
        var rows: [Row] = []
        if filter == .ALL || filter == .ASSIGNMENT {
            for a in assignments {
                guard let due = a.due else { continue }
                let past = !(due > now)
                if past && (a.submitted || !a.missing) { continue }
                rows.append(Row("assignment-\(a.courseId)-\(a.assignmentId)", .ASSIGNMENT, a.name,
                                NoticeText.course(a.courseName) + submittedLabel(a), due, done: a.submitted, overdue: past, url: a.url,
                                assignment: a, attention: a.submitted && (a.check?.problems.isEmpty == false)))
            }
        }
        if filter == .ALL || filter == .EXAM {
            // 시험: 끝나면 목록에서 빠진다(누락이 없다). 보는 중인 시험은 "진행 중".
            for d in mine where d.isExam && !d.done && d.over > now {
                let ongoing = !(d.due > now)
                let subtitle = ["시험", d.location.nilIfBlank, d.supplies.nilIfBlank.map { "준비물: \($0)" },
                                d.reminders.isEmpty ? nil : "🔔 \(MyDeadlines.reminderLabel(d))",
                                d.memo.nilIfBlank, ongoing ? "진행 중" : nil].compactMap { $0 }.joined(separator: " · ")
                rows.append(Row("exam-\(d.id)", .EXAM, MyDeadlines.examTitle(d).removingPrefix("[시험] "), subtitle, d.due, mine: d,
                                timeText: MyDeadlines.examTimeLabel(d) + (ongoing ? "" : " · " + EtlLogic.remaining(d.due, now))))
            }
        }
        if filter == .ALL || filter == .CALENDAR {
            for e in calendar {
                if !(e.end > now) && !(e.start == e.end && e.start > now) { continue }
                let subtitle = [e.calendarName.isEmpty ? "캘린더" : e.calendarName, e.location.nilIfEmpty, e.recurring ? "🔁" : nil]
                    .compactMap { $0 }.joined(separator: " · ")
                let at = (e.allDay || e.start < now) ? max(e.start, ZoneClock.startOfDay(ZoneClock.localDate(now, zone), zone)) : e.start
                rows.append(Row(e.key, .CALENDAR, e.title, subtitle, at, calendar: e,
                                timeText: (e.allDay ? "" : NoticeText.dateLabel(e.start).substringBefore(" ") + " ") + CalendarReadLogic.timeLabel(e)))
            }
        }
        if filter == .ALL || filter == .MINE {
            // 반복 마감은 앞으로 7일의 회차를 모두 펼치고, 7일 뒤의 다음 회차 하나도 "그 이후"에 보인다.
            let until = ZoneClock.startOfDay(ZoneClock.localDate(now, zone).plusDays(7), zone)
            for d in MyDeadlines.agendaItems(mine.filter { !$0.isExam }, now, until) {
                let past = !(d.due > now)
                if past && d.done { continue }
                let subtitle = "내 마감" + (d.repeat != nil ? " · 🔁 \(MyDeadlines.repeatLabel(d))" : "") +
                    (!d.reminders.isEmpty && !d.done ? " · 🔔 \(MyDeadlines.reminderLabel(d))" : "") +
                    (d.memo.kIsBlank ? "" : " · \(d.memo)") + (d.done ? " · 완료" : "")
                rows.append(Row("mine-\(d.id)" + (d.occurrence.map { "-\($0)" } ?? ""), .MINE, d.title, subtitle, d.due,
                                done: d.done, overdue: past, mine: d))
            }
        }
        let visible = stableSorted(rows.filter { !hideDone || !$0.done || $0.attention }) { a, b in
            if a.at != b.at { return a.at < b.at }
            if a.kind != b.kind { return a.kind.rawValue < b.kind.rawValue }
            if a.title != b.title { return a.title.kLess(b.title) }
            return a.id.kLess(b.id)
        }
        let today = ZoneClock.localDate(now, zone)
        let endExclusive = ZoneClock.startOfDay(today.plusDays(7), zone)
        func groups(_ items: [Row]) -> [Group] {
            var order: [DayDate] = []
            var map: [DayDate: [Row]] = [:]
            for r in items {
                let d = ZoneClock.localDate(r.at, zone)
                if map[d] == nil { order.append(d) }
                map[d, default: []].append(r)
            }
            return order.map { Group($0, map[$0]!) }
        }
        let future = visible.filter { !$0.overdue }
        return Schedule(
            today: today,
            // 캘린더 일정은 마감이 아니라 요약에 넣지 않는다(시험은 넣는다).
            urgent: future.filter { !$0.done && $0.kind != .CALENDAR && !($0.at > now.addingTimeInterval(24 * 3600)) },
            overdue: visible.filter(\.overdue),
            upcoming: groups(future.filter { $0.at < endExclusive }),
            later: groups(future.filter { !($0.at < endExclusive) })
        )
    }

    /// ` · 제출함`, 늦었으면 ` · 제출함(늦음)`, 점검에서 문제가 나왔으면 ` · 제출함 · 확인 필요`.
    public static func submittedLabel(_ a: AssignmentItem) -> String {
        if !a.submitted { return a.online ? "" : " · 오프라인" }
        if a.check?.problems.isEmpty == false { return " · 제출함 · 확인 필요" }
        if a.check?.late == true { return " · 제출함(늦음)" }
        return " · 제출함"
    }

    private static let WD = ["월", "화", "수", "목", "금", "토", "일"]

    public static func dateLabel(_ date: DayDate) -> String { "\(date.monthValue)/\(date.dayOfMonth)(\(WD[date.dayOfWeek.rawValue - 1]))" }

    public static func dayLabel(_ date: DayDate, _ today: DayDate) -> String {
        if date == today { return "오늘 · \(dateLabel(date))" }
        if date == today.plusDays(1) { return "내일 · \(dateLabel(date))" }
        return dateLabel(date)
    }

    /// `11/23(월) 23:59 · 1일 2시간 남음`처럼 보일 시각 부분.
    public static func whenLabel(_ r: Row, _ now: Date) -> String {
        r.timeText ?? (NoticeText.dateLabel(r.at) + " · " + EtlLogic.remaining(r.at, now))
    }
}
