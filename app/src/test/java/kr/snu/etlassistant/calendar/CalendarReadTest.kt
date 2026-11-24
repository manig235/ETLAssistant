package kr.snu.etlassistant.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarReadTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private fun utcDay(s: String): Long = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun raw(id: Long, begin: Long, end: Long, allDay: Boolean = false, cal: Long = 1, title: String = "일정$id", uri: String? = null, rrule: String? = null, status: Int? = null) =
        RawInstance(id, cal, "개인", 0x336699, title, "301동", "메모", begin, end, allDay, rrule, null, uri, status)

    private val dayFrom = kst("2026-11-24T00:00")
    private val dayTo = kst("2026-11-25T00:00")

    @Test fun allDayUsesUtcDatesAsKstDates() {
        // 11/24 종일: UTC 11/24 00:00 ~ 11/25 00:00 (KST로 보면 09:00~다음 날 09:00지만 날짜는 11/24)
        val e = CalendarReadLogic.events(listOf(raw(1, utcDay("2026-11-24"), utcDay("2026-11-25"), allDay = true)), setOf(1), emptySet(), emptySet(), dayFrom, dayTo).single()
        assertTrue(e.allDay)
        assertEquals(LocalDate.parse("2026-11-24"), e.startDate)
        assertEquals(LocalDate.parse("2026-11-24"), e.endDate)
        assertEquals(kst("2026-11-24T00:00"), e.start)
        assertEquals("종일", CalendarReadLogic.timeLabel(e))
        // 전날 종일 일정은 오늘 범위에 들어오지 않는다(UTC 그대로 보면 KST 11/24 09:00까지라 잘못 들어올 수 있다)
        assertTrue(CalendarReadLogic.events(listOf(raw(2, utcDay("2026-11-23"), utcDay("2026-11-24"), allDay = true)), setOf(1), emptySet(), emptySet(), dayFrom, dayTo).isEmpty())
    }

    @Test fun multiDayAllDayLabel() {
        val e = CalendarReadLogic.toEvent(raw(3, utcDay("2026-11-24"), utcDay("2026-11-27"), allDay = true))
        assertEquals(LocalDate.parse("2026-11-26"), e.endDate)
        assertEquals("11/24~11/26 종일", CalendarReadLogic.timeLabel(e))
    }

    @Test fun timedEventsInKstAndRecurringInstances() {
        val list = listOf(
            raw(10, kst("2026-11-24T14:00").toEpochMilli(), kst("2026-11-24T15:30").toEpochMilli(), rrule = "FREQ=WEEKLY"),
            raw(10, kst("2026-12-01T14:00").toEpochMilli(), kst("2026-12-01T15:30").toEpochMilli(), rrule = "FREQ=WEEKLY"),
        )
        val week = CalendarReadLogic.events(list, setOf(1), emptySet(), emptySet(), dayFrom, kst("2026-12-08T00:00"))
        assertEquals(2, week.size) // 같은 일정의 회차 둘(키가 시작 시각으로 다르다)
        assertTrue(week.all { it.recurring })
        assertEquals("14:00~15:30", CalendarReadLogic.timeLabel(week[0]))
        assertEquals(1, CalendarReadLogic.events(list, setOf(1), emptySet(), emptySet(), dayFrom, dayTo).size)
    }

    @Test fun excludesOurOwnEventsOtherCalendarsAndCancelled() {
        val t = kst("2026-11-24T10:00").toEpochMilli()
        val list = listOf(
            raw(1, t, t + 3600_000, uri = "youngmin://item/mine:5"), // 영민이가 넣은 일정(URI)
            raw(2, t, t + 3600_000), // 매핑에 있는 일정 ID
            raw(3, t, t + 3600_000, cal = 9), // 앱 캘린더
            raw(4, t, t + 3600_000, cal = 2), // 고르지 않은 캘린더
            raw(5, t, t + 3600_000, status = 2), // 취소됨
            raw(6, t, t + 3600_000, title = "  면담\u0007 "),
        )
        val got = CalendarReadLogic.events(list, setOf(1, 9), setOf(2), setOf(9), dayFrom, dayTo)
        assertEquals(listOf(6L), got.map { it.eventId })
        assertEquals("면담", got.single().title)
    }

    @Test fun briefLinesPutAllDayFirst() {
        val t = kst("2026-11-24T09:00").toEpochMilli()
        val evs = CalendarReadLogic.events(listOf(
            raw(1, t, t + 3600_000, title = "면담"),
            raw(2, utcDay("2026-11-24"), utcDay("2026-11-25"), allDay = true, title = "동아리 MT"),
        ), setOf(1), emptySet(), emptySet(), dayFrom, dayTo)
        val lines = CalendarReadLogic.briefLines(evs, LocalDate.parse("2026-11-24"))
        assertEquals(listOf("• 종일 · 동아리 MT · 301동", "• 09:00~10:00 · 면담 · 301동"), lines)
    }

    @Test fun eventJsonMarksEditable() {
        val e = CalendarReadLogic.toEvent(raw(1, kst("2026-11-24T09:00").toEpochMilli(), kst("2026-11-24T10:00").toEpochMilli()))
        val o = CalendarReadLogic.eventJson(e, writable = true)
        assertEquals("11-24(화) 09:00", o.getString("start_kst"))
        assertTrue(o.getBoolean("can_edit"))
        val r = CalendarReadLogic.toEvent(raw(2, 0, 3600_000, rrule = "FREQ=DAILY"))
        assertFalse(CalendarReadLogic.eventJson(r, writable = true).getBoolean("can_edit"))
    }

    @Test fun settingsRoundTripAndDefaults() {
        val s = CalendarReadSettings(showEnabled = true, assistantEnabled = true, calendarIds = setOf(3, 1), writeCalendarId = 3, writeCalendarName = "학교")
        assertEquals(s, CalendarReadSettings.fromJson(s.toJson()))
        val d = CalendarReadSettings.fromJson(null)
        assertFalse(d.showEnabled)
        assertFalse(d.assistantEnabled)
        assertTrue(d.calendarIds.isEmpty())
        assertEquals("캘린더 2개(개인, 학교)", CalendarReadLogic.selectionLabel(listOf("개인", "학교")))
    }
}
