import Foundation
import ChatKit
import ETLKit

/// make_pdf 도구(Kotlin `pdf/MakePdfTool.kt`): 비서가 쓴 마크다운을 기기 안에서 PDF로 만들어 사용자 폴더
/// `Documents/etl/영민이가 만든 파일/`(파일 앱)에 저장하고 채팅에 파일 카드로 붙인다([EtlEvents.fileSaved]).
/// eTL에는 아무것도 보내지 않는다(폰 안 쓰기). 사용자가 요청한 결과물이라 파일 카드만 보이고 알림 줄(notice)은 남기지 않는다.
final class MakePdfTool {
    static let NAME = "make_pdf"
    static let MADE_DIR = "made"
    static let MIME = "application/pdf"

    /// 도구 정의(이름·설명·스키마는 안드로이드와 같다).
    /// readOnly: 안드로이드 MCP 힌트는 readOnlyHint=false지만 iOS `ToolSpec.readOnly`는 `ToolSpecs.writeTools`(승인 필요 도구)와
    /// 묶여 있고 make_pdf는 승인 없이 쓰는 도구라 true로 둔다. Responses API 함수 도구에는 annotations가 실리지 않는다.
    static var spec: ToolSpec {
        let p: (String) -> ChatJSON = { ["type": "string", "description": .string($0)] }
        let schema: ChatJSON = [
            "type": "object",
            "properties": [
                "title": p("문서 제목(첫 쪽 맨 위와 쪽 아래에 표시, \(PdfLayoutLogic.MAX_TITLE_CHARS)자 이내)"),
                "content_markdown": p("본문 마크다운. 제목은 title에 주고 본문에서 반복하지 않는다"),
                "filename": p("저장할 파일 이름(선택). 없으면 \"{title}.pdf\""),
            ],
            "required": ["title", "content_markdown"],
        ]
        let description = "마크다운을 A4 PDF로 만들어 폰의 Download/etl/\(GeneratedFilesLogic.SUBDIR)/에 저장하고 채팅에 파일 카드로 보여 준다(기기 안에서 그림, eTL에는 아무것도 보내지 않음). " +
            "사용자가 \"PDF로 만들어줘\"처럼 직접 요청했을 때만 쓴다. 지원: # ## ### 제목, 문단, -/1. 목록(한 단계 들여쓰기), **굵게**, *기울임*, `코드`, ``` 코드 블록, 표(| 머리 | 와 구분 줄), --- 가로줄, > 인용, 수식 \\( \\) $ $ \\[ \\] $$ $$. " +
            "최대 \(PdfLayoutLogic.MAX_PAGES)쪽. \(ToolSpecs.untrusted)"
        return ToolSpec(name: NAME, description: description, inputSchema: schema, readOnly: true)
    }

    private let workspace: URL
    private let userDir: URL
    private let folderLabel: String
    private let generated: GeneratedFiles
    private let now: () -> Date
    private let relPath: (URL) -> String

    /// - Parameters:
    ///   - workspace: 비서용 작업 폴더(`etl-files`). PDF는 `etl-files/made/{ms}-{이름}`에 먼저 만든다.
    ///   - userDir: 사용자 폴더(`Documents/etl`). 사본은 그 아래 `영민이가 만든 파일/`.
    ///   - folderLabel: `saved_to`에 쓰는 사용자 폴더 표시(예: "파일 앱 › 영민이 › etl").
    init(workspace: URL, userDir: URL, folderLabel: String, generated: GeneratedFiles = .shared,
         now: @escaping () -> Date = { Date() }, relPath: @escaping (URL) -> String) {
        self.workspace = workspace
        self.userDir = userDir
        self.folderLabel = folderLabel
        self.generated = generated
        self.now = now
        self.relPath = relPath
    }

    func call(_ args: JSONObject) throws -> JSONObject {
        let title = args.optString("title", "").kt.take16(PdfLayoutLogic.MAX_TITLE_CHARS)
        let markdown = args.optString("content_markdown", "")
        if title.isEmpty && markdown.isBlankK { throw EtlException("title과 content_markdown을 주세요") }
        if markdown.utf16.count > PdfLayoutLogic.MAX_MARKDOWN_CHARS {
            throw EtlException("내용이 너무 길어요(\(PdfLayoutLogic.MAX_MARKDOWN_CHARS)자 이내). 나눠서 만들어 주세요")
        }
        let name = PdfLayoutLogic.fileName(title, args.isNull("filename") ? nil : args.optString("filename"))

        let fm = FileManager.default
        let dir = workspace.appendingPathComponent(MakePdfTool.MADE_DIR, isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let at = now()
        let stored = dir.appendingPathComponent(EtlDownloads.fitName("\(at.epochMilli)-\(name)"))
        let today = DayDate(at)
        let made: PdfMaker.Made
        do {
            made = try PdfMaker(title, "영민이가 만든 문서 · \(today.year)년 \(today.month)월 \(today.day)일")
                .write(PdfMarkdown.parse(markdown), to: stored)
        } catch let e as EtlException {
            try? fm.removeItem(at: stored)
            throw e
        } catch {
            try? fm.removeItem(at: stored)
            throw EtlException("PDF를 만들지 못했습니다(\(String(describing: type(of: error))))")
        }
        let bytes = ((try? fm.attributesOfItem(atPath: stored.path))?[.size] as? NSNumber)?.int64Value ?? 0
        if bytes > PdfLayoutLogic.MAX_BYTES {
            try? fm.removeItem(at: stored)
            throw EtlException("PDF가 20MB를 넘어요. 내용을 나눠서 만들어 주세요")
        }
        var saved: GeneratedFiles.Saved?
        var warning: String?
        do {
            saved = try generated.save(stored, name, userDir: userDir, folderLabel: folderLabel)
        } catch let e as EtlException {
            warning = "폰에 저장하지 못했어요(\(e.message)). 채팅의 파일 카드로는 열 수 있어요"
        }
        // 채팅 화면에 파일 카드로 보여 준다(앱 안에서만 전달하고, 모델에게 주는 결과에는 파일 URL을 넣지 않는다).
        EtlEvents.fileSaved(EtlEvents.SavedFile(name: saved?.file.lastPathComponent ?? name, mime: MakePdfTool.MIME, size: bytes, file: stored,
                                                phoneUri: saved?.file.absoluteString, shownPath: saved?.shownPath))
        let out = JSONObject()
            .put("name", name)
            .put("saved_to", saved?.shownPath ?? JSONObject.NULL)
            .put("pages", made.pages)
            .put("bytes", bytes)
            .put("workspace_path", relPath(stored))
            .put("note", "채팅에 파일 카드가 붙었어요. 사용자에게 저장 위치(saved_to)를 알려 주세요")
        if let warning { out.put("warning", warning) }
        return out
    }
}
