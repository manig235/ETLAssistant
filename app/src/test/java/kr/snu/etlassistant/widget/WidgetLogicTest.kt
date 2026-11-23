package kr.snu.etlassistant.widget

import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.notify.AssignmentItem
import kr.snu.etlassistant.notify.MyDeadline
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WidgetLogicTest {
    private val now = Instant.parse("2026-11-23T03:00:00Z") // KST 월요일 12:00
    private fun assignment(id: Long, due: Instant?, submitted: Boolean = false, url: String? = null) =
        AssignmentItem(10, "2026-2 수업 (001)", id, "과제", due, true, null, url, submitted, false)

    @Test fun mergesByDeadlineWithoutSevenDayCutoffAndUsesStableIds() {
        val assignments = listOf(assignment(1, now.plusSeconds(20 * 86400)), assignment(2, now.plusSeconds(86400)))
        val mine = listOf(MyDeadline(2, "내 마감", now.plusSeconds(3600)))
        val items = DeadlineWidgetLogic.items(assignments, mine, now)
        assertEquals(listOf("mine-2", "etl-10-2", "etl-10-1"), items.map { it.key })
        assertEquals(3, items.size)
        assertEquals("수업", items[1].source)
    }

    @Test fun excludesSubmittedCompletedUndatedAndExpiredIncludingExactDeadline() {
        val assignments = listOf(assignment(1, null), assignment(2, now), assignment(3, now.minusSeconds(1)),
            assignment(4, now.plusSeconds(60), submitted = true), assignment(5, now.plusSeconds(120)))
        val mine = listOf(MyDeadline(1, "완료", now.plusSeconds(60), done = true), MyDeadline(2, "지난 일", now))
        assertEquals(listOf("etl-10-5"), DeadlineWidgetLogic.items(assignments, mine, now).map { it.key })
    }

    @Test fun externalOrUnsafeLinksFallBackToTheEtlAssignment() {
        for (url in listOf(null, "https://other.example/course", "javascript:alert(1)",
            "https://myetl.snu.ac.kr.evil.example/", "https://user@myetl.snu.ac.kr/", "http://myetl.snu.ac.kr/")) {
            val item = DeadlineWidgetLogic.items(listOf(assignment(9, now.plusSeconds(60), url = url)), emptyList(), now).single()
            assertEquals("https://myetl.snu.ac.kr/courses/10/assignments/9", item.url)
        }
        val link = "https://myetl.snu.ac.kr/courses/10/assignments/9?module_item_id=5"
        assertEquals(link, DeadlineWidgetLogic.items(listOf(assignment(9, now.plusSeconds(60), url = link)), emptyList(), now).single().url)
    }

    @Test fun compactSizeHoldsTwoAndGrowingHeightAddsRowsWhileLargeFontsReduceCapacity() {
        assertEquals(2, DeadlineWidgetLogic.capacity(110, 1f))
        assertEquals(2, DeadlineWidgetLogic.capacity(160, 1f))
        assertTrue(DeadlineWidgetLogic.capacity(250, 1f) > 2)
        assertTrue(DeadlineWidgetLogic.capacity(110, 1.5f) < DeadlineWidgetLogic.capacity(110, 1f))
        assertEquals(1, DeadlineWidgetLogic.capacity(40, 2f))
    }

    @Test fun labelsFollowKstDatesAndBoundaryDropsExpiredItems() {
        val nearMidnight = Instant.parse("2026-11-23T14:59:00Z")
        val due = nearMidnight.plusSeconds(120)
        assertEquals("내일 00:01", DeadlineWidgetLogic.dueLabel(due, nearMidnight))
        assertEquals("2분 남음", DeadlineWidgetLogic.remaining(due, nearMidnight))
        val item = DeadlineWidgetLogic.items(listOf(assignment(1, now.plusSeconds(60))), emptyList(), now)
        assertEquals(now.plusSeconds(60), DeadlineWidgetLogic.nextBoundary(item, now))
        assertTrue(DeadlineWidgetLogic.items(listOf(assignment(1, now.plusSeconds(60))), emptyList(), now.plusSeconds(60)).isEmpty())
        assertEquals(LocalDate.of(2026, 11, 24).atStartOfDay(EtlLogic.KST).toInstant(), DeadlineWidgetLogic.nextBoundary(emptyList(), now))
    }

    private val monday = LocalDate.of(2026, 11, 23)
    private fun weekly() = TimetableLogic.Weekly("10", 1, listOf(
        TimetableLogic.CourseMeetings(10, "정규 수업", listOf(TimetableLogic.Meeting("월", "09:00", "10:00", "301-101"))),
        TimetableLogic.CourseMeetings(-100, "직접 등록", listOf(TimetableLogic.Meeting("수", "10:00", "11:00", "동아리방"))),
    ), emptyList())

    @Test fun currentWeekKeepsCancelledBlockAddsMakeupAndIncludesCustomClasses() {
        val changes = listOf(
            TimetableLogic.Change(1, monday, "휴강", "정규 수업", 10),
            TimetableLogic.Change(2, monday.plusDays(5), "보강", "정규 수업", 10, "13:00", "14:00", "302-201"),
        )
        val week = TimetableWidgetLogic.week(weekly(), changes, monday.plusDays(3))
        assertEquals(monday, week.monday)
        assertEquals(3, week.entries.size)
        assertEquals(1, week.entries.count { it.cancelled })
        assertEquals(1, week.entries.count { it.row.kind == "보강" })
        assertTrue(week.entries.any { it.row.courseId == -100L })
        assertEquals(6, week.grid.days.size)
        assertEquals(3, week.grid.blocks.size)
    }

    @Test fun weekRollsOverOnMondayWithoutCarryingLastWeeksChanges() {
        val changes = listOf(TimetableLogic.Change(1, monday, "휴강", "정규 수업", 10))
        val sunday = TimetableWidgetLogic.week(weekly(), changes, monday.plusDays(6))
        val nextMonday = TimetableWidgetLogic.week(weekly(), changes, monday.plusDays(7))
        assertEquals(monday, sunday.monday)
        assertTrue(sunday.entries.any { it.cancelled })
        assertEquals(monday.plusDays(7), nextMonday.monday)
        assertFalse(nextMonday.entries.any { it.cancelled })
        assertEquals(2, nextMonday.entries.size)
    }

    @Test fun overlappingMakeupClassesReceiveSeparateLanes() {
        val changes = listOf(TimetableLogic.Change(3, monday, "보강", "추가 수업", null, "09:30", "10:30", "강의실"))
        val week = TimetableWidgetLogic.week(weekly(), changes, monday)
        val blocks = week.grid.blocks.filter { it.meeting.day == "월" }
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.lanes == 2 })
        assertEquals(2, blocks.map { it.lane }.distinct().size)
    }
}
