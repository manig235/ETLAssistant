import Foundation

/// 비서 세션 상태(Kotlin `codex.SessionState`). iOS에서는 Codex 프로세스 대신 인증·모델 목록 준비 상태를 뜻한다.
public enum SessionState: String, Sendable, Hashable, CaseIterable {
    case stopped = "STOPPED", starting = "STARTING", ready = "READY", failed = "FAILED"
}

/// 로그인한 계정(Kotlin `codex.AccountInfo`).
public struct AccountInfo: Sendable, Hashable {
    public enum Kind: String, Sendable, Hashable { case none = "NONE", chatgpt = "CHATGPT", apiKey = "API_KEY", unknown = "UNKNOWN" }
    public var kind: Kind
    public var email: String?
    public var plan: String?

    public init(_ kind: Kind, email: String? = nil, plan: String? = nil) {
        self.kind = kind
        self.email = email
        self.plan = plan
    }

    public var loggedIn: Bool { kind != .none }
}

/// 진행 중인 대화 턴의 바깥 이벤트 이름 모음은 `ResponsesEvent`(엔진)에 있다.
/// Kotlin `SessionEvent` 중 화면이 쓰던 것: State/Account/Busy/Activity/Models/LoginDone → iOS 앱 계층이 상태로 들고 있는다.
public enum SessionEvent: Sendable {
    case state(SessionState, detail: String?)
    case account(AccountInfo)
    case busy(Bool)
    /// 답변을 만드는 동안 "지금 하는 일" 한 줄(`조회하는 중…` 등). nil이면 숨긴다.
    case activity(String?)
    case models([ModelInfo], loading: Bool)
    case loginDone(success: Bool, error: String?)
    case loginIdle
}
