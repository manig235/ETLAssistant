package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.EtlLogic
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 내 마감 편집 창의 계산(2026-11-24, 순수 함수). 반복 종류마다 필요한 입력만 받고 저장할 [MyDeadline.due](첫 회차)를 여기서 계산한다.
 * - 없음: 날짜 + 시각.  매일: 시각(+ 시작 날짜, 기본 오늘).  매주: 요일 + 시각.  격주: 요일 + 시각 + 이번 주/다음 주부터.  매월: N일 + 시각.
 * 시간대는 KST([EtlLogic.KST]). 저장 모델(due = 첫 회차)은 그대로다.
 */
object DeadlineForm {
    enum class EndMode { NONE, UNTIL, COUNT }

    private val ZONE = EtlLogic.KST

    private fun at(date: LocalDate, time: LocalTime): Instant = LocalDateTime.of(date, time).atZone(ZONE).toInstant()

    private fun monday(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 매주: 오늘 이후 가장 가까운 선택 요일(오늘이 선택 요일이고 시각이 아직 안 지났으면 오늘). 요일이 없으면 null. */
    fun firstWeekly(now: Instant, time: LocalTime, weekdays: Set<DayOfWeek>): LocalDate? {
        if (weekdays.isEmpty()) return null
        val today = now.atZone(ZONE).toLocalDate()
        return (0L..7L).map { today.plusDays(it) }.firstOrNull { it.dayOfWeek in weekdays && at(it, time).isAfter(now) }
    }

    /**
     * 격주: 기준 주(이번 주 또는 다음 주, 월요일 시작)부터 두 주마다. 기준 주에 남은(아직 안 지난) 선택 요일이 없으면
     * 두 주 뒤 같은 짝의 주에서 고른다(지난 날을 첫 회차로 두면 바로 "놓친 회차"가 되므로).
     */
    fun firstBiweekly(now: Instant, time: LocalTime, weekdays: Set<DayOfWeek>, nextWeek: Boolean): LocalDate? {
        if (weekdays.isEmpty()) return null
        var base = monday(now.atZone(ZONE).toLocalDate()).plusWeeks(if (nextWeek) 1 else 0)
        repeat(3) {
            (0L..6L).map { base.plusDays(it) }.firstOrNull { it.dayOfWeek in weekdays && at(it, time).isAfter(now) }?.let { return it }
            base = base.plusWeeks(2)
        }
        return null
    }

    /** 매월 [day]일(없는 달은 말일): 오늘 이후 가장 가까운 날(오늘이고 시각이 안 지났으면 오늘). */
    fun firstMonthly(now: Instant, time: LocalTime, day: Int): LocalDate {
        val today = now.atZone(ZONE).toLocalDate()
        var month = today.withDayOfMonth(1)
        while (true) {
            val d = month.withDayOfMonth(minOf(day.coerceIn(1, 31), month.lengthOfMonth()))
            if (!d.isBefore(today) && at(d, time).isAfter(now)) return d
            month = month.plusMonths(1)
        }
    }

    /** 매일: [start](기본 오늘)부터, 그날 시각이 이미 지났으면 다음 날. */
    fun firstDaily(now: Instant, time: LocalTime, start: LocalDate?): LocalDate {
        val today = now.atZone(ZONE).toLocalDate()
        val s = if (start == null || start.isBefore(today)) today else start
        return if (at(s, time).isAfter(now)) s else s.plusDays(1)
    }

    /** 격주 시리즈의 기준이 이번 주와 같은 짝인가(편집 창 "이번 주부터/다음 주부터"를 기존 값으로 채울 때). */
    fun biweeklyNextWeek(start: LocalDate, now: Instant): Boolean =
        ChronoUnit.WEEKS.between(monday(start), monday(now.atZone(ZONE).toLocalDate())) % 2 != 0L

    /** 편집 창의 입력. */
    data class Input(
        val freq: MyRepeat.Freq?,
        val date: LocalDate,
        val time: LocalTime,
        val weekdays: Set<DayOfWeek> = emptySet(),
        val monthDay: Int = date.dayOfMonth,
        val biweeklyNext: Boolean = false,
        /** 매일 반복의 시작 날짜(null이면 오늘). */
        val dailyStart: LocalDate? = null,
    )

    /**
     * 입력에서 저장할 첫 회차 날짜. [keep]은 고치는 중인 원래 항목의 첫 회차 날짜: 반복 종류·요일·일자·기준 주를 바꾸지 않았으면
     * 원래 시작 날짜를 그대로 둔다(횟수·지난 회차 기록이 어긋나지 않게). 요일이 없어 정할 수 없으면 null.
     */
    fun firstDate(input: Input, now: Instant, keep: LocalDate? = null): LocalDate? = when (input.freq) {
        null -> input.date
        MyRepeat.Freq.DAILY -> keep ?: firstDaily(now, input.time, input.dailyStart)
        MyRepeat.Freq.WEEKLY -> keep ?: firstWeekly(now, input.time, input.weekdays)
        MyRepeat.Freq.BIWEEKLY -> keep ?: firstBiweekly(now, input.time, input.weekdays, input.biweeklyNext)
        MyRepeat.Freq.MONTHLY -> keep ?: firstMonthly(now, input.time, input.monthDay)
    }

    fun due(input: Input, now: Instant, keep: LocalDate? = null): Instant? = firstDate(input, now, keep)?.let { at(it, input.time) }

    /** 고치는 중인 항목의 반복 모양이 그대로인가(그러면 시작 날짜를 유지한다). */
    fun samePattern(old: MyDeadline, input: Input, now: Instant): Boolean {
        val r = old.repeat ?: return false
        if (r.freq != input.freq) return false
        val start = old.due.atZone(ZONE).toLocalDate()
        return when (r.freq) {
            MyRepeat.Freq.DAILY -> input.dailyStart == null || input.dailyStart == start
            MyRepeat.Freq.WEEKLY -> MyDeadlines.weekdaysOf(old) == input.weekdays
            MyRepeat.Freq.BIWEEKLY -> MyDeadlines.weekdaysOf(old) == input.weekdays && biweeklyNextWeek(start, now) == input.biweeklyNext
            MyRepeat.Freq.MONTHLY -> start.dayOfMonth == input.monthDay
        }
    }

    /** 반복 종류를 바꿀 때 값 이어받기: 없음→매주·격주는 날짜의 요일, 없음→매월은 날짜의 일, 반복→없음은 계산된 첫 회차 날짜. */
    fun switchFreq(input: Input, next: MyRepeat.Freq?, now: Instant): Input {
        if (input.freq == next) return input
        val first = firstDate(input, now) ?: input.date
        return when (next) {
            null -> input.copy(freq = null, date = first)
            MyRepeat.Freq.WEEKLY, MyRepeat.Freq.BIWEEKLY -> input.copy(freq = next, weekdays = input.weekdays.ifEmpty { setOf(first.dayOfWeek) })
            MyRepeat.Freq.MONTHLY -> input.copy(freq = next, monthDay = if (input.freq == null) input.date.dayOfMonth else first.dayOfMonth)
            MyRepeat.Freq.DAILY -> input.copy(freq = next)
        }
    }

    /** 끝을 바꿀 때 값 이어받기: 날짜까지→횟수는 그 날짜까지의 회차 수, 횟수→날짜까지는 마지막 회차 날짜. */
    fun switchEnd(series: MyDeadline, from: EndMode, to: EndMode, until: LocalDate?, count: Int?): Pair<LocalDate?, Int?> {
        val r = series.repeat ?: return until to count
        return when {
            from == EndMode.UNTIL && to == EndMode.COUNT && until != null ->
                null to MyDeadlines.occurrenceCount(series.copy(repeat = r.copy(until = null, count = null)), until).coerceIn(1, MyDeadlines.COUNT_MAX)
            from == EndMode.COUNT && to == EndMode.UNTIL && count != null ->
                (MyDeadlines.lastOccurrence(series.copy(repeat = r.copy(until = null, count = count)))?.occurrence) to null
            else -> until to count
        }
    }

    /** "첫 마감: 11/26(목) 23:59". 정할 수 없으면 null. */
    fun firstLabel(input: Input, now: Instant, keep: LocalDate? = null): String? =
        due(input, now, keep)?.let { "첫 마감: " + NoticeText.dateLabel(it) }
}
