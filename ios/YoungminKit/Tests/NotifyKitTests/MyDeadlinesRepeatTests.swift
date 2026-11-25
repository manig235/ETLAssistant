import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct MyDeadlinesRepeatTests {
    let MON = Weekday.monday
    let WED = Weekday.wednesday
    let FRI = Weekday.friday

    func series(_ start: String, _ freq: MyRepeat.Freq, _ days: Set<Weekday> = [], until: DayDate? = nil, id: Int64 = 1_763_000_000_000) -> MyDeadline {
        MyDeadline(id, "실험 보고서", kst(start), repeat: MyRepeat(freq, weekdays: days, until: until))
    }

    func dates(_ x: MyDeadline, _ from: String, _ to: String) -> [String] {
        MyDeadlines.occurrences(x, kst(from), kst(to)).map { $0.occurrence!.description }
    }

    @Test func weeklyMultipleWeekdays() {
        // 2026-11-23은 월요일
        let s = series("2026-11-23T18:00", .WEEKLY, [MON, WED])
        #expect(dates(s, "2026-11-20T00:00", "2026-12-03T00:00") == ["2026-11-23", "2026-11-25", "2026-11-30", "2026-12-02"])
        let o = MyDeadlines.occurrences(s, kst("2026-11-25T00:00"), kst("2026-11-26T00:00"))
        #expect(o.count == 1)
        #expect(o[0].due == kst("2026-11-25T18:00")) // 마감 시각은 그대로
        #expect(MyDeadlines.repeatLabel(s) == "매주 월·수")
    }

    @Test func weeklyStartsOnFirstMatchingDayAfterStart() {
        // 화요일 시작, 월·수 반복 → 첫 회차는 수요일. 시작일 전 회차는 없다.
        let s = series("2026-11-24T18:00", .WEEKLY, [MON, WED])
        #expect(dates(s, "2026-11-01T00:00", "2026-12-01T00:00") == ["2026-11-25", "2026-11-30"])
        #expect(MyDeadlines.firstOccurrence(s)!.occurrence == d("2026-11-25"))
        // 요일을 비우면 시작일의 요일
        #expect(MyDeadlines.weekdaysOf(series("2026-11-24T18:00", .WEEKLY)) == [.tuesday])
    }

    @Test func biweekly() {
        let s = series("2026-11-23T09:00", .BIWEEKLY, [MON])
        #expect(dates(s, "2026-11-23T00:00", "2026-12-28T00:00") == ["2026-11-23", "2026-12-07", "2026-12-21"])
        // 수요일 시작, 월·금: 시작 주(11/23 주)의 금요일, 그다음은 12/7 주
        let s2 = series("2026-11-25T09:00", .BIWEEKLY, [MON, FRI])
        #expect(dates(s2, "2026-11-23T00:00", "2026-12-14T00:00") == ["2026-11-27", "2026-12-07", "2026-12-11"])
    }

    @Test func monthlyClampsToMonthEnd() {
        let s = series("2027-01-31T23:59", .MONTHLY)
        #expect(dates(s, "2027-01-01T00:00", "2027-05-01T00:00") == ["2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30"])
        let leap = series("2028-01-31T23:59", .MONTHLY)
        #expect(dates(leap, "2028-02-01T00:00", "2028-03-01T00:00") == ["2028-02-29"])
        #expect(MyDeadlines.repeatLabel(s) == "매월 31일")
    }

    @Test func dailyWithUntilInclusive() {
        let s = series("2026-11-23T22:00", .DAILY, until: d("2026-11-25"))
        #expect(dates(s, "2026-11-01T00:00", "2026-12-31T00:00") == ["2026-11-23", "2026-11-24", "2026-11-25"])
        #expect(MyDeadlines.nextOccurrence(s, kst("2026-11-25T22:00")) == nil)
        #expect(MyDeadlines.repeatLabel(s) == "매일 · 11/25까지")
    }

    @Test func usesKstDates() {
        // 월요일 00:30 KST = 일요일 15:30 UTC. 회차 날짜·요일은 KST 기준.
        let s = series("2026-11-23T00:30", .WEEKLY)
        #expect(MyDeadlines.weekdaysOf(s) == [MON])
        let next = MyDeadlines.nextOccurrence(s, kst("2026-11-23T01:00"))!
        #expect(next.occurrence == d("2026-11-30"))
        #expect(next.due == instant("2026-11-29T15:30:00Z"))
    }

    @Test func doneAppliesToOneOccurrence() {
        let now = kst("2026-11-24T12:00")
        let s = series("2026-11-23T18:00", .WEEKLY, [MON, WED])
        let marked = MyDeadlines.setDone(s, d("2026-11-25"), true, now)
        #expect(marked.doneDates == [d("2026-11-25")])
        #expect(!marked.done)
        let occ = MyDeadlines.occurrences(marked, now, kst("2026-12-01T00:00"))
        #expect(occ.map(\.done) == [true, false])
        // 다음 미완료 회차는 완료한 수요일을 건너뛴다
        #expect(MyDeadlines.nextOccurrence(marked, now)!.occurrence == d("2026-11-30"))
        #expect(MyDeadlines.setDone(marked, d("2026-11-25"), false, now).doneDates.isEmpty)
        // 오래된 완료 기록은 정리한다
        var old = marked
        old.doneDates = [d("2026-01-01")]
        #expect(MyDeadlines.setDone(old, d("2026-11-25"), true, now).doneDates == [d("2026-11-25")])
    }

    @Test func lastMissedOnlyMostRecentWithinWeek() {
        let s = series("2026-11-01T18:00", .DAILY)
        let now = kst("2026-11-24T12:00")
        #expect(MyDeadlines.lastMissed(s, now)!.occurrence == d("2026-11-23"))
        #expect(MyDeadlines.lastMissed(MyDeadlines.setDone(s, d("2026-11-23"), true, now), now) == nil) // 최근 회차를 했으면 없음
        // 7일보다 오래 전에 끝난 반복은 누락으로 보이지 않는다
        var ended = s
        ended.repeat!.until = d("2026-11-10")
        #expect(MyDeadlines.lastMissed(ended, now) == nil)
    }

    @Test func itemsAndAlertsPerOccurrence() {
        let s = series("2026-11-16T18:00", .WEEKLY, [MON, WED])
        let now = kst("2026-11-24T12:00")
        let items = MyDeadlines.toItems([s], now)
        // 최근 놓친 회차(11/23 월)와 다음 회차(11/25 수)만
        #expect(items.map(\.missing) == [true, false])
        #expect(items.map(\.due) == [kst("2026-11-23T18:00"), kst("2026-11-25T18:00")])
        #expect(Set(items.map(\.assignmentId)).count == 2)
        #expect(!items.contains { $0.assignmentId == s.id })

        let plan = NotificationPlanner.plan(Snapshot(now, items, []), NotifyState(announcementBaselineDone: true), now, NotifySettings(), seoul)
        let keys1 = Set(plan.notices.flatMap(\.firedKeys))
        #expect(!keys1.isEmpty)
        // 다음 주 같은 요일 회차는 다른 키라 다시 알린다
        let later = kst("2026-12-01T12:00")
        let items2 = MyDeadlines.toItems([s], later)
        var state = NotifyState(announcementBaselineDone: true)
        for k in keys1 { state.fired[k] = now.epochMilli }
        let plan2 = NotificationPlanner.plan(Snapshot(later, items2, []), state, later, NotifySettings(), seoul)
        #expect(!plan2.notices.isEmpty)
        #expect(!plan2.notices.flatMap(\.firedKeys).contains { keys1.contains($0) })
    }

    @Test func customRemindersFollowEachOccurrence() {
        var s = series("2026-11-23T18:00", .WEEKLY, [MON])
        s.reminders = [kst("2026-11-23T17:00")]
        let at1 = kst("2026-11-23T17:05")
        let n1s = MyDeadlines.reminderNotices([s], at1, [])
        #expect(n1s.count == 1)
        let n1 = n1s[0]
        let fired = Set(n1.firedKeys)
        #expect(MyDeadlines.reminderNotices([s], at1, fired).isEmpty)
        #expect(MyDeadlines.nextReminderAt([s], at1, fired) == kst("2026-11-30T17:00"))
        let n2s = MyDeadlines.reminderNotices([s], kst("2026-11-30T17:01"), fired)
        #expect(n2s.count == 1)
        #expect(!n2s[0].firedKeys.contains { fired.contains($0) })
        #expect(n1.tag != n2s[0].tag)
        // 완료한 회차는 울리지 않는다
        let done = MyDeadlines.setDone(s, d("2026-11-30"), true, at1)
        #expect(MyDeadlines.reminderNotices([done], kst("2026-11-30T17:01"), fired).isEmpty)
        #expect(MyDeadlines.nextReminderAt([done], at1, fired) == kst("2026-12-07T17:00"))
    }

    @Test func skipOneOccurrence() {
        let now = kst("2026-11-24T12:00")
        let s = MyDeadlines.skip(series("2026-11-23T18:00", .WEEKLY, [MON, WED]), d("2026-11-25"), now)
        #expect(dates(s, "2026-11-23T00:00", "2026-12-01T00:00") == ["2026-11-23", "2026-11-30"])
        #expect(!MyDeadlines.matches(s, d("2026-11-25")))
    }

    @Test func jsonRoundTripAndBackwardCompatible() {
        var s = series("2026-11-23T18:00", .BIWEEKLY, [MON, WED], until: d("2026-12-31"))
        s.doneDates = [d("2026-11-23")]
        s.skipDates = [d("2026-12-07")]
        s.reminders = [kst("2026-11-23T17:00")]
        let plain = MyDeadline(2, "서류", kst("2026-11-26T12:00"))
        #expect(MyDeadlines.fromJson(MyDeadlines.toJson([s, plain])) == [s, plain])
        // 예전 저장 형식(반복 키 없음)과 모르는 반복 종류는 반복 없음
        let old = #"[{"id":5,"title":"옛 항목","due":0,"memo":"","done":true,"reminders":[]},{"id":6,"title":"미래","due":0,"repeat":{"freq":"yearly"}}]"#
        let parsed = MyDeadlines.fromJson(old)
        #expect(parsed.map { $0.repeat == nil } == [true, true])
        #expect(parsed[0].done)
    }

    @Test func agendaExpandsWeekAndAddsNextBeyond() {
        let now = kst("2026-11-23T12:00")
        let daily = series("2026-11-20T21:00", .DAILY, id: 10)
        var monthly = series("2026-11-30T23:59", .MONTHLY, id: 20)
        monthly.title = "월세"
        let plain = MyDeadline(30, "서류", kst("2026-11-24T12:00"))
        let s = Agenda.build([], [daily, monthly, plain], now, seoul)
        let upcomingDaily = s.upcoming.flatMap(\.rows).filter { $0.mine?.id == 10 }
        #expect(upcomingDaily.count == 7) // 11/23~11/29 매일
        #expect(upcomingDaily.first!.subtitle.contains("🔁 매일"))
        #expect(s.overdue.filter { $0.mine?.id == 10 }.count == 1) // 11/22 회차만
        // 7일 창 밖의 다음 회차 하나도 "그 이후"에 보인다(매일 반복 11/30 21:00, 월세 11/30 23:59).
        #expect(s.later.flatMap(\.rows).map(\.title) == ["실험 보고서", "월세"])
        let all = (s.overdue + s.upcoming.flatMap(\.rows) + s.later.flatMap(\.rows)).map(\.id)
        #expect(all.count == Set(all).count)
    }

    // widgetShowsNextOccurrenceOnly: 홈 화면 위젯(DeadlineWidgetLogic)은 iOS 1단계 범위 밖이라 옮기지 않았다.
    // 같은 동작(다음 회차 하나)은 MyDeadlines.nextOccurrence로 확인한다.
    @Test func nextOccurrenceForWidgetLikeList() {
        let now = kst("2026-11-23T12:00")
        let daily = series("2026-11-20T21:00", .DAILY)
        let next = MyDeadlines.nextOccurrence(daily, now)!
        #expect(next.occurrence == d("2026-11-23"))
        #expect(next.due == kst("2026-11-23T21:00"))
    }

    @Test func toolResultListsOccurrencesInWindow() throws {
        let now = kst("2026-11-24T12:00") // 화요일
        let s = series("2026-11-16T18:00", .WEEKLY, [MON, WED])
        let week = AssignmentQuery.window(.THIS_WEEK, now)
        let r = MyDeadlines.toolResult([s], now, week, includeDone: false)
        let items = try r.getJSONArray("items")
        let rows = (0..<items.length()).map { try! items.getJSONObject($0) }
        #expect(rows.map { $0.optString("occurrence") } == ["2026-11-23", "2026-11-25"])
        #expect(try rows[0].getBoolean("overdue"))
        #expect(try rows[0].getJSONObject("repeat").getString("freq") == "weekly")
        let wd = try rows[0].getJSONObject("repeat").getJSONArray("weekdays")
        #expect((0..<wd.length()).map { wd.optString($0) } == ["mon", "wed"])
        #expect(try rows[1].getLong("id") == s.id)
        // 기간 없음: 최근 놓친 회차와 다음 회차
        #expect(try MyDeadlines.toolResult([s], now, nil, includeDone: false).getInt("count") == 2)
    }

    @Test func validateAndParse() {
        #expect(MyDeadlines.validateRepeat(series("2026-11-23T18:00", .DAILY, until: d("2026-11-22"))) != nil)
        // 끝 날짜까지 고른 요일이 없다
        #expect(MyDeadlines.validateRepeat(series("2026-11-23T18:00", .WEEKLY, [FRI], until: d("2026-11-25"))) != nil)
        #expect(MyDeadlines.validateRepeat(series("2026-11-23T18:00", .WEEKLY, [FRI])) == nil)
        #expect(MyDeadlines.parseWeekday("mon") == MON)
        #expect(MyDeadlines.parseWeekday("Wednesday") == WED)
        #expect(MyDeadlines.parseWeekday("금") == FRI)
        #expect(MyDeadlines.parseWeekday("금요일") == FRI)
        #expect(MyDeadlines.parseWeekday("금방") == nil)
        #expect(MyDeadlines.parseWeekday("x") == nil)
        #expect(MyRepeat.Freq.parse("Biweekly") == .BIWEEKLY)
        // 반복 마감은 한 줄 표시에 규칙을 붙인다
        #expect(MyDeadlines.line(series("2026-11-23T18:00", .WEEKLY)).hasSuffix("🔁 매주 월"))
        #expect(MyDeadlines.firstOccurrence(series("2026-11-23T18:00", .DAILY))!.due == kst("2026-11-23T18:00"))
    }
}
