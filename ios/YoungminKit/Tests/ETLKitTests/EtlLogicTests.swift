import Foundation
import Testing
@testable import ETLKit

struct EtlLogicTests {

    @Test func modelBoundaryRemovesCredentialsInNestedResultsAndReflectedErrors() throws {
        let secret = "synthetic-test-credential-123"
        let original = JSONObject().put("title", "과제").put("access_token", secret)
            .put("items", JSONArray().put(JSONObject().put("url", "https://files.example/a?X-Amz-Signature=signature-secret&download=1")))
            .put("body", "서버 응답: \(secret)")
        let output = EtlLogic.modelText(original, [secret])
        #expect(!output.contains(secret)); #expect(!output.contains("signature-secret"))
        #expect(try JSONObject(output).getString("title") == "과제")
        #expect(try original.getString("access_token") == secret)
        #expect(EtlLogic.modelText("오류: \(secret)", [secret]) == "오류: ***")
    }

    @Test func modelBoundaryRedactsBeforeJsonEscapesCredentials() throws {
        let secret = "synthetic\\\"credential"
        let output = try JSONObject(EtlLogic.modelText(JSONObject().put("body", "reflected \(secret)").put(secret, "value"), [secret]))
        #expect(try output.getString("body") == "reflected ***")
        #expect(!output.has(secret))
        #expect(try output.getString("***") == "value")
    }

    @Test func modelBoundaryRedactsSignedParametersInsideHtmlLinks() throws {
        for separator in ["&amp;", "&#38;", "&#038;", "&#x26;", "&#X026;", "&amp;amp;"] {
            let original = JSONObject().put("description",
                "<a href='https://files.example/a?download=1\(separator)X-Amz-Signature=SECRET'>강의 자료</a>")
            let result = try JSONObject(EtlLogic.modelText(original)).getString("description")
            #expect(!result.contains("SECRET"), "\(separator)")
            #expect(result.contains("X-Amz-Signature=***"))
            #expect(result.hasSuffix("'>강의 자료</a>"))
        }
        let entityValue = "<a href=\"https://myetl.snu.ac.kr/files/1?download=1&amp;verifier=&#x53;ECRET\">자료</a>"
        let result = EtlLogic.modelText(entityValue)
        #expect(!result.contains("ECRET"))
        #expect(!result.contains("&#x53;"))
        #expect(result.hasSuffix("\">자료</a>"))
    }

    @Test func modelBoundaryRedactsStructuredSignaturesAndTokenFields() throws {
        let input = JSONObject().put("nested", JSONObject()
            .put("verifier", "VERIFIER_SECRET").put("sessionToken", "SESSION_SECRET")
            .put("Signature", "SIGNATURE_SECRET").put("X-Amz-Credential", "CREDENTIAL_SECRET")
            .put("download_frd_token", "DOWNLOAD_SECRET").put("api-key", "API_SECRET")
            .put("title", "강의 자료"))
        let output = EtlLogic.modelText(input)
        #expect(!output.contains("SECRET"))
        #expect(try JSONObject(output).getJSONObject("nested").getString("title") == "강의 자료")
        #expect(try input.getJSONObject("nested").getString("verifier") == "VERIFIER_SECRET")
    }

    private let now = instant("2026-11-19T00:00:00Z")

    private func course(_ id: Int, _ name: String, _ term: String?, restricted: Bool = false) -> JSONObject {
        JSONObject()
            .put("id", id).put("name", name)
            .put("term", term.map { JSONObject().put("name", $0) } ?? JSONObject.NULL)
            .put("access_restricted_by_date", restricted)
    }

    @Test func termRankOrdersSeasons() {
        let r1 = EtlLogic.termRank("2026년 1학기")!
        let rs = EtlLogic.termRank("2026년 하계")!
        let r2 = EtlLogic.termRank("2026-2학기")!
        let rw = EtlLogic.termRank("2026년 동계")!
        #expect(r1 < rs && rs < r2 && r2 < rw)
        #expect(r1 == 20261)
        #expect(EtlLogic.termRank("기타") == nil)
        #expect(EtlLogic.termRank(nil) == nil)
    }

    @Test func currentCoursesKeepsLatestTermOnly() {
        let list = [
            course(1, "A", "2025년 2학기"),
            course(2, "B", "2026년 1학기"),
            course(3, "C", "2026년 1학기"),
            course(4, "D", "2026년 2학기", restricted: true), // 제외
        ]
        #expect(EtlLogic.currentCourses(list).map { $0.optInt("id") } == [2, 3])
    }

    @Test func currentCoursesKeepsAllWhenTermUnknown() {
        let list = [course(1, "A", nil), course(2, "B", "이름만")]
        #expect(EtlLogic.currentCourses(list).count == 2)
    }

    private func assign(_ types: [String], _ due: String?, _ sub: JSONObject? = nil) -> JSONObject {
        let o = JSONObject()
            .put("submission_types", JSONArray(types))
            .put("due_at", due ?? JSONObject.NULL)
        if let sub { o.put("submission", sub) }
        return o
    }

