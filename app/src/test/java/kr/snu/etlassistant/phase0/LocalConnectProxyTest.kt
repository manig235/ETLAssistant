package kr.snu.etlassistant.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Collections

class LocalConnectProxyTest {

    private fun auth(user: String, pass: String): String =
        "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray()) + "\r\n"

    private fun auth(p: LocalConnectProxy): String = auth(p.user, p.password)

    private fun open(port: Int): Socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", port), 5000)
        soTimeout = 20_000
    }

    private fun connectStatus(port: Int, target: String, authHeader: String = ""): String =
        open(port).use { s ->
            s.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n$authHeader\r\n".toByteArray())
            s.getInputStream().bufferedReader().readLine()
        }

    @Test
    fun rejectsLoopbackAndOtherPorts() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val proxy = LocalConnectProxy({ logs += it }).start()
        try {
            assertTrue(connectStatus(proxy.port, "127.0.0.1:443", auth(proxy)).contains(" 502 "))
            assertTrue(connectStatus(proxy.port, "localhost:443", auth(proxy)).contains(" 502 "))
            assertTrue(connectStatus(proxy.port, "example.com:22", auth(proxy)).contains(" 502 "))
            assertTrue(logs.any { it.contains("loopback") })
            assertTrue(logs.any { it.contains("80/443") })
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun rejectsLanLinkLocalAndMulticastDestinations() {
        for (ip in listOf("127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
            "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12::1", "ff02::1")) {
            assertFalse(ip, LocalConnectProxy.isPublicDestination(InetAddress.getByName(ip)))
        }
        assertTrue(LocalConnectProxy.isPublicDestination(InetAddress.getByName("93.184.215.14")))
        assertTrue(LocalConnectProxy.isPublicDestination(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")))
        val proxy = LocalConnectProxy({}).start()
        try {
            for (target in listOf("192.168.1.1:443", "169.254.169.254:80", "[fc00::1]:443")) {
                assertTrue(target, connectStatus(proxy.port, target, auth(proxy)).contains(" 502 "))
            }
        } finally { proxy.stop() }
    }

    @Test
    fun requiresPerRunCredentials() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val proxy = LocalConnectProxy({ logs += it }).start()
        val other = LocalConnectProxy({})
        try {
            // 인증 없음, 틀린 비밀번호, 다른 실행의 자격 증명은 모두 407이고 목적지에 연결하지 않는다.
            assertTrue(connectStatus(proxy.port, "example.com:443").contains(" 407 "))
            assertTrue(connectStatus(proxy.port, "example.com:443", auth(proxy.user, proxy.password + "x")).contains(" 407 "))
            assertTrue(connectStatus(proxy.port, "example.com:443", auth(other)).contains(" 407 "))
            open(proxy.port).use { s ->
                s.getOutputStream().write("GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().bufferedReader().readLine().contains(" 407 "))
            }
            assertFalse(logs.any { it.contains("example.com") && it.contains("OK") })
            assertFalse(logs.any { it.contains(proxy.password) })
            // 실행마다 새 값이고, URL에 그대로 넣을 수 있는 문자만 쓴다.
            assertTrue(proxy.user != other.user && proxy.password != other.password)
            assertTrue(proxy.url.matches(Regex("http://[0-9a-f]+:[0-9a-f]+@127\\.0\\.0\\.1:\\d+")))
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun rejectsDuplicateCredentialsMalformedAuthoritiesAndPlainRequestBodies() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val proxy = LocalConnectProxy({ logs += it }).start()
        try {
            assertTrue(connectStatus(proxy.port, "example.com:443", auth(proxy) + auth(proxy)).contains(" 407 "))
            for (target in listOf("[::1]ignored", "example.com:443/path", "SECRET@example.com:443", "example.com:443?SECRET")) {
                assertTrue(target, connectStatus(proxy.port, target, auth(proxy)).contains(" 400 "))
            }
            for (header in listOf("Content-Length: 1", "Transfer-Encoding: chunked")) {
                open(proxy.port).use { socket ->
                    socket.getOutputStream().write(("GET http://example.com/ HTTP/1.1\r\n${auth(proxy)}$header\r\n\r\n").toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readLine().contains(" 400 "))
                }
            }
            assertFalse(logs.any { it.contains("SECRET") || it.contains(proxy.password) })
        } finally { proxy.stop() }
    }

    @Test
    fun plaintextForwardingStripsProxyCredentialsAndNeverReadsPipelinedRequests() {
        class MemorySocket(input: String) : Socket() {
            val incoming = ByteArrayInputStream(input.toByteArray())
            val outgoing = ByteArrayOutputStream()
            override fun getInputStream() = incoming
            override fun getOutputStream() = outgoing
            override fun shutdownOutput() {}
        }
        val proxy = LocalConnectProxy({})
        val pipelined = "GET http://evil.example/second HTTP/1.1\r\n${auth(proxy)}\r\n"
        val client = MemorySocket(pipelined)
        val upstream = MemorySocket("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK")
        proxy.forwardPlain(client, upstream, "GET", URI("http://example.com/path?q=1"), "HTTP/1.1",
            listOf("Host: wrong.example", auth(proxy).trim(), "Proxy-Connection: keep-alive", "Accept: */*"))
        val sent = upstream.outgoing.toString(Charsets.ISO_8859_1.name())
        assertTrue(sent.startsWith("GET /path?q=1 HTTP/1.1\r\n"))
        assertTrue(sent.contains("Host: example.com\r\n"))
        assertFalse(sent.contains("wrong.example"))
        assertFalse(sent.contains("Proxy-", ignoreCase = true))
        assertFalse(sent.contains(proxy.password))
        assertFalse(sent.contains("/second"))
        assertEquals(pipelined.toByteArray().size, client.incoming.available())
        assertTrue(client.outgoing.toString().endsWith("OK"))
        proxy.stop()
    }

    @Test
    fun boundsConcurrentUnauthenticatedConnectionsAndClosesThemOnStop() {
        val proxy = LocalConnectProxy({}).start()
        val sockets = (1..LocalConnectProxy.MAX_CONNECTIONS).map { open(proxy.port) }
        try {
            open(proxy.port).use { excess ->
                excess.soTimeout = 2000
                assertEquals(-1, excess.getInputStream().read())
            }
            proxy.stop()
            sockets.forEach {
                it.soTimeout = 2000
                assertEquals(-1, it.getInputStream().read())
            }
        } finally {
            sockets.forEach { it.close() }
            proxy.stop()
        }
    }

    /** 네트워크가 필요하다. 오프라인이면 실패할 수 있다. */
    @Test
    fun tunnelsHttpsThroughConnect() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val proxy = LocalConnectProxy({ logs += it }).start()
        try {
            open(proxy.port).use { s ->
                s.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n${auth(proxy)}\r\n".toByteArray())
                val input = s.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                assertTrue(head.startsWith("HTTP/1.1 200"))
                val tls = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                    .createSocket(s, "example.com", 443, false) as javax.net.ssl.SSLSocket
                tls.startHandshake()
                tls.outputStream.write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".toByteArray())
                assertTrue(tls.inputStream.bufferedReader().readLine().startsWith("HTTP/1.1 200"))
            }
            assertTrue(logs.any { it.contains("CONNECT example.com:443") && it.contains("OK") })

            open(proxy.port).use { s ->
                s.getOutputStream().write("GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n${auth(proxy)}\r\n".toByteArray())
                assertTrue(s.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 200"))
            }
            assertTrue(logs.any { it.contains("GET example.com:80") && it.contains("OK") })
        } finally {
            proxy.stop()
        }
    }
}
