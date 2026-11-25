import Foundation

/// 채팅 수식 처리 중 UI에 의존하지 않는 부분(Kotlin `MathText`): 블록 수식 기준으로 글 나누기, 바꾼 구간의 위치 옮기기,
/// 그림으로 못 그린 수식을 읽을 만한 유니코드 글로 바꾸기(`toUnicode`). 위치는 UTF-16 기준.
public enum MathText {

    /// 말풍선 안의 한 조각. 글(인라인 수식 포함) 또는 블록 수식.
    public enum Piece: Sendable, Hashable {
        case text(MarkdownLite.Result)
        /// `raw`는 구분자까지 포함한 원문(복사·접근성용).
        case block(latex: String, raw: String)
    }

    /// `MarkdownLite.Result`를 블록 수식(`.mathBlock`) 앞뒤로 나눈다. 조각 앞뒤의 빈 줄은 떼어 낸다.
    public static func split(_ r: MarkdownLite.Result) -> [Piece] {
        let blocks = r.spans.filter { $0.kind == .mathBlock && $0.latex != nil }.sorted { $0.start < $1.start }
        if blocks.isEmpty { return [.text(r)] }
        let text = T16.units(r.text)
        var out: [Piece] = []
        var pos = 0
        for b in blocks {
            if b.start < pos || b.end > text.count { continue }
            addText(r, text, pos, b.start, &out)
            out.append(.block(latex: b.latex!, raw: T16.string(text[b.start..<b.end])))
            pos = b.end
        }
        addText(r, text, pos, text.count, &out)
        return out
    }

    private static func addText(_ r: MarkdownLite.Result, _ text: [UInt16], _ from: Int, _ to: Int, _ out: inout [Piece]) {
        var a = from
        var b = to
        let nl: UInt16 = 10
        while a < b && text[a] == nl { a += 1 }
        while b > a && text[b - 1] == nl { b -= 1 }
        if a >= b { return }
        let spans = r.spans
            .filter { $0.kind != .mathBlock && $0.end > a && $0.start < b }
            .map { s -> MarkdownLite.Span in
                var c = s
                c.start = max(s.start, a) - a
                c.end = min(s.end, b) - a
                return c
            }
            .filter { $0.end > $0.start }
        out.append(.text(MarkdownLite.Result(T16.string(text[a..<b]), spans)))
    }

    /// 원래 글의 [origStart, origEnd) 구간을 새 글의 [newStart, newEnd)로 바꾼 기록.
    public struct Replacement: Sendable, Hashable {
        public var origStart: Int, origEnd: Int, newStart: Int, newEnd: Int
        public init(_ origStart: Int, _ origEnd: Int, _ newStart: Int, _ newEnd: Int) {
            self.origStart = origStart
            self.origEnd = origEnd
            self.newStart = newStart
            self.newEnd = newEnd
        }
    }

    /// 바꾼 구간들(겹치지 않고 위치 순)에 맞춰 원래 위치 `o`를 새 위치로 옮긴다.
    /// 바꾼 구간 한가운데를 가리키면 시작(`isEnd`=false)은 그 구간 앞, 끝은 그 구간 뒤로 보낸다.
    public static func mapOffset(_ reps: [Replacement], _ o: Int, isEnd: Bool) -> Int {
        var delta = 0
        for r in reps {
            if r.origEnd <= o {
                delta += (r.newEnd - r.newStart) - (r.origEnd - r.origStart)
            } else if r.origStart < o {
                return isEnd ? r.newEnd : r.newStart
            } else {
                break
            }
        }
        return o + delta
    }

    // MARK: - LaTeX → 유니코드 (그림으로 못 그렸을 때만 쓰는 대략적인 변환)

