package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.AssignmentQuery
import kr.snu.etlassistant.etl.EtlLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 사용자가 직접 등록한 마감("내 마감"). eTL과 무관한 일정(장학금 서류, 동아리 회의 등)도 넣을 수 있다.
 * 폰 안에만 저장하고([MyDeadlineStore]), 알림·브리핑에서는 [MyDeadlines.toItems]로 [AssignmentItem]처럼 다뤄
 * 마감 알림 시점·방해 금지·중복 방지·브리핑 로직을 그대로 쓴다.
 */
data class MyDeadline(
    /** 만든 시각(epoch millis)을 쓴다. 지운 항목의 알림 기록 키와 새 항목이 겹치지 않게 재사용하지 않는다. */
    val id: Long,
    val title: String,
    val due: Instant,
    val memo: String = "",
    val done: Boolean = false,
    /** 사용자가 정한 알림 시각들(예: 마감 22:00에 21:00·21:50). 설정의 기본 알림 시점과 별개로, 방해 금지와 무관하게 울린다. */
    val reminders: List<Instant> = emptyList(),
    /**
     * 반복 규칙. null이면 한 번뿐인 마감(예전 동작). 반복이면 [due]가 첫 회차의 기준(시작 날짜와 마감 시각)이고,
     * 회차는 저장하지 않고 그때그때 펼친다([MyDeadlines.occurrences]). [reminders]는 마감과의 간격 그대로 회차마다 따라간다.
     */
    val repeat: MyRepeat? = null,
    /** 반복 마감에서 완료로 표시한 회차의 날짜(KST). 반복 마감에서는 [done]을 쓰지 않는다. */
    val doneDates: Set<LocalDate> = emptySet(),
    /** 반복 마감에서 "이번만 지우기"로 뺀 회차의 날짜(KST). */
    val skipDates: Set<LocalDate> = emptySet(),
    /** 펼친 회차일 때 그 회차의 날짜(KST). 저장하지 않는다. 이때 [due]·[done]·[reminders]는 그 회차의 값이다. */
    val occurrence: LocalDate? = null,
    /** 종류(2026-11-24). 예전 저장값은 모두 [MyKind.DEADLINE]. 시험이면 [due]가 시작 시각이고 반복은 없다. */
    val kind: MyKind = MyKind.DEADLINE,
    /** 시험 끝 시각(선택, [due]보다 뒤). 마감에는 쓰지 않는다. */
    val end: Instant? = null,
    /** 시험 장소(선택, 예: 301동 118호). */
    val location: String = "",
    /** 시험 과목(선택, 과목명 그대로 또는 직접 입력). */
    val course: String = "",
    /** 시험 준비물(선택, 한 줄: "공학용 계산기, 신분증"). */
    val supplies: String = "",
    /** 공지 알림의 "내 일정에 등록"으로 넣었으면 그 공지 ID(같은 공지로 두 번 넣지 않게). */
    val sourceAnnouncementId: Long? = null,
) {
    val isExam: Boolean get() = kind == MyKind.EXAM

    /** 끝난 시각: 시험은 끝 시각(없으면 시작), 마감은 마감 시각. */
    val over: Instant get() = end ?: due
}

/** 내 마감의 종류. */
enum class MyKind(val id: String, val label: String) {
    DEADLINE("deadline", "마감"), EXAM("exam", "시험");

    companion object {
        fun parse(s: String?): MyKind? = values().firstOrNull { it.id == s?.trim()?.lowercase() }
    }
}

/**
 * 반복 규칙. 매주·격주는 [weekdays](비면 시작일의 요일), 매월은 시작일과 같은 날짜(없는 달은 그달 말일).
 * 격주는 시작일이 든 주(월요일 시작)부터 두 주마다. [until]은 그날(KST)까지 포함, null이면 끝없음.
 * [count]는 첫 회차부터 센 총 회차 수(완료·건너뛴 회차 포함, 2026-11-24). [until]과 함께 있으면 먼저 닿는 쪽에서 끝난다.
 */
data class MyRepeat(
    val freq: Freq,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    enum class Freq(val id: String, val label: String) {
        DAILY("daily", "매일"), WEEKLY("weekly", "매주"), BIWEEKLY("biweekly", "격주"), MONTHLY("monthly", "매월");

        companion object {
            fun parse(s: String?): Freq? = values().firstOrNull { it.id == s?.trim()?.lowercase() }
        }
    }
}

object MyDeadlines {
    /** 알림 키(`deadline-m:{courseId}:{id}:{분}`)에서 eTL 강의 ID와 겹치지 않는 값. */
    const val COURSE_ID = -1L
    const val COURSE_NAME = "내 마감"
    const val TITLE_MAX = 60
    const val MEMO_MAX = 200
    const val MAX_ITEMS = 100
    const val MAX_REMINDERS = 5

    /** 반복 마감의 지난 회차를 "누락"으로 볼 기간. 이보다 오래된 회차는 목록·알림에서 뺀다. */
    const val MISSED_LOOKBACK_DAYS = 7L
    /** 완료·건너뛴 회차 날짜를 이보다 오래되면 지운다(저장 크기 제한). */
    private const val KEEP_DATES_DAYS = 60L
    /** 다음 회차를 찾을 때 볼 최대 날수. 어떤 규칙이든 회차 간격은 31일 이하라 충분하다. */
    private const val SEARCH_DAYS = 400L
    /** 직접 정한 알림을 찾을 때 볼 회차 범위(알림은 마감 30일 전까지 정할 수 있다). */
    private val REMINDER_HORIZON: Duration = Duration.ofDays(31)

    // ---- 반복 ----------------------------------------------------------------

    private fun startDate(d: MyDeadline): LocalDate = d.due.atZone(EtlLogic.KST).toLocalDate()

