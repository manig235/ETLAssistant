package kr.snu.etlassistant.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class Role { USER, ASSISTANT, NOTICE }

/**
 * 화면에 보이는 대화 한 줄. 도구 호출은 대화에 남기지 않는다(진행 상태는 [ActivityTracker]가 한 줄로 알려 준다).
 */
class Message(
    val id: Long,
    val role: Role,
    var text: String,
    var status: String? = null,
    var error: String? = null,
    /** 서버 쪽 항목 ID(스트리밍 중인 어시스턴트 메시지나 도구 호출을 찾는 데 쓴다). */
    val itemId: String? = null,
)

/**
 * 대화 상태. 화면(액티비티)이 사라져도 유지되는 싱글턴이 들고 있다.
 * **메인 스레드에서만** 만지고, 바뀌면 [listener]로 알린다.
 */
class ChatModel(private val file: File?) {

    interface Listener {
        fun onAdded(index: Int)
        fun onChanged(index: Int)
        fun onReset()
    }

    val messages = mutableListOf<Message>()
    var listener: Listener? = null
    private var nextId = 1L

    // ---- 변경 ---------------------------------------------------------------

    fun addUser(text: String): Message = add(Message(nextId++, Role.USER, text))

    fun addNotice(text: String): Message = add(Message(nextId++, Role.NOTICE, text))

    /** 스트리밍 중인 어시스턴트 메시지에 글을 덧붙인다. 없으면 만든다. */
    fun appendAssistant(itemId: String, delta: String) {
        val i = indexOfItem(itemId, Role.ASSISTANT)
        if (i < 0) {
            add(Message(nextId++, Role.ASSISTANT, delta, status = "streaming", itemId = itemId))
        } else {
            messages[i].text += delta
            listener?.onChanged(i)
        }
    }

    /** 어시스턴트 메시지를 최종 본문으로 확정한다. */
    fun finishAssistant(itemId: String, fullText: String) {
        val i = indexOfItem(itemId, Role.ASSISTANT)
        if (i < 0) {
            if (fullText.isNotEmpty()) add(Message(nextId++, Role.ASSISTANT, fullText, status = "done", itemId = itemId))
        } else {
            messages[i].text = fullText.ifEmpty { messages[i].text }
            messages[i].status = "done"
            listener?.onChanged(i)
        }
        save()
    }

    /** 턴이 끝났을 때: 아직 스트리밍 상태로 남은 메시지를 닫는다(중단된 경우 등). */
    fun closeStreaming() {
        var changed = false
        for ((i, m) in messages.withIndex()) {
            if (m.role == Role.ASSISTANT && m.status == "streaming") {
                m.status = "done"
                listener?.onChanged(i)
                changed = true
            }
        }
        if (changed) save()
    }

    fun clear() {
        messages.clear()
        listener?.onReset()
        save()
    }

    private fun add(m: Message): Message {
        messages += m
        listener?.onAdded(messages.size - 1)
        if (m.role == Role.USER) save()
        return m
    }

    private fun indexOfItem(itemId: String, role: Role): Int =
        messages.indexOfLast { it.itemId == itemId && it.role == role }

    // ---- 저장 ---------------------------------------------------------------

    /** 사용자와 어시스턴트의 말만 저장한다(도구 호출과 안내문은 저장하지 않는다). 최근 [MAX_SAVED]개. */
    fun save() {
        val f = file ?: return
        val arr = JSONArray()
        messages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .takeLast(MAX_SAVED)
            .forEach { arr.put(JSONObject().put("role", it.role.name).put("text", it.text)) }
        try {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) f.writeText(arr.toString())
        } catch (_: IOException) {
        }
    }

    fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            messages.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = runCatching { Role.valueOf(o.optString("role")) }.getOrNull() ?: continue
                if (role != Role.USER && role != Role.ASSISTANT) continue
                messages += Message(nextId++, role, o.optString("text"), status = "done")
            }
            listener?.onReset()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_SAVED = 200
    }
}
