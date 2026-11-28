package kr.snu.youngmini.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.form.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class FileToolsTest {
    @Test fun bundledSkillsProduceReadModifyAndRenderRealFiles() {
        val root=Files.createTempDirectory("file-skills").toFile();val paths=AppPaths(root);val tools=FileTools(paths,DocumentReader(paths.workspace),null)
        try {
            val skills=BundledSkills.call("skill_list",JSONObject()).getJSONArray("skills");assertEquals(7,skills.length());for(i in 0 until skills.length())assertTrue(BundledSkills.call("skill_read",JSONObject().put("name",skills.getJSONObject(i).getString("name"))).getString("text").length>200)
            assertThrows(Exception::class.java){BundledSkills.call("skill_read",JSONObject().put("name","../../secrets"))}
            val chart=JSONObject(tools.call("chart_create",JSONObject("""{"name":"실험 결과","spec":{"title":"전압과 전류","type":"scatter","x_label":"전압 (V)","y_label":"전류 (A)","series":[{"name":"측정값","values":[[1,2],[2,4],[3,6]]}]}}"""),null).text)
            val path=chart.getString("path");File("build/reports/office-skills").mkdirs();File(paths.workspace,path).copyTo(File("build/reports/office-skills/chart.png"),overwrite=true);ImageIO.read(File(paths.workspace,path)).also {assertEquals(1200,it.width);assertEquals(800,it.height);it.flush()}
            assertEquals(1,tools.call("file_image",JSONObject().put("path",path),null).images.size)
            val resized=JSONObject(tools.call("image_transform",JSONObject().put("path",path).put("name","회전").put("width",600).put("height",400).put("rotation",90),null).text);val img=ImageIO.read(File(paths.workspace,resized.getString("path")));assertEquals(400,img.width);assertEquals(600,img.height);img.flush()
            val csv=JSONObject(tools.call("csv_write",JSONObject().put("name","자료.csv").put("rows",JSONArray().put(JSONArray(listOf("제목","수치"))).put(JSONArray(listOf("=위험한 수식",-2))).put(JSONArray(listOf("줄\n바꿈",3)))),null).text)
            val rows=JSONObject(tools.call("csv_read",JSONObject().put("path",csv.getString("path")),null).text).getJSONArray("rows");assertEquals("'=위험한 수식",rows.getJSONArray(1).getString(0));assertEquals("줄\n바꿈",rows.getJSONArray(2).getString(0))
            val stats=JSONObject(tools.call("data_summary",JSONObject().put("values",JSONArray(listOf(1,2,3,JSONObject.NULL))),null).text);assertEquals(3,stats.getInt("count"));assertEquals(2.0,stats.getDouble("mean"),0.0);assertEquals(1.0,stats.getDouble("sample_stddev"),0.0)
        }finally{root.deleteRecursively()}
    }
    @Test fun pdfFormValuesAndAppearanceSurviveSaveAndPdfEditsKeepOriginal() {
        val root=Files.createTempDirectory("file-form").toFile();val paths=AppPaths(root);val reader=DocumentReader(paths.workspace);val tools=FileTools(paths,reader,null);val file=File(paths.workspace,"uploads/form.pdf").apply {parentFile.mkdirs()}
        try {
            PDDocument().use {doc->val page=PDPage();doc.addPage(page);doc.addPage(PDPage());val form=PDAcroForm(doc);doc.documentCatalog.acroForm=form;val resources=PDResources();resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"),PDType1Font(Standard14Fonts.FontName.HELVETICA));form.defaultResources=resources;form.defaultAppearance="/Helv 12 Tf 0 g";val field=PDTextField(form);field.partialName="student";field.defaultAppearance="/Helv 12 Tf 0 g";form.fields.add(field);val widget=field.widgets[0];widget.rectangle=PDRectangle(40f,650f,300f,40f);widget.page=page;page.annotations.add(widget);doc.save(file)}
            val fields=JSONObject(tools.call("pdf_fields",JSONObject().put("path","uploads/form.pdf"),null).text);assertEquals("student",fields.getJSONArray("fields").getJSONObject(0).getString("name"))
            val filled=JSONObject(tools.call("pdf_edit",JSONObject().put("operation","fill").put("name","작성한 양식").put("path","uploads/form.pdf").put("fields",JSONObject().put("student",if(Platform.windows)"영민"else "Young Mini")),null).text)
            Loader.loadPDF(File(paths.workspace,filled.getString("path"))).use {doc->val f=doc.documentCatalog.acroForm.getField("student");assertEquals(if(Platform.windows)"영민"else "Young Mini",f.valueAsString);assertNotNull(f.widgets[0].normalAppearanceStream)}
            assertEquals(1,reader.render(filled.getString("path"),"1").images.size)
            val extracted=JSONObject(tools.call("pdf_edit",JSONObject().put("operation","extract").put("name","둘째 쪽").put("path",filled.getString("path")).put("pages","2").put("rotation",90),null).text)
            Loader.loadPDF(File(paths.workspace,extracted.getString("path"))).use {assertEquals(1,it.numberOfPages);assertEquals(90,it.getPage(0).rotation)};Loader.loadPDF(file).use {assertEquals(2,it.numberOfPages);assertEquals("",it.documentCatalog.acroForm.getField("student").valueAsString)}
        }finally{root.deleteRecursively()}
    }
}
