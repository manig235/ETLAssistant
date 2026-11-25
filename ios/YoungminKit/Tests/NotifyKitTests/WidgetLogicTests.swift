import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// Kotlin `widget/WidgetLogicTest.kt` + iOS 위젯 사본(App Group) 규칙.
struct WidgetLogicTests {
    let now = instant("2026-11-23T03:00:00Z") // KST 월요일 12:00

    func assignment(_ id: Int64, _ due: Date?, submitted: Bool = false, url: String? = nil) -> AssignmentItem {
        AssignmentItem(10, "2026-2 수업 (001)", id, "과제", due, true, nil, url, submitted, false)
    }

    @Test func mergesByDeadlineWithoutSevenDayCutoffAndUsesStableIds() {
        let assignments = [assignment(1, now.secs(20 * 86400)), assignment(2, now.secs(86400))]
        let mine = [MyDeadline(2, "내 마감", now.secs(3600))]
        let items = DeadlineWidgetLogic.items(assignments, mine, now)
        #expect(items.map(\.key) == ["mine-2", "etl-10-2", "etl-10-1"])
        #expect(items[1].source == "수업")
    }

    @Test func excludesSubmittedCompletedUndatedAndExpiredIncludingExactDeadline() {
        let assignments = [assignment(1, nil), assignment(2, now), assignment(3, now.secs(-1)),
                           assignment(4, now.secs(60), submitted: true), assignment(5, now.secs(120))]
        let mine = [MyDeadline(1, "완료", now.secs(60), done: true), MyDeadline(2, "지난 일", now)]
        #expect(DeadlineWidgetLogic.items(assignments, mine, now).map(\.key) == ["etl-10-5"])
    }

    @Test func externalOrUnsafeLinksFallBackToTheEtlAssignment() {
        for url in [nil, "https://other.example/course", "javascript:alert(1)",
                    "https://myetl.snu.ac.kr.evil.example/", "https://user@myetl.snu.ac.kr/", "http://myetl.snu.ac.kr/"] as [String?] {
            let item = DeadlineWidgetLogic.items([assignment(9, now.secs(60), url: url)], [], now).first!
            #expect(item.url == "https://myetl.snu.ac.kr/courses/10/assignments/9")
        }
        let link = "https://myetl.snu.ac.kr/courses/10/assignments/9?module_item_id=5"
        #expect(DeadlineWidgetLogic.items([assignment(9, now.secs(60), url: link)], [], now).first!.url == link)
    }

    @Test func fillsAvailableHeightWithoutAnItemCapAndRespectsLargerFonts() {
        #expect(DeadlineWidgetLogic.capacity(110, 1) == 1)
        #expect(DeadlineWidgetLogic.capacity(160, 1) == 2)
        #expect(DeadlineWidgetLogic.capacity(250, 1) == 4)
        #expect(DeadlineWidgetLogic.capacity(1000, 1) > 16)
        #expect(DeadlineWidgetLogic.capacity(250, 1.5) < DeadlineWidgetLogic.capacity(250, 1))
        #expect(DeadlineWidgetLogic.capacity(40, 2) == 1)
    }

    @Test func compactRowsUseTwoLinesAndGrowWithAvailableSpace() {
        #expect(DeadlineWidgetLogic.capacity(40, 1, compact: true) == 1)
        #expect(DeadlineWidgetLogic.capacity(110, 1, compact: true) == 3)
        #expect(DeadlineWidgetLogic.capacity(250, 1, compact: true) > DeadlineWidgetLogic.capacity(250, 1))
    }

    @Test func labelsFollowKstDatesAndBoundaryDropsExpiredItems() {
        let nearMidnight = instant("2026-11-23T14:59:00Z")
        let due = nearMidnight.secs(120)
        #expect(DeadlineWidgetLogic.dueLabel(due, nearMidnight) == "내일 00:01")
        #expect(DeadlineWidgetLogic.remaining(due, nearMidnight) == "2분 남음")
        let item = DeadlineWidgetLogic.items([assignment(1, now.secs(60))], [], now)
        #expect(DeadlineWidgetLogic.nextBoundary(item, now) == now.secs(60))
        #expect(DeadlineWidgetLogic.items([assignment(1, now.secs(60))], [], now.secs(60)).isEmpty)
        #expect(DeadlineWidgetLogic.nextBoundary([], now) == d("2026-11-24").startOfDay)
    }

