package kr.snu.etlassistant.codex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the real JSON-RPC pipe client and app-owned grant store, without accounts or network. */
class ChatGptExternalAuthIntegrationTest {
    private class FakeProcess : Process() {
        private val input = PipedInputStream()
        private val server = PipedOutputStream(input)
        private val commands = PipedInputStream()
        private val output = PipedOutputStream(commands)
        private val reader = commands.bufferedReader()
        private val writer = server.bufferedWriter()
        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { runCatching { server.close() }; runCatching { output.close() } }
        fun read() = JSONObject(reader.readLine())
        fun emit(message: JSONObject) { writer.write(message.toString()); writer.newLine(); writer.flush() }
    }

    @Test fun externalLoginRefreshAndLogoutKeepRefreshTokenOutOfCodexAndRejectOtherRequests() {
        val process = FakeProcess()
        var persisted: String? = ChatGptAuthTest.credentials().json()
        val vault = object : ChatGptVault {
            override fun load() = persisted
            override fun save(value: String) { persisted = value }
            override fun clear() { persisted = null }
        }
        val rotated = ChatGptAuthTest.credentials(ChatGptAuthTest.token(suffix = "next"), "new-private-refresh")
        val manager = ChatGptAuthManager(vault, { rotated })
        val client = AppServerClient(process, { _, _ -> }, { _, _ -> }, { params, reply ->
            reply(manager.credentials(true, params.optString("previousAccountId"))?.externalParams())
        }) { }
        val worker = Executors.newSingleThreadExecutor()
        client.start()
        try {
            val initialized = worker.submit<JSONObject> { client.request("initialize", JSONObject().put("capabilities", JSONObject().put("experimentalApi", true)), 2) }
            val init = process.read()
            assertTrue(init.getJSONObject("params").getJSONObject("capabilities").getBoolean("experimentalApi"))
            process.emit(JSONObject().put("id", init.get("id")).put("result", JSONObject()))
            initialized.get(2, TimeUnit.SECONDS)
            val login = worker.submit<JSONObject> { client.request("account/login/start", manager.credentials()!!.externalParams().put("type", "chatgptAuthTokens"), 2) }
            val request = process.read()
            assertEquals("chatgptAuthTokens", request.getJSONObject("params").getString("type"))
            assertFalse(request.toString().contains("fake-refresh"))
            process.emit(JSONObject().put("id", request.get("id")).put("result", JSONObject().put("type", "chatgptAuthTokens")))
            login.get(2, TimeUnit.SECONDS)
            process.emit(JSONObject().put("id", "rotate-once").put("method", "account/chatgptAuthTokens/refresh")
                .put("params", JSONObject().put("reason", "unauthorized").put("previousAccountId", "workspace")))
            val response = process.read()
            assertEquals("rotate-once", response.getString("id"))
            assertEquals(rotated.accessToken, response.getJSONObject("result").getString("accessToken"))
            assertFalse(response.toString().contains(rotated.refreshToken))
            assertEquals(rotated.json(), persisted)
            process.emit(JSONObject().put("id", "denied").put("method", "item/commandExecution/requestApproval").put("params", JSONObject()))
            assertTrue(process.read().has("error"))
            manager.clear()
            assertNull(persisted)
        } finally { client.close(); worker.shutdownNow() }
    }
}
