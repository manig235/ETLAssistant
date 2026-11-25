import SwiftUI
import UIKit

/// OpenAI API 키 입력 시트(ChatGPT 로그인의 예비 방식). 온보딩과 설정에서 같이 쓴다.
/// 키로 모델 목록을 한 번 불러와 확인한 뒤 Keychain에 저장한다(`AssistantAuth.connectAPIKey`).
struct APIKeySheet: View {
    let auth: AssistantAuth
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var checking = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("API 키로 연결")
                    .font(YMFont.title2Bold)
                    .foregroundStyle(YMColor.label)
                Spacer()
                YMTextButton("닫기") { dismiss() }
            }
            .padding(.leading, 24)
            .padding(.trailing, 12)
            .padding(.top, 20)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("ChatGPT 로그인 대신 내 OpenAI API 키로 비서를 실행해요. 사용량만큼 OpenAI가 API 요금을 청구해요.")
                        .font(YMFont.body2Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    YMTextField(
                        title: "OpenAI API 키",
                        placeholder: "sk-로 시작하는 키를 붙여넣어 주세요",
                        text: $key,
                        secure: true,
                        description: error ?? "이 폰의 Keychain에 암호화해서 저장해요. 설정에서 언제든 삭제할 수 있어요.",
                        isError: error != nil,
                        trailingTitle: "붙여넣기",
                        trailingAction: {
                            let s = (UIPasteboard.general.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                            if !s.isEmpty { key = s }
                        }
                    )
                    .padding(.top, 20)
                }
                .padding(.horizontal, 24)
                .padding(.top, 12)
            }
            YMPrimaryButton("확인하고 저장", isLoading: checking) {
                checking = true
                error = nil
                Task {
                    let problem = await auth.connectAPIKey(key)
                    checking = false
                    if let problem { error = problem } else { dismiss() }
                }
            }
            .disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || checking)
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
        .background(YMColor.background.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
