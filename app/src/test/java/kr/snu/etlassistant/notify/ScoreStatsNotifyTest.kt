package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.ScoreStatistics
import kr.snu.etlassistant.etl.ScoreStatsParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 성적 통계의 알림 쪽: 성적 알림 한 줄, "성적 통계" 알림과 기준선, 공지 자동 저장·맞추기·알림 줄, "통계 저장" 버튼, 알림 기록. */
class ScoreStatsNotifyTest {
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z")
    private val stats = ScoreStatistics(min = 10.0, max = 95.0, mean = 71.0)
    private val due = now.minus(Duration.ofDays(3)).toEpochMilli()

    private fun graded(score: Double?, st: ScoreStatistics? = stats, name: String = "중간고사", aid: Long = 7, cid: Long = 5, points: Double? = 100.0) =
        AssignmentItem(cid, "2026-2 제어공학개론 (001)", aid, name, now.minus(Duration.ofDays(3)), true, points, "https://myetl.snu.ac.kr/courses/$cid/assignments/$aid",
            submitted = true, missing = false, score = score, stats = st)

    private fun snap(vararg a: AssignmentItem, ann: List<AnnouncementItem> = emptyList()) = Snapshot(now, a.toList(), ann)

    // ---- 성적 알림 ----------------------------------------------------------------

    @Test fun gradeAlertGetsStatsLine() {
        val prev = WatchState(true, mapOf("5:7" to WatchRecord("중간고사", "c", due, null)), true, statsInitialized = true)
        val (_, notices) = AssignmentWatch.diff(prev, snap(graded(78.0)), now)
        val n = notices.single()
        assertTrue(n.title.startsWith("성적"))
        assertEquals("중간고사 · 78 / 100", n.text)
        assertEquals("중간고사 · 78 / 100\n평균 71 · 내 점수 78 · 평균보다 7점 높아요", n.bigText)
    }

    @Test fun statsAppearingLaterGetOwnAlertOnceAndBaselineAfterUpdate() {
        // 업데이트 뒤 첫 확인(statsInitialized=false): 이미 채점된 과제에 분포가 있어도 알리지 않고 기록만.
        val old = WatchState(true, mapOf("5:7" to WatchRecord("중간고사", "c", due, 78.0)), true)
        val (s1, n1) = AssignmentWatch.diff(old, snap(graded(78.0)), now)
        assertTrue(n1.isEmpty())
        assertTrue(s1.statsInitialized)
        assertTrue(s1.records["5:7"]!!.statsSeen)
        // 기준선 뒤: 점수는 그대로인데 분포가 처음 보이면 "성적 통계" 한 번.
        val before = WatchState(true, mapOf("5:7" to WatchRecord("중간고사", "c", due, 78.0)), true, statsInitialized = true)
        val (s2, n2) = AssignmentWatch.diff(before, snap(graded(78.0)), now)
        assertEquals("성적 통계 · 2026-2 제어공학개론 (001)", n2.single().title)
        assertEquals("중간고사 · 평균 71 · 내 점수 78 · 평균보다 7점 높아요", n2.single().text)
        // 다음 확인에서는 다시 알리지 않는다. 받아 둔 옛 저장본(분포 없음)이 와도 본 적 있음은 유지.
        assertTrue(AssignmentWatch.diff(s2, snap(graded(78.0)), now).second.isEmpty())
        val (s3, _) = AssignmentWatch.diff(s2, snap(graded(78.0, st = null)), now)
        assertTrue(s3.records["5:7"]!!.statsSeen)
    }

    @Test fun mutedCourseAndChangesOffSuppressStatsAlert() {
        val before = WatchState(true, mapOf("5:7" to WatchRecord("중간고사", "c", due, 78.0)), true, statsInitialized = true)
        assertTrue(AssignmentWatch.diff(before, snap(graded(78.0)), now, muted = { it == 5L }).second.isEmpty())
        assertTrue(AssignmentWatch.diff(before, snap(graded(78.0)), now, changes = false).second.isEmpty())
    }

    @Test fun watchStateRoundTripKeepsStatsFlags() {
        val s = WatchState(true, mapOf("5:7" to WatchRecord("n", "c", 1L, 2.0, 3L, statsSeen = true)), true, statsInitialized = true)
        assertEquals(s, WatchState.fromJson(s.toJson()))
        val legacy = WatchState.fromJson(JSONObject().put("initialized", true).put("records", JSONObject().put("1:2", JSONObject().put("name", "x"))).toString())
        assertFalse(legacy.statsInitialized)
        assertFalse(legacy.records["1:2"]!!.statsSeen)
    }

    @Test fun snapshotFetcherParsesScoreStatistics() {
        val a = JSONObject().put("id", 7).put("name", "중간고사").put("submission_types", JSONArray().put("on_paper"))
            .put("submission", JSONObject().put("workflow_state", "graded").put("score", 78))
            .put("score_statistics", JSONObject().put("min", 10).put("max", 95).put("mean", 71))
        val item = SnapshotFetcher.mapAssignments(5, "c", listOf(a), now).single()
        assertEquals(71.0, item.stats!!.mean!!, 0.0)
        assertEquals(78.0, item.score!!, 0.0)
        assertNull(SnapshotFetcher.mapAssignments(5, "c", listOf(JSONObject().put("id", 8).put("name", "x")), now).single().stats)
    }

    // ---- 공지 자동 저장 ------------------------------------------------------------

    private fun ann(title: String, html: String, id: Long = 99, cid: Long = 5, course: String = "2026-2 제어공학개론 (001)"): AnnouncementItem =
        SnapshotFetcher.mapAnnouncements(listOf(JSONObject().put("id", id).put("title", title).put("message", html)
            .put("context_code", "course_$cid").put("posted_at", "2026-11-19T02:00:00Z")), mapOf(cid to course), now, 2).single()

