package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.TimetableLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/*
 * 과목별 알림 끄기·숨기기(2026-11-23, 사용자 요청 "과목별로 알림 끄거나 숨기는 기능").
 * - 알림 끄기: 그 과목의 마감·공지·새 과제·마감 변경·성적·수업 알림을 보내지 않는다. 중복 방지 기록과 기준선은 계속 갱신해서
 *   나중에 다시 켜도 그동안 쌓인 알림이 한꺼번에 오지 않는다.
 * - 숨기기: 알림 끄기 + 내 일정·브리핑·일정 위젯에서 과제·공지를 뺀다. 시간표(화면·위젯·브리핑의 오늘 수업)는 그대로 둔다.
 * 비서(Codex)의 eTL 도구에는 적용하지 않는다(숨겨도 물어보면 답한다). 내 마감(강의 ID −1)과 직접 추가한 수업(음수 ID)은 대상이 아니다.
 * 저장은 폰 안 SharedPreferences([CourseFilterStore]), 과목 ID 기준.
 */

enum class CourseMode(val key: String, val label: String) {
    SHOW("show", "알림 받기"),
    MUTE("mute", "알림 끄기"),
    HIDE("hide", "숨기기");

    companion object {
        fun of(key: String?): CourseMode = values().firstOrNull { it.key == key } ?: SHOW
    }
}

/** 설정 화면 목록의 과목 하나. */
data class CourseRef(val id: Long, val name: String)

data class CourseFilter(
    val modes: Map<Long, CourseMode> = emptyMap(),
    /** 정한 과목의 이름(ID 없이 과목명으로 넣은 보강 수업을 찾을 때, 목록에서 빠진 과목을 보일 때 쓴다). */
    val names: Map<Long, String> = emptyMap(),
) {
    fun mode(courseId: Long?): CourseMode = if (courseId == null || courseId <= 0) CourseMode.SHOW else modes[courseId] ?: CourseMode.SHOW
    fun muted(courseId: Long?): Boolean = mode(courseId) != CourseMode.SHOW
    fun hidden(courseId: Long?): Boolean = mode(courseId) == CourseMode.HIDE

    val isEmpty: Boolean get() = modes.values.all { it == CourseMode.SHOW }
    val mutedCount: Int get() = modes.values.count { it == CourseMode.MUTE }
    val hiddenCount: Int get() = modes.values.count { it == CourseMode.HIDE }

    fun with(course: CourseRef, mode: CourseMode): CourseFilter =
        if (course.id <= 0) this
        else if (mode == CourseMode.SHOW) CourseFilter(modes - course.id, names - course.id)
        else CourseFilter(modes + (course.id to mode), names + (course.id to course.name))

    // ---- 과제·공지 --------------------------------------------------------------

    /** 알림 대상만(끈 과목·숨긴 과목 제외). */
    fun alertable(s: Snapshot): Snapshot = if (isEmpty) s else
        s.copy(assignments = s.assignments.filter { !muted(it.courseId) }, announcements = s.announcements.filter { !muted(it.courseId) })

    /** 끈 과목·숨긴 과목 것만(중복 방지 키를 조용히 기록하는 데 쓴다). */
    fun mutedPart(s: Snapshot): Snapshot =
        s.copy(assignments = s.assignments.filter { muted(it.courseId) }, announcements = s.announcements.filter { muted(it.courseId) }, errors = emptyList())

    /** 목록·브리핑용(숨긴 과목 제외). 숨긴 과목의 조회 오류(`강의명: …`)도 뺀다. */
    fun visible(s: Snapshot): Snapshot {
        if (hiddenCount == 0) return s
        val hiddenNames = modes.filterValues { it == CourseMode.HIDE }.keys.mapNotNull { names[it] }.toSet() +
            s.assignments.filter { hidden(it.courseId) }.map { it.courseName }
        return s.copy(
            assignments = visibleAssignments(s.assignments),
            announcements = s.announcements.filter { !hidden(it.courseId) },
            errors = s.errors.filter { !(it.contains(':') && it.substringBefore(':') in hiddenNames) },
        )
    }

    fun visibleAssignments(items: List<AssignmentItem>): List<AssignmentItem> =
        if (hiddenCount == 0) items else items.filter { !hidden(it.courseId) }

    // ---- 수업 -------------------------------------------------------------------

    /** 수업 행의 과목: ID로 찾고, ID가 없으면(과목명으로 넣은 보강) 정해 둔 과목 이름과 맞춰 본다. */
    fun classMode(row: TimetableLogic.ClassRow): CourseMode {
        row.courseId?.let { return mode(it) }
        return modes.entries.filter { it.value != CourseMode.SHOW && TimetableLogic.sameCourse(row.course, names[it.key]) }
            .maxByOrNull { it.value.ordinal }?.value ?: CourseMode.SHOW
    }

    /** 수업 알림 대상만 남긴 하루. */
    fun alertDay(day: TimetableLogic.Day): TimetableLogic.Day =
        if (isEmpty) day else day.copy(classes = day.classes.filter { classMode(it) == CourseMode.SHOW })
}

/**
 * 끈 과목을 뺀 마감·공지 알림 계획. 끈 과목에서 나왔을 알림의 키는 [Plan.silentKeys]로 넘겨 "보낸 것"으로만 기록한다
 * (다시 켰을 때 지난 공지나 이미 지난 마감 시점 알림이 한꺼번에 오지 않게). 순수 함수.
 */
