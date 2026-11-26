package kr.snu.etlassistant.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 실제 MIT Learn API·ocw.mit.edu로 확인하는 시험. 평소에는 건너뛰고 `STUDY_LIVE=1`일 때만 돈다
 * (`STUDY_LIVE=1 ./gradlew testDebugUnitTest --tests '*StudyLiveTest'`).
 */
class StudyLiveTest {
    private val live = System.getenv("STUDY_LIVE") == "1"

    @Test fun searchListAndFetchOcwExam() {
        assumeTrue(live)
        val site = StudySites.MIT_OCW
        val courses = StudyLogic.parseCourses(site, StudyClient.getJson(StudyLogic.courseSearchUrl(site, "signals and systems", "exams", 5)), "signals and systems")
        val list = courses.getJSONArray("courses")
        assertTrue(list.length() > 0)
        val c = (0 until list.length()).map { list.getJSONObject(it) }.first { it.getJSONArray("course_numbers").toString().contains("6.003") }
        val files = StudyLogic.parseFiles(site, StudyClient.getJson(StudyLogic.fileSearchUrl(site, c.getLong("course_id"), "final", "exams")), c.getLong("course_id"))
        val exam = (0 until files.getJSONArray("files").length()).map { files.getJSONArray("files").getJSONObject(it) }
            .first { it.getString("format") == "pdf" }
        val dir = Files.createTempDirectory("study").toFile()
        val page = StudyClient.fetch(exam.getString("url"), File(dir, "page.pdf")) as StudyClient.Fetched.Page
        val info = StudyLogic.parsePage(page.html, page.url)
        assertEquals("Signals and Systems", info.course)
        val pdf = StudyClient.fetch(info.download!!, File(dir, "exam.pdf")) as StudyClient.Fetched.Pdf
        assertTrue(pdf.bytes > 10_000)
        assertTrue(StudyLogic.looksLikePdf(pdf.file.readBytes().copyOf(4)))
        // 끝 / 없는 주소는 같은 사이트 안으로 리다이렉트된다.
        val redirected = StudyClient.fetch(c.getString("url").trimEnd('/'), File(dir, "x.pdf")) as StudyClient.Fetched.Page
        assertEquals(c.getString("url"), redirected.url)
        println("live ok: ${c.getString("title")} ${c.getString("term")} → ${exam.getString("title")} ${pdf.bytes} bytes")
        dir.deleteRecursively()
    }
}
