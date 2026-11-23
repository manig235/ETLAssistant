package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.AssignmentQuery
import kr.snu.etlassistant.widget.DeadlineWidgetLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MyDeadlinesRepeatTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private fun d(s: String) = LocalDate.parse(s)
    private val MON = DayOfWeek.MONDAY
    private val WED = DayOfWeek.WEDNESDAY
    private val FRI = DayOfWeek.FRIDAY

    private fun series(start: String, freq: MyRepeat.Freq, days: Set<DayOfWeek> = emptySet(), until: LocalDate? = null, id: Long = 1_763_000_000_000) =
        MyDeadline(id, "실험 보고서", kst(start), repeat = MyRepeat(freq, days, until))

    private fun dates(x: MyDeadline, from: String, to: String) =
        MyDeadlines.occurrences(x, kst(from), kst(to)).map { it.occurrence.toString() }

    @Test fun weeklyMultipleWeekdays() {
        // 2026-11-23은 월요일
        val s = series("2026-11-23T18:00", MyRepeat.Freq.WEEKLY, setOf(MON, WED))
        assertEquals(listOf("2026-11-23", "2026-11-25", "2026-11-30", "2026-12-02"), dates(s, "2026-11-20T00:00", "2026-12-03T00:00"))
        val o = MyDeadlines.occurrences(s, kst("2026-11-25T00:00"), kst("2026-11-26T00:00")).single()
        assertEquals(kst("2026-11-25T18:00"), o.due) // 마감 시각은 그대로
        assertEquals("매주 월·수", MyDeadlines.repeatLabel(s))
    }

    @Test fun weeklyStartsOnFirstMatchingDayAfterStart() {
        // 화요일 시작, 월·수 반복 → 첫 회차는 수요일. 시작일 전 회차는 없다.
        val s = series("2026-11-24T18:00", MyRepeat.Freq.WEEKLY, setOf(MON, WED))
        assertEquals(listOf("2026-11-25", "2026-11-30"), dates(s, "2026-11-01T00:00", "2026-12-01T00:00"))
        assertEquals(d("2026-11-25"), MyDeadlines.firstOccurrence(s)!!.occurrence)
        // 요일을 비우면 시작일의 요일
        assertEquals(setOf(DayOfWeek.TUESDAY), MyDeadlines.weekdaysOf(series("2026-11-24T18:00", MyRepeat.Freq.WEEKLY)))
    }

    @Test fun biweekly() {
        val s = series("2026-11-23T09:00", MyRepeat.Freq.BIWEEKLY, setOf(MON))
        assertEquals(listOf("2026-11-23", "2026-12-07", "2026-12-21"), dates(s, "2026-11-23T00:00", "2026-12-28T00:00"))
        // 수요일 시작, 월·금: 시작 주(11/23 주)의 금요일, 그다음은 12/7 주
        val s2 = series("2026-11-25T09:00", MyRepeat.Freq.BIWEEKLY, setOf(MON, FRI))
        assertEquals(listOf("2026-11-27", "2026-12-07", "2026-12-11"), dates(s2, "2026-11-23T00:00", "2026-12-14T00:00"))
    }

    @Test fun monthlyClampsToMonthEnd() {
        val s = series("2027-01-31T23:59", MyRepeat.Freq.MONTHLY)
        assertEquals(listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30"), dates(s, "2027-01-01T00:00", "2027-05-01T00:00"))
        val leap = series("2028-01-31T23:59", MyRepeat.Freq.MONTHLY)
        assertEquals(listOf("2028-02-29"), dates(leap, "2028-02-01T00:00", "2028-03-01T00:00"))
        assertEquals("매월 31일", MyDeadlines.repeatLabel(s))
    }

    @Test fun dailyWithUntilInclusive() {
        val s = series("2026-11-23T22:00", MyRepeat.Freq.DAILY, until = d("2026-11-25"))
        assertEquals(listOf("2026-11-23", "2026-11-24", "2026-11-25"), dates(s, "2026-11-01T00:00", "2026-12-31T00:00"))
        assertNull(MyDeadlines.nextOccurrence(s, kst("2026-11-25T22:00")))
        assertEquals("매일 · 11/25까지", MyDeadlines.repeatLabel(s))
    }

    @Test fun usesKstDates() {
        // 월요일 00:30 KST = 일요일 15:30 UTC. 회차 날짜·요일은 KST 기준.
        val s = series("2026-11-23T00:30", MyRepeat.Freq.WEEKLY)
        assertEquals(setOf(MON), MyDeadlines.weekdaysOf(s))
        val next = MyDeadlines.nextOccurrence(s, kst("2026-11-23T01:00"))!!
        assertEquals(d("2026-11-30"), next.occurrence)
        assertEquals(Instant.parse("2026-11-29T15:30:00Z"), next.due)
    }

    @Test fun doneAppliesToOneOccurrence() {
        val now = kst("2026-11-24T12:00")
        val s = series("2026-11-23T18:00", MyRepeat.Freq.WEEKLY, setOf(MON, WED))
        val marked = MyDeadlines.setDone(s, d("2026-11-25"), true, now)
        assertEquals(setOf(d("2026-11-25")), marked.doneDates)
        assertFalse(marked.done)
        val occ = MyDeadlines.occurrences(marked, now, kst("2026-12-01T00:00"))
        assertEquals(listOf(true, false), occ.map { it.done })
        // 다음 미완료 회차는 완료한 수요일을 건너뛴다
        assertEquals(d("2026-11-30"), MyDeadlines.nextOccurrence(marked, now)!!.occurrence)
        assertEquals(emptySet<LocalDate>(), MyDeadlines.setDone(marked, d("2026-11-25"), false, now).doneDates)
        // 오래된 완료 기록은 정리한다
        assertEquals(setOf(d("2026-11-25")), MyDeadlines.setDone(marked.copy(doneDates = setOf(d("2026-01-01"))), d("2026-11-25"), true, now).doneDates)
    }

    @Test fun lastMissedOnlyMostRecentWithinWeek() {
        val s = series("2026-11-01T18:00", MyRepeat.Freq.DAILY)
        val now = kst("2026-11-24T12:00")
        assertEquals(d("2026-11-23"), MyDeadlines.lastMissed(s, now)!!.occurrence)
        assertNull(MyDeadlines.lastMissed(MyDeadlines.setDone(s, d("2026-11-23"), true, now), now)) // 최근 회차를 했으면 없음
        // 7일보다 오래 전에 끝난 반복은 누락으로 보이지 않는다
        assertNull(MyDeadlines.lastMissed(s.copy(repeat = s.repeat!!.copy(until = d("2026-11-10"))), now))
    }

    @Test fun itemsAndAlertsPerOccurrence() {
        val s = series("2026-11-16T18:00", MyRepeat.Freq.WEEKLY, setOf(MON, WED))
        val now = kst("2026-11-24T12:00")
        val items = MyDeadlines.toItems(listOf(s), now)
        // 최근 놓친 회차(11/23 월)와 다음 회차(11/25 수)만
        assertEquals(listOf(true, false), items.map { it.missing })
        assertEquals(listOf(kst("2026-11-23T18:00"), kst("2026-11-25T18:00")), items.map { it.due })
        assertEquals(2, items.map { it.assignmentId }.distinct().size)
        assertTrue(items.none { it.assignmentId == s.id })

        val plan = NotificationPlanner.plan(Snapshot(now, items, emptyList()), NotifyState(announcementBaselineDone = true), now, NotifySettings(), seoul)
        val keys1 = plan.notices.flatMap { it.firedKeys }.toSet()
        assertTrue(keys1.isNotEmpty())
        // 다음 주 같은 요일 회차는 다른 키라 다시 알린다
        val later = kst("2026-12-01T12:00")
        val items2 = MyDeadlines.toItems(listOf(s), later)
        val state = NotifyState(announcementBaselineDone = true).also { st -> keys1.forEach { st.fired[it] = now.toEpochMilli() } }
        val plan2 = NotificationPlanner.plan(Snapshot(later, items2, emptyList()), state, later, NotifySettings(), seoul)
        assertTrue(plan2.notices.isNotEmpty())
        assertTrue(plan2.notices.flatMap { it.firedKeys }.none { it in keys1 })
    }

    @Test fun customRemindersFollowEachOccurrence() {
        val base = series("2026-11-23T18:00", MyRepeat.Freq.WEEKLY, setOf(MON))
        val s = base.copy(reminders = listOf(kst("2026-11-23T17:00")))
        val at1 = kst("2026-11-23T17:05")
        val n1 = MyDeadlines.reminderNotices(listOf(s), at1, emptySet()).single()
        val fired = n1.firedKeys.toSet()
        assertTrue(MyDeadlines.reminderNotices(listOf(s), at1, fired).isEmpty())
        assertEquals(kst("2026-11-30T17:00"), MyDeadlines.nextReminderAt(listOf(s), at1, fired))
        val n2 = MyDeadlines.reminderNotices(listOf(s), kst("2026-11-30T17:01"), fired).single()
        assertTrue(n2.firedKeys.none { it in fired })
        assertTrue(n1.tag != n2.tag)
        // 완료한 회차는 울리지 않는다
        val done = MyDeadlines.setDone(s, d("2026-11-30"), true, at1)
        assertTrue(MyDeadlines.reminderNotices(listOf(done), kst("2026-11-30T17:01"), fired).isEmpty())
        assertEquals(kst("2026-12-07T17:00"), MyDeadlines.nextReminderAt(listOf(done), at1, fired))
    }

    @Test fun skipOneOccurrence() {
        val now = kst("2026-11-24T12:00")
        val s = MyDeadlines.skip(series("2026-11-23T18:00", MyRepeat.Freq.WEEKLY, setOf(MON, WED)), d("2026-11-25"), now)
        assertEquals(listOf("2026-11-23", "2026-11-30"), dates(s, "2026-11-23T00:00", "2026-12-01T00:00"))
        assertFalse(MyDeadlines.matches(s, d("2026-11-25")))
    }

    @Test fun jsonRoundTripAndBackwardCompatible() {
        val s = series("2026-11-23T18:00", MyRepeat.Freq.BIWEEKLY, setOf(MON, WED), d("2026-12-31"))
            .copy(doneDates = setOf(d("2026-11-23")), skipDates = setOf(d("2026-12-07")), reminders = listOf(kst("2026-11-23T17:00")))
        val plain = MyDeadline(2, "서류", kst("2026-11-26T12:00"))
        assertEquals(listOf(s, plain), MyDeadlines.fromJson(MyDeadlines.toJson(listOf(s, plain))))
        // 예전 저장 형식(반복 키 없음)과 모르는 반복 종류는 반복 없음
        val old = """[{"id":5,"title":"옛 항목","due":0,"memo":"","done":true,"reminders":[]},{"id":6,"title":"미래","due":0,"repeat":{"freq":"yearly"}}]"""
        val parsed = MyDeadlines.fromJson(old)
        assertEquals(listOf(null, null), parsed.map { it.repeat })
        assertTrue(parsed[0].done)
    }

    @Test fun agendaExpandsWeekAndAddsNextBeyond() {
        val now = kst("2026-11-23T12:00")
        val daily = series("2026-11-20T21:00", MyRepeat.Freq.DAILY, id = 10)
        val monthly = series("2026-11-30T23:59", MyRepeat.Freq.MONTHLY, id = 20).copy(title = "월세")
        val plain = MyDeadline(30, "서류", kst("2026-11-24T12:00"))
        val s = Agenda.build(emptyList(), listOf(daily, monthly, plain), now, seoul)
        val upcomingDaily = s.upcoming.flatMap { it.rows }.filter { it.mine?.id == 10L }
        assertEquals(7, upcomingDaily.size) // 11/23~11/29 매일
        assertTrue(upcomingDaily.first().subtitle.contains("🔁 매일"))
        assertEquals(1, s.overdue.count { it.mine?.id == 10L }) // 11/22 회차만
        assertEquals(listOf("월세"), s.later.flatMap { it.rows }.map { it.title })
        val all = (s.overdue + s.upcoming.flatMap { it.rows } + s.later.flatMap { it.rows }).map { it.id }
        assertEquals(all.size, all.distinct().size)
    }

    @Test fun widgetShowsNextOccurrenceOnly() {
        val now = kst("2026-11-23T12:00")
        val daily = series("2026-11-20T21:00", MyRepeat.Freq.DAILY)
        val items = DeadlineWidgetLogic.items(emptyList(), listOf(daily), now)
        assertEquals(1, items.size)
        assertEquals(d("2026-11-23"), items.single().mineDate)
        assertEquals(kst("2026-11-23T21:00"), items.single().due)
    }

    @Test fun toolResultListsOccurrencesInWindow() {
        val now = kst("2026-11-24T12:00") // 화요일
        val s = series("2026-11-16T18:00", MyRepeat.Freq.WEEKLY, setOf(MON, WED))
        val week = AssignmentQuery.window(AssignmentQuery.Range.THIS_WEEK, now)
        val r = MyDeadlines.toolResult(listOf(s), now, week, includeDone = false)
        val rows = (0 until r.getJSONArray("items").length()).map { r.getJSONArray("items").getJSONObject(it) }
        assertEquals(listOf("2026-11-23", "2026-11-25"), rows.map { it.getString("occurrence") })
        assertTrue(rows[0].getBoolean("overdue"))
        assertEquals("weekly", rows[0].getJSONObject("repeat").getString("freq"))
        assertEquals(listOf("mon", "wed"), rows[0].getJSONObject("repeat").getJSONArray("weekdays").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(s.id, rows[1].getLong("id"))
        // 기간 없음: 최근 놓친 회차와 다음 회차
        assertEquals(2, MyDeadlines.toolResult(listOf(s), now, null, false).getInt("count"))
    }

    @Test fun validateAndParse() {
        assertNotNull(MyDeadlines.validateRepeat(series("2026-11-23T18:00", MyRepeat.Freq.DAILY, until = d("2026-11-22"))))
        // 끝 날짜까지 고른 요일이 없다
        assertNotNull(MyDeadlines.validateRepeat(series("2026-11-23T18:00", MyRepeat.Freq.WEEKLY, setOf(FRI), d("2026-11-25"))))
        assertNull(MyDeadlines.validateRepeat(series("2026-11-23T18:00", MyRepeat.Freq.WEEKLY, setOf(FRI))))
        assertEquals(MON, MyDeadlines.parseWeekday("mon"))
        assertEquals(WED, MyDeadlines.parseWeekday("Wednesday"))
        assertEquals(FRI, MyDeadlines.parseWeekday("금"))
        assertEquals(FRI, MyDeadlines.parseWeekday("금요일"))
        assertNull(MyDeadlines.parseWeekday("금방"))
        assertNull(MyDeadlines.parseWeekday("x"))
        assertEquals(MyRepeat.Freq.BIWEEKLY, MyRepeat.Freq.parse("Biweekly"))
        // 반복 마감은 한 줄 표시에 규칙을 붙인다
        assertTrue(MyDeadlines.line(series("2026-11-23T18:00", MyRepeat.Freq.WEEKLY)).endsWith("🔁 매주 월"))
        assertEquals(Duration.ZERO, Duration.between(kst("2026-11-23T18:00"), MyDeadlines.firstOccurrence(series("2026-11-23T18:00", MyRepeat.Freq.DAILY))!!.due))
    }
}
