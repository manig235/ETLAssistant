package kr.snu.etlassistant.files

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kr.snu.etlassistant.etl.EtlException
import java.io.File

/** AI가 만든 파일 판정의 순수 부분(JVM 단위 테스트 대상). */
object GeneratedFilesLogic {
    /** 폰 `Download/etl/` 아래 AI가 만든 파일을 두는 하위 폴더. */
    const val SUBDIR = "영민이가 만든 파일"
    /** MediaStore RELATIVE_PATH 형식(끝에 `/`). */
    const val RELATIVE_PATH = "Download/etl/$SUBDIR/"

    /**
     * MediaStore RELATIVE_PATH(`Download/etl/영민이가 만든 파일/`)나 표시 경로(`Download/etl/영민이가 만든 파일/요약.pdf`)가
     * AI가 만든 파일 폴더 안인가. 앞의 `/`·`./`, 대소문자(`download/`)와 하위 폴더는 허용한다.
     */
    fun isGeneratedPath(relativePath: String?): Boolean {
        val p = (relativePath ?: return false).trim().replace('\\', '/').removePrefix("./").trimStart('/')
        val withSlash = if (p.endsWith("/")) p else "$p/"
        return withSlash.startsWith(RELATIVE_PATH, ignoreCase = true)
    }
}

/**
 * AI(make_pdf)가 만든 파일. 폰의 `Download/etl/영민이가 만든 파일/`에 저장하고(MediaStore, 앱을 지워도 남음),
 * 만든 파일의 URI를 앱 안에 기록해 둔다. 받은 eTL 파일 자동 정리는 [isGenerated]인 파일을 지우지 않는다.
 */
object GeneratedFiles {
    private const val PREFS = "generated_files"
    private const val KEY = "uris"
    private const val MAX_ENTRIES = 1000

    class Saved(val uri: String?, val shownPath: String)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [uri](MediaStore content URI 또는 낮은 버전의 파일 경로)를 기록했거나 [relativePath]가 AI 파일 폴더 안이면 true. */
    fun isGenerated(context: Context, uri: String?, relativePath: String? = null): Boolean =
        GeneratedFilesLogic.isGeneratedPath(relativePath) ||
            (uri != null && runCatching { prefs(context).getStringSet(KEY, emptySet())!!.contains(uri) }.getOrDefault(false))

    @Synchronized private fun register(context: Context, uri: String) {
        val p = prefs(context)
        val set = LinkedHashSet(p.getStringSet(KEY, emptySet()).orEmpty())
        set += uri
        p.edit().putStringSet(KEY, if (set.size > MAX_ENTRIES) set.toList().takeLast(MAX_ENTRIES).toSet() else set).apply()
    }

    /**
     * [src]를 [name]으로 폰에 저장한다. 같은 이름이 있으면 MediaStore가 `이름 (1).pdf`로 바꾸므로, 마친 뒤의 이름을 표시 경로로 쓴다.
     * 실패하면 [EtlException](반쯤 만든 항목은 지운다).
     */
    fun save(context: Context, src: File, name: String, mime: String): Saved {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/etl/${GeneratedFilesLogic.SUBDIR}")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw EtlException("다운로드 폴더에 파일을 만들 수 없습니다")
            try {
                (resolver.openOutputStream(uri) ?: throw EtlException("다운로드 파일을 열 수 없습니다")).use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw if (e is EtlException) e else EtlException("폰에 저장하지 못했습니다(${e.javaClass.simpleName})")
            }
            register(context, uri.toString())
            val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: name
            return Saved(uri.toString(), GeneratedFilesLogic.RELATIVE_PATH + finalName)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "etl/${GeneratedFilesLogic.SUBDIR}").apply { mkdirs() }
        var f = File(dir, name)
        var k = 1
        while (f.exists() && k < 1000) {
            f = File(dir, name.substringBeforeLast('.') + " ($k)." + name.substringAfterLast('.', "pdf"))
            k++
        }
        src.copyTo(f)
        register(context, f.path)
        return Saved(null, GeneratedFilesLogic.RELATIVE_PATH + f.name)
    }
}
