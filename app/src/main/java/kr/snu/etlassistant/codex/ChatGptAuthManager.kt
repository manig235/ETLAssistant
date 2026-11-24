package kr.snu.etlassistant.codex

import android.content.Context
import kr.snu.etlassistant.etl.ChatGptSecretStore
import java.io.IOException

internal interface ChatGptVault {
    fun load(): String?
    fun save(value: String)
    fun clear()
}

/** Serializes refresh rotation and rejects results from canceled logins or a logged-out account. */
internal class ChatGptAuthManager(
    private val vault: ChatGptVault,
    private val refresh: (ChatGptCredentials) -> ChatGptCredentials = ChatGptOAuth::refresh,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    constructor(context: Context) : this(object : ChatGptVault {
        private val store = ChatGptSecretStore(context)
        override fun load() = synchronized(ChatGptAuthStorage.lock) {
            store.load().also { if (it == null && store.has()) throw IOException("암호화한 ChatGPT 로그인을 읽지 못했어요. 로그아웃 후 다시 로그인해 주세요.") }
        }
        override fun save(value: String) = synchronized(ChatGptAuthStorage.lock) { store.save(value) }
        override fun clear() = ChatGptAuthStorage.clear(context)
    })

    private val lock = Any()
    private val refreshLock = Any()
    private var generation = 0L
    private var login: ChatGptOAuth.Attempt? = null
    private data class Pending(val generation: Long, val before: String, val next: ChatGptCredentials)
    private var pending: Pending? = null
    private data class Staged(val attempt: ChatGptOAuth.Attempt, val before: String?, val after: String)
    private var staged: Staged? = null

    fun newLogin(): ChatGptOAuth.Attempt = synchronized(lock) {
        login?.cancel()
        generation++
        pending = null
        ChatGptOAuth.Attempt().also { login = it }
    }

    fun cancelLogin() = synchronized(lock) {
        login?.cancel()
        login = null
        generation++
        pending = null
    }

    fun finishLogin(attempt: ChatGptOAuth.Attempt, credentials: ChatGptCredentials): Boolean = synchronized(lock) {
        if (login !== attempt) return false
        attempt.check()
        val before = vault.load()
        vault.save(credentials.json()) // Return success only after the encrypted blob reaches disk.
        staged = Staged(attempt, before, credentials.json())
        pending = null
        true
    }

    fun confirmLogin(attempt: ChatGptOAuth.Attempt): Boolean = synchronized(lock) {
        if (login !== attempt) return false
        login = null
        staged = null
        true
    }

    fun rollbackLogin(attempt: ChatGptOAuth.Attempt) = synchronized(lock) {
        val saved = staged
        if (saved?.attempt === attempt) {
            if (vault.load() == saved.after) {
                if (saved.before == null) vault.clear() else try { vault.save(saved.before) } catch (_: Exception) {
                    // A canceled new account must not survive a failure to restore the old grant.
                    vault.clear()
                    throw IOException("이전 로그인 복원에 실패해 로그아웃했어요")
                }
            }
            staged = null
        }
    }

    fun clear(): ChatGptCredentials? = synchronized(lock) {
        login?.cancel()
        login = null
        generation++
        pending = null
        staged = null
        val old = runCatching { vault.load()?.let(ChatGptCredentials::parse) }.getOrNull()
        vault.clear()
        old
    }

    fun credentials(forceRefresh: Boolean = false, previousAccountId: String? = null): ChatGptCredentials? = synchronized(refreshLock) refreshScope@ {
        val snapshot = synchronized(lock) {
            val raw = vault.load() ?: return@refreshScope null
            val old = ChatGptCredentials.parse(raw)
            if (previousAccountId != null && old.accountId != previousAccountId) throw IOException("로그인 계정이 바뀌었어요. 다시 요청해 주세요.")
            val savedPending = pending
            if (savedPending != null && savedPending.generation == generation && savedPending.before == raw) {
                vault.save(savedPending.next.json())
                pending = null
                return@refreshScope savedPending.next
            }
            Triple(generation, raw, old)
        }
        val (revision, raw, old) = snapshot
        if (!forceRefresh && old.expiresAt > clockSeconds() + 300) return@refreshScope old
        val next = refresh(old)
        if (next.accountId != old.accountId) throw IOException("ChatGPT 로그인 계정을 확인하지 못했어요")
        synchronized(lock) {
            if (generation != revision || vault.load() != raw) throw IOException("로그인 상태가 바뀌어 토큰을 적용하지 않았어요")
            pending = Pending(revision, raw, next)
            vault.save(next.json())
            pending = null
            next
        }
    }
}
