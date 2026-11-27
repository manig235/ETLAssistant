import SwiftUI

/// 첫 실행 안내(Kotlin `OnboardingRoot`). 한 화면 안에서 단계를 바꾼다(NavigationStack 없음, 페이드 220ms).
struct OnboardingView: View {
    @Environment(AppState.self) private var app
    @State private var model = OnboardingModel()
    @State private var showPrivacy = false

    var body: some View {
        VStack(spacing: 0) {
            topProgress
            ZStack {
                switch model.step {
                case .welcome: OnboardingWelcomeStep(model: model, showPrivacy: $showPrivacy)
                case .token: OnboardingTokenStep(model: model)
                case .login: OnboardingLoginStep(model: model)
                case .notify: OnboardingNotifyStep(model: model)
                case .done: OnboardingDoneStep(model: model)
                }
            }
            .id(model.step)
            .transition(.opacity)
        }
        .background(YMColor.background.ignoresSafeArea())
        .fullScreenCover(isPresented: $showPrivacy) {
            PrivacyPolicyView(onClose: { showPrivacy = false })
        }
    }

    /// 상단 진행줄(높이 56, 좌우 8): [이전 48] [진행바] [빈칸 48].
    private var topProgress: some View {
        HStack(spacing: 0) {
            if model.step == .welcome {
                Color.clear.frame(width: 48, height: 48)
            } else {
                Button { model.goBack() } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(YMColor.label)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(YMPressStyle())
                .accessibilityLabel("이전")
            }
            YMLinearProgress(value: OnboardingLogic.progress(model.step))
                .padding(.horizontal, 8)
                .accessibilityLabel("진행")
                .accessibilityValue("\(model.step.rawValue + 1) / \(OnboardingLogic.steps.count)")
            Color.clear.frame(width: 48, height: 48)
        }
        .padding(.horizontal, 8)
        .frame(height: 56)
        .ymReadableWidth(YMLayout.onboardingWidth + 64)
    }
}

/// 단계 틀(Kotlin `StepFrame`): 스크롤 영역(좌우 24) = 위 12 → header → 제목 → 부제 → 28 → 내용 → 24,
/// 그 아래 스크롤 밖 고정 하단 버튼(좌우 24, 위아래 16, 버튼 사이 6).
struct OnboardingStepFrame<Header: View, Content: View, Buttons: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var header: Header
    @ViewBuilder var content: Content
    @ViewBuilder var buttons: Buttons

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: 12)
                header
                Text(title)
                    .font(YMFont.title1Bold)
                    .foregroundStyle(YMColor.label)
                    .fixedSize(horizontal: false, vertical: true)
                if let subtitle {
                    Text(subtitle)
                        .font(YMFont.body1Reading)
                        .lineSpacing(YMFont.readingLineSpacing)
                        .foregroundStyle(YMColor.labelSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                }
                Spacer().frame(height: 28)
                content
                Spacer().frame(height: 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .ymReadableWidth(YMLayout.onboardingWidth)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 6) { buttons }
                .padding(.horizontal, 24)
                .ymReadableWidth(YMLayout.onboardingWidth)
                .padding(.vertical, 16)
                .background(YMColor.background)
        }
    }
}

extension OnboardingStepFrame where Header == EmptyView {
    init(title: String, subtitle: String? = nil, @ViewBuilder content: () -> Content, @ViewBuilder buttons: () -> Buttons) {
        self.title = title
        self.subtitle = subtitle
        self.header = EmptyView()
        self.content = content()
        self.buttons = buttons()
    }
}
