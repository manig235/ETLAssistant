package kr.snu.etlassistant.calendar

import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.notify.AssignmentItem
import kr.snu.etlassistant.notify.CourseFilter
import kr.snu.etlassistant.notify.MyDeadline
import kr.snu.etlassistant.notify.MyDeadlines
import kr.snu.etlassistant.notify.MyRepeat
import kr.snu.etlassistant.notify.NoticeText
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * 캘린더 연동의 "영민이 → 캘린더"(2026-11-23, 처음 이름은 "캘린더로 내보내기"). eTL 과제 마감과 내 마감(시험 포함)을 폰 캘린더(CalendarContract) 또는 .ics 파일로 보낸다.
 * 사용자가 캘린더에서 고친 내 마감 일정은 되가져오고(내 마감 시각·제목·시험 장소), eTL 과제 일정은 원래대로 되돌린다([edits]).
 * 네트워크는 쓰지 않는다: eTL 과제는 알림 확인 때 받아 둔 결과(SnapshotCache), 내 마감은 폰 안 저장값만 쓴다.
 * 이 파일은 순수 함수만 둔다(단위 테스트 대상). ContentResolver를 쓰는 부분은 CalendarExport.kt.
 */

/** 내보낼 곳. LOCAL: 앱이 만든 "영민이" 로컬 캘린더(이 폰에만). GOOGLE: 사용자가 고른 구글 계정 캘린더. */
enum class CalendarTarget(val key: String) {
    LOCAL("local"), GOOGLE("google");

    companion object {
        fun of(key: String?): CalendarTarget = values().firstOrNull { it.key == key } ?: LOCAL
    }
}

data class CalendarExportSettings(
    val enabled: Boolean = false,
    val target: CalendarTarget = CalendarTarget.LOCAL,
    /** GOOGLE일 때 고른 캘린더의 `Calendars._ID`와 보일 이름. */
    val googleCalendarId: Long? = null,
    val googleCalendarName: String = "",
    val includeEtl: Boolean = true,
    val includeMine: Boolean = true,
    /** 제출·완료한 항목도 보낸다(기본 끔: 제출·완료하면 캘린더에서 지운다). */
    val includeDone: Boolean = false,
    /** 캘린더에서 고친 내 마감 일정을 앱에 되가져온다(기본 켬). 끄면 고친 일정도 앱 내용으로 되돌린다. */
    val pullBack: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("enabled", enabled)
        .put("target", target.key)
        .put("googleCalendarId", googleCalendarId ?: JSONObject.NULL)
        .put("googleCalendarName", googleCalendarName)
        .put("includeEtl", includeEtl)
        .put("includeMine", includeMine)
        .put("includeDone", includeDone)
        .put("pullBack", pullBack)
        .toString()

    companion object {
        fun fromJson(s: String?): CalendarExportSettings = try {
            val o = JSONObject(s ?: throw IllegalArgumentException())
            CalendarExportSettings(
                enabled = o.optBoolean("enabled", false),
                target = CalendarTarget.of(o.optString("target")),
                googleCalendarId = if (o.isNull("googleCalendarId")) null else o.optLong("googleCalendarId").takeIf { it > 0 },
                googleCalendarName = o.optString("googleCalendarName", ""),
                includeEtl = o.optBoolean("includeEtl", true),
                includeMine = o.optBoolean("includeMine", true),
                includeDone = o.optBoolean("includeDone", false),
                pullBack = o.optBoolean("pullBack", true),
            )
        } catch (_: Exception) {
            CalendarExportSettings()
        }
    }
}

