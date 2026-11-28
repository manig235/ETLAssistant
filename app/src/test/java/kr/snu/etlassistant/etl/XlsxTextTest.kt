package kr.snu.etlassistant.etl

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxTextTest {
    private val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private fun read(sheet: String, query: String? = null, max: Int = 20_000, strings: String = "", target: String = "worksheets/sheet1.xml"): XlsxText.Result {
        val file = Files.createTempFile("scores", ".xlsx").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                val entries = mapOf(
                    "xl/workbook.xml" to """<workbook xmlns="$ns" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="중간고사" sheetId="1" r:id="rId5"/></sheets></workbook>""",
                    "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="$target"/></Relationships>""",
                    "xl/worksheets/sheet1.xml" to sheet,
                    "xl/sharedStrings.xml" to """<sst xmlns="$ns">$strings</sst>""",
                )
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            return XlsxText.read(file, query, max)
        } finally { file.delete() }
    }

    private fun sheet(body: String) = """<worksheet xmlns="$ns"><sheetData>$body</sheetData></worksheet>"""
    private fun rejected(block: () -> Unit) {
        try { block(); fail("accepted invalid spreadsheet") } catch (_: EtlException) { }
    }

    @Test fun scoreRowsKeepNamesStudentIdsAndCellCoordinatesTogether() {
        val result = read(sheet("""
            <row r="1"><c r="A1" t="inlineStr"><is><t>이름</t></is></c><c r="C1" t="inlineStr"><is><t>학번</t></is></c><c r="D1" t="inlineStr"><is><t>점수</t></is></c></row>
            <row r="5"><c r="A5" t="s"><v>0</v></c><c r="C5" t="s"><v>1</v></c><c r="D5"><v>87.5</v></c></row>
        """), strings = "<si><r><t>홍</t></r><r><t>길동</t></r></si><si><t>2026-00001</t></si>")
        assertTrue(result.text.contains("--- 시트: 중간고사 ---"))
        assertTrue(result.text.contains("A5: 홍길동 | C5: 2026-00001 | D5: 87.5"))
        assertFalse(result.truncated)
    }

    @Test fun queryCanFindARowPastTheNormalOutputLimitAndKeepHeaders() {
        val rows = (1..200).joinToString("") { n -> """<row r="$n"><c r="A$n" t="inlineStr"><is><t>${if (n == 200) "홍길동" else "학생$n"}</t></is></c><c r="C$n" t="str"><v>2026-$n</v></c><c r="D$n"><v>85</v></c></row>""" }
        val result = read(sheet(rows), query = "홍길동", max = 300)
        assertTrue(result.text.contains("A1: 학생1"))
        assertTrue(result.text.contains("A200: 홍길동 | C200: 2026-200 | D200: 85"))
        assertFalse(result.text.contains("A199:"))
        assertFalse(result.truncated)
        assertEquals(1, result.matchedRows)
        assertTrue(read(sheet(rows), max = 100).truncated)
        assertEquals(0, read(sheet(rows), query = "없는이름").matchedRows)
    }

    @Test fun formulasOnlyUseCachedValues() {
        val result = read(sheet("""<row r="1"><c r="D1"><f>SUM(A1:C1)</f><v>90</v></c><c r="E1" t="b"><v>1</v></c></row>"""))
        assertTrue(result.text.contains("D1: 90 (수식 저장값)"))
        assertTrue(result.text.contains("E1: TRUE"))
        assertFalse(result.text.contains("SUM"))
    }

    @Test fun rejectsDtdExternalPathsAndInvalidStringIndices() {
        rejected { read("""<!DOCTYPE worksheet [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" + sheet("""<row><c t="inlineStr"><is><t>&x;</t></is></c></row>""")) }
        rejected { read(sheet(""), target = "../../secret.xml") }
        rejected { read(sheet(""), target = "https://other.example/sheet.xml") }
        rejected { read(sheet("""<row><c t="s"><v>99</v></c></row>""")) }
        rejected { read(sheet("""<row><c t="inlineStr"><is><t>${"x".repeat(9 shl 20)}</t></is></c></row>""")) }
    }
}
