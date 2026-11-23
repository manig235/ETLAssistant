package kr.snu.etlassistant.chat

/** 수식 안의 개행은 한 ReplacementSpan으로 배치하고, 복사할 때는 개행까지 원문으로 복원한다. */
internal data class MathSelectionText(val original: MarkdownLite.Result, val display: String) {
    val maths = original.spans.filter {
        it.kind == MarkdownLite.Kind.MATH || it.kind == MarkdownLite.Kind.MATH_BLOCK
    }

    fun copySelection(start: Int, end: Int): String {
        var from = minOf(start, end).coerceIn(0, original.text.length)
        var to = maxOf(start, end).coerceIn(0, original.text.length)
        if (from == to) return ""
        // 수식 이미지는 한 단위다. 이미지 중간을 가리켜도 LaTeX를 잘라 복사하지 않는다.
        for (math in maths) {
            if (from in (math.start + 1) until math.end) from = math.start
            if (to in (math.start + 1) until math.end) to = math.end
        }
        return original.text.substring(from, to)
    }

    companion object {
        fun prepare(result: MarkdownLite.Result): MathSelectionText {
            val chars = result.text.toCharArray()
            for (span in result.spans.filter {
                it.kind == MarkdownLite.Kind.MATH || it.kind == MarkdownLite.Kind.MATH_BLOCK
            }) {
                for (i in span.start.coerceAtLeast(0) until span.end.coerceAtMost(chars.size)) {
                    if (chars[i] == '\n' || chars[i] == '\r') chars[i] = ' '
                }
            }
            return MathSelectionText(result, chars.concatToString())
        }
    }
}