/** 캘린더 일정 하나(마감 하나). 마감은 [start] == [end]인 0분짜리, 시험은 시작~끝(끝을 모르면 0분) 일정이다. */
data class CalItem(
    /** `etl:{courseId}:{assignmentId}`, `mine:{id}`, 반복 회차는 `mine:{id}:{epochDay}`. */
    val key: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val description: String,
    val url: String? = null,
    /** 시험 장소(EVENT_LOCATION). */
    val location: String = "",
    /** 바쁨으로 표시(시험). 마감은 바쁨 아님. */
    val busy: Boolean = false,
) {
    /** 바뀌었는지 비교하는 값(일정 내용만). 장소가 없으면 예전 값과 같다(이미 넣은 일정을 다시 쓰지 않게). */
    val hash: String get() = CalendarLogic.hash(listOf(title, start.toEpochMilli().toString(), end.toEpochMilli().toString(), description, url.orEmpty(), CalendarLogic.TZ_ID) +
        (if (location.isNotEmpty() || busy) listOf(location, busy.toString()) else emptyList()))

    /** 캘린더에 실제로 들어간 모양(제목·시각·장소)의 값. 사용자가 캘린더에서 고쳤는지 볼 때 쓴다. */
    val rowHash: String get() = CalendarLogic.rowHash(title, start.toEpochMilli(), end.toEpochMilli(), location)
}

/** 캘린더에서 읽은 내보낸 일정의 지금 모양([CalendarLogic.edits]에 쓴다). */
data class CalRow(val eventId: Long, val title: String, val start: Long, val end: Long, val location: String) {
    val hash: String get() = CalendarLogic.rowHash(title, start, end, location)
}

object CalendarLogic {
    const val TZ_ID = "Asia/Seoul"
    val ZONE: ZoneId = ZoneId.of(TZ_ID)
    const val APP_URI_PREFIX = "youngmin://item/"

    /** 지난 마감도 이 기간까지는 캘린더에 남겨 둔다(그보다 오래된 일정은 더 건드리지 않는다). */
    val KEEP_PAST: Duration = Duration.ofDays(14)
    /** 반복 내 마감은 앞으로 이만큼의 회차를 펼쳐 하나씩 넣는다. */
    val REPEAT_AHEAD: Duration = Duration.ofDays(60)
    /** 이보다 오래된 매핑은 잊는다(일정은 캘린더에 그대로 둔다). */
    val FORGET_AFTER: Duration = Duration.ofDays(180)

    fun etlKey(courseId: Long, assignmentId: Long) = "etl:$courseId:$assignmentId"
    fun mineKey(d: MyDeadline): String = "mine:${d.id}" + (d.occurrence?.let { ":${it.toEpochDay()}" } ?: "")
    fun prefixOf(key: String): String = key.substringBefore(':')

    /** `youngmin://item/{key}`에서 내 마감 ID(없으면 null). 캘린더 앱의 "앱에서 열기"용. */
    fun mineIdFromUri(uri: String?): Long? {
        val key = uri?.removePrefix(APP_URI_PREFIX)?.takeIf { uri.startsWith(APP_URI_PREFIX) } ?: return null
        if (prefixOf(key) != "mine") return null
        return key.split(':').getOrNull(1)?.toLongOrNull()
    }

