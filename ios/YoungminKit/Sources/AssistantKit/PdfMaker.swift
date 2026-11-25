import Foundation
import CoreGraphics
import CoreText
import ChatKit
import ETLKit

/// 마크다운 블록([PdfMarkdown])을 기기 안에서 A4 PDF로 그린다(Kotlin `pdf/PdfMaker.kt`).
/// CoreText + CoreGraphics PDF 문맥(UIKit·네트워크·WebView 없음, macOS 14·iOS 17에서 같은 코드).
/// 글은 시스템 글꼴(한국어 대체 글꼴은 CoreText가 고른다). 블록 수식은 ChatKit `MathLayout`으로 그리고(못 그리면 유니코드 글),
/// 글 안의 수식은 유니코드 글([MathText.toUnicode], 기울임).
/// 글은 줄 단위로 쪽을 나누고([PdfLayoutLogic.paginate]), 쪽마다 아래에 "제목 · n / N"을 적는다.
final class PdfMaker {

    struct Made: Sendable { let pages: Int }

    private let title: String
    private let subtitle: String?

    init(_ title: String, _ subtitle: String?) {
        self.title = title
        self.subtitle = subtitle
    }

    private typealias L = PdfLayoutLogic
    private let width = L.contentWidth
    private let pageH = Double(L.PAGE_HEIGHT)

    private static func rgb(_ hex: UInt32) -> CGColor {
        CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }

    private let ink = rgb(0x1F2328)
    private let gray = rgb(0x6B7280)
    private let lineColor = rgb(0xD0D7DE)
    private let codeBg = rgb(0xF3F4F6)
    private let headerBg = rgb(0xEEF2F7)
    private let linkColor = rgb(0x1D4ED8)
    private let quoteColor = rgb(0x4B5563)

    // MARK: 글꼴

    private enum Face { case regular, bold, mono, serifItalic }

    private static func font(_ size: Double, _ face: Face = .regular) -> CTFont {
        let s = CGFloat(size)
        switch face {
        case .regular: return CTFontCreateUIFontForLanguage(.system, s, "ko" as CFString) ?? CTFontCreateWithName("Helvetica" as CFString, s, nil)
        case .bold: return CTFontCreateUIFontForLanguage(.emphasizedSystem, s, "ko" as CFString) ?? CTFontCreateWithName("Helvetica-Bold" as CFString, s, nil)
        case .mono: return CTFontCreateWithName("Menlo-Regular" as CFString, s, nil)
        case .serifItalic: return CTFontCreateWithName("TimesNewRomanPS-ItalicMT" as CFString, s, nil)
        }
    }

    private static func asFont(_ v: Any?) -> CTFont? {
        guard let v, CFGetTypeID(v as CFTypeRef) == CTFontGetTypeID() else { return nil }
        return (v as! CTFont)
    }

    private static func adding(_ f: CTFont, _ t: CTFontSymbolicTraits) -> CTFont {
        CTFontCreateCopyWithSymbolicTraits(f, 0, nil, t, t) ?? f
    }

    private static let fontKey = NSAttributedString.Key(kCTFontAttributeName as String)
    private static let colorKey = NSAttributedString.Key(kCTForegroundColorAttributeName as String)
    /// 글 뒤 배경(인라인 코드). CoreText는 그리지 않아 직접 그린다.
    private static let backgroundKey = NSAttributedString.Key("YoungminPdfBackground")
    /// 밑줄(링크). CoreText는 그리지 않아 직접 그린다.
    private static let underlineKey = NSAttributedString.Key("YoungminPdfUnderline")

    private func plain(_ text: String, _ font: CTFont, _ color: CGColor? = nil) -> NSMutableAttributedString {
        NSMutableAttributedString(string: text, attributes: [PdfMaker.fontKey: font, PdfMaker.colorKey: color ?? ink])
    }

    // MARK: 줄 배치

    private struct Decoration {
        let x0: Double
        let x1: Double
        let color: CGColor
        let underline: Bool
    }

    private struct Line {
        let ct: CTLine?
        let ascent: Double
        let descent: Double
        /// 줄 높이(줄 간격 배율 포함).
        let height: Double
        /// 레이아웃 위에서부터 줄 위까지.
        let top: Double
        /// 줄 시작 x(들여쓰기·가운데 맞춤).
        let x: Double
        let decorations: [Decoration]
    }

