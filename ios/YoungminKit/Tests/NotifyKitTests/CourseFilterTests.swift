import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 과목별 알림 끄기·숨기기.
@Suite struct CourseFilterTests {
    let now = instant("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    func h(_ x: Double) -> Date { now.h(x) }
    let algo = CourseRef(1, "2026-2 알고리즘 (001)")
    let db = CourseRef(2, "데이터베이스")
    let os = CourseRef(3, "운영체제")
    func item(_ cid: Int64, _ id: Int64, _ due: Date?, _ course: String? = nil, score: Double? = nil) -> AssignmentItem {
        AssignmentItem(cid, course ?? "과목\(cid)", id, "과제\(id)", due, true, 10.0, nil, score != nil, false, score)
    }
    func ann(_ cid: Int64, _ id: Int64) -> AnnouncementItem { AnnouncementItem(id, cid, "과목\(cid)", "공지\(id)", now.secs(-600), nil, "본문") }
    let settings = NotifySettings(quietEnabled: false)
    var filter: CourseFilter { CourseFilter().with(algo, .MUTE).with(db, .HIDE) }

    @Test func modesAndCounts() {
        #expect(filter.mode(1) == .MUTE)
        #expect(filter.mode(2) == .HIDE)
        #expect(filter.mode(3) == .SHOW)
        #expect(filter.muted(2)) // 숨기면 알림도 꺼진다
        #expect(!filter.hidden(1))
        #expect(filter.mutedCount == 1)
        #expect(filter.hiddenCount == 1)
        #expect(filter.with(algo, .SHOW).with(db, .SHOW).isEmpty)
        #expect(CourseFilterLogic.summary(filter) == "알림 끈 과목 1개 · 숨긴 과목 1개(시간표에서도 1개)")
        #expect(CourseFilterLogic.summary(CourseFilter()) == "모든 과목 알림 받는 중")
    }

    @Test func myDeadlinesAndCustomCoursesAreNeverFiltered() {
        let f = filter.with(CourseRef(MyDeadlines.COURSE_ID, "내 마감"), .HIDE).with(CourseRef(-5, "직접"), .MUTE)
        #expect(f == filter)
        #expect(!f.muted(MyDeadlines.COURSE_ID))
        #expect(!f.muted(nil))
        let snap = Snapshot(now, [item(MyDeadlines.COURSE_ID, 9, h(2), "내 마감")], [])
        #expect(f.alertable(snap).assignments.count == 1)
        #expect(f.visible(snap).assignments.count == 1)
    }

    @Test func alertableAndVisibleSplit() {
        let snap = Snapshot(now, [item(1, 10, h(2)), item(2, 20, h(2)), item(3, 30, h(2))], [ann(1, 100), ann(2, 200), ann(3, 300)],
                            errors: ["데이터베이스: 시간 초과", "운영체제: 시간 초과", "공지: 실패"])
        #expect(filter.alertable(snap).assignments.map(\.assignmentId) == [30])
        #expect(filter.alertable(snap).announcements.map(\.id) == [300])
        let visible = filter.visible(snap)
        #expect(visible.assignments.map(\.assignmentId) == [10, 30])
        #expect(visible.announcements.map(\.id) == [100, 300])
        #expect(visible.errors == ["운영체제: 시간 초과", "공지: 실패"])
        #expect(filter.visibleAssignments(snap.assignments).map(\.assignmentId) == [10, 30])
    }

    @Test func mutedCoursesAreRecordedSilentlySoUnmutingDoesNotFlood() {
        let snap = Snapshot(now, [item(1, 10, h(2)), item(3, 30, h(2))], [ann(1, 100), ann(3, 300)])
        var state = NotifyState(announcementBaselineDone: true)
        let plan = CourseMute.plan(snap, state, now, settings, filter, seoul)
        let fired = plan.notices.flatMap(\.firedKeys)
        #expect(fired.contains { $0.hasPrefix("deadline-m:3:30:") })
        #expect(fired.contains("ann:300"))
        #expect(!fired.contains { $0.contains(":1:10:") || $0 == "ann:100" })
        // 끈 과목의 키는 조용히 기록된다.
        #expect(plan.silentKeys.contains { $0.hasPrefix("deadline-m:1:10:") })
        #expect(plan.silentKeys.contains("ann:100"))

        // 기록한 뒤 다시 켜면 같은 시점의 알림은 다시 오지 않는다.
        for k in plan.notices.flatMap(\.firedKeys) { state.fired[k] = 0 }
        for k in plan.silentKeys { state.fired[k] = 0 }
        let again = NotificationPlanner.plan(snap, state, now, settings, seoul)
        #expect(again.notices.isEmpty)
    }

    @Test func noFilterMatchesPlainPlanner() {
        let snap = Snapshot(now, [item(1, 10, h(2))], [ann(1, 100)])
        let state = NotifyState(announcementBaselineDone: true)
        #expect(NotificationPlanner.plan(snap, state, now, settings, seoul) == CourseMute.plan(snap, state, now, settings, CourseFilter(), seoul))
    }

    @Test func watchSkipsMutedButKeepsRecords() {
        let (s0, _) = AssignmentWatch.diff(WatchState(), Snapshot(now, [item(1, 10, h(5)), item(3, 30, h(5))], []), now)
        let next = Snapshot(now, [item(1, 10, h(5), score: 9.0), item(1, 11, h(9)), item(3, 31, h(9))], [])
        let f = filter
        let (s1, n) = AssignmentWatch.diff(s0, next, now, { f.muted($0) })
        #expect(n.map(\.title) == ["새 과제 · 과목3"])
        #expect(s1.records["1:11"] != nil)
        #expect(s1.records["1:10"]!.score == 9.0)
        // 다시 켜도 그동안의 변경은 오지 않는다.
        #expect(AssignmentWatch.diff(s1, next, now).1.isEmpty)
    }

    @Test func classAlertsSkipMutedByIdOrName() {
        let day = TimetableLogic.Day(DayDate.of(2026, 11, 19), "목", [
            TimetableLogic.ClassRow("13:00", "14:15", "알고리즘", 1, nil, "정규"),
            TimetableLogic.ClassRow("15:00", "16:15", "운영체제", 3, nil, "정규"),
            TimetableLogic.ClassRow("17:00", "18:00", "알고리즘", nil, nil, "보강"),
            TimetableLogic.ClassRow("19:00", "20:00", "내 스터디", -7, nil, "정규"),
        ], [])
        #expect(filter.alertDay(day).classes.map(\.start) == ["15:00", "19:00"])
        #expect(CourseFilter().alertDay(day) == day)
    }

    @Test func jsonRoundTripAndRows() {
        let known = [algo, os]
        let stored = CourseFilterLogic.fromJson(CourseFilterLogic.toJson(filter, known, 1234))
        #expect(stored.filter == filter)
        #expect(stored.known == known)
        #expect(stored.knownAt == 1234)
        #expect(CourseFilterLogic.fromJson("not json").filter == CourseFilter())

        let rows = CourseFilterLogic.rows(known, [], filter)
        #expect(rows.map(\.course.id) == [1, 3, 2])
        #expect(rows.map(\.mode) == [.MUTE, .SHOW, .HIDE])
        #expect(rows.map(\.stale) == [false, false, true]) // 목록에 없는 숨긴 과목도 되돌릴 수 있게 보인다

        let fallback = CourseFilterLogic.fallbackCourses(
            [item(3, 1, h(1), "운영체제"), item(MyDeadlines.COURSE_ID, 2, h(1), "내 마감")],
            [TimetableLogic.CourseMeetings(1, "알고리즘", []), TimetableLogic.CourseMeetings(-3, "직접", [])]
        )
        #expect(fallback.map(\.id) == [1, 3])
        #expect(CourseFilterLogic.rows([], fallback, filter).map(\.course.id) == [1, 3, 2])
    }

    // ---- 시간표에서도 숨기기 ------------------------------------------------------

    func row(_ start: String, _ course: String, _ id: Int64?, _ kind: String = "정규") -> TimetableLogic.ClassRow {
        TimetableLogic.ClassRow(start, "23:00", course, id, nil, kind)
    }

    @Test func newlyHiddenCoursesHideFromTimetableByDefault() {
        #expect(filter.hidesTimetable(2))
        #expect(!filter.hidesTimetable(1)) // 알림만 끈 과목
        #expect(!filter.hidesTimetable(3))
        #expect(filter.timetableHiddenCount == 1)
        #expect(CourseFilterLogic.summary(filter) == "알림 끈 과목 1개 · 숨긴 과목 1개(시간표에서도 1개)")
        // 끄기
        let off = filter.withTimetable(2, false)
        #expect(!off.hidesTimetable(2))
        #expect(off.hidden(2))
        #expect(CourseFilterLogic.summary(off) == "알림 끈 과목 1개 · 숨긴 과목 1개")
        // 이미 숨긴 과목을 다시 숨기기로 골라도 고른 값은 그대로
        #expect(!off.with(db, .HIDE).hidesTimetable(2))
        // 숨기기를 풀면 플래그도 지워지고, 다시 숨기면 기본(켬)부터
        #expect(off.with(db, .MUTE).hideTimetable.isEmpty)
        #expect(off.with(db, .MUTE).with(db, .HIDE).hidesTimetable(2))
        #expect(!filter.with(os, .HIDE, hideFromTimetable: false).hidesTimetable(3))
        // 숨긴 과목이 아니면 플래그를 켤 수 없다
        #expect(filter.withTimetable(1, true) == filter)
        #expect(filter.withTimetable(3, true) == filter)
    }

    @Test func timetableDayFiltersByIdAndName() {
        let day = TimetableLogic.Day(DayDate.of(2026, 11, 19), "목", [
            row("09:00", "데이터베이스", 2),
            row("10:00", "알고리즘", 1),
            row("11:00", "데이터베이스", nil, "보강"), // 과목명으로만 넣은 보강
            row("12:00", "내 스터디", -2), // 직접 추가한 수업은 대상 아님
            row("13:00", "운영체제", 3),
        ], [row("15:00", "데이터베이스", 2), row("16:00", "운영체제", 3)])
        let shown = filter.timetableDay(day)
        #expect(shown.classes.map(\.start) == ["10:00", "12:00", "13:00"])
        #expect(shown.cancelled.map(\.start) == ["16:00"])
        // 플래그를 끄면 숨긴 과목도 시간표에는 그대로
        #expect(filter.withTimetable(2, false).timetableDay(day) == day)
        #expect(CourseFilter().timetableDay(day) == day)
    }

    @Test func timetableWeeklyAndChanges() {
        let weekly = TimetableLogic.Weekly("1,2,3", 0, [
            TimetableLogic.CourseMeetings(1, "2026-2 알고리즘 (001)", [TimetableLogic.Meeting("월", "09:00", "10:15", nil)]),
            TimetableLogic.CourseMeetings(2, "데이터베이스", [TimetableLogic.Meeting("화", "09:00", "10:15", nil)]),
            TimetableLogic.CourseMeetings(-2, "데이터베이스 스터디", [TimetableLogic.Meeting("수", "09:00", "10:15", nil)]),
        ], [])
        #expect(filter.timetableWeekly(weekly)!.courses.map(\.courseId) == [1, -2])
        let dd = DayDate.of(2026, 11, 20)
        let changes = [
            TimetableLogic.Change(1, dd, "휴강", "데이터베이스", courseId: 2),
            TimetableLogic.Change(2, dd, "보강", "데이터베이스", start: "18:00", end: "19:00"), // 이름만, 숨긴 과목
            TimetableLogic.Change(3, dd, "보강", "알고리즘", start: "18:00", end: "19:00"),
            TimetableLogic.Change(4, dd, "휴강", "알고리즘", courseId: 1),
        ]
        #expect(filter.timetableChanges(changes, weekly).map(\.id) == [3, 4])
        #expect(filter.withTimetable(2, false).timetableChanges(changes, weekly) == changes)
        // 실제 하루 계산과 같은 결과: 숨긴 과목의 보강·휴강이 시간표에서 빠진다.
        let day = filter.timetableDay(TimetableLogic.day(weekly, dd, changes))
        #expect(!day.classes.contains { $0.course == "데이터베이스" })
    }

    @Test func oldStoredHiddenCoursesKeepTimetable() {
        // 플래그가 생기기 전의 저장값: 숨긴 과목도 시간표에 보인다.
        let old = #"{"version":1,"courses":{"2":{"mode":"hide","name":"데이터베이스"},"1":{"mode":"mute","name":"알고리즘"}},"known":[],"knownAt":0}"#
        let f = CourseFilterLogic.fromJson(old).filter
        #expect(f.hidden(2))
        #expect(!f.hidesTimetable(2))
        #expect(f.timetableHiddenCount == 0)
        // 저장하고 다시 읽어도 그대로, 켠 값도 보존
        #expect(CourseFilterLogic.fromJson(CourseFilterLogic.toJson(f, [], 0)).filter == f)
        let on = f.withTimetable(2, true)
        #expect(CourseFilterLogic.fromJson(CourseFilterLogic.toJson(on, [], 0)).filter.hidesTimetable(2))
    }

    @Test func rowsAreSortedByNameAndKeepShownOrderOnRefresh() {
        let cached = [os, algo] // eTL 순서
        let first = CourseFilterLogic.rows(cached, [], CourseFilter())
        #expect(first.map(\.course.id) == [1, 3]) // 과목명순(학기 접두어 무시)
        let fresh = CourseFilterLogic.rows([os, CourseRef(4, "가나다"), algo, db], [], CourseFilter())
        #expect(fresh.map(\.course.id) == [4, 2, 1, 3])
        // 보는 중에 새로 받으면 보이던 줄은 그대로, 새 과목은 끝에
        #expect(CourseFilterLogic.keepOrder(first.map(\.course.id), fresh).map(\.course.id) == [1, 3, 4, 2])
        #expect(CourseFilterLogic.keepOrder([], fresh) == fresh)
    }
}