    fun hash(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    // ---- 내보낼 항목 -------------------------------------------------------------

    /** 캘린더에 쓸 eTL 링크: eTL 주소이고 쿼리·조각이 없는 것만(서명 링크·토큰이 붙을 여지를 없앤다). */
    fun safeEtlUrl(url: String?): String? = NoticeText.safeUrl(url)?.takeIf { '?' !in it && '#' !in it }

    fun etlTitle(a: AssignmentItem): String =
        "[${NoticeText.clean(TimetableLogic.shortName(a.courseName)).ifEmpty { "eTL" }}] ${NoticeText.clean(a.name)}" +
            (if (a.submitted) " (제출함)" else "")

    fun etlDescription(a: AssignmentItem): String = listOfNotNull(
        "eTL 과제 · ${NoticeText.clean(a.courseName)}",
        if (a.submitted) "제출함" else if (!a.online) "오프라인 제출" else null,
        safeEtlUrl(a.url),
        FOOTER,
    ).joinToString("\n")

    fun mineTitle(d: MyDeadline): String = if (d.isExam) MyDeadlines.examTitle(d)
    else "[내 마감] ${NoticeText.clean(d.title)}" + (if (d.done) " (완료)" else "")

    fun mineDescription(d: MyDeadline): String = listOfNotNull(
        d.supplies.trim().takeIf { d.isExam && it.isNotEmpty() }?.let { "준비물: ${NoticeText.clean(it)}" },
        d.memo.trim().takeIf { it.isNotEmpty() }?.let { cleanMultiline(it) },
        (if (d.isExam) "시험" else "내 마감") + (if (d.repeat != null) " · ${MyDeadlines.repeatLabel(d)}" else ""),
        FOOTER,
    ).joinToString("\n")

    /** 캘린더 제목에서 앱이 붙인 앞뒤 말을 떼고 내 마감 제목만(되가져오기용). 시험은 과목도 뗀다. 비면 null. */
    fun titleFromCalendar(calendarTitle: String, d: MyDeadline): String? {
        var t = NoticeText.clean(calendarTitle)
        if (d.isExam) {
            t = t.removePrefix("[시험]").trim()
            val course = NoticeText.clean(d.course)
            if (course.isNotEmpty() && t.startsWith("$course ") && !NoticeText.clean(d.title).contains(course)) t = t.removePrefix(course).trim()
        } else {
            t = t.removePrefix("[내 마감]").trim().removeSuffix("(완료)").trim()
        }
        return t.take(MyDeadlines.TITLE_MAX).takeIf { it.isNotEmpty() }
    }

    const val FOOTER = "영민이 앱이 넣은 일정이에요. 앱에서 바뀌면 여기도 바뀌어요."

    /** 줄바꿈은 남기고 줄마다 제어·보이지 않는 문자를 정리한다. */
    fun cleanMultiline(s: String): String = s.lines().joinToString("\n") { NoticeText.clean(it) }.trim()

    fun etlItem(a: AssignmentItem): CalItem? {
        val due = a.due ?: return null
        return CalItem(etlKey(a.courseId, a.assignmentId), etlTitle(a), due, due, etlDescription(a), safeEtlUrl(a.url))
    }

    fun mineItem(d: MyDeadline): CalItem = if (d.isExam)
        CalItem(mineKey(d), mineTitle(d), d.due, d.end ?: d.due, mineDescription(d), location = NoticeText.clean(d.location), busy = true)
    else CalItem(mineKey(d), mineTitle(d), d.due, d.due, mineDescription(d))

    fun rowHash(title: String, start: Long, end: Long, location: String): String =
        hash(listOf(NoticeText.clean(title), start.toString(), end.toString(), NoticeText.clean(location)))

    /**
     * 캘린더에 있어야 할 일정. [assignments]가 null이면(받아 둔 eTL 과제가 없음) eTL 쪽은 비우되 [authoritative]에서 빼서
     * 이미 넣은 eTL 일정을 지우지 않는다. 숨긴 과목은 넣지 않는다. 마감이 [KEEP_PAST]보다 오래된 것은 넣지 않는다.
     * 반복 내 마감은 [now]−[KEEP_PAST] ~ [now]+[REPEAT_AHEAD]의 회차를 하나씩 펼친다(RRULE을 쓰지 않는다).
     */
    data class Desired(val items: List<CalItem>, val authoritative: Set<String>)

    fun desired(
        settings: CalendarExportSettings,
        assignments: List<AssignmentItem>?,
        mine: List<MyDeadline>,
        courses: CourseFilter,
        now: Instant,
    ): Desired {
        val from = now.minus(KEEP_PAST)
        val out = mutableListOf<CalItem>()
        if (settings.includeEtl && assignments != null) {
            assignments.asSequence()
                .filter { it.courseId > 0 && !courses.hidden(it.courseId) }
                .filter { it.due != null && !it.due.isBefore(from) }
                .filter { settings.includeDone || !it.submitted }
                .mapNotNull(::etlItem)
                .forEach { out += it }
        }
        if (settings.includeMine) {
            for (d in mine) {
                val list = if (d.repeat == null) listOf(d).filter { !it.due.isBefore(from) }
                else MyDeadlines.occurrences(d, from, now.plus(REPEAT_AHEAD))
                list.filter { settings.includeDone || !it.done }.forEach { out += mineItem(it) }
            }
        }
        val authoritative = buildSet {
            add("mine")
            if (!settings.includeEtl || assignments != null) add("etl")
        }
        return Desired(out.distinctBy { it.key }, authoritative)
    }

    // ---- 맞추기(reconcile) -------------------------------------------------------

    /**
     * 매핑 한 줄. [eventId]가 [TOMBSTONE]이면 사용자가 캘린더에서 지운 일정(다시 만들지 않는다).
     * [rowHash]는 마지막으로 쓴 뒤 캘린더에서 다시 읽은 모양(제목·시각·장소). 비어 있으면 아직 모른다(예전 매핑).
     * [reverts]는 같은 내용으로 되돌린 횟수(캘린더 쪽이 값을 바꿔 써서 되돌리기가 끝없이 반복되지 않게, [MAX_REVERTS]).
     */
    data class Entry(val eventId: Long, val hash: String, val dueMillis: Long, val rowHash: String = "", val reverts: Int = 0) {
        val tombstone: Boolean get() = eventId == TOMBSTONE
    }

    const val MAX_REVERTS = 3

    /** 캘린더에서 고친 것을 어떻게 할지. */
    sealed class Edit {
        /** 반복 없는 내 마감: 바뀐 제목·시각(시험은 끝·장소도)을 앱에 반영한다. null은 그대로. */
        data class PullMine(val key: String, val id: Long, val title: String?, val start: Instant?, val end: Instant?, val location: String?) : Edit()
        /** eTL 과제·반복 회차·되가져오기 꺼짐: 앱 내용으로 되돌린다. */
        data class Revert(val key: String, val repeating: Boolean) : Edit()
        /** 같은 일정을 [MAX_REVERTS]번 되돌려도 다시 바뀌면 지금 모양을 받아들인다(되돌리지 않음). */
        data class Accept(val key: String, val rowHash: String) : Edit()
        /** 예전 매핑이라 모양을 몰랐다: 지금 모양을 기준으로 삼는다. */
        data class Baseline(val key: String, val rowHash: String) : Edit()
    }

    /**
     * 사용자가 캘린더에서 고친 일정을 찾는다. [rows]는 매핑된 일정 중 아직 있는 것의 지금 모양.
     * - 앱에서도 바뀌었으면(desired 해시 ≠ 매핑 해시) 앱이 이긴다(plan의 Update가 덮는다): 여기서는 아무것도 하지 않는다.
     * - 반복 없는 내 마감 + [pullBack]: 바뀐 제목·시각(시험은 끝·장소)을 되가져온다. 제목이 비면 제목은 그대로.
     * - 반복 내 마감 회차: 반복 전체를 바꾸지 않고 되돌린다(앱에는 반영하지 않음).
     * - eTL 과제(읽기 전용)·[pullBack] 꺼짐: 되돌린다.
     */
    fun edits(desired: Desired, mapping: Map<String, Entry>, rows: Map<Long, CalRow>, mine: List<MyDeadline>, pullBack: Boolean): List<Edit> {
        val out = mutableListOf<Edit>()
        val wanted = desired.items.associateBy { it.key }
        for ((key, e) in mapping) {
            if (e.tombstone) continue
            val row = rows[e.eventId] ?: continue
            val item = wanted[key] ?: continue
            if (item.hash != e.hash) continue // 앱 쪽이 바뀜 → plan이 고친다
            if (e.rowHash.isEmpty()) { out += Edit.Baseline(key, row.hash); continue }
            if (row.hash == e.rowHash) continue
            val parts = key.split(':')
            val mineId = if (parts[0] == "mine") parts.getOrNull(1)?.toLongOrNull() else null
            val repeating = parts[0] == "mine" && parts.size > 2
            val d = mineId?.let { id -> mine.firstOrNull { it.id == id } }
            if (pullBack && d != null && !repeating && d.repeat == null) {
                val title = titleFromCalendar(row.title, d)?.takeIf { NoticeText.clean(row.title) != item.title && it != d.title }
                val start = Instant.ofEpochMilli(row.start).takeIf { row.start != item.start.toEpochMilli() }
                val end = if (d.isExam) Instant.ofEpochMilli(row.end).takeIf { row.end != item.end.toEpochMilli() && row.end > row.start } else null
                val location = if (d.isExam) NoticeText.clean(row.location).take(MyDeadlines.LOCATION_MAX).takeIf { it != item.location } else null
                if (title != null || start != null || end != null || location != null) {
                    out += Edit.PullMine(key, d.id, title, start, end, location)
                    continue
                }
                // 되가져올 것이 없는 변화(설명만 등): 지금 모양을 기준으로.
                out += Edit.Accept(key, row.hash)
                continue
            }
            out += if (e.reverts >= MAX_REVERTS) Edit.Accept(key, row.hash) else Edit.Revert(key, repeating)
        }
        return out
    }

    /**
     * [Edit.PullMine]을 내 마감에 적용한 새 값. 시각이 바뀌면 새 ID(알림 기록 규칙, 화면과 같다)이고 알림 시각은 같은 만큼 옮긴다.
     * 시험의 끝은 시작을 옮기면 길이를 유지한다(끝도 바뀌었으면 그 값). 검증에 걸리면 null(되돌린다).
     */
    fun applyPull(d: MyDeadline, e: Edit.PullMine, newId: Long): MyDeadline? {
        val start = e.start ?: d.due
        val shift = Duration.between(d.due, start)
        val end = if (!d.isExam) null else e.end ?: d.end?.plus(shift)
        val new = d.copy(
            id = if (start != d.due) newId else d.id,
            title = e.title ?: d.title,
            due = start,
            end = end,
            location = e.location ?: d.location,
            reminders = d.reminders.map { it.plus(shift) }.filter { it.isBefore(start) },
        )
        if (MyDeadlines.validate(new.title, new.memo, 0) != null || MyDeadlines.validateExam(new) != null) return null
        return new
    }

    const val TOMBSTONE = -1L

    sealed class Op {
        data class Insert(val item: CalItem) : Op()
        data class Update(val eventId: Long, val item: CalItem) : Op()
        data class Delete(val key: String, val eventId: Long) : Op()
    }

    data class Plan(
        val ops: List<Op>,
        /** 캘린더에서 없어진 일정 → 묘비로 바꿀 키. */
        val tombstone: List<String>,
        /** 매핑에서만 지울 키(일정은 건드리지 않음). */
        val forget: List<String>,
    ) {
        val inserts get() = ops.count { it is Op.Insert }
        val updates get() = ops.count { it is Op.Update }
        val deletes get() = ops.count { it is Op.Delete }
    }

    /**
     * [mapping]과 [desired]를 비교해 할 일을 정한다. [live]는 매핑된 일정 중 캘린더에 아직 있는(DELETED=0) 일정 ID.
     * - 새 항목은 넣고, 내용이 바뀐 항목은 고친다.
     * - 사용자가 캘린더에서 지운 일정(매핑은 있는데 [live]에 없음)은 묘비로 남기고 다시 만들지 않는다.
     * - 원본이 사라졌거나 제출·완료된 항목은 지운다. 단 [Desired.authoritative]에 없는 종류는 건드리지 않고,
     *   마감이 [KEEP_PAST]보다 오래된 일정은 지난 기록으로 남겨 둔다([FORGET_AFTER]가 지나면 매핑만 잊는다).
     */
    fun plan(desired: Desired, mapping: Map<String, Entry>, live: Set<Long>, now: Instant, forceUpdate: Set<String> = emptySet()): Plan {
        val ops = mutableListOf<Op>()
        val tomb = mutableListOf<String>()
        val forget = mutableListOf<String>()
        val wanted = desired.items.associateBy { it.key }
        for (item in desired.items) {
            val e = mapping[item.key]
            when {
                e == null -> ops += Op.Insert(item)
                e.tombstone -> Unit
                e.eventId !in live -> tomb += item.key
                e.hash != item.hash || item.key in forceUpdate -> ops += Op.Update(e.eventId, item)
            }
        }
        val keepFrom = now.minus(KEEP_PAST).toEpochMilli()
        val forgetBefore = now.minus(FORGET_AFTER).toEpochMilli()
        for ((key, e) in mapping) {
            if (key in wanted) continue
            when {
                prefixOf(key) !in desired.authoritative -> Unit
                e.tombstone || e.eventId !in live -> forget += key
                e.dueMillis < forgetBefore -> forget += key
                e.dueMillis < keepFrom -> Unit
                else -> ops += Op.Delete(key, e.eventId)
            }
        }
        return Plan(ops, tomb, forget)
    }

    fun mappingToJson(calendarId: Long, entries: Map<String, Entry>): String = JSONObject()
        .put("version", 1)
        .put("calendarId", calendarId)
        .put("entries", JSONObject().also { o ->
            entries.forEach { (k, e) -> o.put(k, JSONArray().put(e.eventId).put(e.hash).put(e.dueMillis).put(e.rowHash).put(e.reverts)) }
        }).toString()

    data class Mapping(val calendarId: Long, val entries: Map<String, Entry>)

    fun mappingFromJson(s: String?): Mapping? = try {
        val o = JSONObject(s ?: throw IllegalArgumentException())
        val entries = mutableMapOf<String, Entry>()
        o.optJSONObject("entries")?.let { e ->
            e.keys().forEach { k ->
                val a = e.optJSONArray(k) ?: return@forEach
                entries[k] = Entry(a.optLong(0, TOMBSTONE), a.optString(1, ""), a.optLong(2, 0L), a.optString(3, ""), a.optInt(4, 0))
            }
        }
        Mapping(o.optLong("calendarId", -1L), entries)
    } catch (_: Exception) {
        null
    }

    // ---- 반복 규칙(RRULE) ---------------------------------------------------------

    private val BYDAY = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val UTC_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZONE)

