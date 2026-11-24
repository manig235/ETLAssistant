package kr.snu.etlassistant.calendar

import kr.snu.etlassistant.notify.NoticeText
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter

/**
 * 비서 캘린더 도구(calendar_events / calendar_add / calendar_update / calendar_delete)의 순수 로직(2026-11-24):
 * 인자 읽기·검사, 되돌리기용 백업, 채팅 알림 문구. 기기 쪽은 CalendarAssistant.kt.
 */
object CalendarToolLogic {
    val ZONE = CalendarLogic.ZONE
    const val TITLE_MAX = 100
    const val LOCATION_MAX = 100
    const val MEMO_MAX = 1000
    const val MAX_RANGE_DAYS = 62L
    const val MAX_EVENT_DAYS = 31L

    class ArgException(message: String) : Exception(message)

    data class Window(val start: Instant, val end: Instant)

    /**
     * 조회 기간. range: today(기본) | this_week(KST 월~일) | next_week | days(지금부터 days일).
     * start/end(YYYY-MM-DD, end 포함)를 주면 그 기간. 최대 [MAX_RANGE_DAYS]일.
     */
    fun window(range: String?, days: Int?, start: String?, end: String?, now: Instant): Window {
        val today = now.atZone(ZONE).toLocalDate()
        if (!start.isNullOrBlank() || !end.isNullOrBlank()) {
            val s = start?.takeIf { it.isNotBlank() }?.let(::date) ?: today
            val e = end?.takeIf { it.isNotBlank() }?.let(::date) ?: s
            if (e.isBefore(s)) throw ArgException("end는 start보다 앞일 수 없습니다")
            if (Duration.between(s.atStartOfDay(), e.atStartOfDay()).toDays() >= MAX_RANGE_DAYS) throw ArgException("한 번에 ${MAX_RANGE_DAYS}일까지 조회할 수 있습니다")
            return Window(s.atStartOfDay(ZONE).toInstant(), e.plusDays(1).atStartOfDay(ZONE).toInstant())
        }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (range?.trim()?.lowercase().orEmpty().ifEmpty { "today" }) {
            "today" -> Window(today.atStartOfDay(ZONE).toInstant(), today.plusDays(1).atStartOfDay(ZONE).toInstant())
            "this_week" -> Window(monday.atStartOfDay(ZONE).toInstant(), monday.plusDays(7).atStartOfDay(ZONE).toInstant())
            "next_week" -> Window(monday.plusDays(7).atStartOfDay(ZONE).toInstant(), monday.plusDays(14).atStartOfDay(ZONE).toInstant())
            "days" -> Window(now, now.plus(Duration.ofDays((days ?: 7).toLong().coerceIn(1, MAX_RANGE_DAYS))))
            else -> throw ArgException("range는 today, this_week, next_week, days 중 하나여야 합니다")
        }
    }

    private fun date(s: String): LocalDate = try { LocalDate.parse(s.trim()) } catch (_: Exception) { throw ArgException("날짜는 YYYY-MM-DD 형식이어야 합니다: $s") }

    /** 일정 시각. 종일이면 [dtStart]·[dtEnd]가 UTC 자정(캘린더 규칙), 아니면 실제 시각. */
    data class When(val allDay: Boolean, val dtStart: Long, val dtEnd: Long) {
        val timezone: String get() = if (allDay) "UTC" else CalendarLogic.TZ_ID
    }