    private struct TextLayout {
        let lines: [Line]
        var height: Double { lines.last.map { $0.top + $0.height } ?? 0 }
    }

    /// [text]를 폭 [w]에 맞춰 줄로 나눈다. `\n`은 줄바꿈. [firstIndent]/[restIndent]는 첫 줄/나머지 줄 들여쓰기.
    private func layout(_ text: NSAttributedString, _ w: Double, spacing: Double, center: Bool = false,
                        firstIndent: Double = 0, restIndent: Double = 0) -> TextLayout {
        let ns = text.string as NSString
        var lines: [Line] = []
        var y = 0.0
        var first = true
        var paraStart = 0
        func add(_ ct: CTLine?, _ fallbackFont: CTFont, _ sub: NSAttributedString?) {
            let indent = first ? firstIndent : restIndent
            first = false
            var ascent: CGFloat = 0, descent: CGFloat = 0, leading: CGFloat = 0
            var lineWidth = 0.0
            if let ct {
                lineWidth = Double(CTLineGetTypographicBounds(ct, &ascent, &descent, &leading))
            }
            if ct == nil || ascent + descent <= 0 {
                ascent = CTFontGetAscent(fallbackFont)
                descent = CTFontGetDescent(fallbackFont)
            }
            let avail = max(1, w - indent)
            var x = indent
            if center, let ct {
                x = indent + Double(CTLineGetPenOffsetForFlush(ct, 0.5, avail))
            } else if center {
                x = indent + max(0, (avail - lineWidth) / 2)
            }
            var decorations: [Decoration] = []
            if let ct, let sub {
                let r = CTLineGetStringRange(ct)
                let range = NSRange(location: r.location, length: r.length)
                for (key, underline) in [(PdfMaker.backgroundKey, false), (PdfMaker.underlineKey, true)] {
                    sub.enumerateAttribute(key, in: range) { value, sr, _ in
                        guard let value, CFGetTypeID(value as CFTypeRef) == CGColor.typeID else { return }
                        let x0 = Double(CTLineGetOffsetForStringIndex(ct, sr.location, nil))
                        let x1 = Double(CTLineGetOffsetForStringIndex(ct, sr.location + sr.length, nil))
                        if x1 > x0 { decorations.append(Decoration(x0: x0, x1: x1, color: value as! CGColor, underline: underline)) }
                    }
                }
            }
            let h = Double(ascent + descent) * spacing
            lines.append(Line(ct: ct, ascent: Double(ascent), descent: Double(descent), height: h, top: y, x: x, decorations: decorations))
            y += h
        }
        let total = ns.length
        while paraStart <= total {
            var nl = ns.range(of: "\n", options: [], range: NSRange(location: paraStart, length: total - paraStart)).location
            if nl == NSNotFound { nl = total }
            let fontAt = (paraStart < total ? text.attribute(PdfMaker.fontKey, at: paraStart, effectiveRange: nil) : nil)
                ?? (total > 0 ? text.attribute(PdfMaker.fontKey, at: max(0, min(paraStart, total) - 1), effectiveRange: nil) : nil)
            let baseFont = PdfMaker.asFont(fontAt) ?? PdfMaker.font(10.5)
            if nl == paraStart {
                add(nil, baseFont, nil)
            } else {
                let sub = text.attributedSubstring(from: NSRange(location: paraStart, length: nl - paraStart))
                let ts = CTTypesetterCreateWithAttributedString(sub as CFAttributedString)
                let len = sub.length
                var start = 0
                while start < len {
                    let indent = first ? firstIndent : restIndent
                    var count = CTTypesetterSuggestLineBreak(ts, start, Double(max(1, w - indent)))
                    if count <= 0 { count = max(1, CTTypesetterSuggestClusterBreak(ts, start, Double(max(1, w - indent)))) }
                    count = min(count, len - start)
                    let ct = CTTypesetterCreateLine(ts, CFRange(location: start, length: count))
                    add(ct, baseFont, sub)
                    start += count
                }
            }
            paraStart = nl + 1
        }
        return TextLayout(lines: lines)
    }

