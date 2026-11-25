import Foundation
import ETLKit

/// 사용자가 직접 등록한 마감("내 마감"). eTL과 무관한 일정도 넣을 수 있다.
/// 폰 안에만 저장하고([MyDeadlineStore]), 알림·브리핑에서는 [MyDeadlines.toItems]로 [AssignmentItem]처럼 다룬다.
public struct MyDeadline: Hashable, Sendable {
    /// 만든 시각(epoch millis)을 쓴다. 재사용하지 않는다.
    public var id: Int64
    public var title: String
    public var due: Date
    public var memo: String
    public var done: Bool
    /// 사용자가 정한 알림 시각들. 설정의 기본 알림 시점과 별개로, 방해 금지와 무관하게 울린다.
    public var reminders: [Date]
    /// 반복 규칙. nil이면 한 번뿐인 마감. 반복이면 [due]가 첫 회차의 기준이고 회차는 그때그때 펼친다([MyDeadlines.occurrences]).
    public var `repeat`: MyRepeat?
    /// 반복 마감에서 완료로 표시한 회차의 날짜(KST).
    public var doneDates: Set<DayDate>
    /// 반복 마감에서 "이번만 지우기"로 뺀 회차의 날짜(KST).
    public var skipDates: Set<DayDate>
    /// 펼친 회차일 때 그 회차의 날짜(KST). 저장하지 않는다.
    public var occurrence: DayDate?
    /// 종류. 예전 저장값은 모두 [MyKind.DEADLINE]. 시험이면 [due]가 시작 시각이고 반복은 없다.
    public var kind: MyKind
    /// 시험 끝 시각(선택, [due]보다 뒤).
    public var end: Date?
    /// 시험 장소(선택).
    public var location: String
    /// 시험 과목(선택).
    public var course: String
    /// 시험 준비물(선택, 한 줄).
    public var supplies: String
    /// 공지 알림의 "내 일정에 등록"으로 넣었으면 그 공지 ID.
    public var sourceAnnouncementId: Int64?

    public init(_ id: Int64, _ title: String, _ due: Date, _ memo: String = "", done: Bool = false, reminders: [Date] = [],
                repeat: MyRepeat? = nil, doneDates: Set<DayDate> = [], skipDates: Set<DayDate> = [], occurrence: DayDate? = nil,
                kind: MyKind = .DEADLINE, end: Date? = nil, location: String = "", course: String = "", supplies: String = "",
                sourceAnnouncementId: Int64? = nil) {
        self.id = id
        self.title = title
        self.due = due
        self.memo = memo
        self.done = done
        self.reminders = reminders
        self.repeat = `repeat`
        self.doneDates = doneDates
        self.skipDates = skipDates
        self.occurrence = occurrence
        self.kind = kind
        self.end = end
        self.location = location
        self.course = course
        self.supplies = supplies
        self.sourceAnnouncementId = sourceAnnouncementId
    }

    public init(id: Int64, title: String, due: Date, memo: String = "", done: Bool = false, reminders: [Date] = [],
                repeat: MyRepeat? = nil, doneDates: Set<DayDate> = [], skipDates: Set<DayDate> = [], occurrence: DayDate? = nil,
                kind: MyKind = .DEADLINE, end: Date? = nil, location: String = "", course: String = "", supplies: String = "",
                sourceAnnouncementId: Int64? = nil) {
        self.init(id, title, due, memo, done: done, reminders: reminders, repeat: `repeat`, doneDates: doneDates, skipDates: skipDates,
                  occurrence: occurrence, kind: kind, end: end, location: location, course: course, supplies: supplies,
                  sourceAnnouncementId: sourceAnnouncementId)
    }

    public var isExam: Bool { kind == .EXAM }

    /// 끝난 시각: 시험은 끝 시각(없으면 시작), 마감은 마감 시각.
    public var over: Date { end ?? due }
}

/// 내 마감의 종류.
public enum MyKind: String, CaseIterable, Hashable, Sendable, Codable {
    case DEADLINE = "deadline", EXAM = "exam"

    public var id: String { rawValue }
    public var label: String { self == .DEADLINE ? "마감" : "시험" }

    public static func parse(_ s: String?) -> MyKind? {
        guard let t = s?.kTrim.lowercased() else { return nil }
        return allCases.first { $0.id == t }
    }
}

/// 반복 규칙. 매주·격주는 [weekdays](비면 시작일의 요일), 매월은 시작일과 같은 날짜(없는 달은 그달 말일).
/// 격주는 시작일이 든 주(월요일 시작)부터 두 주마다. [until]은 그날(KST)까지 포함, nil이면 끝없음.
/// [count]는 첫 회차부터 센 총 회차 수(완료·건너뛴 회차 포함). [until]과 함께 있으면 먼저 닿는 쪽에서 끝난다.
public struct MyRepeat: Hashable, Sendable {
    public var freq: Freq
    public var weekdays: Set<Weekday>
    public var until: DayDate?
    public var count: Int?

    public init(_ freq: Freq, weekdays: Set<Weekday> = [], until: DayDate? = nil, count: Int? = nil) {
        self.freq = freq
        self.weekdays = weekdays
        self.until = until
        self.count = count
    }

    public enum Freq: String, CaseIterable, Hashable, Sendable, Codable {
        case DAILY = "daily", WEEKLY = "weekly", BIWEEKLY = "biweekly", MONTHLY = "monthly"

        public var id: String { rawValue }
        public var label: String {
            switch self {
            case .DAILY: return "매일"
            case .WEEKLY: return "매주"
            case .BIWEEKLY: return "격주"
            case .MONTHLY: return "매월"
            }
        }

        public static func parse(_ s: String?) -> Freq? {
            guard let t = s?.kTrim.lowercased() else { return nil }
            return allCases.first { $0.id == t }
        }
    }
}

