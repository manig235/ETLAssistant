import ChatKit
import SwiftUI

/// 상단바(Kotlin `ChatTopBar`): 높이 56, 시작 4 / 끝 8. [메뉴] 영민이 ……… [새 대화] [테마] [설정].
struct ChatTopBar: View {
    let onMenu: () -> Void
    let onNewChat: () -> Void
    let onSettings: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 0) {
            iconButton("line.3.horizontal", "대화 목록", action: onMenu)
            Text("영민이")
                .font(YMFont.title2Bold)
                .foregroundStyle(YMColor.label)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            iconButton("square.and.pencil", "새 대화", action: onNewChat)
            if colorScheme == .dark {
                iconButton("sun.max", "라이트 모드로 전환") { AppAppearanceStore.shared.mode = .light }
            } else {
                iconButton("moon", "다크 모드로 전환") { AppAppearanceStore.shared.mode = .dark }
            }
            iconButton("gearshape", "설정", action: onSettings)
        }
        .padding(.leading, 4)
        .padding(.trailing, 8)
        .frame(height: 56)
    }

    private func iconButton(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 20))
                .foregroundStyle(YMColor.label)
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(YMPressStyle())
        .accessibilityLabel(label)
    }
}

/// 모델 칩(Kotlin `ModelChip`): 알약, surfaceNeutralSecondary, [Tune] "AI 모델 · {선택}" [∨].
struct ChatModelChip: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 13))
                    .foregroundStyle(YMColor.labelSecondary)
                Text("AI 모델 · \(label)")
                    .font(YMFont.caption1Medium)
                    .foregroundStyle(YMColor.label)
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(YMColor.labelSecondary)
            }
            .padding(.leading, 12)
            .padding(.trailing, 8)
            .padding(.vertical, 7)
            .background(Capsule().fill(YMColor.surfaceSecondary))
            .contentShape(Capsule())
        }
        .buttonStyle(YMPressStyle())
        .accessibilityHint("AI 모델 고르기")
    }
}

/// 상태 배너(Kotlin `StatusBanner`): 모서리 16, surfaceCautionaryPrimary, [Info] 문구 [× 또는 ›].
struct ChatBanner: View {
    let spec: BannerSpec
    let onTap: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        let tappable = spec.action != .none
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: "info.circle")
                .font(.system(size: 18))
                .foregroundStyle(YMColor.cautionaryForeground)
                .frame(width: 20, height: 20)
            Text(spec.text)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.label)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if spec.dismissible {
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(YMColor.labelSecondary)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(YMPressStyle())
                .accessibilityLabel("나중에")
            } else if tappable {
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(YMColor.labelSecondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.cautionarySurface))
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .onTapGesture { if tappable { onTap() } }
        .accessibilityAddTraits(tappable ? .isButton : [])
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

/// 빈 화면(Kotlin `EmptyState`): 가운데 원 아이콘 → "무엇을 도와드릴까요?" → 안내 → 추천 카드 7개(누르면 바로 전송).
struct ChatEmptyState: View {
    let onPick: (String) -> Void
    @State private var suggestions = SuggestionQuestions.shared

    private static let icons = ["calendar", "megaphone", "calendar", "bubble.left", "paperclip", "checkmark.circle", "doc.text"]

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 0) {
                    Image(systemName: "bubble.left")
                        .font(.system(size: 30))
                        .foregroundStyle(YMColor.brandPrimary)
                        .frame(width: 72, height: 72)
                        .background(Circle().fill(YMColor.brandSubtle))
                    Text("무엇을 도와드릴까요?")
                        .font(YMFont.title1Bold)
                        .foregroundStyle(YMColor.label)
                        .multilineTextAlignment(.center)
                        .padding(.top, 20)
                    Text("마감, 공지, 제출 확인, 강의 파일까지 물어보세요.")
                        .font(YMFont.body1Reading)
                        .foregroundStyle(YMColor.labelSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                    VStack(spacing: 10) {
                        ForEach(Array(suggestions.questions.enumerated()), id: \.offset) { i, q in
                            SuggestionCard(icon: Self.icons[i % Self.icons.count], text: q) { onPick(q) }
                        }
                    }
                    .padding(.top, 28)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
                .frame(minHeight: geo.size.height)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .task { await suggestions.refresh() }
    }
}

