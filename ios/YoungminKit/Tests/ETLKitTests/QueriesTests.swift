import Foundation
import Testing
@testable import ETLKit

/// ttokttok-pet queries.js / files.js / timetable.js(보강·휴강) 규칙을 옮긴 것의 테스트.
struct QueriesTests {
    private let now = instant("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    private func iso(_ hours: Int64) -> String { isoString(now.plusSeconds(hours * 3600)) }

    private func course(_ id: Int64, _ name: String, _ term: String, _ score: Double? = nil) -> JSONObject {
        JSONObject()
            .put("id", id).put("name", name).put("term", JSONObject().put("name", term))
            .put("enrollments", JSONArray().put(JSONObject().put("type", "student").put("computed_current_score", score ?? JSONObject.NULL)))
    }

    private func asg(_ id: Int64, _ dueHours: Int64?, submitted: Bool = false, score: Double? = nil, online: Bool = true) -> JSONObject {
        JSONObject()
            .put("id", id).put("name", "과제\(id)").put("due_at", dueHours.map { iso($0) } ?? JSONObject.NULL)
            .put("points_possible", 10).put("submission_types", JSONArray().put(online ? "online_upload" : "on_paper"))
            .put("html_url", "https://myetl.snu.ac.kr/courses/1/assignments/\(id)")
            .put("submission", JSONObject()
                .put("workflow_state", score != nil ? "graded" : (submitted ? "submitted" : "unsubmitted"))
                .put("submitted_at", (submitted || score != nil) ? iso(-10) : JSONObject.NULL as Any)
                .put("score", score ?? JSONObject.NULL))
    }

    private var courses: [JSONObject] {
        [course(1, "2026-2 자료구조 (001)", "2026년 2학기", 91.5),
         course(2, "2026-1 선형대수 (001)", "2026년 1학기")]
    }

    @Test func coursesCurrentFirstWithScores() throws {
        let cs = courses
        let r = try Queries.coursesResult(cs.reversed()).getJSONArray("courses")
        #expect(try r.getJSONObject(0).getLong("course_id") == 1)
        #expect(try r.getJSONObject(0).getBoolean("current"))
        #expect(try !r.getJSONObject(1).getBoolean("current"))
        #expect(try r.getJSONObject(0).getDouble("current_score") == 91.5)
        #expect(try Queries.coursesFor(cs, nil).map { $0.optLong("id") } == [1])
        #expect(try Queries.coursesFor(cs, 2).map { $0.optLong("id") } == [2])
        #expect(throws: EtlException.self) { try Queries.coursesFor(cs, 9) }
    }

    @Test func statusFilters() throws {
        let results = [CourseAssignments.CourseResult(courses[0], [asg(1, 5), asg(2, 200), asg(3, -5), asg(4, -50, submitted: true), asg(5, nil)], nil)]
        func ids(_ status: String, _ days: Int? = nil) throws -> [Int64] {
            let a = try Queries.statusList(results, status, days, now).getJSONArray("assignments")
            return try (0..<a.length()).map { try a.getJSONObject($0).getLong("assignment_id") }
        }
        #expect(try ids("upcoming") == [1, 2])
        #expect(try ids("upcoming", 7) == [1])
        #expect(try ids("missing") == [3])
        #expect(try ids("submitted") == [4])
        #expect(try ids("all") == [4, 3, 1, 2, 5]) // 마감순, 마감 없음은 끝
        #expect(throws: EtlException.self) { try ids("weird") }
    }

    @Test func gradesAndAnnouncements() throws {
        let results = [CourseAssignments.CourseResult(courses[0], [asg(1, -20, score: 8.5), asg(2, 5)], nil)]
        let g = try Queries.gradesResult([courses[0]], results, now).getJSONArray("courses").getJSONObject(0)
        #expect(try g.getJSONArray("graded_assignments").length() == 1)
        #expect(try g.getJSONArray("graded_assignments").getJSONObject(0).getDouble("score") == 8.5)

        let anns = Queries.announcementRows([
            JSONObject().put("context_code", "course_1").put("title", "옛 공지").put("posted_at", iso(-48)).put("message", "<p>a</p>"),
            JSONObject().put("context_code", "course_1").put("title", "새 공지").put("posted_at", iso(-1)).put("message", "<p>b</p>")
                .put("author", JSONObject().put("display_name", "조교")),
        ], [1: "자료구조"])
        #expect(try anns.getJSONObject(0).getString("title") == "새 공지")
        #expect(try anns.getJSONObject(0).getString("author") == "조교")
        #expect(try anns.getJSONObject(0).getString("course") == "자료구조")
        #expect(Queries.announcementBadge("11/20 수업 안내", "이번 주 목요일은 휴강합니다") == "휴강 공지")
        #expect(Queries.announcementBadge("보강 안내", "") == "보강 공지")
        #expect(Queries.announcementBadge("중간고사", "범위") == "시험 공지")
    }

    @Test func examAnnouncementDetection() {
        let exam = { (t: String, b: String) in Queries.isExamAnnouncement(t, b) }
        #expect(exam("중간고사 안내", ""))
        #expect(exam("기말 고사 장소 변경", ""))
        #expect(exam("Quiz 2 공지", ""))
        #expect(exam("Midterm exam room", ""))
        #expect(exam("[자료구조] 퀴즈", ""))
        #expect(exam("공지", "다음 주 화요일에 중간고사를 실시합니다. 장소: 302호"))
        // 성적·채점 공지는 일정 공지가 아니다
        #expect(!exam("중간고사 성적 공개", "평균 70점"))
        #expect(!exam("Midterm grades released", ""))
        #expect(exam("기말고사 일정 및 중간고사 성적 안내", ""))
        // 본문에만 스치듯 나오면 아니다
        #expect(!exam("과제 3 안내", "이번 과제 내용은 시험에 반영됩니다"))
        #expect(!exam("Final project", "Submit your final report"))
        #expect(!exam("실험 보고서 제출", "실험 2 보고서"))
        #expect(!exam("중간고사 성적", "시험 범위 질문은 조교에게"))
        // 제목의 시험이 보강보다 먼저, 본문에만 있으면 보강·휴강이 먼저
        #expect(Queries.announcementBadge("중간고사 안내", "보강 일정과 같이 봐 주세요") == "시험 공지")
        #expect(Queries.announcementBadge("수업 안내", "보강은 시험 일정 뒤에 합니다") == "보강 공지")
        #expect(Queries.announcementBadge("공지", "퀴즈는 목요일 수업 시간에 봅니다") == "시험 공지")
    }

    @Test func categoriesAndSupplies() {
        #expect(Queries.announcementCategory("과제 2 안내", "금요일까지 제출") == .ASSIGNMENT)
        #expect(Queries.announcementCategory("과제 2 성적 공개", "") == nil)
        #expect(Queries.announcementCategory("12주차 동영상 강의", "목요일까지 시청하세요") == .VIDEO)
        #expect(Queries.AnnCategory.MAKEUP.label == "보강")
        #expect(Queries.AnnCategory.VIDEO.label == "동영상 강의")
        #expect(Queries.AnnCategory.CANCEL.timetable)
        #expect(Queries.replacesClass("이번 주 대면 수업은 없습니다"))
        let lines = Queries.suppliesLines("시험 안내입니다. 계산기 지참 가능합니다. 신분증을 꼭 가져오세요.\n- 계산기 지참 가능합니다.")
        #expect(lines == ["계산기 지참 가능합니다.", "신분증을 꼭 가져오세요."])
        #expect(Queries.suppliesLines(String(repeating: "계산기", count: 30), maxLines: 1, maxChars: 10).first?.count == 10)
    }

    @Test func readOnlyConversationsAndCap() throws {
        #expect(Queries.readOnlyPath("/api/v1/conversations/5") == "/api/v1/conversations/5?auto_mark_as_read=false")
        #expect(Queries.readOnlyPath("/api/v1/conversations/5?a=1") == "/api/v1/conversations/5?a=1&auto_mark_as_read=false")
        #expect(Queries.readOnlyPath("/api/v1/conversations?per_page=30") == "/api/v1/conversations?per_page=30")
        let big = JSONArray((0..<50).map { JSONObject().put("i", $0).put("pad", String(repeating: "x", count: 50)) })
        let capped = try Queries.capAnswer("/p", big, 1000)
        #expect(try capped.getJSONArray("data").toString().count <= 1000)
        #expect(try capped.getJSONObject("truncated").getInt("total") == 50)
        #expect(try !Queries.capAnswer("/p", JSONArray().put(1), 1000).has("truncated"))
    }

    @Test func filesMergeSources() throws {
        let listed = [JSONObject().put("id", 10).put("display_name", "1주차.pdf").put("folder_id", 3).put("size", 100)]
        let folders = [JSONObject().put("id", 3).put("full_name", "course files/강의노트")]
        let modules = Queries.moduleFiles([JSONObject().put("name", "1주차").put("items", JSONArray()
            .put(JSONObject().put("type", "File").put("content_id", 10).put("title", "1주차.pdf"))
            .put(JSONObject().put("type", "File").put("content_id", 11).put("title", "실습.zip"))
            .put(JSONObject().put("type", "Page").put("content_id", 99)))])
        let assignments = [asg(1, 5).put("description", #"<a href="/courses/1/files/12/download">양식</a> <a href="https://evil.example/files/13">x</a>"#)]
        let anns = [JSONObject().put("title", "공지").put("attachments", JSONArray().put(JSONObject().put("id", 14).put("display_name", "영상.mp4").put("content-type", "video/mp4")))]
        let attached = Queries.attachedFiles(assignments, anns, 1)
        #expect(attached.map(\.id) == [12, 14])
        let r = Queries.filesResult(1, "자료구조", listed, folders, modules, attached, [11: JSONObject().put("display_name", "실습.zip")], [10], [])
        let arr = try r.getJSONArray("files")
        var rows: [Int64: JSONObject] = [:]
        for i in 0..<arr.length() { let o = try arr.getJSONObject(i); rows[try o.getLong("id")] = o }
        #expect(Set(rows.keys) == [10, 11, 12, 14])
        #expect(try rows[10]!.getString("folder") == "강의노트")
        #expect(try rows[10]!.getString("module") == "1주차")
        #expect(try rows[10]!.getBoolean("downloaded"))
        #expect(try rows[14]!.getString("skip").contains("동영상"))
        #expect(try rows[12]!.getString("assignment") == "과제1")
        #expect(try r.getInt("downloaded_count") == 1)
        #expect(Queries.linkedFiles(#"<img src="/files/7/preview"><a href="/courses/2/files/8">"#, 1) == [7])
    }

    @Test func timetableChanges() throws {
        let w = TimetableLogic.Weekly("1", 0, [
            TimetableLogic.CourseMeetings(1, "2026-2 가상현실공학 (001)", [TimetableLogic.Meeting("목", "09:30", "10:45", "301-118"), TimetableLogic.Meeting("목", "15:30", "16:45", "301-118")]),
            TimetableLogic.CourseMeetings(2, "2026-2 자료구조 (001)", [TimetableLogic.Meeting("목", "11:00", "12:15", nil)]),
        ], [])
        let thu = DayDate.of(2026, 11, 19)
        let changes = [
            TimetableLogic.Change(1, thu, "휴강", "현실공학", note: "학회"),                       // 이름 일부로도 맞는다
            TimetableLogic.Change(2, thu, "보강", "자료구조", courseId: 2, start: "18:00", end: "19:15", room: "302-208"),
            TimetableLogic.Change(3, thu.plusDays(1), "휴강", "자료구조"),                           // 다른 날
        ]
        let day = TimetableLogic.day(w, thu, changes)
        #expect(day.classes.map { "\($0.start) \($0.kind)" } == ["11:00 정규", "18:00 보강"])
        #expect(day.cancelled.count == 2)
        #expect(day.cancelled[0].note == "학회")
        // 시각을 준 휴강은 그 시각 수업만
        let one = TimetableLogic.day(w, thu, [TimetableLogic.Change(4, thu, "휴강", "가상현실공학", courseId: 1, start: "15:30")])
        #expect(one.classes.map(\.start) == ["09:30", "11:00"])
        #expect(TimetableLogic.sameCourse("가상현실 공학", "2026-2 가상현실공학 (001)"))
        #expect(!TimetableLogic.sameCourse("가현공", "2026-2 가상현실공학 (001)"))
        #expect(TimetableLogic.changesFromJson(TimetableLogic.changesToJson(changes)) == changes)
        #expect(TimetableLogic.validateChange("보강", "자료구조", nil, nil) == "보강은 시작·끝 시각이 필요해요")
        #expect(TimetableLogic.validateChange("휴강", "자료구조", nil, nil) == nil)
        #expect(TimetableLogic.validateChange("보강", "자료구조", "18:00", "17:00") == "끝 시각이 시작보다 늦어야 해요")
    }

    @Test func changeStoreDropsOldEntries() {
        let store = TimetableChangeStore(InMemoryStore())
        let today = DayDate.of(2026, 11, 19)
        store.save([TimetableLogic.Change(1, today.minusDays(31), "휴강", "A"), TimetableLogic.Change(2, today, "휴강", "B")], today: today)
        #expect(store.load().map(\.id) == [2])
        store.delete(2)
        #expect(store.load().isEmpty)
    }
}
