package kr.snu.etlassistant.notify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SnapshotFetcherTest {

    private val now = Instant.parse("2026-11-19T03:00:00Z")

    private fun list(json: String): List<JSONObject> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun map(json: String) = SnapshotFetcher.mapAssignments(7L, "알고리즘", list(json), now)

    // ---- 과제 ----------------------------------------------------------------

    @Test
    fun 미게시_과제는_제외하고_published_필드가_없으면_포함() {
        val r = map("""[
            {"id":1,"name":"공개","published":true},
            {"id":2,"name":"미게시","published":false},
            {"id":3,"name":"필드없음"}
        ]""")
        assertEquals(listOf(1L, 3L), r.map { it.assignmentId })
        assertEquals("알고리즘", r[0].courseName)
        assertEquals(7L, r[0].courseId)
    }

    @Test
    fun due가_null이거나_비정상_문자열이면_null() {
        val r = map("""[
            {"id":1,"name":"a","due_at":null},
            {"id":2,"name":"b","due_at":"내일"},
            {"id":3,"name":"c"},
            {"id":4,"name":"d","due_at":"2026-11-20T14:59:00Z"},
            {"id":5,"name":"e","due_at":"2026-11-20T23:59:00+09:00"}
        ]""")
        assertNull(r[0].due)
        assertNull(r[1].due)
        assertNull(r[2].due)
        assertEquals(Instant.parse("2026-11-20T14:59:00Z"), r[3].due)
        assertEquals(Instant.parse("2026-11-20T14:59:00Z"), r[4].due)
    }

    @Test
    fun 오프라인과_온라인_구분() {
        val r = map("""[
            {"id":1,"name":"종이","submission_types":["on_paper"]},
            {"id":2,"name":"없음","submission_types":["none"]},
            {"id":3,"name":"업로드","submission_types":["online_upload"]},
            {"id":4,"name":"혼합","submission_types":["on_paper","online_text_entry"]},
            {"id":5,"name":"필드없음"}
        ]""")
        assertEquals(listOf(false, false, true, true, false), r.map { it.online })
    }

    @Test
    fun 제출_판정은_workflow_state나_submitted_at() {
        val r = map("""[
            {"id":1,"name":"a","submission":{"workflow_state":"submitted"}},
            {"id":2,"name":"b","submission":{"workflow_state":"graded"}},
            {"id":3,"name":"c","submission":{"workflow_state":"pending_review"}},
            {"id":4,"name":"d","submission":{"workflow_state":"unsubmitted","submitted_at":"2026-11-18T01:00:00Z"}},
            {"id":5,"name":"e","submission":{"workflow_state":"unsubmitted","submitted_at":null}},
            {"id":6,"name":"f"}
        ]""")
        assertEquals(listOf(true, true, true, true, false, false), r.map { it.submitted })
    }

    @Test
    fun missing_플래그와_마감_지난_온라인_과제() {
        val r = map("""[
            {"id":1,"name":"플래그","submission_types":["online_upload"],"due_at":"2026-11-25T00:00:00Z","submission":{"missing":true}},
            {"id":2,"name":"지난온라인","submission_types":["online_upload"],"due_at":"2026-11-10T00:00:00Z"},
            {"id":3,"name":"지난오프라인","submission_types":["on_paper"],"due_at":"2026-11-10T00:00:00Z"},
            {"id":4,"name":"아직온라인","submission_types":["online_upload"],"due_at":"2026-11-25T00:00:00Z"},
            {"id":5,"name":"제출됨","submission_types":["online_upload"],"due_at":"2026-11-10T00:00:00Z","submission":{"workflow_state":"submitted","missing":true}}
        ]""")
        assertEquals(listOf(true, true, false, false, false), r.map { it.missing })
    }

    @Test
    fun points가_없거나_null이면_null() {
        val r = map("""[
            {"id":1,"name":"a","points_possible":10},
            {"id":2,"name":"b","points_possible":2.5},
            {"id":3,"name":"c","points_possible":null},
            {"id":4,"name":"d"}
        ]""")
        assertEquals(10.0, r[0].points!!, 0.0)
        assertEquals(2.5, r[1].points!!, 0.0)
        assertNull(r[2].points)
        assertNull(r[3].points)
    }

    @Test
    fun html_url이_없으면_null() {
        val r = map("""[
            {"id":1,"name":"a","html_url":"https://myetl.snu.ac.kr/courses/7/assignments/1"},
            {"id":2,"name":"b"},
            {"id":3,"name":"c","html_url":null}
        ]""")
        assertEquals("https://myetl.snu.ac.kr/courses/7/assignments/1", r[0].url)
        assertNull(r[1].url)
        assertNull(r[2].url)
    }

    @Test
    fun 이름이_없거나_비면_제목_없음() {
        val r = map("""[
            {"id":1},
            {"id":2,"name":null},
            {"id":3,"name":""},
            {"id":4,"name":"   "}
        ]""")
        assertTrue(r.all { it.name == "(제목 없음)" })
    }

    @Test
    fun 정렬은_마감_오름차순이고_마감_없음은_마지막이며_같은_마감은_강의_순서_유지() {
        fun item(course: Long, id: Long, due: String?) = AssignmentItem(
            course, "c$course", id, "n$id", due?.let { Instant.parse(it) },
            online = true, points = null, url = null, submitted = false, missing = false,
        )
        val r = SnapshotFetcher.sortAssignments(listOf(
            item(1, 1, null),
            item(1, 2, "2026-11-22T00:00:00Z"),
            item(2, 3, "2026-11-20T00:00:00Z"),
            item(2, 4, null),
            item(3, 5, "2026-11-22T00:00:00Z"),
        ))
        assertEquals(listOf(3L, 2L, 5L, 1L, 4L), r.map { it.assignmentId })
    }

    // ---- 공지 ----------------------------------------------------------------

    private val names = mapOf(7L to "알고리즘", 8L to "운영체제")

    private fun ann(json: String, days: Int = 2) = SnapshotFetcher.mapAnnouncements(list(json), names, now, days)

    @Test
    fun context_code로_강의명을_찾고_모르는_ID는_숫자_문자열() {
        val r = ann("""[
            {"id":1,"title":"a","context_code":"course_7","posted_at":"2026-11-19T02:00:00Z","html_url":"https://myetl.snu.ac.kr/courses/7/discussion_topics/1"},
            {"id":2,"title":"b","context_code":"course_999","posted_at":"2026-11-19T01:00:00Z"}
        ]""")
        assertEquals(7L, r[0].courseId)
        assertEquals("알고리즘", r[0].courseName)
        assertEquals("https://myetl.snu.ac.kr/courses/7/discussion_topics/1", r[0].url)
        assertEquals(999L, r[1].courseId)
        assertEquals("999", r[1].courseName)
        assertNull(r[1].url)
    }

    @Test
    fun url이_없으면_url_필드로_대체() {
        val r = ann("""[{"id":1,"title":"a","context_code":"course_7","posted_at":"2026-11-19T02:00:00Z","url":"https://myetl.snu.ac.kr/x"}]""")
        assertEquals("https://myetl.snu.ac.kr/x", r[0].url)
    }

    @Test
    fun posted_at_경계는_포함하고_그_이전은_제외() {
        // now - 2일 = 2026-11-17T03:00:00Z
        val r = ann("""[
            {"id":1,"title":"경계","context_code":"course_7","posted_at":"2026-11-17T03:00:00Z"},
            {"id":2,"title":"1초전","context_code":"course_7","posted_at":"2026-11-17T02:59:59Z"},
            {"id":3,"title":"최근","context_code":"course_7","posted_at":"2026-11-19T00:00:00Z"}
        ]""")
        assertEquals(listOf(3L, 1L), r.map { it.id })
    }

    @Test
    fun posted_at이_없으면_now로_간주하고_최신순_정렬() {
        val r = ann("""[
            {"id":1,"title":"옛","context_code":"course_7","posted_at":"2026-11-18T00:00:00Z"},
            {"id":2,"title":"없음","context_code":"course_7"},
            {"id":3,"title":"깨짐","context_code":"course_8","posted_at":"어제"}
        ]""")
        assertEquals(3, r.size)
        assertEquals(1L, r.last().id)
        assertEquals(now, r.first { it.id == 2L }.postedAt)
        assertEquals(now, r.first { it.id == 3L }.postedAt)
    }

    @Test
    fun 제목이_없으면_제목_없음_context_code가_없으면_ID_0() {
        val r = ann("""[{"id":5,"posted_at":"2026-11-19T00:00:00Z"}]""")
        assertEquals("(제목 없음)", r[0].title)
        assertEquals(0L, r[0].courseId)
        assertEquals("0", r[0].courseName)
    }

    @Test
    fun html_본문은_텍스트로_바꾸고_600자로_제한() {
        val long = "가".repeat(700)
        val r = ann("""[
            {"id":1,"title":"a","context_code":"course_7","posted_at":"2026-11-19T00:00:00Z",
             "message":"<p>안녕&nbsp;하세요 &amp; <b>반갑습니다</b></p><script>alert(1)</script><p>둘째 줄</p>"},
            {"id":2,"title":"b","context_code":"course_7","posted_at":"2026-11-18T00:00:00Z","message":"$long"},
            {"id":3,"title":"c","context_code":"course_7","posted_at":"2026-11-17T12:00:00Z","message":null}
        ]""")
        assertEquals("안녕 하세요 & 반갑습니다\n둘째 줄", r[0].text)
        assertEquals(601, r[1].text.length)
        assertTrue(r[1].text.endsWith("…"))
        assertEquals("", r[2].text)
        assertFalse(r[0].text.contains("alert"))
    }

    // ---- KST 날짜 범위 -----------------------------------------------------------

    @Test
    fun KST_날짜_범위_UTC_자정_근처() {
        // UTC 11-19 15:30 = KST 11-20 00:30
        val (s, e) = SnapshotFetcher.kstDateRange(Instant.parse("2026-11-19T15:30:00Z"), 2)
        assertEquals("2026-11-18", s)
        assertEquals("2026-11-21", e)
    }

    @Test
    fun KST_날짜_범위_UTC_자정_직전은_같은_KST_날() {
        // UTC 11-19 14:59 = KST 11-19 23:59
        val (s, e) = SnapshotFetcher.kstDateRange(Instant.parse("2026-11-19T14:59:00Z"), 1)
        assertEquals("2026-11-18", s)
        assertEquals("2026-11-20", e)
    }

    @Test
    fun KST_날짜_범위_월_경계() {
        val (s, e) = SnapshotFetcher.kstDateRange(Instant.parse("2026-12-01T00:00:00Z"), 2)
        assertEquals("2026-11-29", s)
        assertEquals("2026-12-02", e)
    }
}
