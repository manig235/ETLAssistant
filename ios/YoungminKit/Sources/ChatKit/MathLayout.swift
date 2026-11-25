import CoreGraphics
import CoreText
import Foundation

/// 그릴 것 하나. 좌표는 상자 기준선의 왼쪽 끝이 원점, **y는 위쪽이 +**(TeX처럼).
public enum MathDrawItem: Sendable, Hashable {
    /// 글자(기준선 왼쪽 끝 = (x, y)).
    case glyphs(String, MathFontFace, size: Double, x: Double, y: Double)
    /// 채운 사각형(왼쪽 아래 = (x, y)).
    case rule(x: Double, y: Double, width: Double, height: Double)
    /// 선으로 그린 모양(괄호·근호 등).
    case stroke([MathPathOp], lineWidth: Double)
    /// 채운 모양.
    case fill([MathPathOp])
}

public enum MathPathOp: Sendable, Hashable {
    case move(Double, Double)
    case line(Double, Double)
    case quad(Double, Double, Double, Double)
    case curve(Double, Double, Double, Double, Double, Double)
    case close

    func offset(_ dx: Double, _ dy: Double) -> MathPathOp {
        switch self {
        case let .move(x, y): return .move(x + dx, y + dy)
        case let .line(x, y): return .line(x + dx, y + dy)
        case let .quad(a, b, x, y): return .quad(a + dx, b + dy, x + dx, y + dy)
        case let .curve(a, b, c, d, x, y): return .curve(a + dx, b + dy, c + dx, d + dy, x + dx, y + dy)
        case .close: return .close
        }
    }
}

public enum MathFontFace: String, Sendable, Hashable {
    case italic, roman, bold, boldItalic, sans, mono

    init(_ s: MathFontStyle) {
        switch s {
        case .italic: self = .italic
        case .roman: self = .roman
        case .bold: self = .bold
        case .boldItalic: self = .boldItalic
        case .sans: self = .sans
        case .mono: self = .mono
        }
    }
}

/// 배치한 수식 상자. 크기는 포인트.
public struct MathBox: Sendable, Hashable {
    public var width: Double
    public var ascent: Double
    public var descent: Double
    public var items: [MathDrawItem]
    /// 기울인 글자가 오른쪽으로 삐져나온 만큼(위 첨자를 띄울 때 쓴다).
    var italicCorrection: Double = 0

    public init(width: Double = 0, ascent: Double = 0, descent: Double = 0, items: [MathDrawItem] = []) {
        self.width = width
        self.ascent = ascent
        self.descent = descent
        self.items = items
    }

    public var height: Double { ascent + descent }

    /// [other]를 (dx, dy)에 놓은 그림을 더한다(크기는 바꾸지 않는다).
    mutating func place(_ other: MathBox, _ dx: Double, _ dy: Double) {
        for it in other.items { items.append(Self.offset(it, dx, dy)) }
    }

    static func offset(_ it: MathDrawItem, _ dx: Double, _ dy: Double) -> MathDrawItem {
        switch it {
        case let .glyphs(s, f, size, x, y): return .glyphs(s, f, size: size, x: x + dx, y: y + dy)
        case let .rule(x, y, w, h): return .rule(x: x + dx, y: y + dy, width: w, height: h)
        case let .stroke(ops, lw): return .stroke(ops.map { $0.offset(dx, dy) }, lineWidth: lw)
        case let .fill(ops): return .fill(ops.map { $0.offset(dx, dy) })
        }
    }

    /// 모든 그림을 [scale]배.
    public func scaled(_ scale: Double) -> MathBox {
        func s(_ op: MathPathOp) -> MathPathOp {
            switch op {
            case let .move(x, y): return .move(x * scale, y * scale)
            case let .line(x, y): return .line(x * scale, y * scale)
            case let .quad(a, b, x, y): return .quad(a * scale, b * scale, x * scale, y * scale)
            case let .curve(a, b, c, d, x, y): return .curve(a * scale, b * scale, c * scale, d * scale, x * scale, y * scale)
            case .close: return .close
            }
        }
        let items: [MathDrawItem] = self.items.map { it in
            switch it {
            case let .glyphs(str, f, size, x, y): return .glyphs(str, f, size: size * scale, x: x * scale, y: y * scale)
            case let .rule(x, y, w, h): return .rule(x: x * scale, y: y * scale, width: w * scale, height: h * scale)
            case let .stroke(ops, lw): return .stroke(ops.map(s), lineWidth: lw * scale)
            case let .fill(ops): return .fill(ops.map(s))
            }
        }
        return MathBox(width: width * scale, ascent: ascent * scale, descent: descent * scale, items: items)
    }
}

/// TeX 부분 집합 배치기(새로 만듦; 안드로이드 JLatexMath 대신). 글꼴은 Times New Roman 계열(기기 기본 포함),
/// 없는 글자는 CoreText가 대체 글꼴을 고른다.
public enum MathLayout {

