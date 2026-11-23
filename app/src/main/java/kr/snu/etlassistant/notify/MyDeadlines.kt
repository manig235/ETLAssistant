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
)

/**
 * 반복 규칙. 매주·격주는 [weekdays](비면 시작일의 요일), 매월은 시작일과 같은 날짜(없는 달은 그달 말일).
 * 격주는 시작일이 든 주(월요일 시작)부터 두 주마다. [until]은 그날(KST)까지 포함, null이면 끝없음.
 */
data class MyRepeat(
    val freq: Freq,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
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

    /** [date](KST)가 이 마감의 회차인가. 건너뛴 회차는 아니다. */
    fun matches(d: MyDeadline, date: LocalDate): Boolean {
        val start = startDate(d)
        val r = d.repeat ?: return date == start
        if (date.isBefore(start) || (r.until != null && date.isAfter(r.until)) || date in d.skipDates) return false
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
        while (!date.isAfter(last) && (r.until == null || !date.isAfter(r.until))) {
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
        val extra = if (upcoming.none { !it.done }) nextOccurrence(d, now) else null
        (listOfNotNull(lastMissed(d, now)) + upcoming + listOfNotNull(extra)).distinctBy { it.occurrence }
    }

    /** 알림·브리핑에서 쓰는 과제 ID. 반복 회차는 회차마다 달라야 알림 기록(`deadline-m:…`)이 회차별로 남는다. */
    fun itemId(d: MyDeadline): Long = d.occurrence?.let { d.id * 100_000 + it.toEpochDay() } ?: d.id

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
        return base + (r.until?.let { " · ${it.monthValue}/${it.dayOfMonth}까지" } ?: "")
    }

    /** 반복 확인: 끝 날짜는 시작 날짜 뒤, 회차가 하나는 있어야 한다. 문제가 없으면 null. */
    fun validateRepeat(d: MyDeadline): String? {
        val r = d.repeat ?: return null
        return when {
            r.until != null && r.until.isBefore(startDate(d)) -> "반복 끝 날짜는 시작 날짜보다 뒤여야 해요"
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

    /** 알림·브리핑용으로 바꾼다. 완료한 항목은 "제출됨", 지난 미완료 항목은 "누락"으로 본다. 반복 마감은 [active] 회차만. */
    fun toItems(list: List<MyDeadline>, now: Instant): List<AssignmentItem> = active(list, now).map { d ->
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

    /** 지금 울릴 알림: 시각이 지났고 마감 전이며 아직 안 보낸 것(폰이 꺼져 있었으면 마감 전까지는 늦게라도 보낸다). 반복 마감은 회차마다. */
    fun reminderNotices(list: List<MyDeadline>, now: Instant, fired: Set<String>): List<Notice> = reminderCandidates(list, now).flatMap { d ->
        if (d.done || !d.due.isAfter(now)) return@flatMap emptyList()
        val due = d.reminders.filter { !it.isAfter(now) && reminderKey(d, it) !in fired }
        if (due.isEmpty()) emptyList() else listOf(
            Notice(
                channel = Channel.DEADLINE,
                title = NoticeText.cut("알림 · ${NoticeText.clean(d.title)}", NoticeText.TITLE_MAX),
                text = NoticeText.cut("마감 ${NoticeText.dateLabel(d.due)} (${EtlLogic.remaining(d.due, now)})" +
                    (if (d.memo.isNotBlank()) " · ${NoticeText.clean(d.memo)}" else ""), NoticeText.LINE_MAX),
                tag = "mine-remind:${itemId(d)}",
                firedKeys = due.map { reminderKey(d, it) },
            )
        )
    }

    /** 다음 알림 시각(없으면 null). */
    fun nextReminderAt(list: List<MyDeadline>, now: Instant, fired: Set<String>): Instant? =
        reminderCandidates(list, now).filter { !it.done && it.due.isAfter(now) }
            .flatMap { d -> d.reminders.filter { it.isAfter(now) && it.isBefore(d.due) && reminderKey(d, it) !in fired } }
            .minOrNull()

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
            val overdue = !d.done && !d.due.isAfter(now)
            (includeDone || !d.done) && (window == null || overdue || window.contains(d.due))
        }.sortedWith(compareBy({ it.due }, { it.id })).forEach { d ->
            rows.put(
                JSONObject()
                    .put("id", d.id)
                    .put("title", d.title)
                    .put("due_kst", EtlLogic.fmtKst(d.due))
                    .put("remaining", EtlLogic.remaining(d.due, now))
                    .put("done", d.done)
                    .put("overdue", !d.done && !d.due.isAfter(now))
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
            .put("source", "사용자가 앱의 '내 마감'에 직접 등록한 마감(eTL 과제 아님)")
            .put("count", rows.length())
            .put("items", rows)
    }

    /** 비서 도구 결과용 반복 규칙: `{"freq":"weekly","weekdays":["mon","wed"],"until":"2026-12-20"}`. */
    fun repeatJson(d: MyDeadline): JSONObject {
        val r = d.repeat ?: return JSONObject().put("freq", "none")
        return JSONObject().put("freq", r.freq.id)
            .apply { if (r.freq == MyRepeat.Freq.WEEKLY || r.freq == MyRepeat.Freq.BIWEEKLY) put("weekdays", JSONArray(weekdaysOf(d).sorted().map { weekdayId(it) })) }
            .apply { r.until?.let { put("until", it.toString()) } }
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

    /** 채팅에 보일 한 줄: `장학금 서류 · 11/23(월) 23:59`. */
    fun line(d: MyDeadline): String = "${d.title} · ${NoticeText.dateLabel(d.due)}" +
        (if (d.repeat != null) " · 🔁 ${repeatLabel(d)}" else "")

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
                    .apply {
                        val r = d.repeat ?: return@apply
                        put("repeat", JSONObject().put("freq", r.freq.id).put("weekdays", JSONArray(r.weekdays.sorted().map { it.value }))
                            .apply { r.until?.let { put("until", it.toString()) } })
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
            out += MyDeadline(
                id = id,
                title = title.take(TITLE_MAX),
                due = Instant.ofEpochMilli(o.optLong("due")),
                memo = o.optString("memo", "").take(MEMO_MAX),
                done = o.optBoolean("done", false),
                reminders = o.optJSONArray("reminders")?.let { a -> (0 until a.length()).map { Instant.ofEpochMilli(a.optLong(it)) } }.orEmpty(),
                repeat = o.optJSONObject("repeat")?.let(::repeatFromJson),
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
        return MyRepeat(freq, days.toSet(), until)
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