    /// 줄 하나를 그린다. (left, top)은 쪽 위에서부터의 좌표(포인트, y 아래로).
    private func draw(_ c: CGContext, _ line: Line, _ left: Double, _ top: Double) {
        guard let ct = line.ct else { return }
        let baseline = top + line.ascent
        for d in line.decorations {
            if d.underline {
                c.setStrokeColor(d.color)
                c.setLineWidth(0.5)
                c.move(to: CGPoint(x: left + line.x + d.x0, y: pageH - (baseline + 1.2)))
                c.addLine(to: CGPoint(x: left + line.x + d.x1, y: pageH - (baseline + 1.2)))
                c.strokePath()
            } else {
                c.setFillColor(d.color)
                c.fill(CGRect(x: left + line.x + d.x0, y: pageH - (baseline + line.descent), width: d.x1 - d.x0, height: line.ascent + line.descent))
            }
        }
        c.textMatrix = .identity
        c.textPosition = CGPoint(x: left + line.x, y: pageH - baseline)
        CTLineDraw(ct, c)
    }

    private func fillRect(_ c: CGContext, _ color: CGColor, _ x: Double, _ top: Double, _ w: Double, _ h: Double) {
        c.setFillColor(color)
        c.fill(CGRect(x: x, y: pageH - top - h, width: w, height: h))
    }

    private func strokeRect(_ c: CGContext, _ x: Double, _ top: Double, _ w: Double, _ h: Double) {
        c.setStrokeColor(lineColor)
        c.setLineWidth(0.6)
        c.stroke(CGRect(x: x, y: pageH - top - h, width: w, height: h))
    }

    private func hLine(_ c: CGContext, _ x0: Double, _ x1: Double, _ y: Double) {
        c.setStrokeColor(lineColor)
        c.setLineWidth(0.6)
        c.move(to: CGPoint(x: x0, y: pageH - y))
        c.addLine(to: CGPoint(x: x1, y: pageH - y))
        c.strokePath()
    }

    private func measure(_ text: String, _ font: CTFont) -> Double {
        if text.isEmpty { return 0 }
        let line = CTLineCreateWithAttributedString(plain(text, font) as CFAttributedString)
        return Double(CTLineGetTypographicBounds(line, nil, nil, nil))
    }

    // MARK: 조각

    /// 쪽에 놓을 조각. [draw]는 본문 영역 왼쪽 위 기준 (left, top)에 그린다.
    private struct Piece {
        let item: PdfLayoutLogic.Item
        let draw: (CGContext, Double, Double) -> Void
    }

