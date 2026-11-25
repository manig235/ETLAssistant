import CryptoKit
import Foundation

/// Local Canvas fixture backend. Unknown routes fail closed; there is no URLSession
/// fallback. Production queries, parsers, downloads and write approvals still run.
public final class FakeEtl: @unchecked Sendable {
    public static let token = "youngmin-fake-etl-local-only"
    public static let origin = "https://fake-etl.invalid"
    public static let shared = FakeEtl()
    private let anchor: Date
    public init(now: Date = Date()) { anchor = now }
    private func instant(_ offset: TimeInterval) -> String { ISO8601DateFormatter().string(from: anchor.addingTimeInterval(offset)) }
    private func due(_ days: Int) -> String {
        let start = EtlLogic.calendar.startOfDay(for: anchor)
        return ISO8601DateFormatter().string(from: start.addingTimeInterval(Double(days) * 86_400 + 23 * 3600 + 59 * 60))
    }
    public var courses: [JSONObject] {
        [(101, "[가상] 회로이론"), (102, "[가상] 자료구조"), (103, "[가상] 글쓰기")].map { id, name in
            JSONObject().put("id", id).put("name", name).put("course_code", "FAKE-\(id)")
                .put("workflow_state", "available").put("access_restricted_by_date", false)
                .put("html_url", "\(Self.origin)/courses/\(id)")
                .put("syllabus_body", "<p>가상 eTL 테스트 강의입니다. 매주 강의와 과제, 기말시험이 있습니다. 실제 학교 자료가 아닙니다.</p>")
                .put("enrollments", JSONArray([JSONObject().put("type", "student").put("enrollment_state", "active").put("computed_current_score", 92)]))
        }
    }
    private func submission(_ assignmentId: Int64) -> JSONObject {
        let submitted = assignmentId == 1031 || assignmentId == 1013
        let graded = assignmentId == 1013
        let row = JSONObject().put("assignment_id", assignmentId).put("user_id", 1)
            .put("workflow_state", graded ? "graded" : submitted ? "submitted" : "unsubmitted")
            .put("submission_type", submitted ? "online_upload" : JSONObject.NULL)
            .put("submitted_at", submitted ? instant(-3600) : JSONObject.NULL)
            .put("late", false).put("missing", assignmentId == 1012).put("attempt", submitted ? 1 : 0)
            .put("score", graded ? 95 : JSONObject.NULL).put("grade", graded ? "95" : JSONObject.NULL)
            .put("submission_comments", JSONArray()).put("submission_history", JSONArray())
        row.put("attachments", submitted ? JSONArray([file(5102)!]) : JSONArray())
        return row
    }
    public func assignments(_ courseId: Int64) -> [JSONObject] {
        let seeds: [(Int64, String, Int)]
        switch courseId {
        case 101: seeds = [(1011, "RC 회로 과제", 1), (1012, "지난 주 복습", -2), (1013, "기초 회로 연습", -4)]
        case 102: seeds = [(1021, "그래프 탐색 실습", 3)]
        case 103: seeds = [(1031, "에세이 초안", 2)]
        default: seeds = []
        }
        return seeds.map { id, name, days in
            JSONObject().put("id", id).put("course_id", courseId).put("name", name).put("published", true)
                .put("due_at", due(days)).put("points_possible", 100).put("locked_for_user", false)
                .put("submission_types", JSONArray(["online_upload"])).put("allowed_extensions", JSONArray(["pdf", "txt"]))
                .put("html_url", "\(Self.origin)/courses/\(courseId)/assignments/\(id)")
                .put("description", "<p>가상 과제: 강의자료를 읽고 풀이를 정리하세요. <a href=\"\(Self.origin)/courses/\(courseId)/files/5102/download\">제출 안내</a></p>")
                .put("submission", submission(id))
        }
    }
    private var announcements: [JSONObject] {
        courses.map { course in
            let cid = course.optLong("id")
            return JSONObject().put("id", cid * 10).put("context_code", "course_\(cid)")
                .put("title", cid == 101 ? "[가상] 기말시험 안내" : "[가상] 이번 주 강의 안내")
                .put("posted_at", instant(-600)).put("html_url", "\(Self.origin)/courses/\(cid)/discussion_topics/\(cid * 10)")
                .put("message", "<p>가상 테스트 공지입니다. 기말시험은 \(due(7).prefix(10)) 오후 2시부터 4시까지, 301동 101호입니다. 범위는 8~14주차이며 계산기를 지참하세요.</p>")
        }
    }
    public func weekly(nowMs: Int64) -> TimetableLogic.Weekly {
        let rows = courses.enumerated().map { index, course in
            TimetableLogic.CourseMeetings(course.optLong("id"), course.optString("name"),
                [TimetableLogic.Meeting(["월", "화", "수"][index], "10:00", "11:30", "가상강의실 \(index+1)")], "테스트교수", credits: 3)
        }
        return TimetableLogic.Weekly("101,102,103", nowMs, rows, [])
    }
    private func content(_ id: Int64) -> Data? {
        if id == 5102 { return Data("FAKE ETL - Submission guide\nSubmit a PDF or TXT file. Include your reasoning and check that the file is not empty.\n".utf8) }
        guard id == 5101 else { return nil }
        let safeStream = "BT /F1 14 Tf 40 760 Td (FAKE ETL - RC Circuits) Tj 0 -25 Td (Time constant: tau = R * C.) Tj 0 -25 Td (Charging: V = V0 times [1 - exp[-t/tau]].) Tj ET"
        let objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>", "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Length \(safeStream.utf8.count) >>\nstream\n\(safeStream)\nendstream"]
        var pdf = "%PDF-1.4\n"; var offsets = [0]
        for (index, object) in objects.enumerated() { offsets.append(pdf.utf8.count); pdf += "\(index+1) 0 obj\n\(object)\nendobj\n" }
        let xref = pdf.utf8.count
        pdf += "xref\n0 6\n0000000000 65535 f \n"
        for offset in offsets.dropFirst() { pdf += String(format: "%010d 00000 n \n", offset) }
        pdf += "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n"
        return Data(pdf.utf8)
    }
    private func file(_ id: Int64) -> JSONObject? {
        guard let bytes = content(id) else { return nil }
        let name = id == 5101 ? "fake-rc-lecture.pdf" : "fake-submission-guide.txt"
        return JSONObject().put("id", id).put("display_name", name).put("filename", name).put("size", bytes.count)
            .put("content-type", id == 5101 ? "application/pdf" : "text/plain").put("locked_for_user", false).put("hidden_for_user", false)
            .put("url", "\(Self.origin)/files/\(id)/download").put("created_at", instant(-86_400)).put("updated_at", instant(-600))
    }
    public func get(_ raw: String) throws -> Any {
        guard let url = URLComponents(string: raw), url.path.hasPrefix("/api/v1/") else { throw missing() }
        let parts = url.path.split(separator: "/").map(String.init)
        let path = Array(parts.dropFirst(2))
        let query = url.queryItems ?? []
        if path == ["users", "self", "profile"] || path == ["users", "self"] { return JSONObject().put("id", 1).put("name", "가상 학생").put("short_name", "가상 학생") }
        if path == ["courses"] { return JSONArray(courses) }
        if path == ["announcements"] {
            let selected = Set(query.filter { $0.name == "context_codes[]" }.compactMap(\.value))
            return JSONArray(announcements.filter { selected.isEmpty || selected.contains($0.optString("context_code")) })
        }
        if path == ["calendar_events"] { return JSONArray() }
        if path.count == 2, path[0] == "files", let id = Int64(path[1]), let file = file(id) { return file }
        if path.count >= 2, path[0] == "courses", let cid = Int64(path[1]), let course = courses.first(where: { $0.optLong("id") == cid }) {
            if path.count == 2 { return course }
            let tail = Array(path.dropFirst(2))
            if tail == ["assignments"] { return JSONArray(assignments(cid)) }
            if tail.count >= 2, tail[0] == "assignments", let id = Int64(tail[1]), let assignment = assignments(cid).first(where: { $0.optLong("id") == id }) {
                if tail.count == 2 { return assignment }
                if Array(tail.dropFirst(2)) == ["submissions", "self"] { return submission(id) }
            }
            if tail == ["files"] {
                let term = query.first { $0.name == "search_term" }?.value ?? ""
                return JSONArray([file(5101)!, file(5102)!].filter { term.isEmpty || $0.optString("display_name").localizedCaseInsensitiveContains(term) })
            }
            if tail.count == 2, tail[0] == "files", let id = Int64(tail[1]), let file = file(id) { return file }
            if [ ["folders"], ["modules"], ["tabs"] ].contains(tail) { return JSONArray() }
        }
        throw missing()
    }
    private func missing() -> EtlException { EtlException("가상 eTL에 해당 자료가 없어요. 실제 eTL에는 요청하지 않았어요.", kind: "fake-404") }
    public func download(_ raw: String, to destination: URL, maxBytes: Int64) throws -> EtlClient.Download {
        guard EtlClient.sameOrigin(raw, Self.origin), let url = URLComponents(string: raw),
              url.path.hasPrefix("/files/"), url.path.hasSuffix("/download"),
              let part = url.path.split(separator: "/").dropFirst().first, let id = Int64(part), let data = content(id) else { throw missing() }
        guard data.count <= maxBytes else { throw EtlException("가상 파일이 크기 제한을 넘어요") }
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: destination, options: .atomic)
        return EtlClient.Download(bytes: Int64(data.count), sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
    }
}
