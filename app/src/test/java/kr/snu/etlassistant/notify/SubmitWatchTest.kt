package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.SubmissionCheck
import kr.snu.etlassistant.etl.SubmitCheck
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 제출 직후 점검: 제출 정보 판정, 새 제출 감지, 기준선, 늦음만이면 알리지 않음, 첨부 목록이 없을 때 미루기. */
class SubmitWatchTest {
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z")
    private fun h(x: Long) = now.plus(Duration.ofHours(x))

    // ---- 판정(SubmissionCheck) ------------------------------------------------

    private fun assignment(types: List<String> = listOf("online_upload"), ext: List<String> = emptyList(), due: String = "2026-11-20T00:00:00Z") =
        JSONObject().put("id", 7).put("name", "HW").put("submission_types", JSONArray(types)).put("allowed_extensions", JSONArray(ext)).put("due_at", due)

    private fun sub(at: String = "2026-11-19T02:00:00Z", type: String = "online_upload", attachments: JSONArray? = JSONArray()) =
        JSONObject().put("workflow_state", "submitted").put("submitted_at", at).put("submission_type", type)
            .also { if (attachments != null) it.put("attachments", attachments) }

    private fun att(name: String, size: Long) = JSONObject().put("display_name", name).put("size", size)

    @Test fun okSubmissionHasNoProblems() {
        val c = SubmissionCheck.evaluate(assignment(ext = listOf("pdf")), sub(attachments = JSONArray().put(att("report.pdf", 100))), now)!!
        assertTrue(c.problems.isEmpty())
        assertFalse(c.late)
        assertTrue(c.attachmentsKnown)
    }

    @Test fun unsubmittedHasNoCheck() {
        assertNull(SubmissionCheck.evaluate(assignment(), JSONObject().put("workflow_state", "unsubmitted"), now))
        assertNull(SubmissionCheck.evaluate(assignment(), null, now))
    }

    @Test fun emptyFileWrongExtensionAndNoAttachment() {
        val c = SubmissionCheck.evaluate(assignment(ext = listOf("pdf")),
            sub(attachments = JSONArray().put(att("a.pdf", 0)).put(att("b.hwp", 10))), now)!!
        assertTrue(c.problems.any { "0바이트" in it })
        assertTrue(c.problems.any { "확장자" in it && "b.hwp" in it })
        val none = SubmissionCheck.evaluate(assignment(), sub(attachments = JSONArray()), now)!!
        assertEquals(listOf(SubmissionCheck.NO_ATTACHMENT), none.problems)
    }

    @Test fun wrongTypeIsProblemAndLateIsSeparate() {
        val c = SubmissionCheck.evaluate(assignment(due = "2026-11-19T01:00:00Z"), sub(type = "online_url", attachments = null), now)!!
        assertTrue(c.late)
        assertTrue(c.problems.single().contains("online_url"))
        assertTrue(c.attachmentsKnown) // 업로드가 아니면 첨부 목록이 필요 없다
        val flagged = SubmissionCheck.evaluate(assignment(), sub().put("late", true).put("attachments", JSONArray().put(att("x.pdf", 5))), now)!!
        assertTrue(flagged.late)
        assertTrue(flagged.problems.isEmpty())
    }

    @Test fun uploadWithoutAttachmentsKeyIsUnknown() {
        val c = SubmissionCheck.evaluate(assignment(), sub(attachments = null), now)!!
        assertFalse(c.attachmentsKnown)
        assertTrue(c.problems.isEmpty())
    }

    @Test fun liveIncludesLateAndFiles() {
        val r = SubmissionCheck.liveFrom(assignment(due = "2026-11-19T01:00:00Z").put("html_url", "https://myetl.snu.ac.kr/courses/1/assignments/7"),
            sub(attachments = JSONArray().put(att("r.pdf", 3))), now)
        assertTrue(r.submitted)
        assertFalse(r.ok)
        assertTrue(r.problems.any { "마감 후" in it })
        assertEquals(listOf("r.pdf"), r.files)
        val none = SubmissionCheck.liveFrom(assignment(), JSONObject().put("workflow_state", "unsubmitted"), now)
        assertFalse(none.ok)
        assertTrue(none.problems.any { "제출 기록 없음" in it })
    }