    /// 수식을 해석해 배치한다. 해석하지 못했거나 너무 크면 nil(화면은 유니코드 글로 대신).
    /// - Parameters:
    ///   - fontSize: 수식 글자 크기(포인트).
    ///   - display: 블록 수식(`\[ \]`)이면 true.
    public static func layout(_ latex: String, fontSize: Double, display: Bool) -> MathBox? {
        guard let nodes = try? MathParser.parse(latex) else { return nil }
        if nodes.isEmpty { return nil }
        var engine = Engine(base: fontSize)
        let box = engine.list(nodes, Style(display ? .display : .text))
        if !(box.width > 0) || box.width > 20_000 || box.height > 5_000 || !box.width.isFinite { return nil }
        return box
    }

    /// 해석만 되는지(테스트·진단용).
    public static func canParse(_ latex: String) -> Bool { (try? MathParser.parse(latex)) != nil }

    // MARK: 스타일

    struct Style: Hashable {
        var level: MathStyleForce
        var cramped = false
        init(_ level: MathStyleForce, cramped: Bool = false) { self.level = level; self.cramped = cramped }

        var scale: Double {
            switch level {
            case .display, .text: return 1
            case .script: return 0.7
            case .scriptScript: return 0.5
            }
        }
        var isScript: Bool { level == .script || level == .scriptScript }
        var isDisplay: Bool { level == .display }
        var sup: Style { Style(level == .display || level == .text ? .script : .scriptScript, cramped: cramped) }
        var sub: Style { Style(level == .display || level == .text ? .script : .scriptScript, cramped: true) }
        var num: Style { Style(level == .display ? .text : level == .text ? .script : .scriptScript, cramped: cramped) }
        var den: Style { Style(level == .display ? .text : level == .text ? .script : .scriptScript, cramped: true) }
        var crampedSelf: Style { Style(level, cramped: true) }
    }

    // MARK: 글꼴

    static let FACES: [MathFontFace: [String]] = [
        .roman: ["TimesNewRomanPSMT", "Times-Roman"],
        .italic: ["TimesNewRomanPS-ItalicMT", "Times-Italic"],
        .bold: ["TimesNewRomanPS-BoldMT", "Times-Bold"],
        .boldItalic: ["TimesNewRomanPS-BoldItalicMT", "Times-BoldItalic"],
        .sans: ["HelveticaNeue", "Helvetica"],
        .mono: ["Menlo-Regular", "Courier"],
    ]

    private static let fontCache = FontCache()

    final class FontCache: @unchecked Sendable {
        private var fonts: [String: CTFont] = [:]
        private let lock = NSLock()
        func font(_ face: MathFontFace, _ size: Double) -> CTFont {
            let key = "\(face.rawValue)|\(size)"
            lock.lock()
            defer { lock.unlock() }
            if let f = fonts[key] { return f }
            var made: CTFont?
            for name in FACES[face] ?? [] {
                let f = CTFontCreateWithName(name as CFString, CGFloat(size), nil)
                if (CTFontCopyPostScriptName(f) as String) == name { made = f; break }
            }
            let f = made ?? CTFontCreateWithName((FACES[face]?.first ?? "Times-Roman") as CFString, CGFloat(size), nil)
            if fonts.count > 200 { fonts.removeAll() }
            fonts[key] = f
            return f
        }
    }

    public static func font(_ face: MathFontFace, _ size: Double) -> CTFont { fontCache.font(face, size) }

