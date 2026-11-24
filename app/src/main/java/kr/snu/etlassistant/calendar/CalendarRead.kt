package kr.snu.etlassistant.calendar

import kr.snu.etlassistant.notify.NoticeText
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * 캘린더 → 영민이(2026-11-24). 사용자가 고른 폰 캘린더의 일정을 읽어 내 일정·브리핑·비서 도구에 보여 준다.
 * 이 파일은 순수 함수만 둔다(단위 테스트 대상). ContentResolver로 읽는 부분은 CalendarReader.kt.
 * 네트워크는 쓰지 않는다. 일정 내용(남이 쓴 공유 캘린더 글 포함)은 신뢰하지 않는 데이터다.
 */

/** 캘린더 연동의 "캘린더 → 영민이" 설정. 읽을 캘린더 선택은 내 일정 보기(B)와 비서(C)가 함께 쓴다. */
data class CalendarReadSettings(
    /** 내 일정·브리핑에 고른 캘린더의 일정을 보인다(기본 꺼짐, READ_CALENDAR). */
    val showEnabled: Boolean = false,
    /** 비서가 고른 캘린더를 읽고 일정을 넣고·고치고·지운다(기본 꺼짐, READ/WRITE_CALENDAR). */
    val assistantEnabled: Boolean = false,
    /** 읽을 캘린더(`Calendars._ID`). */
    val calendarIds: Set<Long> = emptySet(),
    /** 비서가 새 일정을 넣을 캘린더(고른 캘린더 중 쓸 수 있는 것). */
    val writeCalendarId: Long? = null,
    val writeCalendarName: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("showEnabled", showEnabled)
        .put("assistantEnabled", assistantEnabled)
        .put("calendarIds", JSONArray(calendarIds.sorted()))
        .put("writeCalendarId", writeCalendarId ?: JSONObject.NULL)
        .put("writeCalendarName", writeCalendarName)
        .toString()

    companion object {
        fun fromJson(s: String?): CalendarReadSettings = try {
            val o = JSONObject(s ?: throw IllegalArgumentException())
            val ids = o.optJSONArray("calendarIds")
            CalendarReadSettings(
                showEnabled = o.optBoolean("showEnabled", false),
                assistantEnabled = o.optBoolean("assistantEnabled", false),
                calendarIds = if (ids == null) emptySet() else (0 until ids.length()).map { ids.optLong(it) }.filter { it > 0 }.toSet(),
                writeCalendarId = if (o.isNull("writeCalendarId")) null else o.optLong("writeCalendarId").takeIf { it > 0 },
                writeCalendarName = o.optString("writeCalendarName", ""),
            )
        } catch (_: Exception) {
            CalendarReadSettings()
        }
    }
}

/** `CalendarContract.Instances`에서 읽은 한 줄(회차). 반복 일정은 회차마다 한 줄이다. */
data class RawInstance(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    val color: Int,
    val title: String?,
    val location: String?,
    val description: String?,
    /** epoch millis. 종일 일정은 UTC 자정 기준(날짜만 의미가 있다). */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val rrule: String? = null,
    val originalId: Long? = null,
    val customAppUri: String? = null,
    /** `Events.STATUS`(2 = 취소). */
    val status: Int? = null,
)

/** 화면·브리핑·도구에 쓰는 일정 하나. 종일 일정은 [start]·[end]가 KST 자정이고 [startDate]~[endDate](포함)가 날짜다. */
data class CalEvent(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    val color: Int,
    val title: String,
    val location: String,
    val description: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val recurring: Boolean,
) {
    val key: String get() = "cal-$eventId-${start.toEpochMilli()}"
}

object CalendarReadLogic {
    val ZONE = CalendarLogic.ZONE
    const val TITLE_MAX = 80
    const val TEXT_MAX = 500
    /** 화면·도구에서 한 번에 보이는 최대 일정 수. */
    const val MAX_EVENTS = 300

    /** 영민이가 넣은 일정인가(내보내기한 일정: 우리 URI, 매핑된 ID, 앱 캘린더). 내 일정에 두 번 보이지 않게 뺀다. */
    fun isOurs(r: RawInstance, exportedEventIds: Set<Long>, ownCalendarIds: Set<Long>): Boolean =
        r.calendarId in ownCalendarIds || r.eventId in exportedEventIds || r.customAppUri?.startsWith(CalendarLogic.APP_URI_PREFIX) == true

