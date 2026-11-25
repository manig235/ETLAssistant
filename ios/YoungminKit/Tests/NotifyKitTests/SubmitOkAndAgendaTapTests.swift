import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 정상 제출 알림과 내 마감·시험 알림을 누르면 내 일정으로 가는 경로.
@Suite struct SubmitOkAndAgendaTapTests {
    /// 2026-11-19(목) 12:00 KST
    let now = instant("2026-11-19T03:00:00Z")
    func h(_ x: Double) -> Date { now.h(x) }

    // ---- 정상 제출 알림 ---------------------------------------------------------

    func item(_ id: Int64, at: Date?? = .none, check: SubmitCheck? = SubmitCheck(late: false, problems: [], files: 2), cid: Int64 = 1) -> AssignmentItem {
        let t: Date? = at ?? h(-1)
        return AssignmentItem(cid, "자료구조", id, "HW\(id)", h(10), true, 10.0, nil, t != nil, false, nil, t, t == nil ? nil : check)
    }

    func snap(_ a: [AssignmentItem]) -> Snapshot { Snapshot(now, a, []) }

    func diff(_ prev: WatchState, _ a: AssignmentItem..., submits: Bool = true, submitOk: Bool = true,
              muted: @escaping (Int64) -> Bool = { _ in false }) -> (WatchState, [Notice]) {
        AssignmentWatch.diff(prev, snap(a), now, muted, changes: true, submits: submits, submitOk: submitOk)
    }

    /// 기준선(과제 1은 아직 미제출).
    func baseline() -> WatchState { diff(WatchState(), item(1, at: .some(nil)), item(2)).0 }

    @Test func okSubmissionGetsQuietNotice() {
        let ok = item(1, at: h(0))
        let ns = diff(baseline(), ok, item(2)).1
        #expect(ns.count == 1)
        let n = ns[0]
        #expect(n.channel == .SUBMIT_OK)
        #expect(n.channel.id == "submit_ok")
        #expect(n.title == "제출 확인됐어요")
        #expect(n.text == "자료구조 HW1 · 파일 2개 · 11/19(목) 12:00")
        #expect(n.tag == "submit:1:1:\(h(0).epochMilli)")
        #expect(n.firedKeys == [n.tag])
        #expect(n.url == "https://myetl.snu.ac.kr/courses/1/assignments/1")
        #expect(NoticeRouting.tap(n) == .ETL)
    }

    @Test func problemKeepsHighAlertWithSameKey() {
        let bad = item(1, at: h(0), check: SubmitCheck(late: false, problems: ["빈 파일(0바이트): a.pdf"], files: 1))
        let ns = diff(baseline(), bad, item(2)).1
        #expect(ns.count == 1)
        #expect(ns[0].channel == .SUBMIT)
        #expect(ns[0].tag == AssignmentWatch.submitKey(bad))
        #expect(ns[0].tag == AssignmentWatch.submitKey(item(1, at: h(0)))) // 정상 알림과 같은 키 공간
    }

    @Test func lateOnlyIsOkFlaggedLate() {
        let late = item(1, at: h(0), check: SubmitCheck(late: true, problems: [], files: 1))
        let ns = diff(baseline(), late, item(2)).1
        #expect(ns.count == 1)
        #expect(ns[0].channel == .SUBMIT_OK)
        #expect(ns[0].text.hasSuffix(" · 늦은 제출"))
        #expect(ns[0].text.contains("파일 1개"))
    }

    @Test func textSubmissionHasNoFileCount() {
        let text = item(1, at: h(0), check: SubmitCheck(late: false, problems: [], files: nil))
        let ns = diff(baseline(), text, item(2)).1
        #expect(ns.count == 1 && !ns[0].text.contains("파일"))
    }

    @Test func settingOffOrSubmitAlertsOffSendsNothing() {
        let ok = item(1, at: h(0))
        #expect(diff(baseline(), ok, item(2), submitOk: false).1.isEmpty)
        #expect(diff(baseline(), ok, item(2), submits: false, submitOk: true).1.isEmpty)
        // 꺼 둬도 기록은 남아 나중에 켜도 이 제출이 다시 오지 않는다
        let (s, _) = diff(baseline(), ok, item(2), submitOk: false)
        #expect(diff(s, ok, item(2)).1.isEmpty)
    }

