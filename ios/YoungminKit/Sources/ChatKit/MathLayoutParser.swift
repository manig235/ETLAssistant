import Foundation

// 채팅·PDF 수식 그리기(새로 만듦). 안드로이드는 JLatexMath(외부 라이브러리)로 그렸지만 iOS는 외부 의존성 없이
// 강의 답변에 자주 나오는 TeX 부분 집합만 직접 해석·배치한다. 모르는 명령이 있으면 `MathParseError`를 던지고,
// 화면은 원래처럼 `MathText.toUnicode` 글로 대신한다.

/// TeX 원자 종류(사이 간격 규칙에 쓴다).
public enum MathAtomClass: Int, Sendable, Hashable {
    case ord = 0, op, bin, rel, open, close, punct, inner
}

/// 글자 모양.
public enum MathFontStyle: String, Sendable, Hashable {
    /// 수식 변수(기울임).
    case italic
    case roman
    case bold
    case boldItalic
    case sans
    case mono
}

public enum MathAccentKind: String, Sendable, Hashable {
    case hat, widehat, check, tilde, widetilde, acute, grave, dot, ddot, breve, bar, vec
    case overline, underline, overrightarrow, overleftarrow, overleftrightarrow
    case overbrace, underbrace
}

public enum MathArrayKind: String, Sendable, Hashable {
    case matrix, pmatrix, bmatrix, Bmatrix, vmatrix, Vmatrix, smallmatrix
    case cases, aligned, gathered, array, substack
}

/// 해석한 수식 한 단위.
public indirect enum MathNode: Sendable, Hashable {
    /// 글자 하나 또는 기호(유니코드 글).
    case symbol(String, MathAtomClass, MathFontStyle)
    /// `\text{…}`: 공백을 그대로 둔다.
    case text(String, MathFontStyle)
    /// `{…}` 묶음.
    case group([MathNode])
    /// 분수(`rule`이 false면 `\binom`처럼 선이 없음). `forced`는 `\dfrac`(display)/`\tfrac`(text).
    case frac(num: [MathNode], den: [MathNode], rule: Bool, forced: MathStyleForce?, left: String, right: String)
    case sqrt(body: [MathNode], index: [MathNode]?)
    case scripts(base: MathNode, sup: [MathNode]?, sub: [MathNode]?)
    /// 큰 연산자(∑ ∫ …) 또는 글자 연산자(lim, sin …). `limits` nil이면 기본값.
    case largeOp(String, limits: Bool?, isText: Bool, defaultLimits: Bool)
    /// `\left( … \right)`. 빈 글은 `.`(없음).
    case delimited(left: String, body: [MathNode], right: String)
    /// `\big(` 같은 정해진 크기의 괄호(1…4).
    case sizedDelim(String, size: Int, MathAtomClass)
    case accent(body: [MathNode], kind: MathAccentKind)
    /// 가로 간격(em).
    case space(Double)
    /// `\displaystyle` 등: 뒤따르는 원자의 스타일을 바꾼다.
    case styleChange(MathStyleForce)
    case array(rows: [[[MathNode]]], kind: MathArrayKind, columns: [MathColumnAlign])
    case boxed([MathNode])
    /// `\overset`·`\underset`·`\stackrel`.
    case overUnder(base: [MathNode], over: [MathNode]?, under: [MathNode]?, cls: MathAtomClass)
    /// `\xrightarrow[아래]{위}`.
    case xArrow(left: Bool, over: [MathNode], under: [MathNode]?)
    case phantom([MathNode], horizontal: Bool, vertical: Bool)
}

public enum MathStyleForce: String, Sendable, Hashable { case display, text, script, scriptScript }
public enum MathColumnAlign: String, Sendable, Hashable { case left, center, right }

public struct MathParseError: Error, Sendable, Hashable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// TeX 부분 집합 해석기.
public struct MathParser {
    private let s: [Character]
    private var i = 0
    private var depth = 0
    /// `\bf`·`\rm`처럼 묶음 끝까지 이어지는 글꼴, `\mathbf{}` 안의 글꼴.
    private var font: MathFontStyle?
    private var blackboard = false
    private var calligraphic = false
    private var fraktur = false

    public static let MAX_LENGTH = 4000
    static let MAX_DEPTH = 40

    public init(_ latex: String) { s = Array(latex) }

    /// 수식 전체를 해석한다. 맨 바깥의 `\\`는 가운데 정렬 여러 줄로 본다.
    public static func parse(_ latex: String) throws -> [MathNode] {
        if latex.count > MAX_LENGTH { throw MathParseError("too long") }
        var p = MathParser(latex)
        let rows = try p.parseArrayBody(endName: nil)
        if p.i < p.s.count { throw MathParseError("unexpected \(p.s[p.i])") }
        if rows.count == 1 && rows[0].count == 1 { return rows[0][0] }
        if rows.count == 1 { return rows[0].flatMap { $0 } }
        return [.array(rows: rows, kind: .gathered, columns: [])]
    }

    // MARK: 글자 읽기

    private var atEnd: Bool { i >= s.count }
    private func peek(_ k: Int = 0) -> Character? { i + k < s.count ? s[i + k] : nil }

    private mutating func skipSpaces() {
        while let c = peek(), c.isWhitespace { i += 1 }
        // TeX 주석(%…줄 끝)
        if peek() == "%" {
            while let c = peek(), c != "\n" { i += 1 }
            skipSpaces()
        }
    }

    /// `\이름`을 미리 본다(읽지 않음).
    private func peekCommand() -> String? {
        guard peek() == "\\", let c = peek(1) else { return nil }
        if !c.isLetter { return String(c) }
        var k = i + 1
        var name = ""
        while k < s.count, s[k].isLetter, s[k].isASCII { name.append(s[k]); k += 1 }
        return name.isEmpty ? String(c) : name
    }

