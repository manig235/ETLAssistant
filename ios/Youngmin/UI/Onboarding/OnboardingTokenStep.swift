import SwiftUI
import UIKit

/// 2. eTL 토큰 연결(Kotlin `TokenStep`).
struct OnboardingTokenStep: View {
    @Bindable var model: OnboardingModel
    @Environment(AppState.self) private var app
    @Environment(\.openURL) private var openURL

    var body: some View {
        OnboardingStepFrame(
            title: "eTL에 연결할게요",
            subtitle: "eTL에서 내 토큰을 만들어 붙여넣으면, 앱이 eTL을 읽기 전용으로 조회해요. 제출이나 수정은 하지 않아요."
        ) {
            if case .saved(let name) = model.tokenState {
                YMStatusCard(text: name.map { "\($0)님으로 연결됐어요" } ?? "이미 연결돼 있어요", positive: true)
                Text("토큰은 이 폰에 암호화돼 저장됐어요. 이제 마감, 공지, 파일을 조회할 수 있어요.")
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 12)
            } else {
                YMCard {
                    VStack(alignment: .leading, spacing: 10) {
                        StepNumberRow(number: 1, text: "아래 버튼으로 eTL 설정 페이지를 열어요")
                        StepNumberRow(number: 2, text: "'새 액세스 토큰'을 만들어요 (용도는 아무거나)")
                        StepNumberRow(number: 3, text: "만들어진 토큰을 복사해서 아래에 붙여넣어요")
                    }
                }
                YMOutlineButton("eTL 설정 페이지 열기") {
                    if let url = URL(string: "https://myetl.snu.ac.kr/profile/settings") { openURL(url) }
                }
                .padding(.top, 12)
                YMTextField(
                    title: "eTL 토큰",
                    placeholder: "토큰을 붙여넣어 주세요",
                    text: $model.tokenInput,
                    secure: true,
                    description: model.tokenErrorMessage ?? "이 폰의 Keychain에 암호화해서 저장해요. 설정에서 언제든 삭제할 수 있어요.",
                    isError: model.tokenErrorMessage != nil,
                    trailingTitle: "붙여넣기",
                    trailingAction: paste
                )
                .padding(.top, 24)
                .onSubmit { if canCheck { model.checkAndSaveToken() } }
            }
        } buttons: {
            if model.tokenSaved {
                YMPrimaryButton("다음") {
                    app.refreshTokenState()
                    model.goNext()
                }
                YMTextButton("다른 토큰으로 바꾸기", fullWidth: true) { model.replaceToken() }
            } else {
                YMPrimaryButton("확인하고 저장", isLoading: model.tokenState == .checking) { model.checkAndSaveToken() }
                    .disabled(!canCheck)
                if model.hasStoredToken {
                    YMTextButton("기존 토큰 그대로 쓰기", fullWidth: true) { model.keepExistingToken() }
                }
            }
        }
    }

    private var canCheck: Bool {
        OnboardingLogic.isPlausibleEtlToken(model.tokenInput) && model.tokenState != .checking
    }

    /// 클립보드 첫 항목을 trim해서, 비어 있지 않으면 넣는다(iOS는 붙여넣기 허용 창이 뜰 수 있다).
    private func paste() {
        let s = (UIPasteboard.general.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.isEmpty { model.tokenInput = s }
    }
}

/// 번호 원 24×24 + 12 + 글(Kotlin `Step`).
struct StepNumberRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text("\(number)")
                .font(YMFont.caption1Bold)
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(Circle().fill(YMColor.brandPrimary))
            Text(text)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.label)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
