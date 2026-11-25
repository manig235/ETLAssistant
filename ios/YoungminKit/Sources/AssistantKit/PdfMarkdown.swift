import Foundation
import ChatKit
import ETLKit

/// make_pdf가 받는 마크다운을 PDF용 블록으로 나눈다(Kotlin `pdf/PdfMarkdown.kt`, 그리기 API 없음).
///
/// 지원: 제목(`#`~`###`, 더 깊은 것은 `###`로), 문단, 글머리·번호 목록(들여쓰기 한 단계까지), 인용(`>`), 코드 블록(```),
/// 가로줄(`---`), 표(`|`와 구분 줄), 블록 수식(`$$ $$`, `\[ \]`), 인라인 `**굵게**`·`*기울임*`·`` `코드` ``·`[글](링크)`·
/// 인라인 수식(`\( \)`, `$ $`). 수식 스팬의 글은 유니코드 대체 글([MathText.toUnicode]).
/// HTML 태그는 해석하지 않는다(`<br>`만 줄바꿈). 스팬 위치는 Kotlin처럼 **UTF-16 단위**다(`NSRange`와 같다).
public enum PdfMarkdown {

    public enum Style: String, Sendable, Hashable { case BOLD, ITALIC, CODE, MATH, LINK }

    public struct Span: Sendable, Hashable {
        public var start: Int
        public var end: Int
        public var style: Style
        public var latex: String?
        public init(_ start: Int, _ end: Int, _ style: Style, latex: String? = nil) {
            self.start = start
            self.end = end
            self.style = style
            self.latex = latex
        }
    }

    /// 서식이 붙은 글. [text]는 표시할 글 그대로(마크다운 기호 제거).
    public struct Inline: Sendable, Hashable {
        public var text: String
        public var spans: [Span]
        public init(_ text: String, _ spans: [Span] = []) {
            self.text = text
            self.spans = spans
        }
    }

    public enum Block: Sendable, Hashable {
        case heading(level: Int, text: Inline)
        case paragraph(Inline)
        /// [level] 0 또는 1. [marker]는 "•"/"◦" 또는 "1." 같은 번호.
        case listItem(ordered: Bool, marker: String, level: Int, text: Inline)
        case quote(Inline)
        case code(String)
        case mathBlock(String)
        case table(header: [Inline], rows: [[Inline]])
        case rule
    }

    private static let HEADING = KRegex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private static let RULE = KRegex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private static let LIST = KRegex("^(\\s*)([-*+•]|\\d{1,3}[.)])\\s+(.*)$")
    private static let QUOTE = KRegex("^\\s{0,3}>\\s?(.*)$")
    private static let TABLE_SEP = KRegex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
    private static let BR = KRegex("<br\\s*/?>", .ignoreCase)