    private mutating func readCommand() -> String {
        i += 1 // '\'
        guard let c = peek() else { return "" }
        if !(c.isLetter && c.isASCII) { i += 1; return String(c) }
        var name = ""
        while let c = peek(), c.isLetter, c.isASCII { name.append(c); i += 1 }
        return name
    }

    // MARK: 목록

    private enum Stop { case brace, bracket, right, cell, endOfInput }

    /// 원자 목록. `&`·`\\`·`\end`·`\right`·`}`·(`bracket`이면) `]`에서 멈춘다(읽지 않음).
    private mutating func parseList(bracket: Bool = false) throws -> [MathNode] {
        depth += 1
        defer { depth -= 1 }
        if depth > Self.MAX_DEPTH { throw MathParseError("too deep") }
        let savedFont = font, savedBB = blackboard, savedCal = calligraphic, savedFrak = fraktur
        defer { font = savedFont; blackboard = savedBB; calligraphic = savedCal; fraktur = savedFrak }
        var out: [MathNode] = []
        while true {
            skipSpaces()
            guard let c = peek() else { break }
            if c == "}" || c == "&" { break }
            if bracket && c == "]" { break }
            if c == "\\" {
                let cmd = peekCommand()
                if cmd == "\\" || cmd == "right" || cmd == "end" || cmd == "middle" || cmd == "cr" { break }
                // 묶음 끝까지 가는 글꼴 바꾸기
                if let cmd, let f = Self.SWITCH_FONTS[cmd] {
                    _ = readCommand()
                    font = f; blackboard = false; calligraphic = cmd == "cal"; fraktur = false
                    continue
                }
                if let cmd, let st = Self.STYLE_CHANGES[cmd] {
                    _ = readCommand()
                    out.append(.styleChange(st))
                    continue
                }
                if cmd == "limits" || cmd == "nolimits" {
                    _ = readCommand()
                    try applyLimits(&out, cmd == "limits")
                    continue
                }
            }
            out.append(contentsOf: try parseAtomWithScripts())
        }
        return out
    }

    private func applyLimitsNode(_ n: MathNode, _ on: Bool) throws -> MathNode {
        switch n {
        case let .largeOp(sym, _, isText, def): return .largeOp(sym, limits: on, isText: isText, defaultLimits: def)
        case let .scripts(base, sup, sub): return .scripts(base: try applyLimitsNode(base, on), sup: sup, sub: sub)
        default: throw MathParseError("\\limits without operator")
        }
    }

    private func applyLimits(_ out: inout [MathNode], _ on: Bool) throws {
        guard let last = out.popLast() else { throw MathParseError("\\limits without operator") }
        out.append(try applyLimitsNode(last, on))
    }

    /// 원자 하나와 그 뒤의 `^`·`_`·`'`.
    private mutating func parseAtomWithScripts() throws -> [MathNode] {
        var base: MathNode
        if peek() == "^" || peek() == "_" {
            base = .group([])
        } else {
            let nodes = try parseBase()
            // 명령 하나가 여러 원자가 되는 경우(\pmod, \tag 등): 첨자는 마지막에만 붙는다.
            guard let last = nodes.last else { return [] }
            if nodes.count > 1 || isTransparent(last) {
                var head = Array(nodes.dropLast())
                head.append(contentsOf: try attachScripts(last))
                return head
            }
            base = last
        }
        return try attachScripts(base)
    }

    private func isTransparent(_ n: MathNode) -> Bool {
        if case .space = n { return true }
        if case .styleChange = n { return true }
        return false
    }

    private mutating func attachScripts(_ base0: MathNode) throws -> [MathNode] {
        var base = base0
        var sup: [MathNode]?
        var sub: [MathNode]?
        while true {
            skipSpaces()
            guard let c = peek() else { break }
            if c == "^" {
                i += 1
                if sup != nil { throw MathParseError("double superscript") }
                sup = try parseScriptArg()
            } else if c == "_" {
                i += 1
                if sub != nil { throw MathParseError("double subscript") }
                sub = try parseScriptArg()
            } else if c == "'" {
                var primes = ""
                while peek() == "'" { primes.append("′"); i += 1 }
                let p: MathNode = .symbol(primes == "′′" ? "″" : primes == "′′′" ? "‴" : primes, .ord, .roman)
                sup = (sup ?? []) + [p]
            } else if c == "\\", let cmd = peekCommand(), cmd == "limits" || cmd == "nolimits" {
                _ = readCommand()
                base = try applyLimitsNode(base, cmd == "limits")
            } else {
                break
            }
        }
        if sup == nil && sub == nil { return [base] }
        return [.scripts(base: base, sup: sup, sub: sub)]
    }

    private mutating func parseScriptArg() throws -> [MathNode] {
        skipSpaces()
        guard let c = peek() else { throw MathParseError("missing script") }
        if c == "{" { return try parseGroupBody() }
        if c == "\\" { return try parseBase() }
        if c == "}" || c == "&" || c == "^" || c == "_" { throw MathParseError("missing script") }
        i += 1
        return [charNode(c)]
    }

    /// `{…}`를 읽어 안의 목록을 준다.
    private mutating func parseGroupBody() throws -> [MathNode] {
        guard peek() == "{" else { throw MathParseError("expected {") }
        i += 1
        let body = try parseList()
        guard peek() == "}" else { throw MathParseError("missing }") }
        i += 1
        return body
    }

    /// 명령 인자 하나(묶음 또는 한 단위).
    private mutating func parseArg() throws -> [MathNode] {
        skipSpaces()
        guard let c = peek() else { throw MathParseError("missing argument") }
        if c == "{" { return try parseGroupBody() }
        if c == "\\" { return try parseBase() }
        if c == "}" || c == "&" || c == "^" || c == "_" { throw MathParseError("missing argument") }
        i += 1
        return [charNode(c)]
    }

    /// `[…]` 선택 인자(없으면 nil).
    private mutating func parseOptionalArg() throws -> [MathNode]? {
        skipSpaces()
        guard peek() == "[" else { return nil }
        i += 1
        let body = try parseList(bracket: true)
        guard peek() == "]" else { throw MathParseError("missing ]") }
        i += 1
        return body
    }

