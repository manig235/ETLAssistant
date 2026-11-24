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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 정상 제출 알림(2026-11-24)과 내 마감·시험 알림을 누르면 내 일정으로 가는 경로. */
class SubmitOkAndAgendaTapTest {
    /** 2026-11-19(목) 12:00 KST */
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z")
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun h(x: Long) = now.plus(Duration.ofHours(x))

    // ---- 정상 제출 알림 ---------------------------------------------------------

    private fun item(id: Long, at: Instant? = h(-1), check: SubmitCheck? = SubmitCheck(false, emptyList(), files = 2), cid: Long = 1) =
        AssignmentItem(cid, "자료구조", id, "HW$id", h(10), true, 10.0, null, at != null, false, null, at, if (at == null) null else check)

    private fun snap(vararg a: AssignmentItem) = Snapshot(now, a.toList(), emptyList())

    private fun diff(prev: WatchState, vararg a: AssignmentItem, submits: Boolean = true, submitOk: Boolean = true, muted: (Long) -> Boolean = { false }) =
        AssignmentWatch.diff(prev, snap(*a), now, muted, changes = true, submits = submits, submitOk = submitOk)

    /** 기준선(과제 1은 아직 미제출). */
    private fun baseline(): WatchState = diff(WatchState(), item(1, at = null), item(2)).first

    @Test fun okSubmissionGetsQuietNotice() {
        val ok = item(1, at = h(0))
        val n = diff(baseline(), ok, item(2)).second.single()
        assertEquals(Channel.SUBMIT_OK, n.channel)
        assertEquals("submit_ok", n.channel.id)
        assertEquals("제출 확인됐어요", n.title)
        assertEquals("자료구조 HW1 · 파일 2개 · 11/19(목) 12:00", n.text)
        assertEquals("submit:1:1:${h(0).toEpochMilli()}", n.tag)
        assertEquals(listOf(n.tag), n.firedKeys)
        assertEquals("https://myetl.snu.ac.kr/courses/1/assignments/1", n.url)
        assertEquals(NoticeTap.ETL, NoticeRouting.tap(n))
    }

    @Test fun problemKeepsHighAlertWithSameKey() {
        val bad = item(1, at = h(0), check = SubmitCheck(false, listOf("빈 파일(0바이트): a.pdf"), files = 1))
        val n = diff(baseline(), bad, item(2)).second.single()
        assertEquals(Channel.SUBMIT, n.channel)
        assertEquals(AssignmentWatch.submitKey(bad), n.tag)
        assertEquals(AssignmentWatch.submitKey(item(1, at = h(0))), n.tag) // 정상 알림과 같은 키 공간
    }

    @Test fun lateOnlyIsOkFlaggedLate() {
        val late = item(1, at = h(0), check = SubmitCheck(true, emptyList(), files = 1))
        val n = diff(baseline(), late, item(2)).second.single()
        assertEquals(Channel.SUBMIT_OK, n.channel)
        assertTrue(n.text.endsWith(" · 늦은 제출"))
        assertTrue("파일 1개" in n.text)
    }

    @Test fun textSubmissionHasNoFileCount() {
        val text = item(1, at = h(0), check = SubmitCheck(false, emptyList(), files = null))
        assertFalse("파일" in diff(baseline(), text, item(2)).second.single().text)
    }

    @Test fun settingOffOrSubmitAlertsOffSendsNothing() {
        val ok = item(1, at = h(0))
        assertTrue(diff(baseline(), ok, item(2), submitOk = false).second.isEmpty())
        assertTrue(diff(baseline(), ok, item(2), submits = false, submitOk = true).second.isEmpty())
        // 꺼 둬도 기록은 남아 나중에 켜도 이 제출이 다시 오지 않는다
        val (s, _) = diff(baseline(), ok, item(2), submitOk = false)
        assertTrue(diff(s, ok, item(2)).second.isEmpty())
    }

