import SwiftUI

// Kotlin `SettingsScreen.kt`의 공통 조각(Section, SettingsCard, StatusBanner, SwitchRow, LinkRow, EditableValue,
// RemovableChip, Caption, HyperLink, MainButton/SoftButton). 공용(Common)에 없는 모양만 여기 둔다.

/// 소제목(headline1Bold) + 12 + 카드들(12 간격).
struct SettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(YMFont.headline1Bold)
                .foregroundStyle(YMColor.label)
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(.top, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 상태 줄: 모서리 12, 톤별 배경, h14 v12, 아이콘 22(또는 스피너) + 10 + body2Medium.
struct SettingsStatusBanner: View {
    let text: String
    let tone: StatusTone
    var loading = false

    var body: some View {
        HStack(spacing: 10) {
            if loading {
                ProgressView().controlSize(.small).frame(width: 22, height: 22)
            } else {
                Image(systemName: icon)
                    .font(.system(size: 19))
                    .foregroundStyle(tint)
                    .frame(width: 22, height: 22)
            }
            Text(text)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.label)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 12).fill(background))
        .accessibilityElement(children: .combine)
    }

    private var icon: String {
        switch tone {
        case .positive: "checkmark.circle"
        case .negative: "exclamationmark.circle"
        case .caution: "exclamationmark.triangle"
        case .neutral: "info.circle"
        }
    }

    private var tint: Color {
        switch tone {
        case .positive: YMColor.positiveForeground
        case .negative: YMColor.negativeForeground
        case .caution: YMColor.cautionaryForeground
        case .neutral: YMColor.labelSecondary
        }
    }

    private var background: Color {
        switch tone {
        case .positive: YMColor.positiveSurface
        case .negative: YMColor.negativeSurface
        case .caution: YMColor.cautionarySurface
        case .neutral: YMColor.background
        }
    }
}

/// caption1Regular 보조 글.
struct SettingsCaption: View {
    let text: String
    var color: Color = YMColor.labelSecondary
    init(_ text: String, color: Color = YMColor.labelSecondary) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(YMFont.caption1Regular)
            .foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 줄 전체를 눌러 토글하는 스위치 행(세로 8). 설명은 없을 수 있다.
struct SettingsSwitchRow: View {
    let title: String
    let desc: String?
    let isOn: Bool
    var enabled = true
    let onChange: (Bool) -> Void

    var body: some View {
        Button { onChange(!isOn) } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                    if let desc {
                        Text(desc)
                            .font(YMFont.caption1Regular)
                            .foregroundStyle(YMColor.labelSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .multilineTextAlignment(.leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: Binding(get: { isOn }, set: { onChange($0) }))
                    .labelsHidden()
                    .tint(YMColor.brandPrimary)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityElement(children: .combine)
    }
}

/// 화살표 줄의 모양(세로 12, 제목 body1Medium + 설명 caption, 오른쪽 chevron).
struct SettingsLinkRowLabel: View {
    let title: String
    var desc: String?

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                if let desc {
                    Text(desc)
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .multilineTextAlignment(.leading)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(YMColor.labelSecondary)
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

/// 버튼으로 동작하는 화살표 줄.
struct SettingsLinkRow: View {
    let title: String
    var desc: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) { SettingsLinkRowLabel(title: title, desc: desc) }
            .buttonStyle(YMPressStyle())
    }
}

/// 눌러서 바꾸는 값(시각): 모서리 12, 1pt 선, s14 e12 v9, body2Medium + 8 + 연필 16.
struct SettingsEditableValue: View {
    let text: String
    let clickLabel: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(text).font(YMFont.body2Medium).foregroundStyle(YMColor.label)
                Image(systemName: "pencil")
                    .font(.system(size: 14))
                    .foregroundStyle(YMColor.labelSecondary)
                    .frame(width: 16, height: 16)
            }
            .padding(.leading, 14)
            .padding(.trailing, 12)
            .padding(.vertical, 9)
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(YMColor.line, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(YMPressStyle())
        .accessibilityHint(clickLabel)
    }
}

/// 켜진 알림 시점 하나: 모서리 10, brandSubtle 배경, s12, 글 + 36×36 × 버튼.
struct SettingsRemovableChip: View {
    let label: String
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Text(label).font(YMFont.body2Medium).foregroundStyle(YMColor.brandPrimary)
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(YMColor.brandPrimary)
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(YMPressStyle())
            .accessibilityLabel("\(label) 지우기")
        }
        .padding(.leading, 12)
        .background(RoundedRectangle(cornerRadius: 10).fill(YMColor.brandSubtle))
    }
}

