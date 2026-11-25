import Foundation
import Testing
import ChatKit
import ETLKit
import NotifyKit
@testable import AssistantKit

@Suite struct FakeEtlToolTests {
    @Test func realToolsReadFakeDataAndWritesStillRequireApproval() async throws {
        let now = ISO8601DateFormatter().date(from: "2026-11-26T03:00:00Z")!
        let fake = FakeEtl(now: now)
        let gate = WriteApprovalGate()
        let fakeMine = MyDeadlineStore(InMemoryStore())
        let realPrefs = InMemoryStore(["sentinel": "real data"])
        let realMine = MyDeadlineStore(realPrefs)
        let box = Toolbox(tokens: { FakeEtl.token }, makeClient: { try EtlClient($0, fake: fake) },
                          approvals: gate, myDeadlines: fakeMine,
                          timetableStore: TimetableStore(InMemoryStore()), timetableChanges: TimetableChangeStore(InMemoryStore()), clock: { now })
        let courses = parsed(await box.run(name: "etl_courses", argumentsJSON: "{}").text)
        #expect(courses.toString().contains("[가상] 회로이론"))
        let digest = parsed(await box.run(name: "etl_digest", argumentsJSON: "{}").text)
        #expect(digest.toString().contains("RC 회로 과제"))
        #expect(digest.toString().contains("지난 주 복습"))
        let detail = parsed(await box.run(name: "etl_assignment", argumentsJSON: #"{"course_id":101,"assignment_id":1011}"#).text)
        #expect(!detail.has("error"))
        let args = #"{"title":"가상 시험","due":"2026-12-03 14:00","remind_before_minutes":[60]}"#
        let rejected = parsed(await box.run(name: "my_deadline_add", argumentsJSON: args).text)
        #expect(rejected.has("error") && fakeMine.load().isEmpty)
        let host = gate.attachHost()
        defer { gate.detachHost(host) }
        let requests = RequestObserver(gate)
        let call = Task { await box.run(name: "my_deadline_add", argumentsJSON: args).text }
        let request = try #require(await requests.next())
        #expect(fakeMine.load().isEmpty)
        #expect(gate.decide(hostId: host, requestId: request.id, approved: true))
        let saved = parsed(await call.value)
        #expect(saved.optBoolean("ok"))
        #expect(fakeMine.load().count == 1)
        #expect(realMine.load().isEmpty && realPrefs.string("sentinel") == "real data")
    }
}
