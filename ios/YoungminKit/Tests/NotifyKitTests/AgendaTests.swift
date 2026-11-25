import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct AgendaTests {
    let now = instant("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    func h(_ x: Double) -> Date { now.h(x) }
    func asg(_ id: Int64, _ due: Date?, submitted: Bool = false, missing: Bool = false) -> AssignmentItem {
        AssignmentItem(1, "2026-2 자료구조 (001)", id, "과제\(id)", due, true, 10.0, "https://myetl.snu.ac.kr/courses/1/assignments/\(id)", submitted, missing)
    }
    func future(_ s: Agenda.Schedule) -> [Agenda.Row] { (s.upcoming + s.later).flatMap(\.rows) }

    @Test func sevenDaysIncludesTodayTomorrowAndDaysAcrossCalendarWeek() {
        let assignments = [asg(1, h(5)), asg(2, h(20)), asg(3, h(60)), asg(4, h(24 * 6)), asg(5, h(24 * 20))]
        let mine = [MyDeadline(100, "장학금 서류", h(30), "행정실")]
        let result = Agenda.build(assignments.reversed(), mine, now, seoul)

        #expect(result.today == DayDate.of(2026, 11, 19))
        #expect(result.lastDay == DayDate.of(2026, 11, 25))
        #expect(result.upcoming.flatMap(\.rows).map(\.title) == ["과제1", "과제2", "장학금 서류", "과제3", "과제4"])
        #expect(result.upcomingCount == 5)
        #expect(result.later.flatMap(\.rows).map(\.title) == ["과제5"])
        #expect(result.laterCount == 1)
        #expect(result.urgent.map(\.title) == ["과제1", "과제2"])
        #expect(result.urgent.allSatisfy { future(result).contains($0) })
    }

    @Test func sevenDayBoundaryUsesLocalMidnightAndIncludesEntireLastDay() {
        let boundary = ZoneClock.startOfDay(DayDate.of(2026, 11, 26), seoul)
        let result = Agenda.build([asg(1, boundary.secs(-tiny)), asg(2, boundary)], [], now, seoul)
        #expect(result.upcoming.flatMap(\.rows).map(\.title) == ["과제1"])
        #expect(result.later.flatMap(\.rows).map(\.title) == ["과제2"])
    }

    @Test func urgentIncludesExactly24HoursButExcludesExpiredAndCompleted() {
        let result = Agenda.build([
            asg(1, now, missing: true), asg(2, now.secs(tiny)), asg(3, h(24)),
            asg(4, h(24).secs(tiny)), asg(5, h(1), submitted: true),
        ], [MyDeadline(10, "완료", h(2), done: true), MyDeadline(11, "미완료", h(3))], now, seoul, hideDone: false)
        #expect(result.urgent.map(\.title) == ["과제2", "미완료", "과제3"])
        #expect(result.overdue.map(\.title) == ["과제1"])
        #expect(result.upcomingCount == 6)
        #expect(result.urgent.allSatisfy { future(result).contains($0) })
    }

    @Test func overdueAndCompletedVisibilityIsPreserved() {
        let assignments = [
            asg(1, h(-5), missing: true), asg(2, h(-50), submitted: true),
            asg(3, h(-1)), asg(4, h(10), submitted: true), asg(5, nil),
        ]
        let mine = [MyDeadline(100, "미완료", h(-1)), MyDeadline(101, "끝난 일", h(-3), done: true)]
        let result = Agenda.build(assignments, mine, now, seoul)
        #expect(result.overdue.map(\.title) == ["과제1", "미완료"])
        #expect(future(result).isEmpty)
        let shown = Agenda.build(assignments, mine, now, seoul, hideDone: false)
        #expect(future(shown).map(\.title) == ["과제4"])
        #expect(future(shown).count == 1 && future(shown)[0].done)
        #expect(shown.urgent.isEmpty)
    }

    @Test func filtersApplyToSummaryOverdueAndBothFutureSections() {
        let assignments = [asg(1, h(1)), asg(2, h(200)), asg(3, h(-1), missing: true)]
        let mine = [MyDeadline(1, "곧", h(2)), MyDeadline(2, "나중", h(201)), MyDeadline(3, "지남", h(-2))]
        for (filter, kind) in [(Agenda.Filter.ASSIGNMENT, Agenda.Kind.ASSIGNMENT), (.MINE, .MINE)] {
            let result = Agenda.build(assignments, mine, now, seoul, filter)
            #expect(result.urgent.count == 1)
            #expect(result.overdue.count == 1)
            #expect(result.upcomingCount == 1)
            #expect(result.laterCount == 1)
            #expect((result.urgent + result.overdue + future(result)).allSatisfy { $0.kind == kind })
        }
    }

    @Test func laterDeadlinesRemainAvailableWhenNextSevenDaysAreEmpty() {
        let result = Agenda.build([asg(1, h(300)), asg(2, h(200))], [], now, seoul)
        #expect(result.urgent.isEmpty)
        #expect(result.upcoming.isEmpty)
        #expect(future(result).map(\.title) == ["과제2", "과제1"])
        let empty = Agenda.build([], [], now, seoul)
        #expect((empty.urgent + empty.overdue + future(empty)).isEmpty)
    }

    @Test func endOfYearAndSundayUseRollingDates() {
        let sunday = DayDate.of(2026, 12, 27).atTime(12, 0)
        let nextSunday = ZoneClock.startOfDay(DayDate.of(2027, 1, 3), seoul)
        let result = Agenda.build([asg(1, sunday.secs(3600)), asg(2, nextSunday.secs(-tiny)), asg(3, nextSunday)], [], sunday, seoul)
        #expect(result.lastDay == DayDate.of(2027, 1, 2))
        #expect(result.upcoming.flatMap(\.rows).map(\.title) == ["과제1", "과제2"])
        #expect(result.later.flatMap(\.rows).map(\.title) == ["과제3"])
    }

    @Test func daylightSavingUsesCalendarDaysForListAndElapsedHoursForUrgency() {
        let start = ZoneClock.instant(DayDate.of(2026, 10, 31), 12, 0, 0, newYork)
        let boundary = ZoneClock.startOfDay(DayDate.of(2026, 11, 7), newYork)
        let result = Agenda.build([asg(1, start.h(24)), asg(2, boundary.secs(-tiny)), asg(3, boundary)], [], start, newYork)
        #expect(result.lastDay == DayDate.of(2026, 11, 6))
        #expect(result.urgent.map(\.title) == ["과제1"])
        #expect(result.upcoming.flatMap(\.rows).map(\.title) == ["과제1", "과제2"])
        #expect(result.later.flatMap(\.rows).map(\.title) == ["과제3"])
    }

    @Test func advancingTimeMovesDeadlinesIntoUrgentThenOverdue() {
        let assignments = [asg(1, h(25), missing: true)]
        #expect(Agenda.build(assignments, [], now, seoul).urgent.isEmpty)
        #expect(Agenda.build(assignments, [], h(1), seoul).urgent.count == 1)
        let expired = Agenda.build(assignments, [], h(25), seoul)
        #expect(expired.urgent.isEmpty)
        #expect(future(expired).isEmpty)
        #expect(expired.overdue.count == 1)
    }

    @Test func dateRolloverMovesLaterDeadlineIntoSevenDayList() {
        let today = ZoneClock.localDate(now, seoul)
        let nextDay = ZoneClock.startOfDay(today.plusDays(1), seoul)
        let assignments = [asg(1, ZoneClock.startOfDay(today.plusDays(7), seoul))]
        #expect(Agenda.build(assignments, [], now, seoul).laterCount == 1)
        let result = Agenda.build(assignments, [], nextDay, seoul)
        #expect(result.upcomingCount == 1)
        #expect(result.laterCount == 0)
    }

    @Test func stableIdsDistinguishIdenticalTitlesAndTimesAcrossCourses() {
        let a = asg(1, h(1))
        var b = a
        b.courseId = 2
        let result = Agenda.build([a, b], [MyDeadline(1, a.name, h(1))], now, seoul)
        #expect(Set(future(result).map(\.id)).count == 3)
        let completed = Agenda.build([], [MyDeadline(1, "완료", h(1), done: true)], now, seoul)
        #expect(completed.urgent.isEmpty)
        #expect(completed.upcomingCount == 0)
    }

    @Test func dayLabelsMakeTodayAndTomorrowExplicit() {
        let today = DayDate.of(2026, 11, 23)
        #expect(Agenda.dayLabel(today, today) == "오늘 · 11/23(월)")
        #expect(Agenda.dayLabel(today.plusDays(1), today) == "내일 · 11/24(화)")
        #expect(Agenda.dayLabel(today.plusDays(2), today) == "11/25(수)")
        #expect(!Agenda.dayLabel(today.plusDays(2), today).contains("이번 주"))
    }

    @Test func snapshotCacheRoundTrip() {
        var b = asg(2, nil, submitted: true)
        b.score = 9.5
        b.points = nil
        b.url = nil
        let s = CachedSnapshot(123, [asg(1, h(5)), b])
        #expect(SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(s)) == s)
    }
}
