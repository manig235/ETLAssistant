package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.TimetableLogic
import org.junit.Assert.assertEquals
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

    private val day = TimetableLogic.Day(LocalDate.of(2026, 11, 19), "목",
        listOf(TimetableLogic.ClassRow("09:00", "10:15", "회로", 2, null, "정규"), TimetableLogic.ClassRow("15:00", "16:15", "자료구조", 1, "301-118", "정규")),
        listOf(TimetableLogic.ClassRow("17:00", "18:15", "선형대수", 3, null, "정규", "학회")))

    @Test fun groupsAndFilters() {
        val assignments = listOf(
            asg(1, h(5)),                       // 오늘
            asg(2, h(20)),                      // 내일 08:00
            asg(3, h(60)),                      // 11/21(토) 이번 주
            asg(4, h(24 * 6)),                  // 11/25(수) 다음 주
            asg(5, h(24 * 20)),                 // 그 뒤
            asg(6, h(-5), missing = true),      // 마감 지남
            asg(7, h(-50), submitted = true),   // 지난 제출: 안 보임
            asg(8, h(10), submitted = true),    // 제출함: 숨김 기본
            asg(9, null),                       // 마감 없음: 안 보임
        )
        val mine = listOf(MyDeadline(100, "장학금 서류", h(30), "행정실"), MyDeadline(101, "끝난 일", h(-3), done = true))
        val g = Agenda.build(assignments, mine, listOf(day), now, seoul)
        assertEquals(listOf("마감 지남", "오늘", "내일", "이번 주", "다음 주", "그 뒤"), g.map { it.label })
        val today = g.first { it.label == "오늘" }.rows
        assertEquals(listOf("자료구조", "과제1", "선형대수"), today.map { it.title }) // 끝난 9시 수업은 빠지고, 휴강은 남는다
        assertTrue(today.last().cancelled)
        assertEquals(listOf("과제2", "장학금 서류"), g.first { it.label == "내일" }.rows.map { it.title })
        assertEquals(listOf("과제6"), g.first().rows.map { it.title })

        val shown = Agenda.build(assignments, mine, listOf(day), now, seoul, hideDone = false)
        assertTrue(shown.flatMap { it.rows }.any { it.title == "과제8" && it.done })
        assertTrue(shown.flatMap { it.rows }.none { it.title == "과제7" || it.title == "끝난 일" })

        assertEquals(setOf(Agenda.Kind.MINE), Agenda.build(assignments, mine, listOf(day), now, seoul, Agenda.Filter.MINE).flatMap { it.rows }.map { it.kind }.toSet())
        assertEquals(setOf(Agenda.Kind.CLASS), Agenda.build(assignments, mine, listOf(day), now, seoul, Agenda.Filter.CLASS).flatMap { it.rows }.map { it.kind }.toSet())
    }

    @Test fun snapshotCacheRoundTrip() {
        val s = CachedSnapshot(123L, listOf(asg(1, h(5)), asg(2, null, submitted = true).copy(score = 9.5, points = null, url = null)))
        assertEquals(s, SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(s)))
    }
}