    fun byDay(days: Collection<DayOfWeek>): String = days.sorted().joinToString(",") { BYDAY[it.value - 1] }

    /**
     * 내 마감 반복 규칙 → RFC 5545 RRULE(앞의 `RRULE:` 없이). 반복이 없으면 null.
     * - 매월: 시작일이 28일 이하면 `BYMONTHDAY=d`, 31일이면 `BYMONTHDAY=-1`(앱처럼 그달 말일).
     *   29·30일이면 `BYMONTHDAY=d`를 쓰므로 그날이 없는 2월 회차는 캘린더 앱에서 빠진다(앱은 2월 말일로 맞춘다. 알려진 차이).
     * - 격주: `INTERVAL=2;WKST=MO`. 시작 시각([dtstartFor])이 규칙에 맞는 회차여야 앱과 같은 주에 맞춰진다.
     * - 끝 날짜: 그날(KST) 23:59:59를 UTC로 바꾼 `UNTIL`(그날 회차 포함).
     */
    fun rrule(d: MyDeadline, fromFirst: Boolean = true): String? {
        val r = d.repeat ?: return null
        val start = d.due.atZone(ZONE).toLocalDate()
        val base = when (r.freq) {
            MyRepeat.Freq.DAILY -> "FREQ=DAILY"
            MyRepeat.Freq.WEEKLY -> "FREQ=WEEKLY;BYDAY=${byDay(MyDeadlines.weekdaysOf(d))}"
            MyRepeat.Freq.BIWEEKLY -> "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=${byDay(MyDeadlines.weekdaysOf(d))}"
            MyRepeat.Freq.MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=" + (if (start.dayOfMonth == 31) "-1" else start.dayOfMonth.toString())
        }
        // 끝: 횟수면 COUNT. 단 시작 회차가 첫 회차가 아니거나(나중 회차에서 추가), 날짜도 함께 있거나, 매월 29·30일(캘린더 앱이 2월을 빼서 횟수가 어긋남)이면
        // 실제 마지막 회차 시각을 UNTIL로 쓴다(RFC 5545는 COUNT와 UNTIL을 함께 못 쓴다).
        val monthlyGap = r.freq == MyRepeat.Freq.MONTHLY && start.dayOfMonth in 29..30
        val end = when {
            r.count != null && r.until == null && fromFirst && !monthlyGap -> ";COUNT=${r.count}"
            r.count != null -> MyDeadlines.lastOccurrence(d)?.let { ";UNTIL=" + UTC_FMT.format(it.due) } ?: ";COUNT=${r.count}"
            r.until != null -> ";UNTIL=" + UTC_FMT.format(r.until.atTime(23, 59, 59).atZone(ZONE))
            else -> ""
        }
        return base + end
    }