    @Test func remainingTextBoundaries() {
        #expect(DeadlineWidgetLogic.remaining(now.secs(59), now) == "곧 마감")
        #expect(DeadlineWidgetLogic.remaining(now.secs(-100), now) == "곧 마감")
        #expect(DeadlineWidgetLogic.remaining(now.secs(3 * 3600 + 12 * 60 + 30), now) == "3시간 12분 남음")
        #expect(DeadlineWidgetLogic.remaining(now.secs(2 * 86400 + 5 * 3600), now) == "2일 5시간 남음")
        #expect(DeadlineWidgetLogic.dueLabel(now.secs(3 * 86400), now) == "11/26 12:00")
        #expect(DeadlineWidgetLogic.dueLabel(now.secs(60), now) == "오늘 12:01")
    }

    @Test func examAndRepeatingItemsCarryCompletionTargets() {
        var exam = MyDeadline(7, "중간고사", now.secs(7200))
        exam.kind = .EXAM
        exam.course = "자료구조"
        exam.location = "301-101"
        let weekly = MyDeadline(8, "주간 퀴즈", d("2026-11-23").atTime(23, 0), repeat: MyRepeat(.WEEKLY))
        let items = DeadlineWidgetLogic.items([], [exam, weekly], now)
        #expect(items.map(\.key) == ["exam-7", "mine-8-2026-11-23"])
        #expect(items[0].title == "[시험] 자료구조 중간고사")
        #expect(items[0].source == "시험 · 301-101")
        #expect(items[0].exam && items[0].mineId == 7)
        #expect(items[1].mineDate == d("2026-11-23") && !items[1].exam)
    }

    // ---- 시간표 ----

    let monday = d("2026-11-23")

    func weekly() -> TimetableLogic.Weekly {
        TimetableLogic.Weekly("10", 1, [
            TimetableLogic.CourseMeetings(10, "정규 수업", [TimetableLogic.Meeting("월", "09:00", "10:00", "301-101")]),
            TimetableLogic.CourseMeetings(-100, "직접 등록", [TimetableLogic.Meeting("수", "10:00", "11:00", "동아리방")]),
        ], [])
    }

    func at(_ time: String, _ days: Int = 0) -> Date {
        let hm = time.split(separator: ":").map { Int($0)! }
        return monday.plusDays(days).atTime(hm[0], hm[1])
    }

    @Test func nextClassStaysUntilThirtyMinutesAfterStartThenAdvances() {
        for time in ["08:59", "09:00", "09:29"] {
            let next = TimetableWidgetLogic.nextClass(weekly(), [], at(time))!
            #expect(next.entry.row.courseId == 10)
            #expect(next.switchesAt == at("09:30"))
        }
        let next = TimetableWidgetLogic.nextClass(weekly(), [], at("09:30"))!
        #expect(next.entry.row.courseId == -100)
        #expect(next.entry.date == monday.plusDays(2))
    }

    @Test func nextClassSkipsCancellationIncludesMakeupAndSwitchesAtEarlyEnd() {
        let changes = [
            TimetableLogic.Change(1, monday, "휴강", "정규 수업", courseId: 10),
            TimetableLogic.Change(2, monday, "보강", "짧은 보강", courseId: nil, start: "09:00", end: "09:20", room: "연습실"),
        ]
        let next = TimetableWidgetLogic.nextClass(weekly(), changes, at("09:19"))!
        #expect(next.entry.row.course == "짧은 보강")
        #expect(next.switchesAt == at("09:20"))
        #expect(TimetableWidgetLogic.nextClass(weekly(), changes, at("09:20"))!.entry.row.courseId == -100)
    }

