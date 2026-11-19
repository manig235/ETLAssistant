package kr.snu.etlassistant.chat

import kr.snu.etlassistant.chat.MarkdownLite.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    private fun spansOf(r: MarkdownLite.Result, k: Kind) = r.spans.filter { it.kind == k }.map { r.text.substring(it.start, it.end) }

    @Test fun boldAndCode() {
        val r = MarkdownLite.render("마감은 **11/23(월) 15:00** 이고 `etl_digest`를 썼습니다")
        assertEquals("마감은 11/23(월) 15:00 이고 etl_digest를 썼습니다", r.text)
        assertEquals(listOf("11/23(월) 15:00"), spansOf(r, Kind.BOLD))
        assertEquals(listOf("etl_digest"), spansOf(r, Kind.CODE))
    }

    @Test fun unmatchedMarkersStayLiteral() {
        assertEquals("a **b", MarkdownLite.render("a **b").text)
        assertEquals("a `b", MarkdownLite.render("a `b").text)
        assertEquals("2 * 3 = 6", MarkdownLite.render("2 * 3 = 6").text)
        assertEquals("[x](", MarkdownLite.render("[x](").text)
    }

    @Test fun linksOnlyForHttp() {
        val r = MarkdownLite.render("[과제](https://myetl.snu.ac.kr/courses/1) 와 [나쁨](javascript:alert(1)) 와 [파일](file:///x)")
        assertEquals(listOf("과제"), spansOf(r, Kind.LINK))
        assertEquals("https://myetl.snu.ac.kr/courses/1", r.spans.first { it.kind == Kind.LINK }.url)
        assertTrue(r.text.contains("[나쁨](javascript:alert(1)")) // 링크가 되지 않고 글자로 남는다
        assertEquals(1, r.spans.count { it.kind == Kind.LINK })
    }

    @Test fun headersAndBullets() {
        val r = MarkdownLite.render("## 이번 주\n- 하나\n* 둘\n  - 셋")
        assertEquals("이번 주\n• 하나\n• 둘\n  • 셋", r.text)
        assertEquals(listOf("이번 주"), spansOf(r, Kind.HEADER))
    }

    @Test fun codeBlock() {
        val r = MarkdownLite.render("앞\n```text\nline1 **x**\nline2\n```\n뒤")
        assertEquals("앞\nline1 **x**\nline2\n뒤", r.text)
        assertEquals(listOf("line1 **x**\nline2"), spansOf(r, Kind.CODE_BLOCK))
        assertTrue(spansOf(r, Kind.BOLD).isEmpty()) // 코드 블록 안은 해석하지 않는다
    }

    @Test fun unclosedCodeBlockIsClosed() {
        val r = MarkdownLite.render("```\nabc")
        assertEquals("abc", r.text)
        assertEquals(listOf("abc"), spansOf(r, Kind.CODE_BLOCK))
    }

    @Test fun tablesBecomeDotSeparatedRows() {
        val r = MarkdownLite.render("| 강의 | 결과 |\n|---|---:|\n| 305927 | 13 |\n| 305929 | 0 |")
        assertEquals("강의 · 결과\n305927 · 13\n305929 · 0", r.text)
    }

    @Test fun plainTextAndEmpty() {
        assertEquals("", MarkdownLite.render("").text)
        assertEquals("안녕하세요", MarkdownLite.render("안녕하세요").text)
        assertEquals("a\nb", MarkdownLite.render("a\r\nb").text)
        assertFalse(MarkdownLite.render("a\n\n\n").text.endsWith("\n"))
    }

    @Test fun spansStayInBounds() {
        for (src in listOf("**", "****", "**a", "`", "``", "[a](https://x", "- ", "# ", "```", "|", "| a |")) {
            val r = MarkdownLite.render(src)
            for (s in r.spans) assertTrue("$src -> $s", s.start >= 0 && s.end <= r.text.length && s.start < s.end)
        }
    }
}
