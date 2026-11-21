package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.TimetableLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/*
 * "내 일정" 화면: eTL 과제 마감 + 내 마감 + (오늘·내일의) 수업을 날짜별로 한 목록에.
 * eTL 과제는 알림 확인 때 받은 결과를 [SnapshotCache]에 저장해 두고 화면을 열면 바로 보여 준다.
 */

/** 마지막으로 받은 eTL 과제 목록(내 마감은 넣지 않는다). */
data class CachedSnapshot(val takenAt: Long, val assignments: List<AssignmentItem>)

object SnapshotCacheLogic {
    fun toJson(s: CachedSnapshot): String = JSONObject()
        .put("version", 1)
        .put("takenAt", s.takenAt)
        .put("assignments", JSONArray().also { arr ->
            s.assignments.forEach { a ->
                arr.put(JSONObject().put("cid", a.courseId).put("course", a.courseName).put("aid", a.assignmentId).put("name", a.name)
                    .put("due", a.due?.toEpochMilli() ?: JSONObject.NULL).put("online", a.online).put("points", a.points ?: JSONObject.NULL)
                    .put("url", a.url ?: JSONObject.NULL).put("submitted", a.submitted).put("missing", a.missing).put("score", a.score ?: JSONObject.NULL))
            }
        }).toString()

    fun fromJson(s: String?): CachedSnapshot? = try {
        val o = JSONObject(s ?: return null)
        if (o.optInt("version") != 1) null else {
            val arr = o.optJSONArray("assignments") ?: JSONArray()
            CachedSnapshot(o.optLong("takenAt"), (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                AssignmentItem(
                    a.optLong("cid"), a.optString("course"), a.optLong("aid"), a.optString("name"),
                    if (a.isNull("due")) null else Instant.ofEpochMilli(a.optLong("due")), a.optBoolean("online", true),
                    if (a.isNull("points")) null else a.optDouble("points"), if (a.isNull("url")) null else a.optString("url"),
                    a.optBoolean("submitted"), a.optBoolean("missing"), if (a.isNull("score")) null else a.optDouble("score"),
                )
            })
        }
    } catch (_: Exception) {
        null
    }
}

class SnapshotCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("etl_snapshot", Context.MODE_PRIVATE)
    @Synchronized fun load(): CachedSnapshot? = SnapshotCacheLogic.fromJson(prefs.getString("snapshot", null))
    /** 일부 과목 조회가 실패했으면 그 과목은 이전 값을 남긴다(빈 목록으로 덮어 사라지지 않게). */
    @Synchronized fun save(s: Snapshot) {
        val items = s.assignments.filter { it.courseId != MyDeadlines.COURSE_ID }
        if (items.isEmpty() && s.errors.isNotEmpty()) return
        val failedCourses = s.errors.map { it.substringBefore(':') }.toSet()
        val kept = load()?.assignments.orEmpty().filter { old -> old.courseName in failedCourses && items.none { it.courseId == old.courseId } }
        prefs.edit().putString("snapshot", SnapshotCacheLogic.toJson(CachedSnapshot(s.takenAt.toEpochMilli(), items + kept))).apply()
    }
}

object Agenda {
    enum class Kind { ASSIGNMENT, MINE, CLASS }
    enum class Filter(val label: String) { ALL("전체"), ASSIGNMENT("과제"), MINE("내 마감"), CLASS("수업") }

    /** 한 줄. [sortKey]로 묶음 안 순서를 정한다. */
    data class Row(
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val at: Instant,
        /** 제출했거나 완료한 항목(숨기기 대상). */
        val done: Boolean = false,
        val overdue: Boolean = false,
        val url: String? = null,
        val mine: MyDeadline? = null,
        /** 휴강으로 빠진 수업. */
        val cancelled: Boolean = false,
    )

    data class Group(val label: String, val rows: List<Row>)

