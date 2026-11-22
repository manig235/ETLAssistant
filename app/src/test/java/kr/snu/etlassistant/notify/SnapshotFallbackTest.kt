package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SnapshotFallbackTest {
    private val now: Instant = Instant.parse("2026-11-22T03:00:00Z")
    private fun h(x: Long) = now.plus(Duration.ofHours(x))

    private fun asg(cid: Long, aid: Long, due: Instant?, course: String = "강의$cid", online: Boolean = true, submitted: Boolean = false) =
        AssignmentItem(cid, course, aid, "과제$aid", due, online, null, null, submitted, false)

    private fun cache(hoursAgo: Long, vararg a: AssignmentItem) = CachedSnapshot(now.minus(Duration.ofHours(hoursAgo)).toEpochMilli(), a.toList())
    private fun snap(a: List<AssignmentItem> = emptyList(), errors: List<String> = emptyList()) = Snapshot(now, a, emptyList(), errors)

    @Test fun connectionErrorsAreDetected() {
        assertTrue(SnapshotFallback.hasConnectionErrors(listOf("강의1: eTL 연결 실패: /api/v1/courses/1/assignments (UnknownHostException)")))
        assertTrue(SnapshotFallback.hasConnectionErrors(listOf("공지: eTL 연결 실패: /api/v1/announcements (SocketTimeoutException)")))
        assertEquals(false, SnapshotFallback.hasConnectionErrors(listOf("강의1: 사용자에게 이 동작을 수행할 권한이 없음")))
        assertEquals(false, SnapshotFallback.hasConnectionErrors(emptyList()))
    }

    @Test fun totalFailureUsesCache() {
        val r = SnapshotFallback.apply(snap(errors = listOf("eTL 연결 실패")), cache(2, asg(1, 11, h(30)), asg(2, 21, h(5))), now, totalFailure = true)
        assertEquals(listOf(21L, 11L), r.snapshot.assignments.map { it.assignmentId }) // 마감순
        assertEquals(now.minus(Duration.ofHours(2)), r.cachedAt)
        assertEquals(listOf("eTL 연결 실패"), r.snapshot.errors) // 오류는 그대로 남긴다
    }

    @Test fun totalFailureWithoutCacheKeepsEmpty() {
        val r = SnapshotFallback.apply(snap(errors = listOf("x")), null, now, totalFailure = true)
        assertTrue(r.snapshot.assignments.isEmpty())
        assertNull(r.cachedAt)
    }

    @Test fun tooOldCacheIsIgnored() {
        val r = SnapshotFallback.apply(snap(), cache(73, asg(1, 11, h(30))), now, totalFailure = true)
        assertTrue(r.snapshot.assignments.isEmpty())
        assertNull(r.cachedAt)
    }

    @Test fun partialFailureFillsOnlyFailedCourses() {
        val fresh = snap(listOf(asg(1, 11, h(10))), errors = listOf("강의2: eTL 연결 실패: /api/v1/courses/2/assignments (SocketTimeoutException)", "공지: 실패"))
        val c = cache(1, asg(1, 99, h(20)), asg(2, 21, h(5)), asg(3, 31, h(6)))
        val r = SnapshotFallback.apply(fresh, c, now, totalFailure = false)
        // 강의1은 새로 받은 것만(옛 99는 버림), 강의2는 저장본, 강의3은 실패하지 않았으니 넣지 않는다
        assertEquals(listOf(21L, 11L), r.snapshot.assignments.map { it.assignmentId })
        assertEquals(now.minus(Duration.ofHours(1)), r.cachedAt)
    }

    @Test fun noErrorsNoFallback() {
        val fresh = snap(listOf(asg(1, 11, h(10))))
        val r = SnapshotFallback.apply(fresh, cache(1, asg(2, 21, h(5))), now, totalFailure = false)
        assertEquals(fresh, r.snapshot)
        assertNull(r.cachedAt)
    }

    @Test fun announcementOnlyFailureIsNotFilled() {
        assertEquals(emptySet<String>(), SnapshotFallback.failedCourses(listOf("공지: eTL 연결 실패", "eTL 토큰 없음")))
    }

    @Test fun passedDueBecomesMissingAndMyDeadlinesAreSkipped() {
        val c = cache(5, asg(1, 11, h(-1)), asg(1, 12, h(-1), online = false), asg(1, 13, h(-1), submitted = true), asg(MyDeadlines.COURSE_ID, 1, h(2)))
        val r = SnapshotFallback.apply(snap(), c, now, totalFailure = true)
        assertEquals(listOf(11L, 12L, 13L), r.snapshot.assignments.map { it.assignmentId })
        assertEquals(listOf(true, false, false), r.snapshot.assignments.map { it.missing })
    }

    @Test fun ageLabels() {
        assertEquals("방금", SnapshotFallback.ageLabel(now, now))
        assertEquals("25분 전", SnapshotFallback.ageLabel(now.minusSeconds(25 * 60), now))
        assertEquals("3시간 전", SnapshotFallback.ageLabel(now.minusSeconds(3 * 3600 + 100), now))
        assertEquals("2일 전", SnapshotFallback.ageLabel(now.minusSeconds(50 * 3600), now))
    }
}