    @Test fun fetcherMapsSubmittedAtAndCheck() {
        val raw = assignment(ext = listOf("pdf")).put("submission", sub(attachments = JSONArray().put(att("x.zip", 4))))
        val item = SnapshotFetcher.mapAssignments(1, "자료구조", listOf(raw), now).single()
        assertEquals(Instant.parse("2026-11-19T02:00:00Z"), item.submittedAt)
        assertTrue(item.check!!.problems.any { "x.zip" in it })
    }

    @Test fun cacheKeepsSubmitCheck() {
        val a = item(1, check = SubmitCheck(true, listOf("빈 파일(0바이트): a.pdf"), false))
        val back = SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(CachedSnapshot(1L, listOf(a))))!!.assignments.single()
        assertEquals(a.submittedAt, back.submittedAt)
        assertEquals(a.check, back.check)
        // 예전 저장본(필드 없음)도 읽힌다
        val old = item(2).copy(submittedAt = null, check = null)
        assertNull(SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(CachedSnapshot(1L, listOf(old))))!!.assignments.single().check)
    }

    // ---- 감지(AssignmentWatch) --------------------------------------------------

    private fun item(id: Long, at: Instant? = h(-1), check: SubmitCheck? = SubmitCheck(false, emptyList()), cid: Long = 1) =
        AssignmentItem(cid, "자료구조", id, "HW$id", h(10), true, 10.0, null, at != null, false, null, at, if (at == null) null else check)

    private fun snap(vararg a: AssignmentItem) = Snapshot(now, a.toList(), emptyList())

    private fun diff(prev: WatchState, vararg a: AssignmentItem, muted: (Long) -> Boolean = { false }) =
        AssignmentWatch.diff(prev, snap(*a), now, muted, changes = true, submits = true)

    /** 기준선을 잡은 상태(과제 1은 아직 미제출). */
    private fun baseline(): WatchState = diff(WatchState(), item(1, at = null), item(2)).first

    @Test fun firstRunAndUpgradeAreBaseline() {
        val (s, n) = diff(WatchState(), item(1, check = SubmitCheck(false, listOf("빈 파일"))))
        assertTrue(n.isEmpty())
        assertTrue(s.submitInitialized)
        // 업데이트 직후: 예전 기록(제출 시각 없음)은 기준선만
        val legacy = WatchState(true, mapOf("1:1" to WatchRecord("HW1", "자료구조", h(10).toEpochMilli(), null)))
        val (s2, n2) = diff(legacy, item(1, check = SubmitCheck(false, listOf("빈 파일"))))
        assertTrue(n2.isEmpty())
        assertEquals(h(-1).toEpochMilli(), s2.records["1:1"]!!.submittedAt)
    }

    @Test fun newSubmissionWithProblemAlertsOnce() {
        val bad = item(1, at = h(0), check = SubmitCheck(false, listOf("빈 파일(0바이트): a.pdf")))
        val (s, n) = diff(baseline(), bad, item(2))
        val notice = n.single()
        assertEquals(Channel.SUBMIT, notice.channel)
        assertEquals("submit:1:1:${h(0).toEpochMilli()}", notice.tag)
        assertEquals(listOf(notice.tag), notice.firedKeys)
        assertTrue(notice.text.contains("0바이트"))
        assertTrue(diff(s, bad, item(2)).second.isEmpty())
        // 다시 제출(시각 바뀜)하면 다시 판정한다
        val again = bad.copy(submittedAt = h(1))
        assertEquals(1, diff(s, again, item(2)).second.size)
    }

    @Test fun okOrLateOnlySubmissionIsSilent() {
        assertTrue(diff(baseline(), item(1, at = h(0)), item(2)).second.isEmpty())
        assertTrue(diff(baseline(), item(1, at = h(0), check = SubmitCheck(true, emptyList())), item(2)).second.isEmpty())
    }

    @Test fun unknownAttachmentsDeferUntilKnown() {
        val unknown = item(1, at = h(0), check = SubmitCheck(false, emptyList(), attachmentsKnown = false))
        val base = baseline()
        assertTrue(AssignmentWatch.isNewSubmission(base, unknown))
        val (s, n) = diff(base, unknown, item(2))
        assertTrue(n.isEmpty())
        assertNull(s.records["1:1"]!!.submittedAt) // 기록하지 않아 다음에 다시 본다
        assertTrue(AssignmentWatch.isNewSubmission(s, unknown))
        val known = unknown.copy(check = SubmitCheck(false, listOf(SubmissionCheck.NO_ATTACHMENT)))
        assertEquals(1, diff(s, known, item(2)).second.size)
    }

    @Test fun mutedCourseRecordsWithoutAlert() {
        val bad = item(1, at = h(0), check = SubmitCheck(false, listOf("빈 파일")))
        val (s, n) = diff(baseline(), bad, item(2), muted = { it == 1L })
        assertTrue(n.isEmpty())
        assertEquals(h(0).toEpochMilli(), s.records["1:1"]!!.submittedAt)
    }

    @Test fun submitsOffStillRecords() {
        val bad = item(1, at = h(0), check = SubmitCheck(false, listOf("빈 파일")))
        val (s, n) = AssignmentWatch.diff(baseline(), snap(bad, item(2)), now, changes = true, submits = false)
        assertTrue(n.isEmpty())
        assertEquals(h(0).toEpochMilli(), s.records["1:1"]!!.submittedAt)
    }

    @Test fun changesOffSuppressesNewAssignmentButKeepsSubmit() {
        val bad = item(1, at = h(0), check = SubmitCheck(false, listOf("빈 파일")))
        val (_, n) = AssignmentWatch.diff(baseline(), snap(bad, item(2), item(3, at = null)), now, changes = false, submits = true)
        assertEquals(listOf(Channel.SUBMIT), n.map { it.channel })
    }

    @Test fun cachedItemsWithoutCheckKeepOldRecord() {
        val base = baseline()
        val cached = item(2, at = h(0)).copy(check = null)
        val (s, n) = diff(base, item(1, at = null), cached)
        assertTrue(n.isEmpty())
        assertEquals(h(-1).toEpochMilli(), s.records["1:2"]!!.submittedAt)
    }

    @Test fun watchStateRoundTrip() {
        val s = baseline()
        assertEquals(s, WatchState.fromJson(s.toJson()))
    }

    // ---- 내 일정 -------------------------------------------------------------

    @Test fun agendaShowsLateAndKeepsProblemRowsVisible() {
        val zone = ZoneId.of("Asia/Seoul")
        val late = item(1, at = h(0), check = SubmitCheck(true, emptyList()))
        val bad = item(2, at = h(0), check = SubmitCheck(false, listOf("빈 파일")))
        val fine = item(3, at = h(0))
        val rows = Agenda.build(listOf(late, bad, fine), emptyList(), now, zone, hideDone = true).upcoming.flatMap { it.rows }
        assertEquals(listOf("HW2"), rows.map { it.title })
        assertTrue(rows.single().subtitle.endsWith("제출함 · 확인 필요"))
        val all = Agenda.build(listOf(late, bad, fine), emptyList(), now, zone, hideDone = false).upcoming.flatMap { it.rows }
        assertTrue(all.first { it.title == "HW1" }.subtitle.endsWith("제출함(늦음)"))
        assertTrue(all.first { it.title == "HW3" }.subtitle.endsWith("· 제출함"))
        assertEquals(fine, all.first { it.title == "HW3" }.assignment)
    }
}
