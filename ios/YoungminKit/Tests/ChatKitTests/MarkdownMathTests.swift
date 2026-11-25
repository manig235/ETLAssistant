import Foundation
import Testing
@testable import ChatKit

private func spansOf(_ r: MarkdownLite.Result, _ k: MarkdownLite.Kind) -> [String] {
    r.spans.filter { $0.kind == k }.map { r.text.substring16($0.start, $0.end) }
}

// Kotlin MarkdownLiteTest
@Suite struct MarkdownLiteTests {

    @Test func boldAndCode() {
        let r = MarkdownLite.render("마감은 **11/23(월) 15:00** 이고 `etl_digest`를 썼습니다")
        #expect(r.text == "마감은 11/23(월) 15:00 이고 etl_digest를 썼습니다")
        #expect(spansOf(r, .bold) == ["11/23(월) 15:00"])
        #expect(spansOf(r, .code) == ["etl_digest"])
    }

    /// iOS에서 더한 기울임(PdfMarkdown과 같은 규칙).
    @Test func italics() {
        let r = MarkdownLite.render("**굵게** 와 *기울임*, _밑줄 기울임_ 그리고 `코드` snake_case_name")
        #expect(r.text == "굵게 와 기울임, 밑줄 기울임 그리고 코드 snake_case_name")
        #expect(spansOf(r, .italic) == ["기울임", "밑줄 기울임"])
        #expect(spansOf(r, .bold) == ["굵게"])
        // 기울임 안의 굵게·수식
        let nested = MarkdownLite.render("*참고: **중요** \\(x^2\\) 끝*")
        #expect(spansOf(nested, .italic) == ["참고: 중요 \\(x^2\\) 끝"])
        #expect(spansOf(nested, .bold) == ["중요"])
        #expect(nested.spans.contains { $0.kind == .math && $0.latex == "x^2" })
        // 표시가 아닌 경우: 공백 옆, 단어 안 밑줄, 닫히지 않음, 역슬래시, 목록 기호
        for src in ["2 * 3 * 4", "a_b_c", "*열린 기울임", "\\*별\\*", "_ a _", "**", "x_1 + y_2"] {
            #expect(spansOf(MarkdownLite.render(src), .italic).isEmpty, "\(src)")
        }
        let list = MarkdownLite.render("* 항목 *강조*")
        #expect(list.text == "• 항목 강조")
        #expect(spansOf(list, .italic) == ["강조"])
        // 화면 스타일
        let styles = ChatLogic.styleRanges(r)
        #expect(styles.filter(\.italic).count == 2)
        #expect(styles.filter(\.italic).allSatisfy { !$0.bold && !$0.mono })
    }

    @Test func unmatchedMarkersStayLiteral() {
        #expect(MarkdownLite.render("a **b").text == "a **b")
        #expect(MarkdownLite.render("a `b").text == "a `b")
        #expect(MarkdownLite.render("2 * 3 = 6").text == "2 * 3 = 6")
        #expect(MarkdownLite.render("[x](").text == "[x](")
    }

    @Test func linksOnlyForHttp() {
        let r = MarkdownLite.render("[과제](https://myetl.snu.ac.kr/courses/1) 와 [나쁨](javascript:alert(1)) 와 [파일](file:///x)")
        #expect(spansOf(r, .link) == ["과제"])
        #expect(r.spans.first { $0.kind == .link }!.url == "https://myetl.snu.ac.kr/courses/1")
        #expect(r.text.contains("[나쁨](javascript:alert(1)"))
        #expect(r.spans.filter { $0.kind == .link }.count == 1)
    }

    @Test func headersAndBullets() {
        let r = MarkdownLite.render("## 이번 주\n- 하나\n* 둘\n  - 셋")
        #expect(r.text == "이번 주\n• 하나\n• 둘\n  • 셋")
        #expect(spansOf(r, .header) == ["이번 주"])
    }