    /// 글자 줄(대체 글꼴 포함). 그리기도 이것을 쓴다.
    public static func line(_ text: String, _ face: MathFontFace, _ size: Double, color: CGColor? = nil) -> CTLine {
        var attrs: [NSAttributedString.Key: Any] = [NSAttributedString.Key(kCTFontAttributeName as String): font(face, size)]
        if let color { attrs[NSAttributedString.Key(kCTForegroundColorAttributeName as String)] = color }
        else { attrs[NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String)] = true }
        return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs) as CFAttributedString)
    }

    // MARK: 배치

    struct Engine {
        let base: Double

        init(base: Double) { self.base = base }

        func em(_ st: Style) -> Double { base * st.scale }
        func axis(_ st: Style) -> Double { 0.25 * em(st) }
        func rule(_ st: Style) -> Double { max(0.045 * em(st), 0.5) }
        let xHeight = 0.45

        // MARK: 글자

        func glyphs(_ text: String, _ face: MathFontFace, _ size: Double) -> MathBox {
            if text.isEmpty { return MathBox() }
            let ln = MathLayout.line(text, face, size)
            let w = Double(CTLineGetTypographicBounds(ln, nil, nil, nil))
            let ink = CTLineGetBoundsWithOptions(ln, .useGlyphPathBounds)
            var b = MathBox(width: w, ascent: max(0, Double(ink.maxY)), descent: max(0, -Double(ink.minY)),
                            items: [.glyphs(text, face, size: size, x: 0, y: 0)])
            if face == .italic || face == .boldItalic { b.italicCorrection = max(0, Double(ink.maxX) - w) }
            return b
        }

        // MARK: 목록

        struct Atom {
            var cls: MathAtomClass?
            var box: MathBox
            /// 간격 계산에서 빠지는 것(공백).
            var transparent = false
        }

        mutating func list(_ nodes: [MathNode], _ style0: Style) -> MathBox {
            var style = style0
            var atoms: [Atom] = []
            for n in nodes {
                if case let .styleChange(f) = n { style = Style(f, cramped: style.cramped); continue }
                atoms.append(atom(n, style))
            }
            // 이항 연산자가 맨 앞이거나 연산자·관계·여는 괄호·구두점 뒤, 또는 관계·닫는 괄호·구두점 앞이면 보통 글자로 본다.
            var prev: Int?
            for k in atoms.indices where !atoms[k].transparent {
                if atoms[k].cls == .bin {
                    let p = prev.flatMap { atoms[$0].cls }
                    if p == nil || p == .bin || p == .op || p == .rel || p == .open || p == .punct { atoms[k].cls = .ord }
                }
                if let p = prev, atoms[p].cls == .bin, let c = atoms[k].cls, c == .rel || c == .close || c == .punct { atoms[p].cls = .ord }
                prev = k
            }
            if let p = prev, atoms[p].cls == .bin { atoms[p].cls = .ord }

            var out = MathBox()
            var x = 0.0
            var last: MathAtomClass?
            let mu = em(style) / 18
            for a in atoms {
                if !a.transparent, let c = a.cls {
                    if let l = last { x += Double(MathLayout.space(l, c, script: style.isScript)) * mu }
                    last = c
                }
                out.place(a.box, x, 0)
                out.ascent = max(out.ascent, a.box.ascent)
                out.descent = max(out.descent, a.box.descent)
                x += a.box.width
            }
            out.width = max(0, x)
            if atoms.count == 1 { out.italicCorrection = atoms[0].box.italicCorrection }
            return out
        }

        func atomClass(_ n: MathNode) -> MathAtomClass? {
            switch n {
            case let .symbol(_, c, _): return c
            case .text, .group, .sqrt, .accent, .boxed, .phantom, .array: return .ord
            case .frac: return .inner
            case let .scripts(base, _, _): return atomClass(base)
            case .largeOp: return .op
            case .delimited: return .inner
            case let .sizedDelim(_, _, c): return c
            case .space, .styleChange: return nil
            case let .overUnder(_, _, _, c): return c
            case .xArrow: return .rel
            }
        }

        mutating func atom(_ n: MathNode, _ st: Style) -> Atom {
            if case let .space(e) = n { return Atom(cls: nil, box: MathBox(width: e * em(st)), transparent: true) }
            return Atom(cls: atomClass(n), box: node(n, st))
        }

        mutating func node(_ n: MathNode, _ st: Style) -> MathBox {
            let size = em(st)
            switch n {
            case let .symbol(s, _, f):
                return glyphs(s, MathFontFace(f), size)
            case let .text(s, f):
                return glyphs(s, MathFontFace(f), size)
            case let .group(items):
                return list(items, st)
            case let .frac(num, den, rule, forced, l, r):
                var fs = st
                if let forced { fs = Style(forced, cramped: st.cramped) }
                var f = frac(num, den, rule: rule, fs)
                if !l.isEmpty || !r.isEmpty { f = delimited(l, f, r, fs) }
                return f
            case let .sqrt(body, index):
                return sqrt(body, index, st)
            case let .scripts(base, sup, sub):
                return scripts(base, sup, sub, st)
            case let .largeOp(s, _, isText, _):
                return largeOpGlyph(s, isText: isText, st)
            case let .delimited(l, body, r):
                return delimited(l, list(body, st), r, st)
            case let .sizedDelim(d, sz, _):
                let h = [1.2, 1.8, 2.4, 3.0][max(0, min(3, sz - 1))] * size
                return delimiter(d, height: h, st)
            case let .accent(body, kind):
                return accent(body, kind, st)
            case let .space(e):
                return MathBox(width: e * size)
            case .styleChange:
                return MathBox()
            case let .array(rows, kind, cols):
                return array(rows, kind, cols, st)
            case let .boxed(body):
                let inner = list(body, st)
                let pad = 0.25 * size
                let lw = rule(st)
                var b = MathBox(width: inner.width + 2 * pad, ascent: inner.ascent + pad, descent: inner.descent + pad)
                b.place(inner, pad, 0)
                b.items.append(.stroke([.move(lw / 2, -b.descent + lw / 2), .line(b.width - lw / 2, -b.descent + lw / 2),
                                        .line(b.width - lw / 2, b.ascent - lw / 2), .line(lw / 2, b.ascent - lw / 2), .close], lineWidth: lw))
                return b
            case let .overUnder(base, over, under, _):
                return limits(list(base, st), over.map { list($0, st.sup) }, under.map { list($0, st.sub) }, st)
            case let .xArrow(left, over, under):
                let o = list(over, st.sup)
                let u = under.map { list($0, st.sub) }
                let w = max(o.width, u?.width ?? 0) + 0.8 * size
                let arrow = arrowBox(max(w, 1.2 * size), left: left, right: !left, st)
                return limits(arrow, o.width > 0 ? o : nil, u, st, tight: true)
            case let .phantom(body, h, v):
                let b = list(body, st)
                return MathBox(width: h ? b.width : 0, ascent: v ? b.ascent : 0, descent: v ? b.descent : 0)
            }
        }

        // MARK: 분수

        mutating func frac(_ numN: [MathNode], _ denN: [MathNode], rule hasRule: Bool, _ st: Style) -> MathBox {
            let size = em(st)
            let num = list(numN, st.num)
            let den = list(denN, st.den)
            let t = rule(st)
            let a = axis(st)
            var u = (st.isDisplay ? 0.677 : 0.394) * size
            var v = (st.isDisplay ? 0.686 : 0.345) * size
            if hasRule {
                let phi = st.isDisplay ? 3 * t : t
                let c1 = (u - num.descent) - (a + t / 2)
                if c1 < phi { u += phi - c1 }
                let c2 = (a - t / 2) - (den.ascent - v)
                if c2 < phi { v += phi - c2 }
            } else {
                let phi = st.isDisplay ? 7 * t : 3 * t
                let psi = (u - num.descent) - (den.ascent - v)
                if psi < phi { u += (phi - psi) / 2; v += (phi - psi) / 2 }
            }
            let pad = 0.12 * size
            let w = max(num.width, den.width) + 2 * pad
            var b = MathBox(width: w, ascent: u + num.ascent, descent: v + den.descent)
            b.place(num, (w - num.width) / 2, u)
            b.place(den, (w - den.width) / 2, -v)
            if hasRule { b.items.append(.rule(x: pad * 0.5, y: a - t / 2, width: w - pad, height: t)) }
            return b
        }

        // MARK: 근호

        mutating func sqrt(_ bodyN: [MathNode], _ indexN: [MathNode]?, _ st: Style) -> MathBox {
            let size = em(st)
            let body = list(bodyN, st.crampedSelf)
            let t = rule(st)
            let clear = (st.isDisplay ? 0.2 : 0.12) * size
            let inner = max(body.ascent, 0.6 * size)
            let bottom = -max(body.descent, 0.1 * size) - clear * 0.6
            let top = inner + clear
            let h = top - bottom
            let signW = min(0.85 * size, 0.55 * size + 0.04 * h)
            var b = MathBox()
            var dx = 0.0
            var index: MathBox?
            if let indexN, !indexN.isEmpty {
                let ib = list(indexN, Style(.scriptScript))
                index = ib
                dx = max(0, ib.width - 0.35 * signW)
            }
            let tickY = bottom + min(0.5 * size, h * 0.45)
            let p1x = dx, p1y = tickY - 0.03 * size
            let p2x = dx + 0.12 * signW, p2y = tickY + 0.04 * size
            let p3x = dx + 0.45 * signW, p3y = bottom
            let p4x = dx + signW, p4y = top - t / 2
            b.items.append(.stroke([.move(p1x, p1y), .line(p2x, p2y)], lineWidth: t * 1.1))
            b.items.append(.stroke([.move(p2x, p2y), .line(p3x, p3y)], lineWidth: t * 2.1))
            b.items.append(.stroke([.move(p3x, p3y), .line(p4x, p4y)], lineWidth: t))
            let bodyX = dx + signW + 0.08 * size
            b.place(body, bodyX, 0)
            let endX = bodyX + body.width + 0.08 * size
            b.items.append(.rule(x: p4x - t * 0.3, y: top - t, width: endX - p4x + t * 0.3, height: t))
            b.width = endX + 0.05 * size
            b.ascent = top + t * 0.5
            b.descent = -bottom + t
            if let index {
                let iy = bottom + 0.6 * h - (-index.descent)
                b.place(index, max(0, dx + 0.35 * signW - index.width), max(iy, tickY + 0.1 * size))
                b.ascent = max(b.ascent, max(iy, tickY + 0.1 * size) + index.ascent)
            }
            return b
        }

        // MARK: 첨자

        mutating func scripts(_ baseN: MathNode, _ supN: [MathNode]?, _ subN: [MathNode]?, _ st: Style) -> MathBox {
            if case let .largeOp(_, limitsOpt, _, def) = baseN {
                let useLimits = limitsOpt ?? (def && st.isDisplay)
                if useLimits {
                    let op = node(baseN, st)
                    return limits(op, supN.map { list($0, st.sup) }, subN.map { list($0, st.sub) }, st)
                }
            }
            if case let .accent(_, kind) = baseN, kind == .overbrace || kind == .underbrace {
                let b = node(baseN, st)
                return limits(b, supN.map { list($0, st.sup) }, subN.map { list($0, st.sub) }, st)
            }
            let size = em(st)
            let base = node(baseN, st)
            let isChar: Bool = {
                if case .symbol = baseN { return true }
                if case let .group(g) = baseN, g.count == 1, case .symbol = g[0] { return true }
                return false
            }()
            let sup = supN.map { list($0, st.sup) }
            let sub = subN.map { list($0, st.sub) }
            let scriptEm = em(st.sup)
            var u = isChar ? 0 : base.ascent - 0.386 * scriptEm
            var v = isChar ? 0 : base.descent + 0.05 * scriptEm
            let xh = xHeight * size
            if let sup {
                let p = (st.isDisplay ? 0.413 : st.cramped ? 0.289 : 0.363) * size
                u = max(u, p, sup.descent + xh / 4)
            }
            if let sub, sup == nil {
                v = max(v, 0.15 * size, sub.ascent - 0.8 * xh)
            }
            if let sup, let sub {
                v = max(v, 0.247 * size)
                let gap = (u - sup.descent) - (sub.ascent - v)
                let minGap = 4 * rule(st)
                if gap < minGap {
                    v += minGap - gap
                    let psi = 0.8 * xh - (u - sup.descent)
                    if psi > 0 { u += psi; v -= psi }
                }
            }
            var b = base
            b.italicCorrection = 0
            let space = 0.05 * size
            var w = base.width
            if let sup {
                let x = base.width + base.italicCorrection
                b.place(sup, x, u)
                b.ascent = max(b.ascent, u + sup.ascent)
                b.descent = max(b.descent, sup.descent - u)
                w = max(w, x + sup.width)
            }
            if let sub {
                // 적분 기호처럼 기울어진 글자는 아래 첨자를 조금 왼쪽으로.
                var x = base.width
                if case let .largeOp(s, _, false, _) = baseN, s.contains("∫") || s.contains("∮") { x -= 0.18 * size * (st.isDisplay ? 1.4 : 1) }
                b.place(sub, x, -v)
                b.descent = max(b.descent, v + sub.descent)
                b.ascent = max(b.ascent, sub.ascent - v)
                w = max(w, x + sub.width)
            }
            b.width = w + space
            return b
        }

        /// 위아래에 붙이는 첨자(∑의 범위, lim의 아래, \overset).
        func limits(_ op: MathBox, _ sup: MathBox?, _ sub: MathBox?, _ st: Style, tight: Bool = false) -> MathBox {
            let size = em(st)
            let w = max(op.width, sup?.width ?? 0, sub?.width ?? 0)
            var b = MathBox(width: w, ascent: op.ascent, descent: op.descent)
            b.place(op, (w - op.width) / 2, 0)
            if let sup {
                let gap = tight ? 0.08 * size : max(0.111 * size, 0.2 * size - sup.descent)
                let y = op.ascent + gap + sup.descent
                b.place(sup, (w - sup.width) / 2, y)
                b.ascent = y + sup.ascent + (tight ? 0.02 : 0.1) * size
            }
            if let sub {
                let gap = tight ? 0.1 * size : max(0.167 * size, 0.6 * size - sub.ascent)
                let y = op.descent + gap + sub.ascent
                b.place(sub, (w - sub.width) / 2, -y)
                b.descent = y + sub.descent + (tight ? 0.02 : 0.1) * size
            }
            return b
        }

        func largeOpGlyph(_ s: String, isText: Bool, _ st: Style) -> MathBox {
            let size = em(st)
            if isText { return glyphs(s, .roman, size) }
            let integral = s.contains("∫") || s.contains("∬") || s.contains("∭") || s.contains("∮") || s.contains("∯")
            let factor = st.isDisplay ? (integral ? 1.75 : 1.45) : (integral ? 1.25 : 1.1)
            var g = glyphs(s, integral ? .italic : .roman, size * factor)
            if integral, g.width < 0.3 * size { g = glyphs(s, .roman, size * factor) }
            // 기호 가운데를 수식 축에 맞춘다.
            let shift = axis(st) - (g.ascent - g.descent) / 2
            var b = MathBox(width: g.width + (integral ? 0.08 * size : 0), ascent: g.ascent + shift, descent: g.descent - shift)
            b.place(g, 0, shift)
            return b
        }

        // MARK: 괄호

        mutating func delimited(_ l: String, _ body: MathBox, _ r: String, _ st: Style) -> MathBox {
            let size = em(st)
            let a = axis(st)
            let half = max(body.ascent - a, body.descent + a)
            let h = max(2 * half * 0.95, 2 * half - 0.3 * size)
            var b = MathBox()
            var x = 0.0
            if !l.isEmpty {
                let d = delimiter(l, height: h, st)
                b.place(d, x, 0)
                b.ascent = max(b.ascent, d.ascent); b.descent = max(b.descent, d.descent)
                x += d.width
            } else { x += 0.12 * size }
            b.place(body, x, 0)
            b.ascent = max(b.ascent, body.ascent); b.descent = max(b.descent, body.descent)
            x += body.width
            if !r.isEmpty {
                let d = delimiter(r, height: h, st)
                b.place(d, x, 0)
                b.ascent = max(b.ascent, d.ascent); b.descent = max(b.descent, d.descent)
                x += d.width
            } else { x += 0.12 * size }
            b.width = x
            return b
        }

        /// 높이 [height]인 괄호 하나(수식 축 가운데). 작으면 글자, 크면 선으로 그린다.
        func delimiter(_ d: String, height: Double, _ st: Style) -> MathBox {
            let size = em(st)
            if height <= 1.25 * size || ["/", "\\", "↑", "↓", "↕", "⇑", "⇓"].contains(d) {
                let g = glyphs(d, .roman, height <= 1.25 * size ? size : height * 0.85)
                // 큰 글자 화살표·빗금도 축에 맞춘다.
                if height > 1.25 * size {
                    let shift = axis(st) - (g.ascent - g.descent) / 2
                    var b = MathBox(width: g.width, ascent: g.ascent + shift, descent: g.descent - shift)
                    b.place(g, 0, shift)
                    return b
                }
                return g
            }
            let a = axis(st)
            let top = a + height / 2
            let bot = a - height / 2
            let t = max(rule(st) * 1.1, 0.05 * size)
            let thick = min(0.11 * size, t + 0.012 * height)
            var b = MathBox(ascent: top, descent: -bot)
            let pad = 0.06 * size
            switch d {
            case "(", ")":
                let w = min(0.6 * size, 0.22 * size + 0.07 * height)
                let open = d == "("
                let xo = open ? pad + w : pad      // 위·아래 끝점 x
                let xm = open ? pad : pad + w      // 가운데(가장 튀어나온 곳) x
                let s = open ? 1.0 : -1.0
                // 3차 곡선의 가운데가 xm이 되도록 조절점을 잡는다(끝은 가늘고 가운데가 두꺼운 초승달 모양).
                let x1 = (4 * xm - xo) / 3
                let x2 = (4 * (xm + s * thick) - xo) / 3
                let k = height * 0.3
                b.items.append(.fill([
                    .move(xo, top),
                    .curve(x1, top - k, x1, bot + k, xo, bot),
                    .curve(x2, bot + k, x2, top - k, xo, top),
                    .close,
                ]))
                b.width = w + 2 * pad
            case "[", "]", "⌊", "⌋", "⌈", "⌉":
                let w = 0.32 * size
                let left = d == "[" || d == "⌊" || d == "⌈"
                let vx = left ? pad + 0.05 * size : pad + w - 0.05 * size - t
                b.items.append(.rule(x: vx, y: bot, width: t, height: height))
                let hx = left ? vx : pad + 0.02 * size
                let hw = w - 0.07 * size
                if d == "[" || d == "]" || d == "⌈" || d == "⌉" { b.items.append(.rule(x: hx, y: top - t * 0.9, width: hw, height: t * 0.9)) }
                if d == "[" || d == "]" || d == "⌊" || d == "⌋" { b.items.append(.rule(x: hx, y: bot, width: hw, height: t * 0.9)) }
                b.width = w + 2 * pad
            case "{", "}":
                let w = 0.42 * size
                let open = d == "{"
                let xe = open ? pad + w : pad       // 위·아래 끝
                let xc = pad + w / 2                // 세로 줄
                let xp = open ? pad : pad + w       // 가운데 뾰족
                let mid = a
                let r = min(0.18 * size, height * 0.12)
                b.items.append(.stroke([
                    .move(xe, top),
                    .quad(xc, top, xc, top - r),
                    .line(xc, mid + r),
                    .quad(xc, mid, xp, mid),
                    .quad(xc, mid, xc, mid - r),
                    .line(xc, bot + r),
                    .quad(xc, bot, xe, bot),
                ], lineWidth: t * 1.25))
                b.width = w + 2 * pad
            case "|", "‖":
                let gap = 0.12 * size
                b.items.append(.rule(x: pad + 0.04 * size, y: bot, width: t, height: height))
                if d == "‖" { b.items.append(.rule(x: pad + 0.04 * size + t + gap, y: bot, width: t, height: height)) }
                b.width = 2 * pad + 0.08 * size + t + (d == "‖" ? t + gap : 0)
            case "⟨", "⟩":
                let w = min(0.5 * size, 0.2 * size + 0.12 * height)
                let open = d == "⟨"
                let xo = open ? pad + w : pad
                let xp = open ? pad : pad + w
                b.items.append(.stroke([.move(xo, top), .line(xp, a), .line(xo, bot)], lineWidth: t))
                b.width = w + 2 * pad
            default:
                return glyphs(d, .roman, size)
            }
            return b
        }

        // MARK: 악센트

        mutating func accent(_ bodyN: [MathNode], _ kind: MathAccentKind, _ st: Style) -> MathBox {
            let size = em(st)
            let body = list(bodyN, st.crampedSelf)
            let t = rule(st)
            var b = body
            b.italicCorrection = body.italicCorrection
            let single: Bool = bodyN.count == 1 && { if case .symbol = bodyN[0] { return true }; return false }()
            let italic: Bool = {
                if bodyN.count == 1, case let .symbol(_, _, f) = bodyN[0] { return f == .italic || f == .boldItalic }
                return false
            }()
            let cx = body.width / 2 + (italic ? 0.06 * size : 0)
            let top = max(body.ascent, xHeight * size) + 0.08 * size
            switch kind {
            case .hat, .widehat, .check:
                let half = kind == .widehat ? max(0.22 * size, body.width * 0.48) : 0.2 * size
                let hgt = kind == .widehat ? min(0.28 * size, 0.12 * size + 0.04 * body.width) : 0.16 * size
                let up = kind != .check
                b.items.append(.stroke([.move(cx - half, top + (up ? 0 : hgt)), .line(cx, top + (up ? hgt : 0)), .line(cx + half, top + (up ? 0 : hgt))], lineWidth: t))
                b.ascent = top + hgt + t
            case .tilde, .widetilde:
                let half = kind == .widetilde ? max(0.22 * size, body.width * 0.48) : 0.22 * size
                let hgt = 0.1 * size
                b.items.append(.stroke([.move(cx - half, top), .curve(cx - half * 0.4, top + hgt * 2.2, cx + half * 0.4, top - hgt * 1.2, cx + half, top + hgt)], lineWidth: t * 1.1))
                b.ascent = top + hgt * 1.6 + t
            case .acute, .grave:
                let dir = kind == .acute ? 1.0 : -1.0
                b.items.append(.stroke([.move(cx - dir * 0.08 * size, top), .line(cx + dir * 0.1 * size, top + 0.17 * size)], lineWidth: t * 1.2))
                b.ascent = top + 0.18 * size
            case .dot, .ddot:
                let r = 0.055 * size
                let xs = kind == .dot ? [cx] : [cx - 0.12 * size, cx + 0.12 * size]
                for x in xs { b.items.append(.fill(circle(x, top + r * 1.2, r))) }
                b.ascent = top + r * 2.6
            case .breve:
                b.items.append(.stroke([.move(cx - 0.17 * size, top + 0.15 * size), .quad(cx, top - 0.05 * size, cx + 0.17 * size, top + 0.15 * size)], lineWidth: t))
                b.ascent = top + 0.17 * size
            case .bar, .overline:
                let w = kind == .bar && single ? max(0.36 * size, body.width * 0.85) : body.width
                let x0 = kind == .bar && single ? cx - w / 2 : 0
                let y = kind == .overline ? body.ascent + 0.12 * size : top + 0.02 * size
                b.items.append(.rule(x: x0, y: y, width: w, height: t))
                b.ascent = y + t + 0.04 * size
            case .underline:
                let y = -body.descent - 0.12 * size - t
                b.items.append(.rule(x: 0, y: y, width: body.width, height: t))
                b.descent = -y + 0.04 * size
            case .vec, .overrightarrow, .overleftarrow, .overleftrightarrow:
                let w = kind == .vec ? max(0.42 * size, single ? body.width * 0.9 : body.width) : max(body.width, 0.6 * size)
                let x0 = kind == .vec ? cx - w / 2 : (body.width - w) / 2
                let y = (kind == .vec ? top : body.ascent + 0.12 * size) + 0.06 * size
                let head = 0.11 * size
                var ops: [MathPathOp] = [.move(x0, y), .line(x0 + w, y)]
                if kind != .overleftarrow { ops += [.move(x0 + w - head, y + head * 0.8), .line(x0 + w, y), .line(x0 + w - head, y - head * 0.8)] }
                if kind == .overleftarrow || kind == .overleftrightarrow { ops += [.move(x0 + head, y + head * 0.8), .line(x0, y), .line(x0 + head, y - head * 0.8)] }
                b.items.append(.stroke(ops, lineWidth: t))
                b.ascent = y + head + t
            case .overbrace, .underbrace:
                let over = kind == .overbrace
                let hgt = 0.2 * size
                let w = body.width
                let y0 = over ? body.ascent + 0.08 * size : -body.descent - 0.08 * size
                let s = over ? 1.0 : -1.0
                b.items.append(.stroke([
                    .move(0, y0), .quad(0, y0 + s * hgt * 0.5, 0.15 * size, y0 + s * hgt * 0.5),
                    .line(w / 2 - 0.12 * size, y0 + s * hgt * 0.5), .quad(w / 2, y0 + s * hgt * 0.5, w / 2, y0 + s * hgt),
                    .quad(w / 2, y0 + s * hgt * 0.5, w / 2 + 0.12 * size, y0 + s * hgt * 0.5),
                    .line(w - 0.15 * size, y0 + s * hgt * 0.5), .quad(w, y0 + s * hgt * 0.5, w, y0),
                ], lineWidth: t * 1.1))
                if over { b.ascent = y0 + hgt + t } else { b.descent = -(y0 - hgt) + t }
            }
            return b
        }

        func circle(_ x: Double, _ y: Double, _ r: Double) -> [MathPathOp] {
            let k = 0.5523 * r
            return [.move(x + r, y), .curve(x + r, y + k, x + k, y + r, x, y + r), .curve(x - k, y + r, x - r, y + k, x - r, y),
                    .curve(x - r, y - k, x - k, y - r, x, y - r), .curve(x + k, y - r, x + r, y - k, x + r, y), .close]
        }

        func arrowBox(_ w: Double, left: Bool, right: Bool, _ st: Style) -> MathBox {
            let size = em(st)
            let t = rule(st)
            let y = axis(st)
            let head = 0.14 * size
            var ops: [MathPathOp] = [.move(0.05 * size, y), .line(w - 0.05 * size, y)]
            if right { ops += [.move(w - 0.05 * size - head, y + head * 0.75), .line(w - 0.05 * size, y), .line(w - 0.05 * size - head, y - head * 0.75)] }
            if left { ops += [.move(0.05 * size + head, y + head * 0.75), .line(0.05 * size, y), .line(0.05 * size + head, y - head * 0.75)] }
            return MathBox(width: w, ascent: y + head, descent: 0, items: [.stroke(ops, lineWidth: t * 1.1)])
        }

        // MARK: 표

        mutating func array(_ rows: [[[MathNode]]], _ kind: MathArrayKind, _ cols: [MathColumnAlign], _ st: Style) -> MathBox {
            let cellStyle: Style
            switch kind {
            case .aligned, .gathered: cellStyle = Style(st.level == .display ? .display : st.level, cramped: st.cramped)
            case .smallmatrix, .substack: cellStyle = st.isScript ? Style(.scriptScript) : Style(.script)
            default: cellStyle = st.isDisplay ? Style(.text) : Style(st.level, cramped: st.cramped)
            }
            let size = em(st)
            let cs = em(cellStyle)
            let nCols = rows.map(\.count).max() ?? 0
            if nCols == 0 { return MathBox() }
            var boxes: [[MathBox]] = []
            for row in rows {
                var line: [MathBox] = []
                for (k, cell) in row.enumerated() {
                    var nodes = cell
                    // aligned의 오른쪽(짝수 번째) 칸이 관계 기호로 시작하면 앞에 빈 글자를 두어 간격을 살린다.
                    if kind == .aligned && k % 2 == 1, let first = nodes.first, let c = atomClass(first), c == .rel || c == .bin {
                        nodes.insert(.group([]), at: 0)
                    }
                    line.append(list(nodes, cellStyle))
                }
                while line.count < nCols { line.append(MathBox()) }
                boxes.append(line)
            }
            var widths = Array(repeating: 0.0, count: nCols)
            for line in boxes { for (k, b) in line.enumerated() { widths[k] = max(widths[k], b.width) } }
            func align(_ k: Int) -> MathColumnAlign {
                switch kind {
                case .aligned: return k % 2 == 0 ? .right : .left
                case .cases: return .left
                case .array: return k < cols.count ? cols[k] : .center
                default: return .center
                }
            }
            func gap(after k: Int) -> Double {
                switch kind {
                case .aligned: return k % 2 == 0 ? 0 : 1.5 * size
                case .cases: return 1.0 * size
                case .smallmatrix: return 0.35 * size
                default: return 0.9 * size
                }
            }
            let strutA = (kind == .smallmatrix || kind == .substack ? 0.55 : 0.75) * cs
            let strutD = (kind == .smallmatrix || kind == .substack ? 0.2 : 0.3) * cs
            let rowGap = (kind == .aligned || kind == .gathered ? 0.3 : kind == .substack || kind == .smallmatrix ? 0.05 : 0.18) * cs
            var heights: [(Double, Double)] = boxes.map { line in
                (max(strutA, line.map(\.ascent).max() ?? 0), max(strutD, line.map(\.descent).max() ?? 0))
            }
            if heights.isEmpty { heights = [(strutA, strutD)] }
            let total = heights.reduce(0) { $0 + $1.0 + $1.1 } + rowGap * Double(max(0, heights.count - 1))
            var b = MathBox()
            var y = total / 2 + axis(st)
            var totalW = 0.0
            for k in 0..<nCols { totalW += widths[k] + (k < nCols - 1 ? gap(after: k) : 0) }
            for (r, line) in boxes.enumerated() {
                y -= heights[r].0
                var x = 0.0
                for (k, cell) in line.enumerated() {
                    let extra = widths[k] - cell.width
                    let dx: Double
                    switch align(k) {
                    case .left: dx = 0
                    case .right: dx = extra
                    case .center: dx = extra / 2
                    }
                    b.place(cell, x + dx, y)
                    x += widths[k] + (k < nCols - 1 ? gap(after: k) : 0)
                }
                y -= heights[r].1 + rowGap
            }
            b.width = totalW
            b.ascent = total / 2 + axis(st)
            b.descent = total / 2 - axis(st)
            let wrap: (String, String)?
            switch kind {
            case .pmatrix: wrap = ("(", ")")
            case .bmatrix: wrap = ("[", "]")
            case .Bmatrix: wrap = ("{", "}")
            case .vmatrix: wrap = ("|", "|")
            case .Vmatrix: wrap = ("‖", "‖")
            case .cases: wrap = ("{", "")
            default: wrap = nil
            }
            if let (l, r) = wrap {
                var padded = MathBox(width: b.width + 0.2 * size, ascent: b.ascent, descent: b.descent)
                padded.place(b, 0.1 * size, 0)
                return delimited(l, padded, r, st)
            }
            return b
        }
    }

    // MARK: 간격 표(TeX 규칙, mu 단위)

    /// 0 없음, 3 thin, 4 medium, 5 thick. 음수는 첨자 크기에서는 빼는 간격.
    static let SPACING: [[Int]] = [
        //        ord op  bin  rel open close punct inner
        /* ord */ [0, 3, -4, -5, 0, 0, 0, -3],
        /* op  */ [3, 3, 0, -5, 0, 0, 0, -3],
        /* bin */ [-4, -4, 0, 0, -4, 0, 0, -4],
        /* rel */ [-5, -5, 0, 0, -5, 0, 0, -5],
        /* open*/ [0, 0, 0, 0, 0, 0, 0, 0],
        /* clos*/ [0, 3, -4, -5, 0, 0, 0, -3],
        /* punc*/ [-3, -3, 0, -3, -3, -3, -3, -3],
        /* innr*/ [-3, 3, -4, -5, -3, 0, -3, -3],
    ]

    static func space(_ l: MathAtomClass, _ r: MathAtomClass, script: Bool) -> Int {
        let v = SPACING[l.rawValue][r.rawValue]
        if v < 0 { return script ? 0 : -v }
        return v
    }
}