    /** 매주·격주의 요일(비어 있으면 시작일의 요일). */
    fun weekdaysOf(d: MyDeadline): Set<DayOfWeek> = d.repeat?.weekdays?.ifEmpty { null } ?: setOf(startDate(d).dayOfWeek)

    const val COUNT_MAX = 999

    /** 반복 끝은 날짜(until)와 횟수(count) 중 하나만. 둘 다 주면 오류 문구. */
    fun repeatEndConflict(hasUntil: Boolean, hasCount: Boolean): String? =
        if (hasUntil && hasCount) "반복 끝은 until(날짜)과 count(횟수) 중 하나만 줄 수 있습니다" else null

    /** [date](KST)가 이 마감의 회차인가. 건너뛴 회차는 아니다. */
    fun matches(d: MyDeadline, date: LocalDate): Boolean {
        val start = startDate(d)
        val r = d.repeat ?: return date == start
        if (date.isBefore(start) || date in d.skipDates) return false
        val last = lastDate(d)
        if (last != null && date.isAfter(last)) return false
        return ruleMatches(d, r, start, date)
    }

    /** 끝(until·count)과 건너뛰기를 보지 않은 규칙만의 판정. */
    private fun ruleMatches(d: MyDeadline, r: MyRepeat, start: LocalDate, date: LocalDate): Boolean {
        if (date.isBefore(start)) return false
        return when (r.freq) {
            MyRepeat.Freq.DAILY -> true
            MyRepeat.Freq.WEEKLY -> date.dayOfWeek in weekdaysOf(d)
            MyRepeat.Freq.BIWEEKLY -> date.dayOfWeek in weekdaysOf(d) &&
                ChronoUnit.WEEKS.between(monday(start), monday(date)) % 2 == 0L
            // 31일 시작이면 30일까지인 달은 30일, 2월은 28·29일(그달 말일로 맞춘다).
            MyRepeat.Freq.MONTHLY -> date.dayOfMonth == minOf(start.dayOfMonth, date.lengthOfMonth())
        }
    }

