package kr.snu.etlassistant.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class McpServerTest {

    private class FakeHost : ToolHost {
        override fun tools() = JSONArray().put(JSONObject().put("name", "echo").put("inputSchema", JSONObject().put("type", "object")))
        override fun call(name: String, args: JSONObject): ToolResult = when (name) {
            "echo" -> ToolResult("echo:" + args.optString("x"))
            "fail" -> ToolResult("실패했습니다", isError = true)
            "boom" -> throw IllegalStateException("내부 비밀 정보")
            else -> throw UnknownToolException(name)
        }
    }

    private lateinit var server: McpServer

    @Before fun setUp() {
        server = McpServer(FakeHost()).start()
    }

    @After fun tearDown() {
        server.stop()
    }

    private data class Res(val code: Int, val body: String)

    private fun post(body: String, auth: String? = "Bearer ${server.secret}", extra: Map<String, String> = emptyMap(), path: String = "/mcp", method: String = "POST"): Res {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 5000
        if (auth != null) c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("Content-Type", "application/json")
        extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (method == "POST") {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return Res(code, text)
    }

    private fun rpc(method: String, params: JSONObject? = null, id: Int = 1): JSONObject {
        val m = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) m.put("params", params)
        val r = post(m.toString())
        assertEquals(200, r.code)
        return JSONObject(r.body)
    }

    @Test fun bindsLoopbackOnly() {
        val s = java.net.Socket()
        s.connect(java.net.InetSocketAddress("127.0.0.1", server.port), 2000)
        s.close()
        assertTrue(server.port > 0)
    }

    @Test fun secretsDifferPerServer() {
        val other = McpServer(FakeHost())
        assertNotEquals(server.secret, other.secret)
        assertEquals(64, server.secret.length)
    }

    @Test fun rejectsMissingOrWrongSecret() {
        assertEquals(401, post("{}", auth = null).code)
        assertEquals(401, post("{}", auth = "Bearer nope").code)
        assertEquals(401, post("{}", auth = server.secret).code) // Bearer 접두어 없음
    }

    @Test fun rejectsBrowserOriginEvenWithSecret() {
        // HttpURLConnection은 Origin 헤더를 보내지 못하므로(제한 헤더) raw 소켓으로 보낸다.
        val sock = java.net.Socket("127.0.0.1", server.port)
        sock.soTimeout = 5000
        sock.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: x\r\nOrigin: https://evil.example\r\n" +
            "Authorization: Bearer ${server.secret}\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").toByteArray())
        sock.getOutputStream().flush()
        val text = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
        sock.close()
        assertTrue(text, text.startsWith("HTTP/1.1 403"))
    }

    @Test fun onlyMcpPathAndPost() {
        assertEquals(404, post("{}", path = "/other").code)
        assertEquals(405, post("", method = "GET").code)
    }

    @Test fun initializeEchoesProtocolVersionAndAdvertisesTools() {
        val r = rpc("initialize", JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject()))
        val res = r.getJSONObject("result")
        assertEquals("2025-03-26", res.getString("protocolVersion"))
        assertTrue(res.getJSONObject("capabilities").has("tools"))
        assertEquals(1, r.getInt("id"))
    }

    @Test fun notificationsGet202() {
        val r = post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString())
        assertEquals(202, r.code)
    }

    @Test fun toolsListAndCall() {
        assertEquals("echo", rpc("tools/list").getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name"))
        val r = rpc("tools/call", JSONObject().put("name", "echo").put("arguments", JSONObject().put("x", "안녕")))
        val result = r.getJSONObject("result")
        assertEquals("echo:안녕", result.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(false, result.getBoolean("isError"))
    }

    @Test fun toolErrorsAreResultsNotProtocolErrors() {
        val r = rpc("tools/call", JSONObject().put("name", "fail"))
        assertEquals(true, r.getJSONObject("result").getBoolean("isError"))
    }

    @Test fun unknownToolAndMethodAndInternalErrors() {
        assertEquals(-32602, rpc("tools/call", JSONObject().put("name", "nope")).getJSONObject("error").getInt("code"))
        assertEquals(-32601, rpc("nope/method").getJSONObject("error").getInt("code"))
        val boom = rpc("tools/call", JSONObject().put("name", "boom"))
        assertEquals(-32603, boom.getJSONObject("error").getInt("code"))
        assertTrue(!boom.toString().contains("비밀")) // 내부 예외 문구는 노출하지 않는다
    }

    @Test fun parseErrorAndBatch() {
        val bad = JSONObject(post("{nope").body)
        assertEquals(-32700, bad.getJSONObject("error").getInt("code"))
        val batch = JSONArray()
            .put(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))
            .put(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
            .put(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"))
        val r = JSONArray(post(batch.toString()).body)
        assertEquals(2, r.length())
    }

    @Test fun keepAliveServesMultipleRequestsOnOneConnection() {
        val sock = java.net.Socket("127.0.0.1", server.port)
        sock.soTimeout = 5000
        val out = sock.getOutputStream()
        val inp = sock.getInputStream().buffered()
        repeat(3) { i ->
            val body = JSONObject().put("jsonrpc", "2.0").put("id", i).put("method", "ping").toString()
            out.write(("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${server.secret}\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            out.flush()
            val status = readLine(inp)
            assertTrue(status, status.startsWith("HTTP/1.1 200"))
            var len = 0
            while (true) {
                val h = readLine(inp)
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toInt()
            }
            val resp = ByteArray(len)
            var off = 0
            while (off < len) off += inp.read(resp, off, len - off)
            assertEquals(i, JSONObject(String(resp)).getInt("id"))
        }
        sock.close()
    }

    @Test fun chunkedRequestBodyIsAccepted() {
        val sock = java.net.Socket("127.0.0.1", server.port)
        sock.soTimeout = 5000
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", "ping").toString()
        val half = body.length / 2
        val chunked = "${Integer.toHexString(half)}\r\n${body.substring(0, half)}\r\n" +
            "${Integer.toHexString(body.length - half)}\r\n${body.substring(half)}\r\n0\r\n\r\n"
        sock.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${server.secret}\r\n" +
            "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n$chunked").toByteArray())
        sock.getOutputStream().flush()
        val text = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200"))
        assertTrue(text.contains("\"id\":7"))
        sock.close()
    }

    private fun readLine(inp: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
