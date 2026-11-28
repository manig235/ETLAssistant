package kr.snu.youngmini.portable
import kr.snu.etlassistant.etl.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 고정 응답은 2026-11-27 MIT Learn API·ocw.mit.edu에서 실제로 받은 것(src/test/resources/study/). */
class StudyLogicTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("study/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun rejects(url: String) {
        try {
            StudyLogic.checkFetchUrl(url)
            fail("허용하면 안 됨: $url")
        } catch (_: StudyException) {}
    }

    @Test fun allowlistAcceptsOnlyOcwOverHttps() {
        assertEquals("https://ocw.mit.edu/courses/6-003/", StudyLogic.checkFetchUrl("https://ocw.mit.edu/courses/6-003/"))
        assertEquals("https://ocw.mit.edu/courses/x/", StudyLogic.checkFetchUrl(" https://OCW.mit.edu/courses/x/#top "))
        // 허용 호스트의 http는 https로 올린다(OCW 설명에 http 주소가 남아 있다).
        assertEquals("https://ocw.mit.edu/6-003F11", StudyLogic.checkFetchUrl("http://ocw.mit.edu/6-003F11"))
        assertEquals("https://ocw.mit.edu/search/?q=a", StudyLogic.checkFetchUrl("https://ocw.mit.edu/search/?q=a"))
        rejects("https://evil.example/courses/")
        rejects("https://ocw.mit.edu.evil.example/")
        rejects("https://evilocw.mit.edu/")
        rejects("https://user:pw@ocw.mit.edu/")
        rejects("https://ocw.mit.edu:8443/")
        rejects("ftp://ocw.mit.edu/")
        rejects("file:///etc/passwd")
        rejects("https://myetl.snu.ac.kr/api/v1/users/self")
        rejects("https://127.0.0.1/")
        // 검색 API 호스트는 study_fetch로 받지 않는다(검색은 정해진 주소로만).
        rejects("https://api.learn.mit.edu/api/v1/learning_resources_search/?q=x")
        rejects("")
    }

    @Test fun redirectsStayOnAllowlist() {
        val base = "https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011"
        assertEquals("https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011/", StudyLogic.redirectTarget(base, "/courses/6-003-signals-and-systems-fall-2011/"))
        assertEquals("https://ocw.mit.edu/courses/a/b.pdf", StudyLogic.redirectTarget("https://ocw.mit.edu/courses/a/", "b.pdf"))
        for (bad in listOf("https://evil.example/x", "http://ocw.mit.edu/x", "//evil.example/x", null, "")) {
            try {
                StudyLogic.redirectTarget(base, bad)
                fail("이동 허용하면 안 됨: $bad")
            } catch (_: StudyException) {}
        }
    }

    @Test fun searchUrlsUseLearnApiWithFilters() {
        val s = StudySites.MIT_OCW
        val u = StudyLogic.courseSearchUrl(s, "signals and systems", "exams_and_problem_sets", 99)
        assertTrue(u.startsWith("https://api.learn.mit.edu/api/v1/learning_resources_search/?q=signals%20and%20systems&offered_by=ocw&resource_type=course"))
        assertTrue(u.contains("&course_feature=Exams&course_feature=Problem%20Sets"))
        assertTrue(u.endsWith("&limit=${StudyLogic.MAX_LIMIT}"))
        assertFalse(StudyLogic.courseSearchUrl(s, "x", "all", 5).contains("course_feature"))
        val f = StudyLogic.fileSearchUrl(s, 5611, "", "exams")
        assertEquals("https://api.learn.mit.edu/api/v1/content_file_search/?resource_id=5611&content_feature_type=Exams" +
            "&content_feature_type=Exam%20Solutions&content_feature_type=Supplemental%20Exam%20Materials&limit=50", f)
        assertTrue(StudyLogic.fileSearchUrl(s, 5611, "quiz 2", "all").contains("&q=quiz%202&limit=50"))
        assertTrue(StudySites.isSearchHost("api.learn.mit.edu"))
        assertFalse(StudySites.isSearchHost("ocw.mit.edu"))
        try { StudyLogic.materials("everything"); fail() } catch (_: StudyException) {}
        try { StudyLogic.site("khan"); fail() } catch (_: StudyException) {}
        try { StudyLogic.cleanQuery("   "); fail() } catch (_: StudyException) {}
        assertEquals("a b", StudyLogic.cleanQuery(" a\n\tb "))
        assertEquals(120, StudyLogic.cleanQuery("x".repeat(500)).length)
    }

    @Test fun parsesCourseSearch() {
        val r = StudyLogic.parseCourses(StudySites.MIT_OCW, fixture("ocw_course_search.json"), "signals and systems")
        assertEquals(9, r.getInt("total"))
        val courses = r.getJSONArray("courses")
        assertEquals(3, courses.length())
        val c = courses.getJSONObject(0)
        assertEquals(5611L, c.getLong("course_id"))
        assertEquals("Signals and Systems", c.getString("title"))
        assertEquals("6.003", c.getJSONArray("course_numbers").getString(0))
        assertEquals("Fall 2011", c.getString("term"))
        assertEquals("Undergraduate", c.getJSONArray("level").getString(0))
        assertEquals("Prof. Dennis Freeman", c.getJSONArray("instructors").getString(0))
        assertEquals("https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011/", c.getString("url"))
        assertTrue(c.getBoolean("has_exam_solutions"))
        assertTrue(c.getJSONArray("materials").toString().contains("Exam Solutions"))
        assertEquals("CC BY-NC-SA 4.0", c.getString("license"))
        assertTrue(c.getString("description").length <= 401)
        assertFalse(r.has("hint"))
    }

    @Test fun emptyKoreanSearchSuggestsEnglish() {
        val r = StudyLogic.parseCourses(StudySites.MIT_OCW, """{"count":0,"results":[]}""", "회로이론")
        assertEquals(0, r.getJSONArray("courses").length())
        assertTrue(r.getString("hint").contains("영어"))
        try {
            StudyLogic.parseCourses(StudySites.MIT_OCW, "<html>", "x")
            fail()
        } catch (_: StudyException) {}
    }

    @Test fun parsesCourseFiles() {
        val r = StudyLogic.parseFiles(StudySites.MIT_OCW, fixture("ocw_content_files.json"), 5611)
        assertEquals("Signals and Systems", r.getString("course"))
        assertEquals("Fall 2011", r.getString("term"))
        assertEquals(54, r.getInt("total"))
        val files = r.getJSONArray("files")
        assertEquals(6, files.length())
        val page = files.getJSONObject(0)
        assertEquals("page", page.getString("format"))
        assertTrue(page.getBoolean("fetchable"))
        val zip = files.getJSONObject(1)
        assertEquals("zip", zip.getString("format"))
        assertFalse(zip.getBoolean("fetchable"))
        val exam = files.getJSONObject(2)
        assertEquals("Final Exam, Fall 2009", exam.getString("title"))
        assertEquals("pdf", exam.getString("format"))
        assertEquals("Exams", exam.getJSONArray("types").getString(0))
        assertEquals("https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011/resources/final-exam-fall-2009/", exam.getString("url"))
        assertEquals("This file contains information regarding fall 2009 final exam.", exam.getString("description"))
    }

    @Test fun parsesResourcePageAndDownloadLink() {
        val url = "https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011/resources/final-exam-fall-2009/"
        val p = StudyLogic.parsePage(fixture("ocw_resource_page.html"), url)
        assertEquals("Final Exam, Fall 2009", p.title)
        assertEquals("Signals and Systems", p.course)
        assertEquals("6.003 | Fall 2011 | Undergraduate", p.numberTerm)
        assertEquals(listOf("Prof. Dennis Freeman"), p.instructors)
        assertEquals("https://creativecommons.org/licenses/by-nc-sa/4.0/", p.licenseUrl)
        assertEquals("https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011/6af64c160948026a525bb02fad92d351_MIT6_003F11_F09final.pdf", p.download)
        assertTrue(p.text.contains("Final Exam, Fall 2009"))
        assertFalse(p.text.contains("PDFObject"))
        val src = StudyLogic.source(StudySites.MIT_OCW, url, p, p.download)
        assertEquals("6.003", src.getString("course_number"))
        assertEquals("Fall 2011", src.getString("term"))
        assertEquals("CC BY-NC-SA 4.0", src.getString("license"))
        assertTrue(src.getString("attribution").contains("MIT OpenCourseWare, https://ocw.mit.edu"))
    }

    @Test fun parsesCoursePageTextAndLinks() {
        val url = "https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011/pages/exams/"
        val p = StudyLogic.parsePage(fixture("ocw_exams_page.html"), url, 5000)
        assertEquals("Exams", p.title)
        assertNull(p.download)
        assertTrue(p.text.contains("Quiz 1 Fall 2011 (PDF)"))
        assertTrue(p.text.length <= 5001)
        val quiz = p.links.firstOrNull { it.text == "Quiz 1 Fall 2011 (PDF)" }
        assertNotNull(quiz)
        assertEquals("https://ocw.mit.edu/courses/6-003-signals-and-systems-fall-2011/resources/quiz-1-fall-2011/", quiz!!.url)
        // 같은 사이트 링크만, 최대 개수까지
        assertTrue(p.links.all { it.url.startsWith("https://ocw.mit.edu/") })
        assertTrue(p.links.size <= StudyLogic.MAX_LINKS)
    }

    @Test fun pageLinksDropOffsiteAndScripts() {
        val html = """<div id="main-course-section"><a href="https://evil.example/x">a</a><a href="javascript:alert(1)">b</a>
            <a href="#top">c</a><a href="/courses/z/resources/q/">Quiz &amp; Solutions</a></div>"""
        val p = StudyLogic.parsePage(html, "https://ocw.mit.edu/courses/z/pages/exams/")
        assertEquals(listOf(StudyLogic.Link("Quiz & Solutions", "https://ocw.mit.edu/courses/z/resources/q/")), p.links)
        assertNull(p.course)
        assertEquals("확인 필요: https://example.org/other", StudyLogic.source(StudySites.MIT_OCW, "u",
            p.copy(licenseUrl = "https://example.org/other")).getString("license"))
    }

    @Test fun contentKindsAndSizeLimits() {
        assertEquals(StudyLogic.Kind.PDF, StudyLogic.kindOf("application/pdf"))
        assertEquals(StudyLogic.Kind.HTML, StudyLogic.kindOf("text/html; charset=utf-8"))
        assertEquals(StudyLogic.Kind.OTHER, StudyLogic.kindOf("application/zip"))
        assertEquals(StudyLogic.Kind.OTHER, StudyLogic.kindOf(null))
        assertTrue(StudyLogic.looksLikePdf("%PDF-1.5".toByteArray()))
        assertFalse(StudyLogic.looksLikePdf("<html>".toByteArray()))
        assertEquals(30L * 1024 * 1024, StudyLogic.MAX_PDF_BYTES)
        assertEquals(2 * 1024 * 1024, StudyLogic.MAX_HTML_BYTES)
        assertEquals(2 * 1024 * 1024, StudyLogic.MAX_JSON_BYTES)
        assertEquals(3, StudyLogic.MAX_REDIRECTS)
        assertFalse(StudyLogic.USER_AGENT.contains("영민") || StudyLogic.USER_AGENT.contains("snu", ignoreCase = true))
    }

    @Test fun storedNamesAreStableAndSafe() {
        val u = "https://ocw.mit.edu/courses/x/abc_MIT6_003F11_F09final.pdf"
        val a = StudyLogic.storedName(StudySites.MIT_OCW, u, "MIT6_003F11_F09final.pdf")
        assertEquals(a, StudyLogic.storedName(StudySites.MIT_OCW, u, "MIT6_003F11_F09final.pdf"))
        assertTrue(a.startsWith("ocw-") && a.endsWith("-MIT6_003F11_F09final.pdf"))
        val b = StudyLogic.storedName(StudySites.MIT_OCW, "https://ocw.mit.edu/other", "../../etc/passwd")
        assertFalse(b.contains("/"))
        assertTrue(b.endsWith(".pdf"))
    }

    @Test fun disabledMessageTellsHowToTurnOn() {
        assertTrue(StudyLogic.DISABLED_MESSAGE.contains("설정 → 대화 → '공개 기출 자료 찾기'"))
    }
}
