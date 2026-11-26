package kr.snu.etlassistant.etl

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class EtlDownloadErrorTest {
    private fun withError(body: String, test: (EtlClient) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 401 Unauthorized\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: IOException) { /* Server shutdown or a client stopping at its read limit. */ }
            }
        }
        try { test(EtlClient("test-token", "http://127.0.0.1:${server.localPort}")) }
        finally { server.close(); worker.join(2000) }
    }

    private fun error(block: () -> Unit): EtlException {
        try { block(); fail("Expected an HTTP error") }
        catch (e: EtlException) { return e }
        error("unreachable")
    }

    @Test fun downloadAndJsonClassifyKoreanAndEnglishPermissionsTheSameWay() {
        for (body in listOf("사용자에게 이 동작을 수행할 권한이 없음", "User not authorized to perform that action")) {
            withError(body) { client ->
                val out = ByteArrayOutputStream()
                val download = error { client.download("/files/1/download?verifier=SECRET", out) }
                val json = error { client.get("/api/v1/files/1") }
                assertEquals("forbidden", download.kind)
                assertEquals(json.kind, download.kind)
                assertFalse(download is EtlAuthException)
                assertFalse(download.message!!.contains("SECRET"))
                assertEquals(0, out.size())
            }
        }
    }

    @Test fun realAuthenticationFailuresRemainAuthenticationFailuresWithoutReflectingSecrets() {
        withError("invalid token Bearer SECRET https://example.com/?verifier=SECRET") { client ->
            val failure = error { client.download("/files/1/download", ByteArrayOutputStream()) }
            assertTrue(failure is EtlAuthException)
            assertTrue(failure.message!!.contains("토큰 인증 실패"))
            assertFalse(failure.message!!.contains("SECRET"))
            assertFalse(failure.message!!.contains("https://"))
        }
    }

    @Test fun permissionTextPastErrorBodyLimitIsNotRead() {
        withError("x".repeat(64 * 1024) + "권한 없음") { client ->
            assertTrue(error { client.download("/files/1/download", ByteArrayOutputStream()) } is EtlAuthException)
        }
    }
}
