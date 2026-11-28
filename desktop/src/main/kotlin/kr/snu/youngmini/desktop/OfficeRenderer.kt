package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import org.apache.pdfbox.Loader
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Read-only export of a sanitized private copy. Never attaches to user PowerPoint instances. */
class OfficeRenderer(private val paths:AppPaths,private val office:OfficeFiles) {
    private val gate=Any()
    fun render(path:String):File=synchronized(gate) {
        if(!Platform.windows)throw EtlException("원본 Office 렌더링은 Windows에 설치된 Microsoft Office가 필요해요")
        val input=office.source(path);val kind=input.extension.lowercase()
        val script=System.getProperty("youngmini.assets")?.let {File(it,"render-office.ps1")}?:File("ui/electron/render-office.ps1")
        if(!script.isFile)throw EtlException("Office 렌더링 도구를 찾지 못했어요")
        val digest=MessageDigest.getInstance("SHA-256");input.inputStream().use {stream->val bytes=ByteArray(65536);while(true){val n=stream.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        val key=digest.digest().joinToString(""){"%02x".format(it)}
        val dir=File(paths.workspace,"generated/.office-preview/$key").apply {mkdirs()}
        require(dir.toPath().toRealPath().startsWith(paths.workspace.toPath().toRealPath()))
        val output=File(dir,"preview.pdf")
        if(output.isFile&&runCatching {Loader.loadPDF(output).use {it.numberOfPages>0}}.getOrDefault(false))return@synchronized output
        val source=File(dir,"source.$kind");try{sanitize(input,source)}catch(e:Exception){source.delete();throw e}
        val powershell=File(System.getenv("SystemRoot")?:"C:/Windows","System32/WindowsPowerShell/v1.0/powershell.exe")
        val command=listOf(powershell.path,"-NoLogo","-NoProfile","-NonInteractive","-File",script.absolutePath,"-Kind",kind,"-SourcePath",source.absolutePath,"-OutputPath",output.absolutePath)
        val builder=ProcessBuilder(command).redirectErrorStream(true)
        val old=builder.environment().toMap();builder.environment().clear();for(k in listOf("SystemRoot","WINDIR","PATH","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA"))old[k]?.let {builder.environment()[k]=it}
        val child=builder.start();val drain=Thread({child.inputStream.use {stream->val bytes=ByteArray(4096);while(stream.read(bytes)>=0){}}},"office-render-output").apply {isDaemon=true;start()}
        try {
            if(!child.waitFor(75,TimeUnit.SECONDS)){child.destroyForcibly();throw EtlException("Office 렌더링 시간이 초과됐어요. 열려 있는 Office 창을 확인한 뒤 다시 시도해 주세요.")}
            drain.join(1000)
            if(child.exitValue()!=0||!output.isFile)throw EtlException("원본 Office 렌더링을 마치지 못했어요. 해당 Microsoft Office 설치 상태와 열려 있는 PowerPoint를 확인해 주세요.")
            Loader.loadPDF(output).use {if(it.numberOfPages !in 1..300)throw EtlException("Office 미리보기는 300쪽까지 지원해요")}
            output
        }finally{if(child.isAlive)child.destroyForcibly();source.delete()}
    }
    private fun sanitize(input:File,output:File) {
        val parser=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);setFeature("http://xml.org/sax/features/external-general-entities",false);setFeature("http://xml.org/sax/features/external-parameter-entities",false);setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")}
        val transformers=TransformerFactory.newInstance().apply {setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"")}
        ZipFile(input).use {zip->ZipOutputStream(output.outputStream()).use {out->
            for(entry in zip.entries().asSequence()) {
                if(entry.isDirectory)continue
                if(entry.name.contains("vbaProject",true)||entry.name.contains("activeX",true)||entry.name.contains("/embeddings/",true))throw EtlException("매크로·ActiveX가 있는 문서는 자동 렌더링하지 않아요")
                val raw=zip.getInputStream(entry).use {it.readBytes()}
                val data=if(entry.name.endsWith(".rels")) {
                    val doc=parser.newDocumentBuilder().parse(raw.inputStream());val list=doc.getElementsByTagNameNS("*","Relationship")
                    for(i in list.length-1 downTo 0){val element=list.item(i) as org.w3c.dom.Element;if(element.getAttribute("TargetMode").equals("External",true))element.parentNode.removeChild(element)}
                    java.io.ByteArrayOutputStream().also {transformers.newTransformer().transform(DOMSource(doc),StreamResult(it))}.toByteArray()
                } else {
                    if(entry.name.startsWith("word/")&&entry.name.endsWith(".xml")) {
                        val doc=parser.newDocumentBuilder().parse(raw.inputStream());val simple=doc.getElementsByTagNameNS("*","fldSimple");for(i in 0 until simple.length){val element=simple.item(i) as org.w3c.dom.Element;val instruction=element.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","instr");if(Regex("\\b(DDE|DDEAUTO|INCLUDETEXT|INCLUDEPICTURE|DATABASE|MACROBUTTON|LINK)\\b",RegexOption.IGNORE_CASE).containsMatchIn(instruction))throw EtlException("외부 데이터를 읽는 Word 필드는 자동 렌더링하지 않아요")};val fields=doc.getElementsByTagNameNS("*","instrText")
                        for(i in 0 until fields.length)if(Regex("\\b(DDE|DDEAUTO|INCLUDETEXT|INCLUDEPICTURE|DATABASE|MACROBUTTON|LINK)\\b",RegexOption.IGNORE_CASE).containsMatchIn(fields.item(i).textContent))throw EtlException("외부 데이터를 읽는 Word 필드는 자동 렌더링하지 않아요")
                    }
                    raw
                }
                out.putNextEntry(ZipEntry(entry.name));out.write(data);out.closeEntry()
            }
        }}
    }
}
