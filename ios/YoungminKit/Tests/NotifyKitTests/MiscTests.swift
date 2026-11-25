import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct NotifyHistoryLogicTests {
    @Test func channelLabelKnowsCurrentAndLegacyIds() {
        // 팝업으로 올리며 ID를 바꾼 채널: 바꾸기 전에 남은 기록도 같은 이름으로 보여야 한다.
        #expect(NotifyHistoryLogic.channelLabel(Channel.ANNOUNCEMENT.id) == "공지")
        #expect(NotifyHistoryLogic.channelLabel("announcement") == "공지")
        #expect(NotifyHistoryLogic.channelLabel(Channel.CHANGE.id) == "과제·성적")
        #expect(NotifyHistoryLogic.channelLabel("change") == "과제·성적")
        #expect(NotifyHistoryLogic.channelLabel("deadline") == "마감")
        #expect(NotifyHistoryLogic.channelLabel("unknown") == "알림")
    }

    @Test func storeRecordsAndClears() {
        let h = NotifyHistory(InMemoryStore())
        h.record(Notice(.BRIEF, "t", "x", tag: "brief", draft: "{}"), nowMs: 1000)
        #expect(h.load() == [HistoryEntry(1000, "brief", "t", "x", nil, nil, "{}")])
        h.clear()
        #expect(h.load().isEmpty)
    }
}

@Suite struct LearningXLinkTests {
    @Test func preservesContentPathsAndEncodedQueries() {
        for path in ["", "courses/305930", "courses/305930/assignments/123", "courses/305930/discussion_topics/456", "courses/305930/files/789?wrap=1#preview"] {
            #expect(LearningXLink.fromWebUrl("https://myetl.snu.ac.kr/\(path)") == "canvas-student://myetl.snu.ac.kr/\(path)")
        }
    }

    @Test func routesBoardRedirectToSupportedToolAndPreservesPostQuery() {
        let url = "https://myetl.snu.ac.kr/learningx/redirect/courses/305930/external_tools/85?post_id=779536&title=%ED%95%99%EC%8A%B5#post"
        #expect(LearningXLink.fromWebUrl(url) == "canvas-student://myetl.snu.ac.kr/courses/305930/external_tools/85?post_id=779536&title=%ED%95%99%EC%8A%B5#post")
        #expect(LearningXLink.opensBoardList(url))
        #expect(!LearningXLink.opensBoardList("https://myetl.snu.ac.kr/courses/305930/assignments/1"))
        #expect(!LearningXLink.opensBoardList("https://evil.example/learningx/redirect/courses/305930/external_tools/85"))
    }

    @Test func normalizesEtlHostAndDefaultPortsWithoutChangingEncodedContent() {
        for base in ["https://MYETL.SNU.AC.KR:443", "http://myetl.snu.ac.kr:80", "http://myetl.snu.ac.kr"] {
            #expect(LearningXLink.fromWebUrl("\(base)/courses/1?title=a%2Fb#preview") == "canvas-student://myetl.snu.ac.kr/courses/1?title=a%2Fb#preview")
        }
        #expect(LearningXLink.webUrl("https://myetl.snu.ac.kr") == "https://myetl.snu.ac.kr/")
        #expect(LearningXLink.fromWebUrl("https://myetl.snu.ac.kr") == "canvas-student://myetl.snu.ac.kr/")
    }

    @Test func rejectsForeignHostsAndNonEtlSchemes() {
        let bad: [String?] = [
            nil, "", "https://evil.example/courses/1",
            "https://myetl.snu.ac.kr.evil.example/courses/1",
            "https://myetl.snu.ac.kr@evil.example/courses/1",
            "https://myetl.snu.ac.kr:8443/courses/1",
            "http://myetl.snu.ac.kr:443/courses/1",
            "https://user@myetl.snu.ac.kr/courses/1",
            "https://myetl.snu.ac.kr/courses/%invalid",
            "intent://myetl.snu.ac.kr/courses/1",
            "canvas-student://myetl.snu.ac.kr/courses/1",
            "https://myetl.snu.ac.kr/courses/1\n/2",
        ]
        for u in bad { #expect(LearningXLink.fromWebUrl(u) == nil, "\(u ?? "nil")") }
    }
}

@Suite struct NotifyUiFormatTests {
    @Test func formatTime12h() {
        #expect(NotifyUiFormat.formatTime(0, false) == "오전 12:00")
        #expect(NotifyUiFormat.formatTime(480, false) == "오전 8:00")
        #expect(NotifyUiFormat.formatTime(719, false) == "오전 11:59")
        #expect(NotifyUiFormat.formatTime(720, false) == "오후 12:00")
        #expect(NotifyUiFormat.formatTime(13 * 60 + 5, false) == "오후 1:05")
        #expect(NotifyUiFormat.formatTime(1439, false) == "오후 11:59")
    }

