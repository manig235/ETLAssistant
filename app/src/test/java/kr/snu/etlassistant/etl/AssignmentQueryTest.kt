package kr.snu.etlassistant.etl

import kr.snu.etlassistant.etl.AssignmentQuery.Range
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AssignmentQueryTest {

    /** KST 시각을 UTC Instant로. */
    private fun kst(s: String): Instant = java.time.OffsetDateTime.parse("$s+09:00").toInstant()

    // 2026-11-16(월) ~ 2026-11-22(일)이 한 주.
    private val fri = kst("2026-11-20T23:30:00")

    @Test fun thisWeekIsMondayToSundayKst() {
        val w = AssignmentQuery.window(Range.THIS_WEEK, fri)
        assertEquals(kst("2026-11-16T00:00:00"), w.start)
        assertEquals(kst("2026-11-23T00:00:00"), w.endExclusive)
        assertTrue(w.contains(kst("2026-11-22T23:59:00")))
        assertFalse(w.contains(kst("2026-11-23T00:00:00")))
        assertTrue(w.contains(kst("2026-11-16T00:00:00")))
        assertFalse(w.contains(kst("2026-11-15T23:59:00")))
    }

    @Test fun weekBoundariesFromSundayNightAndMondayMidnight() {
        // 일요일 23:59에는 아직 그 주
        assertEquals(kst("2026-11-16T00:00:00"), AssignmentQuery.window(Range.THIS_WEEK, kst("2026-11-22T23:59:00")).start)
        // 월요일 00:00이 되면 새 주
        assertEquals(kst("2026-11-23T00:00:00"), AssignmentQuery.window(Range.THIS_WEEK, kst("2026-11-23T00:00:00")).start)
        // UTC로는 일요일 15:00 = KST 월요일 00:00
        assertEquals(kst("2026-11-23T00:00:00"), AssignmentQuery.window(Range.THIS_WEEK, Instant.parse("2026-11-22T15:00:00Z")).start)
    }

    @Test fun nextWeekAndDays() {
        val n = AssignmentQuery.window(Range.NEXT_WEEK, fri)
        assertEquals(kst("2026-11-23T00:00:00"), n.start)
        assertEquals(kst("2026-11-30T00:00:00"), n.endExclusive)
        val d = AssignmentQuery.window(Range.DAYS, fri, 3)
        assertEquals(fri, d.start)
        assertEquals(fri.plusSeconds(3 * 86400L), d.endExclusive)
        assertEquals(Range.THIS_WEEK, Range.parse("이상한 값"))
        assertEquals(Range.NEXT_WEEK, Range.parse(" Next_Week "))
    }

    private fun course(id: Long, name: String) = JSONObject().put("id", id).put("name", name)
    private fun asg(id: Long, due: String?, submitted: Boolean = false, published: Boolean = true, score: Double? = null) =
        JSONObject().put("id", id).put("name", "과제$id").put("published", published)
            .put("due_at", due?.let { java.time.OffsetDateTime.parse("$it+09:00").toInstant().toString() } ?: JSONObject.NULL)
            .put("submission_types", org.json.JSONArray(listOf("online_upload")))
            .put("points_possible", 10)
            .put("submission", JSONObject()
                .put("workflow_state", if (submitted) "submitted" else "unsubmitted")
                .put("submitted_at", if (submitted) "2026-11-17T01:00:00Z" else JSONObject.NULL)
                .put("score", score ?: JSONObject.NULL))

    @Test fun selectFiltersAndSorts() {
        val results = listOf(
            CourseAssignments.CourseResult(course(1, "나강의"), listOf(
                asg(11, "2026-11-22T23:59:00"),              // 이번 주 끝
                asg(12, "2026-11-17T12:00:00", submitted = true, score = 9.0), // 이미 낸 것(지남)
                asg(13, "2026-11-23T00:00:00"),              // 다음 주 → 제외
                asg(14, "2026-11-19T12:00:00", published = false), // 미게시 → 제외
                asg(15, null),                               // 마감 없음 → 제외
            ), null),
            CourseAssignments.CourseResult(course(2, "가강의"), listOf(
                asg(21, "2026-11-22T23:59:00"),              // 같은 마감 → 강의 이름순으로 앞
                asg(21, "2026-11-22T23:59:00"),              // 중복 → 한 번만
            ), null),
            CourseAssignments.CourseResult(course(3, "다강의"), emptyList(), "권한 없음(401)"),
        )
        val w = AssignmentQuery.window(Range.THIS_WEEK, fri)
        val rows = AssignmentQuery.select(results, w, includeSubmitted = true, now = fri)
        assertEquals(listOf(12L, 21L, 11L), rows.map { it.optLong("assignment_id") })
        assertTrue(rows[0].optBoolean("submitted"))
        assertEquals(9.0, rows[0].optDouble("score"), 0.0)
        assertEquals("가강의", rows[1].optString("course"))
        assertEquals("11-22(일) 23:59", rows[2].optString("due"))
        assertFalse(rows[2].optBoolean("missing"))

        val unsubmitted = AssignmentQuery.select(results, w, includeSubmitted = false, now = fri)
        assertEquals(listOf(21L, 11L), unsubmitted.map { it.optLong("assignment_id") })
    }
}