public enum MyDeadlines {
    /// 알림 키(`deadline-m:{courseId}:{id}:{분}`)에서 eTL 강의 ID와 겹치지 않는 값.
    public static let COURSE_ID: Int64 = -1
    public static let COURSE_NAME = "내 마감"
    public static let TITLE_MAX = 60
    public static let MEMO_MAX = 200
    public static let MAX_ITEMS = 100
    public static let MAX_REMINDERS = 5

    /// 반복 마감의 지난 회차를 "누락"으로 볼 기간(일).
    public static let MISSED_LOOKBACK_DAYS = 7
    /// 완료·건너뛴 회차 날짜를 이보다 오래되면 지운다.
    private static let KEEP_DATES_DAYS = 60
    /// 다음 회차를 찾을 때 볼 최대 날수.
    private static let SEARCH_DAYS = 400
    /// 직접 정한 알림을 찾을 때 볼 회차 범위(초).
    private static let REMINDER_HORIZON: TimeInterval = 31 * 86_400

    // ---- 반복 ----------------------------------------------------------------

    private static func startDate(_ d: MyDeadline) -> DayDate { DayDate(d.due) }

    /// 매주·격주의 요일(비어 있으면 시작일의 요일).
    public static func weekdaysOf(_ d: MyDeadline) -> Set<Weekday> {
        if let w = d.repeat?.weekdays, !w.isEmpty { return w }
        return [startDate(d).dayOfWeek]
    }

    public static let COUNT_MAX = 999

    /// 반복 끝은 날짜(until)와 횟수(count) 중 하나만. 둘 다 주면 오류 문구.
    public static func repeatEndConflict(hasUntil: Bool, hasCount: Bool) -> String? {
        hasUntil && hasCount ? "반복 끝은 until(날짜)과 count(횟수) 중 하나만 줄 수 있습니다" : nil
    }

    /// [date](KST)가 이 마감의 회차인가. 건너뛴 회차는 아니다.
    public static func matches(_ d: MyDeadline, _ date: DayDate) -> Bool {
        let start = startDate(d)
        guard let r = d.repeat else { return date == start }
        if date < start || d.skipDates.contains(date) { return false }
        if let last = lastDate(d), date > last { return false }
        return ruleMatches(d, r, start, date)
    }

    /// 끝(until·count)과 건너뛰기를 보지 않은 규칙만의 판정.
    private static func ruleMatches(_ d: MyDeadline, _ r: MyRepeat, _ start: DayDate, _ date: DayDate) -> Bool {
        if date < start { return false }
        switch r.freq {
        case .DAILY: return true
        case .WEEKLY: return weekdaysOf(d).contains(date.dayOfWeek)
        case .BIWEEKLY:
            return weekdaysOf(d).contains(date.dayOfWeek) && (monday(start).daysUntil(monday(date)) / 7) % 2 == 0
        case .MONTHLY:
            // 31일 시작이면 30일까지인 달은 30일, 2월은 28·29일.
            return date.dayOfMonth == min(start.dayOfMonth, date.lengthOfMonth)
        }
    }

    private static func monday(_ date: DayDate) -> DayDate { date.previousOrSame(.monday) }

    /// [count]번째 회차 날짜 캐시(규칙·시작 날짜별).
    private struct CountKey: Hashable { let r: MyRepeat; let start: DayDate }
    private final class CountCache: @unchecked Sendable {
        let lock = NSLock()
        var map: [CountKey: DayDate] = [:]
        var order: [CountKey] = []
    }
    private static let countCache = CountCache()

    /// 첫 회차부터 센 [MyRepeat.count]번째 회차 날짜(건너뛴 회차도 센다, until은 보지 않는다). count가 없으면 nil.
    public static func countLastDate(_ d: MyDeadline) -> DayDate? {
        guard let r = d.repeat, let n = r.count else { return nil }
        let start = startDate(d)
        var keyRepeat = r
        keyRepeat.until = nil
        let key = CountKey(r: keyRepeat, start: start)
        if let hit = countCache.lock.withLock({ countCache.map[key] }) { return hit }
        var date = start
        var seen = 0
        var last = start
        let limit = start.plusDays(n * 31 + 31)
        while !(date > limit) {
            if ruleMatches(d, r, start, date) {
                seen += 1
                last = date
                if seen >= n { break }
            }
            date = date.plusDays(1)
        }
        countCache.lock.withLock {
            if countCache.map[key] == nil { countCache.order.append(key) }
            countCache.map[key] = last
            if countCache.order.count > 64 {
                let old = countCache.order.removeFirst()
                countCache.map[old] = nil
            }
        }
        return last
    }

    /// 첫 회차부터 [through]까지(포함)의 회차 수(건너뛴 회차도 센다, 끝은 보지 않는다).
    public static func occurrenceCount(_ d: MyDeadline, _ through: DayDate) -> Int {
        guard let r = d.repeat else { return through < startDate(d) ? 0 : 1 }
        let start = startDate(d)
        var date = start
        var n = 0
        while !(date > through) {
            if ruleMatches(d, r, start, date) { n += 1 }
            date = date.plusDays(1)
        }
        return n
    }

    /// 마지막 회차가 될 수 있는 날짜: until과 count 중 먼저 닿는 쪽. 둘 다 없으면 nil(끝없음).
    public static func lastDate(_ d: MyDeadline) -> DayDate? {
        guard let r = d.repeat else { return startDate(d) }
        return [r.until, countLastDate(d)].compactMap { $0 }.min()
    }

    /// 실제 마지막 회차(건너뛴 회차 제외). 끝이 없으면 nil.
    public static func lastOccurrence(_ d: MyDeadline) -> MyDeadline? {
        if d.repeat == nil { return d }
        guard let last = lastDate(d) else { return nil }
        var date = last
        let start = startDate(d)
        while !(date < start) {
            if matches(d, date) { return occurrenceAt(d, date) }
            date = date.minusDays(1)
        }
        return nil
    }

