package kr.snu.etlassistant.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class Role {
    USER, ASSISTANT, NOTICE,
    /** 비서가 받거나 만든 파일·이미지 묶음(왼쪽에 카드로 보인다). */
    FILES,
}

/**
 * 대화에 붙은 파일 하나. 실제 파일은 앱 작업 폴더([path])에 있고, 폰의 `Download/etl/` 사본이 있으면 [uri]도 있다.
 * 작업 폴더는 일정 기간이 지나면 정리되므로 파일이 없을 수 있다(화면은 "정리됨"으로 보여 준다).
 */
data class Attachment(
    val name: String,
    val mime: String,
    val size: Long,
    /** 앱 작업 폴더 안의 절대 경로. */
    val path: String?,
    /** 폰 사본(MediaStore content://). 다른 앱으로 열 때 먼저 쓴다. */
    val uri: String? = null,
    /** 폰 사본 위치 표시(`Download/etl/…`). */
    val shownPath: String? = null,
    /** 비서가 PDF를 읽으려고 만든 쪽 이미지인가(작은 미리보기로만 보인다). */
    val pagePreview: Boolean = false,
    /** 전달용 결과물이 아니라 비서가 내용을 확인하려고 받은 참고 자료. */
    val referenceOnly: Boolean = pagePreview,
) {
    val isImage: Boolean get() = mime.startsWith("image/")

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("mime", mime).put("size", size)
        .put("path", path ?: JSONObject.NULL).put("uri", uri ?: JSONObject.NULL)
        .put("shownPath", shownPath ?: JSONObject.NULL).put("pagePreview", pagePreview)
        .put("referenceOnly", referenceOnly)

    companion object {
        fun fromJson(o: JSONObject): Attachment? {
            val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return null
            fun s(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
            return Attachment(name, o.optString("mime", "application/octet-stream"), o.optLong("size"),
                s("path"), s("uri"), s("shownPath"), o.optBoolean("pagePreview", false),
                o.optBoolean("referenceOnly", o.optBoolean("pagePreview", false) || (s("uri") == null && s("shownPath") == null)))
        }
    }
}

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
    /** 사용자가 첨부한 파일(USER) 또는 비서가 받은 파일(FILES). */
    var attachments: List<Attachment> = emptyList(),
)

/**
 * 대화 상태. 화면(액티비티)이 사라져도 유지되는 싱글턴이 들고 있다.
 * **메인 스레드에서만** 만지고, 바뀌면 [listener]로 알린다.
 */
class ChatModel(file: File?) {

    /** 지금 대화의 저장 파일. [switchTo]로 다른 대화로 바꾼다. */
    var file: File? = file
        private set

    interface Listener {
        fun onAdded(index: Int)
        fun onChanged(index: Int)
        fun onReset()
    }

    val messages = mutableListOf<Message>()
    var listener: Listener? = null
    private var nextId = 1L

    // ---- 변경 ---------------------------------------------------------------

    fun addUser(text: String, attachments: List<Attachment> = emptyList()): Message =
        add(Message(nextId++, Role.USER, text, attachments = attachments))

    /**
     * 비서가 받은 파일을 대화에 붙인다. 바로 앞 줄이 파일 묶음이면 거기에 더한다(한 답변에서 여러 개를 받는 경우).
     * 같은 경로의 파일은 다시 붙이지 않는다.
     */
    fun addFiles(files: List<Attachment>) {
        if (files.isEmpty()) return
        val last = messages.lastOrNull()
        if (last != null && last.role == Role.FILES) {
            val known = last.attachments.mapNotNull { it.path }.toSet()
            val fresh = files.filter { it.path == null || it.path !in known }
            if (fresh.isEmpty()) return
            last.attachments = last.attachments + fresh
            listener?.onChanged(messages.size - 1)
        } else {
            add(Message(nextId++, Role.FILES, "", attachments = files))
        }
        save()
    }

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

    /** 다른 대화로 바꾼다: 메시지를 비우고 [newFile]에서 읽는다(파일이 없으면 빈 대화). */
    fun switchTo(newFile: File?) {
        file = newFile
        messages.clear()
        load()
        listener?.onReset()
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

    /** 사용자와 어시스턴트의 말, 파일 묶음만 저장한다(안내문은 저장하지 않는다). 최근 [MAX_SAVED]개. */
    fun save() {
        val f = file ?: return
        val arr = JSONArray()
        messages.filter { it.role in SAVED_ROLES }
            .takeLast(MAX_SAVED)
            .forEach { m ->
                val o = JSONObject().put("role", m.role.name).put("text", m.text)
                if (m.attachments.isNotEmpty()) o.put("attachments", JSONArray().also { a -> m.attachments.forEach { a.put(it.toJson()) } })
                arr.put(o)
            }
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
                if (role !in SAVED_ROLES) continue
                val atts = o.optJSONArray("attachments")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(Attachment::fromJson) }
                } ?: emptyList()
                if (role == Role.FILES && atts.isEmpty()) continue
                messages += Message(nextId++, role, o.optString("text"), status = "done", attachments = atts)
            }
            listener?.onReset()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_SAVED = 200
        private val SAVED_ROLES = setOf(Role.USER, Role.ASSISTANT, Role.FILES)
    }
}
