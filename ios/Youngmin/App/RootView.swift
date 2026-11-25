import SwiftUI

/// 앱 뿌리: 시작 화면(남색 + 로봇) → 첫 실행 안내(`OnboardingView`) 또는 채팅(`ChatView`). 전환은 페이드.
struct RootView: View {
    @Environment(AppState.self) private var app

    var body: some View {
        ZStack {
            switch app.phase {
            case .splash:
                SplashView()
                    .transition(.opacity)
                    .task {
                        // 런치 스크린과 같은 모습을 잠깐 보인 뒤 넘어간다(Android는 첫 Compose 프레임까지).
                        try? await Task.sleep(nanoseconds: 350_000_000)
                        app.leaveSplash()
                    }
            case .onboarding:
                OnboardingView()
                    .transition(.opacity)
            case .chat:
                ChatView(controller: app.chatController())
                    .transition(.opacity)
            }
        }
        .toastOverlay()
    }
}

/// 시작 화면: 전체 `brand_navy #181B35`, 가운데 로봇 약 144pt(로고 타일도 남색이라 경계가 보이지 않는다).
struct SplashView: View {
    var body: some View {
        ZStack {
            YMColor.brandNavy.ignoresSafeArea()
            Image("RobotLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 144, height: 144)
                .accessibilityLabel("영민이 로고")
        }
    }
}
