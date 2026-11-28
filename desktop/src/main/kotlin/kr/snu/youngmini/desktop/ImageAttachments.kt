package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import java.io.File
import javax.imageio.ImageIO

/** Only shell-imported, normalized PNG copies can become localImage inputs. */
class ImageAttachments(private val workspace:File) {
    fun file(image:Artifact):File {
        if(image.kind=="document-attachment")return document(image)
        if(image.kind!="attachment"||!Regex("[a-f0-9-]{36}").matches(image.id)||image.path!="uploads/${image.id}.png")
            throw EtlException("사진 첨부 정보를 확인하지 못했어요. 다시 첨부해 주세요.")
        val root=File(workspace,"uploads").toPath().toRealPath()
        val file=File(workspace,image.path).toPath().toRealPath().toFile()
        if(!root.startsWith(workspace.toPath().toRealPath())||!file.toPath().startsWith(root)||!file.isFile||file.length() !in 1..20L*1024*1024)
            throw EtlException("첨부 사진이 없거나 20MB를 넘어요. 다시 첨부해 주세요.")
        ImageIO.createImageInputStream(file).use { input->
            val readers=ImageIO.getImageReaders(input)
            if(!readers.hasNext())throw EtlException("사진을 읽지 못했어요. PNG 또는 JPG로 다시 첨부해 주세요.")
            val reader=readers.next()
            try {
                reader.input=input
                if(!reader.formatName.equals("png",true)||reader.getWidth(0).toLong()*reader.getHeight(0) !in 1..20_000_000L)
                    throw EtlException("사진은 2천만 화소 이하로 첨부해 주세요.")
            } finally {reader.dispose()}
        }
        return file
    }
    private fun document(a:Artifact):File {
        val ext=a.path.substringAfterLast('.').lowercase()
        if(ext !in listOf("pdf","docx","xlsx","pptx","hwp","hwpx","txt","md","csv","tsv","json","tex","ics")||!Regex("[a-f0-9-]{36}").matches(a.id)||a.path!="uploads/${a.id}.$ext")throw EtlException("문서 첨부 정보를 확인해 주세요")
        val root=File(workspace,"uploads").toPath().toRealPath();val f=File(workspace,a.path).toPath().toRealPath().toFile()
        if(!root.startsWith(workspace.toPath().toRealPath())||!f.toPath().startsWith(root)||!f.isFile||f.length() !in 1..20L*1024*1024)throw EtlException("첨부 파일이 없거나 20MB를 넘어요")
        return f
    }
    fun validate(images:List<Artifact>) {
        if(images.size>5||images.distinctBy {it.id}.size!=images.size||images.sumOf {it.bytes}>50L*1024*1024)
            throw EtlException("사진은 한 번에 5장, 합계 50MB까지 첨부할 수 있어요.")
        images.forEach {if(file(it).length()!=it.bytes)throw EtlException("첨부 사진이 변경됐어요. 다시 첨부해 주세요.")}
    }
}