object CourseMute {
    fun plan(
        snapshot: Snapshot,
        state: NotifyState,
        now: Instant,
        settings: NotifySettings,
        filter: CourseFilter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        if (filter.isEmpty) return NotificationPlanner.plan(snapshot, state, now, settings, zone)
        val main = NotificationPlanner.plan(filter.alertable(snapshot), state, now, settings, zone)
        val muted = NotificationPlanner.plan(filter.mutedPart(snapshot), state, now, settings, zone)
        val silent = (main.silentKeys + muted.silentKeys + muted.notices.flatMap { it.firedKeys }).distinct()
        return main.copy(silentKeys = silent)
    }
}

object CourseFilterLogic {
    fun toJson(f: CourseFilter, known: List<CourseRef>, knownAt: Long): String = JSONObject()
        .put("version", 1)
        .put("courses", JSONObject().also { o ->
            f.modes.forEach { (id, m) ->
                if (m != CourseMode.SHOW) o.put(id.toString(), JSONObject().put("mode", m.key).put("name", f.names[id].orEmpty()))
            }
        })
        .put("known", JSONArray().also { arr -> known.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("knownAt", knownAt)
        .toString()

    data class Stored(val filter: CourseFilter, val known: List<CourseRef>, val knownAt: Long)

    fun fromJson(s: String?): Stored = try {
        val o = JSONObject(s ?: throw IllegalArgumentException())
        val modes = mutableMapOf<Long, CourseMode>()
        val names = mutableMapOf<Long, String>()
        o.optJSONObject("courses")?.let { c ->
            c.keys().forEach { k ->
                val id = k.toLongOrNull()?.takeIf { it > 0 } ?: return@forEach
                val x = c.optJSONObject(k) ?: return@forEach
                val m = CourseMode.of(x.optString("mode"))
                if (m == CourseMode.SHOW) return@forEach
                modes[id] = m
                names[id] = x.optString("name")
            }
        }
        val arr = o.optJSONArray("known") ?: JSONArray()
        val known = (0 until arr.length()).mapNotNull { i ->
            val x = arr.optJSONObject(i) ?: return@mapNotNull null
            CourseRef(x.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null, x.optString("name"))
        }
        Stored(CourseFilter(modes, names), known, o.optLong("knownAt"))
    } catch (_: Exception) {
        Stored(CourseFilter(), emptyList(), 0L)
    }

    /**
     * 설정 화면에 보일 과목: 최근에 받은 이번 학기 목록([known]), 없으면 받아 둔 과제·시간표에서 모은 과목([fallback]).
     * 끄거나 숨겨 둔 과목이 목록에 없으면(지난 학기 등) 되돌릴 수 있게 맨 뒤에 붙인다([CourseRow.stale]).
     */
    data class CourseRow(val course: CourseRef, val mode: CourseMode, val stale: Boolean)

    fun rows(known: List<CourseRef>, fallback: List<CourseRef>, filter: CourseFilter): List<CourseRow> {
        val base = (known.ifEmpty { fallback }).filter { it.id > 0 && it.name.isNotBlank() }.distinctBy { it.id }
        val ids = base.map { it.id }.toSet()
        val extra = filter.modes.filter { it.value != CourseMode.SHOW && it.key !in ids }
            .map { (id, _) -> CourseRef(id, filter.names[id]?.takeIf { it.isNotBlank() } ?: "과목 $id") }
            .sortedBy { it.name }
        return base.map { CourseRow(it, filter.mode(it.id), false) } + extra.map { CourseRow(it, filter.mode(it.id), true) }
    }

    /** 받아 둔 과제·시간표에서 과목 모으기(이름순). */
    fun fallbackCourses(assignments: List<AssignmentItem>, timetable: List<TimetableLogic.CourseMeetings>): List<CourseRef> =
        (timetable.filter { it.courseId > 0 }.map { CourseRef(it.courseId, it.name) } +
            assignments.filter { it.courseId > 0 }.map { CourseRef(it.courseId, it.courseName) })
            .filter { it.name.isNotBlank() }.distinctBy { it.id }.sortedBy { it.name }

    /** 설정 화면 요약 한 줄. */
    fun summary(f: CourseFilter): String = when {
        f.isEmpty -> "모든 과목 알림 받는 중"
        else -> listOfNotNull(
            f.mutedCount.takeIf { it > 0 }?.let { "알림 끈 과목 ${it}개" },
            f.hiddenCount.takeIf { it > 0 }?.let { "숨긴 과목 ${it}개" },
        ).joinToString(" · ")
    }
}

class CourseFilterStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("course_filter", Context.MODE_PRIVATE)

    @Synchronized fun loadAll(): CourseFilterLogic.Stored = CourseFilterLogic.fromJson(prefs.getString("state", null))
    fun load(): CourseFilter = loadAll().filter

    @Synchronized fun setMode(course: CourseRef, mode: CourseMode) {
        val s = loadAll()
        save(s.copy(filter = s.filter.with(course, mode)))
        // 숨긴 과목은 일정 위젯에서도 빠진다.
        kr.snu.etlassistant.widget.HomeWidgets.dataChanged(app)
    }

    /** 최근에 받은 이번 학기 과목 목록(설정 화면을 바로 그리는 데 쓴다). 정해 둔 과목의 이름도 새 이름으로 맞춘다. */
    @Synchronized fun saveKnown(courses: List<CourseRef>, at: Long) {
        val s = loadAll()
        val byId = courses.associateBy { it.id }
        val names = s.filter.names.mapValues { (id, n) -> byId[id]?.name ?: n }
        save(CourseFilterLogic.Stored(s.filter.copy(names = names), courses, at))
    }

    private fun save(s: CourseFilterLogic.Stored) {
        prefs.edit().putString("state", CourseFilterLogic.toJson(s.filter, s.known, s.knownAt)).apply()
    }
}