    /** 이 날짜가 [rrule]이 만드는 회차인가(29·30일 매월 반복의 2월 말일처럼 앱과 다른 날을 거른다). */
    private fun rruleHas(d: MyDeadline, date: LocalDate): Boolean {
        val r = d.repeat ?: return true
        if (r.freq != MyRepeat.Freq.MONTHLY) return true
        val day = d.due.atZone(ZONE).dayOfMonth
        return day == 31 || date.dayOfMonth == day
    }

    /**
     * 반복 마감을 RRULE로 넣을 때 시작 회차: [from] 회차(없으면 첫 회차)부터 규칙에 맞는 첫 회차. [d]는 저장된 원래 항목.
     * 없으면 null.
     */
    fun dtstartFor(d: MyDeadline, from: MyDeadline? = null): MyDeadline? {
        if (d.repeat == null) return d
        val begin = (from?.due ?: d.due).minusSeconds(1)
        return MyDeadlines.occurrences(d, begin, begin.plus(Duration.ofDays(400)))
            .firstOrNull { rruleHas(d, it.occurrence ?: return@firstOrNull false) }
    }

    fun utc(t: Instant): String = UTC_FMT.format(t)
    fun local(t: Instant): String = LOCAL_FMT.format(t)

    // ---- .ics(RFC 5545) ---------------------------------------------------------

