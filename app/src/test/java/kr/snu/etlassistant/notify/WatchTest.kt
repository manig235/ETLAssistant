package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.TimetableLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ttokttok-pet watch.js의 새 과제·마감 변경·성적 규칙과 수업 알림. */
class WatchTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    private fun h(x: Long) = now.plus(Duration.ofHours(x))
    private fun item(id: Long, due: Instant?, score: Double? = null, cid: Long = 1, name: String = "과제$id") =
        AssignmentItem(cid, "자료구조", id, name, due, true, 10.0, "https://myetl.snu.ac.kr/courses/$cid/assignments/$id", score != null, false, score)
    private fun snap(vararg a: AssignmentItem, errors: List<String> = emptyList()) = Snapshot(now, a.toList(), emptyList(), errors)

    @Test fun firstRunIsSilentBaseline() {
        val (s, n) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5))), now)
        assertTrue(s.initialized)
        assertTrue(n.isEmpty())
        assertEquals(1, s.records.size)
    }

    @Test fun newDueChangeAndGrade() {
        val (s0, _) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5)), item(2, h(30))), now)
        val (s1, n) = AssignmentWatch.diff(s0, snap(item(1, h(29)), item(2, h(30), score = 9.5), item(3, h(48))), now)
        assertEquals(listOf("마감 변경 · 자료구조", "새 과제 · 자료구조", "성적 · 자료구조"), n.map { it.title })
        assertTrue(n[2].text.contains("9.5 / 10"))
        assertTrue(n.all { it.channel == Channel.CHANGE })
        // 같은 상태로 다시 확인하면 아무것도 없다
        assertTrue(AssignmentWatch.diff(s1, snap(item(1, h(29)), item(2, h(30), score = 9.5), item(3, h(48))), now).second.isEmpty())
    }

    @Test fun manyChangesAreSummarisedAndMyDeadlinesIgnored() {
        val (s0, _) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5))), now)
        val mine = item(99, h(3), cid = MyDeadlines.COURSE_ID)
        val (_, n) = AssignmentWatch.diff(s0, snap(item(1, h(5)), item(2, h(6)), item(3, h(7)), item(4, h(8)), item(5, h(9)), mine), now)
        assertEquals(1, n.size)
        assertEquals("eTL 변경 4건", n[0].title)
        assertTrue(n[0].bigText!!.contains("[새 과제]"))
    }

    @Test fun failedFetchKeepsState() {
        val (s0, _) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5))), now)
        val (s1, n) = AssignmentWatch.diff(s0, snap(errors = listOf("자료구조: 실패")), now)
        assertEquals(s0, s1)
        assertTrue(n.isEmpty())
        // 일부 과목만 실패: 못 본 과제 기록은 남아 나중에 "새 과제"가 되지 않는다
        val (s2, _) = AssignmentWatch.diff(s0, snap(item(7, h(9), cid = 2)), now)
        assertTrue("1:1" in s2.records)
    }

    @Test fun stateJsonRoundTrip() {
        val s = WatchState(true, mapOf("1:1" to WatchRecord("a", "b", 5L, null), "1:2" to WatchRecord("c", "d", null, 3.5)))
        assertEquals(s, WatchState.fromJson(s.toJson()))
    }

    @Test fun classNotices() {
        val day = TimetableLogic.Day(LocalDate.of(2026, 11, 19), "목", listOf(
            TimetableLogic.ClassRow("12:05", "13:20", "자료구조", 1, "301-118", "정규"),
            TimetableLogic.ClassRow("15:00", "16:15", "회로이론", 2, null, "보강", "301동"),
        ), emptyList())
        val n = ClassNotices.plan(day, now, seoul, 10, emptySet())
        assertEquals(1, n.size)
        assertEquals("5분 뒤 수업 · 자료구조", n[0].title)
        assertTrue(n[0].text.contains("301-118"))
        assertTrue(ClassNotices.plan(day, now, seoul, 10, n[0].firedKeys.toSet()).isEmpty())
        val next = ClassNotices.nextAt(listOf(day), now, seoul, 10, n[0].firedKeys.toSet())
        assertEquals(Instant.parse("2026-11-19T05:50:00Z"), next) // 15:00 − 10분 KST
        assertNull(ClassNotices.nextAt(listOf(day), h(10), seoul, 10, emptySet()))
    }

    @Test fun historyKeepsRecent() {
        val day = 24 * 3600 * 1000L
        val old = HistoryEntry(0, "deadline", "a", "b", null, null)
        val list = NotifyHistoryLogic.add(listOf(old), HistoryEntry(100 * day, "class", "c", "d", null, null), 100 * day)
        assertEquals(listOf("c"), list.map { it.title })
        assertEquals(list, NotifyHistoryLogic.fromJson(NotifyHistoryLogic.toJson(list)))
    }
}