    /// `{…}`의 날것 글(짝 맞는 괄호까지).
    private mutating func readRawGroup() throws -> String {
        skipSpaces()
        guard peek() == "{" else {
            // 한 글자 인자
            guard let c = peek() else { throw MathParseError("missing argument") }
            if c == "\\" { return "\\" + readCommand() }
            i += 1
            return String(c)
        }
        i += 1
        var level = 1
        var out = ""
        while let c = peek() {
            if c == "\\", let n = peek(1) {
                out.append(c); out.append(n); i += 2
                continue
            }
            if c == "{" { level += 1 }
            if c == "}" {
                level -= 1
                if level == 0 { i += 1; return out }
            }
            out.append(c)
            i += 1
        }
        throw MathParseError("missing }")
    }

    // MARK: 원자

    private mutating func parseBase() throws -> [MathNode] {
        skipSpaces()
        guard let c = peek() else { throw MathParseError("unexpected end") }
        if c == "{" { return [.group(try parseGroupBody())] }
        if c == "\\" { return try parseCommand() }
        if c == "}" { throw MathParseError("unexpected }") }
        if c == "~" { i += 1; return [.space(0.333)] }
        if c == "#" || c == "$" { throw MathParseError("unexpected \(c)") }
        i += 1
        return [charNode(c)]
    }

    /// 수식 안의 글자 하나.
    private func charNode(_ c: Character) -> MathNode {
        let str = String(c)
        if c.isLetter && c.isASCII {
            if blackboard, let m = Self.mapAlphabet(c, Self.BLACKBOARD) { return .symbol(m, .ord, .roman) }
            if calligraphic, let m = Self.mapAlphabet(c, Self.SCRIPT) { return .symbol(m, .ord, .roman) }
            if fraktur, let m = Self.mapAlphabet(c, Self.FRAKTUR) { return .symbol(m, .ord, .roman) }
            return .symbol(str, .ord, font ?? .italic)
        }
        if c.isNumber && c.isASCII {
            return .symbol(str, .ord, font == .bold || font == .boldItalic ? .bold : font == .sans ? .sans : font == .mono ? .mono : .roman)
        }
        if let (u, cls) = Self.CHAR_CLASS[c] { return .symbol(u, cls, font == .bold ? .bold : .roman) }
        if let g = c.unicodeScalars.first, c.unicodeScalars.count == 1, Self.isGreekLower(g) {
            return .symbol(str, .ord, font ?? .italic)
        }
        if let (u, cls) = Self.UNICODE_CLASS[c] { return .symbol(u, cls, .roman) }
        return .symbol(str, .ord, font == .bold ? .bold : .roman)
    }

    private static func isGreekLower(_ u: Unicode.Scalar) -> Bool { (0x03B1...0x03C9).contains(u.value) || [0x03D1, 0x03D5, 0x03D6, 0x03F1, 0x03F5].contains(u.value) }

