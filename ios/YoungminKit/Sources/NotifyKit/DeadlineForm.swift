import Foundation
import ETLKit

/// 내 마감 편집 창의 계산(순수 함수). 반복 종류마다 필요한 입력만 받고 저장할 [MyDeadline.due](첫 회차)를 여기서 계산한다.
/// - 없음: 날짜 + 시각.  매일: 시각(+ 시작 날짜, 기본 오늘).  매주: 요일 + 시각.  격주: 요일 + 시각 + 이번 주/다음 주부터.  매월: N일 + 시각.
/// 시간대는 KST.
public enum DeadlineForm {
    public enum EndMode: Hashable, Sendable { case NONE, UNTIL, COUNT }

    private static func at(_ date: DayDate, _ time: DayTime) -> Date { date.atTime(time.hour, time.minute) }

    private static func monday(_ d: DayDate) -> DayDate { d.previousOrSame(.monday) }

    /// 매주: 오늘 이후 가장 가까운 선택 요일(오늘이 선택 요일이고 시각이 아직 안 지났으면 오늘). 요일이 없으면 nil.
    public static func firstWeekly(_ now: Date, _ time: DayTime, _ weekdays: Set<Weekday>) -> DayDate? {
        if weekdays.isEmpty { return nil }
        let today = DayDate(now)
        return (0...7).map { today.plusDays($0) }.first { weekdays.contains($0.dayOfWeek) && at($0, time) > now }
    }

    /// 격주: 기준 주(이번 주 또는 다음 주, 월요일 시작)부터 두 주마다. 기준 주에 남은 선택 요일이 없으면 두 주 뒤 같은 짝의 주에서 고른다.
    public static func firstBiweekly(_ now: Date, _ time: DayTime, _ weekdays: Set<Weekday>, nextWeek: Bool) -> DayDate? {
        if weekdays.isEmpty { return nil }
        var base = monday(DayDate(now)).plusWeeks(nextWeek ? 1 : 0)
        for _ in 0..<3 {
            if let d = (0...6).map({ base.plusDays($0) }).first(where: { weekdays.contains($0.dayOfWeek) && at($0, time) > now }) { return d }
            base = base.plusWeeks(2)
        }
        return nil
    }

    /// 매월 [day]일(없는 달은 말일): 오늘 이후 가장 가까운 날(오늘이고 시각이 안 지났으면 오늘).
    public static func firstMonthly(_ now: Date, _ time: DayTime, _ day: Int) -> DayDate {
        let today = DayDate(now)
        var month = today.withDayOfMonth(1)!
        while true {
            let d = month.withDayOfMonth(min(min(max(day, 1), 31), month.lengthOfMonth))!
            if !(d < today) && at(d, time) > now { return d }
            month = month.plusMonths(1)
        }
    }

    /// 매일: [start](기본 오늘)부터, 그날 시각이 이미 지났으면 다음 날.
    public static func firstDaily(_ now: Date, _ time: DayTime, _ start: DayDate?) -> DayDate {
        let today = DayDate(now)
        let s = (start == nil || start! < today) ? today : start!
        return at(s, time) > now ? s : s.plusDays(1)
    }

    /// 격주 시리즈의 기준이 이번 주와 다른 짝인가(편집 창 "다음 주부터"를 기존 값으로 채울 때).
    public static func biweeklyNextWeek(_ start: DayDate, _ now: Date) -> Bool {
        (monday(start).daysUntil(monday(DayDate(now))) / 7) % 2 != 0
    }

    /// 편집 창의 입력.
    public struct Input: Hashable, Sendable {
        public var freq: MyRepeat.Freq?
        public var date: DayDate
        public var time: DayTime
        public var weekdays: Set<Weekday>
        public var monthDay: Int
        public var biweeklyNext: Bool
        /// 매일 반복의 시작 날짜(nil이면 오늘).
        public var dailyStart: DayDate?

