import ChatKit
import CoreGraphics
import CoreText
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import AssistantKit
import ETLKit

/// 파일 읽기 도구(etl_read_file, etl_render_pages, etl_view_image)를 도구 상자로 끝까지 돌린다(Kotlin `EtlTools.readFile`·`renderPages`·`WorkspaceImage.view`).
@Suite struct FilesToolTests {
    let box = makeToolbox()
    var ws: URL { box.workspace }
    /// 이 테스트 상자의 고유 폴더 이름(다른 테스트의 이벤트를 거른다).
    var tag: String { box.workspace.deletingLastPathComponent().lastPathComponent }

    init() { try? FileManager.default.createDirectory(at: box.workspace, withIntermediateDirectories: true) }

    @discardableResult
    private func put(_ rel: String, _ data: Data) -> URL {
        let url = ws.appendingPathComponent(rel)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url)
        return url
    }

    private func run(_ name: String, _ args: String) async -> ToolOutput { await box.run(name: name, argumentsJSON: args) }

    private func pdf(_ url: URL, pages: Int) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        var box = CGRect(x: 0, y: 0, width: 300, height: 200)
        let ctx = CGContext(url as CFURL, mediaBox: &box, nil)!
        for i in 1...pages {
            ctx.beginPDFPage(nil)
            let font = CTFontCreateWithName("Helvetica" as CFString, 18, nil)
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: "Page \(i)", attributes: [kCTFontAttributeName as NSAttributedString.Key: font]))
            ctx.textPosition = CGPoint(x: 20, y: 100)
            CTLineDraw(line, ctx)
            ctx.endPDFPage()
        }
        ctx.closePDF()
    }

    @Test func toolsAreRegisteredWithAndroidSchemas() throws {
        for n in ["etl_read_file", "etl_render_pages", "etl_view_image"] {
            #expect(ToolSpecs.names.contains(n))
            #expect(!ToolSpecs.deferred.contains(n))
            let spec = try #require(ToolSpecs.all.first { $0.name == n })
            #expect(spec.readOnly)
            #expect(spec.description.hasSuffix(ToolSpecs.untrusted))
            #expect(spec.inputSchema["required"]?.array?.compactMap(\.string) == ["path"])
        }
        let read = ToolSpecs.all.first { $0.name == "etl_read_file" }!
        #expect(read.inputSchema["properties"]?["max_chars"]?["type"]?.string == "integer")
        let render = ToolSpecs.all.first { $0.name == "etl_render_pages" }!
        #expect(render.description.contains("한 번에 최대 6쪽."))
        #expect(render.inputSchema["properties"]?["pages"]?["type"]?.string == "string")
    }

    @Test func readsTextWithLimitAndKinds() async throws {
        put("1-a.txt", Data("가나다라마".utf8))
        let t = parsed(await run("etl_read_file", #"{"path":"etl-files/1-a.txt","max_chars":3}"#).text)
        #expect(t.optString("path") == "etl-files/1-a.txt")
        #expect(t.optLong("size") == 15)
        #expect(t.optString("kind") == "text")
        #expect(t.optBoolean("truncated") == true)
        #expect(t.optString("text") == "가나다")
        let all = parsed(await run("etl_read_file", #"{"path":"etl-files/1-a.txt","max_chars":999999}"#).text)
        #expect(all.optBoolean("truncated") == false && all.optString("text") == "가나다라마")

        let docx = ZipWriter.make([("word/document.xml", Data(#"<w:document><w:body><w:p><w:r><w:t>중간고사 10월 20일</w:t></w:r></w:p></w:body></w:document>"#.utf8))])
        put("2-plan.docx", docx)
        let d = parsed(await run("etl_read_file", #"{"path":"etl-files/2-plan.docx"}"#).text)
        #expect(d.optString("kind") == "text" && d.optString("text") == "중간고사 10월 20일" && d.optBoolean("truncated", true) == false)

        let hwpx = ZipWriter.make([("Contents/section0.xml", Data(#"<hs:sec><hp:p><hp:run><hp:t>기말고사</hp:t></hp:run></hp:p></hs:sec>"#.utf8))])
        put("syllabus/syllabus-2026-x-001-ko-plan.hwpx", hwpx)
        let h = parsed(await run("etl_read_file", #"{"path":"etl-files/syllabus/syllabus-2026-x-001-ko-plan.hwpx"}"#).text)
        #expect(h.optString("text") == "기말고사" && !h.has("preview_only"))

        pdf(ws.appendingPathComponent("3-slides.pdf"), pages: 4)
        let p = parsed(await run("etl_read_file", #"{"path":"etl-files/3-slides.pdf"}"#).text)
        #expect(p.optString("kind") == "pdf" && p.optInt("pages") == 4)
        #expect(p.optString("hint") == "내용은 etl_render_pages로 쪽 이미지를 만들어 확인하세요")

        put("4-a.zip", Data([1, 2, 3]))
        let b = parsed(await run("etl_read_file", #"{"path":"etl-files/4-a.zip"}"#).text)
        #expect(b.optString("kind") == "binary" && b.optString("hint") == "이 형식은 내용을 읽을 수 없습니다")
    }

    @Test func readFileErrorsUseAndroidMessages() async {
        put("ok.txt", Data("x".utf8))
        #expect(parsed(await run("etl_read_file", #"{"path":"etl-files/../../etc/passwd"}"#).text).optString("error") == "etl-files/ 안의 파일만 열 수 있습니다")
        #expect(parsed(await run("etl_read_file", #"{"path":"etl-files/nope.txt"}"#).text).optString("error") == "파일이 없습니다: etl-files/nope.txt")
        #expect(parsed(await run("etl_read_file", "{}").text).optString("error") == "허용되지 않은 경로입니다")
        put("bad.hwp", Data(repeating: 3, count: 100))
        #expect(parsed(await run("etl_read_file", #"{"path":"etl-files/bad.hwp"}"#).text).optString("error") == "한글 문서 형식이 아닙니다")
        #expect(parsed(await run("etl_render_pages", #"{"path":"etl-files/ok.txt"}"#).text).optString("error") == "PDF만 쪽 이미지를 만들 수 있습니다")
    }

    @Test func rendersPagesPostsPreviewAndViewsImage() async throws {
        let events = EventLog()
        let sub = EtlEvents.subscribe { e in if case .filesSaved(let files, true) = e { events.add(files) } }
        defer { sub.cancel() }
        let f = ws.appendingPathComponent("123-과제.pdf")
        pdf(f, pages: 8)
        let out = parsed(await run("etl_render_pages", #"{"path":"etl-files/123-과제.pdf","pages":"2-3,8"}"#).text)
        #expect(out.optString("path") == "etl-files/123-과제.pdf")
        #expect(out.optInt("pages_total") == 8)
        let rendered = try #require(out.optJSONArray("rendered")).objects()
        #expect(rendered.map { $0.optInt("page") } == [2, 3, 8])
        #expect(rendered.map { $0.optString("path") } == ["etl-files/render/123-p2.png", "etl-files/render/123-p3.png", "etl-files/render/123-p8.png"])
        #expect(rendered.allSatisfy { $0.optInt("width") == 600 && $0.optInt("height") == 400 })
        #expect(out.optString("note").hasPrefix("etl_view_image에 rendered[].path를 넘겨 확인하세요."))
        let mine = events.files.filter { $0.file.path.contains(tag) }
        #expect(mine.map(\.name) == ["과제.pdf 2쪽", "과제.pdf 3쪽", "과제.pdf 8쪽"])
        #expect(mine.allSatisfy { $0.mime == "image/png" && $0.size > 0 && $0.phoneUri == nil && !$0.referenceOnly })

        // 기본: 앞쪽부터 6쪽
        let first = parsed(await run("etl_render_pages", #"{"path":"etl-files/123-과제.pdf"}"#).text)
        #expect(first.optJSONArray("rendered")?.length() == 6)

        let image = await run("etl_view_image", #"{"path":"etl-files/render/123-p2.png"}"#)
        #expect(image.text == "이미지 600×400. 원본 메타데이터를 제외하고 다시 인코딩했어요.")
        #expect(image.images.count == 1)
        #expect(image.images.first?.mime == "image/jpeg")
        #expect(image.images.first?.data.prefix(2) == Data([0xFF, 0xD8]))
        // Responses API 출력: input_text + input_image
        let parts = image.outputJSON.array ?? []
        #expect(parts.count == 2)
        #expect(parts.last?["type"]?.string == "input_image")
    }

    @Test func syllabusPdfAndUnnamedFilesRenderWithoutPreview() async throws {
        let events = EventLog()
        let sub = EtlEvents.subscribe { e in if case .filesSaved(let files, true) = e { events.add(files) } }
        defer { sub.cancel() }
        pdf(ws.appendingPathComponent("syllabus/syllabus-2026-x-001-ko-plan.pdf"), pages: 1)
        let out = parsed(await run("etl_render_pages", #"{"path":"etl-files/syllabus/syllabus-2026-x-001-ko-plan.pdf"}"#).text)
        let path = try #require(out.optJSONArray("rendered")?.objects().first?.optString("path"))
        // 이름이 숫자 ID로 시작하지 않으면 Java hashCode 16진수가 이름이 된다.
        #expect(path == "etl-files/render/\(Toolbox.javaHashHex("syllabus-2026-x-001-ko-plan.pdf"))-p1.png")
        #expect(events.files.filter { $0.file.path.contains(tag) }.isEmpty)
        #expect(Toolbox.javaHashHex("abc") == "17862")
        #expect(Toolbox.javaHashHex("syllabus-plan.pdf") == String(UInt32(bitPattern: Int32(truncatingIfNeeded: javaHash("syllabus-plan.pdf"))), radix: 16))
    }

    @Test func viewImageErrorsAreFixedJsonWithoutImages() async {
        put("x.png", Data("not an image".utf8))
        let bad = await run("etl_view_image", #"{"path":"etl-files/x.png"}"#)
        #expect(bad.images.isEmpty)
        #expect(parsed(bad.text).optString("error") == "PNG·JPEG·WebP 이미지만 열 수 있어요. PDF는 etl_render_pages로 쪽을 먼저 만들어 주세요.")
        let outside = await run("etl_view_image", #"{"path":"/etc/hosts"}"#)
        #expect(outside.images.isEmpty)
        #expect(parsed(outside.text).optString("error") == "etl-files/ 안의 이미지 파일만 열 수 있어요. etl_render_pages나 첨부가 준 상대 경로를 사용해 주세요.")
    }
}

/// 참고 구현: Java `String.hashCode`(64비트로 계산 후 32비트로 자름).
private func javaHash(_ s: String) -> Int64 {
    var h: Int64 = 0
    for u in s.utf16 { h = (h &* 31 &+ Int64(u)) & 0xFFFF_FFFF }
    return h
}

private final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var list: [EtlEvents.SavedFile] = []
    func add(_ f: [EtlEvents.SavedFile]) { lock.withLock { list += f } }
    var files: [EtlEvents.SavedFile] { lock.withLock { list } }
}
