import ChatKit
import SwiftUI
import UIKit

/// ChatKit `MarkdownLite` 결과를 UIKit 속성 글로 바꾼다(Android `MarkdownCompose`·`SelectableMathBody`의 iOS 판).
/// - **굵게**, *기울임*, `#` 제목(굵게 1.1배), `코드`(고정폭 0.92배 + surfaceNeutralTertiary 배경), 코드 블록(0.88배), 링크(브랜드 색 밑줄, http/https만).
/// - 수식은 ChatKit `MathLayout`으로 그린 그림(`NSTextAttachment`)으로 넣는다(인라인 1.12배, 블록 1.18배 가운데).
///   그리지 못한 수식만 `MathText.toUnicode` 글(기울임)로 대신한다. 어느 쪽이든 복사하면 수식 원문(LaTeX)이 나온다(`.ymMathSource`).
enum YMMarkdown {
    struct Style {
        var fontSize: CGFloat = 16
        var lineSpacing: CGFloat = YMFont.readingLineSpacing
        var color: UIColor = YMUIColor.label
        var linkColor: UIColor = YMUIColor.brandPrimary
        var codeBackground: UIColor = YMUIColor.surfaceTertiary
        /// 글이 놓일 폭(넓은 블록 수식을 줄이거나 가로로 밀어 보게 할 기준).
        var maxWidth: CGFloat = 10_000
        /// 수식 그림 색(그림은 다크 모드를 따라가지 않으므로 화면 모양에 맞춰 정해 준다). nil이면 지금 화면 기준 `color`.
        var mathColor: UIColor?
    }

