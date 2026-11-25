import Foundation

/// 모델 응답에 자주 나오는 마크다운만 처리하는 작은 변환기(Kotlin `MarkdownLite`). UI 프레임워크에 의존하지 않는다.
///
/// 지원: `**굵게**`, `*기울임*`·`_기울임_`(iOS에서 더함, 아래 규칙), `` `코드` ``, 코드 블록(```), `#` 제목, `-`/`*` 목록, `[글](https://…)` 링크, 표(셀을 ` · `로 이어 붙임).
/// 링크는 http/https만 허용한다. 수식(LaTeX): 인라인 `\( \)`·`$ $`, 블록 `\[ \]`·`$$ $$`. 출력 글에는 원문을 그대로 두고
/// `.math`/`.mathBlock` 스팬으로 표시만 한다. 닫히지 않은 수식(스트리밍 중)은 표시하지 않는다. 코드 안은 해석하지 않는다.
///
/// 스팬의 `start`/`end`는 **UTF-16 위치**다(Kotlin과 같음, `NSRange`와 바로 맞는다).
public enum MarkdownLite {

    public enum Kind: String, Sendable, Hashable, CaseIterable {
        case bold = "BOLD", italic = "ITALIC", code = "CODE", codeBlock = "CODE_BLOCK", header = "HEADER", link = "LINK", math = "MATH", mathBlock = "MATH_BLOCK"
    }

    public struct Span: Sendable, Hashable {
        public var start: Int
        public var end: Int
        public var kind: Kind
        public var url: String?
        /// 수식 스팬의 LaTeX 본문(구분자 제외).
        public var latex: String?
        /// 디스플레이 스타일(`\[ \]`, `$$ $$`)인지.
        public var display: Bool

        public init(_ start: Int, _ end: Int, _ kind: Kind, _ url: String? = nil, latex: String? = nil, display: Bool = false) {
            self.start = start
            self.end = end
            self.kind = kind
            self.url = url
            self.latex = latex
            self.display = display
        }
    }

    public struct Result: Sendable, Hashable {
        public var text: String
        public var spans: [Span]
        public init(_ text: String, _ spans: [Span]) {
            self.text = text
            self.spans = spans
        }
    }

    private static let ws = "[ \\t\\n\\x0B\\f\\r]"
    private static let HEADER = try! NSRegularExpression(pattern: "^\(ws){0,3}#{1,6}\(ws)+(.*)$")
    private static let BULLET = try! NSRegularExpression(pattern: "^(\(ws)*)[-*+]\(ws)+(.*)$")
    private static let TABLE_SEP = try! NSRegularExpression(pattern: "^\(ws)*\\|?\(ws)*:?-{2,}:?\(ws)*(\\|\(ws)*:?-{2,}:?\(ws)*)*\\|?\(ws)*$")

    private static func find(_ re: NSRegularExpression, _ line: String) -> [String]? {
        let ns = line as NSString
        guard let m = re.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }

    private static func matches(_ re: NSRegularExpression, _ line: String) -> Bool {
        let n = (line as NSString).length
        guard let m = re.firstMatch(in: line, range: NSRange(location: 0, length: n)) else { return false }
        return m.range.location == 0 && m.range.length == n
    }

    private static let NL: UInt16 = 10
    private static let FENCE = T16.lit("```")

