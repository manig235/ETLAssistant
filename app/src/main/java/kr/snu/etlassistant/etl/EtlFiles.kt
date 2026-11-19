package kr.snu.etlassistant.etl

import java.io.File
import java.io.IOException

/**
 * 비서(Codex)가 읽을 수 있게 앱 전용 작업 폴더(`workspace/etl-files/`)에 두는 파일들의 규칙. 안드로이드 API에 의존하지 않는다.
 *
 * 폰의 `Download/etl/`은 사용자용 사본이다. 비서의 작업 폴더는 앱 전용 저장소라 다른 앱에서는 보이지 않는다.
 */
object EtlFiles {
    const val DIR = "etl-files"
    const val RENDER_DIR = "render"
    const val MAX_RENDER_PAGES = 6
    const val DEFAULT_TEXT_CHARS = 20_000
    const val MAX_TEXT_CHARS = 60_000
    /** 작업 폴더에 둔 파일은 이 기간이 지나면 정리한다. */
    const val KEEP_DAYS = 14

    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "tex", "yaml", "yml", "ini",
        "c", "h", "cpp", "hpp", "cc", "java", "kt", "py", "m", "js", "ts", "sh", "sql", "r", "v", "sv", "asm",
    )

    fun isTextLike(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in TEXT_EXT

    fun isPdf(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "pdf"

    /**
     * 작업 폴더에 저장할 이름: `{파일ID}-{정리된 이름}`. 같은 파일(ID)이면 이름이 같아 반복해서 받아도 쌓이지 않는다.
     * 리눅스 파일 이름 한계(255바이트)를 넘지 않도록 **바이트 기준**으로 줄이고 확장자는 지킨다(한글은 글자당 3바이트).
     */
    fun storedName(fileId: Long, safeName: String, maxBytes: Int = 200): String {
        val full = "$fileId-$safeName"
        if (full.toByteArray(Charsets.UTF_8).size <= maxBytes) return full
        val dot = full.lastIndexOf('.')
        val ext = if (dot > 0 && full.length - dot <= 10) full.substring(dot) else ""
        var base = if (ext.isEmpty()) full else full.substring(0, dot)
        val budget = maxBytes - ext.toByteArray(Charsets.UTF_8).size
        while (base.isNotEmpty() && base.toByteArray(Charsets.UTF_8).size > budget) base = base.dropLast(1)
        return base + ext
    }

    /**
     * 작업 폴더 기준 상대 경로 [rel]을 [root]([DIR]) 안의 파일로 바꾼다. 절대 경로, `..`, 심볼릭 링크로
     * [root] 밖을 가리키면 [EtlException]. 파일이 없어도 예외.
     */
    fun resolveInside(workspace: File, rel: String): File {
        val root = File(workspace, DIR).canonicalFile
        val cleaned = rel.trim().removePrefix("./")
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains('\u0000')) throw EtlException("허용되지 않은 경로입니다")
        val f = try {
            File(workspace, cleaned).canonicalFile
        } catch (_: IOException) {
            throw EtlException("허용되지 않은 경로입니다")
        }
        if (!f.path.startsWith(root.path + File.separator)) throw EtlException("etl-files/ 안의 파일만 열 수 있습니다")
        if (!f.isFile) throw EtlException("파일이 없습니다: $cleaned")
        return f
    }

    /** 작업 폴더 기준 상대 경로 표기. */
    fun relPath(workspace: File, f: File): String = f.canonicalFile.relativeTo(workspace.canonicalFile).path

    /**
     * "1-3,5" 같은 쪽 지정(1부터)을 쪽 번호 목록으로. 범위를 벗어난 값은 버리고, 중복을 없애 오름차순으로 [max]개까지.
     * 비어 있거나 해석할 수 없으면 앞쪽부터 [max]개.
     */
    fun parsePages(spec: String?, total: Int, max: Int = MAX_RENDER_PAGES): List<Int> {
        if (total <= 0) return emptyList()
        val out = sortedSetOf<Int>()
        for (part in (spec ?: "").split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val m = Regex("^(\\d+)(?:\\s*-\\s*(\\d+))?$").find(p) ?: continue
            val a = m.groupValues[1].toIntOrNull() ?: continue
            val b = m.groupValues[2].toIntOrNull() ?: a
            val lo = minOf(a, b)
            val hi = maxOf(a, b).coerceAtMost(total)
            var i = maxOf(lo, 1)
            while (i <= hi && out.size < 10_000) {
                out += i
                i++
            }
        }
        val list = if (out.isEmpty()) (1..total).toList() else out.toList()
        return list.take(max)
    }

    /** [dir] 안에서 [maxAgeMs]보다 오래된 파일을 지운다(하위 폴더 포함). 지운 개수. */
    fun prune(dir: File, nowMs: Long, maxAgeMs: Long = KEEP_DAYS * 24L * 3600 * 1000): Int {
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) n += prune(f, nowMs, maxAgeMs)
            else if (nowMs - f.lastModified() > maxAgeMs && f.delete()) n++
        }
        return n
    }
}
