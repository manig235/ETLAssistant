import Foundation
import PDFKit
import Testing
import ETLKit
@testable import AssistantKit

/// Kotlin `pdf/PdfLogicTest.kt` + make_pdf가 실제로 여러 쪽 PDF를 만드는지(PDFKit으로 연다).
struct PdfLogicTests {
    private typealias Block = PdfMarkdown.Block

    private func items(_ blocks: [Block]) -> [(marker: String, level: Int, text: String)] {
        blocks.compactMap { if case let .listItem(_, m, l, t) = $0 { return (m, l, t.text) } else { return nil } }
    }

    @Test func parsesBlocks() throws {
        let md = """
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
            \\frac{a}{b}
            $$

            | 이름 | 값 |
            |---|:--:|
            | a | `x|y` |
            | b |
            """
        let blocks = PdfMarkdown.parse(md)
        #expect(blocks[0] == .heading(level: 1, text: PdfMarkdown.Inline("큰 제목")))
        guard case let .heading(l2, _) = blocks[1], case let .heading(l3, _) = blocks[2] else { Issue.record("제목"); return }
        #expect(l2 == 2)
        #expect(l3 == 3)
        guard case let .paragraph(p) = blocks[3] else { Issue.record("문단"); return }
        #expect(p.text == "첫 문단 이어지는 줄.")
        let list = items(blocks)
        #expect(list.map(\.marker) == ["•", "•", "◦", "1.", "2."])
        #expect(list.map(\.level) == [0, 0, 1, 0, 0])
        #expect(list[2].text == "안쪽 이어지는 설명")
        #expect(blocks.contains(.quote(PdfMarkdown.Inline("인용"))))
        #expect(blocks.contains(.rule))
        let codes = blocks.compactMap { if case let .code(t) = $0 { return t } else { return nil } }
        #expect(codes == ["val x = 1\n  들여쓰기"])
        let maths = blocks.compactMap { if case let .mathBlock(t) = $0 { return t } else { return nil } }
        #expect(maths == ["\\frac{a}{b}"])
        let tables = blocks.compactMap { if case let .table(h, r) = $0 { return (h, r) } else { return nil } }
        let table = try #require(tables.count == 1 ? tables[0] : nil)
        #expect(table.0.map(\.text) == ["이름", "값"])
        #expect(table.1[0].map(\.text) == ["a", "x|y"])
        #expect(table.1[1].map(\.text) == ["b", ""]) // 모자란 칸은 빈 칸
    }

    @Test func parsesInlineStyles() {
        let i = PdfMarkdown.inline("**굵게** 와 *기울임*, `코드`, [링크](https://a.b) 그리고 \\(x^2\\) snake_case_name \\*별")
        #expect(i.text == "굵게 와 기울임, 코드, 링크 그리고 x² snake_case_name *별")
        func styled(_ style: PdfMarkdown.Style) -> [String] {
            let u = Array(i.text.utf16)
            return i.spans.filter { $0.style == style }.map { String(decoding: u[$0.start..<$0.end], as: UTF16.self) }
        }
        #expect(styled(.BOLD) == ["굵게"])
        #expect(styled(.ITALIC) == ["기울임"])
        #expect(styled(.CODE) == ["코드"])
        #expect(styled(.LINK) == ["링크"])
        #expect(i.spans.filter { $0.style == .MATH }.map(\.latex) == ["x^2"])
        // 가격 같은 $는 수식이 아니다
        #expect(PdfMarkdown.inline("가격은 $5 and $10").spans.isEmpty)
        // 닫히지 않은 서식은 글자 그대로
        #expect(PdfMarkdown.inline("**열린 굵게").text == "**열린 굵게")
    }

