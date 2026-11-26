import Foundation

/// 위쪽 배너를 눌렀을 때 할 일(Kotlin `BannerAction`).
/// `openChat`: 다른 대화로 옮긴다(그 대화의 확인 창).
public enum BannerAction: String, Sendable, Hashable { case settings = "SETTINGS", retry = "RETRY", notifications = "NOTIFICATIONS", openChat = "OPEN_CHAT", none = "NONE" }

/// `dismissible`이면 ×(나중에)로 닫을 수 있다(Kotlin `BannerSpec`).
public struct BannerSpec: Sendable, Hashable {
    public var text: String
    public var action: BannerAction
    public var dismissible: Bool
    public init(_ text: String, _ action: BannerAction, dismissible: Bool = false) {
        self.text = text
        self.action = action
        self.dismissible = dismissible
    }
}

/// 알림이 오지 않는 이유(Kotlin `NotifyProblem`). `appOff`는 권한이나 앱 전체 알림이 꺼짐, `channelOff`는 쓰는 채널 하나가 꺼짐.
/// iOS에는 배터리 최적화·절전 모드 대신 저전력 모드/백그라운드 새로 고침이 있지만, 판정 규칙은 원본대로 둔다.
public enum NotifyProblem: Sendable, Hashable {
    case appOff
    case channelOff(String)
    /// 알림은 켜져 있지만 배터리 최적화 대상이라 앱을 닫으면 확인이 늦거나 안 될 수 있다.
    case batteryOptimized
    /// 배터리 최적화 대상인데 절전 모드까지 켜져 있다.
    case powerSave
}

/// 전송 버튼의 모습: 보내기 / 답변 중단 / 비활성(Kotlin `SendMode`).
public enum SendMode: String, Sendable, Hashable { case send = "SEND", stop = "STOP", disabled = "DISABLED" }

/// 마크다운 한 구간을 화면에서 어떻게 그릴지(Kotlin `MdStyle`). 위치는 UTF-16.
public struct MdStyle: Sendable, Hashable {
    public var start: Int
    public var end: Int
    public var kind: MarkdownLite.Kind
    public var bold: Bool
    /// 기울임(iOS에서 더함, `*…*`·`_…_`).
    public var italic: Bool
    public var mono: Bool
    public var background: Bool
    public var sizeScale: Double
    public var url: String?

    public init(_ start: Int, _ end: Int, _ kind: MarkdownLite.Kind, bold: Bool, italic: Bool = false, mono: Bool, background: Bool, sizeScale: Double, url: String?) {
        self.start = start
        self.end = end
        self.kind = kind
        self.bold = bold
        self.italic = italic
        self.mono = mono
        self.background = background
        self.sizeScale = sizeScale
        self.url = url
    }
}

/// 채팅 화면의 순수 판정 로직(Kotlin `ChatLogic`).
public enum ChatLogic {

    /// 알림 꺼짐 배너를 닫으면 이 기간 동안 숨긴다(밀리초).
    public static let NOTIFY_BANNER_SNOOZE_MS: Int64 = 7 * 24 * 3600 * 1000

