package kr.snu.etlassistant.pdf

import kr.snu.etlassistant.chat.MarkdownLite
import kr.snu.etlassistant.chat.MathText

/**
 * make_pdf가 받는 마크다운을 PDF용 블록으로 나눈다(안드로이드 API 없음, JVM 단위 테스트 대상).
 *
 * 지원: 제목(`#`~`###`, 더 깊은 것은 `###`로), 문단, 글머리·번호 목록(들여쓰기 한 단계까지), 인용(`>`), 코드 블록(```),
 * 가로줄(`---`), 표(`|`와 구분 줄), 블록 수식(`$$ $$`, `\[ \]`), 인라인 `**굵게**`·`*기울임*`·`` `코드` ``·`[글](링크)`·
 * 인라인 수식(`\( \)`, `$ $`). 수식 스팬의 글은 유니코드 대체 글([MathText.toUnicode])이고, 그리는 쪽이 그림으로 바꾼다.
 * HTML 태그는 해석하지 않는다(`<br>`만 줄바꿈).
 */
object PdfMarkdown {

    enum class Style { BOLD, ITALIC, CODE, MATH, LINK }

    data class Span(val start: Int, val end: Int, val style: Style, val latex: String? = null)

    /** 서식이 붙은 글. [text]는 표시할 글 그대로(마크다운 기호 제거). */
    data class Inline(val text: String, val spans: List<Span> = emptyList())

    sealed class Block {
        data class Heading(val level: Int, val text: Inline) : Block()
        data class Paragraph(val text: Inline) : Block()
        /** [level] 0 또는 1. [marker]는 "•"/"◦" 또는 "1." 같은 번호. */
        data class ListItem(val ordered: Boolean, val marker: String, val level: Int, val text: Inline) : Block()
        data class Quote(val text: Inline) : Block()
        data class Code(val text: String) : Block()
        data class MathBlock(val latex: String) : Block()
        data class Table(val header: List<Inline>, val rows: List<List<Inline>>) : Block()
        object Rule : Block() {
            override fun toString() = "Rule"
        }
    }

    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val LIST = Regex("^(\\s*)([-*+•]|\\d{1,3}[.)])\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    fun parse(markdown: String): List<Block> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n")
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        val quote = mutableListOf<String>()
        var lastList: Int = -1 // out 안의 마지막 목록 항목 위치(이어지는 줄을 붙인다)
        val listCounters = IntArray(2)