    @Test func paragraphsAndBlockMathEdgeCases() {
        let b = PdfMarkdown.parse("\\[ E = mc^2 \\]\n본문 $a+b$ 끝\n\n\n")
        #expect(b.first == .mathBlock("E = mc^2"))
        if case let .paragraph(p) = b[1] { #expect(p.spans.map(\.style) == [.MATH]) } else { Issue.record("문단") }
        // 닫히지 않은 코드 블록은 끝까지 코드
        #expect(PdfMarkdown.parse("```\na\nb") == [.code("a\nb")])
        #expect(PdfMarkdown.parse("   \n\n").isEmpty)
    }

    @Test func fileNames() {
        #expect(PdfLayoutLogic.fileName("회로이론 요약", nil) == "회로이론 요약.pdf")
        #expect(PdfLayoutLogic.fileName("제목", "정리본.PDF") == "정리본.pdf")
        #expect(PdfLayoutLogic.fileName("x", "../a/b:c") == "b_c.pdf")
        #expect(PdfLayoutLogic.fileName("x", "a/b/c") == "c.pdf")
        #expect(PdfLayoutLogic.fileName("...숨김", " ") == "숨김.pdf")
        #expect(PdfLayoutLogic.fileName("", "") == "영민이 문서.pdf")
        #expect(PdfLayoutLogic.fileName("줄  바꿈\n", nil) == "줄 바꿈.pdf")
        #expect(PdfLayoutLogic.fileName("a\u{0007}b", nil) == "a_b.pdf")
        let long = PdfLayoutLogic.fileName(String(repeating: "가", count: 300), nil)
        #expect(long.hasSuffix(".pdf"))
        #expect(long.utf8.count <= 200)
    }

    @Test func paginatesWithGapsAndKeepWithNext() {
        typealias I = PdfLayoutLogic.Item
        typealias P = PdfLayoutLogic.Placement
        // 쪽 높이 100: 40 + (10+40) = 90, 다음 40은 넘치므로 새 쪽 맨 위(간격 버림)
        let p = PdfLayoutLogic.paginate([I(40, 10, false), I(40, 10, false), I(40, 10, false)], pageHeight: 100)
        #expect(p == [P(0, 0), P(0, 50), P(1, 0)])
        #expect(PdfLayoutLogic.pageCount(p) == 2)
        // 제목(keepWithNext)이 쪽 끝에 홀로 남지 않는다
        let h = PdfLayoutLogic.paginate([I(70, 0, false), I(15, 5, true), I(20, 0, false)], pageHeight: 100)
        #expect(h.map(\.page) == [0, 1, 1])
        #expect(h[1].y == 0)
        // 쪽보다 큰 조각은 새 쪽 맨 위에, 다음 조각은 그다음 쪽
        let big = PdfLayoutLogic.paginate([I(10, 0, false), I(500, 0, false), I(10, 0, false)], pageHeight: 100)
        #expect(big.map(\.page) == [0, 1, 2])
        #expect(PdfLayoutLogic.pageCount([]) == 1)
        #expect(PdfLayoutLogic.footer("요약", 2, 5) == "요약 · 2 / 5")
        #expect(PdfLayoutLogic.footer(" ", 3, 3) == "3 / 3")
    }

    /// 안드로이드는 `Download/etl/영민이가 만든 파일/`, iOS는 `Documents` 기준 `etl/영민이가 만든 파일/`.
    @Test func generatedFolderDetection() {
        #expect(GeneratedFilesLogic.isGeneratedPath("etl/영민이가 만든 파일/"))
        #expect(GeneratedFilesLogic.isGeneratedPath("/ETL/영민이가 만든 파일/요약.pdf"))
        #expect(GeneratedFilesLogic.isGeneratedPath("./etl/영민이가 만든 파일"))
        #expect(!GeneratedFilesLogic.isGeneratedPath("etl/"))
        #expect(!GeneratedFilesLogic.isGeneratedPath("etl/영민이가 만든 파일.pdf"))
        #expect(!GeneratedFilesLogic.isGeneratedPath("etl/영민이가 만든 파일들/"))
        #expect(!GeneratedFilesLogic.isGeneratedPath(nil))
    }
}

/// 이벤트 모으기(다른 테스트의 이벤트와 섞이지 않게 파일 이름으로 거른다).
private final class SavedFiles: @unchecked Sendable {
    private let lock = NSLock()
    private var files: [EtlEvents.SavedFile] = []
    func add(_ f: [EtlEvents.SavedFile]) { lock.withLock { files += f } }
    func named(_ prefix: String) -> [EtlEvents.SavedFile] { lock.withLock { files.filter { $0.file.lastPathComponent.contains(prefix) } } }
}

struct MakePdfTests {
    private func dirs() -> (workspace: URL, user: URL) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("pdf-\(UUID().uuidString)", isDirectory: true)
        return (root.appendingPathComponent("etl-files", isDirectory: true), root.appendingPathComponent("user", isDirectory: true))
    }