    func write(_ blocks: [PdfMarkdown.Block], to url: URL) throws -> Made {
        var pieces: [Piece] = []
        titlePieces(&pieces)
        var prevList = false
        for b in blocks {
            var isList = false
            switch b {
            case let .heading(level, text):
                let size = level == 1 ? 16.5 : level == 2 ? 14.0 : 12.0
                let gap = level == 1 ? 18.0 : level == 2 ? 14.0 : 10.0
                textPieces(&pieces, styled(text, PdfMaker.font(size, .bold)), gap, keepAll: true)
            case let .paragraph(text):
                textPieces(&pieces, styled(text, PdfMaker.font(10.5)), 7)
            case let .listItem(_, marker, level, text):
                isList = true
                let body = PdfMaker.font(10.5)
                let indent = 4.0 + Double(level) * 16
                let markerWidth = measure(marker + " ", body)
                let sb = plain(marker + " ", body)
                sb.append(styled(text, body))
                textPieces(&pieces, sb, prevList ? 3 : 7, firstIndent: indent, restIndent: indent + markerWidth)
            case let .quote(text):
                textPieces(&pieces, styled(text, PdfMaker.font(10.5), quoteColor), 7, inset: 12, bar: true)
            case let .code(text):
                textPieces(&pieces, plain(text.isEmpty ? " " : text, PdfMaker.font(9, .mono)), 8, inset: 8, background: codeBg, spacing: 1.15)
            case let .mathBlock(latex):
                // 블록 수식은 채팅과 같은 ChatKit `MathLayout`으로 그린다(본문보다 조금 크게, 넓으면 폭에 맞춰 줄임).
                if var box = MathLayout.layout(latex, fontSize: 12.5, display: true) {
                    if box.width > width { box = box.scaled(width / box.width) }
                    let w = width, color = ink, b = box
                    pieces.append(Piece(item: L.Item(b.height + 4, 8)) { [self] c, l, t in
                        MathLayout.draw(b, in: c, x: l + (w - b.width) / 2, baseline: pageH - (t + 2 + b.ascent), color: color, yDown: false)
                    })
                    break
                }
                let uni = MathText.toUnicode(latex)
                textPieces(&pieces, plain(uni.isBlankK ? latex : uni, PdfMaker.font(11, .serifItalic)), 8, center: true)
            case let .table(header, rows):
                table(&pieces, header, rows)
            case .rule:
                let w = width
                pieces.append(Piece(item: L.Item(12, 4)) { [self] c, l, t in hLine(c, l, l + w, t + 6) })
            }
            prevList = isList
        }
        let placements = L.paginate(pieces.map(\.item))
        let total = L.pageCount(placements)
        if total > L.MAX_PAGES { throw EtlException("PDF가 \(L.MAX_PAGES)쪽을 넘어요(\(total)쪽). 내용을 나눠서 만들어 주세요") }

        var box = CGRect(x: 0, y: 0, width: L.PAGE_WIDTH, height: L.PAGE_HEIGHT)
        let info: [String: Any] = [kCGPDFContextTitle as String: title.isEmpty ? "영민이 문서" : title,
                                   kCGPDFContextCreator as String: "영민이"]
        guard let c = CGContext(url as CFURL, mediaBox: &box, info as CFDictionary) else {
            throw EtlException("PDF를 만들지 못했습니다(CGContext)")
        }
        let small = PdfMaker.font(8)
        var index = 0
        for p in 0..<total {
            c.beginPDFPage(nil)
            while index < pieces.count && placements[index].page == p {
                c.saveGState()
                // 쪽보다 큰 조각(긴 표 행 등)이 아래 여백을 넘지 않게 자른다.
                let clipTop = L.MARGIN_TOP - 4, clipBottom = pageH - L.MARGIN_BOTTOM + 4
                c.clip(to: CGRect(x: 0, y: pageH - clipBottom, width: Double(L.PAGE_WIDTH), height: clipBottom - clipTop))
                pieces[index].draw(c, L.MARGIN_X, L.MARGIN_TOP + placements[index].y)
                c.restoreGState()
                index += 1
            }
            let footer = L.footer(title, p + 1, total)
            let fl = CTLineCreateWithAttributedString(plain(footer, small, gray) as CFAttributedString)
            let fw = Double(CTLineGetTypographicBounds(fl, nil, nil, nil))
            c.textMatrix = .identity
            c.textPosition = CGPoint(x: (Double(L.PAGE_WIDTH) - fw) / 2, y: 30)
            CTLineDraw(fl, c)
            c.endPDFPage()
        }
        c.closePDF()
        return Made(pages: total)
    }

    private func titlePieces(_ pieces: inout [Piece]) {
        if !title.isBlankK { textPieces(&pieces, plain(title.kt, PdfMaker.font(20, .bold)), 0, keepAll: true, spacing: 1.15) }
        if let subtitle, !subtitle.isBlankK { textPieces(&pieces, plain(subtitle, PdfMaker.font(8.5), gray), 4, keepAll: true) }
        let w = width
        pieces.append(Piece(item: L.Item(10, 4)) { [self] c, l, t in hLine(c, l, l + w, t + 5) })
    }

    /// 글을 줄 단위 조각으로. [keepAll]이면 모든 줄을 다음 조각과 붙여 둔다(제목). 문단은 첫 줄만 붙여 쪽 끝에 한 줄만 남지 않게 한다.
    private func textPieces(_ pieces: inout [Piece], _ text: NSAttributedString, _ gapBefore: Double,
                            keepAll: Bool = false, inset: Double = 0, background: CGColor? = nil, bar: Bool = false,
                            spacing: Double = 1.25, center: Bool = false, firstIndent: Double = 0, restIndent: Double = 0) {
        let w = width - inset * 2
        let full = width
        let l = layout(text, w, spacing: spacing, center: center, firstIndent: firstIndent, restIndent: restIndent)
        let count = l.lines.count
        for (i, line) in l.lines.enumerated() {
            let pad = background != nil ? 3.0 : 0
            let extraTop = i == 0 ? pad : 0
            let extraBottom = i == count - 1 ? pad : 0
            let item = L.Item(line.height + extraTop + extraBottom, i == 0 ? gapBefore : 0, keepAll || (i == 0 && count > 1))
            let barColor = lineColor
            pieces.append(Piece(item: item) { [self] c, left, y in
                if let background { fillRect(c, background, left, y, full, item.height) }
                if bar { fillRect(c, barColor, left, y, 2.5, item.height) }
                draw(c, line, left + inset, y + extraTop)
            })
        }
    }

