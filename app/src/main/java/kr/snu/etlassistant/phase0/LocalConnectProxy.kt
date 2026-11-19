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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 127.0.0.1에만 바인딩하는 최소 HTTP 프록시.
 *
 * - `CONNECT host:port`: 안드로이드 리졸버(InetAddress)로 주소를 찾아 연결하고 양방향으로 바이트를 넘긴다.
 * - `GET http://host/...`(absolute-URI): origin-form으로 바꿔 전달한다(HTTP_PROXY 대비 최소 구현).
 *
 * 목적지는 포트 80/443만 허용하고, loopback/any-local 주소는 거부한다.
 * musl 정적 바이너리는 /etc/resolv.conf가 없어 DNS를 못 하므로, 이름 해석을 이 프록시가 대신한다.
 */
class LocalConnectProxy(private val log: (String) -> Unit) {

    private val server = ServerSocket()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "proxy-worker").apply { isDaemon = true }
    }
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
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            val client = try { server.accept() } catch (e: IOException) {
                if (running) log("[proxy] accept error: ${e.message}")
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 30_000
                val cin = c.getInputStream()
                val cout = c.getOutputStream()
                val head = readHead(cin) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val parts = requestLine.split(' ')
                if (parts.size < 3) {
                    reply(cout, 400, "Bad Request"); return
                }
                val method = parts[0].uppercase()
                if (method == "CONNECT") {
                    handleConnect(c, parts[1])
                } else {
                    handlePlain(c, method, parts[1], parts[2], lines.drop(1))
                }
            } catch (e: Exception) {
                log("[proxy] error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun handleConnect(client: Socket, authority: String) {
        val cout = client.getOutputStream()
        val (host, port) = parseAuthority(authority, defaultPort = 443) ?: run {
            log("[proxy] CONNECT $authority -> 400 (bad authority)")
            reply(cout, 400, "Bad Request"); return
        }
        val upstream = openUpstream("CONNECT", host, port) ?: run {
            reply(cout, 502, "Bad Gateway"); return
        }
        upstream.use { up ->
            cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            cout.flush()
            client.soTimeout = 0
            pipeBoth(client, up)
        }
    }

    private fun handlePlain(client: Socket, method: String, target: String, version: String, headers: List<String>) {
        val cout = client.getOutputStream()
        val uri = try { URI(target) } catch (_: Exception) { null }
        if (uri == null || !uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrEmpty()) {
            log("[proxy] $method $target -> 400 (absolute http:// URI만 지원)")
            reply(cout, 400, "Bad Request"); return
        }
        val host = uri.host.removePrefix("[").removeSuffix("]")
        val port = if (uri.port == -1) 80 else uri.port
        val upstream = openUpstream(method, host, port) ?: run {
            reply(cout, 502, "Bad Gateway"); return
        }
        upstream.use { up ->
            val path = buildString {
                append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                if (uri.rawQuery != null) append('?').append(uri.rawQuery)
            }
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
            for (h in headers) {
                if (h.isEmpty()) continue
                val name = h.substringBefore(':').trim().lowercase()
                if (name == "proxy-connection" || name == "proxy-authorization" || name == "connection") continue
                sb.append(h).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            val uout = up.getOutputStream()
            uout.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            uout.flush()
            client.soTimeout = 0
            pipeBoth(client, up)
        }
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
        if (addrs.isEmpty() || addrs.any { it.isLoopbackAddress || it.isAnyLocalAddress }) {
            log("[proxy] $kind $host:$port -> 거부 (loopback/any-local: ${addrs.joinToString { it.hostAddress ?: "?" }})")
            return null
        }
        var lastError: Exception? = null
        for (a in addrs) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, port), 15_000)
                s.tcpNoDelay = true
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
        val t = Thread({ pump(a.getInputStream(), b.getOutputStream(), b) }, "proxy-pump")
        t.isDaemon = true
        t.start()
        pump(b.getInputStream(), a.getOutputStream(), a)
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
        var state = 0
        while (out.size() < 16 * 1024) {
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
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val host = authority.substring(1, end)
            val rest = authority.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else defaultPort
            return host to port
        }
        val idx = authority.lastIndexOf(':')
        if (idx < 0) return authority to defaultPort
        val port = authority.substring(idx + 1).toIntOrNull() ?: return null
        val host = authority.substring(0, idx)
        if (host.isEmpty()) return null
        return host to port
    }

    private fun reply(out: OutputStream, code: Int, reason: String) {
        try {
            out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: IOException) {}
    }
}
