package kr.snu.etlassistant.etl

import android.content.Context
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A review belongs to one call and one foreground chat instance. Nothing is approved by model text.
 * [Request.origin] is the Codex thread of the calling turn (null if unknown). Several conversations can answer at once
 * (2026-11-27), so turn boundaries cancel only their own requests; switching conversations inside the chat screen keeps the
 * host attached, and only leaving the screen detaches it.
 */
class WriteApprovalGate(private val timeoutMillis: Long = 90_000) {
    data class Request(val id: String, val operation: String, val details: String, val destructive: Boolean, val origin: String? = null)
    class Denied : Exception("변경을 승인하지 않아 실행하지 않았어요. 다시 실행하려면 사용자가 새로 요청해야 해요.")
    private class Waiting(val request: Request, val host: String, val deadline: Long) {
        val done = CountDownLatch(1)
        var approved = false
    }
    private val lock = Any()
    private var host: String? = null
    private val waiting = linkedMapOf<String, Waiting>()
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    private fun changed() = listeners.forEach { runCatching { it() } }

    fun attachHost(): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) { cancelLocked(); host = id }
        changed()
        return id
    }

    fun detachHost(id: String) {
        synchronized(lock) {
            if (host != id) return
            host = null
            cancelLocked()
        }
        changed()
    }

    fun cancelAll() {
        synchronized(lock) { cancelLocked() }
        changed()
    }

    private fun cancelLocked() {
        waiting.values.forEach { it.approved = false; it.done.countDown() }
        waiting.clear()
    }

    /** One turn began or ended: deny that thread's requests, and requests whose thread is unknown. */
    fun cancelOrigin(origin: String?) {
        val any = synchronized(lock) {
            val hit = waiting.values.filter { it.request.origin == null || it.request.origin == origin }
            hit.forEach { it.approved = false; it.done.countDown(); waiting.remove(it.request.id) }
            hit.isNotEmpty()
        }
        if (any) changed()
    }

    /** Automatic additions still require the foreground host, atomically with the write. */
    fun <T> executeAutomatic(action: () -> T): T = synchronized(lock) {
        if (host == null) throw Denied()
        action()
    }

    fun pending(): List<Request> = synchronized(lock) { waiting.values.filter { it.done.count != 0L }.map { it.request } }

    /** Stale dialogs, another host's token, and repeated answers cannot authorize another call. */
    fun decide(hostId: String, requestId: String, approved: Boolean): Boolean {
        val accepted = synchronized(lock) {
            val w = waiting[requestId]
            if (host != hostId || w == null || w.host != hostId || w.done.count == 0L) false
            else {
                w.approved = approved && System.nanoTime() < w.deadline
                w.done.countDown()
                true
            }
        }
        if (accepted) changed()
        return accepted
    }

    /** The approved immutable preview and this closure are bound in app memory, never supplied as a tool token. */
    fun <T> execute(operation: String, details: String, destructive: Boolean = false, origin: String? = null, action: () -> T): T {
        val w = synchronized(lock) {
            val owner = host ?: throw Denied()
            val request = Request(UUID.randomUUID().toString(), operation, details, destructive, origin)
            Waiting(request, owner, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)).also { waiting[request.id] = it }
        }
        changed()
        try {
            val answered = try { w.done.await(timeoutMillis, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            // Keep host loss/cancellation atomic with the short local write. No approval can survive a detached chat.
            return synchronized(lock) {
                if (!answered || !w.approved || host != w.host || waiting[w.request.id] !== w || System.nanoTime() >= w.deadline) throw Denied()
                waiting.remove(w.request.id)
                action()
            }
        } finally {
            synchronized(lock) { waiting.remove(w.request.id) }
            changed()
        }
    }
}

/** Only the foreground chat can answer these requests; tool callers only create requests. */
object AiWriteApproval {
    private val gate = WriteApprovalGate()
    private val budget = WriteTurnBudget()
    val listeners get() = gate.listeners
    fun attachHost() = gate.attachHost()
    fun detachHost(id: String) = gate.detachHost(id)
    /** A turn of thread [origin] starts or ends. Other conversations' pending reviews stay open. */
    fun beginTurn(origin: String) { gate.cancelOrigin(origin); budget.beginTurn(origin) }
    fun endTurn(origin: String?) { gate.cancelOrigin(origin); if (origin != null) budget.endTurn(origin) }
    /** Deny only [origin]'s pending reviews (the user stopped that answer). */
    fun cancelFor(origin: String?) = gate.cancelOrigin(origin)
    /** Session teardown: deny everything. */
    fun cancelAll() { gate.cancelAll(); budget.endAll() }
    fun pending() = gate.pending()
    fun decide(hostId: String, requestId: String, approved: Boolean) = gate.decide(hostId, requestId, approved)
    fun <T> execute(context: Context, scope: WriteScope, kind: WriteKind, operation: String, details: String, action: () -> T): T = try {
        val settings = WritePolicySettings(context)
        val origin = kr.snu.etlassistant.mcp.ToolOrigin.current()
        val permit = budget.claim(settings.mode(scope), kind, origin ?: "")
        val checkedAction = {
            // Settings may have changed while a confirmation was open.
            settings.requireEnabled(scope)
            action()
        }
        when (permit.decision) {
            WritePolicy.Decision.DENY -> { settings.requireEnabled(scope); throw EtlException("변경이 취소되었어요") }
            WritePolicy.Decision.ASK -> gate.execute(operation, details, destructive = kind != WriteKind.ADD, origin = origin, action = checkedAction)
            WritePolicy.Decision.AUTO -> gate.executeAutomatic {
                if (!budget.isCurrent(permit) || settings.mode(scope) != WriteMode.AUTO_ADD) throw WriteApprovalGate.Denied()
                checkedAction()
            }
        }
    } catch (e: WriteApprovalGate.Denied) {
        throw EtlException(e.message ?: "변경이 취소되었어요")
    }
}