    @Test func mutedCourseSendsNothing() {
        let (s, n) = diff(baseline(), item(1, at: h(0)), item(2), muted: { $0 == 1 })
        #expect(n.isEmpty)
        #expect(s.records["1:1"]!.submittedAt == h(0).epochMilli)
    }

    @Test func oneNoticePerSubmissionAndResubmitAlertsAgain() {
        let ok = item(1, at: h(0))
        let (s, n) = diff(baseline(), ok, item(2))
        #expect(n.count == 1)
        #expect(diff(s, ok, item(2)).1.isEmpty)
        var again = ok
        again.submittedAt = h(1)
        let n2 = diff(s, again, item(2)).1
        #expect(n2.count == 1)
        #expect(n2[0].channel == .SUBMIT_OK)
        #expect(n2[0].tag != n[0].tag)
    }

    @Test func baselineAndUnknownAttachmentsStaySilent() {
        #expect(diff(WatchState(), item(1, at: h(0))).1.isEmpty)
        let legacy = WatchState(true, ["1:1": WatchRecord("HW1", "자료구조", h(10).epochMilli, nil)])
        #expect(diff(legacy, item(1, at: h(0))).1.isEmpty)
        // 첨부 목록을 아직 못 봤으면 정상이라고 하지 않고 다음 확인으로 미룬다
        let unknown = item(1, at: h(0), check: SubmitCheck(late: false, problems: [], attachmentsKnown: false))
        let (s, n) = diff(baseline(), unknown, item(2))
        #expect(n.isEmpty)
        var known = unknown
        known.check = SubmitCheck(late: false, problems: [], files: 1)
        #expect(diff(s, known, item(2)).1.map(\.channel) == [.SUBMIT_OK])
    }