    @Test func codeBlock() {
        let r = MarkdownLite.render("앞\n```text\nline1 **x**\nline2\n```\n뒤")
        #expect(r.text == "앞\nline1 **x**\nline2\n뒤")
        #expect(spansOf(r, .codeBlock) == ["line1 **x**\nline2"])
        #expect(spansOf(r, .bold).isEmpty)
    }

    @Test func unclosedCodeBlockIsClosed() {
        let r = MarkdownLite.render("```\nabc")
        #expect(r.text == "abc")
        #expect(spansOf(r, .codeBlock) == ["abc"])
    }

    @Test func tablesBecomeDotSeparatedRows() {
        let r = MarkdownLite.render("| 강의 | 결과 |\n|---|---:|\n| 305927 | 13 |\n| 305929 | 0 |")
        #expect(r.text == "강의 · 결과\n305927 · 13\n305929 · 0")
    }

    @Test func plainTextAndEmpty() {
        #expect(MarkdownLite.render("").text == "")
        #expect(MarkdownLite.render("안녕하세요").text == "안녕하세요")
        #expect(MarkdownLite.render("a\r\nb").text == "a\nb")
        #expect(!MarkdownLite.render("a\n\n\n").text.hasSuffix("\n"))
    }

    @Test func spansStayInBounds() {
        for src in ["**", "****", "**a", "`", "``", "[a](https://x", "- ", "# ", "```", "|", "| a |"] {
            let r = MarkdownLite.render(src)
            for s in r.spans { #expect(s.start >= 0 && s.end <= r.text.length16 && s.start < s.end, "\(src) -> \(s)") }
        }
    }

    @Test func spanOffsetsAreUTF16() {
        let r = MarkdownLite.render("😀 **굵게**")
        let b = r.spans.first { $0.kind == .bold }!
        #expect(b.start == 3 && b.end == 5)
    }
}

// Kotlin MathTextTest
@Suite struct MathTextTests {
    private func maths(_ r: MarkdownLite.Result) -> [MarkdownLite.Span] { r.spans.filter { $0.kind == .math } }
    private func blocks(_ r: MarkdownLite.Result) -> [MarkdownLite.Span] { r.spans.filter { $0.kind == .mathBlock } }

    @Test func inlineParenDelimiters() {
        let src = "고온에서는 원자당 열용량이 \\(3k_B\\)에 접근해요."
        let r = MarkdownLite.render(src)
        #expect(r.text == src)
        let m = maths(r)
        #expect(m.count == 1)
        #expect(m[0].latex == "3k_B")
        #expect(!m[0].display)
        #expect(r.text.substring16(m[0].start, m[0].end) == "\\(3k_B\\)")
    }

    @Test func inlineDollar() {
        let r = MarkdownLite.render("상태밀도는 $g(\\omega)\\propto\\omega^2$ 이고")
        #expect(maths(r).map(\.latex) == ["g(\\omega)\\propto\\omega^2"])
    }

    @Test func dollarPricesAreNotMath() {
        #expect(maths(MarkdownLite.render("가격은 $5 and $10 이에요")).isEmpty)
        #expect(maths(MarkdownLite.render("$ x $")).isEmpty)
        #expect(maths(MarkdownLite.render("$x$5")).isEmpty)
        #expect(maths(MarkdownLite.render("비용 \\$3 그리고 \\$4")).isEmpty)
    }

    @Test func unclosedMathStaysRaw() {
        let r = MarkdownLite.render("열용량은 \\(3k_")
        #expect(r.spans.isEmpty)
        #expect(r.text == "열용량은 \\(3k_")
        #expect(blocks(MarkdownLite.render("저온에서\n\\[\nC_V\\simeq\n")).isEmpty)
    }

    @Test func codeIsNotParsed() {
        let r = MarkdownLite.render("`\\(x\\)` 와\n```\n$$a$$\n```")
        #expect(maths(r).isEmpty)
        #expect(blocks(r).isEmpty)
    }

