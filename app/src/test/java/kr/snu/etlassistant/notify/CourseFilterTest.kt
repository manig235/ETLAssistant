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
        assertEquals("알림 끈 과목 1개 · 숨긴 과목 1개", CourseFilterLogic.summary(filter))
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
}