    @Test func formatTime24h() {
        #expect(NotifyUiFormat.formatTime(0, true) == "00:00")
        #expect(NotifyUiFormat.formatTime(480, true) == "08:00")
        #expect(NotifyUiFormat.formatTime(719, true) == "11:59")
        #expect(NotifyUiFormat.formatTime(720, true) == "12:00")
        #expect(NotifyUiFormat.formatTime(1439, true) == "23:59")
    }

    @Test func formatTimeClampsOutOfRange() {
        #expect(NotifyUiFormat.formatTime(-5, true) == "00:00")
        #expect(NotifyUiFormat.formatTime(5000, true) == "23:59")
    }

    @Test func normalizeBriefTimes() {
        #expect(NotifyUiFormat.normalizeBriefTimes([]) == [480])
        #expect(NotifyUiFormat.normalizeBriefTimes([480, 480]) == [480])
        #expect(NotifyUiFormat.normalizeBriefTimes([1200, 480]) == [480, 1200])
        #expect(NotifyUiFormat.normalizeBriefTimes([-10, 9999]) == [0, 1439])
        #expect(NotifyUiFormat.normalizeBriefTimes([300, 200, 100]) == [100, 200])
        // 보정 뒤 같아지면 하나로 합친다
        #expect(NotifyUiFormat.normalizeBriefTimes([1439, 2000]) == [1439])
    }

    @Test func describeLastCheckNever() {
        #expect(NotifyUiFormat.describeLastCheck(0, "성공") == "아직 확인한 적이 없어요")
    }

    @Test func describeLastCheckWithResult() {
        // 2026-11-19 14:10 UTC = 23:10 KST(목)
        let ms = instant("2026-11-19T14:10:00Z").epochMilli
        let s = NotifyUiFormat.describeLastCheck(ms, "성공 · 마감 알림 1건, 공지 알림 0건")
        #expect(s.hasPrefix("마지막 확인: "))
        #expect(s.contains("23:10"))
        #expect(s.hasSuffix(" · 성공 · 마감 알림 1건, 공지 알림 0건"))
        #expect(NotifyUiFormat.describeLastCheck(ms, "  ") == "마지막 확인: 11-19(목) 23:10")
    }

    @Test func describeLastBrief() {
        #expect(NotifyUiFormat.describeLastBrief(0) == "아직 브리핑을 보낸 적이 없어요")
        #expect(NotifyUiFormat.describeLastBrief(instant("2026-11-19T14:10:00Z").epochMilli) == "마지막 브리핑: 11-19(목) 23:10")
    }
}

@Suite struct StoresTests {
    @Test func settingsRoundTripAndLegacyWindows() {
        let prefs = InMemoryStore()
        let store = NotifySettingsStore(prefs)
        // 버전 표시가 없으면(옛 저장값) 기본 시점
        #expect(store.load() == NotifySettings())
        var s = NotifySettings(deadlineWindowsMinutes: [30, 4320], briefEnabled: true, briefMinutesOfDay: [480, 1200], quietEnabled: true, classAlerts: true, classLeadMinutes: 15)
        store.save(s)
        s.deadlineWindowsMinutes = [4320, 30]
        #expect(store.load() == s)
        // 버전 2: 시간 단위
        let old = InMemoryStore(["deadlineWindowsVersion": "2", "deadlineWindowsHours": "[72,1]"])
        #expect(NotifySettingsStore(old).load().deadlineWindowsMinutes == [4320, 60])
    }

    @Test func stateStoreUpdateAndPrune() {
        let st = NotifyStateStore(InMemoryStore())
        st.update { $0.fired["a"] = 1; $0.fired["b"] = 40 * 24 * 3600 * 1000; $0.lastResult = "성공" }
        var s = st.load()
        #expect(s.lastResult == "성공")
        s.prune(40 * 24 * 3600 * 1000)
        #expect(Set(s.fired.keys) == ["b"])
        #expect(NotifyState.fromJson("깨짐") == NotifyState())
    }

    @Test func myDeadlineStoreUpsertDeleteAndHook() {
        final class Counter: @unchecked Sendable { var n = 0 }
        let c = Counter()
        let store = MyDeadlineStore(InMemoryStore(), onChanged: { c.n += 1 })
        let now = instant("2026-11-19T03:00:00Z")
        store.upsert(MyDeadline(1, "a", now))
        store.upsert(MyDeadline(2, "b", now))
        store.upsert(MyDeadline(1, "a2", now))
        #expect(store.load().map(\.title) == ["b", "a2"])
        store.delete(2)
        #expect(store.load().map(\.id) == [1])
        #expect(c.n == 4)
    }

    @Test func courseFilterStoreKeepsNames() {
        let store = CourseFilterStore(InMemoryStore())
        store.setMode(CourseRef(1, "옛 이름"), .HIDE)
        store.saveKnown([CourseRef(1, "새 이름"), CourseRef(2, "다른 과목")], 99)
        let all = store.loadAll()
        #expect(all.filter.names[1] == "새 이름")
        #expect(all.filter.hidesTimetable(1))
        #expect(all.known.count == 2 && all.knownAt == 99)
        store.setHideTimetable(1, false)
        #expect(!store.load().hidesTimetable(1))
    }
}
