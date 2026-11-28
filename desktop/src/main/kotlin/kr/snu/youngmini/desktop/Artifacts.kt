package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import org.apache.pdfbox.Loader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Artifact(val id:String=UUID.randomUUID().toString(),val name:String,val path:String,val bytes:Long,val kind:String,val messageId:String?=null) {
    fun json() = JSONObject().put("id",id).put("name",name).put("path",path).put("bytes",bytes).put("kind",kind).put("messageId",messageId?:JSONObject.NULL)
    companion object { fun parse(o:JSONObject)=Artifact(o.getString("id"),o.getString("name"),o.getString("path"),o.optLong("bytes"),o.optString("kind","requested"),o.opt("messageId") as? String) }
}

/** Each thread receives its own unguessable capability. It is never part of eTL data or UI state. */
class ToolContexts {
    data class Context(val key:String,val chatId:String,val project:Project,@Volatile var active:Boolean=true)
    private val contexts=ConcurrentHashMap<String,Context>()
    private val byChat=ConcurrentHashMap<String,String>()
    var onArtifact:(String,Artifact)->Unit={_,_->}
    @Synchronized fun register(chatId:String,project:Project):Context {
        byChat.remove(chatId)?.let {contexts.remove(it)}
        val c=Context(UUID.randomUUID().toString()+UUID.randomUUID(),chatId,project)
        contexts[c.key]=c;byChat[chatId]=c.key;return c
    }
    fun activate(chatId:String,value:Boolean) {byChat[chatId]?.let {contexts[it]?.active=value}}
    fun resolve(key:String):Context=contexts[key]?.takeIf {it.active}?:throw EtlException("대화 연결이 변경됐어요. 다시 질문해 주세요.")
    fun emit(context:Context,artifact:Artifact) {if(contexts[context.key]===context && context.active) onArtifact(context.chatId,artifact)}
    fun instructions(c:Context):String = """
        현재 프로젝트: ${c.project.name}
        기본 자료 범위: ${if(c.project.kind=="integrated") "2026년 2학기 전체 과목" else if(c.project.courseIds.isEmpty()) "연결한 eTL 과목 없음. 일반 대화 가능" else "과목 ID ${c.project.courseIds.joinToString()}"}
        모든 앱 도구의 context 인자는 정확히 ${c.key} 를 사용한다. 이 내부 라우팅 값은 사용자에게 표시하지 않는다.
        같은 프로젝트의 자료와 아래 공통 지침을 사용하되 다른 세션의 대화 내용은 알지 못한다.
        공통 지침: ${c.project.instructions}
    """.trimIndent()
}

class PdfJobs(private val paths:AppPaths) {
    data class Job(val id:String,val name:String,val markdown:String,val target:File,val future:CompletableFuture<Boolean>)
    private val jobs=ConcurrentHashMap<String,Job>()
    var onRequest:(Job)->Unit={throw EtlException("PDF 렌더러가 연결되지 않았어요")}
    fun create(name:String,markdown:String):Artifact {
        if(markdown.isBlank() || markdown.length>300_000) throw EtlException("PDF 내용은 1~300,000자여야 해요")
        val safe=Materials.windowsFileName(name,"학습자료.pdf").removeSuffix(".pdf").take(100)+".pdf"
        val dir=File(paths.workspace,"generated").apply {mkdirs()}
        val id=UUID.randomUUID().toString();val target=File(dir,"$id-$safe")
        val job=Job(id,safe,markdown,target,CompletableFuture())
        jobs[id]=job
        try {
            onRequest(job)
            if(!job.future.get(120,TimeUnit.SECONDS) || !target.isFile) throw EtlException("PDF를 만들지 못했어요. 다시 시도해 주세요.")
            if(target.length()>20L*1024*1024) throw EtlException("PDF가 20MB보다 커요. 내용을 나눠 주세요.")
            Loader.loadPDF(target).use {if(it.numberOfPages>200)throw EtlException("PDF가 200쪽보다 커요. 내용을 나눠 주세요.")}
            return Artifact(id,safe,Materials.portablePath(paths.workspace,target),target.length(),"generated")
        } catch(e:EtlException) {target.delete();throw e}
        catch(_:Exception) {target.delete();throw EtlException("PDF 생성 시간이 초과됐거나 연결이 끊어졌어요")}
        finally {jobs.remove(id)}
    }
    fun complete(id:String,success:Boolean) {jobs[id]?.future?.complete(success)}
    fun close() {jobs.values.forEach {it.future.complete(false)};jobs.clear()}
}
