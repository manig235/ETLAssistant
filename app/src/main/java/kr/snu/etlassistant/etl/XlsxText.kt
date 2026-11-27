package kr.snu.etlassistant.etl

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** xlsx의 저장된 셀 값만 읽는다. 수식·매크로·외부 링크는 실행하지 않는다. */
object XlsxText {
    private const val MAX_PART = 8 shl 20
    private const val MAX_TOTAL = 32 shl 20
    private const val MAX_SHEETS = 100
    private const val MAX_STRINGS = 100_000
    data class Result(val text: String, val truncated: Boolean, val matchedRows: Int)

    fun isXlsx(name: String): Boolean = name.substringAfterLast('.', "").equals("xlsx", true)

    fun read(file: File, query: String? = null, maxChars: Int = EtlFiles.DEFAULT_TEXT_CHARS): Result {
        val limit = maxChars.coerceIn(1, EtlFiles.MAX_TEXT_CHARS)
        val needle = query?.trim().orEmpty()
        val out = StringBuilder()
        var truncated = false
        var matched = 0
        fun append(s: String) {
            val remaining = limit - out.length
            if (s.length > remaining) truncated = true
            if (remaining > 0) out.append(s.take(remaining))
        }
        try {
            ZipFile(file).use { zip ->
                var total = 0
                fun xml(path: String): Element {
                    val entry = zip.getEntry(path) ?: throw EtlException("엑셀 문서의 필수 부분이 없습니다")
                    if (entry.size > MAX_PART) throw EtlException("엑셀 문서가 너무 큽니다")
                    val bytes = zip.getInputStream(entry).use { input ->
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            if (buffer.size() + n > MAX_PART) throw EtlException("엑셀 문서가 너무 큽니다")
                            buffer.write(chunk, 0, n)
                        }
                        buffer.toByteArray()
                    }
                    total += bytes.size
                    if (bytes.size > MAX_PART || total > MAX_TOTAL) throw EtlException("엑셀 문서가 너무 큽니다")
                    // 문자열로 검사한 뒤 같은 문자열을 파싱한다. UTF-16 문서도 DTD 검사를 우회하지 못한다.
                    val text = if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                            (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))) bytes.toString(Charsets.UTF_16)
                        else bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                    if (text.contains('\u0000') || Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                        throw EtlException("안전하지 않은 엑셀 XML을 거부했습니다")
                    }
                    val builder = DocumentBuilderFactory.newInstance().apply {
                        isNamespaceAware = true
                        isExpandEntityReferences = false
                    }.newDocumentBuilder()
                    builder.setEntityResolver { _, _ -> throw EtlException("엑셀의 외부 참조는 읽지 않습니다") }
                    return builder.parse(InputSource(StringReader(text))).documentElement
                }
                val workbook = xml("xl/workbook.xml")
                val rels = xml("xl/_rels/workbook.xml.rels").elements("Relationship").filter {
                    it.getAttribute("TargetMode") != "External" && it.getAttribute("Type").endsWith("/worksheet")
                }.associate { it.getAttribute("Id") to it.getAttribute("Target") }
                val strings = if (zip.getEntry("xl/sharedStrings.xml") != null) {
                    val items = xml("xl/sharedStrings.xml").elements("si")
                    if (items.size > MAX_STRINGS) throw EtlException("엑셀의 문자열이 너무 많습니다")
                    items.map { richText(it) }
                } else emptyList()
                val sheets = workbook.elements("sheet").filter { it.getAttribute("state") !in setOf("hidden", "veryHidden") }
                if (sheets.size > MAX_SHEETS) throw EtlException("엑셀의 시트가 너무 많습니다")
                for (sheet in sheets) {
                    val relationId = (0 until sheet.attributes.length).map { sheet.attributes.item(it) }
                        .firstOrNull { it.localName == "id" }?.nodeValue
                    val target = rels[relationId] ?: throw EtlException("엑셀 시트 경로를 읽을 수 없습니다")
                    val uri = URI("xl/").resolve(target).normalize()
                    val path = uri.path.removePrefix("/")
                    if (uri.isAbsolute || uri.rawAuthority != null || !path.startsWith("xl/") || uri.query != null || uri.fragment != null) {
                        throw EtlException("엑셀 시트 경로가 올바르지 않습니다")
                    }
                    append("--- 시트: ${sheet.getAttribute("name")} ---\n")
                    val rows = xml(path).elements("row")
                    rows.forEachIndexed { index, row ->
                        val cells = row.elements("c").mapNotNull { cell ->
                            val value = cell.elements("v").firstOrNull()?.textContent.orEmpty()
                            val text = when (cell.getAttribute("t")) {
                                "s" -> strings.getOrNull(value.toIntOrNull() ?: -1)
                                    ?: throw EtlException("엑셀의 문자열 참조가 올바르지 않습니다")
                                "inlineStr" -> cell.elements("is").firstOrNull()?.let(::richText).orEmpty()
                                "b" -> if (value == "1") "TRUE" else "FALSE"
                                else -> value
                            }
                            if (text.isEmpty()) null else {
                                val suffix = if (cell.elements("f").isNotEmpty()) " (수식 저장값)" else ""
                                "${cell.getAttribute("r")}: ${text.replace(Regex("[\\r\\n\\t]+"), " ")}$suffix"
                            }
                        }
                        val line = cells.joinToString(" | ")
                        val matches = line.contains(needle, ignoreCase = true)
                        if (cells.isNotEmpty() && matches) matched++
                        // 검색해도 앞 세 행은 열 제목 확인용으로 함께 준다. 셀 주소로 빈 칸·행 경계를 보존한다.
                        if (cells.isNotEmpty() && (matches || index < 3)) append(line + "\n")
                    }
                }
            }
        } catch (e: EtlException) {
            throw e
        } catch (_: Exception) {
            throw EtlException("엑셀 문서가 손상되었거나 지원하지 않는 형식입니다")
        }
        return Result(out.toString().trimEnd(), truncated, matched)
    }

    private fun Element.elements(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun richText(element: Element): String = element.elements("t").filter {
        // 발음 안내(rPh)는 실제 셀 텍스트가 아니다.
        (it.parentNode as? Element)?.localName != "rPh"
    }.joinToString("") { it.textContent }
}
