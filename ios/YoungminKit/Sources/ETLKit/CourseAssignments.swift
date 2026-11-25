import Foundation

/// 현재 학기 강의와 강의별 과제 조회. 비서 도구(etl_digest, etl_assignments)와 알림(SnapshotFetcher)이 함께 쓴다.
/// 동시 요청 수는 [EtlClient]의 공용 제한을 따른다.
public enum CourseAssignments {

    public static let COURSES_PATH = "/api/v1/courses?enrollment_state=active&include[]=total_scores&include[]=term&per_page=100"

    public static func assignmentsPath(_ courseId: Int64) -> String {
        "/api/v1/courses/\(courseId)/assignments?include[]=submission&order_by=due_at&per_page=100"
    }

    /// 강의 하나의 결과. 조회에 실패하면 [assignments]는 비고 [error]에 짧은 이유가 들어간다.
    public struct CourseResult: @unchecked Sendable {
        public let course: JSONObject
        public let assignments: [JSONObject]
        public let error: String?
        /// 연결 끊김·시간 초과처럼 다시 시도해 볼 만한 실패인가.
        public let retryable: Bool

        public init(_ course: JSONObject, _ assignments: [JSONObject], _ error: String?, retryable: Bool = false) {
            self.course = course
            self.assignments = assignments
            self.error = error
            self.retryable = retryable
        }

        public var courseId: Int64 { course.optLong("id") }
        public var courseName: String { course.str("name") ?? "" }
    }

    public static func currentCourses(_ client: EtlClient) async throws -> [JSONObject] {
        EtlLogic.currentCourses(try await client.getAll(COURSES_PATH))
    }

    /// 강의별 과제를 병렬로 모은다. [EtlAuthException](토큰 거부)은 그대로 던지고, 그 밖의 실패는 그 강의만 [CourseResult.error]로 남긴다.
    /// 결과 순서는 [courses] 순서와 같다.
    public static func fetch(_ client: EtlClient, _ courses: [JSONObject]) async throws -> [CourseResult] {
        if courses.isEmpty { return [] }
        let ids = courses.map { $0.optLong("id") }
        var outcomes = [Result<[JSONObject], Error>?](repeating: nil, count: courses.count)
        await withTaskGroup(of: (Int, Result<[JSONObject], Error>).self) { group in
            for (i, id) in ids.enumerated() {
                group.addTask {
                    do { return (i, .success(try await client.getAll(assignmentsPath(id)))) } catch { return (i, .failure(error)) }
                }
            }
            for await (i, r) in group { outcomes[i] = r }
        }
        var out: [CourseResult] = []
        for (i, course) in courses.enumerated() {
            switch outcomes[i]! {
            case .success(let list):
                out.append(CourseResult(course, list, nil))
            case .failure(let e):
                if let auth = e as? EtlAuthException { throw auth }
                if e is CancellationError { throw e }
                if let ee = e as? EtlException {
                    out.append(CourseResult(course, [], ee.message.isEmpty ? "과제 조회 실패" : ee.message,
                                            retryable: ee.kind == "timeout" || ee.kind == "network"))
                } else {
                    // 다른 예외의 내용은 노출하지 않는다.
                    out.append(CourseResult(course, [], "과제 조회 실패"))
                }
            }
        }
        return out
    }
}

/// `etl_assignments`의 순수 로직: 기간 계산(KST)과 과제 고르기·정렬.
/// "이번 주"는 KST 월요일 00:00부터 다음 월요일 00:00 직전(일요일 23:59)까지다.
public enum AssignmentQuery {

    public enum Range: String, CaseIterable, Sendable, Hashable {
        case THIS_WEEK = "this_week", NEXT_WEEK = "next_week", DAYS = "days"

        public var id: String { rawValue }

        public static func parse(_ s: String?) -> Range {
            let key = s?.kTrim.lowercased()
            return allCases.first { $0.id == key } ?? .THIS_WEEK
        }
    }

    /// [start] 이상 [endExclusive] 미만.
    public struct Window: Sendable, Hashable {
        public let start: Date
        public let endExclusive: Date
        public init(start: Date, endExclusive: Date) {
            self.start = start
            self.endExclusive = endExclusive
        }
        public func contains(_ t: Date) -> Bool { !(t < start) && t < endExclusive }
    }

