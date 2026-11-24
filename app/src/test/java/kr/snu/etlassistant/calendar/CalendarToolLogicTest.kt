package kr.snu.etlassistant.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarToolLogicTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private val now = kst("2026-11-25T15:30") // 수요일

    @Test fun windows() {
        val today = CalendarToolLogic.window(null, null, null, null, now)
        assertEquals(kst("2026-11-25T00:00"), today.start)
        assertEquals(kst("2026-11-26T00:00"), today.end)
        val week = CalendarToolLogic.window("this_week", null, null, null, now)
        assertEquals(kst("2026-11-23T00:00"), week.start)
        assertEquals(kst("2026-11-30T00:00"), week.end)
        assertEquals(kst("2026-11-30T00:00"), CalendarToolLogic.window("next_week", null, null, null, now).start)
        assertEquals(now.plusSeconds(3 * 86400), CalendarToolLogic.window("days", 3, null, null, now).end)
        val custom = CalendarToolLogic.window("today", null, "2026-12-01", "2026-12-03", now)
        assertEquals(kst("2026-12-04T00:00"), custom.end) // end 포함
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.window("month", null, null, null, now) }
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.window(null, null, "2026-12-05", "2026-12-01", now) }
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.window(null, null, "2026-01-01", "2026-06-01", now) }
    }

    @Test fun timedEventDefaultsToOneHourAndAcceptsHourOnlyEnd() {
        val w = CalendarToolLogic.parseWhen("2026-11-28 14:00", null, null)
        assertFalse(w.allDay)
        assertEquals(kst("2026-11-28T14:00").toEpochMilli(), w.dtStart)
        assertEquals(kst("2026-11-28T15:00").toEpochMilli(), w.dtEnd)
        assertEquals("Asia/Seoul", w.timezone)
        assertEquals(kst("2026-11-28T16:30").toEpochMilli(), CalendarToolLogic.parseWhen("2026-11-28 14:00", "16:30", null).dtEnd)
        assertEquals(kst("2026-11-29T01:00").toEpochMilli(), CalendarToolLogic.parseWhen("2026-11-28 23:00", "2026-11-29 01:00", null).dtEnd)
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.parseWhen("2026-11-28 14:00", "13:00", null) }
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.parseWhen("2026-11-28", null, false) }
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.parseWhen("내일 3시", null, null) }
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.parseWhen(null, null, null) }
    }

    @Test fun allDayEventsUseUtcMidnightAndInclusiveEnd() {
        val w = CalendarToolLogic.parseWhen("2026-11-28", "2026-11-29", null)
        assertTrue(w.allDay)
        assertEquals("UTC", w.timezone)
        assertEquals(LocalDate.parse("2026-11-28").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), w.dtStart)
        assertEquals(LocalDate.parse("2026-11-30").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), w.dtEnd)
        // 시각이 있어도 all_day=true면 그날 종일
        assertTrue(CalendarToolLogic.parseWhen("2026-11-28 14:00", null, true).allDay)
        assertEquals("11/28(토)~11/29(일) 종일", CalendarToolLogic.whenLabel(true, w.dtStart, w.dtEnd))
    }

    @Test fun textValidation() {
        assertEquals("면담", CalendarToolLogic.cleanTitle(" 면담​ "))
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.cleanTitle("  ") }
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.cleanTitle("가".repeat(101)) }
        assertThrows(CalendarToolLogic.ArgException::class.java) { CalendarToolLogic.cleanMemo("a".repeat(1001)) }
    }

    @Test fun undoHistoryRoundTripAndPrune() {
        val b = CalendarToolLogic.Backup(5, 2, "면담", kst("2026-11-28T14:00").toEpochMilli(), kst("2026-11-28T15:00").toEpochMilli(), false, "Asia/Seoul", "301동", "메모")
        val c1 = CalendarToolLogic.Change(1, now.toEpochMilli(), CalendarToolLogic.Action.UPDATE, 5, "개인", "면담 · ...", b)
        val c2 = CalendarToolLogic.Change(2, now.toEpochMilli(), CalendarToolLogic.Action.ADD, 6, "개인", "회의", null, undone = true)
        val list = listOf(c1, c2)
        assertEquals(list, CalendarToolLogic.changesFromJson(CalendarToolLogic.changesToJson(list)))
        val old = c2.copy(id = 3, at = now.minusSeconds(15 * 86400).toEpochMilli())
        val added = CalendarToolLogic.addChange(listOf(old), c1, now)
        assertEquals(listOf(1L), added.map { it.id })
        assertNull(CalendarToolLogic.changesFromJson("not json").firstOrNull())
    }

    @Test fun noticeShowsBeforeAndAfterAndUndoHint() {
        val b = CalendarToolLogic.Backup(5, 2, "면담", kst("2026-11-28T14:00").toEpochMilli(), kst("2026-11-28T15:00").toEpochMilli(), false, "Asia/Seoul", "", "")
        val n = CalendarToolLogic.notice(CalendarToolLogic.Action.UPDATE, "개인", b, "면담 · 11/28(토) 16:00~17:00")
        assertTrue(n.contains("면담 · 11/28(토) 14:00~15:00 → 면담 · 11/28(토) 16:00~17:00"))
        assertTrue(n.endsWith("설정 → 캘린더 연동에서 되돌릴 수 있어요"))
        assertTrue(CalendarToolLogic.notice(CalendarToolLogic.Action.DELETE, "개인", b, null).contains("지움: 면담"))
    }
}