    @Test func submittedRules() {
        #expect(EtlLogic.isSubmitted(assign(["online_upload"], nil, JSONObject().put("workflow_state", "graded"))))
        #expect(EtlLogic.isSubmitted(assign(["online_upload"], nil, JSONObject().put("submitted_at", "2026-11-01T00:00:00Z"))))
        #expect(!EtlLogic.isSubmitted(assign(["online_upload"], nil, JSONObject().put("workflow_state", "unsubmitted").put("submitted_at", JSONObject.NULL))))
        #expect(!EtlLogic.isSubmitted(assign(["online_upload"], nil)))
    }

    @Test func onlineRules() {
        #expect(EtlLogic.isOnline(assign(["online_upload"], nil)))
        #expect(EtlLogic.isOnline(assign(["none", "online_text_entry"], nil)))
        #expect(!EtlLogic.isOnline(assign(["none", "on_paper", "not_graded"], nil)))
        #expect(!EtlLogic.isOnline(assign([], nil)))
    }

    @Test func missingRules() {
        let past = "2026-11-10T00:00:00Z"
        let future = "2026-11-30T00:00:00Z"
        #expect(EtlLogic.isMissing(assign(["online_upload"], past), now))
        #expect(!EtlLogic.isMissing(assign(["online_upload"], future), now))
        #expect(!EtlLogic.isMissing(assign(["on_paper"], past), now)) // 오프라인은 누락 아님
        #expect(EtlLogic.isMissing(assign(["online_upload"], future, JSONObject().put("missing", true)), now))
        // 제출했으면 마감이 지나도 누락이 아니다
        #expect(!EtlLogic.isMissing(assign(["online_upload"], past, JSONObject().put("workflow_state", "submitted")), now))
    }

    @Test func kstFormatting() {
        // 2026-11-19 14:59 UTC = 11-19 23:59 KST, 목요일
        #expect(EtlLogic.fmtKst(instant("2026-11-19T14:59:00Z")) == "11-19(목) 23:59")
        #expect(EtlLogic.fmtKst(nil) == "마감일 없음")
    }

    @Test func remainingText() {
        #expect(EtlLogic.remaining(now.plusSeconds(86400 + 2 * 3600 + 5), now) == "1일 2시간 남음")
        #expect(EtlLogic.remaining(now.plusSeconds(3 * 3600 + 5 * 60), now) == "3시간 5분 남음")
        #expect(EtlLogic.remaining(now.plusSeconds(-600), now) == "10분 지남")
        #expect(EtlLogic.remaining(nil, now) == "")
    }

    @Test func parseInstantHandlesOffsetsAndGarbage() {
        #expect(EtlLogic.parseInstant("2026-11-19T09:00:00+09:00") == instant("2026-11-19T00:00:00Z"))
        #expect(EtlLogic.parseInstant("어제") == nil)
        #expect(EtlLogic.parseInstant(nil) == nil)
        #expect(EtlLogic.parseInstant("") == nil)
        // LearningX 형식(소수 6자리)
        #expect(EtlLogic.parseInstant("2026-10-15T17:19:18.000000Z") == instant("2026-10-15T17:19:18Z"))
        #expect(EtlLogic.parseInstant("2026-02-30T00:00:00Z") == nil)
    }

    @Test func htmlToTextStripsTagsAndEntities() {
        let html = "<p>안녕&nbsp;하세요 &amp; <b>환영</b></p><script>alert(1)</script><ul><li>하나</li><li>둘</li></ul>"
        let t = EtlLogic.htmlToText(html)
        #expect(!t.contains("alert"))
        #expect(!t.contains("<"))
        #expect(t.contains("안녕 하세요 & 환영"))
        #expect(t.contains("- 하나"))
        #expect(EtlLogic.htmlToText(String(repeating: "가", count: 700), 600).hasSuffix("…"))
        #expect(EtlLogic.htmlToText(nil) == "")
    }

    @Test func safeNameBlocksTraversalAndControlChars() {
        #expect(EtlLogic.safeName("../../etc/passwd", "x") == "passwd")
        #expect(EtlLogic.safeName("a:b*c.pdf", "x") == "a_b_c.pdf")
        #expect(EtlLogic.safeName("...hidden", "x") == "hidden")
        #expect(EtlLogic.safeName("", "file-1") == "file-1")
        #expect(EtlLogic.safeName("..", "file-1") == "file-1")
        #expect(EtlLogic.safeName("a\u{0007}b", "x") == "a_b")
        #expect(EtlLogic.safeName(String(repeating: "a", count: 300), "x").count == 180)
    }

    @Test func fileIdParsing() throws {
        #expect(try EtlLogic.fileId("123") == 123)
        #expect(try EtlLogic.fileId("/courses/9/files/456") == 456)
        #expect(try EtlLogic.fileId("https://myetl.snu.ac.kr/courses/9/files/789/download?x=1") == 789)
        #expect(throws: EtlException.self) { try EtlLogic.fileId("abc") }
    }

