import ChatKit
import ETLKit
import Foundation

// 2단계 도구 그룹: 파일 읽기: etl_read_file, etl_render_pages, etl_view_image.
// 이 파일만 고친다: 도구 정의는 `ToolSpecs.filesTools`, 실행은 `Toolbox.callFiles`(맡은 이름이 아니면 nil).
//
// Kotlin `EtlTools.readFile`·`renderPages`·`WorkspaceImage.view`. 파일 규칙·형식 해독은 ETLKit(`EtlFiles`, `HwpText`, `PdfPages`, `WorkspaceImage`).
// 읽는 곳은 언제나 작업 폴더(`etl-files/`) 안뿐이다(etl_download·etl_syllabus 첨부·사용자 첨부 `etl-files/uploads/`).

extension ToolSpecs {
    private static func filesTool(_ name: String, _ description: String, _ props: [(String, String, String)], required: [String]) -> ToolSpec {
        var o: [String: ChatJSON] = [:]
        for (n, type, desc) in props { o[n] = ["type": .string(type), "description": .string(desc)] }
        let schema: ChatJSON = ["type": "object", "properties": .object(o), "required": .array(required.map { .string($0) })]
        return ToolSpec(name: name, description: "\(description) \(untrusted)", inputSchema: schema)
    }

    /// 안드로이드 순서(etl_read_file, etl_view_image, etl_render_pages). 설명·스키마는 원문 그대로.
    static let filesTools: [ToolSpec] = [
        filesTool("etl_read_file", "etl_download로 받은 파일이나 사용자가 첨부한 파일(etl-files/ 안)의 내용을 읽는다. 텍스트·코드 파일과 docx·pptx·hwp·hwpx는 본문(글만)을, PDF는 쪽 수를 돌려준다(PDF 내용은 etl_render_pages로 본다).",
                  [("path", "string", "etl_download가 돌려준 workspace_path(예: etl-files/123-과제.pdf) 또는 첨부 경로(etl-files/uploads/…)"),
                   ("max_chars", "integer", "텍스트 최대 글자 수. 기본 20000, 최대 60000")],
                  required: ["path"]),
        filesTool(WorkspaceImage.NAME, "작업 폴더 etl-files/ 안의 PNG·JPEG·WebP 이미지 하나를 연다. etl_render_pages가 돌려준 rendered[].path나 첨부 이미지의 상대 경로를 쓴다. 앱 작업 폴더 밖의 파일은 읽을 수 없다. 원본은 최대 20MB·6400만 픽셀이고 큰 이미지는 줄여서 돌려준다. 사진 메타데이터는 제거한다. code mode에서는 반환 content 중 type=image 블록을 image(...)로 출력해야 볼 수 있다. base64는 text나 로그로 출력하지 않는다.",
                  [("path", "string", "etl-files/ 안의 상대 경로(예: etl-files/render/123-p1.png)")],
                  required: ["path"]),
        filesTool("etl_render_pages", "PDF의 쪽을 이미지(PNG)로 만든다. 돌려준 rendered[].path를 etl_view_image에 넘겨 내용(손글씨, 수식, 스캔본 포함)을 확인한다. 한 번에 최대 \(EtlFiles.MAX_RENDER_PAGES)쪽.",
                  [("path", "string", "etl_download가 돌려준 workspace_path 또는 첨부 경로(etl-files/uploads/…)"),
                   ("pages", "string", "쪽 번호(1부터). 예: \"1-3,5\". 비우면 앞쪽부터")],
                  required: ["path"]),
    ]
}

extension Toolbox {
    func callFiles(_ name: String, _ args: JSONObject) async throws -> Any? {
        switch name {
        case "etl_read_file": return try readFile(args.optString("path", ""), args.optInt("max_chars", EtlFiles.DEFAULT_TEXT_CHARS))
        case "etl_render_pages":
            let spec = args.optString("pages", "")
            return try renderPages(args.optString("path", ""), spec.isEmpty ? nil : spec)
        case WorkspaceImage.NAME: return viewImage(args.optString("path", ""))
        default: return nil
        }
    }

    // MARK: 파일 읽기

