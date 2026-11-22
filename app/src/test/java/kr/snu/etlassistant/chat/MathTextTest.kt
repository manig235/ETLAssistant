package kr.snu.etlassistant.chat

import kr.snu.etlassistant.chat.MarkdownLite.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTextTest {

    private fun maths(r: MarkdownLite.Result) = r.spans.filter { it.kind == Kind.MATH }
    private fun blocks(r: MarkdownLite.Result) = r.spans.filter { it.kind == Kind.MATH_BLOCK }

    // ---- 구분자 ----

    @Test fun inlineParenDelimiters() {
        val src = "고온에서는 원자당 열용량이 \\(3k_B\\)에 접근해요."
        val r = MarkdownLite.render(src)
        assertEquals(src, r.text) // 원문은 그대로 남는다(복사용)
        val m = maths(r).single()
        assertEquals("3k_B", m.latex)
        assertFalse(m.display)
        assertEquals("\\(3k_B\\)", r.text.substring(m.start, m.end))
    }

    @Test fun inlineDollar() {
        val r = MarkdownLite.render("상태밀도는 \$g(\\omega)\\propto\\omega^2\$ 이고")
        assertEquals("g(\\omega)\\propto\\omega^2", maths(r).single().latex)
    }

    @Test fun dollarPricesAreNotMath() {
        assertTrue(maths(MarkdownLite.render("가격은 \$5 and \$10 이에요")).isEmpty())
        assertTrue(maths(MarkdownLite.render("\$ x \$")).isEmpty()) // 여는 $ 뒤 공백
        assertTrue(maths(MarkdownLite.render("\$x\$5")).isEmpty()) // 닫는 $ 뒤 숫자
        assertTrue(maths(MarkdownLite.render("비용 \\\$3 그리고 \\\$4")).isEmpty()) // \$는 글자
    }

    @Test fun unclosedMathStaysRaw() {
        val r = MarkdownLite.render("열용량은 \\(3k_")
        assertTrue(r.spans.isEmpty())
        assertEquals("열용량은 \\(3k_", r.text)
        // 블록도 닫히기 전에는 블록으로 보지 않는다(스트리밍 중)
        val b = MarkdownLite.render("저온에서\n\\[\nC_V\\simeq\n")
        assertTrue(blocks(b).isEmpty())
    }

    @Test fun codeIsNotParsed() {
        val r = MarkdownLite.render("`\\(x\\)` 와\n```\n\$\$a\$\$\n```")
        assertTrue(maths(r).isEmpty())
        assertTrue(blocks(r).isEmpty())
    }

    @Test fun multiLineBlock() {
        val src = "저온에서\n   \\[\n   C_V\\simeq\\frac{12\\pi^4}{5}Nk_B\\left(\\frac{T}{\\Theta_D}\\right)^3\n   \\]\n이에요"
        val r = MarkdownLite.render(src)
        val b = blocks(r).single()
        assertEquals("C_V\\simeq\\frac{12\\pi^4}{5}Nk_B\\left(\\frac{T}{\\Theta_D}\\right)^3", b.latex)
        assertTrue(b.display)
        val pieces = MathText.split(r)
        assertEquals(3, pieces.size)
        assertEquals("저온에서", (pieces[0] as MathText.Piece.Text).result.text)
        assertTrue((pieces[1] as MathText.Piece.Block).raw.startsWith("\\["))
        assertEquals("이에요", (pieces[2] as MathText.Piece.Text).result.text)
    }

    @Test fun singleLineBlocks() {
        assertEquals("E=mc^2", blocks(MarkdownLite.render("\$\$E=mc^2\$\$")).single().latex)
        assertEquals("a+b", blocks(MarkdownLite.render("\\[ a+b \\]")).single().latex)
        // 같은 줄에 다른 글이 있으면 블록이 아니라 인라인(디스플레이 스타일)
        val r = MarkdownLite.render("\\[a\\] 그리고 b")
        assertTrue(blocks(r).isEmpty())
        assertTrue(maths(r).single().display)
    }

    @Test fun mathInsideBulletAndBold() {
        val r = MarkdownLite.render("- **Einstein 모형**은 \\(\\omega\\)로 진동")
        assertEquals("\\omega", maths(r).single().latex)
        assertEquals(1, r.spans.count { it.kind == Kind.BOLD })
    }

    @Test fun splitKeepsSpanOffsets() {
        val r = MarkdownLite.render("**앞**\n\$\$x\$\$\n뒤 **굵게**")
        val pieces = MathText.split(r)
        val last = (pieces.last() as MathText.Piece.Text).result
        val bold = last.spans.single { it.kind == Kind.BOLD }
        assertEquals("굵게", last.text.substring(bold.start, bold.end))
    }

    @Test fun spansStayInBounds() {
        for (src in listOf("\\(", "\\(\\)", "\$", "\$\$", "\$\$\$", "\\[", "\\[\\]", "\$a", "a\$", "\\(a\\", "\$\$\n\$\$")) {
            val r = MarkdownLite.render(src)
            for (s in r.spans) assertTrue("$src -> $s", s.start >= 0 && s.end <= r.text.length && s.start < s.end)
            MathText.split(r) // 예외 없이
        }
    }

    // ---- 위치 옮기기 ----

    @Test fun mapOffsetAcrossReplacements() {
        // 원래 [2,6)을 길이 1로, [8,10)을 길이 4로 바꿈
        val reps = listOf(MathText.Replacement(2, 6, 2, 3), MathText.Replacement(8, 10, 5, 9))
        assertEquals(0, MathText.mapOffset(reps, 0, false))
        assertEquals(2, MathText.mapOffset(reps, 2, false))
        assertEquals(3, MathText.mapOffset(reps, 6, true))
        assertEquals(2, MathText.mapOffset(reps, 4, false)) // 바꾼 구간 안 → 앞
        assertEquals(3, MathText.mapOffset(reps, 4, true)) // → 뒤
        assertEquals(4, MathText.mapOffset(reps, 7, false))
        assertEquals(11, MathText.mapOffset(reps, 12, true))
    }

    // ---- 유니코드 대체 ----

    @Test fun unicodeFallback() {
        assertEquals("3k_B", MathText.toUnicode("3k_B"))
        assertEquals("ω", MathText.toUnicode("\\omega"))
        assertEquals("g(ω)∝ω²", MathText.toUnicode("g(\\omega)\\propto\\omega^2"))
        assertEquals("T³", MathText.toUnicode("T^3"))
        assertEquals("Θ_D=ℏω_D/k_B", MathText.toUnicode("\\Theta_D=\\frac{\\hbar\\omega_D}{k_B}"))
        assertEquals("C_V≃12π⁴/5Nk_B(T/Θ_D)³", MathText.toUnicode("C_V\\simeq\\frac{12\\pi^4}{5}Nk_B\\left(\\frac{T}{\\Theta_D}\\right)^3"))
        assertEquals("x₁₂", MathText.toUnicode("x_{12}"))
        assertEquals("√(x+1)", MathText.toUnicode("\\sqrt{x+1}"))
        assertEquals("v⃗", MathText.toUnicode("\\vec{v}"))
        assertEquals("a  b", MathText.toUnicode("a\\qquad b"))
        assertEquals("E=mc²", MathText.toUnicode("E = mc^2")) // 수식 모드 공백은 버린다
        assertEquals("속도 v", MathText.toUnicode("\\text{속도 } v"))
    }

    @Test fun unicodeFallbackNeverThrows() {
        for (src in listOf("", "\\", "{", "}", "^", "_", "\\frac", "\\frac{", "\\sqrt[", "x^{", "\\unknowncmd{x}")) {
            MathText.toUnicode(src)
        }
        assertEquals("unknowncmd", MathText.toUnicode("\\unknowncmd"))
    }

    @Test fun inlineMathAtRejectsEmpty() {
        assertNull(MarkdownLite.inlineMathAt("\\(  \\)", 0))
        assertNull(MarkdownLite.inlineMathAt("\$\$", 0))
    }
}
