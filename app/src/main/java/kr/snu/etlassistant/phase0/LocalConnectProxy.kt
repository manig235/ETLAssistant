package kr.snu.etlassistant.phase0

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 127.0.0.1에만 바인딩하는 최소 HTTP 프록시.
 *
 * - `CONNECT host:port`: 안드로이드 리졸버(InetAddress)로 주소를 찾아 연결하고 양방향으로 바이트를 넘긴다.
 * - `GET http://host/...`(absolute-URI): origin-form으로 바꿔 전달한다(HTTP_PROXY 대비 최소 구현).
 *
 * 목적지는 포트 80/443만 허용하고, loopback·사설망·link-local·multicast 주소는 거부한다.
 * musl 정적 바이너리는 /etc/resolv.conf가 없어 DNS를 못 하므로, 이름 해석을 이 프록시가 대신한다.
 *
 * 127.0.0.1은 폰의 다른 앱도 접속할 수 있으므로, 프록시를 띄울 때마다 새로 만든 임의의 사용자 이름·비밀번호를
 * `Proxy-Authorization: Basic`으로 요구한다(틀리면 407). Codex에는 [url](`http://user:pass@127.0.0.1:port`)을
 * HTTPS_PROXY 등으로 넘긴다. 그러지 않으면 다른 앱이 이 앱의 네트워크(UID)로 우회할 수 있다.
 */
