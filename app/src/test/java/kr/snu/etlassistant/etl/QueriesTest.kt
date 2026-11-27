package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** ttokttok-pet queries.js / files.js / timetable.js(보강·휴강) 규칙을 옮긴 것의 테스트. */
class QueriesTest {
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    private fun iso(hours: Long) = now.plusSeconds(hours * 3600).toString()

    private fun course(id: Long, name: String, term: String, score: Double? = null) = JSONObject()
        .put("id", id).put("name", name).put("term", JSONObject().put("name", term))
        .put("enrollments", JSONArray().put(JSONObject().put("type", "student").put("computed_current_score", score ?: JSONObject.NULL)))

    private fun asg(id: Long, dueHours: Long?, submitted: Boolean = false, score: Double? = null, online: Boolean = true) = JSONObject()
        .put("id", id).put("name", "과제$id").put("due_at", dueHours?.let { iso(it) } ?: JSONObject.NULL)
        .put("points_possible", 10).put("submission_types", JSONArray().put(if (online) "online_upload" else "on_paper"))
        .put("html_url", "https://myetl.snu.ac.kr/courses/1/assignments/$id")
        .put("submission", JSONObject()
            .put("workflow_state", if (score != null) "graded" else if (submitted) "submitted" else "unsubmitted")
            .put("submitted_at", if (submitted || score != null) iso(-10) else JSONObject.NULL)
            .put("score", score ?: JSONObject.NULL))

    private val courses = listOf(
        course(1, "2026-2 자료구조 (001)", "2026년 2학기", 91.5),
        course(2, "2026-1 선형대수 (001)", "2026년 1학기"),
    )

    @Test fun coursesCurrentFirstWithScores() {
        val r = Queries.coursesResult(courses.reversed()).getJSONArray("courses")
        assertEquals(1L, r.getJSONObject(0).getLong("course_id"))
        assertTrue(r.getJSONObject(0).getBoolean("current"))
        assertFalse(r.getJSONObject(1).getBoolean("current"))
        assertEquals(91.5, r.getJSONObject(0).getDouble("current_score"), 0.0)
        assertEquals(listOf(1L), Queries.coursesFor(courses, null).map { it.optLong("id") })
        assertEquals(listOf(2L), Queries.coursesFor(courses, 2).map { it.optLong("id") })
        try { Queries.coursesFor(courses, 9); throw AssertionError() } catch (_: EtlException) {}
    }