    private mutating func parseCommand() throws -> [MathNode] {
        let name = readCommand()
        if name.isEmpty { throw MathParseError("lone backslash") }

        // 한 글자 명령
        switch name {
        case ",": return [.space(3.0 / 18)]
        case ":", ">": return [.space(4.0 / 18)]
        case ";": return [.space(5.0 / 18)]
        case "!": return [.space(-3.0 / 18)]
        case " ": return [.space(0.333)]
        case "{": return [.symbol("{", .open, .roman)]
        case "}": return [.symbol("}", .close, .roman)]
        case "|": return [.symbol("‖", .ord, .roman)]
        case "%", "$", "#", "&", "_": return [.symbol(name, .ord, .roman)]
        case "/": return [.space(0)]
        default: break
        }

        if let g = Self.GREEK[name] {
            let upper = g.unicodeScalars.first.map { (0x0391...0x03A9).contains($0.value) } ?? false
            let style: MathFontStyle = font == .bold ? (upper ? .bold : .boldItalic) : (font ?? (upper ? .roman : .italic))
            return [.symbol(g, .ord, style)]
        }
        if let (sym, cls) = Self.SYMBOLS[name] { return [.symbol(sym, cls, font == .bold ? .bold : .roman)] }
        if let op = Self.LARGE_OPS[name] { return [.largeOp(op.0, limits: nil, isText: false, defaultLimits: op.1)] }
        if let op = Self.TEXT_OPS[name] { return [.largeOp(op.0, limits: nil, isText: true, defaultLimits: op.1)] }
        if let sp = Self.SPACES[name] { return [.space(sp)] }
        if let accent = MathAccentKind(rawValue: name) { return [.accent(body: try parseArg(), kind: accent)] }
        if let accent = Self.ACCENT_ALIASES[name] { return [.accent(body: try parseArg(), kind: accent)] }

        switch name {
        case "frac", "dfrac", "tfrac", "cfrac":
            let num = try parseArg()
            let den = try parseArg()
            let force: MathStyleForce? = name == "dfrac" || name == "cfrac" ? .display : name == "tfrac" ? .text : nil
            return [.frac(num: num, den: den, rule: true, forced: force, left: "", right: "")]
        case "binom", "dbinom", "tbinom":
            let num = try parseArg()
            let den = try parseArg()
            let force: MathStyleForce? = name == "dbinom" ? .display : name == "tbinom" ? .text : nil
            return [.frac(num: num, den: den, rule: false, forced: force, left: "(", right: ")")]
        case "sqrt":
            let index = try parseOptionalArg()
            return [.sqrt(body: try parseArg(), index: index)]
        case "left":
            let l = try readDelimiter()
            let body = try parseList()
            guard peekCommand() == "right" else { throw MathParseError("missing \\right") }
            _ = readCommand()
            let r = try readDelimiter()
            return [.delimited(left: l, body: body, right: r)]
        case "right", "middle":
            throw MathParseError("unexpected \\\(name)")
        case "big", "Big", "bigg", "Bigg", "bigl", "Bigl", "biggl", "Biggl", "bigr", "Bigr", "biggr", "Biggr", "bigm", "Bigm", "biggm", "Biggm":
            let size = name.hasPrefix("bigg") ? 3 : name.hasPrefix("Bigg") ? 4 : name.hasPrefix("Big") ? 2 : 1
            let cls: MathAtomClass = name.hasSuffix("l") ? .open : name.hasSuffix("r") ? .close : name.hasSuffix("m") ? .rel : .ord
            return [.sizedDelim(try readDelimiter(), size: size, cls)]
        case "mathbf", "boldsymbol", "bm", "pmb", "mathbfit":
            return [.group(try withFont(name == "mathbf" ? .bold : .boldItalic) { try $0.parseArg() })]
        case "mathrm", "mathup":
            return [.group(try withFont(.roman) { try $0.parseArg() })]
        case "mathnormal":
            return [.group(try withFont(nil) { try $0.parseArg() })]
        case "mathit":
            return [.group(try withFont(.italic) { try $0.parseArg() })]
        case "mathsf":
            return [.group(try withFont(.sans) { try $0.parseArg() })]
        case "mathtt":
            return [.group(try withFont(.mono) { try $0.parseArg() })]
        case "mathbb", "Bbb":
            return [.group(try withAlphabet(bb: true) { try $0.parseArg() })]
        case "mathcal", "mathscr":
            return [.group(try withAlphabet(cal: true) { try $0.parseArg() })]
        case "mathfrak":
            return [.group(try withAlphabet(frak: true) { try $0.parseArg() })]
        case "text", "textrm", "mbox", "textnormal", "hbox", "textup":
            return [.text(try readText(), .roman)]
        case "textbf":
            return [.text(try readText(), .bold)]
        case "textit", "emph", "textsl":
            return [.text(try readText(), .italic)]
        case "textsf":
            return [.text(try readText(), .sans)]
        case "texttt":
            return [.text(try readText(), .mono)]
        case "operatorname", "operatornamewithlimits":
            var star = name == "operatornamewithlimits"
            if peek() == "*" { i += 1; star = true }
            let raw = try readText()
            return [.largeOp(raw, limits: nil, isText: true, defaultLimits: star)]
        case "not":
            skipSpaces()
            let next = try parseBase()
            guard next.count == 1, case let .symbol(sym, cls, st) = next[0] else { throw MathParseError("\\not") }
            if let neg = Self.NEGATED[sym] { return [.symbol(neg, cls == .ord ? .rel : cls, st)] }
            return [.symbol(sym + "\u{0338}", cls == .ord ? .rel : cls, st)]
        case "bmod":
            return [.symbol("mod", .bin, .roman)]
        case "pmod", "pod":
            let arg = try parseArg()
            var inner: [MathNode] = []
            if name == "pmod" { inner = [.symbol("mod", .ord, .roman), .space(0.333)] }
            return [.space(name == "pmod" ? 1.0 : 0.6), .symbol("(", .open, .roman)] + inner + arg + [.symbol(")", .close, .roman)]
        case "mod":
            let arg = try parseArg()
            return [.space(1.0), .symbol("mod", .ord, .roman), .space(0.333)] + arg
        case "boxed", "fbox":
            if name == "fbox" { return [.boxed([.text(try readText(), .roman)])] }
            return [.boxed(try parseArg())]
        case "overset":
            let over = try parseArg()
            let base = try parseArg()
            return [.overUnder(base: base, over: over, under: nil, cls: Self.classOf(base))]
        case "underset":
            let under = try parseArg()
            let base = try parseArg()
            return [.overUnder(base: base, over: nil, under: under, cls: Self.classOf(base))]
        case "stackrel":
            let over = try parseArg()
            let base = try parseArg()
            return [.overUnder(base: base, over: over, under: nil, cls: .rel)]
        case "xrightarrow", "xleftarrow", "xlongrightarrow", "xlongleftarrow":
            let under = try parseOptionalArg()
            let over = try parseArg()
            return [.xArrow(left: name.contains("left"), over: over, under: under)]
        case "phantom":
            return [.phantom(try parseArg(), horizontal: true, vertical: true)]
        case "hphantom":
            return [.phantom(try parseArg(), horizontal: true, vertical: false)]
        case "vphantom":
            return [.phantom(try parseArg(), horizontal: false, vertical: true)]
        case "color":
            _ = try readRawGroup()
            return [.space(0)]
        case "textcolor", "colorbox":
            _ = try readRawGroup()
            return [.group(try parseArg())]
        case "tag", "tag*":
            let raw = try readText()
            return [.space(2), .text("(\(raw))", .roman)]
        case "label", "ref", "eqref":
            _ = try readRawGroup()
            return [.space(0)]
        case "nonumber", "notag", "hline", "displaylimits", "allowbreak", "nobreak", "relax", "strut":
            return [.space(0)]
        case "hspace", "hspace*", "kern", "mkern", "hskip", "mskip":
            if peek() == "*" { i += 1 }
            let raw = peek() == "{" ? try readRawGroup() : readDimension()
            return [.space(Self.dimension(raw, mu: name.hasPrefix("m")))]
        case "substack":
            skipSpaces()
            guard peek() == "{" else { throw MathParseError("\\substack") }
            i += 1
            let rows = try parseArrayBody(endName: nil)
            guard peek() == "}" else { throw MathParseError("missing }") }
            i += 1
            return [.array(rows: rows, kind: .substack, columns: [])]
        case "begin":
            return [try parseEnvironment()]
        default:
            throw MathParseError("unknown \\\(name)")
        }
    }

    private static func classOf(_ nodes: [MathNode]) -> MathAtomClass {
        if nodes.count == 1, case let .symbol(_, cls, _) = nodes[0] { return cls }
        return .ord
    }

