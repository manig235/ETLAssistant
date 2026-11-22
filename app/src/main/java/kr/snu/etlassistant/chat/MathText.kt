package kr.snu.etlassistant.chat

import kr.snu.etlassistant.chat.MarkdownLite.Kind

/**
 * 채팅 수식 처리 중 안드로이드에 의존하지 않는 부분: 블록 수식 기준으로 글 나누기, 바꾼 구간의 위치 옮기기,
 * 그림으로 못 그린 수식을 읽을 만한 유니코드 글로 바꾸기([toUnicode]).
 */
object MathText {

    /** 말풍선 안의 한 조각. 글(인라인 수식 포함) 또는 블록 수식. */
    sealed class Piece {
        data class Text(val result: MarkdownLite.Result) : Piece()
        /** [raw]는 구분자까지 포함한 원문(복사·접근성용). */
        data class Block(val latex: String, val raw: String) : Piece()
    }

    /** [MarkdownLite.Result]를 블록 수식([Kind.MATH_BLOCK]) 앞뒤로 나눈다. 조각 앞뒤의 빈 줄은 떼어 낸다. */
    fun split(r: MarkdownLite.Result): List<Piece> {
        val blocks = r.spans.filter { it.kind == Kind.MATH_BLOCK && it.latex != null }.sortedBy { it.start }
        if (blocks.isEmpty()) return listOf(Piece.Text(r))
        val out = mutableListOf<Piece>()
        var pos = 0
        for (b in blocks) {
            if (b.start < pos || b.end > r.text.length) continue
            addText(r, pos, b.start, out)
            out += Piece.Block(b.latex!!, r.text.substring(b.start, b.end))
            pos = b.end
        }
        addText(r, pos, r.text.length, out)
        return out
    }

    private fun addText(r: MarkdownLite.Result, from: Int, to: Int, out: MutableList<Piece>) {
        var a = from
        var b = to
        while (a < b && r.text[a] == '\n') a++
        while (b > a && r.text[b - 1] == '\n') b--
        if (a >= b) return
        val spans = r.spans
            .filter { it.kind != Kind.MATH_BLOCK && it.end > a && it.start < b }
            .map { it.copy(start = maxOf(it.start, a) - a, end = minOf(it.end, b) - a) }
            .filter { it.end > it.start }
        out += Piece.Text(MarkdownLite.Result(r.text.substring(a, b), spans))
    }

    /** 원래 글의 [origStart, origEnd) 구간을 새 글의 [newStart, newEnd)로 바꾼 기록. */
    data class Replacement(val origStart: Int, val origEnd: Int, val newStart: Int, val newEnd: Int)

    /**
     * 바꾼 구간들([reps], 겹치지 않고 위치 순)에 맞춰 원래 위치 [o]를 새 위치로 옮긴다.
     * 바꾼 구간 한가운데를 가리키면 시작([isEnd]=false)은 그 구간 앞, 끝은 그 구간 뒤로 보낸다.
     */
    fun mapOffset(reps: List<Replacement>, o: Int, isEnd: Boolean): Int {
        var delta = 0
        for (r in reps) {
            if (r.origEnd <= o) {
                delta += (r.newEnd - r.newStart) - (r.origEnd - r.origStart)
            } else if (r.origStart < o) {
                return if (isEnd) r.newEnd else r.newStart
            } else {
                break
            }
        }
        return o + delta
    }

