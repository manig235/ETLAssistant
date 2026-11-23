package kr.snu.etlassistant.chat

import org.junit.Assert.*
import org.junit.Test

class MathSelectionTextTest {
    @Test fun inlineMathAndSurroundingProseRemainOneSelectableText() {
        val raw = "수식 앞 문장 \\(x^2+1\\) 수식 뒤 문장이에요."
        val content = MathSelectionText.prepare(MarkdownLite.render(raw))
        assertEquals(raw, content.display)
        assertEquals(raw, content.copySelection(0, raw.length))
        val start = raw.indexOf("수식 뒤")
        assertEquals("수식 뒤 문장이에요.", content.copySelection(start, raw.length))
    }

    @Test fun multilineDisplayMathKeepsOffsetsAndCopiesOriginalNewlines() {
        val raw = "앞 문장\n\\[\n x^2 + y^2\n\\]\n뒤 문장"
        val original = MarkdownLite.render(raw)
        val content = MathSelectionText.prepare(original)
        assertEquals(original.text.length, content.display.length)
        val math = content.maths.single()
        assertFalse(content.display.substring(math.start, math.end).contains('\n'))
        assertTrue(content.display.substring(0, math.start).contains('\n'))
        assertEquals(original.text, content.copySelection(0, original.text.length))
        val start = original.text.indexOf("뒤 문장")
        assertEquals("뒤 문장", content.copySelection(start, original.text.length))
    }

    @Test fun copyingAcrossTwoBlocksKeepsBothFormulasAndIntermediateText() {
        val result = MarkdownLite.render("**앞**\n\$\$x^2\$\$\n중간 문장\n\\[\ny+1\n\\]\n뒤")
        val content = MathSelectionText.prepare(result)
        assertEquals(2, content.maths.size)
        assertEquals(result.text, content.copySelection(result.text.length, 0))
        assertEquals(1, result.spans.count { it.kind == MarkdownLite.Kind.BOLD })
    }

    @Test fun formulaImageIsCopiedAsCompleteLatexAndPlainTextSelectionStaysExact() {
        val content = MathSelectionText.prepare(MarkdownLite.render("앞 \\(x^2\\) 뒤"))
        val math = content.maths.single()
        assertEquals("\\(x^2\\)", content.copySelection(math.start + 2, math.end - 1))
        assertEquals("앞", content.copySelection(0, 1))
        assertEquals("", content.copySelection(math.start + 2, math.start + 2))
    }

    @Test fun codeAndUnclosedMathRemainLiteralAndSelectionBoundsAreClamped() {
        val result = MarkdownLite.render("`\\(x\\)` 과 닫히지 않은 \\(y")
        val content = MathSelectionText.prepare(result)
        assertTrue(content.maths.isEmpty())
        assertEquals(result.text, content.display)
        assertEquals(result.text, content.copySelection(-10, result.text.length + 20))
    }
}
