import SwiftUI

/// 3. ChatGPT 로그인(Kotlin `LoginStep`). iOS는 Codex 세션 대신 "ChatGPT로 계속하기"(SIWC) + 예비로 API 키.
/// Android의 "준비 중/세션 실패" 분기는 iOS에서 세션이 따로 없어 생기지 않고, 장치 코드 방식 대신 "API 키로 연결"을 둔다.
struct OnboardingLoginStep: View {
    let model: OnboardingModel
    @Environment(AppState.self) private var app
    @State private var showAPIKey = false

    private var auth: AssistantAuth { app.auth }

    var body: some View {
        OnboardingStepFrame(
            title: "ChatGPT 계정으로\n로그인해요",
            subtitle: "AI 비서는 내 ChatGPT 계정으로 실행돼요. 별도의 API 요금은 없고, 내 플랜의 사용량 안에서 쓰여요."
        ) {
            if auth.loginInProgress {
                YMCard {
                    HStack(spacing: 12) {
                        ProgressView().tint(YMColor.brandPrimary)
                        Text("브라우저에서 로그인을 마쳐 주세요")
                            .font(YMFont.body1Medium)
                            .foregroundStyle(YMColor.label)
                    }
                    Text("승인하면 이 화면이 자동으로 이어져요.")
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .padding(.top, 8)
                }
            } else if auth.isSignedIn {
                YMStatusCard(text: "로그인됐어요 · \(auth.accountLabel)", positive: true)
                Text("다른 계정을 쓰고 싶으면 아래 '다른 계정으로 로그인'을 눌러요. 브라우저에 이미 로그인된 계정이 있으면 그 계정이 먼저 선택될 수 있어요. 원하는 계정이 아니면 로그인 화면에서 계정을 바꿔 주세요.")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 12)
            } else {
                YMCard {
                    Text("브라우저로 로그인하면 코드를 입력할 필요 없이 승인만 누르면 돼요.")
                        .font(YMFont.body2Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let message = auth.loginMessage {
                Text(message)
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.negativeForeground)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 12)
            }
        } buttons: {
            if auth.loginInProgress {
                YMOutlineButton("브라우저 다시 열기") { auth.reopenBrowser() }
                YMTextButton("브라우저가 안 되나요? API 키로 연결", fullWidth: true) {
                    auth.cancelLogin()
                    showAPIKey = true
                }
                YMTextButton("취소", fullWidth: true) { auth.cancelLogin() }
            } else if auth.isSignedIn {
                YMPrimaryButton("다음") { model.goNext() }
                if auth.account.kind == .chatgpt {
                    YMTextButton("다른 계정으로 로그인", fullWidth: true) { auth.startChatGPTLogin(switchAccount: true) }
                } else {
                    YMTextButton("ChatGPT로 로그인", fullWidth: true) { auth.startChatGPTLogin() }
                }
            } else {
                YMPrimaryButton("ChatGPT로 계속하기") { auth.startChatGPTLogin() }
                YMTextButton("API 키로 연결", fullWidth: true) { showAPIKey = true }
                YMTextButton("나중에 할게요", fullWidth: true) { model.goNext() }
            }
        }
        .sheet(isPresented: $showAPIKey) {
            APIKeySheet(auth: auth)
        }
        .task { await auth.refreshAccount() }
    }
}
