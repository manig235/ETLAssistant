import Foundation
import ETLKit

/// eTL을 조회해 [Snapshot]을 만든다. 네트워크는 [EtlClient]만 쓴다(GET 전용).
/// JSON → 모델 변환은 네트워크 없이 테스트할 수 있도록 순수 함수로 분리했다.
public enum SnapshotFetcher {

    /// [EtlAuthException]은 그대로 던진다(토큰 거부는 사용자에게 알려야 한다).
    /// 그 외 [EtlException]은 해당 강의(또는 공지)만 건너뛰고 [Snapshot.errors]에 모은다.
    public static func fetch(_ client: EtlClient, _ now: Date, announcementDays: Int = 2,
                             needsSubmissionDetail: (AssignmentItem) -> Bool = { _ in false }) async throws -> Snapshot {
        let courses = try await CourseAssignments.currentCourses(client)
        var errors: [String] = []

        // ---- 과제 (강의별 병렬, 동시 요청 수는 EtlClient가 제한) ----
        var assignments: [AssignmentItem] = []
        var detailBudget = MAX_SUBMISSION_DETAIL
        for r in try await retryFailed(client, try await CourseAssignments.fetch(client, courses)) {
            if let e = r.error {
                errors.append("\(r.courseName): \(e)")
                continue
            }
            for item in mapAssignments(r.courseId, r.courseName, r.assignments, now) {
                if detailBudget <= 0 || item.check?.attachmentsKnown != false || !needsSubmissionDetail(item) {
                    assignments.append(item)
                    continue
                }
                detailBudget -= 1
                assignments.append(try await withSubmissionDetail(client, item, r.assignments, now))
            }
        }

        // ---- 공지 ----
        var announcements: [AnnouncementItem] = []
        if !courses.isEmpty {
            do {
                let (start, end) = kstDateRange(now, announcementDays)
                let ctx = courses.map { "context_codes[]=course_\($0.optLong("id"))" }.joined(separator: "&")
                let items = try await client.getAll("/api/v1/announcements?\(ctx)&start_date=\(start)&end_date=\(end)&per_page=50")
                var names: [Int64: String] = [:]
                for c in courses { names[c.optLong("id")] = c.str("name") ?? "" }
                announcements = mapAnnouncements(items, names, now, announcementDays)
            } catch let e as EtlAuthException {
                throw e
            } catch let e as EtlException {
                errors.append("공지: \(e.message)")
            }
        }

        let refs = courses.map { CourseRef($0.optLong("id"), $0.str("name") ?? "") }
        return Snapshot(now, sortAssignments(assignments), announcements, errors, refs)
    }

    /// 한 번 확인에서 `submissions/self`를 더 받는 과제 수 상한.
    private static let MAX_SUBMISSION_DETAIL = 5

    /// 파일 업로드 제출인데 과제 목록에 첨부 목록이 없을 때, 내 제출(`submissions/self`, GET)을 한 번 받아 다시 판정한다.
    private static func withSubmissionDetail(_ client: EtlClient, _ item: AssignmentItem, _ raw: [JSONObject], _ now: Date) async throws -> AssignmentItem {
        guard let a = raw.first(where: { $0.optLong("id") == item.assignmentId }) else { return item }
        do {
            let s = try await client.getObject(SubmissionCheck.submissionPath(item.courseId, item.assignmentId))
            guard let c = SubmissionCheck.evaluate(a, s, now) else { return item }
            var x = item
            x.check = c
            x.submittedAt = EtlLogic.parseInstant(s.str("submitted_at")) ?? item.submittedAt
            return x
        } catch let e as EtlAuthException {
            throw e
        } catch is EtlException {
            return item
        }
    }

