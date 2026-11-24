package kr.snu.etlassistant.pdf

import kr.snu.etlassistant.files.GeneratedFilesLogic
import kr.snu.etlassistant.pdf.PdfMarkdown.Block
import kr.snu.etlassistant.pdf.PdfMarkdown.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLogicTest {

    @Test fun parsesBlocks() {
        val md = """
            # 큰 제목
            ## 절 제목
            #### 깊은 제목

            첫 문단
            이어지는 줄.

            - 하나
            - 둘
              - 안쪽
              이어지는 설명
            1. 첫째
            1. 둘째

            > 인용

            ---

            ```kotlin
            val x = 1
              들여쓰기
            ```

            $$
            \frac{a}{b}
            $$

            | 이름 | 값 |
            |---|:--:|
            | a | `x|y` |
            | b |
        """.trimIndent()
        val blocks = PdfMarkdown.parse(md)
        assertEquals(Block.Heading(1, PdfMarkdown.Inline("큰 제목")), blocks[0])
        assertEquals(2, (blocks[1] as Block.Heading).level)
        assertEquals(3, (blocks[2] as Block.Heading).level)
        assertEquals("첫 문단 이어지는 줄.", (blocks[3] as Block.Paragraph).text.text)
        val items = blocks.filterIsInstance<Block.ListItem>()
        assertEquals(listOf("•", "•", "◦", "1.", "2."), items.map { it.marker })
        assertEquals(listOf(0, 0, 1, 0, 0), items.map { it.level })
        assertEquals("안쪽 이어지는 설명", items[2].text.text)
        assertTrue(blocks.contains(Block.Quote(PdfMarkdown.Inline("인용"))))
        assertTrue(blocks.contains(Block.Rule))
        assertEquals("val x = 1\n  들여쓰기", blocks.filterIsInstance<Block.Code>().single().text)
        assertEquals("\\frac{a}{b}", blocks.filterIsInstance<Block.MathBlock>().single().latex)
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(listOf("이름", "값"), table.header.map { it.text })
        assertEquals(listOf("a", "x|y"), table.rows[0].map { it.text })
        assertEquals(listOf("b", ""), table.rows[1].map { it.text }) // 모자란 칸은 빈 칸
    }

    @Test fun parsesInlineStyles() {
        val i = PdfMarkdown.inline("**굵게** 와 *기울임*, `코드`, [링크](https://a.b) 그리고 \\(x^2\\) snake_case_name \\*별")
        assertEquals("굵게 와 기울임, 코드, 링크 그리고 x² snake_case_name *별", i.text)
        fun styled(style: Style) = i.spans.filter { it.style == style }.map { i.text.substring(it.start, it.end) }
        assertEquals(listOf("굵게"), styled(Style.BOLD))
        assertEquals(listOf("기울임"), styled(Style.ITALIC))
        assertEquals(listOf("코드"), styled(Style.CODE))
        assertEquals(listOf("링크"), styled(Style.LINK))
        assertEquals("x^2", i.spans.single { it.style == Style.MATH }.latex)
        // 가격 같은 $는 수식이 아니다
        assertTrue(PdfMarkdown.inline("가격은 $5 and $10").spans.isEmpty())
        // 닫히지 않은 서식은 글자 그대로
        assertEquals("**열린 굵게", PdfMarkdown.inline("**열린 굵게").text)
    }

    @Test fun paragraphsAndBlockMathEdgeCases() {
        val b = PdfMarkdown.parse("\\[ E = mc^2 \\]\n본문 \$a+b\$ 끝\n\n\n")
        assertEquals("E = mc^2", (b[0] as Block.MathBlock).latex)
        assertEquals(Style.MATH, (b[1] as Block.Paragraph).text.spans.single().style)
        // 닫히지 않은 코드 블록은 끝까지 코드
        assertEquals("a\nb", (PdfMarkdown.parse("```\na\nb").single() as Block.Code).text)
        assertTrue(PdfMarkdown.parse("   \n\n").isEmpty())
    }

    @Test fun fileNames() {
        assertEquals("회로이론 요약.pdf", PdfLayoutLogic.fileName("회로이론 요약", null))
        assertEquals("정리본.pdf", PdfLayoutLogic.fileName("제목", "정리본.PDF"))
        assertEquals("b_c.pdf", PdfLayoutLogic.fileName("x", "../a/b:c"))
        assertEquals("c.pdf", PdfLayoutLogic.fileName("x", "a/b/c"))
        assertEquals("숨김.pdf", PdfLayoutLogic.fileName("...숨김", " "))
        assertEquals("영민이 문서.pdf", PdfLayoutLogic.fileName("", ""))
        assertEquals("줄 바꿈.pdf", PdfLayoutLogic.fileName("줄  바꿈\n", null))
        assertEquals("a_b.pdf", PdfLayoutLogic.fileName("a\u0007b", null))
        val long = PdfLayoutLogic.fileName("가".repeat(300), null)
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test fun paginatesWithGapsAndKeepWithNext() {
        val I = PdfLayoutLogic::Item
        // 쪽 높이 100: 40 + (10+40) = 90, 다음 40은 넘치므로 새 쪽 맨 위(간격 버림)
        val p = PdfLayoutLogic.paginate(listOf(I(40f, 10f, false), I(40f, 10f, false), I(40f, 10f, false)), 100f)
        assertEquals(listOf(PdfLayoutLogic.Placement(0, 0f), PdfLayoutLogic.Placement(0, 50f), PdfLayoutLogic.Placement(1, 0f)), p)
        assertEquals(2, PdfLayoutLogic.pageCount(p))
        // 제목(keepWithNext)이 쪽 끝에 홀로 남지 않는다
        val h = PdfLayoutLogic.paginate(listOf(I(70f, 0f, false), I(15f, 5f, true), I(20f, 0f, false)), 100f)
        assertEquals(listOf(0, 1, 1), h.map { it.page })
        assertEquals(0f, h[1].y)
        // 쪽보다 큰 조각은 새 쪽 맨 위에, 다음 조각은 그다음 쪽
        val big = PdfLayoutLogic.paginate(listOf(I(10f, 0f, false), I(500f, 0f, false), I(10f, 0f, false)), 100f)
        assertEquals(listOf(0, 1, 2), big.map { it.page })
        assertEquals(1, PdfLayoutLogic.pageCount(emptyList()))
        assertEquals("요약 · 2 / 5", PdfLayoutLogic.footer("요약", 2, 5))
        assertEquals("3 / 3", PdfLayoutLogic.footer(" ", 3, 3))
    }

    @Test fun generatedFolderDetection() {
        assertTrue(GeneratedFilesLogic.isGeneratedPath("Download/etl/영민이가 만든 파일/"))
        assertTrue(GeneratedFilesLogic.isGeneratedPath("/download/etl/영민이가 만든 파일/요약.pdf"))
        assertTrue(GeneratedFilesLogic.isGeneratedPath("Download/etl/영민이가 만든 파일"))
        assertFalse(GeneratedFilesLogic.isGeneratedPath("Download/etl/"))
        assertFalse(GeneratedFilesLogic.isGeneratedPath("Download/etl/영민이가 만든 파일.pdf"))
        assertFalse(GeneratedFilesLogic.isGeneratedPath("Download/etl/영민이가 만든 파일들/"))
        assertFalse(GeneratedFilesLogic.isGeneratedPath(null))
    }
}
