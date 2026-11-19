package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class EtlLogicTest {

    private val now = Instant.parse("2026-11-19T00:00:00Z")

    private fun course(id: Int, name: String, term: String?, restricted: Boolean = false) = JSONObject()
        .put("id", id).put("name", name)
        .put("term", if (term == null) JSONObject.NULL else JSONObject().put("name", term))
        .put("access_restricted_by_date", restricted)

    @Test fun termRankOrdersSeasons() {
        val r1 = EtlLogic.termRank("2026년 1학기")!!
        val rs = EtlLogic.termRank("2026년 하계")!!
        val r2 = EtlLogic.termRank("2026-2학기")!!
        val rw = EtlLogic.termRank("2026년 동계")!!
        assertTrue(r1 < rs && rs < r2 && r2 < rw)
        assertEquals(20261, r1)
        assertNull(EtlLogic.termRank("기타"))
        assertNull(EtlLogic.termRank(null))
    }

    @Test fun currentCoursesKeepsLatestTermOnly() {
        val list = listOf(
            course(1, "A", "2025년 2학기"),
            course(2, "B", "2026년 1학기"),
            course(3, "C", "2026년 1학기"),
            course(4, "D", "2026년 2학기", restricted = true), // 제외
        )
        assertEquals(listOf(2, 3), EtlLogic.currentCourses(list).map { it.getInt("id") })
    }

    @Test fun currentCoursesKeepsAllWhenTermUnknown() {
        val list = listOf(course(1, "A", null), course(2, "B", "이름만"))
        assertEquals(2, EtlLogic.currentCourses(list).size)
    }

    private fun assign(types: List<String>, due: String?, sub: JSONObject? = null) = JSONObject()
        .put("submission_types", JSONArray(types))
        .put("due_at", due ?: JSONObject.NULL)
        .apply { if (sub != null) put("submission", sub) }

    @Test fun submittedRules() {
        assertTrue(EtlLogic.isSubmitted(assign(listOf("online_upload"), null, JSONObject().put("workflow_state", "graded"))))
        assertTrue(EtlLogic.isSubmitted(assign(listOf("online_upload"), null, JSONObject().put("submitted_at", "2026-11-01T00:00:00Z"))))
        assertFalse(EtlLogic.isSubmitted(assign(listOf("online_upload"), null, JSONObject().put("workflow_state", "unsubmitted").put("submitted_at", JSONObject.NULL))))
        assertFalse(EtlLogic.isSubmitted(assign(listOf("online_upload"), null)))
    }

    @Test fun onlineRules() {
        assertTrue(EtlLogic.isOnline(assign(listOf("online_upload"), null)))
        assertTrue(EtlLogic.isOnline(assign(listOf("none", "online_text_entry"), null)))
        assertFalse(EtlLogic.isOnline(assign(listOf("none", "on_paper", "not_graded"), null)))
        assertFalse(EtlLogic.isOnline(assign(emptyList(), null)))
    }

    @Test fun missingRules() {
        val past = "2026-11-10T00:00:00Z"
        val future = "2026-11-30T00:00:00Z"
        assertTrue(EtlLogic.isMissing(assign(listOf("online_upload"), past), now))
        assertFalse(EtlLogic.isMissing(assign(listOf("online_upload"), future), now))
        assertFalse(EtlLogic.isMissing(assign(listOf("on_paper"), past), now)) // 오프라인은 누락 아님
        assertTrue(EtlLogic.isMissing(assign(listOf("online_upload"), future, JSONObject().put("missing", true)), now))
        // 제출했으면 마감이 지나도 누락이 아니다
        assertFalse(EtlLogic.isMissing(assign(listOf("online_upload"), past, JSONObject().put("workflow_state", "submitted")), now))
    }

    @Test fun kstFormatting() {
        // 2026-11-19 14:59 UTC = 11-19 23:59 KST, 목요일
        assertEquals("11-19(목) 23:59", EtlLogic.fmtKst(Instant.parse("2026-11-19T14:59:00Z")))
        assertEquals("마감일 없음", EtlLogic.fmtKst(null))
    }

    @Test fun remainingText() {
        assertEquals("1일 2시간 남음", EtlLogic.remaining(now.plusSeconds(86400 + 2 * 3600 + 5), now))
        assertEquals("3시간 5분 남음", EtlLogic.remaining(now.plusSeconds(3 * 3600 + 5 * 60), now))
        assertEquals("10분 지남", EtlLogic.remaining(now.minusSeconds(600), now))
        assertEquals("", EtlLogic.remaining(null, now))
    }

    @Test fun parseInstantHandlesOffsetsAndGarbage() {
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"), EtlLogic.parseInstant("2026-11-19T09:00:00+09:00"))
        assertNull(EtlLogic.parseInstant("어제"))
        assertNull(EtlLogic.parseInstant(null))
        assertNull(EtlLogic.parseInstant(""))
    }

    @Test fun htmlToTextStripsTagsAndEntities() {
        val html = "<p>안녕&nbsp;하세요 &amp; <b>환영</b></p><script>alert(1)</script><ul><li>하나</li><li>둘</li></ul>"
        val t = EtlLogic.htmlToText(html)
        assertFalse(t.contains("alert"))
        assertFalse(t.contains("<"))
        assertTrue(t.contains("안녕 하세요 & 환영"))
        assertTrue(t.contains("- 하나"))
        assertTrue(EtlLogic.htmlToText("가".repeat(700), 600).endsWith("…"))
        assertEquals("", EtlLogic.htmlToText(null))
    }

    @Test fun safeNameBlocksTraversalAndControlChars() {
        assertEquals("passwd", EtlLogic.safeName("../../etc/passwd", "x"))
        assertEquals("a_b_c.pdf", EtlLogic.safeName("a:b*c.pdf", "x"))
        assertEquals("hidden", EtlLogic.safeName("...hidden", "x"))
        assertEquals("file-1", EtlLogic.safeName("", "file-1"))
        assertEquals("file-1", EtlLogic.safeName("..", "file-1"))
        assertEquals("a_b", EtlLogic.safeName("a\u0007b", "x"))
        assertEquals(180, EtlLogic.safeName("a".repeat(300), "x").length)
    }

    @Test fun fileIdParsing() {
        assertEquals(123L, EtlLogic.fileId("123"))
        assertEquals(456L, EtlLogic.fileId("/courses/9/files/456"))
        assertEquals(789L, EtlLogic.fileId("https://myetl.snu.ac.kr/courses/9/files/789/download?x=1"))
        try {
            EtlLogic.fileId("abc")
            fail()
        } catch (_: EtlException) {
        }
    }

    @Test fun getPathFilter() {
        assertTrue(EtlLogic.isAllowedGetPath("/api/v1/users/self"))
        assertTrue(EtlLogic.isAllowedGetPath("/api/v1/courses?enrollment_state=active&include[]=term&per_page=100"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v2/users/self"))
        assertFalse(EtlLogic.isAllowedGetPath("https://evil.example/api/v1/x"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/../../logout"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/x y"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/x\n"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/x#frag"))
    }

    // ---- 제출 판정 ----------------------------------------------------------

    private fun a(types: List<String>, due: String?, ext: List<String> = emptyList()) = JSONObject()
        .put("submission_types", JSONArray(types)).put("due_at", due ?: JSONObject.NULL)
        .put("allowed_extensions", JSONArray(ext))

    @Test fun verifyOkWhenSubmittedOnTime() {
        val s = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-09T00:00:00Z").put("submission_type", "online_upload")
        val ev = EtlLogic.evaluateSubmission(a(listOf("online_upload"), "2026-11-10T00:00:00Z"), s, now)
        assertTrue(ev.submitted)
        assertTrue(ev.problems.isEmpty())
    }

    @Test fun verifyNoSubmissionOnlineIsProblem() {
        val ev = EtlLogic.evaluateSubmission(a(listOf("online_upload"), "2026-11-10T00:00:00Z"), JSONObject().put("workflow_state", "unsubmitted"), now)
        assertFalse(ev.submitted)
        assertEquals(listOf("제출 기록 없음 (마감 지남)"), ev.problems)
    }

    @Test fun verifyOfflineAssignmentIsWarningNotProblem() {
        val ev = EtlLogic.evaluateSubmission(a(listOf("on_paper"), null), JSONObject(), now)
        assertTrue(ev.problems.isEmpty())
        assertEquals(1, ev.warnings.size)
    }

    @Test fun verifyLateAndMissingAndWrongType() {
        val late = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-12T00:00:00Z")
            .put("submission_type", "online_url")
        val ev = EtlLogic.evaluateSubmission(a(listOf("online_upload"), "2026-11-10T00:00:00Z"), late, now)
        assertTrue(ev.problems.any { it.contains("마감 후 제출") })
        assertTrue(ev.problems.any { it.contains("online_url") })

        val flagged = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-09T00:00:00Z")
            .put("late", true).put("missing", true).put("excused", true)
        val ev2 = EtlLogic.evaluateSubmission(a(listOf("online_upload"), "2026-11-10T00:00:00Z"), flagged, now)
        assertTrue(ev2.problems.contains("지각 제출로 표시됨"))
        assertTrue(ev2.problems.any { it.contains("누락") })
        assertTrue(ev2.warnings.any { it.contains("면제") })
        assertFalse(ev2.problems.any { it.contains("마감 후 제출") }) // late가 우선
    }

    @Test fun attachmentChecks() {
        val empty = JSONObject().put("display_name", "report.pdf").put("size", 0)
        assertTrue(EtlLogic.checkAttachment(empty, emptyList()).any { it.contains("0바이트") })
        val exe = JSONObject().put("display_name", "run.EXE").put("size", 10)
        assertTrue(EtlLogic.checkAttachment(exe, listOf("pdf", "docx")).any { it.contains("허용되지 않은 확장자") })
        val ok = JSONObject().put("display_name", "Report.PDF").put("size", 10)
        assertTrue(EtlLogic.checkAttachment(ok, listOf("pdf")).isEmpty())
        val noSize = JSONObject().put("display_name", "x.pdf")
        assertTrue(EtlLogic.checkAttachment(noSize, emptyList()).isEmpty())
    }
}
