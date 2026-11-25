import Testing
@testable import ChatKit

// Kotlin ChatLogicTest
@Suite struct ChatLogicTests {

    // ---- 배너 ----

    @Test func bannerNoTokenComesFirst() {
        let b = ChatLogic.banner(hasToken: false, state: .failed, detail: "x", loggedIn: false)!
        #expect(b.action == .settings)
        #expect(b.text.contains("토큰"))
    }

    @Test func bannerFailedOffersRetry() {
        let b = ChatLogic.banner(hasToken: true, state: .failed, detail: "프로세스 종료", loggedIn: false)!
        #expect(b.action == .retry)
        #expect(b.text.contains("프로세스 종료"))
    }

    @Test func bannerFailedWithoutDetail() {
        #expect(ChatLogic.banner(hasToken: true, state: .failed, detail: nil, loggedIn: true) != nil)
    }

    @Test func bannerStartingIsInfoOnly() {
        for s in [SessionState.stopped, .starting] {
            #expect(ChatLogic.banner(hasToken: true, state: s, detail: nil, loggedIn: false)!.action == BannerAction.none)
        }
    }

    @Test func bannerNotLoggedInGoesToSettings() {
        #expect(ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: false)!.action == .settings)
    }

    @Test func bannerHiddenWhenAllGood() {
        #expect(ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true) == nil)
    }

    // ---- 전송 버튼 ----

    @Test func sendModes() {
        #expect(ChatLogic.sendMode(busy: true, input: "") == .stop)
        #expect(ChatLogic.sendMode(busy: true, input: "안녕") == .stop)
        #expect(ChatLogic.sendMode(busy: false, input: "  \n") == .disabled)
        #expect(ChatLogic.sendMode(busy: false, input: "안녕") == .send)
    }

    // ---- 마크다운 스팬 → 스타일 ----

    @Test func styleKinds() {
        let r = MarkdownLite.render("## 제목\n**굵게** 와 `코드` 와 [링크](https://a.kr/x)\n```\nblock\n```")
        var by: [MarkdownLite.Kind: MdStyle] = [:]
        for s in ChatLogic.styleRanges(r) { by[s.kind] = s }
        #expect(by[.bold]!.bold)
        #expect(by[.code]!.mono && by[.code]!.background)
        #expect(by[.codeBlock]!.mono && by[.codeBlock]!.sizeScale < by[.code]!.sizeScale)
        #expect(by[.header]!.bold && by[.header]!.sizeScale > 1)
        #expect(by[.link]!.url == "https://a.kr/x")
        #expect(r.text.substring16(by[.link]!.start, by[.link]!.end) == "링크")
    }

    @Test func rangesAreSortedAndClamped() {
        let r = MarkdownLite.Result("abcdef", [
            MarkdownLite.Span(3, 99, .bold),
            MarkdownLite.Span(0, 4, .code),
            MarkdownLite.Span(0, 6, .header),
            MarkdownLite.Span(5, 5, .bold), // 빈 구간
            MarkdownLite.Span(7, 9, .bold), // 글 밖
        ])
        let out = ChatLogic.styleRanges(r)
        #expect(out.map(\.kind) == [.header, .code, .bold])
        #expect(out.last!.end == 6)
    }

    @Test func nonHttpLinkIsDropped() {
        let r = MarkdownLite.Result("x", [MarkdownLite.Span(0, 1, .link, "javascript:alert(1)"), MarkdownLite.Span(0, 1, .link, nil)])
        #expect(ChatLogic.styleRanges(r).isEmpty)
    }

    @Test func nestedBoldInsideHeaderKeepsBoth() {
        let r = MarkdownLite.render("# 가 **나** 다")
        #expect(ChatLogic.styleRanges(r).map(\.kind) == [.header, .bold])
    }

    // ---- 알림 꺼짐 배너 ----

    private let day: Int64 = 24 * 3600 * 1000

    @Test func notifyBannerHasLowestPriority() {
        let p = NotifyProblem.appOff
        #expect(ChatLogic.banner(hasToken: false, state: .ready, detail: nil, loggedIn: true, notifyProblem: p)!.action == .settings)
        #expect(ChatLogic.banner(hasToken: true, state: .failed, detail: nil, loggedIn: true, notifyProblem: p)!.action == .retry)
        #expect(ChatLogic.banner(hasToken: true, state: .starting, detail: nil, loggedIn: true, notifyProblem: p)!.action == BannerAction.none)
        #expect(ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: false, notifyProblem: p)!.action == .settings)
        let b = ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true, notifyProblem: p)!
        #expect(b.action == .notifications)
        #expect(b.dismissible)
        #expect(ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true, notifyProblem: nil) == nil)
    }

    @Test func notifyBannerSnoozesFor7Days() {
        let p = NotifyProblem.appOff
        let now = 100 * day
        #expect(ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true, notifyProblem: p, notifyDismissedAt: now - 6 * day, nowMs: now) == nil)
        #expect(ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true, notifyProblem: p, notifyDismissedAt: now - 7 * day, nowMs: now) != nil)
        #expect(ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true, notifyProblem: p, notifyDismissedAt: 0, nowMs: now) != nil)
    }

    @Test func notifyProblemDetection() {
        #expect(ChatLogic.notifyProblem(appEnabled: false, usedChannels: [], blockedChannels: []) == nil)
        #expect(ChatLogic.notifyProblem(appEnabled: false, usedChannels: ["deadline"], blockedChannels: []) == .appOff)
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: ["deadline", "announcement"], blockedChannels: ["announcement", "brief"]) == .channelOff("announcement"))
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: ["deadline"], blockedChannels: ["brief"]) == nil)
    }

    @Test func batteryOptimizedIsLowestPriority() {
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: ["deadline"], blockedChannels: [], batteryOptimized: true) == .batteryOptimized)
        #expect(ChatLogic.notifyProblem(appEnabled: false, usedChannels: ["deadline"], blockedChannels: [], batteryOptimized: true) == .appOff)
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: ["deadline"], blockedChannels: ["deadline"], batteryOptimized: true) == .channelOff("deadline"))
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: [], blockedChannels: [], batteryOptimized: true) == nil)
        let b = ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true, notifyProblem: .batteryOptimized)!
        #expect(b.text.hasPrefix("배터리 최적화"))
        #expect(b.dismissible)
    }

    @Test func powerSaveWarnsOnlyWhenNotExempt() {
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: ["deadline"], blockedChannels: [], batteryOptimized: true, powerSave: true) == .powerSave)
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: ["deadline"], blockedChannels: [], batteryOptimized: false, powerSave: true) == nil)
        #expect(ChatLogic.notifyProblem(appEnabled: true, usedChannels: ["deadline"], blockedChannels: ["deadline"], batteryOptimized: true, powerSave: true) == .channelOff("deadline"))
        let b = ChatLogic.banner(hasToken: true, state: .ready, detail: nil, loggedIn: true, notifyProblem: .powerSave)!
        #expect(b.text.hasPrefix("절전 모드"))
        #expect(b.dismissible)
        #expect(b.action == .notifications)
    }
}