    @Test func multiLineBlock() {
        let src = "저온에서\n   \\[\n   C_V\\simeq\\frac{12\\pi^4}{5}Nk_B\\left(\\frac{T}{\\Theta_D}\\right)^3\n   \\]\n이에요"
        let r = MarkdownLite.render(src)
        let b = blocks(r)
        #expect(b.count == 1)
        #expect(b[0].latex == "C_V\\simeq\\frac{12\\pi^4}{5}Nk_B\\left(\\frac{T}{\\Theta_D}\\right)^3")
        #expect(b[0].display)
        let pieces = MathText.split(r)
        #expect(pieces.count == 3)
        guard case .text(let first) = pieces[0], case .block(_, let raw) = pieces[1], case .text(let last) = pieces[2] else {
            Issue.record("unexpected pieces"); return
        }
        #expect(first.text == "저온에서")
        #expect(raw.hasPrefix("\\["))
        #expect(last.text == "이에요")
    }

    @Test func singleLineBlocks() {
        #expect(blocks(MarkdownLite.render("$$E=mc^2$$")).map(\.latex) == ["E=mc^2"])
        #expect(blocks(MarkdownLite.render("\\[ a+b \\]")).map(\.latex) == ["a+b"])
        let r = MarkdownLite.render("\\[a\\] 그리고 b")
        #expect(blocks(r).isEmpty)
        #expect(maths(r).count == 1 && maths(r)[0].display)
    }

    @Test func mathInsideBulletAndBold() {
        let r = MarkdownLite.render("- **Einstein 모형**은 \\(\\omega\\)로 진동")
        #expect(maths(r).map(\.latex) == ["\\omega"])
        #expect(r.spans.filter { $0.kind == .bold }.count == 1)
    }

    @Test func splitKeepsSpanOffsets() {
        let r = MarkdownLite.render("**앞**\n$$x$$\n뒤 **굵게**")
        guard case .text(let last) = MathText.split(r).last! else { Issue.record("no text"); return }
        let bold = last.spans.filter { $0.kind == .bold }
        #expect(bold.count == 1)
        #expect(last.text.substring16(bold[0].start, bold[0].end) == "굵게")
    }

    @Test func spansStayInBounds() {
        for src in ["\\(", "\\(\\)", "$", "$$", "$$$", "\\[", "\\[\\]", "$a", "a$", "\\(a\\", "$$\n$$"] {
            let r = MarkdownLite.render(src)
            for s in r.spans { #expect(s.start >= 0 && s.end <= r.text.length16 && s.start < s.end, "\(src) -> \(s)") }
            _ = MathText.split(r)
        }
    }

    @Test func mapOffsetAcrossReplacements() {
        let reps = [MathText.Replacement(2, 6, 2, 3), MathText.Replacement(8, 10, 5, 9)]
        #expect(MathText.mapOffset(reps, 0, isEnd: false) == 0)
        #expect(MathText.mapOffset(reps, 2, isEnd: false) == 2)
        #expect(MathText.mapOffset(reps, 6, isEnd: true) == 3)
        #expect(MathText.mapOffset(reps, 4, isEnd: false) == 2)
        #expect(MathText.mapOffset(reps, 4, isEnd: true) == 3)
        #expect(MathText.mapOffset(reps, 7, isEnd: false) == 4)
        #expect(MathText.mapOffset(reps, 12, isEnd: true) == 11)
    }

    @Test func unicodeFallback() {
        #expect(MathText.toUnicode("3k_B") == "3k_B")
        #expect(MathText.toUnicode("\\omega") == "ω")
        #expect(MathText.toUnicode("g(\\omega)\\propto\\omega^2") == "g(ω)∝ω²")
        #expect(MathText.toUnicode("T^3") == "T³")
        #expect(MathText.toUnicode("\\Theta_D=\\frac{\\hbar\\omega_D}{k_B}") == "Θ_D=ℏω_D/k_B")
        #expect(MathText.toUnicode("C_V\\simeq\\frac{12\\pi^4}{5}Nk_B\\left(\\frac{T}{\\Theta_D}\\right)^3") == "C_V≃12π⁴/5Nk_B(T/Θ_D)³")
        #expect(MathText.toUnicode("x_{12}") == "x₁₂")
        #expect(MathText.toUnicode("\\sqrt{x+1}") == "√(x+1)")
        #expect(MathText.toUnicode("\\vec{v}") == "v⃗")
        #expect(MathText.toUnicode("a\\qquad b") == "a  b")
        #expect(MathText.toUnicode("E = mc^2") == "E=mc²")
        #expect(MathText.toUnicode("\\text{속도 } v") == "속도 v")
    }

    @Test func unicodeFallbackNeverThrows() {
        for src in ["", "\\", "{", "}", "^", "_", "\\frac", "\\frac{", "\\sqrt[", "x^{", "\\unknowncmd{x}"] {
            _ = MathText.toUnicode(src)
        }
        #expect(MathText.toUnicode("\\unknowncmd") == "unknowncmd")
    }

    @Test func inlineMathAtRejectsEmpty() {
        #expect(MarkdownLite.inlineMathAt("\\(  \\)", 0) == nil)
        #expect(MarkdownLite.inlineMathAt("$$", 0) == nil)
    }
}

// Kotlin MathSelectionTextTest
@Suite struct MathSelectionTextTests {
    @Test func inlineMathAndSurroundingProseRemainOneSelectableText() {
        let raw = "수식 앞 문장 \\(x^2+1\\) 수식 뒤 문장이에요."
        let content = MathSelectionText.prepare(MarkdownLite.render(raw))
        #expect(content.display == raw)
        #expect(content.copySelection(0, raw.length16) == raw)
        let start = (raw as NSString).range(of: "수식 뒤").location
        #expect(content.copySelection(start, raw.length16) == "수식 뒤 문장이에요.")
    }

