import SwiftUI

/// 온보딩 `Card`: 가로 꽉 참, 모서리 16, surfaceNeutralSecondary, 안쪽 16.
struct YMCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
    }
}

/// 온보딩 `StatusCard(text, positive)`. 실패여도 같은 체크 아이콘(원본 그대로).
struct YMStatusCard: View {
    let text: String
    var positive = true

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle")
                .font(.system(size: 20))
                .foregroundStyle(positive ? YMColor.positiveForeground : YMColor.negativeForeground)
                .frame(width: 22, height: 22)
            Text(text)
                .font(YMFont.body1Medium)
                .foregroundStyle(YMColor.label)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(positive ? YMColor.positiveSurface : YMColor.negativeSurface))
    }
}

/// Montage `WantedChip(text, isActive)`: 높이 ≈32, 모서리 8. 활성 = 반전 채움 + 반전 글씨(추정).
struct YMChip: View {
    let text: String
    var isActive = false
    let action: () -> Void

    init(_ text: String, isActive: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.isActive = isActive
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(YMFont.label1Medium)
                .foregroundStyle(isActive ? YMColor.foregroundInverse : YMColor.label)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(isActive ? YMColor.surfaceInverse : YMColor.background)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(isActive ? Color.clear : YMColor.line, lineWidth: 1)
                )
                .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(YMPressStyle())
    }
}

/// Montage `WantedTextField(title, description, trailingButton, status)`. 비밀값이면 `secure`.
struct YMTextField: View {
    let title: String
    let placeholder: String
    @Binding var text: String
    var secure = false
    var description: String?
    var isError = false
    var trailingTitle: String?
    var trailingAction: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(YMFont.label1Bold)
                .foregroundStyle(YMColor.label)
            HStack(spacing: 8) {
                Group {
                    if secure {
                        SecureField(placeholder, text: $text)
                    } else {
                        TextField(placeholder, text: $text)
                    }
                }
                .font(YMFont.body1Regular)
                .foregroundStyle(YMColor.label)
                .textContentType(nil)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                if let trailingTitle, let trailingAction {
                    Button(trailingTitle, action: trailingAction)
                        .font(YMFont.body2Bold)
                        .foregroundStyle(YMColor.brandPrimary)
                        .buttonStyle(YMPressStyle())
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 48)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(isError ? YMColor.negativeForeground : YMColor.line, lineWidth: 1)
            )
            if let description {
                Text(description)
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(isError ? YMColor.negativeForeground : YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// Montage `WantedLinearProgressIndicator`: 높이 4, 둥근 끝.
struct YMLinearProgress: View {
    let value: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(YMColor.surfaceTertiary)
                Capsule().fill(YMColor.brandPrimary)
                    .frame(width: geo.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 4)
        .animation(.easeOut(duration: 0.22), value: value)
    }
}

/// 원본 상단바처럼 왼쪽에 뒤로 화살표 + 큰 제목(title2Bold)인 바. 높이 56.
struct YMBackTopBar: View {
    let title: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(YMColor.label)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(YMPressStyle())
            .accessibilityLabel("뒤로")
            Text(title)
                .font(YMFont.title2Bold)
                .foregroundStyle(YMColor.label)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .frame(height: 56)
    }
}
