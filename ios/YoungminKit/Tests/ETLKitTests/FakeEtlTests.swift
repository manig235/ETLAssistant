import CryptoKit
import Foundation
import PDFKit
import Testing
@testable import ETLKit

@Suite struct FakeEtlTests {
    let now = ISO8601DateFormatter().date(from: "2026-11-26T03:00:00Z")!

    @Test func productionParsersReadCoursesAssignmentsAndSubmission() async throws {
        let c = try EtlClient(FakeEtl.token, fake: FakeEtl(now: now))
        #expect(c.origin == FakeEtl.origin)
        let courses = try await CourseAssignments.currentCourses(c)
        #expect(courses.count == 3)
        let results = try await CourseAssignments.fetch(c, courses)
        #expect(results.allSatisfy { $0.error == nil })
        #expect(results.flatMap(\.assignments).count == 5)
        let assignment = try await c.getObject("/api/v1/courses/101/assignments/1011")
        #expect(assignment.optString("due_at") == "2026-11-27T14:59:00Z")
        let submitted = try await c.getObject("/api/v1/courses/103/assignments/1031/submissions/self")
        #expect(submitted.optString("workflow_state") == "submitted")
        #expect(submitted.optJSONArray("attachments")?.length() == 1)
    }
    @Test func filtersAnnouncementsAndFiles() async throws {
        let c = try EtlClient(FakeEtl.token, fake: FakeEtl(now: now))
        let rows = try await c.getAll("/api/v1/announcements?context_codes[]=course_102")
        #expect(rows.count == 1 && rows[0].optString("context_code") == "course_102")
        let files = try await c.getAll("/api/v1/courses/101/files?search_term=lecture")
        #expect(files.count == 1 && files[0].optLong("id") == 5101)
    }
    @Test func unknownRoutesNeverFallBackToProduction() async throws {
        let c = try EtlClient(FakeEtl.token, origin: "https://must-not-connect.invalid", fake: FakeEtl(now: now))
        do { _ = try await c.get("/api/v1/unknown"); Issue.record("Unknown fake route accepted") }
        catch let error as EtlException { #expect(error.kind == "fake-404") }
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do { _ = try await c.download("https://myetl.snu.ac.kr/files/5101/download", to: path); Issue.record("Real host accepted") }
        catch is EtlException { }
        #expect(!FileManager.default.fileExists(atPath: path.path))
    }
    @Test func fixtureDownloadIsAReadablePdfAndMatchesMetadata() async throws {
        let c = try EtlClient(FakeEtl.token, fake: FakeEtl(now: now))
        let meta = try await c.getObject("/api/v1/files/5101")
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pdf")
        defer { try? FileManager.default.removeItem(at: path) }
        let result = try await c.download(meta.optString("url"), to: path)
        let bytes = try Data(contentsOf: path)
        #expect(result.bytes == meta.optLong("size"))
        #expect(result.sha256 == SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined())
        let pdf = try #require(PDFDocument(url: path))
        #expect(pdf.pageCount == 1)
        #expect(pdf.string?.contains("Time constant") == true)
        do { _ = try await c.download(meta.optString("url"), to: path, maxBytes: 1); Issue.record("Size limit ignored") }
        catch is EtlException { }
    }
    @Test func timetableUsesFixturesWithoutCatalogNetwork() async throws {
        let c = try EtlClient(FakeEtl.token, fake: FakeEtl(now: now))
        let store = TimetableStore(InMemoryStore())
        let weekly = try await Timetable.weekly(c, store, nowMs: now.epochMilli, force: true)
        #expect(weekly.errors.isEmpty)
        #expect(weekly.courses.count == 3)
        #expect(weekly.courses.allSatisfy { !$0.meetings.isEmpty && $0.professor == "테스트교수" })
    }
}

@Suite struct DemoResetThreadTests {
    @Test func collectsOnlyValidNativeReferencesAndDoesNotFollowSymlinks() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let demo = root.appendingPathComponent("demo")
        try FileManager.default.createDirectory(at: demo, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = UUID().uuidString.lowercased()
        let outside = UUID().uuidString.lowercased()
        let own = #"[{"type":"youngmin_codex_thread","thread_id":"\#(id)"},{"type":"youngmin_codex_thread","thread_id":"../../real"},{"type":"message","thread_id":"\#(outside)"}]"#
        try own.write(to: demo.appendingPathComponent("own.json"), atomically: true, encoding: .utf8)
        let real = root.appendingPathComponent("real.json")
        try #"[{"type":"youngmin_codex_thread","thread_id":"\#(outside)"}]"#.write(to: real, atomically: true, encoding: .utf8)
        try FileManager.default.createSymbolicLink(at: demo.appendingPathComponent("link.json"), withDestinationURL: real)
        #expect(StudyDataProfile.nativeThreadIDs(in: demo) == [id])
        #expect(FileManager.default.fileExists(atPath: real.path))
    }
}
