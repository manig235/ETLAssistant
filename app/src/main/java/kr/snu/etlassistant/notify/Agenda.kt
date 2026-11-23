package kr.snu.etlassistant.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * "내 일정" 화면: eTL 과제와 내 마감. 수업은 시간표에서 보여 준다.
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
                    .put("url", a.url ?: JSONObject.NULL).put("submitted", a.submitted).put("missing", a.missing).put("score", a.score ?: JSONObject.NULL)
                    .put("subAt", a.submittedAt?.toEpochMilli() ?: JSONObject.NULL)
                    .also { o -> a.check?.let { c -> o.put("check", checkJson(c)) } })
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
                    if (!a.has("subAt") || a.isNull("subAt")) null else Instant.ofEpochMilli(a.optLong("subAt")),
                    a.optJSONObject("check")?.let(::checkFrom),
                )
            })
        }
    } catch (_: Exception) {
        null
    }

    private fun checkJson(c: kr.snu.etlassistant.etl.SubmitCheck): JSONObject =
        JSONObject().put("late", c.late).put("problems", JSONArray(c.problems)).put("attKnown", c.attachmentsKnown)

    private fun checkFrom(o: JSONObject): kr.snu.etlassistant.etl.SubmitCheck {
        val p = o.optJSONArray("problems") ?: JSONArray()
        return kr.snu.etlassistant.etl.SubmitCheck(o.optBoolean("late"), (0 until p.length()).map { p.optString(it) }, o.optBoolean("attKnown", true))
    }
}

class SnapshotCache(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("etl_snapshot", Context.MODE_PRIVATE)
    @Synchronized fun load(): CachedSnapshot? = SnapshotCacheLogic.fromJson(prefs.getString("snapshot", null))
    /** 일부 과목 조회가 실패했으면 그 과목은 이전 값을 남긴다(빈 목록으로 덮어 사라지지 않게). */
    @Synchronized fun save(s: Snapshot) {
        val items = s.assignments.filter { it.courseId != MyDeadlines.COURSE_ID }
        if (items.isEmpty() && s.errors.isNotEmpty()) return
        val failedCourses = s.errors.map { it.substringBefore(':') }.toSet()
        val kept = load()?.assignments.orEmpty().filter { old -> old.courseName in failedCourses && items.none { it.courseId == old.courseId } }
        prefs.edit().putString("snapshot", SnapshotCacheLogic.toJson(CachedSnapshot(s.takenAt.toEpochMilli(), items + kept))).apply()
        kr.snu.etlassistant.widget.HomeWidgets.dataChanged(app)
    }
}

object Agenda {
    enum class Kind { ASSIGNMENT, MINE }
    enum class Filter(val label: String) { ALL("전체"), ASSIGNMENT("과제"), MINE("내 마감") }

    /** 같은 이름·시각의 과제도 서로 다른 행으로 유지한다. */
    data class Row(
        val id: String,
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val at: Instant,
        /** 제출했거나 완료한 항목(숨기기 대상). */
        val done: Boolean = false,
        val overdue: Boolean = false,
        val url: String? = null,
        /** 내 마감이면 그 항목. 반복 마감이면 회차(`occurrence`가 있는 펼친 값), 고칠 때는 저장된 원래 항목을 다시 찾는다. */
        val mine: MyDeadline? = null,
        /** eTL 과제면 그 과제("제출 확인"에 쓴다). */
        val assignment: AssignmentItem? = null,
        /** 제출했지만 제출 정보 점검에서 문제가 나온 과제. 제출·완료를 숨겨도 보인다. */
        val attention: Boolean = false,
    )

    data class Group(val date: LocalDate, val rows: List<Row>)

    data class Schedule(
        val today: LocalDate,
        val urgent: List<Row>,
        val overdue: List<Row>,
        val upcoming: List<Group>,
        val later: List<Group>,
    ) {
        val lastDay: LocalDate get() = today.plusDays(6)
        val upcomingCount: Int get() = upcoming.sumOf { it.rows.size }
        val laterCount: Int get() = later.sumOf { it.rows.size }
    }