    public static func parse(_ markdown: String) -> [Block] {
        let lines = markdown.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\t", with: "    ").components(separatedBy: "\n")
        var out: [Block] = []
        var para: [String] = []
        var quote: [String] = []
        var lastList = -1 // out 안의 마지막 목록 항목 위치(이어지는 줄을 붙인다)
        var listCounters = [0, 0]

        func flushPara() {
            if !para.isEmpty { out.append(.paragraph(inline(para.map(\.kt).joined(separator: " ")))) }
            para.removeAll()
        }
        func flushQuote() {
            if !quote.isEmpty { out.append(.quote(inline(quote.map(\.kt).joined(separator: " ").kt))) }
            quote.removeAll()
        }
        func flushAll() {
            flushPara()
            flushQuote()
        }

        var i = 0
        while i < lines.count {
            let line = lines[i]
            let trimmed = line.kt
            // 코드 블록
            if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                flushAll()
                lastList = -1
                let fence = String(trimmed.prefix(3))
                var body: [String] = []
                i += 1
                while i < lines.count && !lines[i].kt.hasPrefix(fence) {
                    body.append(lines[i])
                    i += 1
                }
                i += 1 // 닫는 줄(없으면 끝까지)
                out.append(.code(trimEnd(body.joined(separator: "\n"))))
                continue
            }
            if trimmed.isEmpty {
                flushAll()
                // 빈 줄 하나로는 목록이 끝나지 않는다(느슨한 목록). 다음 줄이 목록도 들여쓰기도 아니면 끝난다.
                i += 1
                continue
            }
            // 블록 수식
            if let math = blockMath(lines, i) {
                flushAll()
                lastList = -1
                out.append(.mathBlock(math.latex))
                i = math.end + 1
                continue
            }
            // 표: 이 줄에 |가 있고 다음 줄이 구분 줄
            if line.contains("|") && i + 1 < lines.count && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains("-") {
                flushAll()
                lastList = -1
                let header = cells(line)
                var rows: [[Inline]] = []
                i += 2
                while i < lines.count && lines[i].contains("|") && !lines[i].isBlankK {
                    let r = cells(lines[i])
                    rows.append((0..<header.count).map { $0 < r.count ? r[$0] : Inline("") })
                    i += 1
                }
                out.append(.table(header: header, rows: rows))
                continue
            }
            if let hm = HEADING.find(line) {
                flushAll()
                lastList = -1
                out.append(.heading(level: min(hm.groupValues[1].utf16.count, 3), text: inline(hm.groupValues[2])))
                i += 1
                continue
            }
            if RULE.matches(line) {
                flushAll()
                lastList = -1
                out.append(.rule)
                i += 1
                continue
            }
            if let lm = LIST.find(line) {
                flushAll()
                let indent = lm.groupValues[1].utf16.count
                let level = indent >= 2 && lastList >= 0 ? 1 : 0
                let raw = lm.groupValues[2]
                let ordered = raw.first?.isASCII == true && raw.first?.isNumber == true
                if level == 0 { listCounters[1] = 0 }
                if lastList < 0 && level == 0 { listCounters[0] = 0 }
                let marker: String
                if ordered {
                    // "1. 1. 1."처럼 같은 번호만 써도 이어지는 목록이면 차례로 센다.
                    let given = Int(raw.dropLast()) ?? 0
                    let prev = listCounters[level]
                    listCounters[level] = lastList >= 0 && prev > 0 && given <= prev ? prev + 1 : max(given, 1)
                    marker = "\(listCounters[level])."
                } else {
                    marker = level == 0 ? "•" : "◦"
                }
                out.append(.listItem(ordered: ordered, marker: marker, level: level, text: inline(lm.groupValues[3])))
                lastList = out.count - 1
                i += 1
                continue
            }
            // 목록 항목에 이어지는 들여쓴 줄
            if lastList >= 0 && line.hasPrefix("  ") && para.isEmpty && quote.isEmpty,
               case let .listItem(ordered, marker, level, text) = out[lastList] {
                out[lastList] = .listItem(ordered: ordered, marker: marker, level: level, text: append(text, inline(trimmed)))
                i += 1
                continue
            }
            if let qm = QUOTE.find(line) {
                flushPara()
                lastList = -1
                quote.append(qm.groupValues[1])
                i += 1
                continue
            }
            flushQuote()
            lastList = -1
            para.append(line)
            i += 1
        }
        flushAll()
        return out
    }

    /// Kotlin `trimEnd()`.
    private static func trimEnd(_ s: String) -> String {
        var t = Substring(s)
        while let last = t.last, last.isWhitespace { t = t.dropLast() }
        return String(t)
    }

    /// [from] 줄이 블록 수식이면 (끝 줄, 본문). 닫는 구분자 뒤에 글이 있거나 닫히지 않으면 nil.
    private static func blockMath(_ lines: [String], _ from: Int) -> (end: Int, latex: String)? {
        let first = lines[from].kt
        let open: String, close: String
        if first.hasPrefix("\\[") {
            (open, close) = ("\\[", "\\]")
        } else if first.hasPrefix("$$") {
            (open, close) = ("$$", "$$")
        } else {
            return nil
        }
        var body = ""
        var rest = String(first.dropFirst(open.count))
        var line = from
        while true {
            if let r = rest.range(of: close) {
                if !String(rest[r.upperBound...]).isBlankK { return nil }
                body += rest[..<r.lowerBound]
                let latex = body.kt
                return latex.isEmpty ? nil : (line, latex)
            }
            body += rest + "\n"
            line += 1
            if line >= lines.count || line - from > 60 { return nil }
            rest = lines[line].kt
            if rest.hasPrefix("```") { return nil }
        }
    }

    /// 표 한 줄을 칸으로. 앞뒤 `|`는 빼고, `\|`는 글자 `|`, 코드(`` ` ``) 안의 `|`는 나누지 않는다.
    static func cells(_ line: String) -> [Inline] {
        var s = Array(line.kt.utf16)
        let bar = u("|"), backslash = u("\\"), tick = u("`")
        if s.first == bar { s.removeFirst() }
        if s.last == bar && !(s.count >= 2 && s[s.count - 2] == backslash) { s.removeLast() }
        var out: [[UInt16]] = []
        var cur: [UInt16] = []
        var inCode = false
        var k = 0
        while k < s.count {
            let c = s[k]
            if c == backslash && k + 1 < s.count && s[k + 1] == bar {
                cur.append(bar)
                k += 1
            } else if c == tick {
                inCode.toggle()
                cur.append(c)
            } else if c == bar && !inCode {
                out.append(cur)
                cur.removeAll()
            } else {
                cur.append(c)
            }
            k += 1
        }
        out.append(cur)
        return out.map { inline(str($0).kt) }
    }

    private static func append(_ a: Inline, _ b: Inline) -> Inline {
        let off = a.text.utf16.count + 1
        return Inline(a.text + " " + b.text, a.spans + b.spans.map { Span($0.start + off, $0.end + off, $0.style, latex: $0.latex) })
    }

    /// 인라인 서식을 풀어 글과 스팬으로.
    public static func inline(_ src: String) -> Inline {
        var out: [UInt16] = []
        var spans: [Span] = []
        inlineInto(Array(BR.replace(src, "\n").utf16), &out, &spans, 0)
        return Inline(str(out), stableSorted(spans) { a, b in a.start != b.start ? a.start < b.start : -a.end < -b.end })
    }

    // MARK: UTF-16 보조

    private static func u(_ c: Unicode.Scalar) -> UInt16 { UInt16(c.value) }
    private static func str<S: Collection>(_ a: S) -> String where S.Element == UInt16 { String(decoding: a, as: UTF16.self) }

    private static func startsWith(_ s: [UInt16], _ lit: String, at i: Int) -> Bool {
        let n = Array(lit.utf16)
        guard i >= 0, i + n.count <= s.count else { return false }
        for k in 0..<n.count where s[i + k] != n[k] { return false }
        return true
    }

    private static func indexOf(_ s: [UInt16], _ lit: String, from: Int) -> Int {
        let n = Array(lit.utf16)
        var i = max(0, from)
        guard !n.isEmpty else { return i <= s.count ? i : -1 }
        while i + n.count <= s.count {
            if startsWith(s, lit, at: i) { return i }
            i += 1
        }
        return -1
    }

    private static func scalar(_ c: UInt16) -> Unicode.Scalar? { Unicode.Scalar(UInt32(c)) }

    /// Kotlin `Char.isWhitespace()`.
    private static func isWhitespace(_ c: UInt16?) -> Bool {
        guard let c else { return false }
        switch c {
        case 0x09...0x0D, 0x1C...0x1F: return true
        default: return scalar(c)?.properties.isWhitespace ?? false
        }
    }

    /// Kotlin `Char.isLetterOrDigit()`.
    private static func isWordChar(_ c: UInt16?) -> Bool {
        guard let c, let sc = scalar(c) else { return false }
        return sc.properties.isAlphabetic || sc.properties.numericType == .decimal || CharacterSet.letters.contains(sc)
    }

    private static func isDigit(_ c: UInt16) -> Bool {
        guard let sc = scalar(c) else { return false }
        return sc.properties.numericType == .decimal
    }

    private static func isBlank(_ s: ArraySlice<UInt16>) -> Bool { s.allSatisfy { isWhitespace($0) } }

    private static func trim(_ s: ArraySlice<UInt16>) -> ArraySlice<UInt16> {
        var a = s.startIndex, b = s.endIndex
        while a < b && isWhitespace(s[a]) { a += 1 }
        while b > a && isWhitespace(s[b - 1]) { b -= 1 }
        return s[a..<b]
    }

    /// 인라인 수식(Kotlin `MarkdownLite.inlineMathAt`과 같은 규칙: `\( \)`, `\[ \]`, `$$ $$`, `$ $`).
    private static func inlineMathAt(_ s: [UInt16], _ i: Int) -> (end: Int, latex: String)? {
        guard i >= 0, i < s.count else { return nil }
        func delimited(_ open: String, _ close: String) -> (end: Int, latex: String)? {
            if !startsWith(s, open, at: i) { return nil }
            let o = open.utf16.count
            let end = indexOf(s, close, from: i + o)
            if end < 0 { return nil }
            let body = s[(i + o)..<end]
            if isBlank(body) { return nil }
            return (end + close.utf16.count, str(trim(body)))
        }
        let dollar = u("$")
        if i > 0 && s[i - 1] == u("\\") && s[i] == dollar { return nil } // \$는 글자 그대로
        if let m = delimited("\\(", "\\)") { return m }
        if let m = delimited("\\[", "\\]") { return m }
        if startsWith(s, "$$", at: i) { return delimited("$$", "$$") }
        if s[i] != dollar { return nil }
        // 단일 $: 여는 $ 바로 뒤가 공백이 아니고, 닫는 $ 바로 앞이 공백이 아니며 뒤에 숫자가 오지 않아야 한다.
        if i + 1 >= s.count || isWhitespace(s[i + 1]) || s[i + 1] == dollar { return nil }
        var j = i + 1
        while j < s.count {
            if s[j] == dollar && s[j - 1] != u("\\") {
                if isWhitespace(s[j - 1]) { return nil }
                if j + 1 < s.count && isDigit(s[j + 1]) { return nil }
                let body = s[(i + 1)..<j]
                return isBlank(body) ? nil : (j + 1, str(body))
            }
            j += 1
        }
        return nil
    }

    private static let ESCAPABLE: Set<UInt16> = Set("\\`*_{}[]()#+-.!|>~$".utf16)

    private static func inlineInto(_ s: [UInt16], _ out: inout [UInt16], _ spans: inout [Span], _ depth: Int) {
        let backslash = u("\\"), dollar = u("$"), tick = u("`"), star = u("*"), under = u("_"), lbr = u("[")
        func at(_ k: Int) -> UInt16? { k >= 0 && k < s.count ? s[k] : nil }
        var i = 0
        while i < s.count {
            let c = s[i]
            if c == backslash || c == dollar {
                if let m = inlineMathAt(s, i) {
                    let start = out.count
                    let uni = MathText.toUnicode(m.latex)
                    out.append(contentsOf: (uni.isBlankK ? m.latex : uni).utf16)
                    spans.append(Span(start, out.count, .MATH, latex: m.latex))
                    i = m.end
                    continue
                }
                // 마크다운 이스케이프: \* \_ \# 등은 글자 그대로
                if c == backslash && i + 1 < s.count && ESCAPABLE.contains(s[i + 1]) {
                    out.append(s[i + 1])
                    i += 2
                    continue
                }
            }
            if c == tick {
                if let close = s[(i + 1)...].firstIndex(of: tick), close > i + 1 {
                    let start = out.count
                    out.append(contentsOf: s[(i + 1)..<close])
                    spans.append(Span(start, out.count, .CODE))
                    i = close + 1
                    continue
                }
            }
            if depth < 4 && (startsWith(s, "**", at: i) || startsWith(s, "__", at: i)) {
                let mark = str(s[i..<(i + 2)])
                let close = indexOf(s, mark, from: i + 2)
                if close > i + 2 && (mark == "**" || !isWordChar(at(close + 2))) {
                    let start = out.count
                    inlineInto(Array(s[(i + 2)..<close]), &out, &spans, depth + 1)
                    spans.append(Span(start, out.count, .BOLD))
                    i = close + 2
                    continue
                }
            }
            if depth < 4 && startsWith(s, "~~", at: i) {
                let close = indexOf(s, "~~", from: i + 2)
                if close > i + 2 { // 취소선은 글만 남긴다
                    inlineInto(Array(s[(i + 2)..<close]), &out, &spans, depth + 1)
                    i = close + 2
                    continue
                }
            }
            if depth < 4 && (c == star || c == under) && i + 1 < s.count && !isWhitespace(s[i + 1]) && s[i + 1] != c &&
                (c == star || !isWordChar(at(i - 1))) {
                var close = i + 1
                var found = -1
                while close < s.count {
                    guard let next = s[close...].firstIndex(of: c) else { break }
                    close = next
                    let doubled = at(close + 1) == c
                    if !doubled && !isWhitespace(s[close - 1]) && (c == star || !isWordChar(at(close + 1))) { found = close; break }
                    close += doubled ? 2 : 1
                }
                if found > i + 1 {
                    let start = out.count
                    inlineInto(Array(s[(i + 1)..<found]), &out, &spans, depth + 1)
                    spans.append(Span(start, out.count, .ITALIC))
                    i = found + 1
                    continue
                }
            }
            if c == lbr {
                let mid = indexOf(s, "](", from: i + 1)
                let end = mid > i ? (s[(mid + 2)...].firstIndex(of: u(")")) ?? -1) : -1
                if mid > i + 1 && end > mid + 1 {
                    let start = out.count
                    inlineInto(Array(s[(i + 1)..<mid]), &out, &spans, depth + 1)
                    spans.append(Span(start, out.count, .LINK))
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i += 1
        }
    }
}
