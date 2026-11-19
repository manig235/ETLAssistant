package kr.snu.etlassistant.chat

/**
 * 모델 응답에 자주 나오는 마크다운만 처리하는 작은 변환기. 안드로이드 API에 의존하지 않는다.
 *
 * 지원: `**굵게**`, `` `코드` ``, 코드 블록(```), `#` 제목, `-`/`*` 목록, `[글](https://…)` 링크, 표(셀을 ` · `로 이어 붙임).
 * 링크는 http/https만 허용한다(모델 출력에 다른 스킴이 섞여도 열리지 않게).
 */
object MarkdownLite {

    enum class Kind { BOLD, CODE, CODE_BLOCK, HEADER, LINK }

    data class Span(val start: Int, val end: Int, val kind: Kind, val url: String? = null)

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

        for (line in lines) {
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

    private fun inline(s: String, out: StringBuilder, spans: MutableList<Span>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
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
