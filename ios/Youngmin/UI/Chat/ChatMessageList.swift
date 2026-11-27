import ChatKit
import SwiftUI
import UIKit

/// 메시지 목록(Kotlin `LazyColumn(reverseLayout = true)`): 맨 아래가 최신. 맨 아래를 보고 있으면 스트리밍 중에도 따라 내려가고,
/// 위로 올려 보는 중이면 위치를 지킨다. 새 메시지·대화 전환·화면 복귀 때 맨 아래로.
struct ChatMessageList: View {
    let controller: ChatController
    let onOpen: (Attachment) -> Void
    let onLink: (URL) -> Void
    @State private var atBottom = true

    var body: some View {
        GeometryReader { geo in
            // 넓은 화면(iPad)에서는 말풍선 줄을 가운데 [YMLayout.chatWidth] 폭으로 모은다. 스크롤은 화면 전체 폭에서 된다.
            let column = min(geo.size.width, YMLayout.chatWidth)
            let maxBubble = max(120, (column - 32) * 0.86)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(controller.items) { item in
                            ChatMessageRow(item: item, maxWidth: maxBubble, onOpen: onOpen,
                                           onRedownload: { controller.input = $0 })
                        }
                        Color.clear
                            .frame(height: 1)
                            .id(Self.bottomId)
                            .onAppear { atBottom = true }
                            .onDisappear { atBottom = false }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .ymReadableWidth(YMLayout.chatWidth)
                }
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: controller.scrollToBottomToken) { _, _ in
                    proxy.scrollTo(Self.bottomId, anchor: .bottom)
                }
                .onChange(of: controller.items) { _, _ in
                    if atBottom { proxy.scrollTo(Self.bottomId, anchor: .bottom) }
                }
            }
        }
    }

    private static let bottomId = "chat-bottom"
}

/// 메시지 한 줄(Kotlin `MessageRow`).
struct ChatMessageRow: View {
    let item: ChatItem
    let maxWidth: CGFloat
    let onOpen: (Attachment) -> Void
    let onRedownload: (String) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        switch item.role {
        case .user:
            VStack(alignment: .trailing, spacing: 6) {
                if !item.attachments.isEmpty {
                    ChatAttachmentGroup(attachments: item.attachments, isUser: true, maxWidth: maxWidth,
                                        onOpen: onOpen, onRedownload: onRedownload)
                }
                if !item.text.isEmpty { userBubble }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        case .assistant:
            VStack(alignment: .leading, spacing: 0) {
                assistantBubble
                if !item.streaming && !item.text.isEmpty {
                    Button {
                        UIPasteboard.general.string = item.text
                        ToastCenter.shared.show("복사했어요")
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 15))
                            .foregroundStyle(YMColor.labelTertiary)
                            .frame(width: 44, height: 40)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(YMPressStyle())
                    .accessibilityLabel("답변 전체 복사")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .notice:
            Text(item.text)
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelTertiary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
        case .files:
            ChatAttachmentGroup(attachments: item.attachments, isUser: false, maxWidth: maxWidth,
                                onOpen: onOpen, onRedownload: onRedownload)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var userBubble: some View {
        YMCapWidth(maxWidth: maxWidth) {
            YMSelectableText(text: ChatTextCache.user(item.text), tint: YMUIColor.userSelection)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(UnevenRoundedRectangle(topLeadingRadius: 20, bottomLeadingRadius: 20,
                                                   bottomTrailingRadius: 6, topTrailingRadius: 20)
                    .fill(YMColor.bubbleUser))
        }
        .accessibilityAction(named: "전체 복사") {
            UIPasteboard.general.string = item.text
            ToastCenter.shared.show("복사했어요")
        }
    }

    private var assistantBubble: some View {
        let width = maxWidth - 28
        let segments = ChatTextCache.assistantSegments(item.text.isEmpty ? "…" : item.text, width: width, dark: colorScheme == .dark)
        return YMCapWidth(maxWidth: maxWidth) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                    switch seg {
                    case .text(let t): YMSelectableText(text: t)
                    case .wideMath(let img, let raw): YMWideMathView(image: img, raw: raw, width: width)
                    }
                }
            }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(UnevenRoundedRectangle(topLeadingRadius: 20, bottomLeadingRadius: 6,
                                                   bottomTrailingRadius: 20, topTrailingRadius: 20)
                    .fill(YMColor.surfaceSecondary))
        }
    }
}

/// 말풍선 본문 변환 결과를 재사용한다(스트리밍 중 다른 줄을 다시 해석하지 않도록).
enum ChatTextCache {
    private static let cache: NSCache<NSString, NSAttributedString> = {
        let c = NSCache<NSString, NSAttributedString>()
        c.countLimit = 400
        return c
    }()

    /// [width]는 말풍선 안 글 폭(넓은 블록 수식 맞춤), [dark]는 수식 그림 색을 정한다.
    static func assistant(_ text: String, width: CGFloat, dark: Bool) -> NSAttributedString {
        let key = "A\(Int(width))|\(dark ? 1 : 0)|\(text)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        var style = YMMarkdown.Style()
        style.maxWidth = width
        style.mathColor = style.color.resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
        let v = YMMarkdown.attributed(text, style: style)
        cache.setObject(v, forKey: key)
        return v
    }

    private final class SegmentsBox {
        let value: [YMMarkdown.Segment]
        init(_ value: [YMMarkdown.Segment]) { self.value = value }
    }
    private static let segmentCache: NSCache<NSString, SegmentsBox> = {
        let c = NSCache<NSString, SegmentsBox>()
        c.countLimit = 400
        return c
    }()

    /// 넓은 블록 수식에서 나눈 말풍선 본문 조각(`YMMarkdown.segments`).
    static func assistantSegments(_ text: String, width: CGFloat, dark: Bool) -> [YMMarkdown.Segment] {
        let key = "S\(Int(width))|\(dark ? 1 : 0)|\(text)" as NSString
        if let hit = segmentCache.object(forKey: key) { return hit.value }
        let v = YMMarkdown.segments(assistant(text, width: width, dark: dark))
        segmentCache.setObject(SegmentsBox(v), forKey: key)
        return v
    }

    static func user(_ text: String) -> NSAttributedString {
        let key = ("U" + text) as NSString
        if let hit = cache.object(forKey: key) { return hit }
        var style = YMMarkdown.Style()
        style.color = .white
        let v = YMMarkdown.plain(text, style: style)
        cache.setObject(v, forKey: key)
        return v
    }
}
