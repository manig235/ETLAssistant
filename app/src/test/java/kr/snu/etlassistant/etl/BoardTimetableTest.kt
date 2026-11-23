package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Base64

/** ttokttok-pet의 etl-board / timetable 테스트 픽스처를 옮긴 것. */
class BoardTimetableTest {
    private val base = EtlClient.BASE_URL

    // ---- 게시판 -------------------------------------------------------------

    private fun jwt(exp: Long): String {
        val enc = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        return listOf(enc("""{"alg":"HS256","typ":"JWT"}"""), enc("""{"role":"student","iat":${exp - 7200},"exp":$exp}"""), "sig").joinToString(".")
    }

    private val launchPage = """<!doctype html><html><body>
<form action="$base/learningx/lti/learningx_board/boards" method="POST" id="tool_form">
  <input type="hidden" name="oauth_signature" value="private-oauth-signature">
  <input type="hidden" name="context_title" value="2026-2 &quot;제어&quot; &amp; 공학">
  <input type="hidden" name="custom_canvas_course_id" value="305930">
</form><script>document.getElementById('tool_form').submit()</script></body></html>"""

    @Test fun launchFormPicksLearningxFormAndDecodesValues() {
        val f = BoardLogic.launchForm(launchPage)!!
        assertEquals("$base/learningx/lti/learningx_board/boards", f.action)
        assertEquals("2026-2 \"제어\" & 공학", f.fields.toMap()["context_title"])
        assertEquals("private-oauth-signature", f.fields.toMap()["oauth_signature"])
        assertTrue(BoardLogic.formBody(f.fields).contains("context_title=2026-2+%22%EC%A0%9C%EC%96%B4%22+%26+%EA%B3%B5%ED%95%99"))
    }

    @Test fun launchFormRejectsOtherHostsAndPaths() {
        assertNull(BoardLogic.launchForm(launchPage.replace("$base/learningx/lti/", "https://evil.example/learningx/lti/")))
        assertNull(BoardLogic.launchForm(launchPage.replace("/learningx/lti/", "/login/")))
        assertNull(BoardLogic.launchForm("<html>no form</html>"))
    }

    @Test fun jwtExpiryAndCookie() {
        assertEquals(1_800_000_000_000L, BoardLogic.jwtExpiryMillis(jwt(1_800_000_000L)))
        assertNull(BoardLogic.jwtExpiryMillis("not-a-jwt"))
        val cookies = listOf("WMONID=abc; Path=/", "xn_api_token=${jwt(1_800_000_000L)}; Path=/; HttpOnly")
        assertEquals(jwt(1_800_000_000L), BoardLogic.cookieValue(cookies, "xn_api_token"))
        assertNull(BoardLogic.cookieValue(cookies, "missing"))
    }

    @Test fun boardToolAndLaunchUrl() {
        val tabs = JSONArray("""[{"id":"home","type":"internal","label":"홈"},{"id":"context_external_tool_85","type":"external","label":"게시판"},{"id":"context_external_tool_48","type":"external","label":"강의/출결"}]""")
        assertEquals(85L, BoardLogic.boardToolId(tabs))
        assertNull(BoardLogic.boardToolId(JSONArray("""[{"id":"context_external_tool_48","type":"external","label":"강의/출결"}]""")))
        assertTrue(BoardLogic.isLaunchUrl("$base/courses/305930/external_tools/sessionless_launch?verifier=x"))
        assertFalse(BoardLogic.isLaunchUrl("https://evil.example/courses/305930/external_tools/sessionless_launch?verifier=x"))
        assertFalse(BoardLogic.isLaunchUrl("$base/login/session_token?x=1"))
    }

    @Test fun boardsAndPostsMapping() {
        val boards = BoardLogic.boardsResult(305930, JSONArray("""[{"id":153242,"type":"anonymous","title":"아무거나 게시판","description":"<p>무기명 게시판</p>","total_post_count":18,"unread_post_count":2,"latest_post_created_at":"2026-10-15T17:19:18.000000Z"}]"""), "u")
        val b = boards.getJSONArray("boards").getJSONObject(0)
        assertEquals(153242L, b.getLong("board_id"))
        assertTrue(b.getBoolean("anonymous"))
        assertEquals("무기명 게시판", b.getString("description"))
        assertEquals(2, b.getInt("unread"))
        assertEquals("10-16(금) 02:19", b.getString("latest_post"))

        val posts = BoardLogic.postsResult(305930, 153242, 1, JSONObject("""{"items":[
            {"id":779536,"depth":0,"user_name":"조교","title":"중간고사 공지","is_notice":true,"view_count":89,"attachment_count":0,"comment_count":0,"created_at":"2026-10-13T13:15:56.000000Z"},
            {"id":781528,"depth":1,"user_name":"익명","title":"Re: 질문","is_notice":false,"attachment_count":1,"comment_count":2,"created_at":"2026-10-15T17:19:18.000000Z"}],
            "pagination":{"total":21,"current_page":1,"last_page":2}}"""), "u")
        assertEquals(2, posts.getInt("last_page"))
        val rows = posts.getJSONArray("posts")
        assertTrue(rows.getJSONObject(0).getBoolean("notice"))
        assertTrue(rows.getJSONObject(1).getBoolean("reply"))
    }

