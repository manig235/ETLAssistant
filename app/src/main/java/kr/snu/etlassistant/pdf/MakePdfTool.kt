package kr.snu.etlassistant.pdf

import android.content.Context
import kr.snu.etlassistant.etl.EtlEvents
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlFiles
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.AiWriteApproval
import kr.snu.etlassistant.etl.WriteScope
import kr.snu.etlassistant.etl.WriteKind
import kr.snu.etlassistant.etl.WritePolicySettings
import kr.snu.etlassistant.files.GeneratedFiles
import kr.snu.etlassistant.files.GeneratedFilesLogic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * make_pdf 도구: 비서가 쓴 마크다운을 기기 안에서 PDF로 만들어 폰 `Download/etl/영민이가 만든 파일/`에 저장하고
 * 채팅에 파일 카드와 처리 기록을 붙인다. eTL에는 아무것도 보내지 않는다(폰 안 쓰기).
 */
class MakePdfTool(private val context: Context, private val workspace: File) {

    companion object {
        const val NAME = "make_pdf"
        const val MADE_DIR = "made"

        fun spec(untrusted: String): JSONObject = JSONObject()
            .put("name", NAME)
            .put("description", "마크다운을 A4 PDF로 만들어 폰의 Download/etl/${GeneratedFilesLogic.SUBDIR}/에 저장하고 채팅에 파일 카드로 보여 준다(기기 안에서 그림, eTL에는 아무것도 보내지 않음). " +
                "사용자가 \"PDF로 만들어줘\"처럼 직접 요청했을 때만 쓴다. 지원: # ## ### 제목, 문단, -/1. 목록(한 단계 들여쓰기), **굵게**, *기울임*, `코드`, ``` 코드 블록, 표(| 머리 | 와 구분 줄), --- 가로줄, > 인용, 수식 \\( \\) \$ \$ \\[ \\] \$\$ \$\$. " +
                "최대 ${PdfLayoutLogic.MAX_PAGES}쪽. $untrusted")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string").put("description", "문서 제목(첫 쪽 맨 위와 쪽 아래에 표시, ${PdfLayoutLogic.MAX_TITLE_CHARS}자 이내)"))
                .put("content_markdown", JSONObject().put("type", "string").put("description", "본문 마크다운. 제목은 title에 주고 본문에서 반복하지 않는다"))
                .put("filename", JSONObject().put("type", "string").put("description", "저장할 파일 이름(선택). 없으면 \"{title}.pdf\"")))
                .put("required", JSONArray(listOf("title", "content_markdown"))))
            .put("annotations", JSONObject().put("readOnlyHint", false).put("destructiveHint", false).put("openWorldHint", false))
    }

    fun call(args: JSONObject): JSONObject {
        val title = args.optString("title", "").trim().take(PdfLayoutLogic.MAX_TITLE_CHARS)
        val markdown = args.optString("content_markdown", "")
        if (title.isEmpty() && markdown.isBlank()) throw EtlException("title과 content_markdown을 주세요")
        if (markdown.length > PdfLayoutLogic.MAX_MARKDOWN_CHARS)
            throw EtlException("내용이 너무 길어요(${PdfLayoutLogic.MAX_MARKDOWN_CHARS}자 이내). 나눠서 만들어 주세요")
        val name = PdfLayoutLogic.fileName(title, if (args.isNull("filename")) null else args.optString("filename"))
        WritePolicySettings(context).requireEnabled(WriteScope.FILES)
        return create(title, markdown, name)
    }

    private fun create(title: String, markdown: String, name: String): JSONObject {
        // Rendering may take time. Prepare privately, then publish through the write policy. Files are a direct scope
        // (WriteScope.FILES.reviewed = false): no dialog or foreground chat, only "off" in settings refuses (2026-11-28).
        val stored = File.createTempFile("assistant-pdf-", ".pdf", context.cacheDir)
        val today = LocalDate.now(EtlLogic.KST)
        val made = try {
            stored.outputStream().use { out ->
                PdfMaker(title, "영민이가 만든 문서 · ${today.year}년 ${today.monthValue}월 ${today.dayOfMonth}일")
                    .write(PdfMarkdown.parse(markdown), out)
            }
        } catch (e: EtlException) {
            stored.delete()
            throw e
        } catch (e: Exception) {
            stored.delete()
            throw EtlException("PDF를 만들지 못했습니다(${e.javaClass.simpleName})")
        } catch (e: OutOfMemoryError) {
            stored.delete()
            throw EtlException("PDF가 너무 커서 만들지 못했습니다. 내용을 나눠 주세요")
        }
        val bytes = stored.length()
        if (bytes > PdfLayoutLogic.MAX_BYTES) {
            stored.delete()
            throw EtlException("PDF가 20MB를 넘어요. 내용을 나눠서 만들어 주세요")
        }
        return try {
            AiWriteApproval.execute(context, WriteScope.FILES, WriteKind.ADD, "PDF 만들기",
                "파일: $name\n제목: $title\n${made.pages}쪽 · 본문 ${markdown.length}자\n\n${markdown.take(300)}") {
                val dir = File(workspace, "${EtlFiles.DIR}/$MADE_DIR").apply { mkdirs() }
                val published = File.createTempFile("pdf-", "-${EtlFiles.fitName(name)}", dir)
                try {
                    stored.copyTo(published, overwrite = true)
                    publish(published, name, made.pages, bytes)
                } catch (e: Exception) {
                    published.delete()
                    throw e
                }
            }
        } finally { stored.delete() }
    }

    private fun publish(stored: File, name: String, pages: Int, bytes: Long): JSONObject {
        var saved: GeneratedFiles.Saved? = null
        var warning: String? = null
        try {
            saved = GeneratedFiles.save(context, stored, name, "application/pdf")
        } catch (e: EtlException) {
            warning = "폰에 저장하지 못했어요(${e.message}). 채팅의 파일 카드로는 열 수 있어요"
        }
        EtlEvents.fileSaved(EtlEvents.SavedFile(saved?.shownPath?.substringAfterLast('/') ?: name, "application/pdf", bytes, stored, saved?.uri, saved?.shownPath))
        EtlEvents.notice("📄 PDF를 만들었어요: $name")
        return JSONObject()
            .put("name", name)
            .put("saved_to", saved?.shownPath ?: JSONObject.NULL)
            .put("pages", pages)
            .put("bytes", bytes)
            .put("workspace_path", EtlFiles.relPath(workspace, stored))
            .put("note", "채팅에 파일 카드가 붙었어요. 사용자에게 저장 위치(saved_to)를 알려 주세요")
            .apply { if (warning != null) put("warning", warning) }
    }
}