    static let SYMBOLS: [String: String] = [
        "alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ", "epsilon": "ε", "varepsilon": "ε",
        "zeta": "ζ", "eta": "η", "theta": "θ", "vartheta": "ϑ", "iota": "ι", "kappa": "κ",
        "lambda": "λ", "mu": "μ", "nu": "ν", "xi": "ξ", "pi": "π", "varpi": "ϖ", "rho": "ρ",
        "varrho": "ϱ", "sigma": "σ", "varsigma": "ς", "tau": "τ", "upsilon": "υ", "phi": "φ",
        "varphi": "φ", "chi": "χ", "psi": "ψ", "omega": "ω",
        "Gamma": "Γ", "Delta": "Δ", "Theta": "Θ", "Lambda": "Λ", "Xi": "Ξ", "Pi": "Π",
        "Sigma": "Σ", "Upsilon": "Υ", "Phi": "Φ", "Psi": "Ψ", "Omega": "Ω",
        "hbar": "ℏ", "ell": "ℓ", "infty": "∞", "partial": "∂", "nabla": "∇", "degree": "°",
        "propto": "∝", "simeq": "≃", "approx": "≈", "sim": "∼", "cong": "≅", "equiv": "≡",
        "le": "≤", "leq": "≤", "ge": "≥", "geq": "≥", "ne": "≠", "neq": "≠", "ll": "≪", "gg": "≫",
        "times": "×", "cdot": "·", "div": "÷", "pm": "±", "mp": "∓", "ast": "∗", "circ": "∘",
        "to": "→", "rightarrow": "→", "leftarrow": "←", "Rightarrow": "⇒", "Leftarrow": "⇐",
        "leftrightarrow": "↔", "Leftrightarrow": "⇔", "mapsto": "↦", "implies": "⇒", "iff": "⇔",
        "sum": "∑", "prod": "∏", "int": "∫", "iint": "∬", "oint": "∮",
        "in": "∈", "notin": "∉", "subset": "⊂", "subseteq": "⊆", "cup": "∪", "cap": "∩",
        "forall": "∀", "exists": "∃", "emptyset": "∅", "perp": "⊥", "parallel": "∥", "angle": "∠",
        "langle": "⟨", "rangle": "⟩", "ldots": "…", "cdots": "⋯", "dots": "…", "vdots": "⋮",
        "quad": "  ", "qquad": "    ", "dagger": "†", "prime": "′", "sqrt": "√",
        "sin": "sin", "cos": "cos", "tan": "tan", "log": "log", "ln": "ln", "exp": "exp",
        "lim": "lim", "max": "max", "min": "min", "det": "det",
    ]

    /// 인자를 그대로 펼치는 명령(글꼴만 바꾸는 것들).
    static let PASS_THROUGH: Set<String> = [
        "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal", "mathbb", "boldsymbol",
        "operatorname", "textrm", "textbf", "textit", "mbox", "displaystyle", "textstyle",
    ]

