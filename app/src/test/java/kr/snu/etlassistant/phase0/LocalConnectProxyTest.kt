package kr.snu.etlassistant.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
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
