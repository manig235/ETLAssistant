import Foundation
import Testing
@testable import ETLKit

struct AssignmentQueryTests {
    typealias Range = AssignmentQuery.Range

    // 2026-11-16(월) ~ 2026-11-22(일)이 한 주.
    private let fri = kst("2026-11-20T23:30:00")

    @Test func thisWeekIsMondayToSundayKst() {
        let w = AssignmentQuery.window(.THIS_WEEK, fri)
        #expect(w.start == kst("2026-11-16T00:00:00"))
        #expect(w.endExclusive == kst("2026-11-23T00:00:00"))
        #expect(w.contains(kst("2026-11-22T23:59:00")))
        #expect(!w.contains(kst("2026-11-23T00:00:00")))
        #expect(w.contains(kst("2026-11-16T00:00:00")))
        #expect(!w.contains(kst("2026-11-15T23:59:00")))
    }

    @Test func weekBoundariesFromSundayNightAndMondayMidnight() {
        // 일요일 23:59에는 아직 그 주
        #expect(AssignmentQuery.window(.THIS_WEEK, kst("2026-11-22T23:59:00")).start == kst("2026-11-16T00:00:00"))
        // 월요일 00:00이 되면 새 주
        #expect(AssignmentQuery.window(.THIS_WEEK, kst("2026-11-23T00:00:00")).start == kst("2026-11-23T00:00:00"))
        // UTC로는 일요일 15:00 = KST 월요일 00:00
        #expect(AssignmentQuery.window(.THIS_WEEK, instant("2026-11-22T15:00:00Z")).start == kst("2026-11-23T00:00:00"))
    }

    @Test func nextWeekAndDays() {
        let n = AssignmentQuery.window(.NEXT_WEEK, fri)
        #expect(n.start == kst("2026-11-23T00:00:00"))
        #expect(n.endExclusive == kst("2026-11-30T00:00:00"))
        let d = AssignmentQuery.window(.DAYS, fri, 3)
        #expect(d.start == fri)
        #expect(d.endExclusive == fri.plusSeconds(3 * 86400))
        #expect(Range.parse("이상한 값") == .THIS_WEEK)
        #expect(Range.parse(" Next_Week ") == .NEXT_WEEK)
    }

    private func course(_ id: Int64, _ name: String) -> JSONObject { JSONObject().put("id", id).put("name", name) }
    private func asg(_ id: Int64, _ due: String?, submitted: Bool = false, published: Bool = true, score: Double? = nil) -> JSONObject {
        JSONObject().put("id", id).put("name", "과제\(id)").put("published", published)
            .put("due_at", due.map { isoString(kst($0)) } ?? JSONObject.NULL)
            .put("submission_types", JSONArray(["online_upload"]))
            .put("points_possible", 10)
            .put("submission", JSONObject()
                .put("workflow_state", submitted ? "submitted" : "unsubmitted")
                .put("submitted_at", submitted ? "2026-11-17T01:00:00Z" : JSONObject.NULL as Any)
                .put("score", score ?? JSONObject.NULL))
    }

    @Test func selectFiltersAndSorts() {
        let results = [
            CourseAssignments.CourseResult(course(1, "나강의"), [
                asg(11, "2026-11-22T23:59:00"),              // 이번 주 끝
                asg(12, "2026-11-17T12:00:00", submitted: true, score: 9.0), // 이미 낸 것(지남)
                asg(13, "2026-11-23T00:00:00"),              // 다음 주 → 제외
                asg(14, "2026-11-19T12:00:00", published: false), // 미게시 → 제외
                asg(15, nil),                                // 마감 없음 → 제외
            ], nil),
            CourseAssignments.CourseResult(course(2, "가강의"), [
                asg(21, "2026-11-22T23:59:00"),              // 같은 마감 → 강의 이름순으로 앞
                asg(21, "2026-11-22T23:59:00"),              // 중복 → 한 번만
            ], nil),
            CourseAssignments.CourseResult(course(3, "다강의"), [], "권한 없음(401)"),
        ]
        let w = AssignmentQuery.window(.THIS_WEEK, fri)
        let rows = AssignmentQuery.select(results, w, includeSubmitted: true, now: fri)
        #expect(rows.map { $0.optLong("assignment_id") } == [12, 21, 11])
        #expect(rows[0].optBoolean("submitted"))
        #expect(rows[0].optDouble("score") == 9.0)
        #expect(rows[1].optString("course") == "가강의")
        #expect(rows[2].optString("due") == "11-22(일) 23:59")
        #expect(!rows[2].optBoolean("missing"))

        let unsubmitted = AssignmentQuery.select(results, w, includeSubmitted: false, now: fri)
        #expect(unsubmitted.map { $0.optLong("assignment_id") } == [21, 11])
    }
}