    /// 반복 끝 안내: `마지막 마감: 2027/01/06(수) 23:59 · 총 12번`, 끝이 없으면 `끝 없이 반복`.
    public static func endSummary(_ d: MyDeadline) -> String {
        guard let r = d.repeat else { return "" }
        if r.until == nil && r.count == nil { return "끝 없이 반복" }
        let start = startDate(d)
        guard var lastRule = lastDate(d) else { return "끝 없이 반복" }
        while !(lastRule < start) && !ruleMatches(d, r, start, lastRule) { lastRule = lastRule.minusDays(1) }
        if lastRule < start { return "반복 기간 안에 해당하는 날이 없어요" }
        let total = occurrenceCount(d, lastRule)
        let p = EtlLogic.kstParts(occurrenceAt(d, lastRule).due)
        return String(format: "마지막 마감: %d/%02d/%02d(%@) %02d:%02d · 총 %d번", p.day.year, p.day.month, p.day.day,
                      WEEKDAY_KO[p.day.dayOfWeek.rawValue - 1], p.hour, p.minute, total)
    }

    /// 반복 마감의 [date] 회차. 마감 시각은 같고, 알림 시각은 마감과의 간격을 유지한다.
    public static func occurrenceAt(_ d: MyDeadline, _ date: DayDate) -> MyDeadline {
        let timeOfDay = d.due.timeIntervalSince(startDate(d).startOfDay)
        let due = date.startOfDay.addingTimeInterval(timeOfDay)
        let shift = due.timeIntervalSince(d.due)
        var o = d
        o.due = due
        o.done = d.doneDates.contains(date)
        o.reminders = d.reminders.map { $0.addingTimeInterval(shift) }
        o.occurrence = date
        return o
    }

    /// 마감이 [from, to)에 드는 회차(완료 포함, 마감순). 반복 없는 마감은 그 자신이 들면 그것 하나.
    public static func occurrences(_ d: MyDeadline, _ from: Date, _ to: Date) -> [MyDeadline] {
        if d.repeat == nil { return !(d.due < from) && d.due < to ? [d] : [] }
        if !(from < to) { return [] }
        var out: [MyDeadline] = []
        var date = max(startDate(d), DayDate(from).minusDays(1))
        let last = min(DayDate(to).plusDays(1), date.plusDays(SEARCH_DAYS))
        while !(date > last) {
            if matches(d, date) {
                let o = occurrenceAt(d, date)
                if !(o.due < from) && o.due < to { out.append(o) }
            }
            date = date.plusDays(1)
        }
        return out
    }

    /// [after]보다 뒤에 마감인 첫 미완료 회차. 반복 없는 마감은 미완료이고 마감 전이면 그 자신.
    public static func nextOccurrence(_ d: MyDeadline, _ after: Date) -> MyDeadline? {
        if d.repeat == nil { return !d.done && d.due > after ? d : nil }
        var date = max(startDate(d), DayDate(after).minusDays(1))
        let last = date.plusDays(SEARCH_DAYS)
        let end = lastDate(d)
        while !(date > last) && (end == nil || !(date > end!)) {
            if matches(d, date) {
                let o = occurrenceAt(d, date)
                if !o.done && o.due > after { return o }
            }
            date = date.plusDays(1)
        }
        return nil
    }

    /// 첫 회차(완료 여부 무관). 규칙에 맞는 날이 없으면 nil.
    public static func firstOccurrence(_ d: MyDeadline) -> MyDeadline? {
        if d.repeat == nil { return d }
        return occurrences(d, d.due.addingTimeInterval(-86_400), d.due.addingTimeInterval(Double(SEARCH_DAYS) * 86_400)).first
    }

    /// 반복 마감의 가장 최근 지난 회차가 미완료면 그 회차([MISSED_LOOKBACK_DAYS]일 이내만).
    /// 반복 없는 마감은 지났고 미완료면 그 자신.
    public static func lastMissed(_ d: MyDeadline, _ now: Date) -> MyDeadline? {
        if d.repeat == nil { return !d.done && !(d.due > now) ? d : nil }
        guard let last = occurrences(d, now.addingTimeInterval(-Double(MISSED_LOOKBACK_DAYS) * 86_400), now.addingTimeInterval(0.001)).last else { return nil }
        return last.done ? nil : last
    }

    /// 알림·브리핑·위젯용 목록. 반복 마감은 최근 놓친 회차와 다음 미완료 회차만 넣는다.
    public static func active(_ list: [MyDeadline], _ now: Date) -> [MyDeadline] {
        list.flatMap { d -> [MyDeadline] in
            if d.repeat == nil { return [d] }
            return [lastMissed(d, now), nextOccurrence(d, now)].compactMap { $0 }
        }
    }

    /// "내 일정" 화면용: [until] 전까지의 회차를 모두(완료 포함), 그 뒤의 다음 미완료 회차 하나, 그리고 최근 놓친 회차.
    public static func agendaItems(_ list: [MyDeadline], _ now: Date, _ until: Date) -> [MyDeadline] {
        list.flatMap { d -> [MyDeadline] in
            if d.repeat == nil { return [d] }
            let upcoming = occurrences(d, now.addingTimeInterval(0.001), until)
            let after = nextOccurrence(d, max(now, until.addingTimeInterval(-0.001)))
            return distinctBy([lastMissed(d, now)].compactMap { $0 } + upcoming + [after].compactMap { $0 }) { $0.occurrence }
        }
    }

    /// 알림·브리핑에서 쓰는 과제 ID. 반복 회차는 회차마다 다르다(`id * 100000 + epochDay`).
    public static func itemId(_ d: MyDeadline) -> Int64 {
        guard let o = d.occurrence else { return d.id }
        return d.id &* 100_000 &+ o.epochDay
    }

    /// [itemId]로 저장된 내 마감을 찾는다(원래 ID 또는 반복 회차 ID).
    public static func findByItemId(_ list: [MyDeadline], _ itemId: Int64) -> MyDeadline? {
        list.first { $0.id == itemId } ?? list.first { $0.repeat != nil && $0.id == itemId / 100_000 }
    }