    @Test fun statusFilters() {
        val results = listOf(CourseAssignments.CourseResult(courses[0], listOf(asg(1, 5), asg(2, 200), asg(3, -5), asg(4, -50, submitted = true), asg(5, null)), null))
        fun ids(status: String, days: Int? = null) = Queries.statusList(results, status, days, now).getJSONArray("assignments")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).getLong("assignment_id") } }
        assertEquals(listOf(1L, 2L), ids("upcoming"))
        assertEquals(listOf(1L), ids("upcoming", 7))
        assertEquals(listOf(3L), ids("missing"))
        assertEquals(listOf(4L), ids("submitted"))
        assertEquals(listOf(4L, 3L, 1L, 2L, 5L), ids("all")) // 마감순, 마감 없음은 끝
        try { ids("weird"); throw AssertionError() } catch (_: EtlException) {}
    }

    @Test fun gradesAndAnnouncements() {
        val results = listOf(CourseAssignments.CourseResult(courses[0], listOf(asg(1, -20, score = 8.5), asg(2, 5)), null))
        val g = Queries.gradesResult(listOf(courses[0]), results, now).getJSONArray("courses").getJSONObject(0)
        assertEquals(1, g.getJSONArray("graded_assignments").length())
        assertEquals(8.5, g.getJSONArray("graded_assignments").getJSONObject(0).getDouble("score"), 0.0)

        val anns = Queries.announcementRows(listOf(
            JSONObject().put("context_code", "course_1").put("title", "옛 공지").put("posted_at", iso(-48)).put("message", "<p>a</p>"),
            JSONObject().put("context_code", "course_1").put("title", "새 공지").put("posted_at", iso(-1)).put("message", "<p>b</p>")
                .put("author", JSONObject().put("display_name", "조교")),
        ), mapOf(1L to "자료구조"))
        assertEquals("새 공지", anns.getJSONObject(0).getString("title"))
        assertEquals("조교", anns.getJSONObject(0).getString("author"))
        assertEquals("휴강 공지", Queries.announcementBadge("11/20 수업 안내", "이번 주 목요일은 휴강합니다"))
        assertEquals("보강 공지", Queries.announcementBadge("보강 안내", ""))
        assertEquals("시험 공지", Queries.announcementBadge("중간고사", "범위"))
    }

    @Test fun examAnnouncementDetection() {
        val exam = { t: String, b: String -> Queries.isExamAnnouncement(t, b) }
        assertTrue(exam("중간고사 안내", ""))
        assertTrue(exam("기말 고사 장소 변경", ""))
        assertTrue(exam("Quiz 2 공지", ""))
        assertTrue(exam("Midterm exam room", ""))
        assertTrue(exam("[자료구조] 퀴즈", ""))
        assertTrue(exam("공지", "다음 주 화요일에 중간고사를 실시합니다. 장소: 302호"))
        // 성적·채점 공지는 일정 공지가 아니다
        assertFalse(exam("중간고사 성적 공개", "평균 70점"))
        assertFalse(exam("Midterm grades released", ""))
        assertTrue(exam("기말고사 일정 및 중간고사 성적 안내", ""))
        // 본문에만 스치듯 나오면 아니다
        assertFalse(exam("과제 3 안내", "이번 과제 내용은 시험에 반영됩니다"))
        assertFalse(exam("Final project", "Submit your final report"))
        assertFalse(exam("실험 보고서 제출", "실험 2 보고서"))
        assertFalse(exam("중간고사 성적", "시험 범위 질문은 조교에게"))
        // 제목의 시험이 보강보다 먼저, 본문에만 있으면 보강·휴강이 먼저
        assertEquals("시험 공지", Queries.announcementBadge("중간고사 안내", "보강 일정과 같이 봐 주세요"))
        assertEquals("보강 공지", Queries.announcementBadge("수업 안내", "보강은 시험 일정 뒤에 합니다"))
        assertEquals("시험 공지", Queries.announcementBadge("공지", "퀴즈는 목요일 수업 시간에 봅니다"))
    }

    @Test fun readOnlyConversationsAndCap() {
        assertEquals("/api/v1/conversations/5?auto_mark_as_read=false", Queries.readOnlyPath("/api/v1/conversations/5"))
        assertEquals("/api/v1/conversations/5?a=1&auto_mark_as_read=false", Queries.readOnlyPath("/api/v1/conversations/5?a=1"))
        assertEquals("/api/v1/conversations?per_page=30", Queries.readOnlyPath("/api/v1/conversations?per_page=30"))
        val big = JSONArray((0 until 50).map { JSONObject().put("i", it).put("pad", "x".repeat(50)) })
        val capped = Queries.capAnswer("/p", big, 1000)
        assertTrue(capped.getJSONArray("data").toString().length <= 1000)
        assertEquals(50, capped.getJSONObject("truncated").getInt("total"))
        assertFalse(Queries.capAnswer("/p", JSONArray().put(1), 1000).has("truncated"))
    }

    @Test fun filesMergeSources() {
        val listed = listOf(JSONObject().put("id", 10).put("display_name", "1주차.pdf").put("folder_id", 3).put("size", 100))
        val folders = listOf(JSONObject().put("id", 3).put("full_name", "course files/강의노트"))
        val modules = Queries.moduleFiles(listOf(JSONObject().put("name", "1주차").put("items", JSONArray()
            .put(JSONObject().put("type", "File").put("content_id", 10).put("title", "1주차.pdf"))
            .put(JSONObject().put("type", "File").put("content_id", 11).put("title", "실습.zip"))
            .put(JSONObject().put("type", "Page").put("content_id", 99)))))
        val assignments = listOf(asg(1, 5).put("description", """<a href="/courses/1/files/12/download">양식</a> <a href="https://evil.example/files/13">x</a>"""))
        val anns = listOf(JSONObject().put("title", "공지").put("attachments", JSONArray().put(JSONObject().put("id", 14).put("display_name", "영상.mp4").put("content-type", "video/mp4"))))
        val attached = Queries.attachedFiles(assignments, anns, 1)
        assertEquals(listOf(12L, 14L), attached.map { it.id })
        val r = Queries.filesResult(1, "자료구조", listed, folders, modules, attached, mapOf(11L to JSONObject().put("display_name", "실습.zip")), setOf(10L), emptyList())
        val rows = r.getJSONArray("files").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getLong("id") to a.getJSONObject(it) } }
        assertEquals(setOf(10L, 11L, 12L, 14L), rows.keys)
        assertEquals("강의노트", rows[10L]!!.getString("folder"))
        assertEquals("1주차", rows[10L]!!.getString("module"))
        assertTrue(rows[10L]!!.getBoolean("downloaded"))
        assertTrue(rows[14L]!!.getString("skip").contains("동영상"))
        assertEquals("과제1", rows[12L]!!.getString("assignment"))
        assertEquals(1, r.getInt("downloaded_count"))
        assertTrue(Queries.linkedFiles("""<img src="/files/7/preview"><a href="/courses/2/files/8">""", 1) == listOf(7L))
    }

    @Test fun hiddenAnnouncementAttachmentIncludesUsableDownloadContext() {
        val hidden = JSONObject().put("id", 42).put("display_name", "점수.xlsx").put("hidden_for_user", true)
        val ann = JSONObject().put("id", 7).put("title", "중간고사 채점 완료")
            .put("message", "<a href='/courses/1/files/42/download'>엑셀</a>")
        val refs = Queries.attachedFiles(emptyList(), listOf(ann), 1)
        // 파일 탭 목록에도 있는 경우와, 파일 탭이 막혀 메타만 따로 받은 경우 모두 출처를 보존한다.
        for (listed in listOf(listOf(hidden), null)) {
            val row = Queries.filesResult(1, "물리학", listed, emptyList(), emptyList(), refs,
                mapOf(42L to hidden), setOf(42L), emptyList()).getJSONArray("files").getJSONObject(0)
            assertFalse(row.has("skip"))
            assertTrue(row.getBoolean("downloaded"))
            val args = row.getJSONObject("download_args")
            assertEquals("42", args.getString("file_id_or_url"))
            assertEquals(1L, args.getLong("course_id"))
            assertEquals("announcement", args.getString("source_type"))
            assertEquals(7L, args.getLong("source_id"))
            assertFalse(row.toString().contains("verifier"))
        }
        assertNotNull(Queries.skipReason(hidden))
        assertNull(Queries.skipReason(hidden, linked = true))
        assertTrue(Queries.skipReason(JSONObject(hidden.toString()).put("locked_for_user", true), linked = true)!!.contains("잠긴"))
    }

    @Test fun timetableChanges() {
        val w = TimetableLogic.Weekly("1", 0, listOf(
            TimetableLogic.CourseMeetings(1, "2026-2 가상현실공학 (001)", listOf(TimetableLogic.Meeting("목", "09:30", "10:45", "301-118"), TimetableLogic.Meeting("목", "15:30", "16:45", "301-118"))),
            TimetableLogic.CourseMeetings(2, "2026-2 자료구조 (001)", listOf(TimetableLogic.Meeting("목", "11:00", "12:15", null))),
        ), emptyList())
        val thu = LocalDate.of(2026, 11, 19)
        val changes = listOf(
            TimetableLogic.Change(1, thu, "휴강", "현실공학", note = "학회"),                       // 이름 일부로도 맞는다
            TimetableLogic.Change(2, thu, "보강", "자료구조", courseId = 2, start = "18:00", end = "19:15", room = "302-208"),
            TimetableLogic.Change(3, thu.plusDays(1), "휴강", "자료구조"),                           // 다른 날
        )
        val day = TimetableLogic.day(w, thu, changes)
        assertEquals(listOf("11:00" to "정규", "18:00" to "보강"), day.classes.map { it.start to it.kind })
        assertEquals(2, day.cancelled.size)
        assertEquals("학회", day.cancelled[0].note)
        // 시각을 준 휴강은 그 시각 수업만
        val one = TimetableLogic.day(w, thu, listOf(TimetableLogic.Change(4, thu, "휴강", "가상현실공학", courseId = 1, start = "15:30")))
        assertEquals(listOf("09:30", "11:00"), one.classes.map { it.start })
        assertTrue(TimetableLogic.sameCourse("가상현실 공학", "2026-2 가상현실공학 (001)"))
        assertFalse(TimetableLogic.sameCourse("가현공", "2026-2 가상현실공학 (001)"))
        assertEquals(changes, TimetableLogic.changesFromJson(TimetableLogic.changesToJson(changes)))
        assertEquals("보강은 시작·끝 시각이 필요해요", TimetableLogic.validateChange("보강", "자료구조", null, null))
        assertNull(TimetableLogic.validateChange("휴강", "자료구조", null, null))
        assertEquals("끝 시각이 시작보다 늦어야 해요", TimetableLogic.validateChange("보강", "자료구조", "18:00", "17:00"))
    }
}
