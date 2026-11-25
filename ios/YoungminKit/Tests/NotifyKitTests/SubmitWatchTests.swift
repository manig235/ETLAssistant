import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 제출 직후 점검: 제출 정보 판정, 새 제출 감지, 기준선, 늦음만이면 알리지 않음, 첨부 목록이 없을 때 미루기.
@Suite struct SubmitWatchTests {
    let now = instant("2026-11-19T03:00:00Z")
    func h(_ x: Double) -> Date { now.h(x) }

    // ---- 판정(SubmissionCheck, ETLKit) ------------------------------------------

    func assignment(_ types: [String] = ["online_upload"], ext: [String] = [], due: String = "2026-11-20T00:00:00Z") -> JSONObject {
        JSONObject().put("id", 7).put("name", "HW").put("submission_types", JSONArray(types)).put("allowed_extensions", JSONArray(ext)).put("due_at", due)
    }

    func sub(at: String = "2026-11-19T02:00:00Z", type: String = "online_upload", attachments: JSONArray? = JSONArray()) -> JSONObject {
        let o = JSONObject().put("workflow_state", "submitted").put("submitted_at", at).put("submission_type", type)
        if let attachments { o.put("attachments", attachments) }
        return o
    }

    func att(_ name: String, _ size: Int64) -> JSONObject { JSONObject().put("display_name", name).put("size", size) }

    @Test func okSubmissionHasNoProblems() {
        let c = SubmissionCheck.evaluate(assignment(ext: ["pdf"]), sub(attachments: JSONArray().put(att("report.pdf", 100))), now)!
        #expect(c.problems.isEmpty)
        #expect(!c.late)
        #expect(c.attachmentsKnown)
    }

    @Test func unsubmittedHasNoCheck() {
        #expect(SubmissionCheck.evaluate(assignment(), JSONObject().put("workflow_state", "unsubmitted"), now) == nil)
        #expect(SubmissionCheck.evaluate(assignment(), nil, now) == nil)
    }

    @Test func emptyFileWrongExtensionAndNoAttachment() {
        let c = SubmissionCheck.evaluate(assignment(ext: ["pdf"]), sub(attachments: JSONArray().put(att("a.pdf", 0)).put(att("b.hwp", 10))), now)!
        #expect(c.problems.contains { $0.contains("0바이트") })
        #expect(c.problems.contains { $0.contains("확장자") && $0.contains("b.hwp") })
        let none = SubmissionCheck.evaluate(assignment(), sub(attachments: JSONArray()), now)!
        #expect(none.problems == [SubmissionCheck.NO_ATTACHMENT])
    }

    @Test func wrongTypeIsProblemAndLateIsSeparate() {
        let c = SubmissionCheck.evaluate(assignment(due: "2026-11-19T01:00:00Z"), sub(type: "online_url", attachments: nil), now)!
        #expect(c.late)
        #expect(c.problems.count == 1 && c.problems[0].contains("online_url"))
        #expect(c.attachmentsKnown) // 업로드가 아니면 첨부 목록이 필요 없다
        let flagged = SubmissionCheck.evaluate(assignment(), sub().put("late", true).put("attachments", JSONArray().put(att("x.pdf", 5))), now)!
        #expect(flagged.late)
        #expect(flagged.problems.isEmpty)
    }

    @Test func uploadWithoutAttachmentsKeyIsUnknown() {
        let c = SubmissionCheck.evaluate(assignment(), sub(attachments: nil), now)!
        #expect(!c.attachmentsKnown)
        #expect(c.problems.isEmpty)
    }

    @Test func liveIncludesLateAndFiles() {
        let r = SubmissionCheck.liveFrom(assignment(due: "2026-11-19T01:00:00Z").put("html_url", "https://myetl.snu.ac.kr/courses/1/assignments/7"),
                                         sub(attachments: JSONArray().put(att("r.pdf", 3))), now)
        #expect(r.submitted)
        #expect(!r.ok)
        #expect(r.problems.contains { $0.contains("마감 후") })
        #expect(r.files == ["r.pdf"])
        let none = SubmissionCheck.liveFrom(assignment(), JSONObject().put("workflow_state", "unsubmitted"), now)
        #expect(!none.ok)
        #expect(none.problems.contains { $0.contains("제출 기록 없음") })
    }

