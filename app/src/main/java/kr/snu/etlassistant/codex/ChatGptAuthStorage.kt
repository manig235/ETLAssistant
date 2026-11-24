package kr.snu.etlassistant.codex

import android.content.Context
import kr.snu.etlassistant.etl.ChatGptSecretStore
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Persist ciphertext before deleting the legacy plaintext. Never restore plaintext to CODEX_HOME. */
object ChatGptAuthStorage {
    internal val lock = Any()

    fun prepare(context: Context) = synchronized(lock) {
        val store = ChatGptSecretStore(context)
        migrate(File(context.filesDir, "codex-home"), store::load, store::save)
    }

    fun clear(context: Context) = synchronized(lock) {
        ChatGptSecretStore(context).clear()
        val home = File(context.filesDir, "codex-home")
        if (home.exists()) {
            val files = home.listFiles() ?: throw IOException("기존 로그인 정보를 지우지 못했어요")
            files.filter { it.name == "auth.json" || it.name.startsWith(".auth-") }.forEach {
                if (!it.delete()) throw IOException("기존 로그인 정보를 지우지 못했어요")
            }
        }
    }

    internal fun migrate(home: File, load: () -> String?, save: (String) -> Unit, deleteLegacy: (File) -> Boolean = { it.delete() }) {
        if (!home.exists()) return
        val files = home.listFiles() ?: throw IOException("기존 로그인 정보의 보안 전환을 확인하지 못했어요")
        val legacy = files.filter { it.name == "auth.json" || it.name.startsWith(".auth-") }
        if (legacy.isEmpty()) return
        if (legacy.any { !it.isFile || Files.isSymbolicLink(it.toPath()) || it.length() > 1024 * 1024 }) {
            throw IOException("기존 로그인 정보의 보안 전환을 확인하지 못했어요")
        }
        val stored = load()
        if (stored != null) ChatGptCredentials.parse(stored) // Verify existing ciphertext before deleting the only usable legacy grant.
        if (stored == null) {
            val documents = legacy.sortedByDescending { it.lastModified() }.map { it.readText() }
            val candidate = documents.firstNotNullOfOrNull { document -> runCatching { ChatGptCredentials.parse(document).json() }.getOrNull() }
                ?: throw IOException("기존 ChatGPT 로그인 정보를 전환하지 못했어요. 로그아웃 후 다시 로그인해 주세요.")
            run {
                save(candidate) // Must synchronously commit ciphertext; exceptions leave the original intact.
                if (load() != candidate) throw IOException("암호화한 로그인 정보를 확인하지 못했어요")
            }
        }
        legacy.forEach { if (!deleteLegacy(it)) throw IOException("기존 로그인 정보를 안전하게 전환하지 못했어요") }
    }
}
