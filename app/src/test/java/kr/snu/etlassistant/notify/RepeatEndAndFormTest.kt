package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 반복 끝(날짜까지·횟수)과 편집 창 계산([DeadlineForm]). */
class RepeatEndAndFormTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private fun d(s: String) = LocalDate.parse(s)
    private fun series(due: String, r: MyRepeat) = MyDeadline(1, "보고서", kst(due), repeat = r)

    private fun dates(m: MyDeadline, from: String = "2026-01-01T00:00", to: String = "2028-01-01T00:00") =
        MyDeadlines.occurrences(m, kst(from), kst(to)).map { it.occurrence!! }

    @Test fun countOnly() {
        val s = series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.WEEKLY, count = 3)) // 수요일
        assertEquals(listOf(d("2026-11-25"), d("2026-12-02"), d("2026-12-09")), dates(s))
        assertEquals("마지막 마감: 2026/12/09(수) 23:59 · 총 3번", MyDeadlines.endSummary(s))
        // 건너뛴 회차도 회차 수에 들어간다(끝이 늘어나지 않는다)
        val skipped = s.copy(skipDates = setOf(d("2026-12-02")))
        assertEquals(listOf(d("2026-11-25"), d("2026-12-09")), dates(skipped))
        assertNull(MyDeadlines.nextOccurrence(s, kst("2026-12-10T00:00")))
    }

    @Test fun untilOnlyAndBothTakeEarlier() {
        val u = series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.WEEKLY, until = d("2026-12-05")))
        assertEquals(2, dates(u).size)
        assertEquals("마지막 마감: 2026/12/02(수) 23:59 · 총 2번", MyDeadlines.endSummary(u))
        // 둘 다 있으면(외부 입력) 먼저 닿는 쪽: 횟수가 먼저
        assertEquals(2, dates(series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.WEEKLY, until = d("2027-01-30"), count = 2))).size)
        // 날짜가 먼저
        assertEquals(2, dates(series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.WEEKLY, until = d("2026-12-05"), count = 10))).size)
        assertEquals("끝 없이 반복", MyDeadlines.endSummary(series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.WEEKLY))))
    }

    @Test fun monthlyEndOfMonthAndBiweeklyWithCount() {
        val m = series("2026-10-31T18:00", MyRepeat(MyRepeat.Freq.MONTHLY, count = 4))
        assertEquals(listOf(d("2026-10-31"), d("2026-11-30"), d("2026-12-31"), d("2027-01-31")), dates(m))
        val b = series("2026-11-23T09:00", MyRepeat(MyRepeat.Freq.BIWEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), count = 3))
        assertEquals(listOf(d("2026-11-23"), d("2026-11-26"), d("2026-12-07")), dates(b))
        assertEquals("마지막 마감: 2026/12/07(월) 09:00 · 총 3번", MyDeadlines.endSummary(b))
    }

    @Test fun countSerializesAndValidates() {
        val s = series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.DAILY, count = 12))
        assertEquals(s, MyDeadlines.fromJson(MyDeadlines.toJson(listOf(s))).single())
        assertTrue(MyDeadlines.validateRepeat(s.copy(repeat = MyRepeat(MyRepeat.Freq.DAILY, count = 0)))!!.contains("횟수"))
        assertEquals("매일 · 12번", MyDeadlines.repeatLabel(s))
        assertEquals("12", MyDeadlines.repeatJson(s).get("count").toString())
        assertTrue(MyDeadlines.repeatEndConflict(hasUntil = true, hasCount = true)!!.contains("하나만"))
        assertNull(MyDeadlines.repeatEndConflict(hasUntil = false, hasCount = true))
    }

    // ---- 편집 창 ----------------------------------------------------------------

    @Test fun firstWeeklyIncludesTodayOnlyBeforeTime() {
        val wed = kst("2026-11-25T12:00") // 수요일 정오
        assertEquals(d("2026-11-25"), DeadlineForm.firstWeekly(wed, LocalTime.of(23, 59), setOf(DayOfWeek.WEDNESDAY)))
        assertEquals(d("2026-12-02"), DeadlineForm.firstWeekly(wed, LocalTime.of(9, 0), setOf(DayOfWeek.WEDNESDAY)))
        assertEquals(d("2026-11-26"), DeadlineForm.firstWeekly(wed, LocalTime.of(9, 0), setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)))
        assertNull(DeadlineForm.firstWeekly(wed, LocalTime.of(9, 0), emptySet()))
        assertEquals("첫 마감: 11/26(목) 23:59",
            DeadlineForm.firstLabel(DeadlineForm.Input(MyRepeat.Freq.WEEKLY, d("2026-11-25"), LocalTime.of(23, 59), setOf(DayOfWeek.THURSDAY)), wed))
    }

    @Test fun firstBiweeklyBaseWeek() {
        val wed = kst("2026-11-25T12:00")
        // 이번 주부터: 이번 주 목요일
        assertEquals(d("2026-11-26"), DeadlineForm.firstBiweekly(wed, LocalTime.of(18, 0), setOf(DayOfWeek.THURSDAY), nextWeek = false))
        // 다음 주부터: 다음 주 목요일
        assertEquals(d("2026-12-03"), DeadlineForm.firstBiweekly(wed, LocalTime.of(18, 0), setOf(DayOfWeek.THURSDAY), nextWeek = true))
        // 이번 주에 남은 요일이 없으면(월요일) 같은 짝인 두 주 뒤
        assertEquals(d("2026-12-07"), DeadlineForm.firstBiweekly(wed, LocalTime.of(18, 0), setOf(DayOfWeek.MONDAY), nextWeek = false))
        assertFalse(DeadlineForm.biweeklyNextWeek(d("2026-12-07"), wed))
        assertTrue(DeadlineForm.biweeklyNextWeek(d("2026-12-03"), wed))
    }

    @Test fun firstMonthlyAndDaily() {
        val now = kst("2027-02-10T12:00")
        assertEquals(d("2027-02-28"), DeadlineForm.firstMonthly(now, LocalTime.of(23, 59), 31)) // 없는 달은 말일
        assertEquals(d("2027-03-05"), DeadlineForm.firstMonthly(now, LocalTime.of(23, 59), 5))
        assertEquals(d("2027-02-10"), DeadlineForm.firstMonthly(now, LocalTime.of(23, 59), 10))
        assertEquals(d("2027-03-10"), DeadlineForm.firstMonthly(now, LocalTime.of(9, 0), 10))
        assertEquals(d("2027-02-10"), DeadlineForm.firstDaily(now, LocalTime.of(21, 0), null))
        assertEquals(d("2027-02-11"), DeadlineForm.firstDaily(now, LocalTime.of(9, 0), null))
        assertEquals(d("2027-02-20"), DeadlineForm.firstDaily(now, LocalTime.of(9, 0), d("2027-02-20")))
    }

    @Test fun switchingFreqCarriesValues() {
        val now = kst("2026-11-25T12:00")
        val none = DeadlineForm.Input(null, d("2026-11-27"), LocalTime.of(23, 59)) // 금요일
        val weekly = DeadlineForm.switchFreq(none, MyRepeat.Freq.WEEKLY, now)
        assertEquals(setOf(DayOfWeek.FRIDAY), weekly.weekdays)
        val monthly = DeadlineForm.switchFreq(none, MyRepeat.Freq.MONTHLY, now)
        assertEquals(27, monthly.monthDay)
        // 매주(목) → 없음: 계산된 첫 회차 날짜가 날짜 칸에
        val thu = weekly.copy(weekdays = setOf(DayOfWeek.THURSDAY))
        assertEquals(d("2026-11-26"), DeadlineForm.switchFreq(thu, null, now).date)
    }

    @Test fun keepsOriginalStartWhenPatternUnchanged() {
        val now = kst("2026-12-20T12:00")
        val old = series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.WEEKLY, setOf(DayOfWeek.WEDNESDAY)))
        val same = DeadlineForm.Input(MyRepeat.Freq.WEEKLY, d("2026-11-25"), LocalTime.of(22, 0), setOf(DayOfWeek.WEDNESDAY))
        assertTrue(DeadlineForm.samePattern(old, same, now))
        assertFalse(DeadlineForm.samePattern(old, same.copy(weekdays = setOf(DayOfWeek.THURSDAY)), now))
        assertEquals(kst("2026-11-25T22:00"), DeadlineForm.due(same, now, keep = d("2026-11-25")))
    }

    @Test fun switchingEndCarriesValues() {
        val s = series("2026-11-25T23:59", MyRepeat(MyRepeat.Freq.WEEKLY))
        // 날짜까지 → 횟수: 그 날짜까지의 회차 수
        assertEquals(null to 3, DeadlineForm.switchEnd(s, DeadlineForm.EndMode.UNTIL, DeadlineForm.EndMode.COUNT, d("2026-12-10"), null))
        // 횟수 → 날짜까지: 마지막 회차 날짜
        assertEquals(d("2026-12-09") to null, DeadlineForm.switchEnd(s, DeadlineForm.EndMode.COUNT, DeadlineForm.EndMode.UNTIL, null, 3))
    }
}