    /// 지금 상태에 맞는 안내 배너. 우선순위: 토큰 없음 → 세션 실패 → 준비 중 → 로그인 안 됨 → 알림 꺼짐. 보여 줄 게 없으면 nil.
    public static func banner(
        hasToken: Bool,
        state: SessionState,
        detail: String?,
        loggedIn: Bool,
        notifyProblem: NotifyProblem? = nil,
        notifyDismissedAt: Int64 = 0,
        nowMs: Int64 = 0
    ) -> BannerSpec? {
        if !hasToken { return BannerSpec("eTL 토큰이 필요해요. 눌러서 설정으로 이동", .settings) }
        if state == .failed { return BannerSpec("비서를 시작하지 못했어요: \(detail ?? "")\n눌러서 다시 시도", .retry) }
        if state != .ready { return BannerSpec("비서를 준비하는 중이에요…", .none) }
        if !loggedIn { return BannerSpec("로그인이 필요해요. 눌러서 설정으로 이동", .settings) }
        if let p = notifyProblem, !(notifyDismissedAt > 0 && nowMs - notifyDismissedAt < NOTIFY_BANNER_SNOOZE_MS) {
            let text: String
            switch p {
            case .batteryOptimized: text = "배터리 최적화 때문에 앱을 닫아 두면 알림이 늦거나 안 올 수 있어요. 눌러서 해제"
            case .powerSave: text = "절전 모드에서는 새 과제·공지 알림이 늦을 수 있어요(받아 둔 마감 알림은 와요). 눌러서 배터리 제한 풀기"
            default: text = "알림이 꺼져 있어서 마감·공지 알림이 오지 않아요. 눌러서 켜기"
            }
            return BannerSpec(text, .notifications, dismissible: true)
        }
        return nil
    }

    /// 켜 둔 알림(마감/공지/브리핑)이 실제로 올 수 있는지. 하나도 켜지 않았으면 nil.
    public static func notifyProblem(
        appEnabled: Bool,
        usedChannels: [String],
        blockedChannels: Set<String>,
        batteryOptimized: Bool = false,
        powerSave: Bool = false
    ) -> NotifyProblem? {
        if usedChannels.isEmpty { return nil }
        if !appEnabled { return .appOff }
        if let c = usedChannels.first(where: { blockedChannels.contains($0) }) { return .channelOff(c) }
        if batteryOptimized && powerSave { return .powerSave }
        if batteryOptimized { return .batteryOptimized }
        return nil
    }

    /// 글이 비어 있어도 첨부가 있으면 보낼 수 있다. 첨부를 준비하는 중이면 기다린다.
    public static func sendMode(busy: Bool, input: String, hasAttachments: Bool = false, importing: Bool = false) -> SendMode {
        if busy { return .stop }
        if importing { return .disabled }
        if T16.isBlank(input.utf16) && !hasAttachments { return .disabled }
        return .send
    }

    /// `MarkdownLite` 스팬을 그리기용 구간으로 바꾼다. 글 길이를 넘는 구간은 잘라 내고, 빈 구간과 주소 없는 링크는 버린다.
    /// 시작 위치 순(같으면 긴 구간 먼저)으로 돌려줘서, 겹치면 나중 것이 위에 덧입혀진다.
    public static func styleRanges(_ r: MarkdownLite.Result) -> [MdStyle] {
        let len = r.text.length16
        let out: [MdStyle] = r.spans.compactMap { s in
            let start = max(s.start, 0)
            let end = min(s.end, len)
            if end <= start { return nil }
            switch s.kind {
            case .bold: return MdStyle(start, end, s.kind, bold: true, mono: false, background: false, sizeScale: 1, url: nil)
            case .italic: return MdStyle(start, end, s.kind, bold: false, italic: true, mono: false, background: false, sizeScale: 1, url: nil)
            case .code: return MdStyle(start, end, s.kind, bold: false, mono: true, background: true, sizeScale: 0.92, url: nil)
            case .codeBlock: return MdStyle(start, end, s.kind, bold: false, mono: true, background: true, sizeScale: 0.88, url: nil)
            case .header: return MdStyle(start, end, s.kind, bold: true, mono: false, background: false, sizeScale: 1.1, url: nil)
            case .math, .mathBlock: return nil // 수식은 화면 쪽에서 그림으로 바꾼다
            case .link:
                guard let url = s.url, url.hasPrefix("https://") || url.hasPrefix("http://") else { return nil }
                return MdStyle(start, end, s.kind, bold: false, mono: false, background: false, sizeScale: 1, url: url)
            }
        }
        return stableSorted(out) { a, b in a.start != b.start ? a.start < b.start : a.end > b.end }
    }
}