    private fun monday(date: LocalDate) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** [count]번째 회차 날짜 캐시(규칙·시작 날짜별). 회차 판정마다 다시 세지 않게. */
    private val countCache = object : LinkedHashMap<Pair<MyRepeat, LocalDate>, LocalDate>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<MyRepeat, LocalDate>, LocalDate>?) = size > 64
    }

    /** 첫 회차부터 센 [MyRepeat.count]번째 회차 날짜(건너뛴 회차도 센다, until은 보지 않는다). count가 없으면 null. */
    fun countLastDate(d: MyDeadline): LocalDate? {
        val r = d.repeat ?: return null
        val n = r.count ?: return null
        val start = startDate(d)
        val key = r.copy(until = null) to start
        synchronized(countCache) { countCache[key]?.let { return it } }
        var date = start
        var seen = 0
        var last = start
        val limit = start.plusDays(n.toLong() * 31 + 31)
        while (!date.isAfter(limit)) {
            if (ruleMatches(d, r, start, date)) {
                seen++
                last = date
                if (seen >= n) break
            }
            date = date.plusDays(1)
        }
        synchronized(countCache) { countCache[key] = last }
        return last
    }

    /** 첫 회차부터 [through]까지(포함)의 회차 수(건너뛴 회차도 센다, 끝은 보지 않는다). */
    fun occurrenceCount(d: MyDeadline, through: LocalDate): Int {
        val r = d.repeat ?: return if (through.isBefore(startDate(d))) 0 else 1
        val start = startDate(d)
        var date = start
        var n = 0
        while (!date.isAfter(through)) {
            if (ruleMatches(d, r, start, date)) n++
            date = date.plusDays(1)
        }
        return n
    }

    /** 마지막 회차가 될 수 있는 날짜: until과 count 중 먼저 닿는 쪽. 둘 다 없으면 null(끝없음). */
    fun lastDate(d: MyDeadline): LocalDate? {
        val r = d.repeat ?: return startDate(d)
        val byCount = countLastDate(d)
        return listOfNotNull(r.until, byCount).minOrNull()
    }

    /** 실제 마지막 회차(건너뛴 회차 제외). 끝이 없으면 null. */
    fun lastOccurrence(d: MyDeadline): MyDeadline? {
        if (d.repeat == null) return d
        val last = lastDate(d) ?: return null
        var date = last
        val start = startDate(d)
        while (!date.isBefore(start)) {
            if (matches(d, date)) return occurrenceAt(d, date)
            date = date.minusDays(1)
        }
        return null
    }

    /** 반복 끝 안내: `마지막 마감: 2027/01/06(수) 23:59 · 총 12번`, 끝이 없으면 `끝 없이 반복`. 회차가 없으면 `해당하는 날이 없어요`. */
    fun endSummary(d: MyDeadline): String {
        val r = d.repeat ?: return ""
        if (r.until == null && r.count == null) return "끝 없이 반복"
        // 규칙상 마지막 회차(건너뛴 회차여도 회차로 센다: 횟수와 맞게).
        val start = startDate(d)
        var lastRule = lastDate(d) ?: return "끝 없이 반복"
        while (!lastRule.isBefore(start) && !ruleMatches(d, r, start, lastRule)) lastRule = lastRule.minusDays(1)
        if (lastRule.isBefore(start)) return "반복 기간 안에 해당하는 날이 없어요"
        val total = occurrenceCount(d, lastRule)
        val z = occurrenceAt(d, lastRule).due.atZone(EtlLogic.KST)
        return "마지막 마감: %d/%02d/%02d(%s) %02d:%02d · 총 %d번".format(z.year, z.monthValue, z.dayOfMonth, WEEKDAY_KO[z.dayOfWeek.value - 1], z.hour, z.minute, total)
    }

    /** 반복 마감의 [date] 회차. 마감 시각은 같고, 알림 시각은 마감과의 간격을 유지한다. */
    fun occurrenceAt(d: MyDeadline, date: LocalDate): MyDeadline {
        val due = date.atTime(d.due.atZone(EtlLogic.KST).toLocalTime()).atZone(EtlLogic.KST).toInstant()
        val shift = Duration.between(d.due, due)
        return d.copy(due = due, done = date in d.doneDates, reminders = d.reminders.map { it.plus(shift) }, occurrence = date)
    }

    /** 마감이 [from, to)에 드는 회차(완료 포함, 마감순). 반복 없는 마감은 그 자신이 들면 그것 하나. */
    fun occurrences(d: MyDeadline, from: Instant, to: Instant): List<MyDeadline> {
        if (d.repeat == null) return if (!d.due.isBefore(from) && d.due.isBefore(to)) listOf(d) else emptyList()
        if (!from.isBefore(to)) return emptyList()
        val out = mutableListOf<MyDeadline>()
        var date = maxOf(startDate(d), from.atZone(EtlLogic.KST).toLocalDate().minusDays(1))
        val last = minOf(to.atZone(EtlLogic.KST).toLocalDate().plusDays(1), date.plusDays(SEARCH_DAYS))
        while (!date.isAfter(last)) {
            if (matches(d, date)) {
                val o = occurrenceAt(d, date)
                if (!o.due.isBefore(from) && o.due.isBefore(to)) out += o
            }
            date = date.plusDays(1)
        }
        return out
    }

    /** [after]보다 뒤에 마감인 첫 미완료 회차. 반복 없는 마감은 미완료이고 마감 전이면 그 자신. */
    fun nextOccurrence(d: MyDeadline, after: Instant): MyDeadline? {
        val r = d.repeat ?: return d.takeIf { !it.done && it.due.isAfter(after) }
        var date = maxOf(startDate(d), after.atZone(EtlLogic.KST).toLocalDate().minusDays(1))
        val last = date.plusDays(SEARCH_DAYS)
        val end = lastDate(d)
        while (!date.isAfter(last) && (end == null || !date.isAfter(end))) {
            if (matches(d, date)) {
                val o = occurrenceAt(d, date)
                if (!o.done && o.due.isAfter(after)) return o
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** 첫 회차(완료 여부 무관). 규칙에 맞는 날이 없으면 null(예: 끝 날짜가 고른 요일보다 앞). */
    fun firstOccurrence(d: MyDeadline): MyDeadline? =
        if (d.repeat == null) d else occurrences(d, d.due.minus(Duration.ofDays(1)), d.due.plus(Duration.ofDays(SEARCH_DAYS))).firstOrNull()

    /**
     * 반복 마감의 가장 최근 지난 회차가 미완료면 그 회차([MISSED_LOOKBACK_DAYS]일 이내만).
     * 반복 없는 마감은 지났고 미완료면 그 자신(예전 동작). 지난 회차를 모두 쌓아 보이지 않는다.
     */
    fun lastMissed(d: MyDeadline, now: Instant): MyDeadline? {
        if (d.repeat == null) return d.takeIf { !it.done && !it.due.isAfter(now) }
        val last = occurrences(d, now.minus(Duration.ofDays(MISSED_LOOKBACK_DAYS)), now.plusMillis(1)).lastOrNull()
        return last?.takeIf { !it.done }
    }

    /**
     * 알림·브리핑·위젯용 목록. 반복 없는 마감은 그대로, 반복 마감은 최근 놓친 회차와 다음 미완료 회차만 넣는다
     * (매일 반복이 며칠 치 알림을 한꺼번에 만들지 않게).
     */
    fun active(list: List<MyDeadline>, now: Instant): List<MyDeadline> = list.flatMap { d ->
        if (d.repeat == null) listOf(d) else listOfNotNull(lastMissed(d, now), nextOccurrence(d, now))
    }

    /**
     * "내 일정" 화면용: [until] 전까지의 회차를 모두(완료 포함), 그 안에 남은 회차가 없으면 다음 회차 하나, 그리고 최근 놓친 회차.
     * 반복 없는 마감은 그대로.
     */
    fun agendaItems(list: List<MyDeadline>, now: Instant, until: Instant): List<MyDeadline> = list.flatMap { d ->
        if (d.repeat == null) return@flatMap listOf(d)
        val upcoming = occurrences(d, now.plusMillis(1), until)
        // [until] 뒤의 다음 미완료 회차 하나("그 이후"에 보인다). 예전에는 [until] 안에 회차가 있으면 빠져서
        // 매주 반복이 "그 이후 마감 0개"로 보였다(S22 실기기 보고, 2026-11-24).
        val after = nextOccurrence(d, maxOf(now, until.minusMillis(1)))
        (listOfNotNull(lastMissed(d, now)) + upcoming + listOfNotNull(after)).distinctBy { it.occurrence }
    }

    /** 알림·브리핑에서 쓰는 과제 ID. 반복 회차는 회차마다 달라야 알림 기록(`deadline-m:…`)이 회차별로 남는다. */
    fun itemId(d: MyDeadline): Long = d.occurrence?.let { d.id * 100_000 + it.toEpochDay() } ?: d.id

    /**
     * [itemId]로 저장된 내 마감을 찾는다(알림·위젯·캘린더가 넘긴 ID). 원래 ID이거나 반복 회차 ID(`id * 100000 + epochDay`)면 그 항목.
     */
    fun findByItemId(list: List<MyDeadline>, itemId: Long): MyDeadline? =
        list.firstOrNull { it.id == itemId } ?: list.firstOrNull { it.repeat != null && it.id == itemId / 100_000 }

    /** 완료 표시/취소. 반복 마감은 [occurrence] 회차만 바꾼다. [d]는 저장된 원래 항목. */
    fun setDone(d: MyDeadline, occurrence: LocalDate?, done: Boolean, now: Instant): MyDeadline =
        if (d.repeat == null || occurrence == null) d.copy(done = done)
        else d.copy(doneDates = prune(if (done) d.doneDates + occurrence else d.doneDates - occurrence, now))

    /** 반복 마감에서 한 회차만 지운다. */
    fun skip(d: MyDeadline, occurrence: LocalDate, now: Instant): MyDeadline =
        d.copy(skipDates = prune(d.skipDates + occurrence, now), doneDates = d.doneDates - occurrence)

    private fun prune(dates: Set<LocalDate>, now: Instant): Set<LocalDate> {
        val keepFrom = now.atZone(EtlLogic.KST).toLocalDate().minusDays(KEEP_DATES_DAYS)
        return dates.filter { !it.isBefore(keepFrom) }.toSet()
    }

    /** `매주 월·수 · 12/20까지`. 반복이 없으면 빈 문자열. */
    fun repeatLabel(d: MyDeadline): String {
        val r = d.repeat ?: return ""
        val base = when (r.freq) {
            MyRepeat.Freq.DAILY -> "매일"
            MyRepeat.Freq.WEEKLY, MyRepeat.Freq.BIWEEKLY -> "${r.freq.label} " + weekdaysOf(d).sorted().joinToString("·") { WEEKDAY_KO[it.value - 1].toString() }
            MyRepeat.Freq.MONTHLY -> "매월 ${startDate(d).dayOfMonth}일"
        }
        return base + (r.until?.let { " · ${it.monthValue}/${it.dayOfMonth}까지" } ?: "") + (r.count?.let { " · ${it}번" } ?: "")
    }

    /** 반복 확인: 끝 날짜는 시작 날짜 뒤, 회차가 하나는 있어야 한다. 문제가 없으면 null. */
    fun validateRepeat(d: MyDeadline): String? {
        val r = d.repeat ?: return null
        return when {
            r.until != null && r.until.isBefore(startDate(d)) -> "반복 끝 날짜는 시작 날짜보다 뒤여야 해요"
            r.count != null && (r.count < 1 || r.count > COUNT_MAX) -> "반복 횟수는 1~${COUNT_MAX}번이어야 해요"
            firstOccurrence(d) == null -> "반복 기간 안에 해당하는 날이 없어요"
            else -> null
        }
    }

    private const val WEEKDAY_KO = "월화수목금토일"
    private val WEEKDAY_IDS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun weekdayId(d: DayOfWeek): String = WEEKDAY_IDS[d.value - 1]

    /** "mon"·"monday"·"월"·"월요일" → 요일. 못 읽으면 null. */
    fun parseWeekday(s: String?): DayOfWeek? {
        val t = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        WEEKDAY_IDS.indexOfFirst { t.startsWith(it) }.takeIf { it >= 0 }?.let { return DayOfWeek.of(it + 1) }
        return WEEKDAY_KO.indexOf(t[0]).takeIf { it >= 0 && (t.length == 1 || t == "${t[0]}요일") }?.let { DayOfWeek.of(it + 1) }
    }

    /**
     * 알림·브리핑용으로 바꾼다. 완료한 항목은 "제출됨", 지난 미완료 항목은 "누락"으로 본다. 반복 마감은 [active] 회차만.
     * 시험은 넣지 않는다: 마감 임박·누락 규칙이 맞지 않고, 시험 알림은 직접 정한 알림 시각([MyDeadline.reminders], 기본값 [defaultExamReminders])과
     * 브리핑의 시험 섹션([examLines])으로 따로 보낸다.
     */
    fun toItems(list: List<MyDeadline>, now: Instant): List<AssignmentItem> = active(list.filter { !it.isExam }, now).map { d ->
        AssignmentItem(
            courseId = COURSE_ID,
            courseName = COURSE_NAME,
            assignmentId = itemId(d),
            name = d.title,
            due = d.due,
            online = true, // 알림 문구에 "(오프라인)"을 붙이지 않는다
            points = null,
            url = null,
            submitted = d.done,
            missing = !d.done && !d.due.isAfter(now),
        )
    }

    /** eTL 조회 결과에 내 마감을 더한다(마감순 정렬). */
    fun merge(snapshot: Snapshot, mine: List<MyDeadline>, now: Instant): Snapshot =
        if (mine.isEmpty()) snapshot
        else snapshot.copy(assignments = SnapshotFetcher.sortAssignments(snapshot.assignments + toItems(mine, now)))

    /** 입력 확인. 문제가 없으면 null, 있으면 사용자에게 보일 문구. */
    const val LOCATION_MAX = 40
    const val COURSE_MAX = 60
    const val SUPPLIES_MAX = 100

    /** 시험 확인: 끝은 시작보다 뒤, 장소·과목·준비물 길이, 반복 없음. 마감이면 null. */
    fun validateExam(d: MyDeadline): String? {
        if (!d.isExam) return null
        return when {
            d.end != null && !d.end.isAfter(d.due) -> "끝 시각은 시작 시각보다 뒤여야 해요"
            d.end != null && Duration.between(d.due, d.end) > Duration.ofHours(24) -> "시험 시간은 24시간을 넘을 수 없어요"
            d.location.length > LOCATION_MAX -> "장소는 ${LOCATION_MAX}자까지 쓸 수 있어요"
            d.course.length > COURSE_MAX -> "과목은 ${COURSE_MAX}자까지 쓸 수 있어요"
            d.supplies.length > SUPPLIES_MAX -> "준비물은 ${SUPPLIES_MAX}자까지 쓸 수 있어요"
            d.repeat != null -> "시험은 반복할 수 없어요"
            else -> null
        }
    }

    /** 준비물 정리: 줄바꿈·쉼표·가운뎃점으로 나눈 항목을 ", "로 잇는다(빈 항목·중복 제거). */
    fun normalizeSupplies(s: String?): String =
        s.orEmpty().split('\n', ',', '，', '·', '/', ';').map { NoticeText.clean(it).trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(", ").take(SUPPLIES_MAX)

    /** 시험 기본 알림(직접 정하지 않았을 때): 전날 20:00, 시작 2시간 전. 이미 지난 시각은 뺀다. */
    fun defaultExamReminders(start: Instant, now: Instant): List<Instant> {
        val eve = start.atZone(EtlLogic.KST).toLocalDate().minusDays(1).atTime(20, 0).atZone(EtlLogic.KST).toInstant()
        return normalizeReminders(listOf(eve, start.minus(Duration.ofHours(2)))).filter { it.isAfter(now) && it.isBefore(start) }
    }

    /** 시험 제목: `[시험] 자료구조 중간고사`(과목이 없거나 제목에 이미 있으면 제목만). */
    fun examTitle(d: MyDeadline): String {
        val course = NoticeText.clean(d.course)
        val title = NoticeText.clean(d.title)
        return "[시험] " + if (course.isEmpty() || title.contains(course)) title else "$course $title"
    }

    /** `11/25(수) 10:00~12:00`(끝이 없으면 시작만, 끝이 다른 날이면 날짜도). */
    fun examTimeLabel(d: MyDeadline): String {
        val e = d.end ?: return NoticeText.dateLabel(d.due)
        val ez = e.atZone(EtlLogic.KST)
        val sameDay = ez.toLocalDate() == d.due.atZone(EtlLogic.KST).toLocalDate()
        return NoticeText.dateLabel(d.due) + "~" + if (sameDay) "%02d:%02d".format(ez.hour, ez.minute) else NoticeText.dateLabel(e)
    }

    /**
     * 브리핑의 시험 줄: 시작이 [days]일 안이거나 지금 보고 있는 시험. 오늘·내일은 앞에 [오늘]/[내일].
     * `• [내일] 11/25(수) 10:00~12:00 · [시험] 자료구조 중간고사 · 301동 118호`
     */
    fun examLines(list: List<MyDeadline>, now: Instant, days: Long = 7): List<String> {
        val today = now.atZone(EtlLogic.KST).toLocalDate()
        return list.filter { it.isExam && !it.done && it.over.isAfter(now) && it.due.isBefore(now.plus(Duration.ofDays(days))) }
            .sortedWith(compareBy({ it.due }, { it.id }))
            .map { d ->
                val tag = when (d.due.atZone(EtlLogic.KST).toLocalDate()) { today -> "[오늘] "; today.plusDays(1) -> "[내일] "; else -> "" }
                "• $tag${examTimeLabel(d)} · ${NoticeText.cut(examTitle(d), 60)}" +
                    (d.location.takeIf { it.isNotBlank() }?.let { " · ${NoticeText.clean(it)}" } ?: "") +
                    (d.supplies.takeIf { it.isNotBlank() && tag.isNotEmpty() }?.let { " · 준비물: ${NoticeText.cut(NoticeText.clean(it), 40)}" } ?: "")
            }
    }

    fun validate(title: String, memo: String, countWithoutThis: Int): String? = when {
        title.isBlank() -> "제목을 입력하세요"
        title.trim().length > TITLE_MAX -> "제목은 ${TITLE_MAX}자까지 쓸 수 있어요"
        memo.trim().length > MEMO_MAX -> "메모는 ${MEMO_MAX}자까지 쓸 수 있어요"
        countWithoutThis >= MAX_ITEMS -> "내 마감은 ${MAX_ITEMS}개까지 등록할 수 있어요. 지난 항목을 지워 주세요"
        else -> null
    }

    /** 알림 시각 확인: 마감보다 앞이어야 하고 최대 [MAX_REMINDERS]개. 문제가 없으면 null. */
    fun validateReminders(reminders: List<Instant>, due: Instant): String? = when {
        reminders.size > MAX_REMINDERS -> "알림 시각은 ${MAX_REMINDERS}개까지 정할 수 있어요"
        reminders.any { !it.isBefore(due) } -> "알림 시각은 마감보다 앞이어야 해요"
        else -> null
    }

    /** 정리: 분 단위로 맞추고 중복 제거, 시각순. */
    fun normalizeReminders(reminders: List<Instant>): List<Instant> =
        reminders.map { Instant.ofEpochSecond(it.epochSecond / 60 * 60) }.distinct().sorted()

    /**
     * 알림 시각 인자: "YYYY-MM-DD HH:MM", 또는 "HH:MM"(마감 날짜의 그 시각; 마감보다 늦으면 전날).
     * 못 읽으면 null.
     */
    fun parseReminder(s: String?, due: Instant): Instant? {
        val t = s?.trim() ?: return null
        parseDue(t)?.takeIf { t.length > 10 }?.let { return it }
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return null
        return try {
            val dueDate = due.atZone(EtlLogic.KST).toLocalDate()
            val at = dueDate.atTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()).atZone(EtlLogic.KST).toInstant()
            if (at.isBefore(due)) at else at.minusSeconds(86400)
        } catch (_: Exception) {
            null
        }
    }

    fun reminderKey(d: MyDeadline, at: Instant) = "mine-remind:${d.id}:${at.epochSecond / 60}"

    /** 직접 정한 알림을 볼 회차: 반복 없는 마감은 그대로, 반복 마감은 앞으로 [REMINDER_HORIZON] 안의 회차. */
    private fun reminderCandidates(list: List<MyDeadline>, now: Instant): List<MyDeadline> = list.flatMap { d ->
        when {
            d.reminders.isEmpty() -> emptyList()
            d.repeat == null -> listOf(d)
            else -> occurrences(d, now.plusMillis(1), now.plus(REMINDER_HORIZON))
        }
    }

    /**
     * 지금 울릴 알림: 시각이 지났고 마감 전이며 아직 안 보낸 것(폰이 꺼져 있었으면 마감 전까지는 늦게라도 보낸다). 반복 마감은 회차마다.
     * 시험은 앞에 정보(시각·장소·준비물), 뒤에 응원 문구([ExamCheer], [casual]이면 반말)를 붙인다.
     */
    fun reminderNotices(list: List<MyDeadline>, now: Instant, fired: Set<String>, casual: Boolean = false): List<Notice> = reminderCandidates(list, now).flatMap { d ->
        if (d.done || !d.due.isAfter(now)) return@flatMap emptyList()
        val due = d.reminders.filter { !it.isAfter(now) && reminderKey(d, it) !in fired }
        if (due.isEmpty()) emptyList()
        else if (d.isExam) listOf(examReminder(d, due.max(), now, casual).copy(firedKeys = due.map { reminderKey(d, it) }))
        else listOf(
            Notice(
                channel = Channel.DEADLINE,
                title = NoticeText.cut("알림 · ${NoticeText.clean(d.title)}", NoticeText.TITLE_MAX),
                text = NoticeText.cut("마감 ${NoticeText.dateLabel(d.due)} (${EtlLogic.remaining(d.due, now)})" +
                    (if (d.memo.isNotBlank()) " · ${NoticeText.clean(d.memo)}" else ""), NoticeText.LINE_MAX),
                tag = "mine-remind:${itemId(d)}",
                firedKeys = due.map { reminderKey(d, it) },
                agenda = true,
                mineItemId = itemId(d),
            )
        )
    }

    /** 다음 알림 시각(없으면 null). 시험 끝 시각(수고했어요 알림)도 넣는다. */
    fun nextReminderAt(list: List<MyDeadline>, now: Instant, fired: Set<String>): Instant? =
        (reminderCandidates(list, now).filter { !it.done && it.due.isAfter(now) }
            .flatMap { d -> d.reminders.filter { it.isAfter(now) && it.isBefore(d.due) && reminderKey(d, it) !in fired } } +
            list.mapNotNull { d -> d.end?.takeIf { d.isExam && it.isAfter(now) && examDoneKey(d) !in fired } })
            .minOrNull()

    /** 시험 알림 본문의 정보 부분: `11/25(수) 10:00~12:00 (1일 2시간 남음) · 301동 118호 · 준비물: 공학용 계산기, 신분증`. */
    fun examInfo(d: MyDeadline, now: Instant): String = examTimeLabel(d) + " (${EtlLogic.remaining(d.due, now)})" +
        (d.location.takeIf { it.isNotBlank() }?.let { " · ${NoticeText.clean(it)}" } ?: "") +
        (d.supplies.takeIf { it.isNotBlank() }?.let { " · 준비물: ${NoticeText.clean(it)}" } ?: "")

    /** 시험 알림: 제목 `시험 · 중간고사 · 자료구조`, 본문 앞은 시각·장소·준비물, 뒤는 응원 문구. */
    fun examReminder(d: MyDeadline, at: Instant, now: Instant, casual: Boolean): Notice {
        val info = examInfo(d, now)
        val cheer = ExamCheer.message(ExamCheer.phaseOf(at, d.due), d.id, at, d.course, d.due, d.location, d.supplies, casual)
        val course = NoticeText.clean(d.course).takeIf { it.isNotEmpty() && !d.title.contains(it) }
        return Notice(
            channel = Channel.DEADLINE,
            title = NoticeText.cut("시험 · ${NoticeText.clean(d.title)}" + (course?.let { " · $it" } ?: ""), NoticeText.TITLE_MAX),
            text = NoticeText.cut("$info · $cheer", NoticeText.LINE_MAX),
            bigText = "$info\n$cheer",
            tag = "mine-remind:${itemId(d)}",
            agenda = true,
            mineItemId = itemId(d),
        )
    }

    fun examDoneKey(d: MyDeadline) = "exam-done:${d.id}"

    /** 수고했어요 알림을 보낼 수 있는 기간(끝난 뒤). 그보다 늦게 확인하면 보내지 않는다. */
    val EXAM_DONE_WINDOW: Duration = Duration.ofHours(3)

    /**
     * 끝 시각이 있는 시험이 방금 끝났으면 "수고했어요" 알림. [enabled]가 꺼져 있거나 방해 금지 중([quiet])이면 보내지 않고
     * 다시 보내지 않도록 기록할 키만 돌려준다([Pair.second]).
     */
    fun examDoneNotices(list: List<MyDeadline>, now: Instant, fired: Set<String>, enabled: Boolean, quiet: Boolean, casual: Boolean): Pair<List<Notice>, List<String>> {
        val due = list.filter { d -> d.isExam && !d.done && d.end != null && !d.end.isAfter(now) && now.isBefore(d.end.plus(EXAM_DONE_WINDOW)) && examDoneKey(d) !in fired }
        if (due.isEmpty()) return emptyList<Notice>() to emptyList()
        if (!enabled || quiet) return emptyList<Notice>() to due.map(::examDoneKey)
        return due.map { d ->
            val cheer = ExamCheer.message(ExamCheer.Phase.AFTER, d.id, d.end!!, d.course, d.due, d.location, d.supplies, casual)
            Notice(
                channel = Channel.DEADLINE,
                title = NoticeText.cut((if (casual) "수고했어 · " else "수고했어요 · ") + examTitle(d).removePrefix("[시험] "), NoticeText.TITLE_MAX),
                text = NoticeText.cut(cheer, NoticeText.LINE_MAX),
                bigText = cheer,
                tag = "exam-done:${d.id}",
                firedKeys = listOf(examDoneKey(d)),
                agenda = true,
                mineItemId = d.id,
            )
        } to emptyList()
    }

    fun reminderLabel(d: MyDeadline): String = d.reminders.joinToString(", ") { r ->
        val z = r.atZone(EtlLogic.KST)
        val sameDay = z.toLocalDate() == d.due.atZone(EtlLogic.KST).toLocalDate()
        if (sameDay) "%02d:%02d".format(z.hour, z.minute) else NoticeText.dateLabel(r)
    }

    /** 화면 순서: 미완료(마감 가까운 순) → 완료(최근 마감 순). */
    fun sorted(list: List<MyDeadline>): List<MyDeadline> =
        list.filter { !it.done }.sortedWith(compareBy({ it.due }, { it.id })) +
            list.filter { it.done }.sortedWith(compareByDescending<MyDeadline> { it.due }.thenBy { it.id })

    /**
     * 비서 도구 `my_deadlines`의 결과(읽기 전용). [window]가 null이면 기간 제한 없음.
     * 지난 미완료 항목은 기간과 관계없이 넣는다(먼저 알려야 하므로). [includeDone]이 false면 완료 항목을 뺀다.
     * 반복 마감은 회차마다 한 줄(`occurrence` 날짜 포함): 기간이 있으면 그 안의 회차, 없으면 최근 놓친 회차와 다음 회차.
     */
    fun toolResult(list: List<MyDeadline>, now: Instant, window: AssignmentQuery.Window?, includeDone: Boolean): JSONObject {
        val rows = JSONArray()
        list.flatMap { d ->
            when {
                d.repeat == null -> listOf(d)
                window == null -> listOfNotNull(lastMissed(d, now), nextOccurrence(d, now))
                else -> (listOfNotNull(lastMissed(d, now)) + occurrences(d, window.start, window.endExclusive)).distinctBy { it.occurrence }
            }
        }.filter { d ->
            // 끝난 시험은 "지난 미완료"가 아니다(기간 안이면 그대로 보인다).
            if (d.isExam) return@filter (includeDone || (!d.done && d.over.isAfter(now))) && (window == null || window.contains(d.due) || (!d.due.isAfter(now) && d.over.isAfter(now)))
            val overdue = !d.done && !d.due.isAfter(now)
            (includeDone || !d.done) && (window == null || overdue || window.contains(d.due))
        }.sortedWith(compareBy({ it.due }, { it.id })).forEach { d ->
            rows.put(
                JSONObject()
                    .put("id", d.id)
                    .put("kind", d.kind.id)
                    .put("title", d.title)
                    .put("due_kst", EtlLogic.fmtKst(d.due))
                    .put("remaining", EtlLogic.remaining(d.due, now))
                    .apply {
                        if (d.isExam) {
                            put("start_kst", EtlLogic.fmtKst(d.due))
                            d.end?.let { put("end_kst", EtlLogic.fmtKst(it)) }
                            if (d.location.isNotBlank()) put("location", d.location)
                            if (d.course.isNotBlank()) put("course", d.course)
                            if (d.supplies.isNotBlank()) put("supplies", d.supplies)
                        }
                    }
                    .put("done", d.done)
                    .put("overdue", !d.isExam && !d.done && !d.due.isAfter(now))
                    .apply { if (d.reminders.isNotEmpty()) put("reminders_kst", JSONArray(d.reminders.map { EtlLogic.fmtKst(it) })) }
                    .apply { if (d.memo.isNotBlank()) put("memo", d.memo) }
                    .apply {
                        if (d.repeat != null) {
                            put("occurrence", d.occurrence?.toString())
                            put("repeat", repeatJson(d).put("label", repeatLabel(d)))
                        }
                    }
            )
        }
        return JSONObject()
            .put("now_kst", EtlLogic.fmtKst(now))
            .put("source", "사용자가 앱의 '내 마감'에 직접 등록한 마감과 시험(kind=exam, due_kst=시작 시각). eTL 과제 아님")
            .put("count", rows.length())
            .put("items", rows)
    }

    /** 비서 도구 결과용 반복 규칙: `{"freq":"weekly","weekdays":["mon","wed"],"until":"2026-12-20"}`. */
    fun repeatJson(d: MyDeadline): JSONObject {
        val r = d.repeat ?: return JSONObject().put("freq", "none")
        return JSONObject().put("freq", r.freq.id)
            .apply { if (r.freq == MyRepeat.Freq.WEEKLY || r.freq == MyRepeat.Freq.BIWEEKLY) put("weekdays", JSONArray(weekdaysOf(d).sorted().map { weekdayId(it) })) }
            .apply { r.until?.let { put("until", it.toString()) } }
            .apply { r.count?.let { put("count", it) } }
    }

    /** "YYYY-MM-DD HH:MM"(또는 T 구분, 초 허용)을 KST 시각으로. 날짜만 오면 그날 23:59. 못 읽으면 null. */
    fun parseDue(s: String?): Instant? {
        val t = s?.trim()?.replace('T', ' ') ?: return null
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:\\s+(\\d{1,2}):(\\d{2})(?::\\d{2})?)?$").find(t) ?: return null
        return try {
            val date = java.time.LocalDate.parse(m.groupValues[1])
            val time = if (m.groupValues[2].isEmpty()) java.time.LocalTime.of(23, 59)
            else java.time.LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt())
            date.atTime(time).atZone(EtlLogic.KST).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    /** 시험 끝: "YYYY-MM-DD HH:MM" 또는 [start] 날짜(KST)의 "HH:MM". 날짜만은 받지 않는다. 못 읽으면 null. */
    fun parseEnd(s: String?, start: Instant): Instant? {
        val t = s?.trim() ?: return null
        if (t.length > 10) return parseDue(t)
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return null
        return try {
            start.atZone(EtlLogic.KST).toLocalDate().atTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()).atZone(EtlLogic.KST).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    /** 채팅에 보일 한 줄: `장학금 서류 · 11/23(월) 23:59`. */
    fun line(d: MyDeadline): String =
        if (d.isExam) examTitle(d) + " · " + examTimeLabel(d) + (d.location.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
            (d.supplies.takeIf { it.isNotBlank() }?.let { " · 준비물: $it" } ?: "")
        else "${d.title} · ${NoticeText.dateLabel(d.due)}" + (if (d.repeat != null) " · 🔁 ${repeatLabel(d)}" else "")

    fun toJson(list: List<MyDeadline>): String = JSONArray().also { arr ->
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("title", d.title)
                    .put("due", d.due.toEpochMilli())
                    .put("memo", d.memo)
                    .put("done", d.done)
                    .put("reminders", JSONArray(d.reminders.map { it.toEpochMilli() }))
                    .apply { d.sourceAnnouncementId?.let { put("srcAnn", it) } }
                    .apply {
                        if (!d.isExam) return@apply
                        put("kind", d.kind.id)
                        d.end?.let { put("end", it.toEpochMilli()) }
                        if (d.location.isNotEmpty()) put("location", d.location)
                        if (d.course.isNotEmpty()) put("course", d.course)
                        if (d.supplies.isNotEmpty()) put("supplies", d.supplies)
                    }
                    .apply {
                        val r = d.repeat ?: return@apply
                        put("repeat", JSONObject().put("freq", r.freq.id).put("weekdays", JSONArray(r.weekdays.sorted().map { it.value }))
                            .apply { r.until?.let { put("until", it.toString()) } }
                            .apply { r.count?.let { put("count", it) } })
                        put("doneDates", JSONArray(d.doneDates.sorted().map { it.toString() }))
                        put("skipDates", JSONArray(d.skipDates.sorted().map { it.toString() }))
                    }
            )
        }
    }.toString()

    /** 깨진 항목은 건너뛴다. 전체가 깨져 있으면 빈 목록. */
    fun fromJson(s: String?): List<MyDeadline> {
        if (s.isNullOrEmpty()) return emptyList()
        val arr = try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<MyDeadline>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            val title = o.optString("title", "").trim()
            if (id == 0L || title.isEmpty() || !o.has("due")) continue
            val kind = MyKind.parse(o.optString("kind", "")) ?: MyKind.DEADLINE
            val exam = kind == MyKind.EXAM
            out += MyDeadline(
                sourceAnnouncementId = if (o.has("srcAnn") && !o.isNull("srcAnn")) o.optLong("srcAnn").takeIf { it > 0 } else null,
                kind = kind,
                end = if (exam && o.has("end") && !o.isNull("end")) Instant.ofEpochMilli(o.optLong("end")).takeIf { it.toEpochMilli() > o.optLong("due") } else null,
                location = if (exam) o.optString("location", "").take(LOCATION_MAX) else "",
                course = if (exam) o.optString("course", "").take(COURSE_MAX) else "",
                supplies = if (exam) o.optString("supplies", "").take(SUPPLIES_MAX) else "",
                id = id,
                title = title.take(TITLE_MAX),
                due = Instant.ofEpochMilli(o.optLong("due")),
                memo = o.optString("memo", "").take(MEMO_MAX),
                done = o.optBoolean("done", false),
                reminders = o.optJSONArray("reminders")?.let { a -> (0 until a.length()).map { Instant.ofEpochMilli(a.optLong(it)) } }.orEmpty(),
                repeat = if (exam) null else o.optJSONObject("repeat")?.let(::repeatFromJson),
                doneDates = dates(o.optJSONArray("doneDates")),
                skipDates = dates(o.optJSONArray("skipDates")),
            )
        }
        return out.distinctBy { it.id }
    }

    /** 모르는 반복 종류면 null(반복 없음으로 본다). */
    private fun repeatFromJson(o: JSONObject): MyRepeat? {
        val freq = MyRepeat.Freq.parse(o.optString("freq")) ?: return null
        val days = o.optJSONArray("weekdays")?.let { a -> (0 until a.length()).mapNotNull { a.optInt(it).takeIf { v -> v in 1..7 }?.let(DayOfWeek::of) } }.orEmpty()
        val until = o.optString("until", "").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val count = if (o.has("count") && !o.isNull("count")) o.optInt("count", 0).takeIf { it in 1..COUNT_MAX } else null
        return MyRepeat(freq, days.toSet(), until, count)
    }

    private fun dates(a: JSONArray?): Set<LocalDate> =
        if (a == null) emptySet() else (0 until a.length()).mapNotNull { runCatching { LocalDate.parse(a.optString(it)) }.getOrNull() }.toSet()
}

/** 앱 내부 SharedPreferences에 JSON으로 저장한다. */
class MyDeadlineStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("my_deadlines", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<MyDeadline> = MyDeadlines.fromJson(prefs.getString("items", null))

    @Synchronized
    fun save(list: List<MyDeadline>) {
        prefs.edit().putString("items", MyDeadlines.toJson(list)).apply()
        kr.snu.etlassistant.widget.HomeWidgets.dataChanged(app)
        kr.snu.etlassistant.calendar.CalendarExport.requestSync(app) // 캘린더로 내보내기가 켜져 있으면 뒤에서 맞춘다
    }

    /** 같은 ID면 바꾸고, 없으면 더한다. */
    @Synchronized
    fun upsert(d: MyDeadline) {
        val list = load().filter { it.id != d.id } + d
        save(list)
    }

    @Synchronized
    fun delete(id: Long) {
        save(load().filter { it.id != id })
    }
}