    private mutating func readDimension() -> String {
        skipSpaces()
        var out = ""
        while let c = peek(), c.isASCII, c.isNumber || c == "." || c == "-" { out.append(c); i += 1 }
        skipSpaces()
        var letters = 0
        while letters < 2, let c = peek(), c.isASCII, c.isLetter { out.append(c); i += 1; letters += 1 }
        return out
    }

    static func dimension(_ raw: String, mu: Bool) -> Double {
        let t = raw.trimmingCharacters(in: .whitespaces)
        let num = Double(t.prefix { $0.isNumber || $0 == "." || $0 == "-" }) ?? 0
        let unit = t.drop { $0.isNumber || $0 == "." || $0 == "-" }.trimmingCharacters(in: .whitespaces)
        switch unit {
        case "em": return num
        case "ex": return num * 0.45
        case "pt", "bp": return num / 10
        case "mm": return num * 0.2845
        case "cm": return num * 2.845
        case "in": return num * 7.227
        case "mu": return num / 18
        default: return mu ? num / 18 : 0
        }
    }

    private mutating func withFont(_ f: MathFontStyle?, _ body: (inout MathParser) throws -> [MathNode]) rethrows -> [MathNode] {
        let saved = (font, blackboard, calligraphic, fraktur)
        font = f; blackboard = false; calligraphic = false; fraktur = false
        defer { (font, blackboard, calligraphic, fraktur) = saved }
        return try body(&self)
    }

    private mutating func withAlphabet(bb: Bool = false, cal: Bool = false, frak: Bool = false, _ body: (inout MathParser) throws -> [MathNode]) rethrows -> [MathNode] {
        let saved = (font, blackboard, calligraphic, fraktur)
        font = .roman; blackboard = bb; calligraphic = cal; fraktur = frak
        defer { (font, blackboard, calligraphic, fraktur) = saved }
        return try body(&self)
    }

    /// `\text{…}` 안의 글. 이스케이프만 풀고, 안쪽의 `$…$` 수식은 지원하지 않는다.
    private mutating func readText() throws -> String {
        let raw = try readRawGroup()
        if raw.contains("$") { throw MathParseError("math inside \\text") }
        var out = ""
        let a = Array(raw)
        var k = 0
        while k < a.count {
            let c = a[k]
            if c == "\\" && k + 1 < a.count {
                let n = a[k + 1]
                if n.isLetter && n.isASCII {
                    var j = k + 1
                    var name = ""
                    while j < a.count, a[j].isLetter, a[j].isASCII { name.append(a[j]); j += 1 }
                    switch name {
                    case "quad": out += "\u{2003}"
                    case "qquad": out += "\u{2003}\u{2003}"
                    case "textbf", "textit", "textrm", "emph", "text", "mathrm": break
                    case "ldots", "dots": out += "…"
                    default:
                        if let g = Self.GREEK[name] { out += g } else if let (sym, _) = Self.SYMBOLS[name] { out += sym } else { throw MathParseError("\\\(name) in text") }
                    }
                    k = j
                    continue
                }
                out.append(n == " " ? " " : n == "," ? "\u{2009}" : n)
                k += 2
                continue
            }
            if c == "{" || c == "}" { k += 1; continue }
            if c == "~" { out.append("\u{00A0}"); k += 1; continue }
            out.append(c == "\n" ? " " : c)
            k += 1
        }
        return out
    }

    /// 괄호 하나(`\left`/`\big` 뒤). `.`이면 빈 글.
    private mutating func readDelimiter() throws -> String {
        skipSpaces()
        guard let c = peek() else { throw MathParseError("missing delimiter") }
        if c == "\\" {
            let name = readCommand()
            if let d = Self.DELIMITERS[name] { return d }
            throw MathParseError("bad delimiter \\\(name)")
        }
        i += 1
        switch c {
        case ".": return ""
        case "(", ")", "[", "]", "|", "/", "<", ">": return c == "<" ? "⟨" : c == ">" ? "⟩" : String(c)
        case "⟨", "⟩", "‖", "⌊", "⌋", "⌈", "⌉", "{", "}": return String(c)
        default: throw MathParseError("bad delimiter \(c)")
        }
    }

    // MARK: 환경

    private mutating func parseEnvironment() throws -> MathNode {
        let raw = try readRawGroup()
        let name = raw.trimmingCharacters(in: .whitespaces)
        var columns: [MathColumnAlign] = []
        let kind: MathArrayKind
        switch name {
        case "matrix", "matrix*": kind = .matrix
        case "pmatrix", "pmatrix*": kind = .pmatrix
        case "bmatrix", "bmatrix*": kind = .bmatrix
        case "Bmatrix", "Bmatrix*": kind = .Bmatrix
        case "vmatrix", "vmatrix*": kind = .vmatrix
        case "Vmatrix", "Vmatrix*": kind = .Vmatrix
        case "smallmatrix": kind = .smallmatrix
        case "cases", "dcases": kind = .cases
        case "aligned", "align", "align*", "alignat", "alignat*", "alignedat", "split", "eqnarray", "eqnarray*", "flalign", "flalign*":
            kind = .aligned
            if name.hasPrefix("alignat") || name == "alignedat" { _ = try readRawGroup() }
        case "gathered", "gather", "gather*", "equation", "equation*", "multline", "multline*": kind = .gathered
        case "array", "darray":
            kind = .array
            let spec = try readRawGroup()
            for ch in spec {
                switch ch {
                case "l": columns.append(.left)
                case "r": columns.append(.right)
                case "c": columns.append(.center)
                default: break
                }
            }
        default:
            throw MathParseError("unknown environment \(name)")
        }
        let rows = try parseArrayBody(endName: name)
        return .array(rows: rows, kind: kind, columns: columns)
    }

