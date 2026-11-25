import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 반복 끝(날짜까지·횟수)과 편집 창 계산([DeadlineForm]).
@Suite struct RepeatEndAndFormTests {
    func series(_ due: String, _ r: MyRepeat) -> MyDeadline { MyDeadline(1, "보고서", kst(due), repeat: r) }

    func dates(_ m: MyDeadline, _ from: String = "2026-01-01T00:00", _ to: String = "2028-01-01T00:00") -> [DayDate] {
        MyDeadlines.occurrences(m, kst(from), kst(to)).map { $0.occurrence! }
    }

    @Test func countOnly() {
        let s = series("2026-11-25T23:59", MyRepeat(.WEEKLY, count: 3)) // 수요일
        #expect(dates(s) == [d("2026-11-25"), d("2026-12-02"), d("2026-12-09")])
        #expect(MyDeadlines.endSummary(s) == "마지막 마감: 2026/12/09(수) 23:59 · 총 3번")
        // 건너뛴 회차도 회차 수에 들어간다(끝이 늘어나지 않는다)
        var skipped = s
        skipped.skipDates = [d("2026-12-02")]
        #expect(dates(skipped) == [d("2026-11-25"), d("2026-12-09")])
        #expect(MyDeadlines.nextOccurrence(s, kst("2026-12-10T00:00")) == nil)
    }

    @Test func untilOnlyAndBothTakeEarlier() {
        let u = series("2026-11-25T23:59", MyRepeat(.WEEKLY, until: d("2026-12-05")))
        #expect(dates(u).count == 2)
        #expect(MyDeadlines.endSummary(u) == "마지막 마감: 2026/12/02(수) 23:59 · 총 2번")
        // 둘 다 있으면(외부 입력) 먼저 닿는 쪽: 횟수가 먼저
        #expect(dates(series("2026-11-25T23:59", MyRepeat(.WEEKLY, until: d("2027-01-30"), count: 2))).count == 2)
        // 날짜가 먼저
        #expect(dates(series("2026-11-25T23:59", MyRepeat(.WEEKLY, until: d("2026-12-05"), count: 10))).count == 2)
        #expect(MyDeadlines.endSummary(series("2026-11-25T23:59", MyRepeat(.WEEKLY))) == "끝 없이 반복")
    }

    @Test func monthlyEndOfMonthAndBiweeklyWithCount() {
        let m = series("2026-10-31T18:00", MyRepeat(.MONTHLY, count: 4))
        #expect(dates(m) == [d("2026-10-31"), d("2026-11-30"), d("2026-12-31"), d("2027-01-31")])
        let b = series("2026-11-23T09:00", MyRepeat(.BIWEEKLY, weekdays: [.monday, .thursday], count: 3))
        #expect(dates(b) == [d("2026-11-23"), d("2026-11-26"), d("2026-12-07")])
        #expect(MyDeadlines.endSummary(b) == "마지막 마감: 2026/12/07(월) 09:00 · 총 3번")
    }

    @Test func countSerializesAndValidates() {
        let s = series("2026-11-25T23:59", MyRepeat(.DAILY, count: 12))
        #expect(MyDeadlines.fromJson(MyDeadlines.toJson([s])) == [s])
        var zero = s
        zero.repeat = MyRepeat(.DAILY, count: 0)
        #expect(MyDeadlines.validateRepeat(zero)!.contains("횟수"))
        #expect(MyDeadlines.repeatLabel(s) == "매일 · 12번")
        #expect(MyDeadlines.repeatJson(s).optString("count") == "12")
        #expect(MyDeadlines.repeatEndConflict(hasUntil: true, hasCount: true)!.contains("하나만"))
        #expect(MyDeadlines.repeatEndConflict(hasUntil: false, hasCount: true) == nil)
    }

    // ---- 편집 창 ----------------------------------------------------------------

    @Test func firstWeeklyIncludesTodayOnlyBeforeTime() {
        let wed = kst("2026-11-25T12:00") // 수요일 정오
        #expect(DeadlineForm.firstWeekly(wed, .of(23, 59), [.wednesday]) == d("2026-11-25"))
        #expect(DeadlineForm.firstWeekly(wed, .of(9, 0), [.wednesday]) == d("2026-12-02"))
        #expect(DeadlineForm.firstWeekly(wed, .of(9, 0), [.wednesday, .thursday]) == d("2026-11-26"))
        #expect(DeadlineForm.firstWeekly(wed, .of(9, 0), []) == nil)
        #expect(DeadlineForm.firstLabel(DeadlineForm.Input(.WEEKLY, d("2026-11-25"), .of(23, 59), [.thursday]), wed) == "첫 마감: 11/26(목) 23:59")
    }