    /** TEXT 값 이스케이프: `\` `;` `,` 줄바꿈. 그 밖의 제어문자는 지운다. */
    fun escapeText(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == ';' -> sb.append("\\;")
                ch == ',' -> sb.append("\\,")
                ch == '\r' -> { sb.append("\\n"); if (i + 1 < s.length && s[i + 1] == '\n') i++ }
                ch == '\n' -> sb.append("\\n")
                Character.isISOControl(ch) -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /** 한 줄을 75옥텟(UTF-8)씩 접는다. 이어지는 줄은 공백 하나로 시작한다. 글자(코드 포인트)를 가운데서 자르지 않는다. */
    fun fold(line: String): String {
        val sb = StringBuilder(line.length + 8)
        var bytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val n = Character.charCount(cp)
            val b = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + b > limit) {
                sb.append("\r\n ")
                bytes = 1
                limit = 75
            }
            sb.appendCodePoint(cp)
            bytes += b
            i += n
        }
        return sb.toString()
    }

    /**
     * eTL 과제와 내 마감을 VCALENDAR로. 반복 내 마감은 펼치지 않고 RRULE 하나로 넣고, 건너뛴 회차(와 [CalendarExportSettings.includeDone]이
     * 꺼져 있으면 완료한 회차)는 EXDATE로 뺀다. 0분짜리 일정이라 DTEND는 넣지 않는다(RFC 5545: DTEND는 DTSTART보다 뒤여야 함).
     */
    fun ics(
        settings: CalendarExportSettings,
        assignments: List<AssignmentItem>,
        mine: List<MyDeadline>,
        courses: CourseFilter,
        now: Instant,
    ): String {
        val from = now.minus(KEEP_PAST)
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//etl-android//Youngmin//KO",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:${escapeText("영민이")}",
            "X-WR-TIMEZONE:$TZ_ID",
            "BEGIN:VTIMEZONE",
            "TZID:$TZ_ID",
            "BEGIN:STANDARD",
            "DTSTART:19700101T000000",
            "TZOFFSETFROM:+0900",
            "TZOFFSETTO:+0900",
            "TZNAME:KST",
            "END:STANDARD",
            "END:VTIMEZONE",
        )
        val stamp = utc(now)
        fun event(uidKey: String, title: String, start: Instant, desc: String, url: String?, extra: List<String> = emptyList(), end: Instant? = null, location: String = "") {
            lines += "BEGIN:VEVENT"
            lines += "UID:${uidKey.replace(':', '-')}@youngmin.etl-android"
            lines += "DTSTAMP:$stamp"
            lines += "DTSTART;TZID=$TZ_ID:${local(start)}"
            if (end != null && end.isAfter(start)) lines += "DTEND;TZID=$TZ_ID:${local(end)}"
            lines += extra
            lines += "SUMMARY:${escapeText(title)}"
            if (location.isNotEmpty()) lines += "LOCATION:${escapeText(location)}"
            lines += "DESCRIPTION:${escapeText(desc)}"
            if (url != null) lines += "URL:$url"
            lines += if (end != null && end.isAfter(start)) "TRANSP:OPAQUE" else "TRANSP:TRANSPARENT"
            lines += "END:VEVENT"
        }
        if (settings.includeEtl) {
            assignments.asSequence()
                .filter { it.courseId > 0 && !courses.hidden(it.courseId) }
                .filter { it.due != null && !it.due.isBefore(from) }
                .filter { settings.includeDone || !it.submitted }
                .sortedBy { it.due }
                .forEach { a -> etlItem(a)?.let { event(it.key, it.title, it.start, it.description, it.url) } }
        }
        if (settings.includeMine) {
            for (d in mine.sortedBy { it.due }) {
                val r = d.repeat
                if (r == null) {
                    if (d.due.isBefore(from) || (!settings.includeDone && d.done)) continue
                    val item = mineItem(d)
                    event(mineKey(d), item.title, d.due, item.description, null, end = item.end, location = item.location)
                    continue
                }
                val lastDate = MyDeadlines.lastDate(d)
                if (lastDate != null && lastDate.isBefore(from.atZone(ZONE).toLocalDate())) continue
                val first = dtstartFor(d) ?: continue
                val fromFirst = first.occurrence == MyDeadlines.firstOccurrence(d)?.occurrence
                val time = d.due.atZone(ZONE).toLocalTime()
                val ex = (d.skipDates + if (settings.includeDone) emptySet() else d.doneDates)
                    .filter { !it.isBefore(first.occurrence ?: it) }.sorted()
                    .map { local(it.atTime(time).atZone(ZONE).toInstant()) }
                val extra = listOfNotNull(
                    "RRULE:${rrule(d, fromFirst)}",
                    ex.takeIf { it.isNotEmpty() }?.let { "EXDATE;TZID=$TZ_ID:${it.joinToString(",")}" },
                )
                event("mine:${d.id}", mineTitle(d.copy(done = false)), first.due, mineDescription(d), null, extra)
            }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    fun icsFileName(now: Instant): String = "youngmin-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZONE).format(now) + ".ics"

    // ---- 상태 문구 ---------------------------------------------------------------

    fun resultLabel(plan: Plan, at: Instant): String {
        val z = at.atZone(ZONE)
        val parts = listOfNotNull(
            plan.inserts.takeIf { it > 0 }?.let { "추가 $it" },
            plan.updates.takeIf { it > 0 }?.let { "바뀜 $it" },
            plan.deletes.takeIf { it > 0 }?.let { "지움 $it" },
        )
        return "%d/%d %02d:%02d 맞춤 · ".format(z.monthValue, z.dayOfMonth, z.hour, z.minute) + (parts.joinToString(" · ").ifEmpty { "바뀐 것 없음" })
    }

    /** 되가져오기 결과 문구: ` · 캘린더에서 고친 내 마감 1개 반영 · 되돌림 2`. */
    fun editsLabel(pulled: Int, reverted: Int, repeatingReverted: Int): String = listOfNotNull(
        pulled.takeIf { it > 0 }?.let { "캘린더에서 고친 내 마감 ${it}개 반영" },
        (reverted - repeatingReverted).takeIf { it > 0 }?.let { "eTL 과제 일정 되돌림 $it" },
        repeatingReverted.takeIf { it > 0 }?.let { "반복 회차 되돌림 $it(반복 마감은 앱에서 고쳐 주세요)" },
    ).joinToString("") { " · $it" }
}