    @Test fun postHidesSignedAttachmentLinks() {
        val p = JSONObject("""{"id":779536,"user_name":"조교","title":"중간고사 공지","is_notice":true,
            "content":"<p>일시: <strong>10월 22일</strong></p><p>장소: 301동</p>",
            "attachments":[{"id":787697,"filename":"좌석표.pdf","filesize":1234,"url":"$base/files/5/download?verifier=private-verifier-value","canvas_file_id":5}],
            "comments":[{"user_name":"학생","content":"<p>감사합니다</p>","created_at":"2026-10-14T00:00:00.000000Z"}],
            "post_url":"$base/learningx/redirect/courses/305930/external_tools/85?post_id=779536",
            "prev_post":{"id":777092,"title":"중간고사","user_name":"학생","created_at":"2026-10-11T12:56:04.000000Z"},"next_post":null}""")
        val r = BoardLogic.postResult(305930, 153242, 779536, p, "fallback")
        assertFalse(r.toString().contains("private-verifier-value"))
        assertEquals(5L, r.getJSONArray("attachments").getJSONObject(0).getLong("file_id"))
        assertTrue(r.getString("text").contains("10월 22일"))
        assertEquals("감사합니다", r.getJSONArray("comments").getJSONObject(0).getString("text"))
        assertEquals("$base/learningx/redirect/courses/305930/external_tools/85?post_id=779536", r.getString("url"))
        assertEquals(777092L, r.getJSONObject("previous").getLong("post_id"))
        assertTrue(r.isNull("next"))
        assertTrue(BoardLogic.hasAttachment(p, 5))
        assertFalse(BoardLogic.hasAttachment(p, 6))
        assertEquals("fallback", BoardLogic.postLink(JSONObject("""{"post_url":"https://evil.example/x"}"""), "fallback"))
    }

    // ---- etl_get 경로·가리기 -----------------------------------------------------

    @Test fun getPathRules() {
        assertTrue(EtlLogic.isAllowedGetPath("/api/v1/users/self/todo"))
        assertTrue(EtlLogic.isAllowedGetPath("/api/v1/courses/1/discussion_topics/2/view"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/users/self/tokens"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/login/session_token"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/courses/1/external_tools/sessionless_launch?id=85"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1/courses/1/%2e%2e/x"))
        assertFalse(EtlLogic.isAllowedGetPath("/api/v1//x"))
    }

    @Test fun unsignMasksSignedLinksAndFeeds() {
        val o = JSONObject("""{"url":"$base/files/5/download?download_frd=1&verifier=SECRET","nested":[{"feed":"$base/feeds/calendars/user_SECRET2.ics"}],"n":3}""")
        val s = EtlLogic.unsign(o).toString()
        assertFalse(s.contains("SECRET"))
        assertTrue(s.contains("verifier=***"))
        assertTrue(s.contains("/feeds/calendars/***"))
        assertTrue(s.contains("\"n\":3"))
    }

    // ---- 시간표 ---------------------------------------------------------------

    private val syllabus = """<iframe class="xn-syllabus-iframe" src="https://sugang.snu.ac.kr/sugang/cc/cc103.action?openSchyy=2026&amp;openShtmFg=U000200002&amp;openDetaShtmFg=U000300001&amp;sbjtCd=430.309A&amp;ltNo=001&amp;sbjtSubhCd=000&amp;"></iframe>"""

    @Test fun catalogRefFromSyllabus() {
        val ref = TimetableLogic.catalogRef(syllabus)!!
        assertEquals("430.309A", ref["sbjtCd"])
        assertEquals("001", ref["ltNo"])
        assertEquals(TimetableLogic.REF_KEYS, ref.keys.toList())
        assertNull(TimetableLogic.catalogRef("<p>계획서 없음</p>"))
        assertNull(TimetableLogic.catalogRef(syllabus.replace("430.309A", "430'309")))
        // sbjtSubhCd가 없으면 000
        assertEquals("000", TimetableLogic.catalogRef(syllabus.replace("&amp;sbjtSubhCd=000", ""))!!["sbjtSubhCd"])
    }

    @Test fun parseMeetingsBothFormats() {
        val summary = JSONObject("""{"LISTTAB01":{"lsnTmtablFormaSmryCtnt":"월(17:00~18:15)|이론|301-103(무선랜제공)/수(17:00~18:15)|이론|301-103(무선랜제공)"}}""")
        val m = TimetableLogic.parseMeetings(summary)
        assertEquals(listOf("월", "수"), m.map { it.day })
        assertEquals("301-103", m[0].room)
        val fallback = JSONObject("""{"ltTime":["화(9:30~10:45)","목(9:30~10:45)"],"ltRoom":["43-1-101",""]}""")
        val f = TimetableLogic.parseMeetings(fallback)
        assertEquals("09:30", f[0].start)
        assertNull(f[1].room)
        assertTrue(TimetableLogic.parseMeetings(JSONObject()).isEmpty())
    }