    public static func window(_ range: Range, _ now: Date, _ days: Int = 7) -> Window {
        let monday = DayDate(now).previousOrSame(.monday)
        switch range {
        case .THIS_WEEK: return Window(start: monday.startOfDay, endExclusive: monday.plusWeeks(1).startOfDay)
        case .NEXT_WEEK: return Window(start: monday.plusWeeks(1).startOfDay, endExclusive: monday.plusWeeks(2).startOfDay)
        case .DAYS: return Window(start: now, endExclusive: now.plusSeconds(Int64(min(max(days, 1), 60)) * 86400))
        }
    }

    /// 기간 안에 마감인 게시된 과제를 마감순(같으면 강의 이름, 과제 ID)으로 고른다.
    /// [includeSubmitted]가 false면 이미 낸 과제를 뺀다. 결과는 모델에게 주는 JSON 행.
    public static func select(_ courses: [CourseAssignments.CourseResult], _ window: Window, includeSubmitted: Bool, now: Date) -> [JSONObject] {
        struct Row { let due: Date; let course: String; let id: Int64; let json: JSONObject }
        struct Key: Hashable { let c: Int64; let a: Int64 }
        var rows: [Row] = []
        var seen = Set<Key>()
        for cr in courses {
            for a in cr.assignments {
                if a.has("published") && !a.optBoolean("published", true) { continue }
                guard let due = EtlLogic.parseInstant(a.str("due_at")) else { continue }
                if !window.contains(due) { continue }
                let submitted = EtlLogic.isSubmitted(a)
                if submitted && !includeSubmitted { continue }
                let aid = a.optLong("id")
                if !seen.insert(Key(c: cr.courseId, a: aid)).inserted { continue }
                let sub = a.obj("submission")
                let name = a.str("name").flatMap { $0.nilIfBlank } ?? "(제목 없음)"
                rows.append(Row(due: due, course: cr.courseName, id: aid, json: JSONObject()
                    .put("course", cr.courseName)
                    .put("course_id", cr.courseId)
                    .put("assignment_id", aid)
                    .put("name", name)
                    .put("due", EtlLogic.fmtKst(due))
                    .put("remaining", EtlLogic.remaining(due, now))
                    .put("submitted", submitted)
                    .put("submitted_at", EtlLogic.parseInstant(sub?.str("submitted_at")).map { EtlLogic.fmtKst($0) } ?? JSONObject.NULL)
                    .put("missing", EtlLogic.isMissing(a, now))
                    .put("online", EtlLogic.isOnline(a))
                    .put("points", jsonNullIf(!a.has("points_possible") || a.isNull("points_possible"), a.opt("points_possible")))
                    .put("score", jsonNullIf(sub == nil || !sub!.has("score") || sub!.isNull("score"), sub?.opt("score")))
                    .put("url", a.str("html_url") ?? JSONObject.NULL)))
            }
        }
        let sorted = stableSorted(rows) { x, y in
            if x.due != y.due { return x.due < y.due }
            if x.course != y.course { return x.course.utf16.lexicographicallyPrecedes(y.course.utf16) }
            return x.id < y.id
        }
        return sorted.map(\.json)
    }
}

/// 제출 직후 자동 점검. 파일을 받지 않고 eTL이 준 제출 정보(메타데이터)만 본다.
public struct SubmitCheck: Sendable, Hashable, Codable {
    /// 늦은 제출(eTL `late` 표시 또는 마감 뒤 제출). 알림은 보내지 않고 화면에만 보인다.
    public var late: Bool
    /// 늦음을 뺀 문제(알림 대상).
    public var problems: [String]
    /// 첨부 목록을 확인했는가. 파일 업로드 제출인데 `attachments` 키가 아예 없으면 false.
    public var attachmentsKnown: Bool
    /// 제출한 첨부 파일 수(첨부 목록을 봤을 때만, 파일이 없는 글·링크 제출은 nil).
    public var files: Int?

    public init(late: Bool, problems: [String], attachmentsKnown: Bool = true, files: Int? = nil) {
        self.late = late
        self.problems = problems
        self.attachmentsKnown = attachmentsKnown
        self.files = files
    }
}