    /// 완료 표시/취소. 반복 마감은 [occurrence] 회차만 바꾼다. [d]는 저장된 원래 항목.
    public static func setDone(_ d: MyDeadline, _ occurrence: DayDate?, _ done: Bool, _ now: Date) -> MyDeadline {
        var c = d
        if d.repeat == nil || occurrence == nil {
            c.done = done
        } else {
            var dates = d.doneDates
            if done { dates.insert(occurrence!) } else { dates.remove(occurrence!) }
            c.doneDates = prune(dates, now)
        }
        return c
    }

    /// 반복 마감에서 한 회차만 지운다.
    public static func skip(_ d: MyDeadline, _ occurrence: DayDate, _ now: Date) -> MyDeadline {
        var c = d
        c.skipDates = prune(d.skipDates.union([occurrence]), now)
        c.doneDates = d.doneDates.subtracting([occurrence])
        return c
    }

    private static func prune(_ dates: Set<DayDate>, _ now: Date) -> Set<DayDate> {
        let keepFrom = DayDate(now).minusDays(KEEP_DATES_DAYS)
        return dates.filter { !($0 < keepFrom) }
    }

    /// `매주 월·수 · 12/20까지`. 반복이 없으면 빈 문자열.
    public static func repeatLabel(_ d: MyDeadline) -> String {
        guard let r = d.repeat else { return "" }
        let base: String
        switch r.freq {
        case .DAILY: base = "매일"
        case .WEEKLY, .BIWEEKLY: base = "\(r.freq.label) " + weekdaysOf(d).sorted().map { WEEKDAY_KO[$0.rawValue - 1] }.joined(separator: "·")
        case .MONTHLY: base = "매월 \(startDate(d).dayOfMonth)일"
        }
        return base + (r.until.map { " · \($0.monthValue)/\($0.dayOfMonth)까지" } ?? "") + (r.count.map { " · \($0)번" } ?? "")
    }

    /// 반복 확인: 끝 날짜는 시작 날짜 뒤, 회차가 하나는 있어야 한다. 문제가 없으면 nil.
    public static func validateRepeat(_ d: MyDeadline) -> String? {
        guard let r = d.repeat else { return nil }
        if let u = r.until, u < startDate(d) { return "반복 끝 날짜는 시작 날짜보다 뒤여야 해요" }
        if let c = r.count, c < 1 || c > COUNT_MAX { return "반복 횟수는 1~\(COUNT_MAX)번이어야 해요" }
        if firstOccurrence(d) == nil { return "반복 기간 안에 해당하는 날이 없어요" }
        return nil
    }

