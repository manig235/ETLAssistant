package kr.snu.etlassistant.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

class LocalConnectProxyTest {

    private fun connectStatus(port: Int, target: String): String =
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            s.soTimeout = 20_000
            s.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readLine()
        }

    @Test
    fun rejectsLoopbackAndOtherPorts() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val proxy = LocalConnectProxy { logs += it }.start()
        try {
            assertTrue(connectStatus(proxy.port, "127.0.0.1:443").contains(" 502 "))
            assertTrue(connectStatus(proxy.port, "localhost:443").contains(" 502 "))
            assertTrue(connectStatus(proxy.port, "example.com:22").contains(" 502 "))
            assertTrue(logs.any { it.contains("loopback") })
            assertTrue(logs.any { it.contains("80/443") })
        } finally {
            proxy.stop()
        }
    }

    /** 네트워크가 필요하다. 오프라인이면 실패할 수 있다. */
    @Test
    fun tunnelsHttpsThroughConnect() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val proxy = LocalConnectProxy { logs += it }.start()
        try {
            val conn = java.net.URL("https://example.com/").openConnection(
                java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))
            ) as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            assertEquals(200, conn.responseCode)
            conn.inputStream.use { it.readBytes() }
            assertTrue(logs.any { it.contains("CONNECT example.com:443") && it.contains("OK") })

            val plain = java.net.URL("http://example.com/").openConnection(
                java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))
            ) as java.net.HttpURLConnection
            assertEquals(200, plain.responseCode)
            plain.inputStream.use { it.readBytes() }
            assertTrue(logs.any { it.contains("GET example.com:80") && it.contains("OK") })
        } finally {
            proxy.stop()
        }
    }
}
