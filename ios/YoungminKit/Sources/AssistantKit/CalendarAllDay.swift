import Foundation
import ETLKit

/// EKEvent의 시각 칸(startDate·endDate·isAllDay·timeZone)만 떼어 낸 값. EventKit 없이 변환을 테스트하려고 둔다.
public struct EventKitTimes: Hashable, Sendable {
    public var start: Date
    public var end: Date
    public var isAllDay: Bool
    /// 종일 일정은 nil(떠 있는 날짜). 시간 일정은 그 일정의 시간대.
    public var timeZone: TimeZone?

    public init(start: Date, end: Date, isAllDay: Bool, timeZone: TimeZone?) {
        self.start = start
        self.end = end
        self.isAllDay = isAllDay
        self.timeZone = timeZone
    }
}

/// 종일 일정 날짜 변환(순수 함수). 규칙은 세 겹이다.
///
/// 1. 도구·화면: 끝 날짜를 **포함**한다(`11/26~11/28`은 사흘). `CalendarToolLogic.parseWhen`, `CalendarReadLogic.toEvent`.
/// 2. 앱 안 값(`CalendarToolLogic.Backup`, `RawInstance`): 안드로이드 CalendarContract와 같이 UTC 자정 millis,
///    끝은 마지막 날의 **다음 날** 0시(배타). `11/26~11/28` = `[utc(11/26), utc(11/29))`.
/// 3. EventKit: 종일 일정은 시간대가 없는(떠 있는) 날짜라 기기 달력(현재 시간대)으로 읽고 쓴다.
///    - 쓰기: 시작 = 첫날 0시, 끝 = **마지막 날 23:59:59**(= 다음 날 0시 - 1초). EventKit이 종일 일정을 읽어 줄 때의 모양과 같고,
///      끝을 포함으로 보든(마지막 날 안의 시각) 배타로 올림하든(다음 날 0시) 같은 날짜가 된다. 마지막 날 0시는 쓰지 않는다
///      (다음 날 0시 배타 표현과 구별할 수 없어 읽을 때 하루가 줄어든다).
///    - 읽기: 끝이 시작보다 뒤이고 **정확히 0시**(0.5초 이내)면 배타(그 전날이 마지막 날), 그 밖(23:59:59 등)은 그 시각의 날짜가 마지막 날.
///      끝이 시작과 같거나 앞이면 하루짜리.
///
/// 앱은 KST 중심이지만 종일 일정의 날짜는 KST가 아니라 일정을 표시하는 기기 달력 기준이어야 한다(떠 있는 날짜).
/// 읽기와 쓰기에 같은 달력을 쓰므로 기기 시간대가 무엇이든 왕복해도 날짜가 바뀌지 않는다.
public enum CalendarAllDay {
    /// 0시 판정 허용 오차(초). 23:59:59와 다음 날 0시의 차이(1초)보다 작아야 한다.
    static let MIDNIGHT_TOLERANCE: TimeInterval = 0.5

    /// [zone] 시간대의 그레고리력.
    public static func calendar(_ zone: TimeZone) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        return c
    }

    /// [date]가 [cal]에서 속한 날짜.
    public static func day(_ date: Date, _ cal: Calendar) -> DayDate {
        let p = cal.dateComponents([.year, .month, .day], from: date)
        return DayDate(year: p.year ?? 1970, month: p.month ?? 1, day: p.day ?? 1) ?? DayDate.of(1970, 1, 1)
    }

    /// [d]의 첫 순간([cal] 기준). 0시가 없는 날(서머타임으로 0시를 건너뛰는 시간대)은 그날 첫 시각.
    public static func dayStart(_ d: DayDate, _ cal: Calendar) -> Date {
        let noon = cal.date(from: DateComponents(year: d.year, month: d.month, day: d.day, hour: 12)) ?? d.startOfDay
        return cal.startOfDay(for: noon)
    }

    /// 포함 날짜 범위 → EventKit 시작·끝(끝 = 마지막 날 23:59:59).
    public static func eventKitDates(first: DayDate, last: DayDate, _ cal: Calendar) -> (start: Date, end: Date) {
        let lastDay = max(last, first)
        return (dayStart(first, cal), dayStart(lastDay.plusDays(1), cal).addingTimeInterval(-1))
    }

    /// EventKit 종일 일정의 시작·끝 → 포함 날짜 범위. 끝이 다음 날 0시(배타)든 마지막 날 23:59:59든 같은 마지막 날을 낸다.
    public static func inclusiveDays(start: Date, end: Date?, _ cal: Calendar) -> (first: DayDate, last: DayDate) {
        let first = day(start, cal)
        guard let end, end > start else { return (first, first) }
        let endDay = day(end, cal)
        let atMidnight = abs(end.timeIntervalSince(dayStart(endDay, cal))) < MIDNIGHT_TOLERANCE
        let last = atMidnight ? endDay.minusDays(1) : endDay
        return (first, max(first, last))
    }

    /// 앱 안 값(UTC 자정, 끝 배타) → 포함 날짜 범위. 끝이 시작 이하이면 하루짜리.
    public static func inclusiveDays(dtStart: Int64, dtEnd: Int64) -> (first: DayDate, last: DayDate) {
        let first = CalendarLogic.utcDate(dtStart)
        return (first, max(first, CalendarLogic.utcDate(dtEnd).minusDays(1)))
    }

    /// 포함 날짜 범위 → 앱 안 값(UTC 자정, 끝 배타).
    public static func utcRange(first: DayDate, last: DayDate) -> (dtStart: Int64, dtEnd: Int64) {
        (CalendarLogic.utcMidnight(first), CalendarLogic.utcMidnight(max(last, first).plusDays(1)))
    }

    /// 백업(도구가 쓰는 모양) → EKEvent에 넣을 시각 칸.
    public static func eventKitTimes(_ b: CalendarToolLogic.Backup, _ cal: Calendar) -> EventKitTimes {
        if b.allDay {
            let (first, last) = inclusiveDays(dtStart: b.dtStart, dtEnd: b.dtEnd)
            let (s, e) = eventKitDates(first: first, last: last, cal)
            return EventKitTimes(start: s, end: e, isAllDay: true, timeZone: nil)
        }
        let s = Date(epochMilli: b.dtStart)
        return EventKitTimes(start: s, end: max(Date(epochMilli: b.dtEnd), s), isAllDay: false,
                             timeZone: TimeZone(identifier: b.timezone) ?? EtlLogic.kst)
    }

    /// EKEvent의 시각 칸 → 앱 안 값(종일은 UTC 자정·끝 배타, 시간 일정은 실제 시각).
    public static func backupTimes(start: Date, end: Date?, isAllDay: Bool, _ cal: Calendar) -> (dtStart: Int64, dtEnd: Int64) {
        if isAllDay {
            let (first, last) = inclusiveDays(start: start, end: end, cal)
            return utcRange(first: first, last: last)
        }
        return (start.epochMilli, max(end ?? start, start).epochMilli)
    }

    public static func backupTimes(_ t: EventKitTimes, _ cal: Calendar) -> (dtStart: Int64, dtEnd: Int64) {
        backupTimes(start: t.start, end: t.end, isAllDay: t.isAllDay, cal)
    }
}