    private func tool(_ d: (workspace: URL, user: URL), _ generated: GeneratedFiles) -> MakePdfTool {
        MakePdfTool(workspace: d.workspace, userDir: d.user, folderLabel: "파일 앱 › 영민이 › etl", generated: generated,
                    now: { EtlLogic.parseInstant("2026-11-25T09:00:00+09:00")! },
                    relPath: { "etl-files/" + $0.path.replacingOccurrences(of: d.workspace.path + "/", with: "") })
    }

    private static let longMarkdown: String = {
        var md = "## 개요\n\n회로이론 기말고사 범위를 정리했어요. **중요한 부분**은 굵게, *참고*는 기울임으로 적었고 `KVL` 같은 용어는 코드로 적었어요. [eTL](https://myetl.snu.ac.kr)\n\n"
        for k in 1...60 {
            md += "### \(k). 절 제목 \(k)\n\n"
            md += "옴의 법칙 \\(V = IR\\)과 키르히호프 전압 법칙을 함께 쓴다. 전력은 $P = VI = I^2 R$ 이다. 이 문단은 쪽 나누기를 확인하려고 충분히 길게 적은 한국어 문장이다. English words mix in too.\n\n"
            md += "- 첫째 항목 \(k)\n- 둘째 항목\n  - 안쪽 항목\n1. 번호 하나\n1. 번호 둘\n\n"
            if k % 10 == 0 {
                md += "| 소자 | 전압 | 전류 |\n|---|---|---|\n| 저항 | $V=IR$ | 1 A |\n| 축전기 | `C dv/dt` | 2 mA |\n\n"
                md += "$$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n$$\n\n```\nlet v = i * r\n```\n\n> 시험에 자주 나와요\n\n---\n\n"
            }
        }
        return md
    }()

    @Test func makesValidMultiPagePdf() throws {
        let d = dirs()
        let generated = GeneratedFiles(InMemoryStore())
        let seen = SavedFiles()
        let sub = EtlEvents.subscribe { e in if case let .filesSaved(f, false) = e { seen.add(f) } }
        defer { sub.cancel() }

        let out = try tool(d, generated).call(JSONObject().put("title", "회로이론 요약").put("content_markdown", Self.longMarkdown))
        #expect(out.optString("name") == "회로이론 요약.pdf")
        #expect(out.optString("saved_to") == "파일 앱 › 영민이 › etl/영민이가 만든 파일/회로이론 요약.pdf")
        let pages = out.optInt("pages")
        #expect(pages >= 3)
        #expect(out.optLong("bytes") > 0 && out.optLong("bytes") <= PdfLayoutLogic.MAX_BYTES)
        let rel = out.optString("workspace_path")
        #expect(rel.hasPrefix("etl-files/made/") && rel.hasSuffix("-회로이론 요약.pdf"))
        #expect(out.optString("note") == "채팅에 파일 카드가 붙었어요. 사용자에게 저장 위치(saved_to)를 알려 주세요")
        #expect(!out.has("warning"))

        let userFile = d.user.appendingPathComponent("영민이가 만든 파일/회로이론 요약.pdf")
        let doc = try #require(PDFDocument(url: userFile))
        #expect(doc.pageCount == pages)
        let first = try #require(doc.page(at: 0))
        let box = first.bounds(for: .mediaBox)
        #expect(box.width == 595 && box.height == 842) // A4
        let text = first.string ?? ""
        #expect(text.contains("회로이론 요약"))
        #expect(text.contains("영민이가 만든 문서 · 2026년 11월 25일"))
        #expect(text.contains("V=IR") && text.contains("I²R")) // 수식은 유니코드 글
        let last = try #require(doc.page(at: pages - 1)?.string)
        #expect(last.contains("회로이론 요약 · \(pages) / \(pages)"))
        #expect((doc.string ?? "").contains("소자"))

        // 기록과 파일 카드
        #expect(generated.recorded() == ["etl/영민이가 만든 파일/회로이론 요약.pdf"])
        #expect(generated.isGenerated("etl/영민이가 만든 파일/회로이론 요약.pdf"))
        let cards = seen.named("회로이론 요약")
        #expect(cards.count == 1)
        #expect(cards.first?.mime == "application/pdf")
        #expect(cards.first?.shownPath == out.optString("saved_to"))
        #expect(cards.first?.phoneUri == userFile.absoluteString)
        #expect(cards.first?.referenceOnly == false)

        // 같은 이름이면 "이름 (1).pdf"
        let again = try tool(d, generated).call(JSONObject().put("title", "회로이론 요약").put("content_markdown", "짧은 본문").put("filename", "회로이론 요약.pdf"))
        #expect(again.optString("saved_to").hasSuffix("/회로이론 요약 (1).pdf"))
        #expect(again.optInt("pages") == 1)
        #expect(PDFDocument(url: d.user.appendingPathComponent("영민이가 만든 파일/회로이론 요약 (1).pdf"))?.pageCount == 1)
    }