    /// 지워도 되는 명령.
    static let DROP: Set<String> = ["left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "limits", "nolimits"]

    static let ACCENTS: [String: UInt16] = ["vec": 0x20D7, "hat": 0x0302, "bar": 0x0304, "dot": 0x0307, "ddot": 0x0308, "tilde": 0x0303]

    static let SUP_FROM = T16.lit("0123456789+-=()niabcdefghjklmoprstuvwxyzABDEGHIJKLMNOPRTUVW")
    static let SUP_TO = T16.lit("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱᵃᵇᶜᵈᵉᶠᵍʰʲᵏˡᵐᵒᵖʳˢᵗᵘᵛʷˣʸᶻᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ")
    static let SUB_FROM = T16.lit("0123456789+-=()aehijklmnoprstuvxβγρφχ")
    static let SUB_TO = T16.lit("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓᵦᵧᵨᵩᵪ")

    /// 안의 공백을 그대로 두는(글 모드) 명령. 수식 모드의 공백은 LaTeX처럼 버린다.
    static let TEXT_MODE: Set<String> = ["text", "textrm", "textbf", "textit", "mbox"]

    private static let MULTI_SPACE = try! NSRegularExpression(pattern: "[ \\t]{2,}")

    /// LaTeX 수식을 대략적인 유니코드 글로 바꾼다. 예: `\omega^2` → `ω²`, `\frac{T}{\Theta_D}` → `T/Θ_D`.
    public static func toUnicode(_ latex: String) -> String {
        var c = Converter(T16.units(latex))
        let converted = T16.string(c.run())
        let ns = converted as NSString
        let collapsed = MULTI_SPACE.stringByReplacingMatches(in: converted, range: NSRange(location: 0, length: ns.length), withTemplate: "  ")
        return kotlinTrim(collapsed)
    }

    private struct Converter {
        let s: [UInt16]
        var i = 0
        var textDepth = 0

        init(_ s: [UInt16]) { self.s = s }

        mutating func run() -> [UInt16] {
            var out: [UInt16] = []
            while i < s.count { out.append(contentsOf: atom()) }
            return out
        }

        /// 다음 한 단위(명령, 묶음, 글자)를 바꿔서 준다.
        mutating func atom() -> [UInt16] {
            let c = s[i]
            switch c {
            case T16.ch("\\"): return command()
            case T16.ch("{"): return group()
            case T16.ch("}"): i += 1; return []
            case T16.ch("^"): i += 1; let a = arg(); return script(a, sup: true)
            case T16.ch("_"): i += 1; let a = arg(); return script(a, sup: false)
            case T16.ch("&"), T16.ch("~"): i += 1; return [T16.ch(" ")]
            case T16.ch(" "), T16.ch("\t"), T16.ch("\n"): i += 1; return textDepth > 0 ? [T16.ch(" ")] : []
            default: i += 1; return [c]
            }
        }

        /// `{...}` 묶음 전체를 바꿔서 준다. `i`는 '{'를 가리킨다.
        mutating func group() -> [UInt16] {
            i += 1
            var sb: [UInt16] = []
            while i < s.count && s[i] != T16.ch("}") { sb.append(contentsOf: atom()) }
            if i < s.count { i += 1 }
            return sb
        }

        /// 명령 인자 하나: 묶음이면 묶음, 아니면 다음 한 단위. 공백은 건너뛴다.
        mutating func arg() -> [UInt16] {
            while i < s.count && s[i] == T16.ch(" ") { i += 1 }
            if i >= s.count { return [] }
            return s[i] == T16.ch("{") ? group() : atom()
        }

        mutating func command() -> [UInt16] {
            i += 1
            if i >= s.count { return [] }
            let c = s[i]
            if !T16.isLetter(c) {
                i += 1
                switch c {
                case T16.ch(","), T16.ch(";"), T16.ch(":"), T16.ch(" "): return [T16.ch(" ")]
                case T16.ch("!"): return []
                case T16.ch("\\"): return T16.lit("  ")
                default: return [c]
                }
            }
            let start = i
            while i < s.count && T16.isLetter(s[i]) { i += 1 }
            let name = T16.string(s[start..<i])
            if name == "frac" || name == "dfrac" || name == "tfrac" {
                let num = arg()
                let den = arg()
                return wrap(num) + [T16.ch("/")] + wrap(den)
            }
            if name == "sqrt" {
                if i < s.count && s[i] == T16.ch("[") {
                    let close = T16.indexOf(s, T16.ch("]"), from: i)
                    if close > 0 { i = close + 1 }
                }
                let a = arg()
                return T16.lit("√") + wrap(a)
            }
            if let accent = MathText.ACCENTS[name] {
                let a = arg()
                return a.count == 1 ? a + [accent] : a
            }
            if MathText.TEXT_MODE.contains(name) {
                textDepth += 1
                defer { textDepth -= 1 }
                return arg()
            }
            if MathText.PASS_THROUGH.contains(name) { return arg() }
            if MathText.DROP.contains(name) { return [] }
            if name == "begin" || name == "end" { _ = arg(); return [] }
            return MathText.SYMBOLS[name].map(T16.units) ?? T16.units(name)
        }

        /// 연산자·공백이 섞인 묶음만 괄호로 싼다(`ℏω_D`는 그대로, `x+1`은 `(x+1)`).
        func wrap(_ x: [UInt16]) -> [UInt16] {
            let ops = Set(T16.lit(" +-−=/·×,<>±∓"))
            if !x.contains(where: { ops.contains($0) }) { return x }
            return [T16.ch("(")] + x + [T16.ch(")")]
        }

        func script(_ x: [UInt16], sup: Bool) -> [UInt16] {
            if x.isEmpty { return [] }
            let from = sup ? MathText.SUP_FROM : MathText.SUB_FROM
            let to = sup ? MathText.SUP_TO : MathText.SUB_TO
            let idx = x.map { from.firstIndex(of: $0) }
            if idx.allSatisfy({ $0 != nil && $0! < to.count }) { return idx.map { to[$0!] } }
            let mark = sup ? T16.ch("^") : T16.ch("_")
            return x.count == 1 ? [mark] + x : [mark, T16.ch("(")] + x + [T16.ch(")")]
        }
    }
}
