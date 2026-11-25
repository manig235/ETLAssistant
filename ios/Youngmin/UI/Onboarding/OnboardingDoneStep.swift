import SwiftUI

/// 5. 완료와 추천 질문(Kotlin `DoneStep`). 질문 카드를 누르면 그 질문을 입력창에 채운 채 채팅으로(자동 전송 안 함).
struct OnboardingDoneStep: View {
    let model: OnboardingModel
    @Environment(AppState.self) private var app
    @State private var suggestions = SuggestionQuestions.shared

    var body: some View {
        OnboardingStepFrame(
            title: "준비됐어요!",
            subtitle: "이렇게 물어볼 수 있어요. 눌러서 바로 시작해 보세요."
        ) {
            VStack(spacing: 10) {
                ForEach(Array(suggestions.questions.enumerated()), id: \.offset) { _, q in
                    SuggestionCard(icon: "bubble.left", text: q) { app.finishOnboarding(prefill: q) }
                }
            }
        } buttons: {
            YMPrimaryButton("비서 시작하기") { app.finishOnboarding(prefill: nil) }
        }
        .task { await suggestions.refresh() }
    }
}

/// 추천 질문 카드: 모서리 16, surfaceNeutralSecondary, 안쪽 16, 아이콘 20 브랜드 + 12 + 질문 body1Medium.
struct SuggestionCard: View {
    let icon: String
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundStyle(YMColor.brandPrimary)
                    .frame(width: 20, height: 20)
                Text(text)
                    .font(YMFont.body1Medium)
                    .foregroundStyle(YMColor.label)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(YMPressStyle())
    }
}
