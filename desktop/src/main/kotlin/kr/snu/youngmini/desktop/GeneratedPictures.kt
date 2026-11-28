package kr.snu.youngmini.desktop

import org.json.JSONObject
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/** Engine events only; text links and arbitrary PC paths do not import files. */
class GeneratedPictures(private val paths:AppPaths,private val files:OfficeFiles) {
    fun capture(item:JSONObject):Artifact?=runCatching {
        if(item.optString("type")!="imageGeneration"||item.optString("status")!="completed")return null
        val encoded=item.optString("result");val source=item.optString("savedPath").takeIf {it.isNotBlank()&&it!="null"}
        val bytes=if(source!=null){val file=File(source).toPath().toRealPath().toFile();val allowed=listOf(File(paths.workspace,"generated"),File(paths.codexHome,"generated_images")).any {it.exists()&&file.toPath().startsWith(it.toPath().toRealPath())};if(!allowed||!file.isFile||file.length() !in 1..12L*1024*1024)return null;file.readBytes()}else{if(encoded.length !in 1..16*1024*1024)return null;Base64.getDecoder().decode(encoded)}
        if(bytes.size !in 1..12*1024*1024)return null
        val png=byteArrayOf(-119,80,78,71,13,10,26,10);if(!bytes.take(8).toByteArray().contentEquals(png)&&!bytes.take(4).toByteArray().contentEquals("GIF8".toByteArray()))return null
        ImageIO.createImageInputStream(bytes.inputStream()).use {input->val reader=ImageIO.getImageReaders(input).asSequence().firstOrNull()?:return null;try{reader.input=input;if(reader.getWidth(0).toLong()*reader.getHeight(0) !in 1..20000000)return null;val image=reader.read(0);try {files.generated("생성한 이미지.png","png"){ImageIO.write(image,"png",it)}}finally{image.flush()}}finally{reader.dispose()}}
    }.getOrNull()
}