    @Test func limits() throws {
        let d = dirs()
        let t = tool(d, GeneratedFiles(InMemoryStore()))
        #expect(throws: EtlException.self) { try t.call(JSONObject().put("title", " ").put("content_markdown", "  ")) }
        do {
            _ = try t.call(JSONObject().put("title", "긴 글").put("content_markdown", String(repeating: "가", count: PdfLayoutLogic.MAX_MARKDOWN_CHARS + 1)))
            Issue.record("길이 제한")
        } catch let e as EtlException {
            #expect(e.message == "내용이 너무 길어요(300000자 이내). 나눠서 만들어 주세요")
        }
        do {
            _ = try t.call(JSONObject().put("title", "많은 쪽").put("content_markdown", String(repeating: "줄\n\n", count: 12_000)))
            Issue.record("쪽 제한")
        } catch let e as EtlException {
            #expect(e.message.hasPrefix("PDF가 200쪽을 넘어요("))
        }
        // 실패하면 만들던 파일을 남기지 않는다.
        let made = (try? FileManager.default.contentsOfDirectory(atPath: d.workspace.appendingPathComponent("made").path)) ?? []
        #expect(made.isEmpty)
    }

    @Test func toolboxRunsMakePdfAndBoardTools() async throws {
        let names = ToolSpecs.all.map(\.name)
        for n in ["etl_boards", "etl_board_posts", "etl_board_post", "make_pdf"] {
            #expect(names.contains(n), "\(n)")
            #expect(!ToolSpecs.deferred.contains(n), "\(n)")
        }
        let spec = try #require(ToolSpecs.all.first { $0.name == "make_pdf" })
        #expect(spec.inputSchema["required"]?.array?.compactMap(\.string) == ["title", "content_markdown"])
        #expect(spec.description.contains("최대 200쪽."))

        let box = makeToolbox(handler: { req in
            if req.url?.path == "/api/v1/courses/7/tabs" { return (200, #"[{"id":"home","type":"internal","label":"홈"}]"#) }
            return nil
        })
        let pdf = parsed(await box.run(name: "make_pdf", argumentsJSON: ##"{"title":"요약","content_markdown":"# 하나\n\n본문"}"##).text)
        #expect(pdf.optInt("pages") == 1)
        #expect(pdf.optString("workspace_path").hasPrefix("etl-files/made/"))
        #expect(pdf.optString("saved_to") == "파일 앱 › 영민이 › etl/영민이가 만든 파일/요약.pdf")

        let empty = parsed(await box.run(name: "make_pdf", argumentsJSON: #"{"title":"","content_markdown":""}"#).text)
        #expect(empty.optString("error") == "title과 content_markdown을 주세요")

        let noBoard = parsed(await box.run(name: "etl_boards", argumentsJSON: #"{"course_id":7}"#).text)
        #expect(noBoard.optString("error") == BoardClient.NO_BOARD)
        let badId = parsed(await box.run(name: "etl_board_post", argumentsJSON: #"{"course_id":7,"board_id":1}"#).text)
        #expect(badId.optString("error") == "post_id 가 필요합니다(양의 정수)")
        let noToken = parsed(await makeToolbox(token: nil).run(name: "etl_board_posts", argumentsJSON: #"{"course_id":7,"board_id":1}"#).text)
        #expect(noToken.optString("error") == "eTL 토큰이 저장되어 있지 않습니다. 앱에서 토큰을 먼저 저장하세요.")
        // etl_download의 숨김 파일 확인: 게시판 인자가 없으면 거절
        await #expect { try await box.boardAttachmentPost(JSONObject().put("course_id", 7), 5) } throws: {
            ($0 as? EtlException)?.message == "숨겨진 파일입니다. 게시판 첨부라면 course_id, board_id, post_id를 함께 넘기세요. 아니면 eTL에서 확인해 주세요."
        }
    }
}
