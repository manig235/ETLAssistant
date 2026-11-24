package kr.snu.etlassistant.codex

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Uses only the fixed OpenAI OAuth issuer. Redirects and response-body logging are forbidden. */
internal object ChatGptOAuth {
    internal fun interface Transport {
        fun post(path: String, body: String, json: Boolean, attempt: Attempt?): JSONObject
    }
    private val network = Transport { path, body, json, attempt -> post(path, body, json, attempt) }
    class Failure(val status: Int) : IOException("ChatGPT 로그인 서버 요청에 실패했어요(HTTP $status)")
    class Attempt {
        private val cancelled = AtomicBoolean()
        @Volatile private var server: ServerSocket? = null
        @Volatile private var socket: Socket? = null
        @Volatile private var connection: HttpsURLConnection? = null
        val deadline = System.nanoTime() + ChatGptOAuthProtocol.LOGIN_TIMEOUT_MS * 1_000_000
        fun check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("로그인을 취소했어요")
            if (System.nanoTime() >= deadline) throw IOException("로그인 시간이 지났어요. 다시 시도해 주세요.")
        }
        @Synchronized fun track(value: ServerSocket) { check(); server = value }
        @Synchronized fun track(value: Socket) { check(); socket = value }
        @Synchronized fun track(value: HttpsURLConnection) { check(); connection = value }
        @Synchronized fun cancel() {
            cancelled.set(true)
            runCatching { server?.close() }
            runCatching { socket?.close() }
            connection?.disconnect()
        }
        fun pause(millis: Long) {
            val until = System.nanoTime() + millis * 1_000_000
            while (System.nanoTime() < until) {
                check()
                try { Thread.sleep(minOf(200, ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(1))) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); check() }
            }
        }
    }

    fun post(path: String, body: String, json: Boolean = false, attempt: Attempt? = null, timeoutMillis: Int = 20_000): JSONObject {
        require(path in setOf("/oauth/token", "/oauth/revoke", "/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token"))
        val connection = URL(ChatGptOAuthProtocol.ORIGIN + path).openConnection() as HttpsURLConnection
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        fun remainingMillis(): Int {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw IOException("로그인 서버 응답 시간이 지났어요")
            return left.coerceAtMost(timeoutMillis.toLong()).toInt().coerceAtLeast(1)
        }
        try {
            attempt?.track(connection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = minOf(15_000, timeoutMillis)
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            connection.readTimeout = remainingMillis()
            val status = connection.responseCode
            if (status !in 200..299) throw Failure(status)
            if (path == "/oauth/revoke") return JSONObject()
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    attempt?.check()
                    connection.readTimeout = remainingMillis()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1024 * 1024) throw IOException("로그인 응답이 너무 커요")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            return try { JSONObject(response) } catch (_: Exception) { throw IOException("로그인 응답을 확인하지 못했어요") }
        } catch (e: Failure) { throw e }
        catch (_: Exception) { throw IOException("ChatGPT 로그인 서버에 연결하지 못했어요. 다시 시도해 주세요.") }
        finally { connection.disconnect() }
    }

    fun exchange(code: String, verifier: String, redirect: String, attempt: Attempt, transport: Transport = network): ChatGptCredentials {
        attempt.check()
        return ChatGptCredentials.fromTokens(transport.post("/oauth/token", ChatGptOAuthProtocol.form(linkedMapOf(
            "grant_type" to "authorization_code", "client_id" to ChatGptOAuthProtocol.CLIENT_ID,
            "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect,
        )), false, attempt), null)
    }

    fun browser(attempt: Attempt, prompt: (String, String?) -> Unit, transport: Transport = network): ChatGptCredentials {
        val verifier = ChatGptOAuthProtocol.random()
        val state = ChatGptOAuthProtocol.random()
        val listener = ServerSocket()
        try {
            attempt.track(listener)
            listener.reuseAddress = false
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1455))
            listener.soTimeout = 1000
            val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
            prompt(ChatGptOAuthProtocol.authorize(redirect, verifier, state), null)
            while (true) {
                attempt.check()
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                var validCallback = false
                try { socket.use { incoming ->
                    attempt.track(incoming)
                    incoming.soTimeout = 3000
                    val until = System.nanoTime() + 3_000_000_000L
                    val request = readRequestLine(incoming, attempt, until)
                    var headerBytes = request.length
                    while (true) {
                        val header = readRequestLine(incoming, attempt, until)
                        headerBytes += header.length
                        if (headerBytes > 32 * 1024) throw IOException("로그인 요청을 확인하지 못했어요")
                        if (header.isEmpty()) break
                    }
                    val code = if (request.startsWith("GET ")) ChatGptOAuthProtocol.callbackCode(request.split(' ').getOrNull(1).orEmpty(), state) else null
                    if (code == null) {
                        respond(incoming, false)
                    } else {
                        validCallback = true
                        val credentials = try { exchange(code, verifier, redirect, attempt, transport) } catch (e: Exception) {
                            runCatching { respond(incoming, false) }
                            throw e
                        }
                        runCatching { respond(incoming, true) }
                        return credentials
                    }
                } } catch (e: IOException) {
                    attempt.check()
                    // A malformed/slow local request cannot terminate the legitimate browser login.
                    if (validCallback) throw e
                }
            }
        } catch (e: IOException) { throw IOException(if (e is java.net.BindException) "로그인 포트를 열지 못했어요. 기기 코드 로그인을 사용해 주세요." else e.message ?: "로그인하지 못했어요") }
        finally { listener.close() }
    }

    private fun readRequestLine(socket: Socket, attempt: Attempt, until: Long): String {
        val bytes = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (bytes.size() <= 32 * 1024) {
            attempt.check()
            if (System.nanoTime() >= until) throw IOException("로그인 요청 시간이 지났어요")
            val c = input.read()
            if (c < 0 || c == '\n'.code) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(c)
        }
        throw IOException("로그인 요청을 확인하지 못했어요")
    }

    private fun respond(socket: Socket, ok: Boolean) {
        val text = if (ok) "로그인을 확인했어요. 영민이 앱으로 돌아가 주세요." else "로그인 요청을 확인하지 못했어요. 영민이 앱에서 다시 시도해 주세요."
        val bytes = "<!doctype html><meta charset=utf-8><title>영민이 로그인</title><p>$text</p>".toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 ${if (ok) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(bytes); flush()
        }
    }

    fun device(attempt: Attempt, prompt: (String, String?) -> Unit, transport: Transport = network): ChatGptCredentials {
        val response = transport.post("/api/accounts/deviceauth/usercode", JSONObject().put("client_id", ChatGptOAuthProtocol.CLIENT_ID).toString(), true, attempt)
        val id = response.optString("device_auth_id").takeIf { it.isNotBlank() } ?: throw IOException("기기 코드를 받지 못했어요")
        val code = response.optString("user_code").ifBlank { response.optString("usercode") }.takeIf { it.isNotBlank() } ?: throw IOException("기기 코드를 받지 못했어요")
        val interval = response.optString("interval", "5").toLongOrNull()?.coerceIn(1, 60) ?: 5
        prompt(ChatGptOAuthProtocol.ORIGIN + "/codex/device", code)
        while (true) {
            attempt.check()
            val result = try {
                transport.post("/api/accounts/deviceauth/token", JSONObject().put("device_auth_id", id).put("user_code", code).toString(), true, attempt)
            } catch (e: Failure) {
                if (e.status != 403 && e.status != 404) throw e
                attempt.pause(interval * 1000)
                continue
            }
            val authorization = result.optString("authorization_code").takeIf { it.isNotBlank() } ?: throw IOException("기기 로그인 응답을 확인하지 못했어요")
            val verifier = result.optString("code_verifier").takeIf { it.isNotBlank() } ?: throw IOException("기기 로그인 응답을 확인하지 못했어요")
            return exchange(authorization, verifier, ChatGptOAuthProtocol.ORIGIN + "/deviceauth/callback", attempt, transport)
        }
    }

    fun revoke(credentials: ChatGptCredentials) {
        post("/oauth/revoke", JSONObject().put("token", credentials.refreshToken).put("token_type_hint", "refresh_token")
            .put("client_id", ChatGptOAuthProtocol.CLIENT_ID).toString(), json = true, timeoutMillis = 5000)
    }

    fun refresh(old: ChatGptCredentials): ChatGptCredentials = ChatGptCredentials.fromTokens(
        post("/oauth/token", ChatGptOAuthProtocol.form(linkedMapOf("grant_type" to "refresh_token", "client_id" to ChatGptOAuthProtocol.CLIENT_ID, "refresh_token" to old.refreshToken))), old,
    )
}