class LocalConnectProxy(
    private val log: (String) -> Unit,
    val user: String = randomHex(8),
    val password: String = randomHex(24),
) {
    private val expectedAuth: ByteArray =
        ("Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))).toByteArray(Charsets.ISO_8859_1)

    /** Codex에 넘기는 프록시 주소(자격 증명 포함). 로그에 그대로 남기지 않는다. */
    val url: String get() = "http://$user:$password@127.0.0.1:$port"

    private val server = ServerSocket()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "proxy-worker").apply { isDaemon = true } })
    @Volatile private var running = false

    val port: Int get() = server.localPort

    fun start(): LocalConnectProxy {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        running = true
        Thread({ acceptLoop() }, "proxy-accept").apply { isDaemon = true }.start()
        log("[proxy] listening on 127.0.0.1:$port")
        return this
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: IOException) {}
        sockets.forEach { try { it.close() } catch (_: IOException) {} }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            val client = try { server.accept() } catch (e: IOException) {
                if (running) log("[proxy] accept error: ${e.message}")
                break
            }
            sockets.add(client)
            try {
                pool.execute {
                    try { handle(client) } finally { sockets.remove(client) }
                }
            } catch (_: RejectedExecutionException) {
                sockets.remove(client)
                try { client.close() } catch (_: IOException) {}
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 10_000
                val cin = c.getInputStream()
                val cout = c.getOutputStream()
                val head = readHead(cin) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val parts = requestLine.split(' ')
                if (parts.size != 3 || !parts[0].matches(Regex("[A-Z]+")) || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
                    reply(cout, 400, "Bad Request"); return
                }
                val method = parts[0].uppercase()
                val headers = lines.drop(1)
                if (headers.any { h ->
                    val colon = h.indexOf(':')
                    colon <= 0 || !HEADER_NAME.matches(h.substring(0, colon)) ||
                        h.substring(colon + 1).any { it.code < 32 && it != '\t' || it.code == 127 }
                }) {
                    reply(cout, 400, "Bad Request"); return
                }
                if (!authorized(headers)) {
                    log("[proxy] $method -> 407 (프록시 인증 없음/틀림)")
                    try {
                        cout.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"etl\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        cout.flush()
                    } catch (_: IOException) {}
                    return
                }
                if (method == "CONNECT") {
                    handleConnect(c, parts[1])
                } else {
                    handlePlain(c, method, parts[1], parts[2], headers)
                }
            } catch (e: Exception) {
                log("[proxy] error: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun handleConnect(client: Socket, authority: String) {
        val cout = client.getOutputStream()
        val (host, port) = parseAuthority(authority, defaultPort = 443) ?: run {
            log("[proxy] CONNECT -> 400 (bad authority)")
            reply(cout, 400, "Bad Request"); return
        }
        val upstream = openUpstream("CONNECT", host, port) ?: run {
            reply(cout, 502, "Bad Gateway"); return
        }
        try { upstream.use { up ->
            cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            cout.flush()
            client.soTimeout = TUNNEL_IDLE_TIMEOUT_MS
            pipeBoth(client, up)
        } } finally { sockets.remove(upstream) }
    }

    private fun handlePlain(client: Socket, method: String, target: String, version: String, headers: List<String>) {
        val cout = client.getOutputStream()
        if (method != "GET" && method != "HEAD") {
            reply(cout, 405, "Method Not Allowed"); return
        }
        // Plain HTTP is a single bodyless request. Piping further client bytes would forward the next
        // request's Proxy-Authorization header to the origin (including pipelined requests).
        if (headers.any { h ->
            val name = h.substringBefore(':').lowercase()
            name == "transfer-encoding" || name == "content-length" && h.substringAfter(':').trim() != "0"
        }) {
            reply(cout, 400, "Bad Request"); return
        }
        val uri = try { URI(target) } catch (_: Exception) { null }
        if (uri == null || !uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawFragment != null) {
            log("[proxy] $method -> 400 (absolute http:// URI만 지원)")
            reply(cout, 400, "Bad Request"); return
        }
        val host = uri.host.removePrefix("[").removeSuffix("]")
        val port = if (uri.port == -1) 80 else uri.port
        val upstream = openUpstream(method, host, port) ?: run {
            reply(cout, 502, "Bad Gateway"); return
        }
        try {
            upstream.use { forwardPlain(client, it, method, uri, version, headers) }
        } finally { sockets.remove(upstream) }
    }

    /** 이미 검증한, 본문 없는 HTTP 요청 하나와 응답만 전달한다. */
    internal fun forwardPlain(client: Socket, upstream: Socket, method: String, uri: URI, version: String, headers: List<String>) {
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (uri.rawQuery != null) append('?').append(uri.rawQuery)
        }
        val head = buildString {
            append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
            for (h in headers) {
                if (h.isEmpty()) continue
                val name = h.substringBefore(':').lowercase(java.util.Locale.ROOT)
                if (name in setOf("proxy-connection", "proxy-authorization", "connection", "host")) continue
                append(h).append("\r\n")
            }
            append("Host: ").append(uri.rawAuthority).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        upstream.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        pump(upstream.getInputStream(), client.getOutputStream(), client)
    }

    /** `Proxy-Authorization` 헤더가 이번 실행의 자격 증명과 같은가(상수 시간 비교). */
    private fun authorized(headers: List<String>): Boolean {
        val authHeaders = headers.filter { it.substringBefore(':').equals("proxy-authorization", ignoreCase = true) }
        val value = authHeaders.singleOrNull()?.substringAfter(':')?.trim() ?: return false
        return MessageDigest.isEqual(value.toByteArray(Charsets.ISO_8859_1), expectedAuth)
    }

    /** 목적지 검사 + 안드로이드 리졸버로 이름 해석 + 연결. 실패 시 null. */
    private fun openUpstream(kind: String, host: String, port: Int): Socket? {
        if (port != 80 && port != 443) {
            log("[proxy] $kind $host:$port -> 거부 (포트 80/443만 허용)")
            return null
        }
        val addrs = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            log("[proxy] $kind $host:$port -> DNS 실패: ${e.message}")
            return null
        }
        if (addrs.isEmpty() || addrs.any { !isPublicDestination(it) }) {
            log("[proxy] $kind $host:$port -> 거부 (loopback/private/link-local: ${addrs.joinToString { it.hostAddress ?: "?" }})")
            return null
        }
        var lastError: Exception? = null
        for (a in addrs) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, port), 15_000)
                s.tcpNoDelay = true
                s.soTimeout = TUNNEL_IDLE_TIMEOUT_MS
                sockets.add(s)
                if (!running) {
                    sockets.remove(s)
                    s.close()
                    return null
                }
                log("[proxy] $kind $host:$port -> ${a.hostAddress} OK")
                return s
            } catch (e: Exception) {
                lastError = e
                try { s.close() } catch (_: IOException) {}
            }
        }
        log("[proxy] $kind $host:$port -> 연결 실패: ${lastError?.message}")
        return null
    }

    private fun pipeBoth(a: Socket, b: Socket) {
        // Acquire streams on the worker so a concurrent stop cannot throw outside pump's catch
        // on a new Android thread (an uncaught exception there terminates the whole app).
        val ain = a.getInputStream()
        val aout = a.getOutputStream()
        val bin = b.getInputStream()
        val bout = b.getOutputStream()
        val t = Thread({ pump(ain, bout, b) }, "proxy-pump")
        t.isDaemon = true
        t.start()
        pump(bin, aout, a)
        t.join()
    }

    private fun pump(src: InputStream, dst: OutputStream, dstSocket: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: IOException) {
        } finally {
            try { dstSocket.shutdownOutput() } catch (_: IOException) {}
        }
    }

    /** `\r\n\r\n`까지 읽는다(최대 16KB). */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var state = 0
        while (out.size() < 16 * 1024) {
            if (System.nanoTime() >= deadline) return null
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun parseAuthority(authority: String, defaultPort: Int): Pair<String, Int>? {
        val uri = try { URI("https://$authority") } catch (_: Exception) { return null }
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
        return uri.host.removePrefix("[").removeSuffix("]") to (if (uri.port == -1) defaultPort else uri.port)
    }

    private fun reply(out: OutputStream, code: Int, reason: String) {
        try {
            out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: IOException) {}
    }

    companion object {
        internal const val MAX_CONNECTIONS = 32
        private const val TUNNEL_IDLE_TIMEOUT_MS = 300_000
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")
        private val rng = SecureRandom()

        internal fun isPublicDestination(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            // InetAddress.isSiteLocalAddress does not include IPv6 unique local addresses (fc00::/7).
            if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
            return true
        }

        /** 프록시 URL에 그대로 넣을 수 있는(퍼센트 인코딩이 필요 없는) 임의의 16진 문자열. */
        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes).also { rng.nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