    private static let WEEKDAY_KO = ["월", "화", "수", "목", "금", "토", "일"]
    private static let WEEKDAY_IDS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]

    public static func weekdayId(_ d: Weekday) -> String { WEEKDAY_IDS[d.rawValue - 1] }

    /// "mon"·"monday"·"월"·"월요일" → 요일. 못 읽으면 nil.
    public static func parseWeekday(_ s: String?) -> Weekday? {
        guard let t = s?.kTrim.lowercased(), !t.isEmpty else { return nil }
        if let i = WEEKDAY_IDS.firstIndex(where: { t.hasPrefix($0) }) { return Weekday.of(i + 1) }
        let first = String(t.first!)
        guard let i = WEEKDAY_KO.firstIndex(of: first) else { return nil }
        return (t.count == 1 || t == "\(first)요일") ? Weekday.of(i + 1) : nil
    }

    /// 알림·브리핑용으로 바꾼다. 완료한 항목은 "제출됨", 지난 미완료 항목은 "누락"으로 본다. 반복 마감은 [active] 회차만.
    /// 시험은 넣지 않는다(시험 알림은 [reminderNotices]와 브리핑의 시험 섹션으로 따로 보낸다).
    public static func toItems(_ list: [MyDeadline], _ now: Date) -> [AssignmentItem] {
        active(list.filter { !$0.isExam }, now).map { d in
            AssignmentItem(COURSE_ID, COURSE_NAME, itemId(d), d.title, d.due,
                           true, // 알림 문구에 "(오프라인)"을 붙이지 않는다
                           nil, nil, d.done, !d.done && !(d.due > now))
        }
    }

    /// eTL 조회 결과에 내 마감을 더한다(마감순 정렬).
    public static func merge(_ snapshot: Snapshot, _ mine: [MyDeadline], _ now: Date) -> Snapshot {
        if mine.isEmpty { return snapshot }
        var s = snapshot
        s.assignments = SnapshotFetcher.sortAssignments(snapshot.assignments + toItems(mine, now))
        return s
    }

    public static let LOCATION_MAX = 40
    public static let COURSE_MAX = 60
    public static let SUPPLIES_MAX = 100

    /// 시험 확인: 끝은 시작보다 뒤, 장소·과목·준비물 길이, 반복 없음. 마감이면 nil.
    public static func validateExam(_ d: MyDeadline) -> String? {
        if !d.isExam { return nil }
        if let e = d.end, !(e > d.due) { return "끝 시각은 시작 시각보다 뒤여야 해요" }
        if let e = d.end, e.timeIntervalSince(d.due) > 24 * 3600 { return "시험 시간은 24시간을 넘을 수 없어요" }
        if d.location.kLength > LOCATION_MAX { return "장소는 \(LOCATION_MAX)자까지 쓸 수 있어요" }
        if d.course.kLength > COURSE_MAX { return "과목은 \(COURSE_MAX)자까지 쓸 수 있어요" }
        if d.supplies.kLength > SUPPLIES_MAX { return "준비물은 \(SUPPLIES_MAX)자까지 쓸 수 있어요" }
        if d.repeat != nil { return "시험은 반복할 수 없어요" }
        return nil
    }

    /// 준비물 정리: 줄바꿈·쉼표·가운뎃점으로 나눈 항목을 ", "로 잇는다(빈 항목·중복 제거).
    public static func normalizeSupplies(_ s: String?) -> String {
        let seps: Set<Character> = ["\n", ",", "，", "·", "/", ";"]
        let parts = (s ?? "").split(omittingEmptySubsequences: false, whereSeparator: { seps.contains($0) })
            .map { NoticeText.clean(String($0)).kTrim }.filter { !$0.isEmpty }
        return distinct(parts).joined(separator: ", ").kTake(SUPPLIES_MAX)
    }

    /// 시험 기본 알림(직접 정하지 않았을 때): 전날 20:00, 시작 2시간 전. 이미 지난 시각은 뺀다.
    public static func defaultExamReminders(_ start: Date, _ now: Date) -> [Date] {
        let eve = DayDate(start).minusDays(1).atTime(20, 0)
        return normalizeReminders([eve, start.addingTimeInterval(-2 * 3600)]).filter { $0 > now && $0 < start }
    }

    /// 시험 제목: `[시험] 자료구조 중간고사`(과목이 없거나 제목에 이미 있으면 제목만).
    public static func examTitle(_ d: MyDeadline) -> String {
        let course = NoticeText.clean(d.course)
        let title = NoticeText.clean(d.title)
        return "[시험] " + (course.isEmpty || title.contains(course) ? title : "\(course) \(title)")
    }

    /// `11/25(수) 10:00~12:00`(끝이 없으면 시작만, 끝이 다른 날이면 날짜도).
    public static func examTimeLabel(_ d: MyDeadline) -> String {
        guard let e = d.end else { return NoticeText.dateLabel(d.due) }
        let ez = EtlLogic.kstParts(e)
        let sameDay = ez.day == DayDate(d.due)
        return NoticeText.dateLabel(d.due) + "~" + (sameDay ? String(format: "%02d:%02d", ez.hour, ez.minute) : NoticeText.dateLabel(e))
    }

    /// 브리핑의 시험 줄: 시작이 [days]일 안이거나 지금 보고 있는 시험. 오늘·내일은 앞에 [오늘]/[내일].
    public static func examLines(_ list: [MyDeadline], _ now: Date, days: Int = 7) -> [String] {
        let today = DayDate(now)
        let picked = list.filter { $0.isExam && !$0.done && $0.over > now && $0.due < now.addingTimeInterval(Double(days) * 86_400) }
        return stableSorted(picked) { a, b in a.due != b.due ? a.due < b.due : a.id < b.id }.map { d in
            let day = DayDate(d.due)
            let tag = day == today ? "[오늘] " : (day == today.plusDays(1) ? "[내일] " : "")
            return "• \(tag)\(examTimeLabel(d)) · \(NoticeText.cut(examTitle(d), 60))" +
                (d.location.nilIfBlank.map { " · \(NoticeText.clean($0))" } ?? "") +
                (d.supplies.nilIfBlank.flatMap { tag.isEmpty ? nil : " · 준비물: \(NoticeText.cut(NoticeText.clean($0), 40))" } ?? "")
        }
    }

    /// 입력 확인. 문제가 없으면 nil, 있으면 사용자에게 보일 문구.
    public static func validate(_ title: String, _ memo: String, _ countWithoutThis: Int) -> String? {
        if title.kIsBlank { return "제목을 입력하세요" }
        if title.kTrim.kLength > TITLE_MAX { return "제목은 \(TITLE_MAX)자까지 쓸 수 있어요" }
        if memo.kTrim.kLength > MEMO_MAX { return "메모는 \(MEMO_MAX)자까지 쓸 수 있어요" }
        if countWithoutThis >= MAX_ITEMS { return "내 마감은 \(MAX_ITEMS)개까지 등록할 수 있어요. 지난 항목을 지워 주세요" }
        return nil
    }

    /// 알림 시각 확인: 마감보다 앞이어야 하고 최대 [MAX_REMINDERS]개.
    public static func validateReminders(_ reminders: [Date], _ due: Date) -> String? {
        if reminders.count > MAX_REMINDERS { return "알림 시각은 \(MAX_REMINDERS)개까지 정할 수 있어요" }
        if reminders.contains(where: { !($0 < due) }) { return "알림 시각은 마감보다 앞이어야 해요" }
        return nil
    }

    /// 정리: 분 단위로 맞추고 중복 제거, 시각순.
    public static func normalizeReminders(_ reminders: [Date]) -> [Date] {
        distinct(reminders.map { Date(timeIntervalSince1970: TimeInterval($0.epochSecond / 60 * 60)) }).sorted()
    }

    private static let HM = KRegex("^(\\d{1,2}):(\\d{2})$")

    /// 알림 시각 인자: "YYYY-MM-DD HH:MM", 또는 "HH:MM"(마감 날짜의 그 시각; 마감보다 늦으면 전날). 못 읽으면 nil.
    public static func parseReminder(_ s: String?, _ due: Date) -> Date? {
        guard let t = s?.kTrim else { return nil }
        if t.kLength > 10, let v = parseDue(t) { return v }
        guard let m = HM.find(t), let h = Int(m.groupValues[1]), let mi = Int(m.groupValues[2]),
              (0...23).contains(h), (0...59).contains(mi) else { return nil }
        let at = DayDate(due).atTime(h, mi)
        return at < due ? at : at.addingTimeInterval(-86_400)
    }

    public static func reminderKey(_ d: MyDeadline, _ at: Date) -> String { "mine-remind:\(d.id):\(at.epochSecond / 60)" }

    /// 직접 정한 알림을 볼 회차: 반복 없는 마감은 그대로, 반복 마감은 앞으로 [REMINDER_HORIZON] 안의 회차.
    private static func reminderCandidates(_ list: [MyDeadline], _ now: Date) -> [MyDeadline] {
        list.flatMap { d -> [MyDeadline] in
            if d.reminders.isEmpty { return [] }
            if d.repeat == nil { return [d] }
            return occurrences(d, now.addingTimeInterval(0.001), now.addingTimeInterval(REMINDER_HORIZON))
        }
    }

    /// 지금 울릴 알림: 시각이 지났고 마감 전이며 아직 안 보낸 것. 반복 마감은 회차마다.
    /// 시험은 앞에 정보(시각·장소·준비물), 뒤에 응원 문구([ExamCheer], [casual]이면 반말)를 붙인다.
    public static func reminderNotices(_ list: [MyDeadline], _ now: Date, _ fired: Set<String>, casual: Bool = false) -> [Notice] {
        reminderCandidates(list, now).flatMap { d -> [Notice] in
            if d.done || !(d.due > now) { return [] }
            let due = d.reminders.filter { !($0 > now) && !fired.contains(reminderKey(d, $0)) }
            if due.isEmpty { return [] }
            if d.isExam {
                var n = examReminder(d, due.max()!, now, casual)
                n.firedKeys = due.map { reminderKey(d, $0) }
                return [n]
            }
            return [Notice(.DEADLINE, NoticeText.cut("알림 · \(NoticeText.clean(d.title))", NoticeText.TITLE_MAX),
                           NoticeText.cut("마감 \(NoticeText.dateLabel(d.due)) (\(EtlLogic.remaining(d.due, now)))" +
                                          (d.memo.kIsBlank ? "" : " · \(NoticeText.clean(d.memo))"), NoticeText.LINE_MAX),
                           tag: "mine-remind:\(itemId(d))", firedKeys: due.map { reminderKey(d, $0) }, agenda: true, mineItemId: itemId(d))]
        }
    }

    /// 다음 알림 시각(없으면 nil). 시험 끝 시각(수고했어요 알림)도 넣는다.
    public static func nextReminderAt(_ list: [MyDeadline], _ now: Date, _ fired: Set<String>) -> Date? {
        let reminders = reminderCandidates(list, now).filter { !$0.done && $0.due > now }
            .flatMap { d in d.reminders.filter { $0 > now && $0 < d.due && !fired.contains(reminderKey(d, $0)) } }
        let ends = list.compactMap { d -> Date? in
            guard let e = d.end, d.isExam, e > now, !fired.contains(examDoneKey(d)) else { return nil }
            return e
        }
        return (reminders + ends).min()
    }

    /// 시험 알림 본문의 정보 부분: `11/25(수) 10:00~12:00 (1일 2시간 남음) · 301동 118호 · 준비물: 공학용 계산기, 신분증`.
    public static func examInfo(_ d: MyDeadline, _ now: Date) -> String {
        examTimeLabel(d) + " (\(EtlLogic.remaining(d.due, now)))" +
            (d.location.nilIfBlank.map { " · \(NoticeText.clean($0))" } ?? "") +
            (d.supplies.nilIfBlank.map { " · 준비물: \(NoticeText.clean($0))" } ?? "")
    }

    /// 시험 알림: 제목 `시험 · 중간고사 · 자료구조`, 본문 앞은 시각·장소·준비물, 뒤는 응원 문구.
    public static func examReminder(_ d: MyDeadline, _ at: Date, _ now: Date, _ casual: Bool) -> Notice {
        let info = examInfo(d, now)
        let cheer = ExamCheer.message(ExamCheer.phaseOf(at, d.due), d.id, at, d.course, d.due, d.location, d.supplies, casual: casual)
        let c = NoticeText.clean(d.course)
        let course: String? = (!c.isEmpty && !d.title.contains(c)) ? c : nil
        return Notice(.DEADLINE, NoticeText.cut("시험 · \(NoticeText.clean(d.title))" + (course.map { " · \($0)" } ?? ""), NoticeText.TITLE_MAX),
                      NoticeText.cut("\(info) · \(cheer)", NoticeText.LINE_MAX), bigText: "\(info)\n\(cheer)",
                      tag: "mine-remind:\(itemId(d))", agenda: true, mineItemId: itemId(d))
    }

    public static func examDoneKey(_ d: MyDeadline) -> String { "exam-done:\(d.id)" }

    /// 수고했어요 알림을 보낼 수 있는 기간(끝난 뒤, 초).
    public static let EXAM_DONE_WINDOW: TimeInterval = 3 * 3600

    /// 끝 시각이 있는 시험이 방금 끝났으면 "수고했어요" 알림. [enabled]가 꺼져 있거나 방해 금지 중([quiet])이면 보내지 않고
    /// 다시 보내지 않도록 기록할 키만 돌려준다(`.1`).
    public static func examDoneNotices(_ list: [MyDeadline], _ now: Date, _ fired: Set<String>, enabled: Bool, quiet: Bool, casual: Bool) -> ([Notice], [String]) {
        let due = list.filter { d in
            guard d.isExam, !d.done, let e = d.end else { return false }
            return !(e > now) && now < e.addingTimeInterval(EXAM_DONE_WINDOW) && !fired.contains(examDoneKey(d))
        }
        if due.isEmpty { return ([], []) }
        if !enabled || quiet { return ([], due.map(examDoneKey)) }
        return (due.map { d in
            let cheer = ExamCheer.message(.AFTER, d.id, d.end!, d.course, d.due, d.location, d.supplies, casual: casual)
            return Notice(.DEADLINE, NoticeText.cut((casual ? "수고했어 · " : "수고했어요 · ") + examTitle(d).removingPrefix("[시험] "), NoticeText.TITLE_MAX),
                          NoticeText.cut(cheer, NoticeText.LINE_MAX), bigText: cheer, tag: "exam-done:\(d.id)",
                          firedKeys: [examDoneKey(d)], agenda: true, mineItemId: d.id)
        }, [])
    }

    public static func reminderLabel(_ d: MyDeadline) -> String {
        d.reminders.map { r in
            let z = EtlLogic.kstParts(r)
            return z.day == DayDate(d.due) ? String(format: "%02d:%02d", z.hour, z.minute) : NoticeText.dateLabel(r)
        }.joined(separator: ", ")
    }

    /// 화면 순서: 미완료(마감 가까운 순) → 완료(최근 마감 순).
    public static func sorted(_ list: [MyDeadline]) -> [MyDeadline] {
        stableSorted(list.filter { !$0.done }) { a, b in a.due != b.due ? a.due < b.due : a.id < b.id } +
            stableSorted(list.filter(\.done)) { a, b in a.due != b.due ? a.due > b.due : a.id < b.id }
    }

    /// 비서 도구 `my_deadlines`의 결과(읽기 전용). [window]가 nil이면 기간 제한 없음.
    /// 지난 미완료 항목은 기간과 관계없이 넣는다. [includeDone]이 false면 완료 항목을 뺀다.
    public static func toolResult(_ list: [MyDeadline], _ now: Date, _ window: AssignmentQuery.Window?, includeDone: Bool) -> JSONObject {
        let rows = JSONArray()
        let expanded = list.flatMap { d -> [MyDeadline] in
            if d.repeat == nil { return [d] }
            guard let window else { return [lastMissed(d, now), nextOccurrence(d, now)].compactMap { $0 } }
            return distinctBy([lastMissed(d, now)].compactMap { $0 } + occurrences(d, window.start, window.endExclusive)) { $0.occurrence }
        }
        let filtered = expanded.filter { d in
            // 끝난 시험은 "지난 미완료"가 아니다.
            if d.isExam {
                return (includeDone || (!d.done && d.over > now)) && (window == nil || window!.contains(d.due) || (!(d.due > now) && d.over > now))
            }
            let overdue = !d.done && !(d.due > now)
            return (includeDone || !d.done) && (window == nil || overdue || window!.contains(d.due))
        }
        for d in stableSorted(filtered, by: { a, b in a.due != b.due ? a.due < b.due : a.id < b.id }) {
            let o = JSONObject()
                .put("id", d.id)
                .put("kind", d.kind.id)
                .put("title", d.title)
                .put("due_kst", EtlLogic.fmtKst(d.due))
                .put("remaining", EtlLogic.remaining(d.due, now))
            if d.isExam {
                o.put("start_kst", EtlLogic.fmtKst(d.due))
                if let e = d.end { o.put("end_kst", EtlLogic.fmtKst(e)) }
                if !d.location.kIsBlank { o.put("location", d.location) }
                if !d.course.kIsBlank { o.put("course", d.course) }
                if !d.supplies.kIsBlank { o.put("supplies", d.supplies) }
            }
            o.put("done", d.done)
            o.put("overdue", !d.isExam && !d.done && !(d.due > now))
            if !d.reminders.isEmpty { o.put("reminders_kst", JSONArray(d.reminders.map { EtlLogic.fmtKst($0) })) }
            if !d.memo.kIsBlank { o.put("memo", d.memo) }
            if d.repeat != nil {
                o.put("occurrence", d.occurrence?.description)
                o.put("repeat", repeatJson(d).put("label", repeatLabel(d)))
            }
            rows.put(o)
        }
        return JSONObject()
            .put("now_kst", EtlLogic.fmtKst(now))
            .put("source", "사용자가 앱의 '내 마감'에 직접 등록한 마감과 시험(kind=exam, due_kst=시작 시각). eTL 과제 아님")
            .put("count", rows.length())
            .put("items", rows)
    }

    /// 비서 도구 결과용 반복 규칙: `{"freq":"weekly","weekdays":["mon","wed"],"until":"2026-12-20"}`.
    public static func repeatJson(_ d: MyDeadline) -> JSONObject {
        guard let r = d.repeat else { return JSONObject().put("freq", "none") }
        let o = JSONObject().put("freq", r.freq.id)
        if r.freq == .WEEKLY || r.freq == .BIWEEKLY { o.put("weekdays", JSONArray(weekdaysOf(d).sorted().map { weekdayId($0) })) }
        if let u = r.until { o.put("until", u.description) }
        if let c = r.count { o.put("count", c) }
        return o
    }

    private static let DUE = KRegex("^(\\d{4}-\\d{2}-\\d{2})(?:\\s+(\\d{1,2}):(\\d{2})(?::\\d{2})?)?$")

    /// "YYYY-MM-DD HH:MM"(또는 T 구분, 초 허용)을 KST 시각으로. 날짜만 오면 그날 23:59. 못 읽으면 nil.
    public static func parseDue(_ s: String?) -> Date? {
        guard let t = s?.kTrim.replacingOccurrences(of: "T", with: " ") else { return nil }
        guard let m = DUE.find(t), let date = DayDate.parse(m.groupValues[1]) else { return nil }
        if m.groupValues[2].isEmpty { return date.atTime(23, 59) }
        guard let h = Int(m.groupValues[2]), let mi = Int(m.groupValues[3]), (0...23).contains(h), (0...59).contains(mi) else { return nil }
        return date.atTime(h, mi)
    }

    /// 시험 끝: "YYYY-MM-DD HH:MM" 또는 [start] 날짜(KST)의 "HH:MM". 날짜만은 받지 않는다. 못 읽으면 nil.
    public static func parseEnd(_ s: String?, _ start: Date) -> Date? {
        guard let t = s?.kTrim else { return nil }
        if t.kLength > 10 { return parseDue(t) }
        guard let m = HM.find(t), let h = Int(m.groupValues[1]), let mi = Int(m.groupValues[2]),
              (0...23).contains(h), (0...59).contains(mi) else { return nil }
        return DayDate(start).atTime(h, mi)
    }

    /// 채팅에 보일 한 줄: `장학금 서류 · 11/23(월) 23:59`.
    public static func line(_ d: MyDeadline) -> String {
        if d.isExam {
            return examTitle(d) + " · " + examTimeLabel(d) + (d.location.nilIfBlank.map { " · \($0)" } ?? "") +
                (d.supplies.nilIfBlank.map { " · 준비물: \($0)" } ?? "")
        }
        return "\(d.title) · \(NoticeText.dateLabel(d.due))" + (d.repeat != nil ? " · 🔁 \(repeatLabel(d))" : "")
    }

    public static func toJson(_ list: [MyDeadline]) -> String {
        let arr = JSONArray()
        for d in list {
            let o = JSONObject()
                .put("id", d.id)
                .put("title", d.title)
                .put("due", d.due.epochMilli)
                .put("memo", d.memo)
                .put("done", d.done)
                .put("reminders", JSONArray(d.reminders.map { $0.epochMilli }))
            if let s = d.sourceAnnouncementId { o.put("srcAnn", s) }
            if d.isExam {
                o.put("kind", d.kind.id)
                if let e = d.end { o.put("end", e.epochMilli) }
                if !d.location.isEmpty { o.put("location", d.location) }
                if !d.course.isEmpty { o.put("course", d.course) }
                if !d.supplies.isEmpty { o.put("supplies", d.supplies) }
            }
            if let r = d.repeat {
                let ro = JSONObject().put("freq", r.freq.id).put("weekdays", JSONArray(r.weekdays.sorted().map { Int64($0.rawValue) }))
                if let u = r.until { ro.put("until", u.description) }
                if let c = r.count { ro.put("count", c) }
                o.put("repeat", ro)
                o.put("doneDates", JSONArray(d.doneDates.sorted().map(\.description)))
                o.put("skipDates", JSONArray(d.skipDates.sorted().map(\.description)))
            }
            arr.put(o)
        }
        return arr.toString()
    }

    /// 깨진 항목은 건너뛴다. 전체가 깨져 있으면 빈 목록.
    public static func fromJson(_ s: String?) -> [MyDeadline] {
        guard let s, !s.isEmpty, let arr = try? JSONArray(s) else { return [] }
        var out: [MyDeadline] = []
        for i in 0..<arr.length() {
            guard let o = arr.optJSONObject(i) else { continue }
            let id = o.optLong("id", 0)
            let title = o.optString("title", "").kTrim
            if id == 0 || title.isEmpty || !o.has("due") { continue }
            let kind = MyKind.parse(o.optString("kind", "")) ?? .DEADLINE
            let exam = kind == .EXAM
            let src: Int64? = (o.has("srcAnn") && !o.isNull("srcAnn")) ? { let v = o.optLong("srcAnn"); return v > 0 ? v : nil }() : nil
            var end: Date?
            if exam && o.has("end") && !o.isNull("end") {
                let e = o.optLong("end")
                end = e > o.optLong("due") ? Date(epochMilli: e) : nil
            }
            let remindersArr = o.optJSONArray("reminders")
            out.append(MyDeadline(
                id, title.kTake(TITLE_MAX), Date(epochMilli: o.optLong("due")), o.optString("memo", "").kTake(MEMO_MAX),
                done: o.optBoolean("done", false),
                reminders: remindersArr.map { a in (0..<a.length()).map { Date(epochMilli: a.optLong($0)) } } ?? [],
                repeat: exam ? nil : o.optJSONObject("repeat").flatMap(repeatFromJson),
                doneDates: dates(o.optJSONArray("doneDates")),
                skipDates: dates(o.optJSONArray("skipDates")),
                kind: kind, end: end,
                location: exam ? o.optString("location", "").kTake(LOCATION_MAX) : "",
                course: exam ? o.optString("course", "").kTake(COURSE_MAX) : "",
                supplies: exam ? o.optString("supplies", "").kTake(SUPPLIES_MAX) : "",
                sourceAnnouncementId: src
            ))
        }
        return distinctBy(out) { $0.id }
    }

    /// 모르는 반복 종류면 nil(반복 없음으로 본다).
    private static func repeatFromJson(_ o: JSONObject) -> MyRepeat? {
        guard let freq = MyRepeat.Freq.parse(o.optString("freq")) else { return nil }
        let days = o.optJSONArray("weekdays").map { a in (0..<a.length()).compactMap { i -> Weekday? in
            let v = a.optInt(i)
            return (1...7).contains(v) ? Weekday.of(v) : nil
        } } ?? []
        let until = o.optString("until", "").nilIfEmpty.flatMap { DayDate.parse($0) }
        var count: Int?
        if o.has("count") && !o.isNull("count") {
            let c = o.optInt("count", 0)
            count = (1...COUNT_MAX).contains(c) ? c : nil
        }
        return MyRepeat(freq, weekdays: Set(days), until: until, count: count)
    }

    private static func dates(_ a: JSONArray?) -> Set<DayDate> {
        guard let a else { return [] }
        return Set((0..<a.length()).compactMap { DayDate.parse(a.optString($0)) })
    }
}