    @Test func nextClassLooksAcrossWeekBoundaryAndRejectsInvalidTimes() {
        let next = TimetableWidgetLogic.nextClass(weekly(), [], at("23:59", 6))!
        #expect(next.entry.date == monday.plusDays(7))
        #expect(next.startsAt == at("09:00", 7))
        let bad = TimetableLogic.Weekly("bad", 1, [TimetableLogic.CourseMeetings(1, "잘못된 수업", [
            TimetableLogic.Meeting("월", "bad", "10:00", nil),
            TimetableLogic.Meeting("월", "10:00", "09:00", nil),
        ])], [])
        #expect(TimetableWidgetLogic.nextClass(bad, [], at("08:00")) == nil)
        #expect(TimetableWidgetLogic.nextClass(nil, [], now) == nil)
    }

    @Test func currentWeekKeepsCancelledBlockAddsMakeupAndIncludesCustomClasses() {
        let changes = [
            TimetableLogic.Change(1, monday, "휴강", "정규 수업", courseId: 10),
            TimetableLogic.Change(2, monday.plusDays(5), "보강", "정규 수업", courseId: 10, start: "13:00", end: "14:00", room: "302-201"),
        ]
        let week = TimetableWidgetLogic.week(weekly(), changes, monday.plusDays(3))
        #expect(week.monday == monday)
        #expect(week.entries.count == 3)
        #expect(week.entries.filter(\.cancelled).count == 1)
        #expect(week.entries.filter { $0.row.kind == "보강" }.count == 1)
        #expect(week.entries.contains { $0.row.courseId == -100 })
        #expect(week.grid.days.count == 6)
        #expect(week.grid.blocks.count == 3)
    }

    @Test func weekRollsOverOnMondayWithoutCarryingLastWeeksChanges() {
        let changes = [TimetableLogic.Change(1, monday, "휴강", "정규 수업", courseId: 10)]
        let sunday = TimetableWidgetLogic.week(weekly(), changes, monday.plusDays(6))
        let nextMonday = TimetableWidgetLogic.week(weekly(), changes, monday.plusDays(7))
        #expect(sunday.monday == monday)
        #expect(sunday.entries.contains { $0.cancelled })
        #expect(nextMonday.monday == monday.plusDays(7))
        #expect(!nextMonday.entries.contains { $0.cancelled })
        #expect(nextMonday.entries.count == 2)
    }

    @Test func overlappingMakeupClassesReceiveSeparateLanes() {
        let changes = [TimetableLogic.Change(3, monday, "보강", "추가 수업", courseId: nil, start: "09:30", end: "10:30", room: "강의실")]
        let week = TimetableWidgetLogic.week(weekly(), changes, monday)
        let blocks = week.grid.blocks.filter { $0.meeting.day == "월" }
        #expect(blocks.count == 2)
        #expect(blocks.allSatisfy { $0.lanes == 2 })
        #expect(Set(blocks.map(\.lane)).count == 2)
    }

    @Test func hiddenTimetableCoursesAreLeftOutOfWeekAndNextClass() {
        let filter = CourseFilter([10: .HIDE], [10: "정규 수업"], [10])
        let week = TimetableWidgetLogic.week(weekly(), [], monday, filter)
        #expect(week.entries.map { $0.row.courseId } == [-100])
        #expect(TimetableWidgetLogic.nextClass(weekly(), [], at("08:00"), filter)?.entry.row.courseId == -100)
    }

    @Test func textHelpersAndColorsMatchTheApp() {
        let next = TimetableWidgetLogic.nextClass(weekly(), [], at("08:00"))!
        #expect(WidgetText.nextClassDetail(next, at("08:00")) == "오늘 09:00 · 301-101")
        let custom = TimetableWidgetLogic.nextClass(weekly(), [], at("09:30"))!
        #expect(WidgetText.nextClassDetail(custom, at("09:30")) == "수요일 10:00 · 동아리방")
        #expect(WidgetText.nextClassDetail(custom, at("09:30", 1)) == "내일 10:00 · 동아리방")
        #expect(WidgetText.room("  미정 ") == nil && WidgetText.room("강의실 미정") == nil && WidgetText.room("301-1") == "301-1")
        // Kotlin: 10L.hashCode() = 10, (-100L).hashCode() = 99, "abc".hashCode() = 96354.
        #expect(WidgetText.colorIndex(10, "", 9) == 1)
        #expect(WidgetText.colorIndex(-100, "", 9) == 0)
        #expect(WidgetText.colorIndex(nil, "abc", 9) == 96354 % 9)
    }

