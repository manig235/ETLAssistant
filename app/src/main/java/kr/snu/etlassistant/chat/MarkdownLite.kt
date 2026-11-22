package kr.snu.etlassistant.chat

/**
 * 모델 응답에 자주 나오는 마크다운만 처리하는 작은 변환기. 안드로이드 API에 의존하지 않는다.
 *
 * 지원: `**굵게**`, `` `코드` ``, 코드 블록(```), `#` 제목, `-`/`*` 목록, `[글](https://…)` 링크, 표(셀을 ` · `로 이어 붙임).
 * 링크는 http/https만 허용한다(모델 출력에 다른 스킴이 섞여도 열리지 않게).
 *
 * 수식(LaTeX): 인라인 `\( \)`·`$ $`, 블록 `\[ \]`·`$$ $$`(혼자 있는 줄 또는 여러 줄). 출력 글에는 원문을 그대로 두고
 * [Kind.MATH]/[Kind.MATH_BLOCK] 스팬으로 표시만 한다(복사하면 원래 LaTeX가 나오게). 그리기는 화면 쪽이 맡는다.
 * 닫히지 않은 수식(스트리밍 중)은 표시하지 않고 원문 그대로 둔다. 코드(`` ` ``, ```)안은 해석하지 않는다.
 */
object MarkdownLite {

    enum class Kind { BOLD, CODE, CODE_BLOCK, HEADER, LINK, MATH, MATH_BLOCK }

    data class Span(
        val start: Int,
        val end: Int,
        val kind: Kind,
        val url: String? = null,
        /** 수식 스팬의 LaTeX 본문(구분자 제외). */
        val latex: String? = null,
        /** 디스플레이 스타일(`\[ \]`, `$$ $$`)인지. */
        val display: Boolean = false,
    )

    data class Result(val text: String, val spans: List<Span>)

    private val HEADER = Regex("^\\s{0,3}#{1,6}\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun render(src: String): Result {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var inFence = false
        var fenceStart = 0

        fun closeFence() {
            if (out.isNotEmpty() && out.last() == '\n') out.setLength(out.length - 1)
            if (out.length > fenceStart) spans += Span(fenceStart, out.length, Kind.CODE_BLOCK)
            out.append('\n')
        }

        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            idx++
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (!inFence) {
                    inFence = true
                    fenceStart = out.length
                } else {
                    inFence = false
                    closeFence()
                }
                continue
            }
            if (inFence) {
                out.append(line).append('\n')
                continue
            }
            // 블록 수식: `\[`나 `$$`로 시작하는 줄부터 닫는 구분자가 있는 줄까지. 닫히지 않았으면 일반 글로 둔다.
            val block = blockMath(lines, idx - 1)
            if (block != null) {
                val start = out.length
                out.append(lines.subList(idx - 1, block.endLine + 1).joinToString("\n") { it.trim() })
                spans += Span(start, out.length, Kind.MATH_BLOCK, latex = block.latex, display = true)
                out.append('\n')
                idx = block.endLine + 1
                continue
            }
            if (TABLE_SEP.matches(line) && line.contains('-') && (line.contains('|') || line.trim().startsWith(":-"))) continue

