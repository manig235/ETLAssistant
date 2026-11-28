package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.sl.usermodel.ShapeType
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xslf.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.*
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/** File skills use bounded document operations; no model supplied code, command or destination path. */
class OfficeFiles(private val paths:AppPaths,private val reader:DocumentReader) {
    private val font="Malgun Gothic"
    fun generated(name:String,extension:String,write:(File)->Unit)=output(name,extension,write)
    private fun list(a:JSONArray?)=(0 until (a?.length()?:0)).map {a!!.get(it)}
    private fun objects(a:JSONArray?)=list(a).map {it as? JSONObject?:throw EtlException("문서 항목 형식을 확인해 주세요")}
    fun source(path:String):File=reader.localFile(path).also {f->
        if(f.extension.lowercase() !in listOf("docx","xlsx","pptx"))throw EtlException("DOCX, XLSX, PPTX 파일을 사용해 주세요")
        if(f.length()>50L*1024*1024)throw EtlException("50MB 이하 파일을 사용해 주세요")
        ZipFile(f).use {zip->val items=zip.entries().asSequence().toList();if(items.size>4000||items.any {it.size<0||it.size>32L*1024*1024}||items.sumOf {it.size}>100L*1024*1024)throw EtlException("압축을 푼 문서 크기가 너무 커요")}
    }
    private fun output(name:String,ext:String,write:(File)->Unit):Artifact {
        val dir=File(paths.workspace,"generated").apply {mkdirs()};val root=dir.toPath().toRealPath()
        if(!root.startsWith(paths.workspace.toPath().toRealPath()))throw EtlException("생성 폴더를 열지 못했어요")
        require(ext in listOf("docx","xlsx","pptx","pdf","png","csv","svg","txt","ics"))
        val id=UUID.randomUUID().toString();val safe=Materials.windowsFileName(name,"학습자료.$ext").substringBeforeLast('.').take(100).ifBlank {"학습자료"}+".$ext"
        val target=File(dir,"$id-$safe");val temp=Files.createTempFile(root,".office-",".part").toFile()
        try {write(temp);if(temp.length() !in 1..50L*1024*1024)throw EtlException("생성 파일은 50MB까지 지원해요");Files.move(temp.toPath(),target.toPath());return Artifact(id,safe,Materials.portablePath(paths.workspace,target),target.length(),"generated")}finally{temp.delete()}
    }
    fun create(kind:String,name:String,spec:JSONObject):Artifact {
        require(kind in listOf("docx","xlsx","pptx"))
        require(spec.toString().length<=1000000)
        return output(name,kind){target->when(kind){"docx"->document(spec,target);"xlsx"->workbook(spec,target);"pptx"->slides(spec,target);else->throw EtlException("docx, xlsx, pptx 중에서 선택해 주세요")}}
    }
    private fun document(spec:JSONObject,target:File) {
        XWPFDocument().use {doc->
            fun paragraph(text:String,size:Double=11.0,bold:Boolean=false,style:String?=null) {
                val p=doc.createParagraph();p.spacingAfter=120;p.setSpacingBetween(1.25);style?.let {p.style=it}
                val r=p.createRun();r.fontFamily=font;r.setFontSize(size);r.isBold=bold
                text.split('\n').forEachIndexed {i,line->if(i>0)r.addBreak();r.setText(line)}
            }
            spec.optString("title").takeIf {it.isNotBlank()}?.let {paragraph(it,23.0,true,"Title")}
            spec.optString("subtitle").takeIf {it.isNotBlank()}?.let {paragraph(it,12.0)}
            fun table(o:JSONObject) {
                val headers=list(o.optJSONArray("headers"));val rows=list(o.optJSONArray("rows")).map {list(it as? JSONArray)}
                val cols=maxOf(headers.size,rows.maxOfOrNull {it.size}?:0);if(cols==0)return
                require(cols<=30&&rows.size<=1000)
                val all=if(headers.isEmpty())rows else listOf(headers)+rows;val t=doc.createTable(all.size,cols);t.setWidth("100%")
                all.forEachIndexed {ri,row->row.forEachIndexed {ci,value->val cell=t.getRow(ri).getCell(ci);cell.removeParagraph(0);val p=cell.addParagraph();p.spacingAfter=80;val r=p.createRun();r.fontFamily=font;r.setFontSize(10.0);r.setText(if(value===JSONObject.NULL)"" else value.toString());if(ri==0&&headers.isNotEmpty()){r.isBold=true;cell.color="EDF2F8";t.getRow(ri).isRepeatHeader=true}}}
                doc.createParagraph()
            }
            for(section in objects(spec.optJSONArray("sections"))) {
                section.optString("heading").takeIf {it.isNotBlank()}?.let {paragraph(it,16.0,true,"Heading1")}
                for(p in list(section.optJSONArray("paragraphs")))if(p is JSONObject)paragraph(p.optString("text"),p.optDouble("size",11.0).coerceIn(9.0,40.0),p.optBoolean("bold"))else paragraph(p.toString())
                val bullets=list(section.optJSONArray("bullets"));if(bullets.isNotEmpty()) {
                    val numbering=doc.numbering?:doc.createNumbering();val abstract=org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum.Factory.newInstance();abstract.abstractNumId=BigInteger.valueOf(numbering.abstractNums.size.toLong());val level=abstract.addNewLvl();level.ilvl=BigInteger.ZERO;level.addNewStart().`val`=BigInteger.ONE;level.addNewNumFmt().`val`=org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat.BULLET;level.addNewLvlText().`val`="•";val id=numbering.addNum(numbering.addAbstractNum(XWPFAbstractNum(abstract)))
                    for(b in bullets){val p=doc.createParagraph();p.numID=id;p.indentationLeft=400;p.indentationHanging=220;p.spacingAfter=100;p.createRun().apply {fontFamily=font;setFontSize(11.0);setText(b.toString())}}
                }
                section.optJSONObject("table")?.let(::table)
                section.optString("image_path").takeIf {it.isNotBlank()}?.let {path->val image=reader.localFile(path);checkImage(image);val p=doc.createParagraph();val run=p.createRun();image.inputStream().use {run.addPicture(it,if(image.extension.equals("png",true))Document.PICTURE_TYPE_PNG else Document.PICTURE_TYPE_JPEG,image.name,org.apache.poi.util.Units.toEMU(440.0),org.apache.poi.util.Units.toEMU(imageHeight(image,440.0)))}}
            }
            for(p in list(spec.optJSONArray("paragraphs")))paragraph(p.toString())
            for(t in objects(spec.optJSONArray("tables")))table(t)
            target.outputStream().use(doc::write)
        }
        XWPFDocument(target.inputStream()).use {require(it.paragraphs.isNotEmpty()||it.tables.isNotEmpty())}
    }
    private fun workbook(spec:JSONObject,target:File) {
        val sheets=objects(spec.optJSONArray("sheets"));require(sheets.size in 1..30)
        XSSFWorkbook().use {book->
            var cells=0
            for(s in sheets) {
                val name=s.optString("name","Sheet${book.numberOfSheets+1}");require(name.length in 1..31&&name.none {it in "[]:*?/\\"})
                val sheet=book.createSheet(name);val rows=list(s.optJSONArray("rows"));require(rows.size<=20000)
                val normal=book.createCellStyle().apply {wrapText=true;verticalAlignment=VerticalAlignment.TOP;setFont(book.createFont().apply {fontName=this@OfficeFiles.font;fontHeightInPoints=11})}
                val header=book.createCellStyle().apply {cloneStyleFrom(normal);fillForegroundColor=IndexedColors.PALE_BLUE.index;fillPattern=FillPatternType.SOLID_FOREGROUND;setFont(book.createFont().apply {fontName=this@OfficeFiles.font;fontHeightInPoints=11;bold=true})}
                val headerRows=s.optInt("header_rows",1).coerceIn(0,10)
                rows.forEachIndexed {ri,data->val row=sheet.createRow(ri);list(data as? JSONArray).forEachIndexed {ci,v->if(++cells>100000)throw EtlException("한 파일에 10만 셀까지 지원해요");require(ci<200);val cell=row.createCell(ci);cell.cellStyle=if(ri<headerRows)header else normal;setCell(cell,v,book)}}
                for(f in objects(s.optJSONArray("formats"))) {val range=CellRangeAddress.valueOf(f.getString("range"));require(range.lastRow<20000&&range.lastColumn<200);val style=book.createCellStyle().apply {cloneStyleFrom(normal);dataFormat=book.createDataFormat().getFormat(f.getString("format"))};for(ri in range.firstRow..range.lastRow)for(ci in range.firstColumn..range.lastColumn)(sheet.getRow(ri)?:sheet.createRow(ri)).let {(it.getCell(ci)?:it.createCell(ci)).cellStyle=style}}
                for(m in list(s.optJSONArray("merged")))sheet.addMergedRegion(CellRangeAddress.valueOf(m.toString()))
                val images=objects(s.optJSONArray("images"));require(images.size<=30)
                for(image in images) {
                    val file=reader.localFile(image.getString("path"));checkImage(file)
                    val index=book.addPicture(file.readBytes(),if(file.extension.equals("png",true))Workbook.PICTURE_TYPE_PNG else Workbook.PICTURE_TYPE_JPEG)
                    fun column(key:String,default:Int)=image.optInt(key,default).also {require(it in 0..200)}
                    val anchor=book.creationHelper.createClientAnchor().apply {setCol1(column("from_col",3));row1=image.optInt("from_row",1);setCol2(column("to_col",11));row2=image.optInt("to_row",22)}
                    require(anchor.col1>=0&&anchor.row1>=0&&anchor.col2>anchor.col1&&anchor.row2>anchor.row1&&anchor.col2<=200&&anchor.row2<=20000)
                    sheet.createDrawingPatriarch().createPicture(anchor,index)
                }
                val width=s.optJSONArray("widths");val max=rows.maxOfOrNull { (it as? JSONArray)?.length()?:0 }?:0
                for(c in 0 until max)sheet.setColumnWidth(c,((width?.optDouble(c,22.0)?:22.0).coerceIn(5.0,80.0)*256).toInt())
                sheet.createFreezePane(0,s.optInt("freeze_rows",headerRows).coerceIn(0,rows.size));sheet.setFitToPage(true);sheet.printSetup.fitWidth=1;sheet.printSetup.fitHeight=0;sheet.printSetup.landscape=max>6
            }
            book.creationHelper.createFormulaEvaluator().evaluateAll()
            for(sheet in book)for(row in sheet)for(cell in row)if(cell.cellType==CellType.FORMULA&&cell.cachedFormulaResultType==CellType.ERROR)throw EtlException("수식 ${sheet.sheetName}!${cell.address}에서 오류가 났어요. 수식과 참조를 확인해 주세요.")
            book.setForceFormulaRecalculation(true);target.outputStream().use(book::write)
        }
        XSSFWorkbook(target.inputStream()).use {require(it.numberOfSheets>0)}
    }
    private fun checkFormulas(book:XSSFWorkbook) {
        book.creationHelper.createFormulaEvaluator().evaluateAll()
        for(sheet in book)for(row in sheet)for(cell in row)if(cell.cellType==CellType.FORMULA&&cell.cachedFormulaResultType==CellType.ERROR)throw EtlException("수식 ${sheet.sheetName}!${cell.address}에서 오류가 났어요")
    }
    private fun setCell(cell:org.apache.poi.ss.usermodel.Cell,v:Any?,book:XSSFWorkbook) {
        when(v) {
            null,JSONObject.NULL->cell.setBlank()
            is Number->{require(v.toDouble().isFinite());cell.setCellValue(v.toDouble())}
            is Boolean->cell.setCellValue(v)
            is JSONObject->{
                if(v.has("formula")){val formula=v.getString("formula").removePrefix("=");require(formula.length in 1..2000&&!formula.contains(Regex("\\[|\\]|WEBSERVICE|RTD\\s*\\(|DDE|HYPERLINK\\s*\\(",RegexOption.IGNORE_CASE)));cell.cellFormula=formula}
                else setCell(cell,v.opt("value"),book)
                if(v.has("format")){val fmt=book.createDataFormat().getFormat(v.getString("format"));val base=cell.cellStyle;val style=(0 until book.numCellStyles).map {book.getCellStyleAt(it)}.find {it.dataFormat==fmt&&it.fontIndex==base.fontIndex&&it.fillForegroundColor==base.fillForegroundColor}?:book.createCellStyle().apply {cloneStyleFrom(base);dataFormat=fmt};cell.cellStyle=style}
            }
            else->{val text=v.toString();require(text.length<=32767);cell.setCellValue(text)}
        }
    }
    private fun slides(spec:JSONObject,target:File) {
        val slides=objects(spec.optJSONArray("slides"));require(slides.size in 1..100)
        XMLSlideShow().use {deck->
            deck.pageSize=Dimension(1280,720)
            for((index,s) in slides.withIndex()) {
                val slide=deck.createSlide();slide.background.fillColor=color(s.optString("background","FFFFFF"))
                fun text(text:String,x:Double,y:Double,w:Double,h:Double,size:Double,bold:Boolean=false,hex:String="24364F") {
                    require(x>=0&&y>=0&&w>0&&h>0&&x+w<=1281&&y+h<=721)
                    val box=slide.createTextBox();box.anchor=Rectangle2D.Double(x,y,w,h);box.clearText()
                    text.split('\n').forEach {line->val p=box.addNewTextParagraph();p.spaceAfter=12.0;val r=p.addNewTextRun();r.setText(line);r.fontFamily=font;r.fontSize=size;r.isBold=bold;r.setFontColor(color(hex))}
                }
                text(s.optString("title"),64.0,44.0,1152.0,100.0,38.0,true)
                val body=list(s.optJSONArray("body")).joinToString("\n"){it.toString()};if(body.isNotBlank())text(body,68.0,170.0,1144.0,440.0,24.0)
                for(shape in objects(s.optJSONArray("shapes"))) {
                    val x=shape.optDouble("x",64.0);val y=shape.optDouble("y",170.0);val w=shape.optDouble("width",550.0);val h=shape.optDouble("height",100.0)
                    require(x>=0&&y>=0&&w>0&&h>0&&x+w<=1280&&y+h<=720)
                    when(shape.optString("type","text")) {
                        "text"->text(shape.optString("text"),x,y,w,h,shape.optDouble("size",24.0).coerceIn(12.0,72.0),shape.optBoolean("bold"),shape.optString("color","24364F"))
                        "image"->{val image=reader.localFile(shape.getString("path"));checkImage(image);val data=deck.addPicture(image,if(image.extension.equals("png",true))org.apache.poi.sl.usermodel.PictureData.PictureType.PNG else org.apache.poi.sl.usermodel.PictureData.PictureType.JPEG);slide.createPicture(data).anchor=Rectangle2D.Double(x,y,w,h)}
                        "rectangle","ellipse"->{val r=slide.createAutoShape();r.shapeType=if(shape.optString("type")=="ellipse")ShapeType.ELLIPSE else ShapeType.RECT;r.anchor=Rectangle2D.Double(x,y,w,h);r.fillColor=color(shape.optString("fill","EDF3FB"));r.lineColor=null}
                        else->throw EtlException("지원하는 도형은 text, image, rectangle, ellipse예요")
                    }
                }
                text("${index+1}",1180.0,663.0,50.0,30.0,14.0,false,"8B97A7")
                s.optString("notes").takeIf {it.isNotBlank()}?.let {note->val notes=deck.getNotesSlide(slide);notes.shapes.filterIsInstance<XSLFTextShape>().firstOrNull {it.placeholder==org.apache.poi.sl.usermodel.Placeholder.BODY}?.text=note}
            }
            target.outputStream().use(deck::write)
        }
        XMLSlideShow(target.inputStream()).use {require(it.slides.size==slides.size)}
    }
    private fun color(hex:String):Color {require(Regex("[0-9A-Fa-f]{6}").matches(hex));return Color(hex.toInt(16))}
    private fun imageHeight(file:File,width:Double):Double=ImageIO.createImageInputStream(file).use {input->val r=ImageIO.getImageReaders(input).next();try {r.input=input;width*r.getHeight(0)/r.getWidth(0)}finally{r.dispose()}}
    private fun checkImage(file:File) {require(file.extension.lowercase() in listOf("png","jpg","jpeg")&&file.length()<=12L*1024*1024);ImageIO.createImageInputStream(file).use {input->val r=ImageIO.getImageReaders(input).asSequence().firstOrNull()?:throw EtlException("이미지를 읽지 못했어요");try {r.input=input;require(r.getWidth(0).toLong()*r.getHeight(0) in 1..20000000L)}finally{r.dispose()}}}
    fun inspect(path:String):JSONObject {
        val file=source(path)
        return when(file.extension.lowercase()) {
            "docx"->XWPFDocument(file.inputStream()).use {d->JSONObject().put("paragraphs",JSONArray(d.paragraphs.mapIndexed {i,p->JSONObject().put("index",i).put("text",p.text).put("style",p.style?:JSONObject.NULL)})).put("tables",JSONArray(d.tables.map {t->JSONArray(t.rows.map {r->JSONArray(r.tableCells.map {it.text})})}))}
            "xlsx"->XSSFWorkbook(file.inputStream()).use {w->val formatter=DataFormatter().apply {setUseCachedValuesForFormulaCells(true)};JSONObject().put("sheets",JSONArray(w.map {s->JSONObject().put("name",s.sheetName).put("rows",JSONArray(s.take(2000).map {r->JSONArray(r.take(100).map {c->JSONObject().put("cell",c.address.formatAsString()).put("value",formatter.formatCellValue(c)).put("formula",if(c.cellType==CellType.FORMULA)c.cellFormula else JSONObject.NULL)})})).put("merged",JSONArray(s.mergedRegions.map {it.formatAsString()}))}))}
            else->XMLSlideShow(file.inputStream()).use {d->JSONObject().put("width",d.pageSize.width).put("height",d.pageSize.height).put("slides",JSONArray(d.slides.mapIndexed {i,s->JSONObject().put("index",i).put("shapes",JSONArray(s.shapes.mapIndexedNotNull {j,shape->(shape as? XSLFTextShape)?.let {JSONObject().put("index",j).put("text",it.text)}}))}))}
        }.put("path",path)
    }
    fun edit(path:String,name:String,ops:JSONArray):Artifact {
        val source=source(path);require(ops.length() in 1..1000)
        return output(name,source.extension.lowercase()){target->when(source.extension.lowercase()) {
            "docx"->XWPFDocument(source.inputStream()).use {doc->for(op in objects(ops)){when(op.getString("op")){"set_paragraph"->{val p=doc.paragraphs.getOrNull(op.getInt("index"))?:throw EtlException("문단 번호를 확인해 주세요");while(p.runs.isNotEmpty())p.removeRun(0);p.createRun().apply {fontFamily=font;setFontSize(11.0);setText(op.getString("text"))}};"append_paragraph"->doc.createParagraph().createRun().apply {fontFamily=font;setFontSize(11.0);setText(op.getString("text"))};else->throw EtlException("문서 편집은 set_paragraph, append_paragraph를 지원해요")}};target.outputStream().use(doc::write)}
            "xlsx"->XSSFWorkbook(source.inputStream()).use {book->for(op in objects(ops)){require(op.getString("op")=="set_cell");val sheet=book.getSheet(op.getString("sheet"))?:throw EtlException("시트 이름을 확인해 주세요");val ref=CellReference(op.getString("cell"));require(ref.row in 0..19999&&ref.col.toInt() in 0..199);val row=sheet.getRow(ref.row)?:sheet.createRow(ref.row);setCell(row.getCell(ref.col.toInt())?:row.createCell(ref.col.toInt()),op.get("value"),book)};checkFormulas(book);target.outputStream().use(book::write)}
            else->XMLSlideShow(source.inputStream()).use {deck->for(op in objects(ops)){require(op.getString("op")=="set_text");val slide=deck.slides.getOrNull(op.getInt("slide"))?:throw EtlException("슬라이드 번호를 확인해 주세요");val shape=slide.shapes.getOrNull(op.getInt("shape")) as? XSLFTextShape?:throw EtlException("텍스트 도형 번호를 확인해 주세요");val run=shape.textParagraphs.firstOrNull()?.textRuns?.firstOrNull();val size=run?.fontSize?:24.0;val family=run?.fontFamily?:font;shape.text=op.getString("text");shape.textParagraphs.flatMap {it.textRuns}.forEach {it.fontFamily=family;it.fontSize=size}};target.outputStream().use(deck::write)}
        }}
    }
}