    public static func render(_ src: String) -> Result {
        var out: [UInt16] = []
        var spans: [Span] = []
        let normalized = src.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let lines = T16.split(T16.units(normalized), NL)
        var inFence = false
        var fenceStart = 0

        func closeFence() {
            if let last = out.last, last == NL { out.removeLast() }
            if out.count > fenceStart { spans.append(Span(fenceStart, out.count, .codeBlock)) }
            out.append(NL)
        }

        var idx = 0
        while idx < lines.count {
            let line = lines[idx]
            idx += 1
            let trimmed = T16.trim(line)
            if T16.startsWith(trimmed, FENCE) {
                if !inFence {
                    inFence = true
                    fenceStart = out.count
                } else {
                    inFence = false
                    closeFence()
                }
                continue
            }
            if inFence {
                out.append(contentsOf: line)
                out.append(NL)
                continue
            }
            if let block = blockMath(lines, idx - 1) {
                let start = out.count
                var joined: [UInt16] = []
                for (k, l) in lines[(idx - 1)...block.endLine].enumerated() {
                    if k > 0 { joined.append(NL) }
                    joined.append(contentsOf: T16.trim(l))
                }
                out.append(contentsOf: joined)
                spans.append(Span(start, out.count, .mathBlock, latex: block.latex, display: true))
                out.append(NL)
                idx = block.endLine + 1
                continue
            }
            let lineString = T16.string(line)
            if matches(TABLE_SEP, lineString) && line.contains(T16.ch("-"))
                && (line.contains(T16.ch("|")) || T16.startsWith(trimmed, T16.lit(":-"))) { continue }

            let start = out.count
            if let header = find(HEADER, lineString) {
                inline(T16.trim(T16.units(header[1])), &out, &spans)
                spans.append(Span(start, out.count, .header))
            } else if let bullet = find(BULLET, lineString) {
                out.append(contentsOf: T16.units(bullet[1]))
                out.append(contentsOf: T16.lit("• "))
                inline(T16.units(bullet[2]), &out, &spans)
            } else if T16.startsWith(trimmed, T16.lit("|")) && trimmed.filter({ $0 == T16.ch("|") }).count >= 2 {
                let cells = T16.split(T16.trim(trimmed, char: T16.ch("|")), T16.ch("|")).map { T16.trim($0) }
                var joined: [UInt16] = []
                for (k, c) in cells.enumerated() {
                    if k > 0 { joined.append(contentsOf: T16.lit(" · ")) }
                    joined.append(contentsOf: c)
                }
                inline(joined, &out, &spans)
            } else {
                inline(line, &out, &spans)
            }
            out.append(NL)
        }
        if inFence { closeFence() }
        while let last = out.last, last == NL { out.removeLast() }
        let len = out.count
        let fixed: [Span] = spans.compactMap { s in
            if s.start >= len { return nil }
            if s.end > len { var c = s; c.end = len; return c }
            return s
        }.filter { $0.end > $0.start }
        return Result(T16.string(out), fixed)
    }

    private struct Block { let endLine: Int; let latex: String }

    /// `from` 줄이 블록 수식의 시작이면 끝 줄과 본문을 준다. 닫는 구분자 뒤에 다른 글이 있으면 블록이 아니다.
    private static func blockMath(_ lines: [[UInt16]], _ from: Int) -> Block? {
        let first = T16.trim(lines[from])
        let open: [UInt16]
        let close: [UInt16]
        if T16.startsWith(first, T16.lit("\\[")) { open = T16.lit("\\["); close = T16.lit("\\]") }
        else if T16.startsWith(first, T16.lit("$$")) { open = T16.lit("$$"); close = T16.lit("$$") }
        else { return nil }
        var body: [UInt16] = []
        var rest = Array(first[open.count...])
        var line = from
        while true {
            let at = T16.indexOf(rest, close, from: 0)
            if at >= 0 {
                if !T16.isBlank(rest[(at + close.count)...]) { return nil }
                body.append(contentsOf: rest[0..<at])
                let latex = T16.trim(body)
                return latex.isEmpty ? nil : Block(endLine: line, latex: T16.string(latex))
            }
            body.append(contentsOf: rest)
            body.append(NL)
            line += 1
            if line >= lines.count { return nil }
            let next = T16.trim(lines[line])
            if T16.startsWith(next, FENCE) { return nil }
            rest = next
        }
    }

    /// `i`에서 인라인 수식이 시작하면 (끝 위치(배타, UTF-16), 본문, 디스플레이 여부)를 준다.
    static func inlineMathAt(_ s: String, _ i: Int) -> (end: Int, latex: String, display: Bool)? {
        inlineMathAt(T16.units(s), i)
    }

    static func inlineMathAt(_ s: [UInt16], _ i: Int) -> (end: Int, latex: String, display: Bool)? {
        guard i >= 0, i < s.count else { return nil }
        func delimited(_ open: String, _ close: String, _ display: Bool) -> (end: Int, latex: String, display: Bool)? {
            let o = T16.lit(open), c = T16.lit(close)
            if !T16.startsWith(s, o, at: i) { return nil }
            let end = T16.indexOf(s, c, from: i + o.count)
            if end < 0 { return nil }
            let body = s[(i + o.count)..<end]
            if T16.isBlank(body) { return nil }
            return (end + c.count, T16.string(T16.trim(body)), display)
        }
        let dollar = T16.ch("$")
        if i > 0 && s[i - 1] == T16.ch("\\") && s[i] == dollar { return nil } // \$는 글자 그대로
        if let m = delimited("\\(", "\\)", false) { return m }
        if let m = delimited("\\[", "\\]", true) { return m }
        if T16.startsWith(s, T16.lit("$$"), at: i) { return delimited("$$", "$$", true) }
        if s[i] != dollar { return nil }
        // 단일 $: 여는 $ 바로 뒤가 공백이 아니고, 닫는 $ 바로 앞이 공백이 아니며 뒤에 숫자가 오지 않아야 한다.
        if i + 1 >= s.count || T16.isWhitespace(s[i + 1]) || s[i + 1] == dollar { return nil }
        var j = i + 1
        while j < s.count {
            if s[j] == dollar && s[j - 1] != T16.ch("\\") {
                if T16.isWhitespace(s[j - 1]) { return nil }
                if j + 1 < s.count && T16.isDigit(s[j + 1]) { return nil }
                let body = s[(i + 1)..<j]
                return T16.isBlank(body) ? nil : (j + 1, T16.string(body), false)
            }
            j += 1
        }
        return nil
    }