    @Test fun mutedCourseSendsNothing() {
        val (s, n) = diff(baseline(), item(1, at = h(0)), item(2), muted = { it == 1L })
        assertTrue(n.isEmpty())
        assertEquals(h(0).toEpochMilli(), s.records["1:1"]!!.submittedAt)
    }

    @Test fun oneNoticePerSubmissionAndResubmitAlertsAgain() {
        val ok = item(1, at = h(0))
        val (s, n) = diff(baseline(), ok, item(2))
        assertEquals(1, n.size)
        assertTrue(diff(s, ok, item(2)).second.isEmpty())
        val again = ok.copy(submittedAt = h(1))
        val n2 = diff(s, again, item(2)).second.single()
        assertEquals(Channel.SUBMIT_OK, n2.channel)
        assertTrue(n2.tag != n.single().tag)
    }

    @Test fun baselineAndUnknownAttachmentsStaySilent() {
        assertTrue(diff(WatchState(), item(1, at = h(0))).second.isEmpty())
        val legacy = WatchState(true, mapOf("1:1" to WatchRecord("HW1", "자료구조", h(10).toEpochMilli(), null)))
        assertTrue(diff(legacy, item(1, at = h(0))).second.isEmpty())
        // 첨부 목록을 아직 못 봤으면 정상이라고 하지 않고 다음 확인으로 미룬다
        val unknown = item(1, at = h(0), check = SubmitCheck(false, emptyList(), attachmentsKnown = false))
        val (s, n) = diff(baseline(), unknown, item(2))
        assertTrue(n.isEmpty())
        assertEquals(Channel.SUBMIT_OK, diff(s, unknown.copy(check = SubmitCheck(false, emptyList(), files = 1)), item(2)).second.single().channel)
    }