    @Test fun announcementParsedAndMatchedToEtlAssignment() {
        val a = ann("중간고사 성적 공개", "<p>중간고사 평균 62.4점, 표준편차 15.1, 최고 98점, 최저 12점 (100점 만점)</p><p>Q1 50, Q2 63, Q3 75</p>")
        assertTrue(a.scoreStats)
        assertTrue(a.parsedStats!!.confident)
        val entries = ScoreStatsAuto.entries(a, listOf(graded(71.0, st = null, name = "Midterm Exam")), now.toEpochMilli())
        val e = entries.single()
        assertEquals("ann:99:mid:0", e.id)
        assertEquals(7L, e.assignmentId)
        assertEquals(71.0, e.myScore!!, 0.0)
        assertEquals(99L, e.sourceAnnouncementId)
        assertEquals("중간고사", e.title)
        val line = ScoreStatsAuto.lines(entries).single()
        assertTrue(line, line.startsWith("통계 저장됨 · 평균 62.4 · 내 점수 71 · 상위 약"))
    }

    @Test fun noMatchOrDifferentScaleLeavesMyScoreEmpty() {
        val a = ann("중간고사 성적", "평균 31.2 / 50, 최고 49 / 50")
        assertNull(ScoreStatsAuto.entries(a, listOf(graded(71.0, name = "중간고사", points = 100.0)), 0).single().myScore)
        assertNull(ScoreStatsAuto.entries(a, listOf(graded(71.0, name = "기말고사")), 0).single().assignmentId)
        assertEquals("통계 저장됨 · 평균 31.2 · 내 점수를 넣으면 위치를 알려 드려요",
            ScoreStatsAuto.lines(ScoreStatsAuto.entries(a, emptyList(), 0)).single())
    }

    @Test fun sectionsKeepOnlyMySection() {
        val a = ann("중간고사 성적", "1분반 평균 60.2, 최고 95\n2분반 평균 63.5, 최고 97", course = "2026-2 제어공학개론 (002)")
        val e = ScoreStatsAuto.entries(a, listOf(graded(70.0)), 0)
        assertEquals(listOf("중간고사 (2분반)"), e.map { it.title })
        assertEquals(70.0, e.single().myScore!!, 0.0)
        // 내 분반을 모르면 모두 남기고 점수는 붙이지 않는다.
        val other = ann("중간고사 성적", "1분반 평균 60.2, 최고 95\n2분반 평균 63.5, 최고 97", course = "공학수학")
        val all = ScoreStatsAuto.entries(other, listOf(graded(70.0)), 0)
        assertEquals(2, all.size)
        assertTrue(all.all { it.myScore == null })
    }

    @Test fun unclearAnnouncementIsNotSavedAndGetsButton() {
        val a = ann("중간고사 성적 공개", "<p>히스토그램은 아래 그림을 참고하세요.</p><img src='x.png'>")
        assertTrue(a.scoreStats)
        assertFalse(a.parsedStats!!.confident)
        assertTrue(ScoreStatsAuto.entries(a, emptyList(), 0).isEmpty())
        val n = NotificationPlanner.plan(Snapshot(now, emptyList(), listOf(a)), NotifyState(announcementBaselineDone = true), now, NotifySettings(), ZoneId.of("Asia/Seoul"))
            .notices.single()
        assertEquals(NotificationPlanner.SCORE_STATS_ACTION, n.prefillAction)
        assertTrue(n.prefill!!.endsWith("(공지 ID 99)"))
        assertEquals(NoticeTap.ETL, NoticeRouting.tap(n))
    }

    @Test fun parsedAnnouncementNoticeHasSavedLineAndNoButton() {
        val a = ann("중간고사 성적 공개", "중간고사 평균 62.4점, 표준편차 15.1, 최고 98점")
        val n = NotificationPlanner.plan(Snapshot(now, listOf(graded(80.0, st = null)), listOf(a)), NotifyState(announcementBaselineDone = true), now,
            NotifySettings(), ZoneId.of("Asia/Seoul")).notices.single()
        assertNull(n.prefillAction)
        assertTrue(n.bigText!!, n.bigText!!.startsWith("통계 저장됨 · 평균 62.4 · 내 점수 80 · "))
    }

    @Test fun ordinaryAnnouncementUnchanged() {
        val a = ann("과제 3 안내", "과제 3은 다음 주 금요일까지 제출하세요. 평균 소요 시간은 3시간이에요.")
        assertFalse(a.scoreStats)
        assertNull(a.parsedStats)
    }

    @Test fun historyKeepsPrefillButton() {
        val e = HistoryEntry(1, Channel.ANNOUNCEMENT.id, "t", "x", null, null, null, "통계 저장", "과목 ‘제목’ 공지 보고 … (공지 ID 42)")
        val back = NotifyHistoryLogic.fromJson(NotifyHistoryLogic.toJson(listOf(e))).single()
        assertEquals(e, back)
        assertEquals(42L, back.announcementId)
        assertNull(NotifyHistoryLogic.fromJson(NotifyHistoryLogic.toJson(listOf(e.copy(action = null, prefill = null)))).single().announcementId)
    }

    @Test fun examKeyMatchesRealNames() {
        assertEquals(ScoreStatsParser.examKey("[I2EP] Exam1 성적 공개")?.first, ScoreStatsParser.examKey("I2EP_Exam_1")?.first)
        assertNotNull(ScoreStatsAuto.mySection("2026-2 Analog Electronic Circuits (002)"))
    }
}