    static func attributed(_ source: String, style: Style = Style()) -> NSAttributedString {
        let result = MarkdownLite.render(source)
        let ns = result.text as NSString
        let len = ns.length
        let styles = ChatLogic.styleRanges(result)
        let maths = result.spans
            .filter { ($0.kind == .math || $0.kind == .mathBlock) && $0.latex != nil && $0.start < $0.end && $0.end <= len }
            .sorted { $0.start < $1.start }

        var cuts = Set<Int>([0, len])
        for s in styles { cuts.insert(s.start); cuts.insert(s.end) }
        for m in maths { cuts.insert(m.start); cuts.insert(m.end) }
        let points = cuts.filter { $0 >= 0 && $0 <= len }.sorted()

        let out = NSMutableAttributedString()
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = style.lineSpacing
        let base: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: style.fontSize),
            .foregroundColor: style.color,
            .paragraphStyle: paragraph,
        ]

        var i = 0
        while i + 1 < points.count {
            let a = points[i], b = points[i + 1]
            i += 1
            if let m = maths.first(where: { $0.start <= a && b <= $0.end }) {
                guard a == m.start else { continue }
                let raw = ns.substring(with: NSRange(location: m.start, length: m.end - m.start))
                out.append(mathString(m, raw: raw, style: style, base: base))
                continue
            }
            let piece = ns.substring(with: NSRange(location: a, length: b - a))
            var attrs = base
            let covering = styles.filter { $0.start <= a && $0.end >= b }
            var bold = false, italic = false, mono = false, scale = 1.0
            for s in covering {
                if s.bold { bold = true }
                if s.italic { italic = true }
                if s.mono { mono = true }
                if s.sizeScale != 1 { scale = s.sizeScale }
                if s.background { attrs[.backgroundColor] = style.codeBackground }
                if let url = s.url, let u = URL(string: url) {
                    attrs[.link] = u
                    attrs[.foregroundColor] = style.linkColor
                    attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue
                }
            }
            let size = style.fontSize * CGFloat(scale)
            let font = mono ? UIFont.monospacedSystemFont(ofSize: size, weight: bold ? .bold : .regular)
                            : UIFont.systemFont(ofSize: size, weight: bold ? .bold : .regular)
            attrs[.font] = italic ? Self.slanted(font, bold: bold) : font
            out.append(NSAttributedString(string: piece, attributes: attrs))
        }
        return out
    }

    /// 수식 한 구간: 그림(`NSTextAttachment`) 한 글자, 못 그리면 유니코드 글. 둘 다 `.ymMathSource`에 원문을 단다.
    private static func mathString(_ m: MarkdownLite.Span, raw: String, style: Style, base: [NSAttributedString.Key: Any]) -> NSAttributedString {
        let latex = m.latex ?? ""
        let block = m.kind == .mathBlock
        let size = style.fontSize * (block ? 1.18 : 1.12)
        let tag = ChatMathSource(raw)
        var attrs = base
        attrs[.ymMathSource] = tag
        if block {
            let p = NSMutableParagraphStyle()
            p.alignment = .center
            p.lineSpacing = style.lineSpacing
            p.paragraphSpacing = 4
            p.paragraphSpacingBefore = 4
            attrs[.paragraphStyle] = p
        }
        let color = style.mathColor ?? style.color.resolvedColor(with: UITraitCollection.current)
        let display = block || m.display
        let fallback = MathText.toUnicode(latex)
        if let laid = ChatMathRender.layout(latex, fontSize: size, display: display) {
            let pad = max(1, (size * 0.08).rounded(.up)) * 2
            let available = max(40, style.maxWidth - 2)
            let natural = CGFloat(laid.width) + pad
            // Android처럼 0.78배까지 줄여 맞추고, 그래도 넘치면 가로로 밀어 본다.
            let scale = natural > available ? max(0.78, available / natural) : 1
            let vpad: CGFloat = block ? 4 : 0
            if let img = ChatMathRender.render(latex, fontSize: size, display: display, color: color, scale: scale, verticalPadding: vpad) {
                img.image.accessibilityLabel = fallback
                let attachment: NSTextAttachment
                if img.size.width > available {
                    attachment = ChatMathScrollAttachment(full: img, visibleWidth: available)
                } else {
                    attachment = NSTextAttachment(image: img.image)
                    attachment.bounds = CGRect(x: 0, y: -img.baselineOffset, width: img.size.width, height: img.size.height)
                }
                let s = NSMutableAttributedString(attachment: attachment)
                s.addAttributes(attrs, range: NSRange(location: 0, length: s.length))
                return s
            }
        }
        let font = UIFont.systemFont(ofSize: size)
        if let italic = font.fontDescriptor.withSymbolicTraits(.traitItalic) {
            attrs[.font] = UIFont(descriptor: italic, size: size)
        } else {
            attrs[.font] = font
        }
        return NSAttributedString(string: fallback.isEmpty ? raw : fallback, attributes: attrs)
    }

    /// 선택한 구간을 복사할 글로: 수식(그림·대체 글)은 일부만 골라도 원문 전체로 바꾼다(Android `MathSelectionText.copySelection`).
    static func copyText(_ text: NSAttributedString, range: NSRange) -> String {
        let full = NSRange(location: 0, length: text.length)
        let r = NSIntersectionRange(range, full)
        if r.length == 0 { return "" }
        var out = ""
        var seen = Set<ObjectIdentifier>()
        text.enumerateAttribute(.ymMathSource, in: r, options: []) { value, sub, _ in
            if let tag = value as? ChatMathSource {
                if seen.insert(ObjectIdentifier(tag)).inserted { out += tag.raw }
            } else {
                out += (text.string as NSString).substring(with: sub)
            }
        }
        return out
    }

    /// 기울임 정도(Android `TextPaint.textSkewX = -0.25`와 비슷하게).
    static let ITALIC_SKEW: CGFloat = 0.22

    /// 기울인 글꼴. 한글 글꼴에는 기울임체가 없어 `.traitItalic`이면 한글이 똑바로 나오고, TextKit 2는 `.obliqueness`를 그리지 않는다.
    /// 그래서 Android(가짜 기울임)처럼 글꼴 행렬로 기울이고, 한글을 그리는 대체 글꼴에도 같은 행렬을 준다.
    static func slanted(_ font: UIFont, bold: Bool) -> UIFont {
        let m = CGAffineTransform(a: 1, b: 0, c: ITALIC_SKEW, d: 1, tx: 0, ty: 0)
        let hangul = UIFontDescriptor(name: bold ? "AppleSDGothicNeo-Bold" : "AppleSDGothicNeo-Regular", size: font.pointSize).withMatrix(m)
        let d = font.fontDescriptor.withMatrix(m).addingAttributes([.cascadeList: [hangul]])
        return UIFont(descriptor: d, size: font.pointSize)
    }

    /// 말풍선 본문 조각: 글, 또는 말풍선보다 넓어 가로로 밀어 보는 블록 수식.
    enum Segment {
        case text(NSAttributedString)
        case wideMath(ChatMathRender.Image, raw: String)
    }

    /// 넓은 블록 수식(`ChatMathScrollAttachment`) 자리에서 본문을 나눈다. 넓은 수식이 없으면 글 하나.
    /// 수식 앞뒤 줄바꿈 한 개씩은 조각 경계가 대신하므로 뺀다(빈 줄이 두 번 생기지 않게).
    static func segments(_ text: NSAttributedString) -> [Segment] {
        var wide: [(NSRange, ChatMathScrollAttachment, String)] = []
        text.enumerateAttribute(.attachment, in: NSRange(location: 0, length: text.length), options: []) { v, r, _ in
            if let a = v as? ChatMathScrollAttachment {
                let raw = (text.attribute(.ymMathSource, at: r.location, effectiveRange: nil) as? ChatMathSource)?.raw ?? ""
                wide.append((r, a, raw))
            }
        }
        guard !wide.isEmpty else { return [.text(text)] }
        let ns = text.string as NSString
        var out: [Segment] = []
        var cursor = 0
        func appendText(_ from: Int, _ to: Int) {
            var a = from, b = to
            if b > a, ns.character(at: b - 1) == 0x0A { b -= 1 }
            if b > a, ns.character(at: a) == 0x0A, a == cursor, !out.isEmpty { a += 1 }
            guard b > a else { return }
            let piece = text.attributedSubstring(from: NSRange(location: a, length: b - a))
            if piece.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return }
            out.append(.text(piece))
        }
        for (r, a, raw) in wide {
            appendText(cursor, r.location)
            out.append(.wideMath(a.full, raw: raw))
            cursor = r.location + r.length
        }
        appendText(cursor, text.length)
        return out
    }

    /// 글자 그대로(마크다운 해석 없이) 보여 줄 때.
    static func plain(_ text: String, style: Style) -> NSAttributedString {
        let p = NSMutableParagraphStyle()
        p.lineSpacing = style.lineSpacing
        return NSAttributedString(string: text, attributes: [
            .font: UIFont.systemFont(ofSize: style.fontSize),
            .foregroundColor: style.color,
            .paragraphStyle: p,
        ])
    }
}