    private func table(_ pieces: inout [Piece], _ header: [PdfMarkdown.Inline], _ rows: [[PdfMarkdown.Inline]]) {
        let n = header.count
        let size = 9.5
        let cellFont = PdfMaker.font(size)
        let headFont = PdfMaker.font(size, .bold)
        let pad = 4.0
        let minCol = 40.0
        if n == 0 { return }
        if Double(n) * minCol > width {
            // 칸이 너무 많으면 행마다 "머리: 값" 글로 적는다.
            for row in rows {
                let sb = NSMutableAttributedString()
                for (j, cell) in row.enumerated() {
                    if j > 0 { sb.append(plain("  ·  ", cellFont)) }
                    sb.append(plain(header[j].text + ": ", headFont))
                    sb.append(styled(cell, cellFont))
                }
                textPieces(&pieces, sb, 4)
            }
            return
        }
        let all = [header] + rows
        let natural: [Double] = (0..<n).map { j in
            var widest = 0.0
            for (r, row) in all.enumerated() {
                let f = r == 0 ? headFont : cellFont
                for s in row[j].text.components(separatedBy: "\n") { widest = max(widest, measure(s, f)) }
            }
            return min(max(widest + pad * 2 + 1, minCol), width * 0.6)
        }
        let sum = natural.reduce(0, +)
        let widths: [Double]
        if sum <= width {
            widths = natural.map { $0 * width / sum }
        } else {
            // 넓은 칸을 줄여 맞춘다: 최소 폭을 먼저 주고 남은 폭을 원래 폭에 비례해 나눈다.
            let spare = width - Double(n) * minCol
            let over = natural.map { $0 - minCol }.reduce(0, +)
            widths = natural.map { minCol + ($0 - minCol) * spare / over }
        }
        for (r, row) in all.enumerated() {
            let head = r == 0
            let layouts = row.enumerated().map { j, cell in layout(styled(cell, head ? headFont : cellFont), widths[j] - pad * 2, spacing: 1.2) }
            let h = (layouts.map(\.height).max() ?? 0) + pad * 2
            let fill = headerBg
            pieces.append(Piece(item: L.Item(h, head ? 8 : 0, head)) { [self] c, left, top in
                var x = left
                for j in 0..<n {
                    if head { fillRect(c, fill, x, top, widths[j], h) }
                    strokeRect(c, x, top, widths[j], h)
                    for line in layouts[j].lines { draw(c, line, x + pad, top + pad + line.top) }
                    x += widths[j]
                }
            })
        }
    }

    // MARK: 인라인 서식

    private func styled(_ inline: PdfMarkdown.Inline, _ base: CTFont, _ color: CGColor? = nil) -> NSMutableAttributedString {
        let sb = plain(inline.text, base, color)
        let len = sb.length
        for sp in inline.spans {
            let s = min(max(sp.start, 0), len)
            let e = min(max(sp.end, s), len)
            if s == e { continue }
            let range = NSRange(location: s, length: e - s)
            func mapFont(_ f: (CTFont) -> CTFont) {
                var parts: [(NSRange, CTFont)] = []
                sb.enumerateAttribute(PdfMaker.fontKey, in: range) { v, r, _ in
                    let cur = PdfMaker.asFont(v) ?? base
                    parts.append((r, f(cur)))
                }
                for (r, font) in parts { sb.addAttribute(PdfMaker.fontKey, value: font, range: r) }
            }
            switch sp.style {
            case .BOLD: mapFont { PdfMaker.adding($0, .traitBold) }
            case .ITALIC, .MATH: mapFont { PdfMaker.adding($0, .traitItalic) }
            case .CODE:
                mapFont { PdfMaker.font(Double(CTFontGetSize($0)), .mono) }
                sb.addAttribute(PdfMaker.backgroundKey, value: codeBg, range: range)
            case .LINK:
                sb.addAttribute(PdfMaker.colorKey, value: linkColor, range: range)
                sb.addAttribute(PdfMaker.underlineKey, value: linkColor, range: range)
            }
        }
        return sb
    }
}
