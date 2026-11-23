package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AgendaTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    private fun h(x: Long) = now.plus(Duration.ofHours(x))
    private fun asg(id: Long, due: Instant?, submitted: Boolean = false, missing: Boolean = false) =
        AssignmentItem(1, "2026-2 자료구조 (001)", id, "과제$id", due, true, 10.0, "https://myetl.snu.ac.kr/courses/1/assignments/$id", submitted, missing)
    private fun Agenda.Schedule.future() = (upcoming + later).flatMap { it.rows }

    @Test fun sevenDaysIncludesTodayTomorrowAndDaysAcrossCalendarWeek() {
        val assignments = listOf(asg(1, h(5)), asg(2, h(20)), asg(3, h(60)), asg(4, h(24 * 6)), asg(5, h(24 * 20)))
        val mine = listOf(MyDeadline(100, "장학금 서류", h(30), "행정실"))
        val result = Agenda.build(assignments.reversed(), mine, now, seoul)

        assertEquals(LocalDate.of(2026, 11, 19), result.today)
        assertEquals(LocalDate.of(2026, 11, 25), result.lastDay)
        assertEquals(listOf("과제1", "과제2", "장학금 서류", "과제3", "과제4"), result.upcoming.flatMap { it.rows }.map { it.title })
        assertEquals(5, result.upcomingCount)
        assertEquals(listOf("과제5"), result.later.flatMap { it.rows }.map { it.title })
        assertEquals(1, result.laterCount)
        assertEquals(listOf("과제1", "과제2"), result.urgent.map { it.title })
        assertTrue(result.urgent.all { it in result.future() })
    }

    @Test fun sevenDayBoundaryUsesLocalMidnightAndIncludesEntireLastDay() {
        val boundary = LocalDate.of(2026, 11, 26).atStartOfDay(seoul).toInstant()
        val result = Agenda.build(listOf(asg(1, boundary.minusNanos(1)), asg(2, boundary)), emptyList(), now, seoul)
        assertEquals(listOf("과제1"), result.upcoming.flatMap { it.rows }.map { it.title })
        assertEquals(listOf("과제2"), result.later.flatMap { it.rows }.map { it.title })
    }

    @Test fun urgentIncludesExactly24HoursButExcludesExpiredAndCompleted() {
        val result = Agenda.build(listOf(
            asg(1, now, missing = true), asg(2, now.plusNanos(1)), asg(3, h(24)),
            asg(4, h(24).plusNanos(1)), asg(5, h(1), submitted = true),
        ), listOf(MyDeadline(10, "완료", h(2), done = true), MyDeadline(11, "미완료", h(3))), now, seoul, hideDone = false)
        assertEquals(listOf("과제2", "미완료", "과제3"), result.urgent.map { it.title })
        assertEquals(listOf("과제1"), result.overdue.map { it.title })
        assertEquals(6, result.upcomingCount)
        assertTrue(result.urgent.all { it in result.future() })
    }

    @Test fun overdueAndCompletedVisibilityIsPreserved() {
        val assignments = listOf(
            asg(1, h(-5), missing = true), asg(2, h(-50), submitted = true),
            asg(3, h(-1)), asg(4, h(10), submitted = true), asg(5, null),
        )
        val mine = listOf(MyDeadline(100, "미완료", h(-1)), MyDeadline(101, "끝난 일", h(-3), done = true))
        val result = Agenda.build(assignments, mine, now, seoul)
        assertEquals(listOf("과제1", "미완료"), result.overdue.map { it.title })
        assertTrue(result.future().isEmpty())
        val shown = Agenda.build(assignments, mine, now, seoul, hideDone = false)
        assertEquals(listOf("과제4"), shown.future().map { it.title })
        assertTrue(shown.future().single().done)
        assertTrue(shown.urgent.isEmpty())
    }

    @Test fun filtersApplyToSummaryOverdueAndBothFutureSections() {
        val assignments = listOf(asg(1, h(1)), asg(2, h(200)), asg(3, h(-1), missing = true))
        val mine = listOf(MyDeadline(1, "곧", h(2)), MyDeadline(2, "나중", h(201)), MyDeadline(3, "지남", h(-2)))
        for ((filter, kind) in listOf(Agenda.Filter.ASSIGNMENT to Agenda.Kind.ASSIGNMENT, Agenda.Filter.MINE to Agenda.Kind.MINE)) {
            val result = Agenda.build(assignments, mine, now, seoul, filter)
            assertEquals(1, result.urgent.size)
            assertEquals(1, result.overdue.size)
            assertEquals(1, result.upcomingCount)
            assertEquals(1, result.laterCount)
            assertTrue((result.urgent + result.overdue + result.future()).all { it.kind == kind })
        }
    }

    @Test fun laterDeadlinesRemainAvailableWhenNextSevenDaysAreEmpty() {
        val result = Agenda.build(listOf(asg(1, h(300)), asg(2, h(200))), emptyList(), now, seoul)
        assertTrue(result.urgent.isEmpty())
        assertTrue(result.upcoming.isEmpty())
        assertEquals(listOf("과제2", "과제1"), result.future().map { it.title })
        val empty = Agenda.build(emptyList(), emptyList(), now, seoul)
        assertTrue((empty.urgent + empty.overdue + empty.future()).isEmpty())
    }

    @Test fun endOfYearAndSundayUseRollingDates() {
        val sunday = LocalDate.of(2026, 12, 27).atTime(12, 0).atZone(seoul).toInstant()
        val nextSunday = LocalDate.of(2027, 1, 3).atStartOfDay(seoul).toInstant()
        val result = Agenda.build(listOf(asg(1, sunday.plusSeconds(3600)), asg(2, nextSunday.minusNanos(1)), asg(3, nextSunday)), emptyList(), sunday, seoul)
        assertEquals(LocalDate.of(2027, 1, 2), result.lastDay)
        assertEquals(listOf("과제1", "과제2"), result.upcoming.flatMap { it.rows }.map { it.title })
        assertEquals(listOf("과제3"), result.later.flatMap { it.rows }.map { it.title })
    }

    @Test fun daylightSavingUsesCalendarDaysForListAndElapsedHoursForUrgency() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 10, 31).atTime(12, 0).atZone(zone).toInstant()
        val boundary = LocalDate.of(2026, 11, 7).atStartOfDay(zone).toInstant()
        val result = Agenda.build(listOf(asg(1, start.plus(Duration.ofHours(24))), asg(2, boundary.minusNanos(1)), asg(3, boundary)), emptyList(), start, zone)
        assertEquals(LocalDate.of(2026, 11, 6), result.lastDay)
        assertEquals(listOf("과제1"), result.urgent.map { it.title })
        assertEquals(listOf("과제1", "과제2"), result.upcoming.flatMap { it.rows }.map { it.title })
        assertEquals(listOf("과제3"), result.later.flatMap { it.rows }.map { it.title })
    }

    @Test fun advancingTimeMovesDeadlinesIntoUrgentThenOverdue() {
        val assignments = listOf(asg(1, h(25), missing = true))
        assertTrue(Agenda.build(assignments, emptyList(), now, seoul).urgent.isEmpty())
        assertEquals(1, Agenda.build(assignments, emptyList(), h(1), seoul).urgent.size)
        val expired = Agenda.build(assignments, emptyList(), h(25), seoul)
        assertTrue(expired.urgent.isEmpty())
        assertTrue(expired.future().isEmpty())
        assertEquals(1, expired.overdue.size)
    }

    @Test fun dateRolloverMovesLaterDeadlineIntoSevenDayList() {
        val nextDay = now.atZone(seoul).toLocalDate().plusDays(1).atStartOfDay(seoul).toInstant()
        val assignments = listOf(asg(1, now.atZone(seoul).toLocalDate().plusDays(7).atStartOfDay(seoul).toInstant()))
        assertEquals(1, Agenda.build(assignments, emptyList(), now, seoul).laterCount)
        val result = Agenda.build(assignments, emptyList(), nextDay, seoul)
        assertEquals(1, result.upcomingCount)
        assertEquals(0, result.laterCount)
    }

    @Test fun stableIdsDistinguishIdenticalTitlesAndTimesAcrossCourses() {
        val a = asg(1, h(1))
        val result = Agenda.build(listOf(a, a.copy(courseId = 2)), listOf(MyDeadline(1, a.name, h(1))), now, seoul)
        assertEquals(3, result.future().map { it.id }.distinct().size)
        val completed = Agenda.build(emptyList(), listOf(MyDeadline(1, "완료", h(1), done = true)), now, seoul)
        assertTrue(completed.urgent.isEmpty())
        assertEquals(0, completed.upcomingCount)
    }

    @Test fun dayLabelsMakeTodayAndTomorrowExplicit() {
        val today = LocalDate.of(2026, 11, 23)
        assertEquals("오늘 · 11/23(월)", Agenda.dayLabel(today, today))
        assertEquals("내일 · 11/24(화)", Agenda.dayLabel(today.plusDays(1), today))
        assertEquals("11/25(수)", Agenda.dayLabel(today.plusDays(2), today))
        assertFalse(Agenda.dayLabel(today.plusDays(2), today).contains("이번 주"))
    }

    @Test fun snapshotCacheRoundTrip() {
        val s = CachedSnapshot(123L, listOf(asg(1, h(5)), asg(2, null, submitted = true).copy(score = 9.5, points = null, url = null)))
        assertEquals(s, SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(s)))
    }
}