    // ---- LaTeX → 유니코드 (그림으로 못 그렸을 때만 쓰는 대략적인 변환) ----

    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
        "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "hbar" to "ℏ", "ell" to "ℓ", "infty" to "∞", "partial" to "∂", "nabla" to "∇", "degree" to "°",
        "propto" to "∝", "simeq" to "≃", "approx" to "≈", "sim" to "∼", "cong" to "≅", "equiv" to "≡",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠", "ll" to "≪", "gg" to "≫",
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗", "circ" to "∘",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦", "implies" to "⇒", "iff" to "⇔",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "cup" to "∪", "cap" to "∩",
        "forall" to "∀", "exists" to "∃", "emptyset" to "∅", "perp" to "⊥", "parallel" to "∥", "angle" to "∠",
        "langle" to "⟨", "rangle" to "⟩", "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮",
        "quad" to "  ", "qquad" to "    ", "dagger" to "†", "prime" to "′", "sqrt" to "√",
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "exp" to "exp",
        "lim" to "lim", "max" to "max", "min" to "min", "det" to "det",
    )

    /** 인자를 그대로 펼치는 명령(글꼴만 바꾸는 것들). */
    private val PASS_THROUGH = setOf(
        "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal", "mathbb", "boldsymbol",
        "operatorname", "textrm", "textbf", "textit", "mbox", "displaystyle", "textstyle",
    )

    /** 지워도 되는 명령. */
    private val DROP = setOf("left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "limits", "nolimits")

    private val ACCENTS = mapOf("vec" to '⃗', "hat" to '̂', "bar" to '̄', "dot" to '̇', "ddot" to '̈', "tilde" to '̃')

    private const val SUP_FROM = "0123456789+-=()niabcdefghjklmoprstuvwxyzABDEGHIJKLMNOPRTUVW"
    private const val SUP_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱᵃᵇᶜᵈᵉᶠᵍʰʲᵏˡᵐᵒᵖʳˢᵗᵘᵛʷˣʸᶻᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ"
    private const val SUB_FROM = "0123456789+-=()aehijklmnoprstuvxβγρφχ"
    private const val SUB_TO = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓᵦᵧᵨᵩᵪ"

    /** LaTeX 수식을 대략적인 유니코드 글로 바꾼다. 예: `\omega^2` → `ω²`, `\frac{T}{\Theta_D}` → `T/Θ_D`. */
    fun toUnicode(latex: String): String =
        runCatching { Converter(latex).run() }.getOrDefault(latex)
            .replace(Regex("[ \\t]{2,}"), "  ").trim()

    /** 안의 공백을 그대로 두는(글 모드) 명령. 수식 모드의 공백은 LaTeX처럼 버린다. */
    private val TEXT_MODE = setOf("text", "textrm", "textbf", "textit", "mbox")

    private class Converter(private val s: String) {
        private var i = 0
        private var textDepth = 0

        fun run(): String = buildString { while (i < s.length) append(atom()) }

        /** 다음 한 단위(명령, 묶음, 글자)를 바꿔서 준다. */
        private fun atom(): String {
            val c = s[i]
            return when (c) {
                '\\' -> command()
                '{' -> group()
                '}' -> { i++; "" }
                '^' -> { i++; script(arg(), sup = true) }
                '_' -> { i++; script(arg(), sup = false) }
                '&' -> { i++; " " }
                '~' -> { i++; " " }
                ' ', '\t', '\n' -> { i++; if (textDepth > 0) " " else "" }
                else -> { i++; c.toString() }
            }
        }

        /** `{...}` 묶음 전체를 바꿔서 준다. [i]는 '{'를 가리킨다. */
        private fun group(): String {
            i++ // '{'
            val sb = StringBuilder()
            while (i < s.length && s[i] != '}') sb.append(atom())
            if (i < s.length) i++ // '}'
            return sb.toString()
        }

        /** 명령 인자 하나: 묶음이면 묶음, 아니면 다음 한 단위. 공백은 건너뛴다. */
        private fun arg(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            return if (s[i] == '{') group() else atom()
        }

        private fun command(): String {
            i++ // '\'
            if (i >= s.length) return ""
            val c = s[i]
            if (!c.isLetter()) {
                i++
                return when (c) {
                    ',', ';', ':', ' ' -> " "
                    '!' -> ""
                    '\\' -> "  "
                    else -> c.toString() // \{ \} \% \$ \# \_
                }
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when {
                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    val num = arg()
                    val den = arg()
                    "${wrap(num)}/${wrap(den)}"
                }
                name == "sqrt" -> {
                    // \sqrt[n]{x}의 [n]은 건너뛴다
                    if (i < s.length && s[i] == '[') { val close = s.indexOf(']', i); if (close > 0) i = close + 1 }
                    "√" + wrap(arg())
                }
                name in ACCENTS -> {
                    val a = arg()
                    if (a.length == 1) a + ACCENTS.getValue(name) else a
                }
                name in TEXT_MODE -> { textDepth++; try { arg() } finally { textDepth-- } }
                name in PASS_THROUGH -> arg()
                name in DROP -> ""
                name == "begin" || name == "end" -> { arg(); "" }
                else -> SYMBOLS[name] ?: name
            }
        }

        /** 연산자·공백이 섞인 묶음만 괄호로 싼다(`ℏω_D`는 그대로, `x+1`은 `(x+1)`). */
        private fun wrap(x: String): String = if (x.none { it in " +-−=/·×,<>±∓" }) x else "($x)"

        private fun script(x: String, sup: Boolean): String {
            if (x.isEmpty()) return ""
            val (from, to) = if (sup) SUP_FROM to SUP_TO else SUB_FROM to SUB_TO
            if (x.all { from.indexOf(it) >= 0 }) return x.map { to[from.indexOf(it)] }.joinToString("")
            val mark = if (sup) "^" else "_"
            return if (x.length == 1) mark + x else "$mark($x)"
        }
    }
}
