package kr.snu.etlassistant.etl

import kr.snu.etlassistant.etl.EtlLogic.obj
import kr.snu.etlassistant.etl.EtlLogic.str
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * `etl_assignments`의 순수 로직: 기간 계산(KST)과 과제 고르기·정렬. 네트워크 없이 테스트한다.
 * "이번 주"는 KST 월요일 00:00부터 다음 월요일 00:00 직전(일요일 23:59)까지다.
 */
object AssignmentQuery {

    enum class Range(val id: String) {
        THIS_WEEK("this_week"), NEXT_WEEK("next_week"), DAYS("days");

        companion object {
            fun parse(s: String?): Range = values().firstOrNull { it.id == s?.trim()?.lowercase() } ?: THIS_WEEK
        }
    }

    /** [start] 이상 [endExclusive] 미만. */
    data class Window(val start: Instant, val endExclusive: Instant) {
        fun contains(t: Instant) = !t.isBefore(start) && t.isBefore(endExclusive)
    }

    fun window(range: Range, now: Instant, days: Int = 7): Window {
        val monday = ZonedDateTime.ofInstant(now, EtlLogic.KST).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(EtlLogic.KST)
        return when (range) {
            Range.THIS_WEEK -> Window(monday.toInstant(), monday.plusWeeks(1).toInstant())
            Range.NEXT_WEEK -> Window(monday.plusWeeks(1).toInstant(), monday.plusWeeks(2).toInstant())
            Range.DAYS -> Window(now, now.plusSeconds(days.coerceIn(1, 60) * 86400L))
        }
    }

    /**
     * 기간 안에 마감인 게시된 과제를 마감순(같으면 강의 이름, 과제 ID)으로 고른다.
     * [includeSubmitted]가 false면 이미 낸 과제를 뺀다. 결과는 모델에게 주는 JSON 행.
     */
    fun select(
        courses: List<CourseAssignments.CourseResult>,
        window: Window,
        includeSubmitted: Boolean,
        now: Instant,
    ): List<JSONObject> {
        data class Row(val due: Instant, val course: String, val id: Long, val json: JSONObject)
        val rows = mutableListOf<Row>()
        val seen = HashSet<Pair<Long, Long>>()
        for (cr in courses) {
            for (a in cr.assignments) {
                if (a.has("published") && !a.optBoolean("published", true)) continue
                val due = EtlLogic.parseInstant(a.str("due_at")) ?: continue
                if (!window.contains(due)) continue
                val submitted = EtlLogic.isSubmitted(a)
                if (submitted && !includeSubmitted) continue
                val aid = a.optLong("id")
                if (!seen.add(cr.courseId to aid)) continue
                val sub = a.obj("submission")
                rows += Row(due, cr.courseName, aid, JSONObject()
                    .put("course", cr.courseName)
                    .put("course_id", cr.courseId)
                    .put("assignment_id", aid)
                    .put("name", a.str("name")?.takeIf { it.isNotBlank() } ?: "(제목 없음)")
                    .put("due", EtlLogic.fmtKst(due))
                    .put("remaining", EtlLogic.remaining(due, now))
                    .put("submitted", submitted)
                    .put("submitted_at", EtlLogic.parseInstant(sub?.str("submitted_at"))?.let { EtlLogic.fmtKst(it) } ?: JSONObject.NULL)
                    .put("missing", EtlLogic.isMissing(a, now))
                    .put("online", EtlLogic.isOnline(a))
                    .put("points", if (!a.has("points_possible") || a.isNull("points_possible")) JSONObject.NULL else a.opt("points_possible"))
                    .put("score", if (sub == null || !sub.has("score") || sub.isNull("score")) JSONObject.NULL else sub.opt("score"))
                    .put("url", a.str("html_url") ?: JSONObject.NULL))
            }
        }
        rows.sortWith(compareBy<Row>({ it.due }, { it.course }, { it.id }))
        return rows.map { it.json }
    }
}