    /// `*기울임*`·`_기울임_`(안드로이드 채팅 MarkdownLite에는 없고, 같은 앱의 PDF 변환 규칙(`PdfMarkdown`)과 같게 더함).
    /// 여는 표시 바로 뒤와 닫는 표시 바로 앞은 공백이 아니어야 하고, `**`처럼 겹친 표시는 건너뛴다.
    /// `_`는 단어 안(`snake_case_name`)에서는 표시가 아니다. `\*`(역슬래시 뒤)는 여는 표시가 아니다. 닫는 위치를 준다.
    static func italicClose(_ s: [UInt16], _ i: Int) -> Int? {
        let c = s[i]
        let under = T16.ch("_")
        func at(_ k: Int) -> UInt16? { k >= 0 && k < s.count ? s[k] : nil }
        guard let next = at(i + 1), !T16.isWhitespace(next), next != c else { return nil }
        if at(i - 1) == T16.ch("\\") { return nil }
        if c == under && isWordChar(at(i - 1)) { return nil }
        var k = i + 1
        while k < s.count {
            guard s[k] == c else { k += 1; continue }
            if at(k + 1) == c { k += 2; continue } // 겹친 표시(굵게)는 건너뛴다
            if !T16.isWhitespace(s[k - 1]) && s[k - 1] != T16.ch("\\") && (c != under || !isWordChar(at(k + 1))) {
                return k > i + 1 ? k : nil
            }
            k += 1
        }
        return nil
    }

    private static func isWordChar(_ c: UInt16?) -> Bool {
        guard let c else { return false }
        return T16.isLetter(c) || T16.isDigit(c)
    }

    private static func inline(_ s: [UInt16], _ out: inout [UInt16], _ spans: inout [Span]) {
        let star = T16.ch("*"), under = T16.ch("_"), tick = T16.ch("`"), lbr = T16.ch("["), backslash = T16.ch("\\"), dollar = T16.ch("$")
        var i = 0
        while i < s.count {
            let c = s[i]
            if c == backslash || c == dollar, let m = inlineMathAt(s, i) {
                let start = out.count
                out.append(contentsOf: s[i..<m.end])
                spans.append(Span(start, out.count, .math, latex: m.latex, display: m.display))
                i = m.end
                continue
            }
            if c == star && T16.startsWith(s, T16.lit("**"), at: i) {
                let close = T16.indexOf(s, T16.lit("**"), from: i + 2)
                if close > i + 2 {
                    let start = out.count
                    inline(Array(s[(i + 2)..<close]), &out, &spans)
                    spans.append(Span(start, out.count, .bold))
                    i = close + 2
                    continue
                }
            }
            if (c == star || c == under), let close = italicClose(s, i) {
                let start = out.count
                inline(Array(s[(i + 1)..<close]), &out, &spans)
                spans.append(Span(start, out.count, .italic))
                i = close + 1
                continue
            }
            if c == tick {
                let close = T16.indexOf(s, tick, from: i + 1)
                if close > i + 1 {
                    let start = out.count
                    out.append(contentsOf: s[(i + 1)..<close])
                    spans.append(Span(start, out.count, .code))
                    i = close + 1
                    continue
                }
            }
            if c == lbr {
                let mid = T16.indexOf(s, T16.lit("]("), from: i + 1)
                let end = mid > i ? T16.indexOf(s, T16.ch(")"), from: mid + 2) : -1
                if mid > i + 1 && end > mid + 2 {
                    let url = T16.string(T16.trim(s[(mid + 2)..<end]))
                    if url.hasPrefix("https://") || url.hasPrefix("http://") {
                        let start = out.count
                        out.append(contentsOf: s[(i + 1)..<mid])
                        spans.append(Span(start, out.count, .link, url))
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i += 1
        }
    }
}