            val start = out.length
            val header = HEADER.find(line)
            val bullet = BULLET.find(line)
            when {
                header != null -> {
                    inline(header.groupValues[1].trim(), out, spans)
                    spans += Span(start, out.length, Kind.HEADER)
                }
                bullet != null -> {
                    out.append(bullet.groupValues[1]).append("• ")
                    inline(bullet.groupValues[2], out, spans)
                }
                trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2 -> {
                    val cells = trimmed.trim('|').split('|').map { it.trim() }
                    inline(cells.joinToString(" · "), out, spans)
                }
                else -> inline(line, out, spans)
            }
            out.append('\n')
        }
        if (inFence) closeFence()
        while (out.isNotEmpty() && out.last() == '\n') out.setLength(out.length - 1)
        // 끝에서 잘린 범위를 바로잡는다
        val len = out.length
        return Result(out.toString(), spans.mapNotNull { s ->
            if (s.start >= len) null else if (s.end > len) s.copy(end = len) else s
        }.filter { it.end > it.start })
    }

    private class Block(val endLine: Int, val latex: String)

    /** [from] 줄이 블록 수식의 시작이면 끝 줄과 본문을 준다. 닫는 구분자 뒤에 다른 글이 있으면 블록이 아니다. */
    private fun blockMath(lines: List<String>, from: Int): Block? {
        val first = lines[from].trim()
        val (open, close) = when {
            first.startsWith("\\[") -> "\\[" to "\\]"
            first.startsWith("$$") -> "$$" to "$$"
            else -> return null
        }
        val body = StringBuilder()
        var rest = first.substring(open.length)
        var line = from
        while (true) {
            val at = rest.indexOf(close)
            if (at >= 0) {
                if (rest.substring(at + close.length).isNotBlank()) return null
                body.append(rest, 0, at)
                val latex = body.toString().trim()
                return if (latex.isEmpty()) null else Block(line, latex)
            }
            body.append(rest).append('\n')
            line++
            if (line >= lines.size) return null
            val next = lines[line].trim()
            if (next.startsWith("```")) return null
            rest = next
        }
    }

    /** [i]에서 인라인 수식이 시작하면 (끝 인덱스(배타), 본문, 디스플레이 여부)를 준다. */
    internal fun inlineMathAt(s: String, i: Int): Triple<Int, String, Boolean>? {
        fun delimited(open: String, close: String, display: Boolean): Triple<Int, String, Boolean>? {
            if (!s.startsWith(open, i)) return null
            val end = s.indexOf(close, i + open.length)
            if (end < 0) return null
            val body = s.substring(i + open.length, end)
            if (body.isBlank()) return null
            return Triple(end + close.length, body.trim(), display)
        }
        if (i > 0 && s[i - 1] == '\\' && s[i] == '$') return null // \$는 글자 그대로
        delimited("\\(", "\\)", false)?.let { return it }
        delimited("\\[", "\\]", true)?.let { return it }
        if (s.startsWith("$$", i)) return delimited("$$", "$$", true)
        if (s[i] != '$') return null
        // 단일 $: 여는 $ 바로 뒤가 공백이 아니고, 닫는 $ 바로 앞이 공백이 아니며 뒤에 숫자가 오지 않아야 한다(가격 "$5 and $10" 오탐 방지).
        if (i + 1 >= s.length || s[i + 1].isWhitespace() || s[i + 1] == '$') return null
        var j = i + 1
        while (j < s.length) {
            if (s[j] == '$' && s[j - 1] != '\\') {
                if (s[j - 1].isWhitespace()) return null
                if (j + 1 < s.length && s[j + 1].isDigit()) return null
                val body = s.substring(i + 1, j)
                return if (body.isBlank()) null else Triple(j + 1, body, false)
            }
            j++
        }
        return null
    }

    private fun inline(s: String, out: StringBuilder, spans: MutableList<Span>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' || c == '$') {
                val m = inlineMathAt(s, i)
                if (m != null) {
                    val start = out.length
                    out.append(s, i, m.first)
                    spans += Span(start, out.length, Kind.MATH, latex = m.second, display = m.third)
                    i = m.first
                    continue
                }
            }
            if (c == '*' && s.startsWith("**", i)) {
                val close = s.indexOf("**", i + 2)
                if (close > i + 2) {
                    val start = out.length
                    inline(s.substring(i + 2, close), out, spans)
                    spans += Span(start, out.length, Kind.BOLD)
                    i = close + 2
                    continue
                }
            }
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(s, i + 1, close)
                    spans += Span(start, out.length, Kind.CODE)
                    i = close + 1
                    continue
                }
            }
            if (c == '[') {
                val mid = s.indexOf("](", i + 1)
                val end = if (mid > i) s.indexOf(')', mid + 2) else -1
                if (mid > i + 1 && end > mid + 2) {
                    val url = s.substring(mid + 2, end).trim()
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        val start = out.length
                        out.append(s, i + 1, mid)
                        spans += Span(start, out.length, Kind.LINK, url)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
    }
}