    /// 표 본문: 칸(`&`)과 줄(`\\`). [endName]이 있으면 `\end{endName}`까지 읽는다. 없으면 `}`나 끝에서 멈춘다.
    private mutating func parseArrayBody(endName: String?) throws -> [[[MathNode]]] {
        var rows: [[[MathNode]]] = []
        var row: [[MathNode]] = []
        while true {
            let cell = try parseList()
            row.append(cell)
            skipSpaces()
            if peek() == "&" {
                i += 1
                continue
            }
            if let cmd = peekCommand(), cmd == "\\" || cmd == "cr" {
                _ = readCommand()
                if cmd == "\\" && peek() == "*" { i += 1 }
                // \\[2pt] 같은 줄 간격은 건너뛴다.
                skipSpaces()
                if peek() == "[" {
                    while let c = peek(), c != "]" { i += 1 }
                    if peek() == "]" { i += 1 }
                }
                rows.append(row)
                row = []
                continue
            }
            if peekCommand() == "end" {
                guard let endName else { throw MathParseError("unexpected \\end") }
                _ = readCommand()
                let got = try readRawGroup().trimmingCharacters(in: .whitespaces)
                if got != endName { throw MathParseError("\\end{\(got)} for \(endName)") }
                break
            }
            if endName != nil { throw MathParseError("missing \\end{\(endName!)}") }
            break
        }
        // 마지막 빈 줄(끝의 \\)은 버린다.
        if !(row.count == 1 && row[0].isEmpty && !rows.isEmpty) { rows.append(row) }
        return rows
    }

    // MARK: 표

    static let SWITCH_FONTS: [String: MathFontStyle] = ["bf": .bold, "rm": .roman, "it": .italic, "sf": .sans, "tt": .mono, "cal": .roman]
    static let STYLE_CHANGES: [String: MathStyleForce] = ["displaystyle": .display, "textstyle": .text, "scriptstyle": .script, "scriptscriptstyle": .scriptScript]

    static let GREEK: [String: String] = [
        "alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ", "epsilon": "ϵ", "varepsilon": "ε",
        "zeta": "ζ", "eta": "η", "theta": "θ", "vartheta": "ϑ", "iota": "ι", "kappa": "κ", "varkappa": "ϰ",
        "lambda": "λ", "mu": "μ", "nu": "ν", "xi": "ξ", "omicron": "ο", "pi": "π", "varpi": "ϖ", "rho": "ρ",
        "varrho": "ϱ", "sigma": "σ", "varsigma": "ς", "tau": "τ", "upsilon": "υ", "phi": "ϕ",
        "varphi": "φ", "chi": "χ", "psi": "ψ", "omega": "ω", "digamma": "ϝ",
        "Gamma": "Γ", "Delta": "Δ", "Theta": "Θ", "Lambda": "Λ", "Xi": "Ξ", "Pi": "Π",
        "Sigma": "Σ", "Upsilon": "Υ", "Phi": "Φ", "Psi": "Ψ", "Omega": "Ω",
    ]

