package kr.snu.etlassistant.codex

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * `codex app-server`(stdio, 줄 단위 JSON-RPC)에 붙는 클라이언트. 오래 사는 세션용이다.
 *
 * 프로토콜은 `codex app-server generate-json-schema`(rust-v0.170.0)로 확인했다.
 * 콜백은 읽기 스레드에서 불린다(UI에 쓰려면 메인 스레드로 넘길 것).
 */
class AppServerClient(
    private val p: Process,
    private val onNotification: (method: String, params: JSONObject) -> Unit,
    private val onServerRequest: (method: String, params: JSONObject?) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
    private val ids = AtomicLong(1)
    private val waiters = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()

    @Volatile var closed = false
        private set

    fun start() {
        Thread({
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.isNotBlank()) handle(line)
                }
            } catch (_: IOException) {
            } finally {
                closed = true
                val e = IOException("app-server 연결이 닫혔습니다")
                waiters.values.forEach { it.completeExceptionally(e) }
                onClosed()
            }
        }, "app-server-reader").apply { isDaemon = true }.start()
    }

    private fun send(o: JSONObject) {
        synchronized(writer) {
            writer.write(o.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    /** 응답의 result를 돌려준다. 오류 응답이면 [IOException]. */
    fun request(method: String, params: JSONObject, timeoutSec: Long = 60): JSONObject {
        if (closed) throw IOException("app-server 연결이 닫혔습니다")
        val id = ids.getAndIncrement()
        val f = CompletableFuture<JSONObject>()
        waiters[id] = f
        try {
            try {
                send(JSONObject().put("id", id).put("method", method).put("params", params))
            } catch (e: IOException) {
                throw IOException("$method 전송 실패")
            }
            val res = f.get(timeoutSec, TimeUnit.SECONDS)
            res.optJSONObject("error")?.let { throw IOException(it.optString("message", "$method 오류")) }
            return res.optJSONObject("result") ?: JSONObject()
        } catch (e: TimeoutException) {
            throw IOException("$method 응답 시간 초과")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException ?: IOException(e.cause?.message ?: "$method 실패"))
        } finally {
            waiters.remove(id)
        }
    }

    fun notify(method: String) {
        try {
            send(JSONObject().put("method", method))
        } catch (_: IOException) {
        }
    }

    fun close() {
        closed = true
        try {
            p.destroy()
        } catch (_: Exception) {
        }
    }

    private fun handle(line: String) {
        val m = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        val hasId = m.has("id")
        val method = m.optString("method", "")
        when {
            hasId && method.isNotEmpty() -> {
                // 승인 정책을 never로 쓰므로 서버 요청은 오지 않아야 한다. 오면 거절하고 알린다.
                try {
                    send(JSONObject().put("id", m.get("id"))
                        .put("error", JSONObject().put("code", -32601).put("message", "unsupported by this client")))
                } catch (_: IOException) {
                }
                onServerRequest(method, m.optJSONObject("params"))
            }
            method.isNotEmpty() -> onNotification(method, m.optJSONObject("params") ?: JSONObject())
            hasId -> (m.opt("id") as? Number)?.toLong()?.let { waiters[it]?.complete(m) }
        }
    }
}
