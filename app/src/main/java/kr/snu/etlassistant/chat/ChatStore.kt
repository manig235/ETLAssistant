package kr.snu.etlassistant.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 대화 하나의 색인 항목. 메시지는 `chats/{id}.json`에 따로 있다([ChatModel] 형식). */
data class ChatMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 이 대화의 Codex 스레드. 처음 보낼 때 정해진다. */
    val threadId: String? = null,
    /** 사용자가 이름을 직접 바꿨는가(그러면 자동 제목으로 덮지 않는다). */
    val renamed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("threadId", threadId ?: JSONObject.NULL).put("renamed", renamed)

    companion object {
        fun fromJson(o: JSONObject): ChatMeta? {
            val id = o.optString("id").takeIf { it.isNotEmpty() && ChatStore.isSafeId(it) } ?: return null
            return ChatMeta(
                id = id,
                title = o.optString("title").ifEmpty { ChatTitles.DEFAULT },
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                threadId = if (o.isNull("threadId")) null else o.optString("threadId").takeIf { it.isNotEmpty() },
                renamed = o.optBoolean("renamed", false),
            )
        }
    }
}

/** 대화 제목과 목록 묶음 규칙(순수 함수). */
object ChatTitles {
    const val DEFAULT = "새 대화"
    const val MAX_LEN = 40

    /** 첫 사용자 메시지로 제목을 만든다. 글이 없으면 첨부를 요약한다("사진 1장", "파일: 보고서.pdf"). */
    fun from(text: String, attachments: List<Attachment>): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isNotEmpty()) return cut(t)
        val images = attachments.count { it.isImage }
        val files = attachments.filter { !it.isImage }
        return when {
            images > 0 && files.isEmpty() -> "사진 ${images}장"
            images == 0 && files.size == 1 -> cut("파일: " + files[0].name)
            images == 0 && files.size > 1 -> "파일 ${files.size}개"
            images > 0 -> "사진 ${images}장, 파일 ${files.size}개"
            else -> DEFAULT
        }
    }

    fun cut(s: String, max: Int = MAX_LEN): String {
        val t = s.trim()
        if (t.length <= max) return t
        var end = max - 1
        if (Character.isHighSurrogate(t[end - 1])) end--
        return t.substring(0, end).trimEnd() + "…"
    }

    enum class Group(val label: String) { TODAY("오늘"), YESTERDAY("어제"), WEEK("지난 7일"), OLDER("이전") }

    fun groupOf(updatedAt: Long, now: Long, zone: ZoneId): Group {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day: LocalDate = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
        return when {
            !day.isBefore(today) -> Group.TODAY
            day == today.minusDays(1) -> Group.YESTERDAY
            !day.isBefore(today.minusDays(6)) -> Group.WEEK
            else -> Group.OLDER
        }
    }

    /** 최근 순으로 정렬해 날짜 묶음별로 나눈다(빈 묶음은 뺀다). */
    fun grouped(list: List<ChatMeta>, now: Long, zone: ZoneId): List<Pair<Group, List<ChatMeta>>> =
        list.sortedByDescending { it.updatedAt }
            .groupBy { groupOf(it.updatedAt, now, zone) }
            .toSortedMap(compareBy { it.ordinal })
            .map { (g, items) -> g to items }
}

/**
 * 대화 목록 저장소. `dir/index.json`에 색인, `dir/{id}.json`에 대화마다 메시지를 둔다.
 * 빈 대화(아직 아무것도 보내지 않은 대화)는 색인에 넣지 않는다. 여러 스레드에서 부르므로 잠근다.
 */
class ChatStore(private val dir: File, private val attachmentRoot: File? = null) {
    private val items = mutableListOf<ChatMeta>()

    @Synchronized
    fun load() {
        items.clear()
        val f = File(dir, INDEX)
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(ChatMeta::fromJson)?.let { items += it }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun list(): List<ChatMeta> = items.sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(id: String): ChatMeta? = items.firstOrNull { it.id == id }

    fun fileFor(id: String): File = File(dir, "$id.json")

    fun newId(): String = UUID.randomUUID().toString()

    /** 사용자가 메시지를 보냈다: 색인에 없으면(첫 메시지) 제목을 붙여 넣고, 있으면 수정 시각만 올린다. */
    @Synchronized
    fun noteUserMessage(id: String, text: String, attachments: List<Attachment>, now: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) items += ChatMeta(id, ChatTitles.from(text, attachments), now, now)
        else items[i] = items[i].copy(updatedAt = now)
        save()
    }

    @Synchronized
    fun setThread(id: String, threadId: String?) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = items[i].copy(threadId = threadId)
        save()
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val i = items.indexOfFirst { it.id == id }
        val t = ChatTitles.cut(title.replace(Regex("\\s+"), " "))
        if (i < 0 || t.isEmpty()) return
        items[i] = items[i].copy(title = t, renamed = true)
        save()
    }

    /** 색인과 메시지 파일을 지운다. 지운 항목(스레드 정리용)을 돌려준다. */
    @Synchronized
    fun delete(id: String): ChatMeta? {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return null
        val attachments = attachmentRoot?.let { kr.snu.etlassistant.files.WorkFiles.attachments(fileFor(id)) }
        val removed = items.removeAt(i)
        fileFor(id).delete()
        save()
        if (attachmentRoot != null && attachments != null) {
            // 저장 실패로 남은 대화도 참조에 포함해 다른 대화의 파일을 잘못 지우지 않는다.
            val others = dir.listFiles()?.filter { it.extension == "json" && it.name != INDEX && it.name != "$id.json" }
            if (others != null) kr.snu.etlassistant.files.WorkFiles.deleteUnshared(attachmentRoot, attachments, others)
        }
        return removed
    }

    /**
     * 예전 형식(대화 하나: `chat.json` + 설정의 threadId)을 첫 대화로 옮긴다. 색인이 비어 있고 예전 파일에 메시지가 있을 때만.
     * 옮겼으면 새 대화 ID를, 아니면 null을 돌려준다. 예전 파일은 옮긴 뒤 지운다.
     */
    @Synchronized
    fun migrateLegacy(legacy: File, legacyThreadId: String?, now: Long): String? {
        if (items.isNotEmpty() || !legacy.exists()) return null
        val probe = ChatModel(legacy).also { it.load() }
        val firstUser = probe.messages.firstOrNull { it.role == Role.USER }
        if (probe.messages.isEmpty() || firstUser == null) {
            legacy.delete()
            return null
        }
        val id = newId()
        dir.mkdirs()
        try {
            legacy.copyTo(fileFor(id), overwrite = true)
        } catch (_: IOException) {
            return null
        }
        val stamp = legacy.lastModified().takeIf { it > 0 } ?: now
        items += ChatMeta(id, ChatTitles.from(firstUser.text, firstUser.attachments), stamp, stamp, legacyThreadId)
        save()
        legacy.delete()
        return id
    }

    private fun save() {
        dir.mkdirs()
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        try {
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(File(dir, INDEX))) File(dir, INDEX).writeText(arr.toString())
        } catch (_: IOException) {
        }
    }

    companion object {
        const val INDEX = "index.json"
        private val SAFE = Regex("^[A-Za-z0-9-]{1,64}$")

        /** 파일 이름으로 쓸 수 있는 ID인가(색인이 망가져도 다른 경로를 가리키지 않게). */
        fun isSafeId(id: String) = SAFE.matches(id)
    }
}