    private val DATE_TIME = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:[ T](\\d{1,2}):(\\d{2})(?::\\d{2})?)?$")
    private val TIME = Regex("^(\\d{1,2}):(\\d{2})$")

    private fun parseDateTime(s: String, name: String): Pair<LocalDate, LocalTime?> {
        val m = DATE_TIME.find(s.trim()) ?: throw ArgException("$name 은(는) \"YYYY-MM-DD HH:MM\" 또는 \"YYYY-MM-DD\"(종일)이어야 합니다")
        val d = date(m.groupValues[1])
        val t = if (m.groupValues[2].isEmpty()) null else try { LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (_: Exception) { throw ArgException("$name 의 시각을 읽을 수 없습니다") }
        return d to t
    }

    /**
     * start/end/all_day 인자를 시각으로. start에 시각이 없으면 종일(all_day=false면 오류).
     * 시간 일정의 end는 "YYYY-MM-DD HH:MM" 또는 시작 날짜의 "HH:MM", 없으면 1시간. 종일의 end는 끝 날짜(포함), 없으면 그날.
     */
    fun parseWhen(start: String?, end: String?, allDay: Boolean?): When {
        if (start.isNullOrBlank()) throw ArgException("start가 필요합니다(\"YYYY-MM-DD HH:MM\", 종일이면 \"YYYY-MM-DD\")")
        val (sd, st) = parseDateTime(start, "start")
        val isAllDay = allDay ?: (st == null)
        if (!isAllDay && st == null) throw ArgException("시간 일정은 start에 시각(HH:MM)이 필요합니다. 종일이면 all_day=true")
        if (isAllDay) {
            val ed = end?.takeIf { it.isNotBlank() }?.let { parseDateTime(it, "end").first } ?: sd
            if (ed.isBefore(sd)) throw ArgException("끝 날짜는 시작 날짜보다 앞일 수 없습니다")
            if (Duration.between(sd.atStartOfDay(), ed.atStartOfDay()).toDays() >= MAX_EVENT_DAYS) throw ArgException("일정은 ${MAX_EVENT_DAYS}일까지 넣을 수 있습니다")
            return When(true, sd.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), ed.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        }
        val s = LocalDateTime.of(sd, st).atZone(ZONE).toInstant()
        val e = when {
            end.isNullOrBlank() -> s.plus(Duration.ofHours(1))
            TIME.matches(end.trim()) -> TIME.find(end.trim())!!.let { m ->
                try { LocalDateTime.of(sd, LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())).atZone(ZONE).toInstant() } catch (_: Exception) { throw ArgException("end 시각을 읽을 수 없습니다") }
            }
            else -> parseDateTime(end, "end").let { (d, t) -> LocalDateTime.of(d, t ?: throw ArgException("시간 일정의 end에는 시각이 필요합니다")).atZone(ZONE).toInstant() }
        }
        if (!e.isAfter(s)) throw ArgException("끝 시각은 시작 시각보다 뒤여야 합니다")
        if (Duration.between(s, e).toDays() >= MAX_EVENT_DAYS) throw ArgException("일정은 ${MAX_EVENT_DAYS}일까지 넣을 수 있습니다")
        return When(false, s.toEpochMilli(), e.toEpochMilli())
    }

    /** 제목·장소·메모 정리와 검사. */
    fun cleanTitle(s: String?): String {
        val t = NoticeText.clean(s).trim()
        if (t.isEmpty()) throw ArgException("title이 필요합니다")
        if (t.length > TITLE_MAX) throw ArgException("title은 ${TITLE_MAX}자까지입니다")
        return t
    }

    fun cleanLocation(s: String?): String = NoticeText.clean(s).trim().also { if (it.length > LOCATION_MAX) throw ArgException("location은 ${LOCATION_MAX}자까지입니다") }

    fun cleanMemo(s: String?): String = CalendarLogic.cleanMultiline(s.orEmpty()).also { if (it.length > MEMO_MAX) throw ArgException("memo는 ${MEMO_MAX}자까지입니다") }

    /** 고치거나 지우기 전 일정의 모양(되돌리기용). 반복·참석자 일정은 다루지 않으므로 이 값으로 충분하다. */
    data class Backup(
        val eventId: Long,
        val calendarId: Long,
        val title: String,
        val dtStart: Long,
        val dtEnd: Long,
        val allDay: Boolean,
        val timezone: String,
        val location: String,
        val description: String,
    ) {
        fun toJson(): JSONObject = JSONObject().put("eventId", eventId).put("calendarId", calendarId).put("title", title)
            .put("dtStart", dtStart).put("dtEnd", dtEnd).put("allDay", allDay).put("tz", timezone).put("location", location).put("description", description)

        companion object {
            fun fromJson(o: JSONObject?): Backup? = o?.let {
                Backup(it.optLong("eventId"), it.optLong("calendarId"), it.optString("title"), it.optLong("dtStart"), it.optLong("dtEnd"),
                    it.optBoolean("allDay"), it.optString("tz", CalendarLogic.TZ_ID), it.optString("location"), it.optString("description"))
            }
        }
    }

    enum class Action(val id: String, val label: String) {
        ADD("add", "넣음"), UPDATE("update", "고침"), DELETE("delete", "지움");

        companion object {
            fun of(s: String?) = values().firstOrNull { it.id == s }
        }
    }

    /** 비서가 바꾼 일정 하나(되돌리기 목록). [before]는 고치거나 지우기 전, 넣은 일정은 null. [eventId]는 지금의 일정 ID. */
    data class Change(val id: Long, val at: Long, val action: Action, val eventId: Long, val calendarName: String, val summary: String, val before: Backup?, val undone: Boolean = false)

    const val UNDO_MAX = 30
    val UNDO_KEEP: Duration = Duration.ofDays(14)

    fun changesToJson(list: List<Change>): String = JSONArray().also { a ->
        list.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("at", c.at).put("action", c.action.id).put("eventId", c.eventId).put("calendar", c.calendarName)
                .put("summary", c.summary).put("undone", c.undone).apply { c.before?.let { put("before", it.toJson()) } })
        }
    }.toString()

    fun changesFromJson(s: String?): List<Change> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val action = Action.of(o.optString("action")) ?: return@mapNotNull null
            Change(o.optLong("id"), o.optLong("at"), action, o.optLong("eventId"), o.optString("calendar"), o.optString("summary"),
                Backup.fromJson(o.optJSONObject("before")), o.optBoolean("undone"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun nextChangeId(list: List<Change>, now: Instant): Long = maxOf(now.toEpochMilli(), (list.maxOfOrNull { it.id } ?: 0L) + 1)

    /** Undo one event in reverse order so undoing an older edit cannot overwrite a newer edit. */
    fun canUndo(list: List<Change>, change: Change): Boolean =
        !change.undone && list.firstOrNull { !it.undone && it.eventId == change.eventId }?.id == change.id

    /** Recreating a deleted row changes its provider ID. Keep earlier undo entries attached to that row. */
    fun finishUndo(list: List<Change>, id: Long, restoredEventId: Long? = null): List<Change> {
        val selected = list.firstOrNull { it.id == id } ?: return list
        return list.map { c ->
            val linked = if (restoredEventId != null && c.eventId == selected.eventId) {
                c.copy(eventId = restoredEventId, before = c.before?.copy(eventId = restoredEventId))
            } else c
            if (linked.id == id) linked.copy(undone = true) else linked
        }
    }

    /** 새 기록을 앞에 두고 오래된 것([UNDO_KEEP])과 [UNDO_MAX]개 넘는 것을 버린다. */
    fun addChange(list: List<Change>, c: Change, now: Instant): List<Change> =
        (listOf(c) + list).filter { it.at >= now.minus(UNDO_KEEP).toEpochMilli() }.take(UNDO_MAX)

    /** `11/25(수) 14:00~15:00`, 종일이면 `11/25(수) 종일`(여러 날이면 `~11/26(목)`). */
    fun whenLabel(allDay: Boolean, dtStart: Long, dtEnd: Long): String {
        if (allDay) {
            val sd = Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate()
            val ed = Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).let { if (it.isBefore(sd)) sd else it }
            return kr.snu.etlassistant.notify.Agenda.dateLabel(sd) + (if (ed != sd) "~" + kr.snu.etlassistant.notify.Agenda.dateLabel(ed) else "") + " 종일"
        }
        val s = Instant.ofEpochMilli(dtStart).atZone(ZONE)
        val e = Instant.ofEpochMilli(dtEnd).atZone(ZONE)
        val end = if (e.toLocalDate() == s.toLocalDate()) "%02d:%02d".format(e.hour, e.minute) else NoticeText.dateLabel(e.toInstant())
        return NoticeText.dateLabel(s.toInstant()) + "~" + end
    }

    fun describe(title: String, allDay: Boolean, dtStart: Long, dtEnd: Long, location: String): String =
        "${NoticeText.cut(NoticeText.clean(title), 60)} · ${whenLabel(allDay, dtStart, dtEnd)}" + (location.takeIf { it.isNotBlank() }?.let { " · ${NoticeText.cut(it, 40)}" } ?: "")

    fun describe(b: Backup): String = describe(b.title, b.allDay, b.dtStart, b.dtEnd, b.location)

    /** Approval shows the complete write, including the year, account and content that a short notice omits. */
    fun reviewDetails(calendarName: String, account: String, b: Backup): String {
        val time = if (b.allDay) {
            val start = Instant.ofEpochMilli(b.dtStart).atZone(ZoneOffset.UTC).toLocalDate()
            val end = Instant.ofEpochMilli(b.dtEnd).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            "$start ~ $end (종일, 끝 날짜 포함)"
        } else {
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            "${format.format(Instant.ofEpochMilli(b.dtStart).atZone(ZONE))} ~ ${format.format(Instant.ofEpochMilli(b.dtEnd).atZone(ZONE))} (한국 시각)"
        }
        return "캘린더: $calendarName ($account)\n제목: ${b.title}\n시각: $time\n장소: ${b.location.ifEmpty { "없음" }}\n메모: ${b.description.ifEmpty { "없음" }}"
    }

    /** 채팅 알림 한 줄(바뀌기 전 → 뒤). */
    fun notice(action: Action, calendarName: String, before: Backup?, after: String?): String {
        val where = "\"${NoticeText.cut(calendarName, 30)}\" 캘린더"
        val body = when (action) {
            Action.ADD -> "$where 에 넣음: $after"
            Action.UPDATE -> "$where 일정 고침: ${before?.let(::describe)} → $after"
            Action.DELETE -> "$where 에서 지움: ${before?.let(::describe)}"
        }
        return "🗓 $body · 설정 → 캘린더 연동에서 되돌릴 수 있어요"
    }
}
