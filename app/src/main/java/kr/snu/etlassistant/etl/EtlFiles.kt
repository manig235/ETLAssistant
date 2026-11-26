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
    /**
     * 강의계획서 첨부를 두는 하위 폴더. 비서가 정보를 얻으려고만 읽는 파일이라, 이 안의 파일은
     * 채팅에 쪽 미리보기나 파일 카드를 띄우지 않는다([isPrivateRead]).
     */
    const val SYLLABUS_DIR = "syllabus"
    /**
     * 공개 기출 자료(MIT OCW 등, `study_fetch`)를 두는 하위 폴더. 강의계획서 첨부처럼 비서가 시험 자료를 만들려고만 읽으므로
     * 채팅에 쪽 미리보기나 파일 카드를 띄우지 않는다([isPrivateRead]).
     */
    const val STUDY_DIR = "study"
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

    /** 글을 뽑아 읽을 수 있는 Office 문서(docx, pptx). 강의계획서 첨부가 docx인 경우가 많다. */
    fun isOoxml(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("docx", "pptx")

    /** 압축을 풀었을 때 읽을 XML 한 부분의 상한(압축 폭탄 방지). */
    private const val MAX_XML_BYTES = 8L shl 20

    /**
     * docx(word/document.xml), pptx(ppt/slides/slideN.xml, 쪽 순서)의 글만 뽑는다. 문단·표 칸·줄바꿈은 줄로, 슬라이드는
     * `--- 슬라이드 N ---`으로 나눈다. 서식·그림은 버린다. 형식이 아니면 [EtlException].
     */
    fun ooxmlText(f: File): String {
        val isPptx = f.name.substringAfterLast('.', "").lowercase() == "pptx"
        val parts = sortedMapOf<Int, String>()
        java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val key = if (isPptx) Regex("^ppt/slides/slide(\\d+)\\.xml$").find(e.name)?.groupValues?.get(1)?.toIntOrNull()
                else if (e.name == "word/document.xml") 0 else null
                if (key == null) continue
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(1 shl 16)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_XML_BYTES) throw EtlException("문서가 너무 커서 읽을 수 없습니다")
                }
                parts[key] = xmlText(buf.toString("UTF-8"))
            }
        }
        if (parts.isEmpty()) throw EtlException("문서 형식을 읽을 수 없습니다")
        return if (isPptx) parts.entries.joinToString("\n\n") { "--- 슬라이드 ${it.key} ---\n${it.value}" } else parts.values.first()
    }

    /** WordprocessingML/DrawingML의 글: 문단(`</w:p>`, `</a:p>`)·줄바꿈·탭·표 칸을 살리고 태그를 지운다. */
    fun xmlText(xml: String): String {
        var s = xml
        s = Regex("<w:tab\\b[^>]*/>").replace(s, "\t")
        s = Regex("<(?:w:br|w:cr|a:br)\\b[^>]*/>").replace(s, "\n")
        // 표: 칸 안의 마지막 문단은 " | "로, 행 끝은 줄로(칸 안의 다른 문단은 아래에서 줄이 된다).
        s = Regex("</w:p>\\s*</w:tc>").replace(s, " | ")
        s = Regex("</w:tr>").replace(s, "\n")
        s = Regex("</(?:w:p|a:p)>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = s.replace(" | \n", "\n")
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        s = Regex("[ \\t]+\\n").replace(s, "\n")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    /**
     * 작업 폴더에 저장할 이름: `{파일ID}-{정리된 이름}`. 같은 파일(ID)이면 이름이 같아 반복해서 받아도 쌓이지 않는다.
     * 리눅스 파일 이름 한계(255바이트)를 넘지 않도록 **바이트 기준**으로 줄이고 확장자는 지킨다(한글은 글자당 3바이트).
     */
    fun storedName(fileId: Long, safeName: String, maxBytes: Int = 200): String = fitName("$fileId-$safeName", maxBytes)

    /** [full]을 확장자를 지키며 UTF-8 [maxBytes] 바이트 안으로 줄인다. */
    fun fitName(full: String, maxBytes: Int = 200): String {
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

    /**
     * 비서만 읽고 사용자에게는 보여 주지 않는 파일인가(강의계획서 첨부, 공개 기출 자료). `etl-files/syllabus/`·`etl-files/study/` 아래이거나,
     * 이전 빌드가 `etl-files/` 바로 아래에 둔 `syllabus-…` 파일. 이 폴더에는 앱 코드만 쓰고 비서(read-only 샌드박스)는
     * 파일을 옮기거나 복사할 수 없으므로, 경로를 바꿔 미리보기를 띄우는 우회는 없다.
     */
    fun isPrivateRead(workspace: File, f: File): Boolean {
        val root = File(workspace, DIR).canonicalFile
        val c = f.canonicalFile
        return c.path.startsWith(File(root, SYLLABUS_DIR).path + File.separator) ||
            c.path.startsWith(File(root, STUDY_DIR).path + File.separator) ||
            (c.parentFile == root && c.name.startsWith("syllabus-"))
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