    static let SYMBOLS: [String: (String, MathAtomClass)] = {
        var m: [String: (String, MathAtomClass)] = [:]
        let ord: [String: String] = [
            "infty": "∞", "partial": "∂", "nabla": "∇", "hbar": "ℏ", "hslash": "ℏ", "ell": "ℓ", "forall": "∀",
            "exists": "∃", "nexists": "∄", "emptyset": "∅", "varnothing": "∅", "angle": "∠", "measuredangle": "∡",
            "triangle": "△", "degree": "°", "prime": "′", "ldots": "…", "cdots": "⋯", "vdots": "⋮", "ddots": "⋱",
            "dots": "…", "dotsc": "…", "dotsb": "⋯", "dotsm": "⋯", "dotsi": "⋯", "dotso": "…", "mathellipsis": "…",
            "aleph": "ℵ", "beth": "ℶ", "Re": "ℜ", "Im": "ℑ", "wp": "℘", "top": "⊤", "bot": "⊥", "neg": "¬", "lnot": "¬",
            "therefore": "∴", "because": "∵", "imath": "ı", "jmath": "ȷ", "surd": "√", "flat": "♭", "sharp": "♯",
            "natural": "♮", "checkmark": "✓", "square": "□", "Box": "□", "blacksquare": "■", "backslash": "\\",
            "clubsuit": "♣", "diamondsuit": "♢", "heartsuit": "♡", "spadesuit": "♠", "mho": "℧", "complement": "∁",
            "vert": "|", "Vert": "‖", "textbackslash": "\\", "copyright": "©", "S": "§", "P": "¶", "dag": "†", "ddag": "‡",
            "Angstrom": "Å", "AA": "Å", "circledR": "®", "eth": "ð", "varDelta": "Δ", "infin": "∞",
        ]
        for (k, v) in ord { m[k] = (v, .ord) }
        let bin: [String: String] = [
            "pm": "±", "mp": "∓", "times": "×", "div": "÷", "cdot": "·", "ast": "∗", "star": "⋆", "circ": "∘",
            "bullet": "•", "oplus": "⊕", "ominus": "⊖", "otimes": "⊗", "odot": "⊙", "oslash": "⊘", "cup": "∪",
            "cap": "∩", "sqcup": "⊔", "sqcap": "⊓", "wedge": "∧", "land": "∧", "vee": "∨", "lor": "∨",
            "setminus": "∖", "smallsetminus": "∖", "uplus": "⊎", "amalg": "⨿", "diamond": "⋄", "bigtriangleup": "△",
            "bigtriangledown": "▽", "triangleleft": "◁", "triangleright": "▷", "wr": "≀", "dagger": "†", "ddagger": "‡",
            "ltimes": "⋉", "rtimes": "⋊", "bigcirc": "◯", "cdotp": "·", "centerdot": "·", "dotplus": "∔",
        ]
        for (k, v) in bin { m[k] = (v, .bin) }
        let rel: [String: String] = [
            "le": "≤", "leq": "≤", "ge": "≥", "geq": "≥", "leqslant": "⩽", "geqslant": "⩾", "ne": "≠", "neq": "≠",
            "approx": "≈", "approxeq": "≊", "sim": "∼", "simeq": "≃", "cong": "≅", "equiv": "≡", "propto": "∝",
            "ll": "≪", "gg": "≫", "lll": "⋘", "ggg": "⋙", "prec": "≺", "succ": "≻", "preceq": "⪯", "succeq": "⪰",
            "subset": "⊂", "supset": "⊃", "subseteq": "⊆", "supseteq": "⊇", "subsetneq": "⊊", "supsetneq": "⊋",
            "sqsubseteq": "⊑", "sqsupseteq": "⊒", "in": "∈", "ni": "∋", "owns": "∋", "notin": "∉", "perp": "⊥",
            "parallel": "∥", "nparallel": "∦", "mid": "∣", "nmid": "∤", "vdash": "⊢", "dashv": "⊣", "models": "⊨",
            "asymp": "≍", "doteq": "≐", "bowtie": "⋈", "smile": "⌣", "frown": "⌢", "to": "→", "rightarrow": "→",
            "leftarrow": "←", "gets": "←", "leftrightarrow": "↔", "Rightarrow": "⇒", "Leftarrow": "⇐",
            "Leftrightarrow": "⇔", "implies": "⟹", "impliedby": "⟸", "iff": "⟺", "longrightarrow": "⟶",
            "longleftarrow": "⟵", "longleftrightarrow": "⟷", "Longrightarrow": "⟹", "Longleftarrow": "⟸",
            "Longleftrightarrow": "⟺", "mapsto": "↦", "longmapsto": "⟼", "uparrow": "↑", "downarrow": "↓",
            "updownarrow": "↕", "Uparrow": "⇑", "Downarrow": "⇓", "Updownarrow": "⇕", "nearrow": "↗", "searrow": "↘",
            "swarrow": "↙", "nwarrow": "↖", "hookrightarrow": "↪", "hookleftarrow": "↩", "rightharpoonup": "⇀",
            "rightharpoondown": "⇁", "leftharpoonup": "↼", "leftharpoondown": "↽", "rightleftharpoons": "⇌",
            "leftrightharpoons": "⇋", "leftrightarrows": "⇆", "rightleftarrows": "⇄", "rightrightarrows": "⇉",
            "nleq": "≰", "ngeq": "≱", "nless": "≮", "ngtr": "≯", "lesssim": "≲", "gtrsim": "≳", "triangleq": "≜",
            "coloneqq": "≔", "coloneq": "≔", "eqqcolon": "≕", "leadsto": "⇝", "nsim": "≁", "ncong": "≇", "nequiv": "≢",
            "lneq": "⪇", "gneq": "⪈", "sqsubset": "⊏", "sqsupset": "⊐", "nsubseteq": "⊈", "nsupseteq": "⊉",
            "vDash": "⊨", "Vdash": "⊩", "propor": "∷", "eqsim": "≂", "backsim": "∽", "circeq": "≗", "risingdotseq": "≓",
            "fallingdotseq": "≒", "lessgtr": "≶", "gtrless": "≷", "shortparallel": "∥", "Colon": "∷",
            "rightsquigarrow": "⇝", "twoheadrightarrow": "↠", "dashrightarrow": "⇢", "dashleftarrow": "⇠",
            "longleftrightarrows": "⇆", "multimap": "⊸", "pitchfork": "⋔", "between": "≬", "doteqdot": "≑",
        ]
        for (k, v) in rel { m[k] = (v, .rel) }
        m["colon"] = (":", .punct)
        m["ldotp"] = (".", .punct)
        m["lbrace"] = ("{", .open); m["rbrace"] = ("}", .close)
        m["langle"] = ("⟨", .open); m["rangle"] = ("⟩", .close)
        m["lfloor"] = ("⌊", .open); m["rfloor"] = ("⌋", .close)
        m["lceil"] = ("⌈", .open); m["rceil"] = ("⌉", .close)
        m["lvert"] = ("|", .open); m["rvert"] = ("|", .close)
        m["lVert"] = ("‖", .open); m["rVert"] = ("‖", .close)
        m["lbrack"] = ("[", .open); m["rbrack"] = ("]", .close)
        return m
    }()

    /// 큰 연산자: (기호, 디스플레이에서 기본으로 위아래에 첨자를 둘지).
    static let LARGE_OPS: [String: (String, Bool)] = [
        "sum": ("∑", true), "prod": ("∏", true), "coprod": ("∐", true), "int": ("∫", false), "iint": ("∬", false),
        "iiint": ("∭", false), "oint": ("∮", false), "oiint": ("∯", false), "bigcup": ("⋃", true), "bigcap": ("⋂", true),
        "bigvee": ("⋁", true), "bigwedge": ("⋀", true), "bigoplus": ("⨁", true), "bigotimes": ("⨂", true),
        "bigodot": ("⨀", true), "biguplus": ("⨄", true), "bigsqcup": ("⨆", true), "intop": ("∫", true), "smallint": ("∫", false),
    ]

    /// 글자 연산자: (글, 디스플레이에서 위아래 첨자).
    static let TEXT_OPS: [String: (String, Bool)] = [
        "lim": ("lim", true), "liminf": ("lim inf", true), "limsup": ("lim sup", true), "max": ("max", true),
        "min": ("min", true), "sup": ("sup", true), "inf": ("inf", true), "det": ("det", true), "Pr": ("Pr", true),
        "gcd": ("gcd", true), "argmax": ("arg max", true), "argmin": ("arg min", true), "plim": ("plim", true),
        "injlim": ("inj lim", true), "projlim": ("proj lim", true),
        "sin": ("sin", false), "cos": ("cos", false), "tan": ("tan", false), "cot": ("cot", false), "sec": ("sec", false),
        "csc": ("csc", false), "arcsin": ("arcsin", false), "arccos": ("arccos", false), "arctan": ("arctan", false),
        "sinh": ("sinh", false), "cosh": ("cosh", false), "tanh": ("tanh", false), "coth": ("coth", false),
        "log": ("log", false), "ln": ("ln", false), "lg": ("lg", false), "exp": ("exp", false), "arg": ("arg", false),
        "deg": ("deg", false), "dim": ("dim", false), "hom": ("hom", false), "ker": ("ker", false),
        "sgn": ("sgn", false), "tr": ("tr", false), "Tr": ("Tr", false), "rank": ("rank", false), "diag": ("diag", false),
        "Var": ("Var", false), "Cov": ("Cov", false), "erf": ("erf", false), "sech": ("sech", false), "csch": ("csch", false),
        "arcsec": ("arcsec", false), "arccot": ("arccot", false), "arccsc": ("arccsc", false),
    ]

