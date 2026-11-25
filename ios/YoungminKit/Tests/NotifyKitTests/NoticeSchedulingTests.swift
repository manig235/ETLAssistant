import Foundation
import Testing
import ETLKit
@testable import NotifyKit

struct NoticeCodecTests {
    @Test func roundTripKeepsEveryField() {
        let n = Notice(.ANNOUNCEMENT, "제목", "본문", bigText: "긴 본문", url: "https://myetl.snu.ac.kr/courses/1", tag: "ann:1",
                       firedKeys: ["a", "b"], prefill: "정리해 줘", prefillAction: "비서에게 부탁", draft: "{\"x\":1}", agenda: true, mineItemId: 42)
        #expect(NoticeCodec.fromJson(NoticeCodec.toJson(n)) == n)
    }

    @Test func roundTripKeepsNils() {
        let n = Notice(.BRIEF, "브리핑", "요약", tag: "brief")
        #expect(NoticeCodec.fromJson(NoticeCodec.toJson(n)) == n)
    }

    @Test func badInputIsNil() {
        #expect(NoticeCodec.fromJson(nil) == nil)
        #expect(NoticeCodec.fromJson("{") == nil)
        #expect(NoticeCodec.fromJson("{\"channel\":\"nope\",\"tag\":\"t\"}") == nil)
    }
}

struct NoticeOpenTests {
    let url = "https://myetl.snu.ac.kr/courses/1/announcements/2"

    @Test func buttonsOpenTheirTargets() {
        let n = Notice(.ANNOUNCEMENT, "t", "x", url: url, tag: "a", prefill: "p", prefillAction: "비서에게 부탁", draft: "{}")
        #expect(NoticeOpen.resolve(n, action: NoticeOpen.ACTION_DRAFT) == .draft("{}"))
        #expect(NoticeOpen.resolve(n, action: NoticeOpen.ACTION_PREFILL) == .chat(prefill: "p"))
        #expect(NoticeOpen.resolve(n, action: NoticeOpen.ACTION_ETL) == .etl(url))
        // 본문: 버튼으로 채우는 문구가 있으면 eTL이 먼저.
        #expect(NoticeOpen.resolve(n, action: "com.apple.UNNotificationDefaultActionIdentifier") == .etl(url))
    }

    @Test func bodyTapFollowsNoticeRouting() {
        #expect(NoticeOpen.resolve(Notice(.DEADLINE, "t", "x", url: url, tag: "a", prefill: "p"), action: "") == .chat(prefill: "p"))
        #expect(NoticeOpen.resolve(Notice(.DEADLINE, "t", "x", tag: "a", agenda: true, mineItemId: 7), action: "") == .agenda(7))
        #expect(NoticeOpen.resolve(Notice(.DEADLINE, "t", "x", tag: "a", agenda: true), action: "") == .agenda(nil))
        #expect(NoticeOpen.resolve(Notice(.BRIEF, "t", "x", tag: "brief"), action: "") == .chat(prefill: nil))
        // eTL이 아닌 주소는 열지 않는다.
        #expect(NoticeOpen.resolve(Notice(.DEADLINE, "t", "x", url: "https://evil.example.com", tag: "a"), action: "") == .chat(prefill: nil))
    }

    @Test func missingButtonFallsBackToBody() {
        let n = Notice(.DEADLINE, "t", "x", tag: "a", agenda: true, mineItemId: 3)
        #expect(NoticeOpen.resolve(n, action: NoticeOpen.ACTION_DRAFT) == .agenda(3))
        #expect(NoticeOpen.resolve(n, action: NoticeOpen.ACTION_ETL) == .agenda(3))
    }

    @Test func categoryIdDependsOnButtonsAndLockScreen() {
        let plain = Notice(.BRIEF, "t", "x", tag: "brief")
        #expect(NoticeOpen.categoryId(NotificationPoster.actions(plain), hideOnLockScreen: false) == nil)
        #expect(NoticeOpen.categoryId(NotificationPoster.actions(plain), hideOnLockScreen: true) == "ym.hidden:")
        let n = Notice(.ANNOUNCEMENT, "t", "x", url: url, tag: "a", draft: "{}")
        let id = NoticeOpen.categoryId(NotificationPoster.actions(n), hideOnLockScreen: false)
        #expect(id == "ym:ym.draft=내 일정에 등록|ym.etl=eTL에서 열기")
        #expect(id != NoticeOpen.categoryId(NotificationPoster.actions(n), hideOnLockScreen: true))
    }
}

struct ScheduledNoticesTests {
    func entry(_ id: String, _ at: Int64, keys: [String] = [], channel: Channel = .DEADLINE) -> ScheduledNotices.Entry {
        ScheduledNotices.Entry(id, at, Notice(channel, "t", "x", tag: id, firedKeys: keys))
    }

    @Test func passedAndNotPendingCountsAsFired() {
        let list = [entry("b", 200), entry("a", 100), entry("p", 150), entry("f", 900)]
        let r = ScheduledNotices.resolve(list, pendingIds: ["p", "f"], nowMs: 500)
        #expect(r.fired.map(\.id) == ["a", "b"])
        #expect(r.keep.map(\.id) == ["p", "f"])
    }

    @Test func applyRecordsKeysAtFireTimeAndBriefTime() {
        var st = NotifyState(fired: ["k1": 50], lastBriefAt: 10)
        ScheduledNotices.apply([entry("a", 100, keys: ["k1", "k2"]), entry("brief:300", 300, channel: .BRIEF)], to: &st)
        #expect(st.fired["k1"] == 50)
        #expect(st.fired["k2"] == 100)
        #expect(st.lastBriefAt == 300)
    }

    @Test func jsonRoundTrip() {
        let list = [entry("timed:a", 100, keys: ["x"]), entry("brief:2", 200, channel: .BRIEF)]
        #expect(ScheduledNotices.fromJson(ScheduledNotices.toJson(list)) == list)
        #expect(ScheduledNotices.fromJson("oops").isEmpty)
    }
}

struct BriefForecastTests {
    @Test func slotsWithinHorizon() {
        let s = NotifySettings(briefEnabled: true, briefMinutesOfDay: [8 * 60, 20 * 60])
        let now = kst("2026-11-25T10:00")
        #expect(BriefForecast.upcomingSlots(s, now, seoul) ==
                [kst("2026-11-25T20:00"), kst("2026-11-26T08:00"), kst("2026-11-26T20:00"), kst("2026-11-27T08:00")])
        #expect(BriefForecast.upcomingSlots(s, now, seoul, horizonMillis: 3600 * 1000).isEmpty)
        #expect(BriefForecast.upcomingSlots(s, kst("2026-11-25T07:30"), seoul, horizonMillis: 3600 * 1000) == [kst("2026-11-25T08:00")])
        var off = s
        off.briefEnabled = false
        #expect(BriefForecast.upcomingSlots(off, now, seoul).isEmpty)
    }

    @Test func offlineBriefNeedsTokenOrMine() {
        let stores = NotifyStores.inMemory()
        stores.settings.save(NotifySettings(briefEnabled: true))
        #expect(BriefForecast.offline(stores, at: kst("2026-11-25T08:00"), zone: seoul, hasToken: false) == nil)
        stores.mine.save([MyDeadline(1, "보고서", kst("2026-11-26T23:59"))])
        let n = BriefForecast.offline(stores, at: kst("2026-11-25T08:00"), zone: seoul, hasToken: false)
        #expect(n?.channel == .BRIEF)
        #expect(n?.tag == "brief")
        #expect(n?.bigText?.contains("보고서") == true)
    }
}