    func readFile(_ path: String, _ maxChars: Int) throws -> JSONObject {
        let f = try EtlFiles.resolveInside(workspace, path)
        let name = f.lastPathComponent
        let size = ((try? FileManager.default.attributesOfItem(atPath: f.path)[.size]) as? NSNumber)?.int64Value ?? 0
        let o = JSONObject().put("path", EtlFiles.relPath(workspace, f)).put("size", size)
        let limit = min(max(maxChars, 1), EtlFiles.MAX_TEXT_CHARS)
        if EtlFiles.isTextLike(name) {
            let r = try EtlFiles.readTextPrefix(f, limit: limit)
            return o.put("kind", "text").put("truncated", r.truncated).put("text", r.text)
        }
        if EtlFiles.isOoxml(name) {
            let text = try EtlFiles.ooxmlText(f)
            return o.put("kind", "text").put("truncated", text.utf16.count > limit).put("text", EtlFiles.take16(text, limit))
        }
        if HwpText.isHwp(name) {
            let r = try HwpText.read(f)
            o.put("kind", "text").put("truncated", r.text.utf16.count > limit).put("text", EtlFiles.take16(r.text, limit))
            if r.previewOnly { o.put("preview_only", true).put("note", r.note) }
            return o
        }
        if EtlFiles.isPdf(name) {
            return o.put("kind", "pdf").put("pages", try PdfPages.pageCount(f))
                .put("hint", "내용은 etl_render_pages로 쪽 이미지를 만들어 확인하세요")
        }
        return o.put("kind", "binary").put("hint", "이 형식은 내용을 읽을 수 없습니다")
    }

    func renderPages(_ path: String, _ spec: String?) throws -> JSONObject {
        let f = try EtlFiles.resolveInside(workspace, path)
        let name = f.lastPathComponent
        if !EtlFiles.isPdf(name) { throw EtlException("PDF만 쪽 이미지를 만들 수 있습니다") }
        let total = try PdfPages.pageCount(f)
        let pages = EtlFiles.parsePages(spec, total)
        let outDir = workspace.appendingPathComponent(EtlFiles.RENDER_DIR, isDirectory: true)
        let head = name.firstIndex(of: "-").map { String(name[..<$0]) } ?? name
        let base = (!head.isEmpty && head.allSatisfy { $0.isASCII && $0.isNumber }) ? head : Self.javaHashHex(name)
        let rendered = try PdfPages.render(f, pages, outDir, base)
        // 강의계획서 첨부는 비서가 정보를 얻으려고만 보는 파일이라 채팅에 쪽 미리보기를 띄우지 않는다.
        if !EtlFiles.isPrivateRead(workspace, f) {
            let shown = name.firstIndex(of: "-").map { String(name[name.index(after: $0)...]) } ?? name
            EtlEvents.pagesRendered(rendered.map { r in
                let bytes = ((try? FileManager.default.attributesOfItem(atPath: r.file.path)[.size]) as? NSNumber)?.int64Value ?? 0
                return EtlEvents.SavedFile(name: "\(shown) \(r.page)쪽", mime: "image/png", size: bytes, file: r.file, phoneUri: nil, shownPath: nil)
            })
        }
        let arr = JSONArray()
        for r in rendered {
            arr.put(JSONObject().put("page", r.page).put("path", EtlFiles.relPath(workspace, r.file)).put("width", r.width).put("height", r.height))
        }
        return JSONObject().put("path", EtlFiles.relPath(workspace, f)).put("pages_total", total).put("rendered", arr)
            .put("note", "etl_view_image에 rendered[].path를 넘겨 확인하세요. code mode에서는 결과 content의 image 블록을 image(...)로 출력하세요. 더 보려면 pages를 바꿔 다시 호출하세요")
    }

    /// 이미지를 다시 인코딩해 [ToolOutput.images]로 돌려준다. 오류는 다른 도구처럼 `{"error": …}`(고정 문구).
    func viewImage(_ path: String, codec: WorkspaceImage.Codec = WorkspaceImage.ImageIOCodec()) -> ToolOutput {
        let r = WorkspaceImage.view(workspace, path, codec: codec)
        if r.isError { return ToolOutput(text: JSONObject().put("error", r.text).toString()) }
        return ToolOutput(text: r.text, images: r.images.map { InputImage(data: $0.data, mime: $0.mimeType) })
    }

    /// Java `Integer.toHexString(s.hashCode())`(UTF-16 단위, 32비트 넘침).
    static func javaHashHex(_ s: String) -> String {
        var h: Int32 = 0
        for u in s.utf16 { h = h &* 31 &+ Int32(u) }
        return String(UInt32(bitPattern: h), radix: 16)
    }
}