/// 활동 줄(Kotlin `ActivityLine`): caption1Medium secondary, 1.0↔0.35 700ms 깜빡임.
/// 다른 대화의 답변이 끝났다는 막대(Kotlin `FinishBar`): "‘…’ 답변이 끝났어요" [보기] [×]. 몇 초 뒤 사라진다.
struct ChatFinishBar: View {
    let text: String
    let onOpen: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Text(text)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.label)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("보기", action: onOpen)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.brandPrimary)
                .padding(.horizontal, 8)
                .frame(minHeight: 36)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(YMColor.labelSecondary)
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(YMPressStyle())
            .accessibilityLabel("닫기")
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 14).fill(YMColor.surfaceSecondary))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .onAppear { UIAccessibility.post(notification: .announcement, argument: text) }
    }
}

struct ChatActivityLine: View {
    let text: String
    @State private var dim = false

    var body: some View {
        Text(text)
            .font(YMFont.caption1Medium)
            .foregroundStyle(YMColor.labelSecondary)
            .opacity(dim ? 0.35 : 1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.vertical, 4)
            .onAppear {
                withAnimation(.linear(duration: 0.7).repeatForever(autoreverses: true)) { dim = true }
            }
            .accessibilityAddTraits(.updatesFrequently)
    }
}

/// 입력 바(Kotlin `InputBar`): 알약(모서리 26) surfaceNeutralSecondary, [+ 첨부] [입력 최대 5줄] [보내기/중단].
struct ChatInputBar: View {
    @Bindable var controller: ChatController
    let onPickPhotos: () -> Void
    let onPickFiles: () -> Void
    /// "사진 찍기"(카메라가 없는 기기면 nil → 메뉴에서 숨김).
    var onTakePhoto: (() -> Void)? = nil
    @FocusState private var focused: Bool

    var body: some View {
        let attachDisabled = controller.pending.count + controller.importing >= AttachmentLogic.MAX_PENDING
        HStack(alignment: .bottom, spacing: 0) {
            Menu {
                Button { onPickPhotos() } label: { Label("사진", systemImage: "photo.on.rectangle") }
                if let onTakePhoto {
                    Button { onTakePhoto() } label: { Label("사진 찍기", systemImage: "camera") }
                }
                Button { onPickFiles() } label: { Label("파일", systemImage: "paperclip") }
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(attachDisabled ? YMColor.disableForeground : YMColor.label)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .disabled(attachDisabled)
            .accessibilityLabel("첨부")
            .padding(.leading, 6)
            .padding(.bottom, 6)

            TextField("", text: $controller.input, prompt: Text("메시지를 입력하세요").foregroundStyle(YMColor.labelQuaternary), axis: .vertical)
                .font(YMFont.body1Regular)
                .foregroundStyle(YMColor.label)
                .tint(YMColor.brandPrimary)
                .lineLimit(1...5)
                .textInputAutocapitalization(.sentences)
                .focused($focused)
                .padding(.leading, 4)
                .padding(.trailing, 8)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, alignment: .leading)

            sendButton
                .padding(6)
        }
        .frame(minHeight: 52)
        .background(RoundedRectangle(cornerRadius: 26).fill(YMColor.surfaceSecondary))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var sendButton: some View {
        let mode = controller.sendMode
        Button { controller.submit() } label: {
            ZStack {
                Circle().fill(mode == .send ? YMColor.brandPrimary : mode == .stop ? YMColor.surfaceInverse : YMColor.disableSurface)
                Image(systemName: mode == .stop ? "stop.fill" : "paperplane")
                    .font(.system(size: mode == .stop ? 14 : 17, weight: .medium))
                    .foregroundStyle(mode == .send ? Color.white : mode == .stop ? YMColor.foregroundInverse : YMColor.disableForeground)
            }
            .frame(width: 40, height: 40)
        }
        .buttonStyle(YMPressStyle())
        .disabled(mode == .disabled)
        .accessibilityLabel(mode == .stop ? "답변 중단" : "보내기")
    }
}