        fun flushPara() {
            if (para.isNotEmpty()) out += Block.Paragraph(inline(para.joinToString(" ") { it.trim() }))
            para.clear()
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) out += Block.Quote(inline(quote.joinToString(" ") { it.trim() }.trim()))
            quote.clear()
        }
        fun flushAll() {
            flushPara()
            flushQuote()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            // 코드 블록
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushAll()
                lastList = -1
                val fence = trimmed.take(3)
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    body += lines[i]
                    i++
                }
                i++ // 닫는 줄(없으면 끝까지)
                out += Block.Code(body.joinToString("\n").trimEnd())
                continue
            }
            if (trimmed.isEmpty()) {
                flushAll()
                // 빈 줄 하나로는 목록이 끝나지 않는다(느슨한 목록). 다음 줄이 목록도 들여쓰기도 아니면 끝난다.
                i++
                continue
            }
            // 블록 수식
            val math = blockMath(lines, i)
            if (math != null) {
                flushAll()
                lastList = -1
                out += Block.MathBlock(math.second)
                i = math.first + 1
                continue
            }
            // 표: 이 줄에 |가 있고 다음 줄이 구분 줄
            if (line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                flushAll()
                lastList = -1
                val header = cells(line)
                val rows = mutableListOf<List<Inline>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    val r = cells(lines[i])
                    rows += List(header.size) { r.getOrElse(it) { Inline("") } }
                    i++
                }
                out += Block.Table(header, rows)
                continue
            }
            val hm = HEADING.matchEntire(line)
            if (hm != null) {
                flushAll()
                lastList = -1
                out += Block.Heading(hm.groupValues[1].length.coerceAtMost(3), inline(hm.groupValues[2]))
                i++
                continue
            }
            if (RULE.matches(line)) {
                flushAll()
                lastList = -1
                out += Block.Rule
                i++
                continue
            }
            val lm = LIST.matchEntire(line)
            if (lm != null) {
                flushAll()
                val indent = lm.groupValues[1].length
                val level = if (indent >= 2 && lastList >= 0) 1 else 0
                val raw = lm.groupValues[2]
                val ordered = raw.first().isDigit()
                if (level == 0) listCounters[1] = 0
                if (lastList < 0 && level == 0) listCounters[0] = 0
                val marker = if (ordered) {
                    // "1. 1. 1."처럼 같은 번호만 써도 이어지는 목록이면 차례로 센다.
                    val given = raw.dropLast(1).toIntOrNull() ?: 0
                    val prev = listCounters[level]
                    listCounters[level] = if (lastList >= 0 && prev > 0 && given <= prev) prev + 1 else given.coerceAtLeast(1)
                    "${listCounters[level]}."
                } else if (level == 0) "•" else "◦"
                out += Block.ListItem(ordered, marker, level, inline(lm.groupValues[3]))
                lastList = out.lastIndex
                i++
                continue
            }
            // 목록 항목에 이어지는 들여쓴 줄
            if (lastList >= 0 && line.startsWith("  ") && para.isEmpty() && quote.isEmpty()) {
                val item = out[lastList] as Block.ListItem
                out[lastList] = item.copy(text = append(item.text, inline(trimmed)))
                i++
                continue
            }
            val qm = QUOTE.matchEntire(line)
            if (qm != null) {
                flushPara()
                lastList = -1
                quote += qm.groupValues[1]
                i++
                continue
            }
            flushQuote()
            lastList = -1
            para += line
            i++
        }
        flushAll()
        return out
    }

    /** [from] 줄이 블록 수식이면 (끝 줄, 본문). 닫는 구분자 뒤에 글이 있거나 닫히지 않으면 null. */
    private fun blockMath(lines: List<String>, from: Int): Pair<Int, String>? {
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
                return if (latex.isEmpty()) null else line to latex
            }
            body.append(rest).append('\n')
            line++
            if (line >= lines.size || line - from > 60) return null
            rest = lines[line].trim()
            if (rest.startsWith("```")) return null
        }
    }

    /** 표 한 줄을 칸으로. 앞뒤 `|`는 빼고, `\|`는 글자 `|`, 코드(`` ` ``) 안의 `|`는 나누지 않는다. */
    internal fun cells(line: String): List<Inline> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inCode = false
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                c == '\\' && k + 1 < s.length && s[k + 1] == '|' -> { cur.append('|'); k++ }
                c == '`' -> { inCode = !inCode; cur.append(c) }
                c == '|' && !inCode -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            k++
        }
        out += cur.toString()
        return out.map { inline(it.trim()) }
    }

    private fun append(a: Inline, b: Inline): Inline {
        val off = a.text.length + 1
        return Inline(a.text + " " + b.text, a.spans + b.spans.map { it.copy(start = it.start + off, end = it.end + off) })
    }

    /** 인라인 서식을 풀어 글과 스팬으로. */
    fun inline(src: String): Inline {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        inlineInto(BR.replace(src, "\n"), out, spans, 0)
        return Inline(out.toString(), spans.sortedWith(compareBy({ it.start }, { -it.end })))
    }

    private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit())

    private fun inlineInto(s: String, out: StringBuilder, spans: MutableList<Span>, depth: Int) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' || c == '$') {
                val m = MarkdownLite.inlineMathAt(s, i)
                if (m != null) {
                    val start = out.length
                    out.append(MathText.toUnicode(m.second).ifBlank { m.second })
                    spans += Span(start, out.length, Style.MATH, latex = m.second)
                    i = m.first
                    continue
                }
                // 마크다운 이스케이프: \* \_ \# 등은 글자 그대로
                if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|>~$") {
                    out.append(s[i + 1])
                    i += 2
                    continue
                }
            }
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(s, i + 1, close)
                    spans += Span(start, out.length, Style.CODE)
                    i = close + 1
                    continue
                }
            }
            if (depth < 4 && (s.startsWith("**", i) || s.startsWith("__", i))) {
                val mark = s.substring(i, i + 2)
                val close = s.indexOf(mark, i + 2)
                if (close > i + 2 && (mark == "**" || !isWordChar(s.getOrNull(close + 2)))) {
                    val start = out.length
                    inlineInto(s.substring(i + 2, close), out, spans, depth + 1)
                    spans += Span(start, out.length, Style.BOLD)
                    i = close + 2
                    continue
                }
            }
            if (depth < 4 && s.startsWith("~~", i)) {
                val close = s.indexOf("~~", i + 2)
                if (close > i + 2) { // 취소선은 글만 남긴다
                    inlineInto(s.substring(i + 2, close), out, spans, depth + 1)
                    i = close + 2
                    continue
                }
            }
            if (depth < 4 && (c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c &&
                (c == '*' || !isWordChar(s.getOrNull(i - 1)))
            ) {
                var close = i + 1
                var found = -1
                while (close < s.length) {
                    close = s.indexOf(c, close)
                    if (close < 0) break
                    val doubled = s.getOrNull(close + 1) == c
                    if (!doubled && !s[close - 1].isWhitespace() && (c == '*' || !isWordChar(s.getOrNull(close + 1)))) { found = close; break }
                    close += if (doubled) 2 else 1
                }
                if (found > i + 1) {
                    val start = out.length
                    inlineInto(s.substring(i + 1, found), out, spans, depth + 1)
                    spans += Span(start, out.length, Style.ITALIC)
                    i = found + 1
                    continue
                }
            }
            if (c == '[') {
                val mid = s.indexOf("](", i + 1)
                val end = if (mid > i) s.indexOf(')', mid + 2) else -1
                if (mid > i + 1 && end > mid + 1) {
                    val start = out.length
                    inlineInto(s.substring(i + 1, mid), out, spans, depth + 1)
                    spans += Span(start, out.length, Style.LINK)
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
    }
}