        public init(_ freq: MyRepeat.Freq?, _ date: DayDate, _ time: DayTime, _ weekdays: Set<Weekday> = [], monthDay: Int? = nil,
                    biweeklyNext: Bool = false, dailyStart: DayDate? = nil) {
            self.freq = freq
            self.date = date
            self.time = time
            self.weekdays = weekdays
            self.monthDay = monthDay ?? date.dayOfMonth
            self.biweeklyNext = biweeklyNext
            self.dailyStart = dailyStart
        }
    }

    /// 입력에서 저장할 첫 회차 날짜. [keep]은 고치는 중인 원래 항목의 첫 회차 날짜(반복 모양이 그대로면 원래 시작 날짜를 둔다).
    public static func firstDate(_ input: Input, _ now: Date, keep: DayDate? = nil) -> DayDate? {
        guard let f = input.freq else { return input.date }
        switch f {
        case .DAILY: return keep ?? firstDaily(now, input.time, input.dailyStart)
        case .WEEKLY: return keep ?? firstWeekly(now, input.time, input.weekdays)
        case .BIWEEKLY: return keep ?? firstBiweekly(now, input.time, input.weekdays, nextWeek: input.biweeklyNext)
        case .MONTHLY: return keep ?? firstMonthly(now, input.time, input.monthDay)
        }
    }

    public static func due(_ input: Input, _ now: Date, keep: DayDate? = nil) -> Date? {
        firstDate(input, now, keep: keep).map { at($0, input.time) }
    }

    /// 고치는 중인 항목의 반복 모양이 그대로인가(그러면 시작 날짜를 유지한다).
    public static func samePattern(_ old: MyDeadline, _ input: Input, _ now: Date) -> Bool {
        guard let r = old.repeat, r.freq == input.freq else { return false }
        let start = DayDate(old.due)
        switch r.freq {
        case .DAILY: return input.dailyStart == nil || input.dailyStart == start
        case .WEEKLY: return MyDeadlines.weekdaysOf(old) == input.weekdays
        case .BIWEEKLY: return MyDeadlines.weekdaysOf(old) == input.weekdays && biweeklyNextWeek(start, now) == input.biweeklyNext
        case .MONTHLY: return start.dayOfMonth == input.monthDay
        }
    }

    /// 반복 종류를 바꿀 때 값 이어받기: 없음→매주·격주는 날짜의 요일, 없음→매월은 날짜의 일, 반복→없음은 계산된 첫 회차 날짜.
    public static func switchFreq(_ input: Input, _ next: MyRepeat.Freq?, _ now: Date) -> Input {
        if input.freq == next { return input }
        let first = firstDate(input, now) ?? input.date
        var c = input
        guard let next else {
            c.freq = nil
            c.date = first
            return c
        }
        c.freq = next
        switch next {
        case .WEEKLY, .BIWEEKLY: if input.weekdays.isEmpty { c.weekdays = [first.dayOfWeek] }
        case .MONTHLY: c.monthDay = input.freq == nil ? input.date.dayOfMonth : first.dayOfMonth
        case .DAILY: break
        }
        return c
    }

    /// 끝을 바꿀 때 값 이어받기: 날짜까지→횟수는 그 날짜까지의 회차 수, 횟수→날짜까지는 마지막 회차 날짜.
    public static func switchEnd(_ series: MyDeadline, _ from: EndMode, _ to: EndMode, _ until: DayDate?, _ count: Int?) -> (until: DayDate?, count: Int?) {
        guard var r = series.repeat else { return (until, count) }
        if from == .UNTIL && to == .COUNT, let until {
            r.until = nil
            r.count = nil
            var s = series
            s.repeat = r
            return (nil, min(max(MyDeadlines.occurrenceCount(s, until), 1), MyDeadlines.COUNT_MAX))
        }
        if from == .COUNT && to == .UNTIL, let count {
            r.until = nil
            r.count = count
            var s = series
            s.repeat = r
            return (MyDeadlines.lastOccurrence(s)?.occurrence, nil)
        }
        return (until, count)
    }

    /// "첫 마감: 11/26(목) 23:59". 정할 수 없으면 nil.
    public static func firstLabel(_ input: Input, _ now: Date, keep: DayDate? = nil) -> String? {
        due(input, now, keep: keep).map { "첫 마감: " + NoticeText.dateLabel($0) }
    }
}