/// 수식 원문을 되살려 복사하는 본문 보기(편집 불가).
final class YMTextView: UITextView {
    override func copy(_ sender: Any?) {
        let text = attributedText ?? NSAttributedString()
        let range = selectedRange
        var hasMath = false
        text.enumerateAttribute(.ymMathSource, in: NSRange(location: 0, length: text.length), options: []) { v, _, stop in
            if v != nil { hasMath = true; stop.pointee = true }
        }
        guard hasMath, range.length > 0 else { super.copy(sender); return }
        UIPasteboard.general.string = YMMarkdown.copyText(text, range: range)
    }
}

/// 말풍선보다 넓은 블록 수식: 말풍선 폭만큼 보이고 가로로 밀어 본다. 길게 누르면 수식 원문(LaTeX)을 복사한다.
struct YMWideMathView: View {
    let image: ChatMathRender.Image
    let raw: String
    let width: CGFloat

    var body: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Image(uiImage: image.image)
                .resizable()
                .frame(width: image.size.width, height: image.size.height)
                .accessibilityLabel(image.image.accessibilityLabel ?? raw)
        }
        .frame(width: width, height: image.size.height)
        .padding(.vertical, 4)
        .contextMenu {
            Button {
                UIPasteboard.general.string = raw
                ToastCenter.shared.show("복사했어요")
            } label: { Label("수식 복사", systemImage: "doc.on.doc") }
        }
    }
}

/// 부분 선택이 되는 본문(편집 불가 `UITextView`). SwiftUI `Text.textSelection`은 iOS에서 전체 복사만 되므로
/// Android처럼 문장 일부를 골라 복사하려고 UIKit을 쓴다. 글 폭만큼만 차지한다(말풍선이 내용에 맞게 줄어든다).
struct YMSelectableText: UIViewRepresentable {
    let text: NSAttributedString
    var tint: UIColor = YMUIColor.brandPrimary
    var scrollable = false
    var insets: UIEdgeInsets = .zero

    func makeUIView(context: Context) -> UITextView {
        let v = YMTextView()
        v.isEditable = false
        v.isSelectable = true
        v.isScrollEnabled = scrollable
        v.backgroundColor = .clear
        v.textContainerInset = insets
        v.textContainer.lineFragmentPadding = 0
        v.adjustsFontForContentSizeCategory = false
        v.dataDetectorTypes = []
        v.linkTextAttributes = [:]
        v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        v.setContentHuggingPriority(.defaultHigh, for: .vertical)
        v.attributedText = text
        v.tintColor = tint
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        // 같은 본문을 다시 넣으면 진행 중인 선택이 사라진다(Android 0.2.8과 같은 이유).
        if v.attributedText != text { v.attributedText = text }
        v.tintColor = tint
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        if scrollable { return nil }
        let maxWidth = (proposal.width ?? 10_000).isFinite ? (proposal.width ?? 10_000) : 10_000
        let inner = max(maxWidth - insets.left - insets.right, 1)
        let bounds = text.boundingRect(with: CGSize(width: inner, height: .greatestFiniteMagnitude),
                                       options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        let width = min(maxWidth, ceil(bounds.width) + insets.left + insets.right + 1)
        let fit = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fit.height))
    }
}
