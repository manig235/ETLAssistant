package kr.snu.etlassistant.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import kr.snu.etlassistant.etl.EtlLogic
import java.io.File

/*
 * "드라이브에 저장"(2026-11-24, 사용자 요청): OAuth·Drive API 없이 시스템 문서 선택기(Storage Access Framework,
 * ACTION_CREATE_DOCUMENT)로 사용자가 고른 곳(Google Drive 등)에 파일 사본을 쓴다. 앱은 Drive 서버와 직접 통신하지 않는다
 * (실제 업로드는 사용자가 고른 문서 제공 앱이 한다). 마지막으로 저장한 위치를 기억해 다음에 그 위치에서 시작한다.
 */

/** 순수 규칙(JVM 단위 테스트 대상). */
object SaveCopyLogic {
    /** Google Drive 앱의 문서 제공자. */
    const val DRIVE_AUTHORITY = "com.google.android.apps.docs.storage"

    fun label(driveAvailable: Boolean): String = if (driveAvailable) "드라이브에 저장" else "다른 곳에 저장"

    private val MIME_BY_EXT = mapOf(
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "doc" to "application/msword", "ppt" to "application/vnd.ms-powerpoint", "xls" to "application/vnd.ms-excel",
        "hwp" to "application/x-hwp", "hwpx" to "application/vnd.hancom.hwpx",
        "zip" to "application/zip", "txt" to "text/plain", "csv" to "text/csv", "md" to "text/markdown",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "mp3" to "audio/mpeg", "m4a" to "audio/mp4",
    )

    /** 선택기에 넘길 MIME. 알려 준 값이 비었거나 octet-stream이면 확장자로 고르고, 모르면 octet-stream. */
    fun mimeFor(name: String, mime: String?): String {
        val m = mime?.trim()?.lowercase().orEmpty()
        if (m.isNotEmpty() && m != "application/octet-stream" && m.contains('/') && !m.contains(' ')) return m
        return MIME_BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }

    /** 선택기에 채울 파일 이름(경로 구분자·제어문자 제거, 앱 공통 규칙). */
    fun fileName(name: String): String = EtlLogic.safeName(name, "파일")

    /** 이 크기 이상이면 "저장하는 중" 안내를 먼저 띄운다. */
    const val BIG_BYTES = 2L * 1024 * 1024
}

/** 문서 선택기: (파일 이름, MIME, 시작 위치) → 고른 문서 URI. */
class CreateDocumentContract : ActivityResultContract<CreateDocumentContract.Input, Uri?>() {
    data class Input(val name: String, val mime: String, val initial: Uri?)

    override fun createIntent(context: Context, input: Input): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)
            .apply { input.initial?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

object SaveCopy {
    private const val PREFS = "save_copy"

    /** Google Drive 앱(문서 제공자)이 있는가(매니페스트 `<queries>`에 제공자를 적어 둬야 보인다). */
    fun driveAvailable(context: Context): Boolean =
        runCatching { context.packageManager.resolveContentProvider(SaveCopyLogic.DRIVE_AUTHORITY, 0) != null }.getOrDefault(false)

    fun label(context: Context): String = SaveCopyLogic.label(driveAvailable(context))

    /** 지난번 저장 위치(폴더 문서 URI). 없으면 null(선택기 기본 위치). */
    fun initialUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 저장한 문서의 부모 폴더를 찾아 기억한다. 제공자가 경로 찾기를 지원하지 않으면 그 문서 URI를 그대로 둔다(선택기가 그 폴더를 연다). 실패하면 무시. */
    fun rememberLocation(context: Context, saved: Uri) {
        val parent = runCatching {
            val path = DocumentsContract.findDocumentPath(context.contentResolver, saved)?.path
            if (path != null && path.size >= 2) DocumentsContract.buildDocumentUri(saved.authority, path[path.size - 2]) else null
        }.getOrNull()
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", (parent ?: saved).toString()).apply()
        }
    }

    /**
     * [source] 파일(작업 폴더 사본이 있으면 그것, 없으면 폰 사본 content URI)을 [dest]에 그대로 복사한다. 쓴 바이트 수.
     * 원본이 없으면 [java.io.FileNotFoundException].
     */
    fun copy(context: Context, workspacePath: String?, phoneUri: String?, dest: Uri): Long {
        val resolver = context.contentResolver
        val input = workspacePath?.let { File(it) }?.takeIf { it.isFile }?.inputStream()
            ?: phoneUri?.let { runCatching { resolver.openInputStream(Uri.parse(it)) }.getOrNull() }
            ?: throw java.io.FileNotFoundException("원본이 없어요")
        input.use { inp ->
            // "wt"(덮어쓰기)를 지원하지 않는 제공자도 있어 그때는 기본 모드로 연다.
            val out = runCatching { resolver.openOutputStream(dest, "wt") }.getOrNull() ?: resolver.openOutputStream(dest)
                ?: throw java.io.IOException("저장할 곳을 열 수 없어요")
            out.use { return inp.copyTo(it) }
        }
    }
}