    // ---- App Group 사본 ----

    @Test func snapshotRoundTripsAndDropsStaleData() {
        var hidden = assignment(3, now.secs(600))
        hidden.courseId = 20
        let filter = CourseFilter([20: .HIDE], [20: "숨긴 과목"])
        let cached = CachedSnapshot(now.secs(-60).epochMilli, [assignment(1, now.secs(-1)), assignment(2, now.secs(600)), hidden,
                                                                assignment(4, now.secs(900), submitted: true)])
        let changes = [TimetableLogic.Change(1, monday.minusDays(1), "휴강", "정규 수업", courseId: 10),
                       TimetableLogic.Change(2, monday, "휴강", "정규 수업", courseId: 10)]
        let mine = [MyDeadline(1, "끝남", now.secs(60), done: true), MyDeadline(2, "할 일", now.secs(60))]
        let s = WidgetSnapshot.make(snapshot: cached, mine: mine, weekly: weekly(), changes: changes, filter: filter, now: now)
        #expect(s.assignments.map(\.assignmentId) == [2])
        #expect(s.changes.map(\.id) == [2])
        #expect(s.myDeadlines.map(\.id) == [2])
        #expect(s.assignmentsTakenAt == now.secs(-60))
        let back = WidgetSnapshot.decode(s.encoded())
        #expect(back == s)
        #expect(back?.deadlineItems(now).map(\.key) == ["mine-2", "etl-10-2"])
        #expect(WidgetSnapshot.decode(Data("{}".utf8)) == nil)
        #expect(WidgetSnapshot.make(snapshot: nil, mine: [], weekly: nil, changes: [], filter: CourseFilter(), now: now).assignmentsTakenAt == nil)
    }

    @Test func completionMarksOnlyThatOccurrenceAndNeverUndoes() {
        let repeating = MyDeadline(8, "주간 퀴즈", d("2026-11-23").atTime(23, 0), repeat: MyRepeat(.WEEKLY))
        let once = MyDeadline(9, "보고서", now.secs(3600))
        let s = WidgetSnapshot.make(snapshot: nil, mine: [repeating, once], weekly: nil, changes: [], filter: CourseFilter(), now: now)
        let done = s.markingDone(8, d("2026-11-23"), now).markingDone(9, nil, now)
        #expect(done.deadlineItems(now).map(\.key) == ["mine-8-2026-11-30"])
        #expect(done.markingDone(9, nil, now) == done)

        let c1 = WidgetCompletion(id: 9, occurrence: nil, at: now)
        let c2 = WidgetCompletion(id: 8, occurrence: d("2026-11-23"), at: now)
        #expect(WidgetCompletion.decode(c1.encoded()) == c1)
        let changed = WidgetCompletion.apply([c1, c1, c2, WidgetCompletion(id: 404, occurrence: nil, at: now)], to: [repeating, once])
        #expect(Set(changed.map(\.id)) == [8, 9])
        #expect(changed.first { $0.id == 9 }!.done)
        #expect(changed.first { $0.id == 8 }!.doneDates == [d("2026-11-23")])
        #expect(WidgetCompletion.apply([c1], to: [MyDeadlines.setDone(once, nil, true, now)]).isEmpty)
    }

    @Test func timelineCoversTicksDeadlinesClassSwitchesAndMidnight() {
        let s = WidgetSnapshot.make(snapshot: CachedSnapshot(now.epochMilli, [assignment(1, at("13:07"))]), mine: [], weekly: weekly(),
                                    changes: [], filter: CourseFilter(), now: at("08:50"))
        let dates = WidgetTimelineLogic.entryDates(s, at("08:50"), horizon: 6 * 3600)
        #expect(dates.first == at("08:50"))
        #expect(dates.contains(at("09:05")))
        #expect(dates.contains(at("09:30")))
        #expect(dates.contains(at("13:07")))
        #expect(dates == dates.sorted())
        #expect(dates.last! <= at("14:50"))
        let late = WidgetTimelineLogic.entryDates(nil, at("22:00"), horizon: 3 * 3600)
        #expect(late.contains(at("00:00", 1)))
        #expect(late.count == 13)
    }
}