/// 내 마감 저장소(Kotlin prefs `my_deadlines`의 `items`). 안드로이드의 위젯 갱신·캘린더 내보내기 대신
/// 저장할 때마다 [EtlEvents.dataChanged]와 [onChanged]를 부른다(앱은 여기서 알림 예약을 다시 계산한다).
public final class MyDeadlineStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSRecursiveLock()
    /// 저장한 뒤 불린다(저장한 스레드에서). 앱이 로컬 알림 예약을 다시 계산하는 데 쓴다.
    public var onChanged: (@Sendable () -> Void)?

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "my_deadlines"), onChanged: (@Sendable () -> Void)? = nil) {
        self.prefs = prefs
        self.onChanged = onChanged
    }

    public func load() -> [MyDeadline] { lock.withLock { MyDeadlines.fromJson(prefs.string("items")) } }

    public func save(_ list: [MyDeadline]) {
        lock.withLock { prefs.set(MyDeadlines.toJson(list), for: "items") }
        EtlEvents.dataChanged()
        onChanged?()
    }

    /// 같은 ID면 바꾸고, 없으면 더한다.
    public func upsert(_ d: MyDeadline) {
        lock.withLock { save(load().filter { $0.id != d.id } + [d]) }
    }

    public func delete(_ id: Int64) {
        lock.withLock { save(load().filter { $0.id != id }) }
    }
}
