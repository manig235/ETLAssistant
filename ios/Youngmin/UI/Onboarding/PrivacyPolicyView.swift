import SwiftUI

/// 개인정보 처리방침(Kotlin `PrivacyPolicyActivity`). 번들 `privacy-policy.md`를 MarkdownLite로 그린다(네트워크 없음).
/// 온보딩에서는 `fullScreenCover`(`onClose`), 설정에서는 NavigationStack push(`onClose` 없이 dismiss)로 연다.
struct PrivacyPolicyView: View {
    var onClose: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var text = NSAttributedString()

    var body: some View {
        VStack(spacing: 0) {
            YMBackTopBar(title: "개인정보 처리방침") {
                if let onClose { onClose() } else { dismiss() }
            }
            YMSelectableText(text: text, scrollable: true, insets: UIEdgeInsets(top: 16, left: 24, bottom: 16, right: 24))
                .ymReadableWidth()
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task {
            if text.length == 0 { text = YMMarkdown.attributed(Self.loadPolicy()) }
        }
    }

    static func loadPolicy() -> String {
        guard let url = Bundle.main.url(forResource: "privacy-policy", withExtension: "md"),
              let s = try? String(contentsOf: url, encoding: .utf8) else {
            return "개인정보 처리방침을 불러오지 못했어요."
        }
        return s
    }
}
