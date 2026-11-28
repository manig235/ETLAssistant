package kr.snu.youngmini.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class OfficeFilesTest {
    private fun doc()=JSONObject("""{"title":"미적분 학습 요약","subtitle":"개념과 연습 문제","sections":[{"heading":"1. 핵심 개념","paragraphs":["함수의 변화율은 도함수로 나타내요.","극한과 연속의 정의를 먼저 확인해요."],"table":{"headers":["개념","확인할 점"],"rows":[["도함수","접선의 기울기"],["정적분","누적 변화량"]]}}]}""")
    private fun sheet()=JSONObject("""{"sheets":[{"name":"학습 계획","rows":[["단원","문제 수"],["미분",2],["적분",4],["합계",{"formula":"SUM(B2:B3)"}]],"widths":[28,16]}]}""")
    private fun slides()=JSONObject("""{"slides":[{"title":"미적분 학습 계획","body":["핵심 개념을 정리해요","예제 풀이로 연결해요"],"notes":"수업 발표용 설명"},{"title":"오늘의 연습","body":["미분 문제 2개","적분 문제 4개"]}]}""")
    @Test fun realOfficeFilesReopenAndEditsPreserveTheirOriginals() {
        val root=Files.createTempDirectory("youngmini-office").toFile();val paths=AppPaths(root);val office=OfficeFiles(paths,DocumentReader(paths.workspace))
        try {
            val word=office.create("docx","학습 요약.docx",doc());assertTrue(office.inspect(word.path).getJSONArray("paragraphs").toString().contains("미적분"))
            val changed=office.edit(word.path,"수정본.docx",JSONArray().put(JSONObject().put("op","set_paragraph").put("index",0).put("text","선형대수 학습 요약")))
            assertTrue(office.inspect(word.path).toString().contains("미적분"));assertTrue(office.inspect(changed.path).toString().contains("선형대수"))
            val picture=File(paths.workspace,"uploads/robot.png").apply {parentFile.mkdirs()};File("ui/public/robot.png").copyTo(picture)
            val spreadsheet=sheet();spreadsheet.getJSONArray("sheets").getJSONObject(0).put("images",JSONArray().put(JSONObject().put("path","uploads/robot.png")))
            val xlsx=office.create("xlsx","계획.xlsx",spreadsheet);val original=office.inspect(xlsx.path);assertTrue(original.toString().contains("SUM(B2:B3)"))
            org.apache.poi.xssf.usermodel.XSSFWorkbook(File(paths.workspace,xlsx.path).inputStream()).use {assertEquals(1,it.allPictures.size)}
            val updated=office.edit(xlsx.path,"계획 수정.xlsx",JSONArray().put(JSONObject().put("op","set_cell").put("sheet","학습 계획").put("cell","B2").put("value",10)))
            org.apache.poi.xssf.usermodel.XSSFWorkbook(File(paths.workspace,updated.path).inputStream()).use {assertEquals(14.0,it.getSheetAt(0).getRow(3).getCell(1).numericCellValue,0.001)}
            val ppt=office.create("pptx","발표.pptx",slides());assertEquals(2,office.inspect(ppt.path).getJSONArray("slides").length())
            assertThrows(Exception::class.java){office.create("xlsx","불허 수식.xlsx",JSONObject("""{"sheets":[{"name":"Sheet1","rows":[[{"formula":"WEBSERVICE(\"https://example.com\")"}]]}]}"""))}
        }finally{root.deleteRecursively()}
    }
    @Test fun nativeOfficeExportsActualKoreanFilesForVisualReviewWhenRequested() {
        assumeTrue(Platform.windows&&System.getenv("YOUNGMIN_TEST_OFFICE")=="1")
        val root=Files.createTempDirectory("youngmini-office-native").toFile();val paths=AppPaths(root);val office=OfficeFiles(paths,DocumentReader(paths.workspace));val renderer=OfficeRenderer(paths,office)
        try {
            val reports=File("build/reports/office-skills").apply {mkdirs()}
            for((kind,spec) in listOf("docx" to doc(),"xlsx" to sheet(),"pptx" to slides())) {
                val file=office.create(kind,"학습자료.$kind",spec);val pdf=renderer.render(file.path)
                File(paths.workspace,file.path).copyTo(File(reports,"sample.$kind"),overwrite=true);pdf.copyTo(File(reports,"$kind-preview.pdf"),overwrite=true)
                Loader.loadPDF(pdf).use {d->assertTrue(d.numberOfPages>0);assertTrue(PDFTextStripper().getText(d).contains(if(kind=="xlsx")"적분" else "미적분"));val image=PDFRenderer(d).renderImageWithDPI(0,90f);try{ImageIO.write(image,"png",File(reports,"$kind-preview.png"))}finally{image.flush()}}
            }
        }finally{root.deleteRecursively()}
    }
}