    /**
     * 오늘~6일 뒤의 전체 마감과 이후 마감을 날짜별로 묶는다.
     * 24시간 내 미완료 마감은 요약에도 넣으며 전체 목록에서 빼지 않는다.
     * 지난 미완료 마감은 따로 유지하고, [hideDone]이면 앞으로의 제출·완료 항목도 뺀다.
     */
    fun build(
        assignments: List<AssignmentItem>,
        mine: List<MyDeadline>,
        now: Instant,
        zone: ZoneId,
        filter: Filter = Filter.ALL,
        hideDone: Boolean = true,
    ): Schedule {
        val rows = mutableListOf<Row>()
        if (filter == Filter.ALL || filter == Filter.ASSIGNMENT) {
            for (a in assignments) {
                val due = a.due ?: continue
                val past = !due.isAfter(now)
                if (past && (a.submitted || !a.missing)) continue
                rows += Row(
                    "assignment-${a.courseId}-${a.assignmentId}", Kind.ASSIGNMENT, a.name,
                    NoticeText.course(a.courseName) + submittedLabel(a),
                    due, done = a.submitted, overdue = past, url = a.url, assignment = a,
                    attention = a.submitted && a.check?.problems?.isNotEmpty() == true,
                )
            }
        }
        if (filter == Filter.ALL || filter == Filter.MINE) {
            // 반복 마감은 앞으로 7일의 회차를 모두 펼친다(그 안에 없으면 다음 회차 하나).
            val until = now.atZone(zone).toLocalDate().plusDays(7).atStartOfDay(zone).toInstant()
            for (d in MyDeadlines.agendaItems(mine, now, until)) {
                val past = !d.due.isAfter(now)
                if (past && d.done) continue
                rows += Row("mine-${d.id}" + (d.occurrence?.let { "-$it" } ?: ""), Kind.MINE, d.title,
                    "내 마감" + (if (d.repeat != null) " · 🔁 ${MyDeadlines.repeatLabel(d)}" else "") +
                        (if (d.reminders.isNotEmpty() && !d.done) " · 🔔 ${MyDeadlines.reminderLabel(d)}" else "") +
                        (if (d.memo.isNotBlank()) " · ${d.memo}" else "") + (if (d.done) " · 완료" else ""),
                    d.due, done = d.done, overdue = past, mine = d)
            }
        }
        val visible = rows.filter { !hideDone || !it.done || it.attention }
            .sortedWith(compareBy({ it.at }, { it.kind.ordinal }, { it.title }, { it.id }))
        val today = now.atZone(zone).toLocalDate()
        val endExclusive = today.plusDays(7).atStartOfDay(zone).toInstant()
        fun groups(items: List<Row>) = items.groupBy { it.at.atZone(zone).toLocalDate() }
            .map { (date, entries) -> Group(date, entries) }
        val future = visible.filter { !it.overdue }
        return Schedule(
            today = today,
            urgent = future.filter { !it.done && !it.at.isAfter(now.plus(Duration.ofHours(24))) },
            overdue = visible.filter { it.overdue },
            upcoming = groups(future.filter { it.at.isBefore(endExclusive) }),
            later = groups(future.filter { !it.at.isBefore(endExclusive) }),
        )
    }

    /** ` · 제출함`, 늦었으면 ` · 제출함(늦음)`, 점검에서 문제가 나왔으면 ` · 제출함 · 확인 필요`. */
    fun submittedLabel(a: AssignmentItem): String = when {
        !a.submitted -> if (!a.online) " · 오프라인" else ""
        a.check?.problems?.isNotEmpty() == true -> " · 제출함 · 확인 필요"
        a.check?.late == true -> " · 제출함(늦음)"
        else -> " · 제출함"
    }

    fun dateLabel(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}(${"월화수목금토일"[date.dayOfWeek.value - 1]})"

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "오늘 · ${dateLabel(date)}"
        today.plusDays(1) -> "내일 · ${dateLabel(date)}"
        else -> dateLabel(date)
    }

    /** `11/23(월) 23:59 · 1일 2시간 남음`처럼 보일 시각 부분. */
    fun whenLabel(r: Row, now: Instant): String =
        NoticeText.dateLabel(r.at) + " · " + kr.snu.etlassistant.etl.EtlLogic.remaining(r.at, now)
}
