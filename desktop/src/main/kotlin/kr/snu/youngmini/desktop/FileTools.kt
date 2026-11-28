package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.*
import kr.snu.etlassistant.mcp.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import org.apache.pdfbox.rendering.PDFRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

class FileTools(private val paths:AppPaths,private val reader:DocumentReader,private val contexts:ToolContexts?) {
    val office=OfficeFiles(paths,reader)
    val renderer=OfficeRenderer(paths,office)
    private fun spec(name:String,description:String,properties:Map<String,String>,required:List<String>)=JSONObject().put("name",name).put("description",description).put("inputSchema",JSONObject().put("type","object").put("properties",JSONObject().also {p->properties.forEach {(k,v)->p.put(k,JSONObject().put("type",v))}}).put("required",JSONArray(required)).put("additionalProperties",false))
    fun specs()=listOf(
        spec("office_create","실제 DOCX/XLSX/PPTX 생성. 먼저 해당 file skill을 읽으세요. kind=docx/xlsx/pptx, spec은 스킬 예제의 JSON입니다. 결과 원본은 파일 카드에 표시됩니다.",mapOf("kind" to "string","name" to "string","spec" to "object"),listOf("kind","name","spec")),
        spec("office_inspect","관리되는 DOCX/XLSX/PPTX의 문단/표/셀·수식/슬라이드 텍스트와 편집 인덱스를 읽습니다.",mapOf("path" to "string"),listOf("path")),
        spec("office_edit","기존 Office 원본을 보존한 새 수정본 생성. operations는 DOCX set_paragraph(index,text)/append_paragraph(text), XLSX set_cell(sheet,cell,value), PPTX set_text(slide,shape,text). 인덱스는 0부터예요.",mapOf("path" to "string","name" to "string","operations" to "array"),listOf("path","name","operations")),
        spec("office_render","Microsoft Office로 DOCX/XLSX/PPTX의 원본 레이아웃을 PDF로 내보내고 쪽 이미지를 반환합니다. pages는 1-3처럼 최대 3쪽. 생성/변경 후 실제 출력 검수에 씁니다. 미지원/실패 시 검수했다고 말하지 마세요.",mapOf("path" to "string","pages" to "string"),listOf("path")),
        spec("pdf_edit","PDF 추출·분할·재정렬/회전·병합·양식 채우기·주석을 새 파일에 적용합니다. operation=extract/merge/fill/annotate. extract: path,pages,rotation(0/90/180/270). merge: paths 배열. fill: path,fields={필드이름:값}. annotate: path,notes=[{page,text,x,y}]. 양식은 인터랙티브하게 유지합니다.",mapOf("operation" to "string","name" to "string","path" to "string","paths" to "array","pages" to "string","rotation" to "integer","fields" to "object","notes" to "array"),listOf("operation","name")),
        spec("pdf_fields","PDF의 실제 양식 필드 이름·종류·값을 확인합니다. 필드가 없거나 구조가 모호하면 채우기를 진행하지 마세요.",mapOf("path" to "string"),listOf("path")),
        spec("file_image","생성/첨부/자료 폴더의 PNG/JPEG/GIF 이미지를 직접 읽어 확인합니다. 한 번에 한 장, 반환 이미지는 읽기 적당한 크기로 조정됩니다.",mapOf("path" to "string"),listOf("path")),
        spec("image_transform","원본을 보존한 PNG 크기 조정/자르기/90도 회전. 창작·그림 스타일 변환 도구가 아닙니다. width/height는 출력 크기, crop=[x,y,w,h], rotation=0/90/180/270.",mapOf("path" to "string","name" to "string","width" to "integer","height" to "integer","crop" to "array","rotation" to "integer"),listOf("path","name")),
        spec("csv_read","CSV/TSV 텍스트를 행·열 배열로 읽습니다. 인코딩은 UTF-8, delimiter 기본 쉼표. 수식을 실행하지 않습니다.",mapOf("path" to "string","delimiter" to "string"),listOf("path")),
        spec("csv_write","데이터 행 배열로 UTF-8 BOM CSV를 만듭니다. 텍스트의 수식 접두사는 Excel이 실행하지 않도록 보호됩니다. 숫자는 JSON 숫자로 주세요.",mapOf("name" to "string","rows" to "array"),listOf("name","rows")),
        spec("chart_create","학습 데이터로 PNG 선/막대/산점도 생성. spec={type:line/bar/scatter,title,x_label,y_label,labels:[문자],series:[{name,values:[숫자 또는 [x,y]]}],source}. 숫자·단위·출처를 확인하고 생성 후 file_image로 검수하세요.",mapOf("name" to "string","spec" to "object"),listOf("name","spec")),
        spec("calendar_read","로컬 ICS 일정 파일을 안전하게 읽어 일정 후보를 반환합니다. 지원하지 않는 반복은 errors로 반환하며 외부 URL/첨부/초대는 실행하지 않아요. 실제 등록은 사용자가 요청한 항목에 한해 my_deadline_add의 확인 절차로 진행하세요.",mapOf("path" to "string"),listOf("path")),
        spec("calendar_export","내 일정/선택한 일정 배열을 표준 ICS로 만듭니다. events 항목은 my_deadlines와 같은 title,due(밀리초),kind,notes,location,repeat JSON. PC의 캘린더 앱에서 사용자가 직접 가져올 수 있어요.",mapOf("name" to "string","events" to "array"),listOf("name","events")),
        spec("data_summary","수치 배열의 개수/평균/중앙값/최소/최대/표본 표준편차를 계산합니다. 결측값은 제외하며 모집단이나 단위를 추측하지 않습니다.",mapOf("values" to "array"),listOf("values"))
    )+BundledSkills.specs()
    fun names()=specs().map {it.getString("name")}.toSet()
    private fun publish(file:Artifact,context:ToolContexts.Context?):JSONObject {if(context!=null)contexts?.emit(context,file);return file.json()}
    fun preview(path:String)=renderer.render(path)
    fun call(name:String,args:JSONObject,context:ToolContexts.Context?):ToolResult {
        if(name in setOf("skill_list","skill_read"))return ToolResult(BundledSkills.call(name,args).toString())
        val result:Any=when(name) {
            "office_create"->publish(office.create(args.getString("kind"),args.getString("name"),args.getJSONObject("spec")),context)
            "office_inspect"->office.inspect(args.getString("path"))
            "office_edit"->publish(office.edit(args.getString("path"),args.getString("name"),args.getJSONArray("operations")),context)
            "office_render"->{val pdf=renderer.render(args.getString("path"));val rel=Materials.portablePath(paths.workspace,pdf);return reader.render(rel,args.optString("pages","1")).let {it.copy(text=JSONObject().put("path",args.getString("path")).put("preview_pdf",rel).put("native_renderer","Microsoft Office").put("page_count",Loader.loadPDF(pdf).use {d->d.numberOfPages}).put("render_result",JSONObject(it.text)).toString())}}
            "pdf_edit"->publish(pdfEdit(args),context)
            "pdf_fields"->{val source=reader.localFile(args.getString("path"));Loader.loadPDF(source).use {d->val form=d.documentCatalog.acroForm;JSONObject().put("fields",JSONArray(form?.fieldTree?.map {f->JSONObject().put("name",f.fullyQualifiedName).put("type",f.fieldType).put("value",f.valueAsString).put("read_only",f.isReadOnly)}.orEmpty())).put("signed",d.signatureDictionaries.isNotEmpty())}}
            "file_image"->{val image=loadImage(reader.localFile(args.getString("path")));try {return imageResult(image,JSONObject().put("path",args.getString("path")))}finally{image.flush()}}
            "image_transform"->publish(transform(args),context)
            "csv_read"->{val file=reader.localFile(args.getString("path"));require(file.length()<=4L*1024*1024);JSONObject().put("rows",parseCsv(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"),args.optString("delimiter",",").single())).put("path",args.getString("path"))}
            "csv_write"->{val rows=args.getJSONArray("rows");require(rows.length()<=20000);publish(office.generated(args.getString("name"),"csv"){file->file.bufferedWriter(Charsets.UTF_8).use {out->out.write("\uFEFF");for(i in 0 until rows.length()){val row=rows.getJSONArray(i);require(row.length()<=200);out.write((0 until row.length()).joinToString(","){j->val value=row.get(j);var text=if(value===JSONObject.NULL)""else value.toString();if(value is String&&text.firstOrNull() in listOf('=','+','-','@','\t','\r'))text="'"+text;"\""+text.replace("\"","\"\"")+"\""});out.write("\r\n")}}},context)}
            "calendar_read"->{val file=reader.localFile(args.getString("path"));require(file.extension.equals("ics",true)&&file.length()<=2L*1024*1024);CalendarFiles.read(file.readText(Charsets.UTF_8))}
            "calendar_export"->publish(office.generated(args.getString("name"),"ics"){it.writeText(CalendarFiles.export(args.getJSONArray("events")),Charsets.UTF_8)},context)
            "chart_create"->publish(office.generated(args.getString("name"),"png"){Charts.render(args.getJSONObject("spec"),it)},context)
            "data_summary"->{val values=args.getJSONArray("values");require(values.length()<=100000);val numbers=(0 until values.length()).mapNotNull {(values.opt(it) as? Number)?.toDouble()?.takeIf(Double::isFinite)}.sorted();if(numbers.isEmpty())throw EtlException("계산할 숫자가 없어요");val mean=numbers.average();val n=numbers.size;JSONObject().put("count",n).put("missing",values.length()-n).put("min",numbers.first()).put("max",numbers.last()).put("mean",mean).put("median",if(n%2==1)numbers[n/2]else(numbers[n/2-1]+numbers[n/2])/2).put("sample_stddev",if(n<2)JSONObject.NULL else kotlin.math.sqrt(numbers.sumOf {(it-mean)*(it-mean)}/(n-1)))}
            else->throw UnknownToolException(name)
        }
        return ToolResult(result.toString())
    }
    private fun pdfEdit(args:JSONObject):Artifact {
        val operation=args.getString("operation")
        return office.generated(args.getString("name"),"pdf"){target->
            when(operation) {
                "merge"->{val paths=args.getJSONArray("paths");require(paths.length() in 1..12);PDDocument().use {out->var total=0;for(i in 0 until paths.length())Loader.loadPDF(pdf(paths.getString(i))).use {src->if(src.isEncrypted)throw EtlException("잠긴 PDF는 합치지 않아요");for(page in src.pages){if(++total>300)throw EtlException("한 PDF는 300쪽까지 지원해요");out.importPage(page)}};out.save(target)}}
                "extract"->{Loader.loadPDF(pdf(args.getString("path"))).use {src->val pages=EtlFiles.parsePages(args.optString("pages"),src.numberOfPages,300);val rotation=args.optInt("rotation");require(rotation in listOf(0,90,180,270));PDDocument().use {out->for(n in pages){val p=out.importPage(src.getPage(n-1));p.rotation=(p.rotation+rotation)%360};out.save(target)}}}
                "fill"->{val fields=args.getJSONObject("fields");require(fields.length() in 1..500);Loader.loadPDF(pdf(args.getString("path"))).use {doc->
                    if(doc.isEncrypted||doc.signatureDictionaries.isNotEmpty())throw EtlException("잠기거나 서명된 PDF 양식은 자동 변경하지 않아요")
                    val form=doc.documentCatalog.acroForm?:throw EtlException("실제 PDF 양식 필드가 없어요");val all=form.fieldTree.toList();if(all.map {it.fullyQualifiedName}.distinct().size!=all.size)throw EtlException("중복된 양식 이름이 있어 원본 구조 확인이 필요해요")
                    val known=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<org.apache.pdfbox.cos.COSDictionary,Boolean>());all.forEach {f->f.widgets.forEach {known.add(it.cosObject)}}
                    if(doc.pages.any {p->p.annotations.filterIsInstance<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget>().any {it.cosObject !in known}})throw EtlException("양식 트리와 연결되지 않은 필드가 있어 원본 구조 확인이 필요해요")
                    val font=File(System.getenv("SystemRoot")?:"C:/Windows","Fonts/malgun.ttf");if(font.isFile){val resources=form.defaultResources?:PDResources().also {form.defaultResources=it};val name=resources.add(font.inputStream().use {PDType0Font.load(doc,it,false)});form.defaultAppearance="/${name.name} 10 Tf 0 g"}
                    fields.keys().forEach {key->val field=form.getField(key)?:throw EtlException("양식 필드를 찾지 못했어요: $key");if(field.isReadOnly||field is PDSignatureField)throw EtlException("읽기 전용/서명 필드는 바꾸지 않아요");if(field is org.apache.pdfbox.pdmodel.interactive.form.PDVariableText&&font.isFile)field.defaultAppearance=form.defaultAppearance;if(field is PDCheckBox){if(fields.optBoolean(key))field.check()else field.unCheck()}else field.setValue(fields.get(key).toString())}
                    form.setNeedAppearances(false);doc.save(target)
                };Loader.loadPDF(target).use {doc->fields.keys().forEach {key->val f=doc.documentCatalog.acroForm.getField(key)?:throw EtlException("저장한 양식 검증에 실패했어요");if(f is PDCheckBox){if(f.isChecked!=fields.getBoolean(key))throw EtlException("체크 상자 저장 검증에 실패했어요")}else if(f.valueAsString!=fields.get(key).toString())throw EtlException("양식 값 저장 검증에 실패했어요");if(f.widgets.any {it.normalAppearanceStream==null})throw EtlException("양식 표시 데이터 검증에 실패했어요")}}}
                "annotate"->{val notes=args.getJSONArray("notes");require(notes.length() in 1..200);Loader.loadPDF(pdf(args.getString("path"))).use {doc->for(i in 0 until notes.length()){val n=notes.getJSONObject(i);val p=n.getInt("page");require(p in 1..doc.numberOfPages);val annotation=PDAnnotationText();annotation.contents=n.getString("text").take(4000);annotation.rectangle=org.apache.pdfbox.pdmodel.common.PDRectangle(n.optDouble("x",40.0).toFloat(),n.optDouble("y",700.0).toFloat(),24f,24f);doc.getPage(p-1).annotations.add(annotation)};doc.save(target)}}
                else->throw EtlException("PDF 작업은 extract, merge, fill, annotate를 지원해요")
            }
            Loader.loadPDF(target).use {if(it.numberOfPages !in 1..300)throw EtlException("PDF 저장 결과를 확인하지 못했어요")}
        }
    }
    private fun pdf(path:String)=reader.localFile(path).also {if(!it.extension.equals("pdf",true)||it.length()>50L*1024*1024)throw EtlException("50MB 이하 PDF를 사용해 주세요")}
    private fun loadImage(file:File):BufferedImage {
        require(file.extension.lowercase() in listOf("png","jpg","jpeg","gif")&&file.length()<=20L*1024*1024)
        return ImageIO.createImageInputStream(file).use {input->val r=ImageIO.getImageReaders(input).asSequence().firstOrNull()?:throw EtlException("이미지를 읽지 못했어요");try{r.input=input;require(r.getWidth(0).toLong()*r.getHeight(0) in 1..20000000);r.read(0)}finally{r.dispose()}}
    }
    private fun imageResult(image:BufferedImage,meta:JSONObject):ToolResult {
        val ratio=minOf(1.0,1600.0/maxOf(image.width,image.height));val small=if(ratio==1.0)image else BufferedImage((image.width*ratio).toInt().coerceAtLeast(1),(image.height*ratio).toInt().coerceAtLeast(1),BufferedImage.TYPE_INT_ARGB).also {out->val g=out.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(image,0,0,out.width,out.height,null)}finally{g.dispose()}}
        val bytes=ByteArrayOutputStream().also {ImageIO.write(small,"png",it)}.toByteArray();if(small!==image)small.flush();require(bytes.size<=8*1024*1024)
        return ToolResult(meta.put("width",image.width).put("height",image.height).toString(),images=listOf(ToolImage("image/png",Base64.getEncoder().encodeToString(bytes))))
    }
    private fun transform(args:JSONObject):Artifact {
        val original=loadImage(reader.localFile(args.getString("path")))
        try {
            val crop=args.optJSONArray("crop");val img=if(crop==null)original else {require(crop.length()==4);val x=crop.getInt(0);val y=crop.getInt(1);val w=crop.getInt(2);val h=crop.getInt(3);require(x>=0&&y>=0&&w>0&&h>0&&x.toLong()+w<=original.width&&y.toLong()+h<=original.height);original.getSubimage(x,y,w,h)}
            val width=args.optInt("width",img.width);val height=args.optInt("height",img.height);require(width>0&&height>0&&width.toLong()*height<=20000000)
            val rotation=args.optInt("rotation");require(rotation in listOf(0,90,180,270));val out=BufferedImage(if(rotation%180==0)width else height,if(rotation%180==0)height else width,BufferedImage.TYPE_INT_ARGB)
            try {val g=out.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);when(rotation){90->{g.translate(height,0);g.rotate(Math.PI/2)};180->{g.translate(width,height);g.rotate(Math.PI)};270->{g.translate(0,width);g.rotate(-Math.PI/2)}};g.drawImage(img,0,0,width,height,null)}finally{g.dispose()};return office.generated(args.getString("name"),"png"){ImageIO.write(out,"png",it)}}finally{out.flush()}
        }finally{original.flush()}
    }
    private fun parseCsv(text:String,delimiter:Char):JSONArray {
        require(delimiter in listOf(',','\t',';'));val out=JSONArray();var row=JSONArray();val cell=StringBuilder();var quote=false;var index=0
        fun finish(){require(cell.length<=32767&&row.length()<200);row.put(cell.toString());cell.setLength(0)}
        while(index<text.length){val c=text[index];when{c=='"'->{if(quote&&index+1<text.length&&text[index+1]=='"'){cell.append('"');index++}else quote=!quote};c==delimiter&&!quote->finish();(c=='\n'||c=='\r')&&!quote->{finish();out.put(row);row=JSONArray();if(c=='\r'&&index+1<text.length&&text[index+1]=='\n')index++;require(out.length()<=20000)};else->cell.append(c)};index++}
        if(quote)throw EtlException("CSV 따옴표가 닫히지 않았어요");if(cell.isNotEmpty()||row.length()>0){finish();out.put(row)};return out
    }
}