    @Test func firstBiweeklyBaseWeek() {
        let wed = kst("2026-11-25T12:00")
        // 이번 주부터: 이번 주 목요일
        #expect(DeadlineForm.firstBiweekly(wed, .of(18, 0), [.thursday], nextWeek: false) == d("2026-11-26"))
        // 다음 주부터: 다음 주 목요일
        #expect(DeadlineForm.firstBiweekly(wed, .of(18, 0), [.thursday], nextWeek: true) == d("2026-12-03"))
        // 이번 주에 남은 요일이 없으면(월요일) 같은 짝인 두 주 뒤
        #expect(DeadlineForm.firstBiweekly(wed, .of(18, 0), [.monday], nextWeek: false) == d("2026-12-07"))
        #expect(!DeadlineForm.biweeklyNextWeek(d("2026-12-07"), wed))
        #expect(DeadlineForm.biweeklyNextWeek(d("2026-12-03"), wed))
    }

    @Test func firstMonthlyAndDaily() {
        let now = kst("2027-02-10T12:00")
        #expect(DeadlineForm.firstMonthly(now, .of(23, 59), 31) == d("2027-02-28")) // 없는 달은 말일
        #expect(DeadlineForm.firstMonthly(now, .of(23, 59), 5) == d("2027-03-05"))
        #expect(DeadlineForm.firstMonthly(now, .of(23, 59), 10) == d("2027-02-10"))
        #expect(DeadlineForm.firstMonthly(now, .of(9, 0), 10) == d("2027-03-10"))
        #expect(DeadlineForm.firstDaily(now, .of(21, 0), nil) == d("2027-02-10"))
        #expect(DeadlineForm.firstDaily(now, .of(9, 0), nil) == d("2027-02-11"))
        #expect(DeadlineForm.firstDaily(now, .of(9, 0), d("2027-02-20")) == d("2027-02-20"))
    }

    @Test func switchingFreqCarriesValues() {
        let now = kst("2026-11-25T12:00")
        let none = DeadlineForm.Input(nil, d("2026-11-27"), .of(23, 59)) // 금요일
        let weekly = DeadlineForm.switchFreq(none, .WEEKLY, now)
        #expect(weekly.weekdays == [.friday])
        let monthly = DeadlineForm.switchFreq(none, .MONTHLY, now)
        #expect(monthly.monthDay == 27)
        // 매주(목) → 없음: 계산된 첫 회차 날짜가 날짜 칸에
        var thu = weekly
        thu.weekdays = [.thursday]
        #expect(DeadlineForm.switchFreq(thu, nil, now).date == d("2026-11-26"))
    }

    @Test func keepsOriginalStartWhenPatternUnchanged() {
        let now = kst("2026-12-20T12:00")
        let old = series("2026-11-25T23:59", MyRepeat(.WEEKLY, weekdays: [.wednesday]))
        let same = DeadlineForm.Input(.WEEKLY, d("2026-11-25"), .of(22, 0), [.wednesday])
        #expect(DeadlineForm.samePattern(old, same, now))
        var other = same
        other.weekdays = [.thursday]
        #expect(!DeadlineForm.samePattern(old, other, now))
        #expect(DeadlineForm.due(same, now, keep: d("2026-11-25")) == kst("2026-11-25T22:00"))
    }

    @Test func switchingEndCarriesValues() {
        let s = series("2026-11-25T23:59", MyRepeat(.WEEKLY))
        // 날짜까지 → 횟수: 그 날짜까지의 회차 수
        let a = DeadlineForm.switchEnd(s, .UNTIL, .COUNT, d("2026-12-10"), nil)
        #expect(a.until == nil && a.count == 3)
        // 횟수 → 날짜까지: 마지막 회차 날짜
        let b = DeadlineForm.switchEnd(s, .COUNT, .UNTIL, nil, 3)
        #expect(b.until == d("2026-12-09") && b.count == nil)
    }
}