    @Test func multilineDisplayMathKeepsOffsetsAndCopiesOriginalNewlines() {
        let raw = "앞 문장\n\\[\n x^2 + y^2\n\\]\n뒤 문장"
        let original = MarkdownLite.render(raw)
        let content = MathSelectionText.prepare(original)
        #expect(content.display.length16 == original.text.length16)
        #expect(content.maths.count == 1)
        let math = content.maths[0]
        #expect(!content.display.substring16(math.start, math.end).contains("\n"))
        #expect(content.display.substring16(0, math.start).contains("\n"))
        #expect(content.copySelection(0, original.text.length16) == original.text)
        let start = (original.text as NSString).range(of: "뒤 문장").location
        #expect(content.copySelection(start, original.text.length16) == "뒤 문장")
    }

    @Test func copyingAcrossTwoBlocksKeepsBothFormulasAndIntermediateText() {
        let result = MarkdownLite.render("**앞**\n$$x^2$$\n중간 문장\n\\[\ny+1\n\\]\n뒤")
        let content = MathSelectionText.prepare(result)
        #expect(content.maths.count == 2)
        #expect(content.copySelection(result.text.length16, 0) == result.text)
        #expect(result.spans.filter { $0.kind == .bold }.count == 1)
    }

    @Test func formulaImageIsCopiedAsCompleteLatexAndPlainTextSelectionStaysExact() {
        let content = MathSelectionText.prepare(MarkdownLite.render("앞 \\(x^2\\) 뒤"))
        let math = content.maths[0]
        #expect(content.copySelection(math.start + 2, math.end - 1) == "\\(x^2\\)")
        #expect(content.copySelection(0, 1) == "앞")
        #expect(content.copySelection(math.start + 2, math.start + 2) == "")
    }

    @Test func codeAndUnclosedMathRemainLiteralAndSelectionBoundsAreClamped() {
        let result = MarkdownLite.render("`\\(x\\)` 과 닫히지 않은 \\(y")
        let content = MathSelectionText.prepare(result)
        #expect(content.maths.isEmpty)
        #expect(content.display == result.text)
        #expect(content.copySelection(-10, result.text.length16 + 20) == result.text)
    }
}