    @Test func evaluateCountsFilesAndCacheKeepsThem() {
        let a = JSONObject().put("id", 7).put("name", "HW").put("submission_types", JSONArray(["online_upload"])).put("due_at", "2026-11-20T00:00:00Z")
        let s = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-19T02:00:00Z").put("submission_type", "online_upload")
            .put("attachments", JSONArray().put(JSONObject().put("display_name", "a.pdf").put("size", 3)).put(JSONObject().put("display_name", "b.pdf").put("size", 4)))
        #expect(SubmissionCheck.evaluate(a, s, now)!.files == 2)
        let text = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-19T02:00:00Z").put("submission_type", "online_text_entry")
        #expect(SubmissionCheck.evaluate(a, text, now)!.files == nil)
        let it = item(1)
        let back = SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(CachedSnapshot(1, [it])))!.assignments[0]
        #expect(it.check == back.check)
    }

    @Test func settingsDefaultOn() {
        #expect(NotifySettings().submitOkAlerts)
    }

    // ---- 내 마감·시험 알림 → 내 일정 -----------------------------------------------

    func mine(_ id: Int64, _ due: Date, _ reminders: [Date] = []) -> MyDeadline { MyDeadline(id, "장학금 서류", due, reminders: reminders) }

    @Test func myReminderOpensAgendaWithItem() {
        let ns = MyDeadlines.reminderNotices([mine(5, h(3), [h(-1)])], now, [])
        #expect(ns.count == 1)
        #expect(NoticeRouting.tap(ns[0]) == .AGENDA)
        #expect(ns[0].mineItemId == 5)
    }

    @Test func examRemindersAndDoneOpenAgenda() {
        let exam = MyDeadline(6, "중간고사", h(2), reminders: [h(-1)], kind: .EXAM, end: h(4))
        let r = MyDeadlines.reminderNotices([exam], now, [])
        #expect(r.count == 1)
        #expect(NoticeRouting.tap(r[0]) == .AGENDA)
        #expect(r[0].mineItemId == 6)
        var done = exam
        done.due = h(-3)
        done.end = h(-1)
        let (notices, _) = MyDeadlines.examDoneNotices([done], now, [], enabled: true, quiet: false, casual: false)
        #expect(notices.count == 1)
        #expect(NoticeRouting.tap(notices[0]) == .AGENDA)
        #expect(notices[0].mineItemId == 6)
    }

    @Test func defaultDeadlineAlertForMineOpensAgendaButEtlStaysEtl() {
        let etl = AssignmentItem(10, "자료구조", 7, "HW3", h(2), true, nil, nil, false, false)
        let s = MyDeadlines.merge(Snapshot(now, [etl], []), [mine(1, h(50))], now)
        let plan = NotificationPlanner.plan(s, NotifyState(announcementBaselineDone: true), now, NotifySettings(), seoul)
        let mineN = plan.notices.first { $0.tag == "deadline:-1:1" }!
        #expect(NoticeRouting.tap(mineN) == .AGENDA)
        #expect(mineN.mineItemId == 1)
        let etlN = plan.notices.first { $0.tag == "deadline:10:7" }!
        #expect(NoticeRouting.tap(etlN) == .ETL)
        #expect(etlN.mineItemId == nil)
    }

    @Test func groupedRouting() {
        let settings = NotifySettings()
        let state = NotifyState(announcementBaselineDone: true)
        // 내 마감만 묶이면 내 일정
        let onlyMine = MyDeadlines.merge(Snapshot(now, [], []), [mine(1, h(2)), mine(2, h(2).secs(60))], now)
        let gs = NotificationPlanner.plan(onlyMine, state, now, settings, seoul).notices
        #expect(gs.count == 1)
        #expect(gs[0].tag.hasPrefix("deadline:wm"))
        #expect(NoticeRouting.tap(gs[0]) == .AGENDA)
        #expect(gs[0].mineItemId == nil)
        // eTL 과제가 섞이면 지금처럼 eTL
        let etl = AssignmentItem(10, "자료구조", 7, "HW3", h(2), true, nil, nil, false, false)
        let mixed = MyDeadlines.merge(Snapshot(now, [etl], []), [mine(1, h(2))], now)
        let ms = NotificationPlanner.plan(mixed, state, now, settings, seoul).notices
        #expect(ms.count == 1 && NoticeRouting.tap(ms[0]) == .ETL)
    }

    @Test func briefAndPrefillKeepChat() {
        let brief = BriefBuilder.build(Snapshot(now, [], []), now, NotifySettings(), seoul)
        #expect(NoticeRouting.tap(brief) == .CHAT)
        let prefill = Notice(.NEW_FILES, "t", "x", url: "https://myetl.snu.ac.kr/courses/1", tag: "f", prefill: "받아줘")
        #expect(NoticeRouting.tap(prefill) == .CHAT_PREFILL)
        // 안전하지 않은 주소는 eTL로 보내지 않는다
        #expect(NoticeRouting.tap(Notice(.DEADLINE, "t", "x", url: "https://evil.example/", tag: "m", agenda: true)) == .AGENDA)
    }

    @Test func findByItemIdResolvesRepeatOccurrence() {
        let weekly = MyDeadline(1_763_000_000_000, "실험 보고서", h(5), repeat: MyRepeat(.WEEKLY, weekdays: [.thursday]))
        let plain = mine(42, h(3))
        let list = [plain, weekly]
        #expect(MyDeadlines.findByItemId(list, 42) == plain)
        #expect(MyDeadlines.findByItemId(list, weekly.id) == weekly)
        let occ = MyDeadlines.active([weekly], now).first!
        #expect(MyDeadlines.findByItemId(list, MyDeadlines.itemId(occ)) == weekly)
        #expect(MyDeadlines.findByItemId(list, Int64.min) == nil)
        #expect(MyDeadlines.findByItemId(list, 7) == nil)
    }

    @Test func actionsOrderDraftPrefillEtl() {
        let n = Notice(.NEW_FILES, "t", "x", url: "https://myetl.snu.ac.kr/courses/1/files", tag: "f", prefill: "받아줘", prefillAction: "자료 받아줘")
        #expect(NotificationPoster.actions(n).map(\.label) == ["자료 받아줘", "eTL에서 열기"])
        #expect(NotificationPoster.importance(.SUBMIT_OK) == .low)
        #expect(NotificationPoster.importance(.BRIEF) == .normal)
        #expect(NotificationPoster.importance(.DEADLINE) == .high)
    }
}