/// 하이퍼링크: 최소 높이 48, body2Medium 브랜드 + 밑줄(비활성이면 회색·밑줄 없음).
struct SettingsHyperLink: View {
    let text: String
    let clickLabel: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(YMFont.body2Medium)
                .foregroundStyle(enabled ? YMColor.brandPrimary : YMColor.labelTertiary)
                .underline(enabled)
                .frame(minHeight: 48, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(YMPressStyle())
        .disabled(!enabled)
        .accessibilityHint(clickLabel)
    }
}

/// Montage `WantedButton(PRIMARY, MEDIUM, SOLID)`: 높이 40, 모서리 10, 내용 폭.
struct SettingsMainButton: View {
    let title: String
    var enabled = true
    var loading = false
    let action: () -> Void

    init(_ title: String, enabled: Bool = true, loading: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.loading = loading
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                Text(title).font(YMFont.body2Bold).opacity(loading ? 0 : 1)
                if loading { ProgressView().controlSize(.small).tint(.white) }
            }
            .foregroundStyle(enabled ? Color.white : YMColor.disableForeground)
            .padding(.horizontal, 16)
            .frame(height: 40)
            .background(RoundedRectangle(cornerRadius: 10).fill(enabled ? YMColor.brandPrimary : YMColor.disableSurface))
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(YMPressStyle())
        .disabled(!enabled || loading)
    }
}

/// Montage `WantedButton(ASSISTIVE, MEDIUM, OUTLINED)`: 높이 40, 모서리 10, 1pt 선, 내용 폭.
struct SettingsSoftButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    init(_ title: String, enabled: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(YMFont.body2Bold)
                .foregroundStyle(enabled ? YMColor.label : YMColor.labelTertiary)
                .padding(.horizontal, 16)
                .frame(height: 40)
                .background(RoundedRectangle(cornerRadius: 10).fill(YMColor.background))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(YMColor.line, lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(YMPressStyle())
        .disabled(!enabled)
    }
}

/// Material `AlertDialog`처럼 가운데 뜨는 카드(칩·입력칸이 있어 `.alert`로는 안 되는 창).
/// 폭 min(화면-48, 560), 모서리 28, 안쪽 24, 바깥을 누르면 닫힌다.
struct SettingsDialogCard<Content: View, Buttons: View>: View {
    let title: String
    let onDismiss: () -> Void
    @ViewBuilder var content: Content
    @ViewBuilder var buttons: Buttons

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.opacity(0.32)
                    .ignoresSafeArea()
                    .onTapGesture(perform: onDismiss)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(YMColor.label)
                        .accessibilityAddTraits(.isHeader)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) { content }
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .padding(.top, 16)
                    HStack(spacing: 8) {
                        Spacer(minLength: 0)
                        buttons
                    }
                    .padding(.top, 16)
                }
                .padding(24)
                .frame(width: min(geo.size.width - 48, 560))
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxHeight: geo.size.height - 48)
                .background(RoundedRectangle(cornerRadius: 28).fill(YMColor.background))
                .shadow(color: .black.opacity(0.18), radius: 24, y: 8)
                .accessibilityAddTraits(.isModal)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .transition(.opacity)
    }
}