    /**
     * 묶음: 마감 지남(미제출·미완료) → 오늘 → 내일 → 이번 주(월~일) 남은 날 → 다음 주 → 그 뒤.
     * 수업은 오늘·내일에만 넣는다([days]에 그날 시간표). 이미 지난 제출 과제·완료 내 마감은 넣지 않는다.
     * [hideDone]이면 앞으로의 제출·완료 항목도 뺀다.
     */
    fun build(
        assignments: List<AssignmentItem>,
        mine: List<MyDeadline>,
        days: List<TimetableLogic.Day>,
        now: Instant,
        zone: ZoneId,
        filter: Filter = Filter.ALL,
        hideDone: Boolean = true,
    ): List<Group> {
        val rows = mutableListOf<Row>()
        if (filter == Filter.ALL || filter == Filter.ASSIGNMENT) {
            for (a in assignments) {
                val due = a.due ?: continue
                val past = !due.isAfter(now)
                if (past && (a.submitted || !a.missing)) continue
                rows += Row(
                    Kind.ASSIGNMENT, a.name,
                    NoticeText.course(a.courseName) + (if (a.submitted) " · 제출함" else if (!a.online) " · 오프라인" else ""),
                    due, done = a.submitted, overdue = past, url = a.url,
                )
            }
        }
        if (filter == Filter.ALL || filter == Filter.MINE) {
            for (d in mine) {
                val past = !d.due.isAfter(now)
                if (past && d.done) continue
                rows += Row(Kind.MINE, d.title, "내 마감" + (if (d.reminders.isNotEmpty() && !d.done) " · 🔔 ${MyDeadlines.reminderLabel(d)}" else "") +
                    (if (d.memo.isNotBlank()) " · ${d.memo}" else "") + (if (d.done) " · 완료" else ""),
                    d.due, done = d.done, overdue = past, mine = d)
            }
        }
        if (filter == Filter.ALL || filter == Filter.CLASS) {
            val today = now.atZone(zone).toLocalDate()
            for (day in days.filter { it.date == today || it.date == today.plusDays(1) }) {
                fun at(hm: String) = day.date.atTime(java.time.LocalTime.parse(hm)).atZone(zone).toInstant()
                for (c in day.classes) {
                    if (day.date == today && !at(c.end).isAfter(now)) continue // 끝난 수업은 뺀다
                    rows += Row(Kind.CLASS, c.course, "${c.start}~${c.end}" + (c.room?.let { " · $it" } ?: "") + (if (c.kind != "정규") " · ${c.kind}" else "") +
                        (c.note?.let { " · $it" } ?: ""), at(c.start), url = c.courseId?.takeIf { it > 0 }?.let { "${NoticeText.ETL_PREFIX}courses/$it" })
                }
                for (c in day.cancelled) {
                    rows += Row(Kind.CLASS, c.course, "${c.start} 휴강" + (c.note?.let { " · $it" } ?: ""), at(c.start), cancelled = true)
                }
            }
        }
        val visible = if (hideDone) rows.filter { !it.done } else rows

        val today = now.atZone(zone).toLocalDate()
        val weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val nextWeekEnd = weekEnd.plusWeeks(1)
        fun label(r: Row): String {
            if (r.overdue && r.kind != Kind.CLASS) return "마감 지남"
            val d: LocalDate = r.at.atZone(zone).toLocalDate()
            return when {
                d == today -> "오늘"
                d == today.plusDays(1) -> "내일"
                !d.isAfter(weekEnd) -> "이번 주"
                !d.isAfter(nextWeekEnd) -> "다음 주"
                else -> "그 뒤"
            }
        }
        val order = listOf("마감 지남", "오늘", "내일", "이번 주", "다음 주", "그 뒤")
        return visible.groupBy(::label).entries
            .sortedBy { order.indexOf(it.key) }
            .map { (k, v) -> Group(k, v.sortedWith(compareBy({ it.at }, { it.kind.ordinal }, { it.title }))) }
    }

    /** `11/23(월) 23:59 · 1일 2시간 남음`처럼 보일 시각 부분. 수업은 시간만. */
    fun whenLabel(r: Row, now: Instant): String = when (r.kind) {
        Kind.CLASS -> ""
        else -> NoticeText.dateLabel(r.at) + " · " + kr.snu.etlassistant.etl.EtlLogic.remaining(r.at, now)
    }
}