    @Test func getPathFilter() {
        #expect(EtlLogic.isAllowedGetPath("/api/v1/users/self"))
        #expect(EtlLogic.isAllowedGetPath("/api/v1/courses?enrollment_state=active&include[]=term&per_page=100"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v2/users/self"))
        #expect(!EtlLogic.isAllowedGetPath("https://evil.example/api/v1/x"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/../../logout"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/x y"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/x\n"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/x#frag"))
    }

    // ---- 제출 판정 ----------------------------------------------------------

    private func a(_ types: [String], _ due: String?, _ ext: [String] = []) -> JSONObject {
        JSONObject()
            .put("submission_types", JSONArray(types)).put("due_at", due ?? JSONObject.NULL)
            .put("allowed_extensions", JSONArray(ext))
    }

    @Test func verifyOkWhenSubmittedOnTime() {
        let s = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-09T00:00:00Z").put("submission_type", "online_upload")
        let ev = EtlLogic.evaluateSubmission(a(["online_upload"], "2026-11-10T00:00:00Z"), s, now)
        #expect(ev.submitted)
        #expect(ev.problems.isEmpty)
    }

    @Test func verifyNoSubmissionOnlineIsProblem() {
        let ev = EtlLogic.evaluateSubmission(a(["online_upload"], "2026-11-10T00:00:00Z"), JSONObject().put("workflow_state", "unsubmitted"), now)
        #expect(!ev.submitted)
        #expect(ev.problems == ["제출 기록 없음 (마감 지남)"])
    }

    @Test func verifyOfflineAssignmentIsWarningNotProblem() {
        let ev = EtlLogic.evaluateSubmission(a(["on_paper"], nil), JSONObject(), now)
        #expect(ev.problems.isEmpty)
        #expect(ev.warnings.count == 1)
    }

    @Test func verifyLateAndMissingAndWrongType() {
        let late = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-12T00:00:00Z")
            .put("submission_type", "online_url")
        let ev = EtlLogic.evaluateSubmission(a(["online_upload"], "2026-11-10T00:00:00Z"), late, now)
        #expect(ev.problems.contains { $0.contains("마감 후 제출") })
        #expect(ev.problems.contains { $0.contains("online_url") })

        let flagged = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-09T00:00:00Z")
            .put("late", true).put("missing", true).put("excused", true)
        let ev2 = EtlLogic.evaluateSubmission(a(["online_upload"], "2026-11-10T00:00:00Z"), flagged, now)
        #expect(ev2.problems.contains("지각 제출로 표시됨"))
        #expect(ev2.problems.contains { $0.contains("누락") })
        #expect(ev2.warnings.contains { $0.contains("면제") })
        #expect(!ev2.problems.contains { $0.contains("마감 후 제출") }) // late가 우선
    }

    @Test func attachmentChecks() {
        let empty = JSONObject().put("display_name", "report.pdf").put("size", 0)
        #expect(EtlLogic.checkAttachment(empty, []).contains { $0.contains("0바이트") })
        let exe = JSONObject().put("display_name", "run.EXE").put("size", 10)
        #expect(EtlLogic.checkAttachment(exe, ["pdf", "docx"]).contains { $0.contains("허용되지 않은 확장자") })
        let ok = JSONObject().put("display_name", "Report.PDF").put("size", 10)
        #expect(EtlLogic.checkAttachment(ok, ["pdf"]).isEmpty)
        let noSize = JSONObject().put("display_name", "x.pdf")
        #expect(EtlLogic.checkAttachment(noSize, []).isEmpty)
    }

    // ---- 제출 직후 점검(SubmissionCheck) ------------------------------------

    @Test func submissionCheckSeparatesLateAndCountsFiles() {
        let asg = a(["online_upload"], "2026-11-10T00:00:00Z", ["pdf"])
        let s = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-12T00:00:00Z").put("submission_type", "online_upload")
            .put("attachments", JSONArray().put(JSONObject().put("display_name", "a.pdf").put("size", 0)))
        let r = SubmissionCheck.evaluate(asg, s, now)!
        #expect(r.late)
        #expect(r.problems.count == 1 && r.problems[0].contains("0바이트"))
        #expect(r.files == 1)
        #expect(SubmissionCheck.evaluate(asg, nil, now) == nil)
        let noKey = JSONObject().put("workflow_state", "submitted").put("submitted_at", "2026-11-09T00:00:00Z").put("submission_type", "online_upload")
        #expect(SubmissionCheck.evaluate(asg, noKey, now)?.attachmentsKnown == false)
        let live = SubmissionCheck.liveFrom(asg.put("name", "과제"), noKey.put("attachments", JSONArray()), now)
        #expect(live.problems == [SubmissionCheck.NO_ATTACHMENT])
        #expect(!live.ok)
    }
}