    @Test fun evaluateCountsFilesAndCacheKeepsThem() {
        val a = JSONObject().put("id", 7).put("name", "HW").put("submission_types", JSONArray(listOf("online_upload"))).put("due_at", "2026-11-20T00:00:00Z")
        val s = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-19T02:00:00Z").put("submission_type", "online_upload")
            .put("attachments", JSONArray().put(JSONObject().put("display_name", "a.pdf").put("size", 3)).put(JSONObject().put("display_name", "b.pdf").put("size", 4)))
        assertEquals(2, SubmissionCheck.evaluate(a, s, now)!!.files)
        val text = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-19T02:00:00Z").put("submission_type", "online_text_entry")
        assertNull(SubmissionCheck.evaluate(a, text, now)!!.files)
        val it = item(1)
        val back = SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(CachedSnapshot(1L, listOf(it))))!!.assignments.single()
        assertEquals(it.check, back.check)
    }

    @Test fun settingsDefaultOn() {
        assertTrue(NotifySettings().submitOkAlerts)
    }

    // ---- 내 마감·시험 알림 → 내 일정 -----------------------------------------------

    private fun mine(id: Long, due: Instant, reminders: List<Instant> = emptyList()) = MyDeadline(id, "장학금 서류", due, reminders = reminders)

    @Test fun myReminderOpensAgendaWithItem() {
        val d = mine(5, h(3), listOf(h(-1)))
        val n = MyDeadlines.reminderNotices(listOf(d), now, emptySet()).single()
        assertEquals(NoticeTap.AGENDA, NoticeRouting.tap(n))
        assertEquals(5L, n.mineItemId)
    }

    @Test fun examRemindersAndDoneOpenAgenda() {
        val exam = MyDeadline(6, "중간고사", h(2), kind = MyKind.EXAM, end = h(4), reminders = listOf(h(-1)))
        val r = MyDeadlines.reminderNotices(listOf(exam), now, emptySet()).single()
        assertEquals(NoticeTap.AGENDA, NoticeRouting.tap(r))
        assertEquals(6L, r.mineItemId)
        val done = exam.copy(due = h(-3), end = h(-1))
        val (notices, _) = MyDeadlines.examDoneNotices(listOf(done), now, emptySet(), enabled = true, quiet = false, casual = false)
        assertEquals(NoticeTap.AGENDA, NoticeRouting.tap(notices.single()))
        assertEquals(6L, notices.single().mineItemId)
    }

    @Test fun defaultDeadlineAlertForMineOpensAgendaButEtlStaysEtl() {
        val etl = AssignmentItem(10, "자료구조", 7, "HW3", h(2), true, null, null, false, false)
        val snap = MyDeadlines.merge(Snapshot(now, listOf(etl), emptyList()), listOf(mine(1, h(50))), now)
        val plan = NotificationPlanner.plan(snap, NotifyState(announcementBaselineDone = true), now, NotifySettings(), seoul)
        val mineN = plan.notices.single { it.tag == "deadline:-1:1" }
        assertEquals(NoticeTap.AGENDA, NoticeRouting.tap(mineN))
        assertEquals(1L, mineN.mineItemId)
        val etlN = plan.notices.single { it.tag == "deadline:10:7" }
        assertEquals(NoticeTap.ETL, NoticeRouting.tap(etlN))
        assertNull(etlN.mineItemId)
    }

    @Test fun groupedRouting() {
        val settings = NotifySettings()
        val state = NotifyState(announcementBaselineDone = true)
        // 내 마감만 묶이면 내 일정
        val onlyMine = MyDeadlines.merge(Snapshot(now, emptyList(), emptyList()), listOf(mine(1, h(2)), mine(2, h(2).plusSeconds(60))), now)
        val g = NotificationPlanner.plan(onlyMine, state, now, settings, seoul).notices.single()
        assertTrue(g.tag.startsWith("deadline:wm"))
        assertEquals(NoticeTap.AGENDA, NoticeRouting.tap(g))
        assertNull(g.mineItemId)
        // eTL 과제가 섞이면 지금처럼 eTL
        val etl = AssignmentItem(10, "자료구조", 7, "HW3", h(2), true, null, null, false, false)
        val mixed = MyDeadlines.merge(Snapshot(now, listOf(etl), emptyList()), listOf(mine(1, h(2))), now)
        assertEquals(NoticeTap.ETL, NoticeRouting.tap(NotificationPlanner.plan(mixed, state, now, settings, seoul).notices.single()))
    }

    @Test fun briefAndPrefillKeepChat() {
        val brief = BriefBuilder.build(Snapshot(now, emptyList(), emptyList()), now, NotifySettings(), seoul)
        assertEquals(NoticeTap.CHAT, NoticeRouting.tap(brief))
        val prefill = Notice(Channel.NEW_FILES, "t", "x", tag = "f", prefill = "받아줘", url = "https://myetl.snu.ac.kr/courses/1")
        assertEquals(NoticeTap.CHAT_PREFILL, NoticeRouting.tap(prefill))
        // 안전하지 않은 주소는 eTL로 보내지 않는다
        assertEquals(NoticeTap.AGENDA, NoticeRouting.tap(Notice(Channel.DEADLINE, "t", "x", url = "https://evil.example/", tag = "m", agenda = true)))
    }

    @Test fun findByItemIdResolvesRepeatOccurrence() {
        val weekly = MyDeadline(1_763_000_000_000, "실험 보고서", h(5), repeat = MyRepeat(MyRepeat.Freq.WEEKLY, setOf(DayOfWeek.THURSDAY)))
        val plain = mine(42, h(3))
        val list = listOf(plain, weekly)
        assertEquals(plain, MyDeadlines.findByItemId(list, 42))
        assertEquals(weekly, MyDeadlines.findByItemId(list, weekly.id))
        val occ = MyDeadlines.active(listOf(weekly), now).first()
        assertEquals(weekly, MyDeadlines.findByItemId(list, MyDeadlines.itemId(occ)))
        assertNull(MyDeadlines.findByItemId(list, Long.MIN_VALUE))
        assertNull(MyDeadlines.findByItemId(list, 7))
    }
}
