import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 실행 순서와 결과(네트워크 없음: 토큰 없이 내 마감만, 또는 연결이 안 되는 주소).
@Suite struct NotifyRunnerTests {
    let now = instant("2026-11-19T03:00:00Z") // 12:00 KST

    func env(_ stores: NotifyStores, token: String? = nil, canPost: Bool = true) -> NotifyRunner.Environment {
        let now = self.now
        return NotifyRunner.Environment(stores: stores, token: token, zone: seoul, canPost: canPost, clock: { now })
    }

    @Test func skipsWithoutTokenAndDeadlines() async {
        let stores = NotifyStores.inMemory()
        let r = await NotifyRunner.runCheck(env(stores), now: now)
        #expect(r.outcome == .SKIPPED)
        #expect(r.notices.isEmpty)
        #expect(r.lastResult == "건너뜀 · eTL 토큰 없음")
        #expect(stores.state.load().lastCheckAt == now.epochMilli)
    }

    @Test func skipsWithoutPermissionAndRecordsNothing() async {
        let stores = NotifyStores.inMemory()
        stores.mine.save([MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(-60)])])
        let r = await NotifyRunner.runCheck(env(stores, canPost: false), now: now)
        #expect(r.outcome == .SKIPPED)
        #expect(r.lastResult == "건너뜀 · 알림 권한 없음")
        #expect(stores.state.load().fired.isEmpty)
    }

    @Test func myDeadlinesOnlyRunPostsRemindersFirstThenDeadlineWindowAndRecords() async {
        let stores = NotifyStores.inMemory()
        stores.mine.save([MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(-60)]), MyDeadline(6, "보고서", now.secs(9000))])
        let r = await NotifyRunner.runCheck(env(stores), now: now)
        #expect(r.outcome == .SUCCESS)
        #expect(r.notices.map(\.tag) == ["mine-remind:5", "deadline:wm180"])
        #expect(r.lastResult == "성공 · 마감 1건, 공지 0건")
        // 토큰 없이 확인하면 공지 기준선을 잡지 않는다
        #expect(!stores.state.load().announcementBaselineDone)
        #expect(r.nextAt != nil)
        // 다시 돌면 같은 알림은 오지 않는다
        let again = await NotifyRunner.runCheck(env(stores), now: now)
        #expect(again.notices.isEmpty)
    }

    @Test func connectionFailureFallsBackToCacheAndRetries() async throws {
        let stores = NotifyStores.inMemory()
        stores.snapshot.save(Snapshot(now.secs(-3600), [AssignmentItem(1, "자료구조", 2, "HW", now.secs(5 * 3600), true, nil, nil, false, false)], []))
        var e = env(stores, token: "t")
        let (client, _) = try stubClient { _ in nil } // 모든 요청이 연결 실패
        e.makeClient = { _ in client }
        let r = await NotifyRunner.runCheck(e, now: now)
        #expect(r.outcome == .RETRY)
        #expect(r.notices.map(\.tag) == ["deadline:1:2"])
        #expect(r.notices[0].bigText!.hasSuffix("※ eTL에서 지금 받지 못한 과제는 1시간 전 받아 둔 정보예요"))
        #expect(r.lastResult.hasPrefix("실패 · eTL 연결 실패"))
        #expect(r.lastResult.hasSuffix("1시간 전 받아 둔 과제로 마감 1건"))
    }

    @Test func tokenRejectedPostsAuthNoticeOncePerDay() async throws {
        let stores = NotifyStores.inMemory()
        var e = env(stores, token: "t")
        let (client, _) = try stubClient { _ in (401, #"{"errors":[{"message":"Invalid access token."}]}"#) }
        e.makeClient = { _ in client }
        let r = await NotifyRunner.runCheck(e, now: now)
        #expect(r.outcome == .FAILED)
        #expect(r.notices.map(\.tag) == ["auth"])
        #expect(r.lastResult == "실패 · eTL이 토큰을 거부했어요")
        let again = await NotifyRunner.runCheck(e, now: now.secs(3600))
        #expect(again.notices.isEmpty)
    }

    /// 꾸민 eTL 응답으로 한 바퀴: 첫 확인은 기준선(공지·새 과제·강의자료), 다음 확인에서 새 공지·새 과제·새 자료.
    @Test func fullRunAgainstStubbedEtl() async throws {
        final class World: @unchecked Sendable {
            let lock = NSLock()
            var assignments = #"[{"id":2,"name":"HW1","due_at":"2026-11-19T08:00:00Z","submission_types":["online_upload"],"html_url":"https://myetl.snu.ac.kr/courses/1/assignments/2"}]"#
            var announcements = #"[{"id":50,"title":"옛 공지","context_code":"course_1","posted_at":"2026-11-19T01:00:00Z","message":"<p>안녕</p>"}]"#
            var files = #"[{"id":900,"display_name":"1주차.pdf"}]"#
        }
        let w = World()
        let (client, _) = try stubClient { req in
            let path = req.url!.path
            return w.lock.withLock {
                switch path {
                case "/api/v1/courses": return (200, #"[{"id":1,"name":"2026-2 자료구조 (001)","term":{"name":"2026-2학기"}}]"#)
                case "/api/v1/courses/1/assignments": return (200, w.assignments)
                case "/api/v1/announcements": return (200, w.announcements)
                case "/api/v1/courses/1/files": return (200, w.files)
                default: return (404, "{}")
                }
            }
        }
        let stores = NotifyStores.inMemory()
        var e = env(stores, token: "t")
        e.makeClient = { _ in client }
        let r1 = await NotifyRunner.runCheck(e, now: now)
        #expect(r1.outcome == .SUCCESS)
        #expect(r1.notices.map(\.tag) == ["deadline:1:2"]) // 5시간 뒤 마감: 24시간 창
        #expect(r1.lastResult == "성공 · 마감 1건, 공지 0건")
        #expect(stores.state.load().announcementBaselineDone)
        #expect(stores.snapshot.load()!.assignments.map(\.assignmentId) == [2])
        #expect(stores.files.load().courses[1]!.seen == [900])

        w.lock.withLock {
            w.assignments = #"[{"id":2,"name":"HW1","due_at":"2026-11-19T08:00:00Z","submission_types":["online_upload"]},{"id":3,"name":"HW2","due_at":"2026-11-30T08:00:00Z","submission_types":["online_upload"]}]"#
            w.announcements = #"[{"id":51,"title":"중간고사 안내","context_code":"course_1","posted_at":"2026-11-19T06:00:00Z","message":"11월 28일 19:00 301동"},{"id":50,"title":"옛 공지","context_code":"course_1","posted_at":"2026-11-19T01:00:00Z","message":"<p>안녕</p>"}]"#
            w.files = #"[{"id":901,"display_name":"2주차.pdf"},{"id":900,"display_name":"1주차.pdf"}]"#
        }
        let later = now.secs(5 * 3600) // 17:00 KST(브리핑도 함께)
        e.clock = { later }
        stores.settings.save(NotifySettings(briefEnabled: true))
        let r2 = await NotifyRunner.runCheck(e, now: later, briefDueAt: later.secs(-60))
        #expect(r2.notices.map(\.channel) == [.ANNOUNCEMENT, .CHANGE, .NEW_FILES, .BRIEF])
        #expect(r2.notices[0].title == "2026-2 자료구조 (001) · 시험 공지")
        #expect(r2.notices[0].draft != nil)
        #expect(r2.notices[1].title == "새 과제 · 2026-2 자료구조 (001)")
        #expect(r2.notices[2].title == "새 강의자료 1개 · 2026-2 자료구조 (001)")
        #expect(r2.notices[3].title.hasPrefix("eTL 브리핑 · 11/19(목)"))
        #expect(stores.pending.load().map(\.id) == [51])
        #expect(stores.state.load().lastBriefAt == later.epochMilli)
    }
}