    /// 연결 끊김·시간 초과로 실패한 강의만 한 번 더(차례로) 받는다. 토큰 거부는 그대로 던진다.
    private static func retryFailed(_ client: EtlClient, _ results: [CourseAssignments.CourseResult]) async throws -> [CourseAssignments.CourseResult] {
        var out: [CourseAssignments.CourseResult] = []
        for r in results {
            guard r.error != nil, r.retryable else { out.append(r); continue }
            do {
                out.append(CourseAssignments.CourseResult(r.course, try await client.getAll(CourseAssignments.assignmentsPath(r.courseId)), nil, retryable: false))
            } catch let e as EtlAuthException {
                throw e
            } catch let e as EtlException {
                out.append(CourseAssignments.CourseResult(r.course, r.assignments, e.message, retryable: r.retryable))
            }
        }
        return out
    }

    /// 마감 오름차순(마감 없음은 마지막). 안정 정렬이라 같은 마감끼리는 강의 순서가 유지된다.
    public static func sortAssignments(_ items: [AssignmentItem]) -> [AssignmentItem] {
        stableSorted(items) { ($0.due ?? .distantFuture) < ($1.due ?? .distantFuture) }
    }

    /// 한 강의의 과제 JSON을 [AssignmentItem]으로 바꾼다. 미게시(`published == false`) 과제는 제외한다.
    public static func mapAssignments(_ courseId: Int64, _ courseName: String, _ items: [JSONObject], _ now: Date) -> [AssignmentItem] {
        items.compactMap { a in
            if a.has("published") && !a.optBoolean("published", true) { return nil }
            let points: Double? = (!a.has("points_possible") || a.isNull("points_possible")) ? nil : { let v = a.optDouble("points_possible"); return v.isNaN ? nil : v }()
            let score: Double? = Queries.isGraded(a) ? a.obj("submission").flatMap { let v = $0.optDouble("score"); return v.isNaN ? nil : v } : nil
            return AssignmentItem(
                courseId, courseName, a.optLong("id"), a.str("name").flatMap { $0.nilIfBlank } ?? "(제목 없음)",
                EtlLogic.parseInstant(a.str("due_at")), EtlLogic.isOnline(a), points, a.str("html_url"),
                EtlLogic.isSubmitted(a), EtlLogic.isMissing(a, now), score,
                EtlLogic.parseInstant(a.obj("submission")?.str("submitted_at")),
                SubmissionCheck.evaluate(a, a.obj("submission"), now)
            )
        }
    }

    /// 공지 JSON을 [AnnouncementItem]으로 바꾼다(최신순). `posted_at`이 [now] − [announcementDays]일보다 이전이면 제외하고,
    /// `posted_at`이 없으면 [now]로 본다. [courseNames]에 없는 강의 ID는 숫자 문자열을 이름으로 쓴다.
    public static func mapAnnouncements(_ items: [JSONObject], _ courseNames: [Int64: String], _ now: Date, _ announcementDays: Int) -> [AnnouncementItem] {
        let cutoff = now.addingTimeInterval(-Double(announcementDays) * 86_400)
        let dated = items.map { ($0, EtlLogic.parseInstant($0.str("posted_at")) ?? now) }.filter { !($0.1 < cutoff) }
        return stableSorted(dated) { $0.1 > $1.1 }.map { (a, posted) in
            let cid = Int64((a.str("context_code") ?? "course_0").substringAfterLast("_")) ?? 0
            let full = EtlLogic.htmlToText(a.str("message"), 2000)
            return AnnouncementItem(
                a.optLong("id"), cid, courseNames[cid] ?? String(cid),
                a.str("title").flatMap { $0.nilIfBlank } ?? "(제목 없음)",
                posted, a.str("html_url") ?? a.str("url"),
                // 시험 공지는 준비물 줄을 찾으려고 본문을 더 길게 둔다.
                Queries.isExamAnnouncement(a.str("title"), full) ? full : EtlLogic.htmlToText(a.str("message"), 600)
            )
        }
    }

    /// 공지 조회 기간: (now − days일, now + 1일)의 KST 날짜 `YYYY-MM-DD` 쌍.
    public static func kstDateRange(_ now: Date, _ days: Int) -> (String, String) {
        (DayDate(now.addingTimeInterval(-Double(days) * 86_400)).description, DayDate(now.addingTimeInterval(86_400)).description)
    }
}
