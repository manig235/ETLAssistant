package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Approves one immutable local operation. eTL writes and arbitrary scripts are never accepted. */
class LocalApprovals(private val timeoutMillis:Long=90000) {
    private data class Pending(val id:String,val chatId:String,val title:String,val preview:String,val created:Long,val result:CompletableFuture<Boolean>)
    private val pending=ConcurrentHashMap<String,Pending>()
    var onChange:()->Unit={}
    fun snapshot()=JSONArray(pending.values.sortedBy {it.created}.map {JSONObject().put("id",it.id).put("chatId",it.chatId).put("title",it.title).put("preview",it.preview).put("expiresAt",it.created+timeoutMillis)})
    fun decide(id:String,approved:Boolean,activeChatId:String){val p=pending[id]?:return;if(p.chatId!=activeChatId)throw EtlException("해당 대화를 연 뒤 확인해 주세요");p.result.complete(approved)}
    fun cancel(chatId:String?=null){pending.values.filter {chatId==null||it.chatId==chatId}.forEach {it.result.complete(false)}}
    fun execute(chatId:String,title:String,preview:String,check:()->Unit,action:()->Any):Any {
        require(preview.length<=12000);check()
        val request=Pending(UUID.randomUUID().toString(),chatId,title,preview,System.currentTimeMillis(),CompletableFuture())
        pending[request.id]=request;onChange()
        try {
            val accepted=try {request.result.get(timeoutMillis,TimeUnit.MILLISECONDS)}catch(_:Exception){throw EtlException("확인 시간이 지나 취소했어요. 필요하면 사용자에게 다시 요청해 주세요.")}
            if(!accepted)throw EtlException("사용자가 확인을 취소했거나 대화를 벗어났어요. 변경하지 않았어요.")
            check();return action()
        }finally{pending.remove(request.id);onChange()}
    }
}
