package kr.snu.etlassistant.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 새 강의자료: 기준선, 새 파일 감지, 숨김·잠김 제외, 파일 탭 막힘 건너뛰기, 실패 시 기준선 유지, 묶음 알림. */
class NewFilesTest {
    private val now = 1_800_000_000_000L
    private val ds = CourseRef(1, "자료구조")
    private val algo = CourseRef(2, "알고리즘")
    private fun f(id: Long, name: String = "file$id.pdf") = NewFiles.Outcome.Files(listOf(FileRef(id, name)))
    private fun files(vararg ids: Long) = NewFiles.Outcome.Files(ids.map { FileRef(it, "file$it.pdf") })

    @Test fun parseSkipsHiddenAndLocked() {
        val items = listOf(
            JSONObject().put("id", 1).put("display_name", "a.pdf"),
            JSONObject().put("id", 2).put("display_name", "b.pdf").put("hidden_for_user", true),
            JSONObject().put("id", 3).put("display_name", "c.pdf").put("locked_for_user", true),
            JSONObject().put("id", 4).put("filename", "d.pptx"),
        )
        assertEquals(listOf(FileRef(1, "a.pdf"), FileRef(4, "d.pptx")), NewFiles.parse(items))
    }

    @Test fun firstSeenCourseIsBaselineOnly() {
        val (s, n) = NewFiles.apply(FilesWatchState(), now, listOf(ds), mapOf(1L to files(1, 2, 3)))
        assertTrue(n.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), s.courses[1]!!.seen)
        assertEquals(now, s.lastRunAt)
        // 빈 목록으로 기준선을 잡은 과목도 다음부터는 새 파일을 알린다
        val (s2, _) = NewFiles.apply(FilesWatchState(), now, listOf(ds), mapOf(1L to files()))
        val (_, n2) = NewFiles.apply(s2, now + 1, listOf(ds), mapOf(1L to files(9)))
        assertEquals(1, n2.size)
    }

    @Test fun newFilesGroupedPerCourse() {
        val base = NewFiles.apply(FilesWatchState(), now, listOf(ds, algo), mapOf(1L to files(1), 2L to files(5))).first
        val (s, n) = NewFiles.apply(base, now + 1, listOf(ds, algo), mapOf(
            1L to NewFiles.Outcome.Files(listOf(FileRef(3, "3주차.pdf"), FileRef(2, "2주차.pdf"), FileRef(1, "file1.pdf"))),
            2L to files(5),
        ))
        val notice = n.single()
        assertEquals(Channel.NEW_FILES, notice.channel)
        assertEquals("새 강의자료 2개 · 자료구조", notice.title)
        assertEquals("3주차.pdf, 2주차.pdf", notice.text)
        assertEquals("https://myetl.snu.ac.kr/courses/1/files", notice.url)
        assertEquals("자료구조 새 강의자료 3주차.pdf, 2주차.pdf 받아줘", notice.prefill)
        assertNull(notice.prefillAction) // 누르면 채팅
        assertEquals(listOf(3L, 2L, 1L), s.courses[1]!!.seen)
        assertTrue(NewFiles.apply(s, now + 2, listOf(ds, algo), mapOf(1L to files(3, 2, 1))).second.isEmpty())
    }

    @Test fun prefillSummarisesManyFiles() {
        val p = NewFiles.prefill(ds, (1L..5L).map { FileRef(it, "f$it.pdf") })
        assertEquals("자료구조 새 강의자료 f1.pdf 외 4개 받아줘", p)
        // 줄바꿈 같은 제어문자는 정리한다
        assertEquals("자료구조 새 강의자료 a b.pdf 받아줘", NewFiles.prefill(ds, listOf(FileRef(1, "a\nb.pdf"))))
    }

    @Test fun mutedCourseUpdatesBaselineWithoutAlert() {
        val base = NewFiles.apply(FilesWatchState(), now, listOf(ds), mapOf(1L to files(1))).first
        val (s, n) = NewFiles.apply(base, now + 1, listOf(ds), mapOf(1L to files(2, 1)), muted = { it == 1L })
        assertTrue(n.isEmpty())
        assertTrue(2L in s.courses[1]!!.seen)
    }

    @Test fun failureKeepsBaselineAndRetries() {
        val base = NewFiles.apply(FilesWatchState(), now, listOf(ds), mapOf(1L to files(1))).first
        val (s, n) = NewFiles.apply(base, now + NewFiles.INTERVAL_MS, listOf(ds), mapOf(1L to NewFiles.Outcome.Failed))
        assertTrue(n.isEmpty())
        assertEquals(base.courses, s.courses)
        assertEquals(base.lastRunAt, s.lastRunAt) // 모두 실패면 다음 확인에서 다시
        assertTrue(NewFiles.due(s, now + NewFiles.INTERVAL_MS))
    }

    @Test fun deniedCourseIsSkippedForSevenDays() {
        val (s, _) = NewFiles.apply(FilesWatchState(), now, listOf(ds, algo), mapOf(1L to NewFiles.Outcome.Denied, 2L to files(1)))
        assertEquals(now + NewFiles.SKIP_MS, s.courses[1]!!.skipUntil)
        assertEquals(listOf(algo), NewFiles.toCheck(s, listOf(ds, algo), now + 1))
        assertEquals(listOf(ds, algo), NewFiles.toCheck(s, listOf(ds, algo), now + NewFiles.SKIP_MS))
        // 권한 문구 없는 401도 다른 과목이 성공했으면 파일 탭 막힘으로 본다
        val (s2, _) = NewFiles.apply(FilesWatchState(), now, listOf(ds, algo), mapOf(1L to NewFiles.Outcome.AuthRejected, 2L to files(1)))
        assertEquals(now + NewFiles.SKIP_MS, s2.courses[1]!!.skipUntil)
    }

    @Test fun allAuthRejectedLooksLikeTokenProblem() {
        val (s, _) = NewFiles.apply(FilesWatchState(), now, listOf(ds, algo), mapOf(1L to NewFiles.Outcome.AuthRejected, 2L to NewFiles.Outcome.AuthRejected))
        assertTrue(s.courses.isEmpty())
        assertEquals(0L, s.lastRunAt)
    }

    @Test fun skippedCourseBecomesNewFilesAfterSkipWithoutLosingBaseline() {
        val base = NewFiles.apply(FilesWatchState(), now, listOf(ds), mapOf(1L to files(1))).first
        val denied = NewFiles.apply(base, now + 1, listOf(ds), mapOf(1L to NewFiles.Outcome.Denied)).first
        assertEquals(listOf(1L), denied.courses[1]!!.seen)
        val (s, n) = NewFiles.apply(denied, now + NewFiles.SKIP_MS + 2, listOf(ds), mapOf(1L to files(2, 1)))
        assertEquals(1, n.size)
        assertEquals(0L, s.courses[1]!!.skipUntil)
    }

    @Test fun seenIsCapped() {
        val prev = CourseFiles((1L..NewFiles.SEEN_MAX.toLong()).toList())
        val (rec, fresh) = NewFiles.diffCourse(prev, listOf(FileRef(9999, "new.pdf")))
        assertEquals(listOf(FileRef(9999, "new.pdf")), fresh)
        assertEquals(NewFiles.SEEN_MAX, rec.seen.size)
        assertEquals(9999L, rec.seen.first())
    }

    @Test fun dueEveryFourHours() {
        assertTrue(NewFiles.due(FilesWatchState(), now))
        val s = FilesWatchState(lastRunAt = now)
        assertFalse(NewFiles.due(s, now + 3600_000))
        assertFalse(NewFiles.due(s, now + 3 * 3600_000))
        assertTrue(NewFiles.due(s, now + 4 * 3600_000 - 5 * 60_000)) // 주기 작업이 조금 일찍 돌아도
        assertTrue(NewFiles.due(s, now - 2 * 3600_000)) // 시계가 뒤로 갔을 때
    }

    @Test fun droppedCoursesArePrunedAndJsonRoundTrips() {
        val base = NewFiles.apply(FilesWatchState(), now, listOf(ds, algo), mapOf(1L to files(1), 2L to files(2))).first
        val (s, _) = NewFiles.apply(base, now + 1, listOf(ds), mapOf(1L to files(1)))
        assertEquals(setOf(1L), s.courses.keys)
        assertEquals(s, FilesWatchState.fromJson(s.toJson()))
        assertEquals(FilesWatchState(), FilesWatchState.fromJson("깨진 값"))
    }

    @Test fun singleFileNoticeHasNoBigText() {
        val base = NewFiles.apply(FilesWatchState(), now, listOf(ds), mapOf(1L to files())).first
        val n = NewFiles.apply(base, now + 1, listOf(ds), mapOf(1L to f(7, "강의노트.pdf"))).second.single()
        assertEquals("새 강의자료 1개 · 자료구조", n.title)
        assertNull(n.bigText)
        assertEquals("자료구조 새 강의자료 강의노트.pdf 받아줘", n.prefill)
    }
}