public enum SubmissionCheck {
    private static let LATE: Set<String> = [EtlLogic.LATE_FLAGGED, EtlLogic.LATE_AFTER_DUE]

    /// [a] = 과제 JSON, [s] = 그 제출(과제 목록의 `submission` 또는 `submissions/self`). 제출하지 않았으면 nil.
    public static func evaluate(_ a: JSONObject, _ s: JSONObject?, _ now: Date) -> SubmitCheck? {
        guard let s else { return nil }
        let ev = EtlLogic.evaluateSubmission(a, s, now)
        if !ev.submitted { return nil }
        var problems = ev.problems.filter { !LATE.contains($0) }
        let late = ev.problems.contains { LATE.contains($0) }
        let attachments = s.optJSONArray("attachments")
        let upload = s.str("submission_type") == "online_upload"
        if let attachments {
            let allowedExt = allowedExtensions(a)
            for i in 0..<attachments.length() {
                if let att = attachments.optJSONObject(i) { problems += EtlLogic.checkAttachment(att, allowedExt) }
            }
            if upload && attachments.length() == 0 { problems.append(NO_ATTACHMENT) }
        }
        let files = attachments.map { $0.length() }.flatMap { (upload || $0 > 0) ? $0 : nil }
        return SubmitCheck(late: late, problems: problems, attachmentsKnown: attachments != nil || !upload, files: files)
    }

    public static let NO_ATTACHMENT = "파일 업로드 과제인데 첨부 파일이 없음"

    public static func allowedExtensions(_ a: JSONObject) -> [String] {
        a.strList("allowed_extensions").map { $0.lowercased().kTrimStart(["."]) }
    }

    public static func submissionPath(_ courseId: Int64, _ assignmentId: Int64) -> String {
        "/api/v1/courses/\(courseId)/assignments/\(assignmentId)/submissions/self"
    }

    /// "내 일정"의 "제출 확인" 결과. [problems]에는 늦음도 들어간다(직접 확인할 때는 다 보여 준다).
    public struct Live: Sendable, Hashable {
        public var assignment: String
        public var submitted: Bool
        public var submittedAt: Date?
        public var due: Date?
        public var problems: [String]
        public var warnings: [String]
        public var files: [String]
        public var url: String?

        public init(assignment: String, submitted: Bool, submittedAt: Date?, due: Date?, problems: [String], warnings: [String], files: [String], url: String?) {
            self.assignment = assignment
            self.submitted = submitted
            self.submittedAt = submittedAt
            self.due = due
            self.problems = problems
            self.warnings = warnings
            self.files = files
            self.url = url
        }

        public var ok: Bool { submitted && problems.isEmpty }
    }

    /// 과제와 내 제출을 지금 받아 판정한다(GET 두 번, 파일은 받지 않는다).
    public static func live(_ client: EtlClient, _ courseId: Int64, _ assignmentId: Int64, _ now: Date) async throws -> Live {
        let a = try await client.getObject("/api/v1/courses/\(courseId)/assignments/\(assignmentId)")
        let s = try await client.getObject(submissionPath(courseId, assignmentId))
        return liveFrom(a, s, now)
    }

    public static func liveFrom(_ a: JSONObject, _ s: JSONObject, _ now: Date) -> Live {
        let ev = EtlLogic.evaluateSubmission(a, s, now)
        var problems = ev.problems
        var files: [String] = []
        if let attachments = s.optJSONArray("attachments") {
            let allowedExt = allowedExtensions(a)
            for i in 0..<attachments.length() {
                guard let att = attachments.optJSONObject(i) else { continue }
                problems += EtlLogic.checkAttachment(att, allowedExt)
                files.append(att.str("display_name") ?? att.str("filename") ?? "(이름 없음)")
            }
        }
        if ev.submitted && s.str("submission_type") == "online_upload" && files.isEmpty { problems.append(NO_ATTACHMENT) }
        return Live(assignment: a.str("name") ?? "(제목 없음)", submitted: ev.submitted, submittedAt: ev.submittedAt, due: ev.due,
                    problems: problems, warnings: ev.warnings, files: files, url: a.str("html_url"))
    }
}
