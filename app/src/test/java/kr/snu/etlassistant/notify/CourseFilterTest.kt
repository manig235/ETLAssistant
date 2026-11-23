package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.TimetableLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 과목별 알림 끄기·숨기기. */
class CourseFilterTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    private fun h(x: Long) = now.plus(Duration.ofHours(x))
    private val algo = CourseRef(1, "2026-2 알고리즘 (001)")
    private val db = CourseRef(2, "데이터베이스")
    private val os = CourseRef(3, "운영체제")
    private fun item(cid: Long, id: Long, due: Instant?, course: String = "과목$cid", score: Double? = null) =
        AssignmentItem(cid, course, id, "과제$id", due, true, 10.0, null, score != null, false, score)
    private fun ann(cid: Long, id: Long) = AnnouncementItem(id, cid, "과목$cid", "공지$id", now.minusSeconds(600), null, "본문")
    private val settings = NotifySettings(quietEnabled = false)
    private val filter = CourseFilter().with(algo, CourseMode.MUTE).with(db, CourseMode.HIDE)

    @Test fun modesAndCounts() {
        assertEquals(CourseMode.MUTE, filter.mode(1))
        assertEquals(CourseMode.HIDE, filter.mode(2))
        assertEquals(CourseMode.SHOW, filter.mode(3))
        assertTrue(filter.muted(2)) // 숨기면 알림도 꺼진다
        assertFalse(filter.hidden(1))
        assertEquals(1, filter.mutedCount)
        assertEquals(1, filter.hiddenCount)
        assertTrue(filter.with(algo, CourseMode.SHOW).with(db, CourseMode.SHOW).isEmpty)
        assertEquals("알림 끈 과목 1개 · 숨긴 과목 1개(시간표에서도 1개)", CourseFilterLogic.summary(filter))
        assertEquals("모든 과목 알림 받는 중", CourseFilterLogic.summary(CourseFilter()))
    }

    @Test fun myDeadlinesAndCustomCoursesAreNeverFiltered() {
        val f = filter.with(CourseRef(MyDeadlines.COURSE_ID, "내 마감"), CourseMode.HIDE).with(CourseRef(-5, "직접"), CourseMode.MUTE)
        assertEquals(filter, f)
        assertFalse(f.muted(MyDeadlines.COURSE_ID))
        assertFalse(f.muted(null))
        val snap = Snapshot(now, listOf(item(MyDeadlines.COURSE_ID, 9, h(2), "내 마감")), emptyList())
        assertEquals(1, f.alertable(snap).assignments.size)
        assertEquals(1, f.visible(snap).assignments.size)
    }

    @Test fun alertableAndVisibleSplit() {
        val snap = Snapshot(now, listOf(item(1, 10, h(2)), item(2, 20, h(2)), item(3, 30, h(2))), listOf(ann(1, 100), ann(2, 200), ann(3, 300)),
            errors = listOf("데이터베이스: 시간 초과", "운영체제: 시간 초과", "공지: 실패"))
        assertEquals(listOf(30L), filter.alertable(snap).assignments.map { it.assignmentId })
        assertEquals(listOf(300L), filter.alertable(snap).announcements.map { it.id })
        val visible = filter.visible(snap)
        assertEquals(listOf(10L, 30L), visible.assignments.map { it.assignmentId })
        assertEquals(listOf(100L, 300L), visible.announcements.map { it.id })
        assertEquals(listOf("운영체제: 시간 초과", "공지: 실패"), visible.errors)
        assertEquals(listOf(10L, 30L), filter.visibleAssignments(snap.assignments).map { it.assignmentId })
    }

    @Test fun mutedCoursesAreRecordedSilentlySoUnmutingDoesNotFlood() {
        val snap = Snapshot(now, listOf(item(1, 10, h(2)), item(3, 30, h(2))), listOf(ann(1, 100), ann(3, 300)))
        val state = NotifyState(announcementBaselineDone = true)
        val plan = CourseMute.plan(snap, state, now, settings, filter, seoul)
        val fired = plan.notices.flatMap { it.firedKeys }
        assertTrue(fired.any { it.startsWith("deadline-m:3:30:") })
        assertTrue("ann:300" in fired)
        assertFalse(fired.any { it.contains(":1:10:") || it == "ann:100" })
        // 끈 과목의 키는 조용히 기록된다.
        assertTrue(plan.silentKeys.any { it.startsWith("deadline-m:1:10:") })
        assertTrue("ann:100" in plan.silentKeys)

        // 기록한 뒤 다시 켜면 같은 시점의 알림은 다시 오지 않는다.
        plan.notices.flatMap { it.firedKeys }.forEach { state.fired[it] = 0L }
        plan.silentKeys.forEach { state.fired[it] = 0L }
        val again = NotificationPlanner.plan(snap, state, now, settings, seoul)
        assertTrue(again.notices.isEmpty())
    }

    @Test fun noFilterMatchesPlainPlanner() {
        val snap = Snapshot(now, listOf(item(1, 10, h(2))), listOf(ann(1, 100)))
        val state = NotifyState(announcementBaselineDone = true)
        assertEquals(NotificationPlanner.plan(snap, state, now, settings, seoul), CourseMute.plan(snap, state, now, settings, CourseFilter(), seoul))
    }

    @Test fun watchSkipsMutedButKeepsRecords() {
        val (s0, _) = AssignmentWatch.diff(WatchState(), Snapshot(now, listOf(item(1, 10, h(5)), item(3, 30, h(5))), emptyList()), now)
        val next = Snapshot(now, listOf(item(1, 10, h(5), score = 9.0), item(1, 11, h(9)), item(3, 31, h(9))), emptyList())
        val (s1, n) = AssignmentWatch.diff(s0, next, now, filter::muted)
        assertEquals(listOf("새 과제 · 과목3"), n.map { it.title })
        assertTrue("1:11" in s1.records)
        assertEquals(9.0, s1.records.getValue("1:10").score!!, 0.0)
        // 다시 켜도 그동안의 변경은 오지 않는다.
        assertTrue(AssignmentWatch.diff(s1, next, now).second.isEmpty())
    }

    @Test fun classAlertsSkipMutedByIdOrName() {
        val day = TimetableLogic.Day(LocalDate.of(2026, 11, 19), "목", listOf(
            TimetableLogic.ClassRow("13:00", "14:15", "알고리즘", 1, null, "정규"),
            TimetableLogic.ClassRow("15:00", "16:15", "운영체제", 3, null, "정규"),
            TimetableLogic.ClassRow("17:00", "18:00", "알고리즘", null, null, "보강"),
            TimetableLogic.ClassRow("19:00", "20:00", "내 스터디", -7, null, "정규"),
        ), emptyList())
        assertEquals(listOf("15:00", "19:00"), filter.alertDay(day).classes.map { it.start })
        assertEquals(day, CourseFilter().alertDay(day))
    }

    @Test fun jsonRoundTripAndRows() {
        val known = listOf(algo, os)
        val stored = CourseFilterLogic.fromJson(CourseFilterLogic.toJson(filter, known, 1234L))
        assertEquals(filter, stored.filter)
        assertEquals(known, stored.known)
        assertEquals(1234L, stored.knownAt)
        assertEquals(CourseFilter(), CourseFilterLogic.fromJson("not json").filter)

        val rows = CourseFilterLogic.rows(known, emptyList(), filter)
        assertEquals(listOf(1L, 3L, 2L), rows.map { it.course.id })
        assertEquals(listOf(CourseMode.MUTE, CourseMode.SHOW, CourseMode.HIDE), rows.map { it.mode })
        assertEquals(listOf(false, false, true), rows.map { it.stale }) // 목록에 없는 숨긴 과목도 되돌릴 수 있게 보인다

        val fallback = CourseFilterLogic.fallbackCourses(
            listOf(item(3, 1, h(1), "운영체제"), item(MyDeadlines.COURSE_ID, 2, h(1), "내 마감")),
            listOf(TimetableLogic.CourseMeetings(1, "알고리즘", emptyList()), TimetableLogic.CourseMeetings(-3, "직접", emptyList())),
        )
        assertEquals(listOf(1L, 3L), fallback.map { it.id })
        assertEquals(listOf(1L, 3L, 2L), CourseFilterLogic.rows(emptyList(), fallback, filter).map { it.course.id })
    }
    // ---- 시간표에서도 숨기기 ------------------------------------------------------

    private fun row(start: String, course: String, id: Long?, kind: String = "정규") = TimetableLogic.ClassRow(start, "23:00", course, id, null, kind)

    @Test fun newlyHiddenCoursesHideFromTimetableByDefault() {
        assertTrue(filter.hidesTimetable(2))
        assertFalse(filter.hidesTimetable(1)) // 알림만 끈 과목
        assertFalse(filter.hidesTimetable(3))
        assertEquals(1, filter.timetableHiddenCount)
        assertEquals("알림 끈 과목 1개 · 숨긴 과목 1개(시간표에서도 1개)", CourseFilterLogic.summary(filter))
        // 끄기
        val off = filter.withTimetable(2, false)
        assertFalse(off.hidesTimetable(2))
        assertTrue(off.hidden(2))
        assertEquals("알림 끈 과목 1개 · 숨긴 과목 1개", CourseFilterLogic.summary(off))
        // 이미 숨긴 과목을 다시 숨기기로 골라도 고른 값은 그대로
        assertFalse(off.with(db, CourseMode.HIDE).hidesTimetable(2))
        // 숨기기를 풀면 플래그도 지워지고, 다시 숨기면 기본(켬)부터
        assertTrue(off.with(db, CourseMode.MUTE).hideTimetable.isEmpty())
        assertTrue(off.with(db, CourseMode.MUTE).with(db, CourseMode.HIDE).hidesTimetable(2))
        assertFalse(filter.with(os, CourseMode.HIDE, hideFromTimetable = false).hidesTimetable(3))
        // 숨긴 과목이 아니면 플래그를 켤 수 없다
        assertEquals(filter, filter.withTimetable(1, true))
        assertEquals(filter, filter.withTimetable(3, true))
    }

    @Test fun timetableDayFiltersByIdAndName() {
        val day = TimetableLogic.Day(LocalDate.of(2026, 11, 19), "목", listOf(
            row("09:00", "데이터베이스", 2),
            row("10:00", "알고리즘", 1),
            row("11:00", "데이터베이스", null, "보강"), // 과목명으로만 넣은 보강
            row("12:00", "내 스터디", -2), // 직접 추가한 수업은 대상 아님
            row("13:00", "운영체제", 3),
        ), listOf(row("15:00", "데이터베이스", 2), row("16:00", "운영체제", 3)))
        val shown = filter.timetableDay(day)
        assertEquals(listOf("10:00", "12:00", "13:00"), shown.classes.map { it.start })
        assertEquals(listOf("16:00"), shown.cancelled.map { it.start })
        // 플래그를 끄면 숨긴 과목도 시간표에는 그대로
        assertEquals(day, filter.withTimetable(2, false).timetableDay(day))
        assertEquals(day, CourseFilter().timetableDay(day))
    }

    @Test fun timetableWeeklyAndChanges() {
        val weekly = TimetableLogic.Weekly("1,2,3", 0L, listOf(
            TimetableLogic.CourseMeetings(1, "2026-2 알고리즘 (001)", listOf(TimetableLogic.Meeting("월", "09:00", "10:15", null))),
            TimetableLogic.CourseMeetings(2, "데이터베이스", listOf(TimetableLogic.Meeting("화", "09:00", "10:15", null))),
            TimetableLogic.CourseMeetings(-2, "데이터베이스 스터디", listOf(TimetableLogic.Meeting("수", "09:00", "10:15", null))),
        ), emptyList())
        assertEquals(listOf(1L, -2L), filter.timetableWeekly(weekly)!!.courses.map { it.courseId })
        val d = LocalDate.of(2026, 11, 20)
        val changes = listOf(
            TimetableLogic.Change(1, d, "휴강", "데이터베이스", courseId = 2),
            TimetableLogic.Change(2, d, "보강", "데이터베이스", start = "18:00", end = "19:00"), // 이름만, 숨긴 과목
            TimetableLogic.Change(3, d, "보강", "알고리즘", start = "18:00", end = "19:00"),
            TimetableLogic.Change(4, d, "휴강", "알고리즘", courseId = 1),
        )
        assertEquals(listOf(3L, 4L), filter.timetableChanges(changes, weekly).map { it.id })
        assertEquals(changes, filter.withTimetable(2, false).timetableChanges(changes, weekly))
        // 실제 하루 계산과 같은 결과: 숨긴 과목의 보강·휴강이 시간표에서 빠진다.
        val day = filter.timetableDay(TimetableLogic.day(weekly, d, changes))
        assertTrue(day.classes.none { it.course == "데이터베이스" })
    }

    @Test fun oldStoredHiddenCoursesKeepTimetable() {
        // 플래그가 생기기 전의 저장값: 숨긴 과목도 시간표에 보인다.
        val old = """{"version":1,"courses":{"2":{"mode":"hide","name":"데이터베이스"},"1":{"mode":"mute","name":"알고리즘"}},"known":[],"knownAt":0}"""
        val f = CourseFilterLogic.fromJson(old).filter
        assertTrue(f.hidden(2))
        assertFalse(f.hidesTimetable(2))
        assertEquals(0, f.timetableHiddenCount)
        // 저장하고 다시 읽어도 그대로, 켠 값도 보존
        assertEquals(f, CourseFilterLogic.fromJson(CourseFilterLogic.toJson(f, emptyList(), 0L)).filter)
        val on = f.withTimetable(2, true)
        assertTrue(CourseFilterLogic.fromJson(CourseFilterLogic.toJson(on, emptyList(), 0L)).filter.hidesTimetable(2))
    }

    @Test fun rowsAreSortedByNameAndKeepShownOrderOnRefresh() {
        val cached = listOf(os, algo) // eTL 순서
        val first = CourseFilterLogic.rows(cached, emptyList(), CourseFilter())
        assertEquals(listOf(1L, 3L), first.map { it.course.id }) // 과목명순(학기 접두어 무시)
        val fresh = CourseFilterLogic.rows(listOf(os, CourseRef(4, "가나다"), algo, db), emptyList(), CourseFilter())
        assertEquals(listOf(4L, 2L, 1L, 3L), fresh.map { it.course.id })
        // 보는 중에 새로 받으면 보이던 줄은 그대로, 새 과목은 끝에
        assertEquals(listOf(1L, 3L, 4L, 2L), CourseFilterLogic.keepOrder(first.map { it.course.id }, fresh).map { it.course.id })
        assertEquals(fresh, CourseFilterLogic.keepOrder(emptyList(), fresh))
    }
}
