package kr.snu.youngmini.desktop

import com.sun.jna.platform.win32.Crypt32Util
import kr.snu.etlassistant.codex.ChatGptCredentials
import kr.snu.etlassistant.codex.ChatGptOAuth
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object Platform {
    val windows = System.getProperty("os.name").startsWith("Windows", true)
}

class AppPaths(val root: File = defaultRoot()) {
    val workspace = File(root, "workspace").apply { mkdirs() }
    val codexHome = File(root, "codex-home").apply { mkdirs() }
    val secrets = File(root, "secrets").apply { mkdirs() }
    val chats = File(root, "chats").apply { mkdirs() }

    /** Prevent two app processes from racing token rotation, runtime install, or history writes. */
    fun lock(): AutoCloseable {
        val channel = FileChannel.open(File(root, "app.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try { channel.tryLock() } catch (_: Exception) { null }
        if (lock == null) { channel.close(); throw IOException("영민이가 이미 실행 중이에요. 열려 있는 창을 확인해 주세요.") }
        return AutoCloseable { lock.release(); channel.close() }
    }

    companion object {
        private fun defaultRoot(): File = if (Platform.windows)
            File(System.getenv("LOCALAPPDATA") ?: throw IOException("Windows 사용자 폴더를 찾지 못했어요"), "YoungMini")
        else File(System.getProperty("user.home"), ".local/share/youngmini-desktop")
    }
}

internal fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile.mkdirs()
    val temp = Files.createTempFile(file.parentFile.toPath(), ".youngmini-", ".tmp")
    try {
        Files.write(temp, bytes)
        try { Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

/** Current-user DPAPI: neither a plaintext fallback nor a separately stored encryption key. */
class SecretVault(private val directory: File) {
    // Linux development sessions deliberately keep credentials in memory only.
    private val memory = mutableMapOf<String, String>()
    private fun file(name: String): File {
        require(name in setOf("etl", "chatgpt"))
        return File(directory, "$name.dpapi")
    }
    @Synchronized fun read(name: String): String? {
        val file = file(name)
        if (!Platform.windows) return memory[name]
        if (!file.exists()) return null
        return try { Crypt32Util.cryptUnprotectData(file.readBytes()).toString(Charsets.UTF_8) }
        catch (_: Exception) { throw IOException("저장된 로그인을 열지 못했어요. 연결을 해제한 뒤 다시 로그인해 주세요.") }
    }
    @Synchronized fun write(name: String, value: String) {
        val file = file(name)
        if (!Platform.windows) { memory[name] = value; return }
        try {
            val protected = Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))
            // Verify the encrypted value before replacing a working grant.
            check(Crypt32Util.cryptUnprotectData(protected).toString(Charsets.UTF_8) == value)
            atomicWrite(file, protected)
        } catch (_: Exception) { throw IOException("로그인 정보를 Windows에 암호화해 저장하지 못했어요") }
    }
    @Synchronized fun clear(name: String) { memory.remove(name); Files.deleteIfExists(file(name).toPath()) }
}

class DesktopAuth(private val vault: SecretVault) {
    @Synchronized fun hasLogin() = vault.read("chatgpt") != null
    @Synchronized fun save(credentials: ChatGptCredentials) = vault.write("chatgpt", credentials.json())
    @Synchronized fun clear() = vault.clear("chatgpt")
    @Synchronized fun credentials(force: Boolean = false, expectedAccount: String? = null): ChatGptCredentials? {
        val old = vault.read("chatgpt")?.let(ChatGptCredentials::parse) ?: return null
        if (expectedAccount != null && old.accountId != expectedAccount) throw IOException("계정이 변경됐어요. 다시 로그인해 주세요.")
        if (!force && old.expiresAt > System.currentTimeMillis() / 1000 + 120) return old
        return ChatGptOAuth.refresh(old).also(::save)
    }
}

data class ChatMessage(val id: String, val role: String, val text: String)
data class SavedChat(val id: String, val title: String, val thread: String?, val messages: List<ChatMessage>,
    val model: String? = null, val effort: String? = null, val files: List<Downloaded> = emptyList(), val draft: String = "", val updatedAt: Long = 0,
    val projectId:String="integrated",val artifacts:List<Artifact> = emptyList(),val draftImages:List<Artifact> = emptyList())

class ChatHistory(private val directory: File) {
    @Synchronized fun delete(id:String) {
        require(Regex("[a-f0-9-]{36}").matches(id))
        Files.deleteIfExists(File(directory,"$id.json").toPath())
    }
    fun list(): List<SavedChat> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .sortedByDescending { it.lastModified() }.mapNotNull { runCatching { read(it) }.getOrNull() }
    @Synchronized fun save(chat: SavedChat) {
        require(Regex("[a-f0-9-]{36}").matches(chat.id))
        val value = JSONObject().put("id", chat.id).put("title", chat.title).put("thread", chat.thread ?: JSONObject.NULL)
            .put("model", chat.model ?: JSONObject.NULL).put("effort", chat.effort ?: JSONObject.NULL)
            .put("files", JSONArray(chat.files.map { it.json() })).put("draft", chat.draft).put("updated_at", chat.updatedAt)
            .put("projectId",chat.projectId).put("artifacts",JSONArray(chat.artifacts.map {it.json()})).put("draftImages",JSONArray(chat.draftImages.map {it.json()}))
            .put("messages", JSONArray(chat.messages.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text) }))
        atomicWrite(File(directory, "${chat.id}.json"), value.toString().toByteArray(Charsets.UTF_8))
    }
    private fun read(file: File): SavedChat {
        require(file.length() <= 16L * 1024 * 1024)
        val value = JSONObject(file.readText())
        require(Regex("[a-f0-9-]{36}").matches(value.getString("id")))
        val messages = value.getJSONArray("messages")
        val files = value.optJSONArray("files") ?: JSONArray()
        return SavedChat(value.getString("id"), value.getString("title"), value.opt("thread") as? String,
            (0 until messages.length()).map { messages.getJSONObject(it).let { ChatMessage(it.getString("id"), it.getString("role"), it.getString("text")) } },
            value.opt("model") as? String, value.opt("effort") as? String,
            (0 until files.length()).map { files.getJSONObject(it).let { f -> Downloaded(f.getLong("file_id"), f.getString("name"), f.getString("workspace_path"), f.getLong("bytes")) } },
            value.optString("draft"), value.optLong("updated_at", 0),value.optString("projectId","integrated"),
            value.optJSONArray("artifacts")?.let {a->(0 until a.length()).map {Artifact.parse(a.getJSONObject(it))}}.orEmpty(),
            value.optJSONArray("draftImages")?.let {a->(0 until a.length()).map {Artifact.parse(a.getJSONObject(it))}}.orEmpty())
    }
}
