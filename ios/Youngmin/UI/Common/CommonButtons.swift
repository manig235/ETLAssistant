import SwiftUI

/// Montage `WantedButton(PRIMARY, LARGE, SOLID)`: 높이 48, 모서리 12, 브랜드 채움, 흰 글씨. `isLoading`이면 글자 자리에 흰 스피너.
struct YMPrimaryButton: View {
    let title: String
    var isLoading = false
    let action: () -> Void

    init(_ title: String, isLoading: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.isLoading = isLoading
        self.action = action
    }

    var body: some View {
        Button(action: action) { Text(title) }
            .buttonStyle(YMSolidButtonStyle(isLoading: isLoading))
    }
}

struct YMSolidButtonStyle: ButtonStyle {
    var isLoading = false
    var small = false

    func makeBody(configuration: Configuration) -> some View {
        SolidBody(configuration: configuration, isLoading: isLoading, small: small)
    }

    private struct SolidBody: View {
        let configuration: Configuration
        let isLoading: Bool
        let small: Bool
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            ZStack {
                configuration.label
                    .font(small ? YMFont.label2Bold : YMFont.body1Bold)
                    .opacity(isLoading ? 0 : 1)
                if isLoading { ProgressView().tint(.white) }
            }
            .foregroundStyle(isEnabled ? Color.white : YMColor.disableForeground)
            .frame(maxWidth: small ? nil : .infinity)
            .padding(.horizontal, small ? 14 : 20)
            .frame(height: small ? 32 : 48)
            .background(
                RoundedRectangle(cornerRadius: small ? 8 : 12)
                    .fill(isEnabled ? YMColor.brandPrimary : YMColor.disableSurface)
            )
            .opacity(configuration.isPressed ? 0.85 : 1)
            .contentShape(RoundedRectangle(cornerRadius: small ? 8 : 12))
        }
    }
}

/// Montage `WantedButton(ASSISTIVE, LARGE, OUTLINED)`: 높이 48, 모서리 12, 1pt 중립 선.
struct YMOutlineButton: View {
    let title: String
    let action: () -> Void

    init(_ title: String, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(YMFont.body1Bold)
                .foregroundStyle(YMColor.label)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(YMColor.line, lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(YMPressStyle())
    }
}

/// Montage `WantedTextButton`: 배경 없이 브랜드 글씨, 최소 터치 높이 40.
struct YMTextButton: View {
    let title: String
    var fullWidth = false
    var alignment: Alignment = .center
    let action: () -> Void

    init(_ title: String, fullWidth: Bool = false, alignment: Alignment = .center, action: @escaping () -> Void) {
        self.title = title
        self.fullWidth = fullWidth
        self.alignment = alignment
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(YMFont.body2Bold)
                .foregroundStyle(YMColor.brandPrimary)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 40, alignment: alignment)
                .contentShape(Rectangle())
        }
        .buttonStyle(YMPressStyle())
    }
}

/// 눌림 효과만 있는 스타일.
struct YMPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// 원본 행(서랍 진입 행, 대화 행)처럼 눌렀을 때 옅은 배경이 깔리는 스타일.
struct YMRowPressStyle: ButtonStyle {
    var cornerRadius: CGFloat = 12
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(configuration.isPressed ? YMColor.label.opacity(0.08) : Color.clear)
            )
    }
}
