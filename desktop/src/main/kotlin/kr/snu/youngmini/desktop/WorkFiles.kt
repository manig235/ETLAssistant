package kr.snu.youngmini.desktop

import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Only old unreferenced managed files. Files.walk does not follow symbolic links. */
object WorkFiles {
    fun cleanup(workspace:File,referenced:Set<String>,days:Int,now:Long=System.currentTimeMillis()):JSONObject {
        require(days in listOf(2,7,30));val root=workspace.toPath().toRealPath();val cutoff=now-days*86400000L
        val keep=referenced.mapNotNull {runCatching {File(workspace,it).toPath().toRealPath()}.getOrNull()}.toSet()
        var count=0;var bytes=0L
        for(name in listOf("etl-files","generated","uploads")) {
            val dir=root.resolve(name);if(!Files.isDirectory(dir)||Files.isSymbolicLink(dir)||!dir.toRealPath().startsWith(root))continue
            Files.walk(dir).use {stream->stream.filter {Files.isRegularFile(it,java.nio.file.LinkOption.NOFOLLOW_LINKS)}.forEach {p->
                if(p.toRealPath().startsWith(dir)&&p.toRealPath() !in keep&&Files.getLastModifiedTime(p).toMillis()<cutoff)runCatching {val size=Files.size(p);Files.delete(p);count++;bytes+=size}
            }}
        }
        return JSONObject().put("count",count).put("bytes",bytes).put("at",now)
    }
}
