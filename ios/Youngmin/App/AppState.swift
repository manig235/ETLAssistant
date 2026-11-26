import ChatKit
import ETLKit
import SwiftUI

/// 앱 최상위 상태: 시작 화면 → 첫 실행 안내 또는 채팅. 비서 도구·승인 게이트를 끼우는 곳.
@MainActor
@Observable
final class AppState {
    enum Phase: Equatable { case splash, onboarding, chat }

    private(set) var phase: Phase = .splash
    let auth: AssistantAuth
    let toolbox: AssistantToolbox
    let approvals: WriteApprovalSource
    /// 채팅 화면 상태. 앱이 살아 있는 동안 하나.
    private(set) var chat: ChatController?
    /// 알림을 눌렀을 때 채팅 화면이 열 경로(nil이면 채팅 뿌리). 값이 바뀔 때마다 `openRequest`가 늘어난다.
    private(set) var openRoute: AppRoute?
    private(set) var openRequest = 0

    /// 알림 탭: 채팅 입력창을 채우고(보내지 않음) 채팅 뿌리 또는 지정한 화면으로 간다.
    func openFromNotification(prefill: String?, route: AppRoute?) {
        if let prefill, !prefill.isEmpty { chatController().input = prefill }
        openRoute = route
        openRequest += 1
    }
    /// 답변 완료 알림: 그 대화를 열고 채팅 뿌리로 간다.
    func openChat(_ id: String) {
        chatController().openConversation(id)
        openRoute = nil
        openRequest += 1
    }
    /// eTL 토큰 저장 여부(배너·온보딩 분기). 토큰을 바꾸는 화면은 `refreshTokenState()`를 부른다.
    private(set) var hasEtlToken: Bool

    /// - Parameters:
    ///   - toolbox: AssistantKit의 도구·지침. 기본은 도구 없음.
    ///   - approvals: AssistantKit의 쓰기 승인 게이트. 기본은 요청 없음.
    init(toolbox: AssistantToolbox = EmptyAssistantToolbox(), approvals: WriteApprovalSource = NoWriteApprovals()) {
        self.toolbox = toolbox
        self.approvals = approvals
        self.auth = AssistantAuth()
        self.hasEtlToken = AppStores.tokens.hasToken()
    }

    var onboardingCompleted: Bool {
        get { AppStores.onboarding.bool("completed", default: false) }
        set { AppStores.onboarding.set(newValue, for: "completed") }
    }

    func refreshTokenState() {
        hasEtlToken = AppStores.tokens.hasToken()
    }

    /// 첫 프레임 뒤 한 번: `OnboardingLogic.shouldSkip(completed, hasEtlToken)`이면 채팅, 아니면 안내.
    func leaveSplash() {
        guard phase == .splash else { return }
        refreshTokenState()
        withAnimation(.easeOut(duration: 0.25)) {
            phase = OnboardingLogic.shouldSkip(completed: onboardingCompleted, hasEtlToken: hasEtlToken) ? .chat : .onboarding
        }
        if phase == .chat { _ = chatController() }
    }

    /// 채팅 상태를 만들거나 돌려준다.
    func chatController() -> ChatController {
        if let chat { return chat }
        let c = ChatController(auth: auth, toolbox: toolbox, approvals: approvals)
        chat = c
        return c
    }

    /// 안내 완료(Android `OnboardingActivity.finish`): 입력창에 질문을 채운 채 채팅으로(자동 전송 안 함).
    func finishOnboarding(prefill: String?, newChat: Bool = true) {
        onboardingCompleted = true
        refreshTokenState()
        let c = chatController()
        if newChat { c.newConversation() }
        if let prefill { c.input = prefill }
        withAnimation(.easeOut(duration: 0.25)) { phase = .chat }
    }

    /// 설정의 "첫 실행 안내 다시 보기" 등에서 쓴다.
    func showOnboarding() {
        withAnimation(.easeOut(duration: 0.25)) { phase = .onboarding }
    }
}