    @Test func fetcherMapsSubmittedAtAndCheck() {
        let raw = assignment(ext: ["pdf"]).put("submission", sub(attachments: JSONArray().put(att("x.zip", 4))))
        let items = SnapshotFetcher.mapAssignments(1, "자료구조", [raw], now)
        #expect(items.count == 1)
        #expect(items[0].submittedAt == instant("2026-11-19T02:00:00Z"))
        #expect(items[0].check!.problems.contains { $0.contains("x.zip") })
    }

    @Test func cacheKeepsSubmitCheck() {
        let a = item(1, check: SubmitCheck(late: true, problems: ["빈 파일(0바이트): a.pdf"], attachmentsKnown: false))
        let back = SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(CachedSnapshot(1, [a])))!.assignments[0]
        #expect(a.submittedAt == back.submittedAt)
        #expect(a.check == back.check)
        // 예전 저장본(필드 없음)도 읽힌다
        var old = item(2)
        old.submittedAt = nil
        old.check = nil
        #expect(SnapshotCacheLogic.fromJson(SnapshotCacheLogic.toJson(CachedSnapshot(1, [old])))!.assignments[0].check == nil)
    }

    // ---- 감지(AssignmentWatch) --------------------------------------------------

    func item(_ id: Int64, at: Date?? = .none, check: SubmitCheck? = SubmitCheck(late: false, problems: []), cid: Int64 = 1) -> AssignmentItem {
        let t: Date? = at ?? h(-1)
        return AssignmentItem(cid, "자료구조", id, "HW\(id)", h(10), true, 10.0, nil, t != nil, false, nil, t, t == nil ? nil : check)
    }

    func snap(_ a: [AssignmentItem]) -> Snapshot { Snapshot(now, a, []) }

    func diff(_ prev: WatchState, _ a: AssignmentItem..., muted: @escaping (Int64) -> Bool = { _ in false }) -> (WatchState, [Notice]) {
        AssignmentWatch.diff(prev, snap(a), now, muted, changes: true, submits: true)
    }

    /// 기준선을 잡은 상태(과제 1은 아직 미제출).
    func baseline() -> WatchState { diff(WatchState(), item(1, at: .some(nil)), item(2)).0 }

    @Test func firstRunAndUpgradeAreBaseline() {
        let (s, n) = diff(WatchState(), item(1, check: SubmitCheck(late: false, problems: ["빈 파일"])))
        #expect(n.isEmpty)
        #expect(s.submitInitialized)
        // 업데이트 직후: 예전 기록(제출 시각 없음)은 기준선만
        let legacy = WatchState(true, ["1:1": WatchRecord("HW1", "자료구조", h(10).epochMilli, nil)])
        let (s2, n2) = diff(legacy, item(1, check: SubmitCheck(late: false, problems: ["빈 파일"])))
        #expect(n2.isEmpty)
        #expect(s2.records["1:1"]!.submittedAt == h(-1).epochMilli)
    }

    @Test func newSubmissionWithProblemAlertsOnce() {
        let bad = item(1, at: h(0), check: SubmitCheck(late: false, problems: ["빈 파일(0바이트): a.pdf"]))
        let (s, n) = diff(baseline(), bad, item(2))
        #expect(n.count == 1)
        let notice = n[0]
        #expect(notice.channel == .SUBMIT)
        #expect(notice.tag == "submit:1:1:\(h(0).epochMilli)")
        #expect(notice.firedKeys == [notice.tag])
        #expect(notice.text.contains("0바이트"))
        #expect(diff(s, bad, item(2)).1.isEmpty)
        // 다시 제출(시각 바뀜)하면 다시 판정한다
        var again = bad
        again.submittedAt = h(1)
        #expect(diff(s, again, item(2)).1.count == 1)
    }

    @Test func okOrLateOnlySubmissionIsSilent() {
        #expect(diff(baseline(), item(1, at: h(0)), item(2)).1.isEmpty)
        #expect(diff(baseline(), item(1, at: h(0), check: SubmitCheck(late: true, problems: [])), item(2)).1.isEmpty)
    }

    @Test func unknownAttachmentsDeferUntilKnown() {
        let unknown = item(1, at: h(0), check: SubmitCheck(late: false, problems: [], attachmentsKnown: false))
        let base = baseline()
        #expect(AssignmentWatch.isNewSubmission(base, unknown))
        let (s, n) = diff(base, unknown, item(2))
        #expect(n.isEmpty)
        #expect(s.records["1:1"]!.submittedAt == nil) // 기록하지 않아 다음에 다시 본다
        #expect(AssignmentWatch.isNewSubmission(s, unknown))
        var known = unknown
        known.check = SubmitCheck(late: false, problems: [SubmissionCheck.NO_ATTACHMENT])
        #expect(diff(s, known, item(2)).1.count == 1)
    }

    @Test func mutedCourseRecordsWithoutAlert() {
        let bad = item(1, at: h(0), check: SubmitCheck(late: false, problems: ["빈 파일"]))
        let (s, n) = diff(baseline(), bad, item(2), muted: { $0 == 1 })
        #expect(n.isEmpty)
        #expect(s.records["1:1"]!.submittedAt == h(0).epochMilli)
    }

    @Test func submitsOffStillRecords() {
        let bad = item(1, at: h(0), check: SubmitCheck(late: false, problems: ["빈 파일"]))
        let (s, n) = AssignmentWatch.diff(baseline(), snap([bad, item(2)]), now, changes: true, submits: false)
        #expect(n.isEmpty)
        #expect(s.records["1:1"]!.submittedAt == h(0).epochMilli)
    }

    @Test func changesOffSuppressesNewAssignmentButKeepsSubmit() {
        let bad = item(1, at: h(0), check: SubmitCheck(late: false, problems: ["빈 파일"]))
        let (_, n) = AssignmentWatch.diff(baseline(), snap([bad, item(2), item(3, at: .some(nil))]), now, changes: false, submits: true)
        #expect(n.map(\.channel) == [.SUBMIT])
    }

    @Test func cachedItemsWithoutCheckKeepOldRecord() {
        let base = baseline()
        var cached = item(2, at: h(0))
        cached.check = nil
        let (s, n) = diff(base, item(1, at: .some(nil)), cached)
        #expect(n.isEmpty)
        #expect(s.records["1:2"]!.submittedAt == h(-1).epochMilli)
    }

    @Test func watchStateRoundTrip() {
        let s = baseline()
        #expect(WatchState.fromJson(s.toJson()) == s)
    }

    // ---- 내 일정 -------------------------------------------------------------

    @Test func agendaShowsLateAndKeepsProblemRowsVisible() {
        let late = item(1, at: h(0), check: SubmitCheck(late: true, problems: []))
        let bad = item(2, at: h(0), check: SubmitCheck(late: false, problems: ["빈 파일"]))
        let fine = item(3, at: h(0))
        let rows = Agenda.build([late, bad, fine], [], now, seoul, hideDone: true).upcoming.flatMap(\.rows)
        #expect(rows.map(\.title) == ["HW2"])
        #expect(rows[0].subtitle.hasSuffix("제출함 · 확인 필요"))
        let all = Agenda.build([late, bad, fine], [], now, seoul, hideDone: false).upcoming.flatMap(\.rows)
        #expect(all.first { $0.title == "HW1" }!.subtitle.hasSuffix("제출함(늦음)"))
        #expect(all.first { $0.title == "HW3" }!.subtitle.hasSuffix("· 제출함"))
        #expect(all.first { $0.title == "HW3" }!.assignment == fine)
    }
}