    @Test fun professorComesFromSyllabusAndFallsBackToCourseDetails() {
        assertEquals("홍교수", TimetableLogic.professor(JSONObject("""{"LISTTAB03":{"profNm":"<b>홍교수 (대표교수)</b>"},"LISTTAB01":{"profNm":"김교수（담당교수）"}}""")))
        assertEquals("김교수", TimetableLogic.professor(JSONObject("""{"LISTTAB03":{"profNm":" "},"LISTTAB01":{"profNm":"김교수（담당교수）"}}""")))
        assertNull(TimetableLogic.professor(JSONObject("""{"LISTTAB03":{"profNm":null}}""")))
        assertNull(TimetableLogic.professor(null))
    }

    @Test fun creditsComeOnlyFromExplicitCatalogMetadata() {
        assertEquals(3.0, TimetableLogic.credits(JSONObject("""{"LISTTAB01":{"openPnt":"3"}}""")))
        assertEquals(4.0, TimetableLogic.credits(JSONObject("""{"LISTTAB01":{"openPnt":4}}""")))
        assertNull(TimetableLogic.credits(JSONObject("""{"LISTTAB01":{"openPnt":"미정"}}""")))
        assertNull(TimetableLogic.credits(JSONObject("""{"LISTTAB01":{"openPnt":-1}}""")))
        assertNull(TimetableLogic.credits(JSONObject()))
    }

    @Test fun professorSurvivesCacheAndOldCacheStillShowsMeetings() {
        val course = TimetableLogic.CourseMeetings(1,"과목",listOf(TimetableLogic.Meeting("월","09:00","10:00","301-302")),"홍교수(대표교수)", credits = 3.0)
        val current = TimetableLogic.Weekly("1",1000,listOf(course),emptyList())
        assertEquals(current.copy(courses = listOf(course.copy(professor = "홍교수"))),
            TimetableLogic.fromJson(TimetableLogic.toJson(current)))
        val old = JSONObject(TimetableLogic.toJson(current)).put("version",1)
        old.getJSONArray("courses").getJSONObject(0).remove("professor")
        old.getJSONArray("courses").getJSONObject(0).remove("credits")
        val restored = TimetableLogic.fromJson(old.toString())!!
        assertEquals(1, restored.metadataVersion)
        assertEquals(course.meetings, restored.courses.single().meetings)
        assertNull(restored.courses.single().professor)
        assertNull(restored.courses.single().credits)
    }

    @Test fun partialMetadataFailurePreservesKnownMeetingsAndProfessor() {
        val old = TimetableLogic.CourseMeetings(1,"과목",listOf(TimetableLogic.Meeting("월","09:00","10:00","301-302")),"이전 교수")
        val onlyProfessor = TimetableLogic.CatalogCourse(emptyList(),"새 교수",meetingError="조회 실패")
        assertEquals(old.meetings, TimetableLogic.refreshedCourse(1,"과목",onlyProfessor,old).meetings)
        assertEquals("새 교수", TimetableLogic.refreshedCourse(1,"과목",onlyProfessor,old).professor)
        assertEquals(old, TimetableLogic.refreshedCourse(1,"과목",null,old))
        val confirmedEmpty = TimetableLogic.CatalogCourse(emptyList(),null)
        assertTrue(TimetableLogic.refreshedCourse(1,"과목",confirmedEmpty,old).meetings.isEmpty())
        assertEquals("이전 교수", TimetableLogic.refreshedCourse(1,"과목",confirmedEmpty,old).professor)
    }

    @Test fun dayClassesAndCacheRoundTrip() {
        val w = TimetableLogic.Weekly(
            "1,2", 1000L,
            listOf(
                TimetableLogic.CourseMeetings(1, "2026-2 전기에너지변환 (001)", listOf(TimetableLogic.Meeting("월", "17:00", "18:15", "301-103"))),
                TimetableLogic.CourseMeetings(2, "2026-2 제어공학개론 (001)", listOf(TimetableLogic.Meeting("월", "09:30", "10:45", null), TimetableLogic.Meeting("수", "09:30", "10:45", null))),
            ),
            listOf("체력단련: 강의계획서에 수강편람 링크가 없어요"),
        )
        val monday = LocalDate.of(2026, 11, 23)
        val ev = TimetableLogic.ClassRow("12:00", "13:00", "제어공학개론", 2, "301-118", "일정", "보강")
        val rows = TimetableLogic.dayClasses(w, monday, listOf(ev))
        assertEquals(listOf("09:30", "12:00", "17:00"), rows.map { it.start })
        assertEquals("전기에너지변환", rows[2].course)
        assertEquals(listOf("• 09:30~10:45 제어공학개론", "• 12:00~13:00 제어공학개론 (301-118) [일정]", "• 17:00~18:15 전기에너지변환 (301-103)"), TimetableLogic.briefLines(rows))
        assertEquals(w, TimetableLogic.fromJson(TimetableLogic.toJson(w)))
        assertNotNull(TimetableLogic.weekJson(w).optJSONArray("수"))
        assertNull(TimetableLogic.weekJson(w).optJSONArray("금"))
    }
}