    /**
     * 읽은 회차를 일정으로 바꾼다: [selected] 캘린더만, 영민이가 넣은 일정·취소된 일정은 빼고, [from, to)와 겹치는 것만(KST), 시작순.
     * 종일 일정의 날짜는 UTC 기준으로 저장되므로 UTC 날짜를 그대로 KST 날짜로 쓴다(끝은 다음 날 0시라 하루 빼서 포함 날짜로).
     */
    fun events(
        raw: List<RawInstance>,
        selected: Set<Long>,
        exportedEventIds: Set<Long>,
        ownCalendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<CalEvent> = raw.asSequence()
        .filter { it.calendarId in selected && !isOurs(it, exportedEventIds, ownCalendarIds) && it.status != 2 }
        .map(::toEvent)
        .filter { it.end.isAfter(from) && it.start.isBefore(to) || (it.start == it.end && !it.start.isBefore(from) && it.start.isBefore(to)) }
        .distinctBy { it.key }
        .sortedWith(compareBy({ it.start }, { !it.allDay }, { it.title }, { it.eventId }))
        .take(MAX_EVENTS)
        .toList()

    fun toEvent(r: RawInstance): CalEvent {
        val title = NoticeText.clean(r.title).take(TITLE_MAX).ifEmpty { "(제목 없음)" }
        val location = NoticeText.clean(r.location).take(TEXT_MAX)
        val description = CalendarLogic.cleanMultiline(r.description.orEmpty()).take(TEXT_MAX)
        return if (r.allDay) {
            val sd = Instant.ofEpochMilli(r.begin).atZone(ZoneOffset.UTC).toLocalDate()
            val endExclusive = Instant.ofEpochMilli(r.end).atZone(ZoneOffset.UTC).toLocalDate()
            val ed = if (endExclusive.isAfter(sd)) endExclusive.minusDays(1) else sd
            CalEvent(r.eventId, r.calendarId, r.calendarName, r.color, title, location, description,
                sd.atStartOfDay(ZONE).toInstant(), ed.plusDays(1).atStartOfDay(ZONE).toInstant(), true, sd, ed, recurring(r))
        } else {
            val s = Instant.ofEpochMilli(r.begin)
            val e = Instant.ofEpochMilli(maxOf(r.end, r.begin))
            CalEvent(r.eventId, r.calendarId, r.calendarName, r.color, title, location, description,
                s, e, false, s.atZone(ZONE).toLocalDate(), e.minusMillis(1).coerceAtLeast(s).atZone(ZONE).toLocalDate(), recurring(r))
        }
    }

    private fun Instant.coerceAtLeast(o: Instant) = if (isBefore(o)) o else this

    fun recurring(r: RawInstance): Boolean = !r.rrule.isNullOrBlank() || (r.originalId ?: 0L) > 0L

    /** 종일 일정은 범위를 넓혀 읽은 뒤 [events]에서 KST 날짜로 거른다(UTC 저장이라 9시간 어긋날 수 있다). */
    fun queryRange(from: Instant, to: Instant): Pair<Long, Long> =
        from.minus(Duration.ofDays(1)).toEpochMilli() to to.plus(Duration.ofDays(1)).toEpochMilli()

    /** `10:00~11:30`, 종일이면 `종일`, 여러 날 종일이면 `11/24~11/26 종일`, 날을 넘기면 `10:00~11/25 02:00`. */
    fun timeLabel(e: CalEvent): String {
        if (e.allDay) return if (e.startDate == e.endDate) "종일" else "${e.startDate.monthValue}/${e.startDate.dayOfMonth}~${e.endDate.monthValue}/${e.endDate.dayOfMonth} 종일"
        val s = e.start.atZone(ZONE)
        val en = e.end.atZone(ZONE)
        val hs = "%02d:%02d".format(s.hour, s.minute)
        if (e.end == e.start) return hs
        val he = "%02d:%02d".format(en.hour, en.minute)
        return if (en.toLocalDate() == s.toLocalDate() || (en.hour == 0 && en.minute == 0 && en.toLocalDate() == s.toLocalDate().plusDays(1))) "$hs~$he"
        else "$hs~${en.monthValue}/${en.dayOfMonth} $he"
    }

    /** 브리핑 "오늘 일정" 줄: 오늘(KST)과 겹치는 일정, 종일 먼저. `• 종일 · 동아리 MT`, `• 14:00~15:00 · 면담 · 301동`. */
    fun briefLines(events: List<CalEvent>, today: LocalDate): List<String> {
        val start = today.atStartOfDay(ZONE).toInstant()
        val end = today.plusDays(1).atStartOfDay(ZONE).toInstant()
        return events.filter { (it.end.isAfter(start) && it.start.isBefore(end)) || (it.start == it.end && !it.start.isBefore(start) && it.start.isBefore(end)) }
            .sortedWith(compareBy({ !it.allDay }, { it.start }, { it.title }))
            .map { e ->
                "• ${timeLabel(e)} · ${NoticeText.cut(e.title, 50)}" + (e.location.takeIf { it.isNotEmpty() }?.let { " · ${NoticeText.cut(it, 30)}" } ?: "")
            }
    }

    /** 비서 도구 `calendar_events` 결과의 한 줄. 설명은 [TEXT_MAX]자로 자른다. */
    fun eventJson(e: CalEvent, writable: Boolean): JSONObject = JSONObject()
        .put("event_id", e.eventId)
        .put("calendar", e.calendarName)
        .put("title", e.title)
        .put("all_day", e.allDay)
        .apply {
            if (e.allDay) {
                put("start_date", e.startDate.toString())
                put("end_date", e.endDate.toString())
            } else {
                put("start_kst", fmt(e.start))
                put("end_kst", fmt(e.end))
            }
        }
        .apply { if (e.location.isNotEmpty()) put("location", e.location) }
        .apply { if (e.description.isNotEmpty()) put("memo", e.description) }
        .put("recurring", e.recurring)
        .put("can_edit", writable && !e.recurring)

    fun fmt(t: Instant): String = kr.snu.etlassistant.etl.EtlLogic.fmtKst(t)

    /** 캘린더 선택 요약: `캘린더 2개(개인, 학교)`. */
    fun selectionLabel(names: List<String>): String = when {
        names.isEmpty() -> "고른 캘린더가 없어요"
        names.size <= 3 -> "캘린더 ${names.size}개(${names.joinToString(", ")})"
        else -> "캘린더 ${names.size}개(${names.take(2).joinToString(", ")} 외 ${names.size - 2}개)"
    }
}