    static let SPACES: [String: Double] = [
        "quad": 1, "qquad": 2, "enspace": 0.5, "enskip": 0.5, "thinspace": 3.0 / 18, "medspace": 4.0 / 18,
        "thickspace": 5.0 / 18, "negthinspace": -3.0 / 18, "negmedspace": -4.0 / 18, "negthickspace": -5.0 / 18,
        "space": 0.333, "nobreakspace": 0.333, "hfill": 1, "hfil": 1, "qqquad": 3,
    ]

    static let ACCENT_ALIASES: [String: MathAccentKind] = ["overrightharpoon": .overrightarrow, "wideparen": .overline, "mathring": .dot]

    static let DELIMITERS: [String: String] = [
        "{": "{", "}": "}", "lbrace": "{", "rbrace": "}", "langle": "⟨", "rangle": "⟩", "lvert": "|", "rvert": "|",
        "vert": "|", "|": "‖", "Vert": "‖", "lVert": "‖", "rVert": "‖", "lfloor": "⌊", "rfloor": "⌋", "lceil": "⌈",
        "rceil": "⌉", "backslash": "\\", "lbrack": "[", "rbrack": "]", "uparrow": "↑", "downarrow": "↓", "updownarrow": "↕",
        "Uparrow": "⇑", "Downarrow": "⇓", "lgroup": "(", "rgroup": ")", "lmoustache": "(", "rmoustache": ")",
    ]

    static let NEGATED: [String: String] = [
        "=": "≠", "∈": "∉", "<": "≮", ">": "≯", "≡": "≢", "⊂": "⊄", "⊃": "⊅", "∼": "≁", "≤": "≰", "≥": "≱",
        "⊆": "⊈", "⊇": "⊉", "≅": "≇", "≈": "≉", "∋": "∌", "∣": "∤", "∥": "∦", "≃": "≄", "→": "↛", "←": "↚",
        "⇒": "⇏", "⇔": "⇎", "↔": "↮", "∃": "∄",
    ]

    /// 수식 안에 바로 쓴 ASCII 글자의 종류(빼기는 수학 빼기표로).
    static let CHAR_CLASS: [Character: (String, MathAtomClass)] = [
        "+": ("+", .bin), "-": ("−", .bin), "*": ("∗", .bin), "/": ("/", .ord), "=": ("=", .rel), "<": ("<", .rel),
        ">": (">", .rel), ":": (":", .rel), ",": (",", .punct), ";": (";", .punct), "(": ("(", .open), "[": ("[", .open),
        ")": (")", .close), "]": ("]", .close), "!": ("!", .close), "?": ("?", .close), "|": ("|", .ord), ".": (".", .ord),
        "'": ("′", .ord), "@": ("@", .ord), "\"": ("\"", .ord),
    ]

    /// 유니코드로 바로 쓴 연산자·관계 기호.
    static let UNICODE_CLASS: [Character: (String, MathAtomClass)] = {
        var m: [Character: (String, MathAtomClass)] = [:]
        for (_, v) in SYMBOLS where v.0.count == 1 { if let c = v.0.first, m[c] == nil { m[c] = v } }
        for (_, v) in LARGE_OPS { if let c = v.0.first { m[c] = (v.0, .op) } }
        m["−"] = ("−", .bin)
        m["⋅"] = ("·", .bin)
        m["·"] = ("·", .bin)
        // 그리스 문자 글자는 ord로 따로 처리한다.
        m["|"] = ("|", .ord)
        return m
    }()

    // MARK: 글자 모양 바꾸기(`\mathbb` 등)

    static let BLACKBOARD: [Character: String] = ["C": "ℂ", "H": "ℍ", "N": "ℕ", "P": "ℙ", "Q": "ℚ", "R": "ℝ", "Z": "ℤ"]
    static let SCRIPT: [Character: String] = ["B": "ℬ", "E": "ℰ", "F": "ℱ", "H": "ℋ", "I": "ℐ", "L": "ℒ", "M": "ℳ", "R": "ℛ", "e": "ℯ", "g": "ℊ", "o": "ℴ"]
    static let FRAKTUR: [Character: String] = ["C": "ℭ", "H": "ℌ", "I": "ℑ", "R": "ℜ", "Z": "ℨ"]

    /// 수학 영숫자 블록으로 바꾼다(대문자 위주, 빠진 글자는 별도 표).
    static func mapAlphabet(_ c: Character, _ exceptions: [Character: String]) -> String? {
        if let e = exceptions[c] { return e }
        guard let u = c.unicodeScalars.first, c.isASCII else { return nil }
        let upperBase: UInt32
        let lowerBase: UInt32
        if exceptions == BLACKBOARD { upperBase = 0x1D538; lowerBase = 0x1D552 }
        else if exceptions == SCRIPT { upperBase = 0x1D49C; lowerBase = 0x1D4B6 }
        else { upperBase = 0x1D504; lowerBase = 0x1D51E }
        if (65...90).contains(u.value), let s = Unicode.Scalar(upperBase + u.value - 65) { return String(Character(s)) }
        if (97...122).contains(u.value), let s = Unicode.Scalar(lowerBase + u.value - 97) { return String(Character(s)) }
        if exceptions == BLACKBOARD, (48...57).contains(u.value), let s = Unicode.Scalar(0x1D7D8 + u.value - 48) { return String(Character(s)) }
        return nil
    }
}
