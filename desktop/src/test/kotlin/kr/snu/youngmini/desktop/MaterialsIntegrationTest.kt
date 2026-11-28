package kr.snu.youngmini.desktop

import com.sun.net.httpserver.HttpServer
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class MaterialsIntegrationTest {
    @Test fun connectFindHiddenAttachmentDownloadAndReadWithoutLeakingCredentials() {
        val root = Files.createTempDirectory("youngmini-materials").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val token = "test-etl-token-not-real"
        val requests = CopyOnWriteArrayList<String>()
        val address = "http://127.0.0.1:${server.address.port}"
        val content = "Lecture: matrices and linear transformations."
        server.createContext("/") { exchange ->
            try {
                requests += exchange.requestMethod + " " + exchange.requestURI.path
                assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
                val path = exchange.requestURI.path
                val response: Any = when (path) {
                    "/api/v1/users/self" -> JSONObject().put("name", "Test student")
                    "/api/v1/courses" -> JSONArray().put(JSONObject().put("id", 10).put("name", "Linear Algebra").put("term", JSONObject().put("name", "2026년 2학기")))
                    "/api/v1/courses/10/assignments" -> JSONArray()
                    "/api/v1/courses/10/files" -> JSONArray()
                    "/api/v1/courses/10/folders", "/api/v1/courses/10/modules" -> JSONArray()
                    "/api/v1/announcements" -> JSONArray().put(JSONObject().put("id", 30).put("title", "Lecture notes").put("is_announcement", true)
                        .put("message", "<a href=\"https://myetl.snu.ac.kr/courses/10/files/20/download?verifier=secret-signature\">notes.txt</a>"))
                    "/api/v1/courses/10/discussion_topics/30" -> JSONObject().put("id", 30).put("is_announcement", true).put("published", true)
                        .put("message", "<a href=\"https://myetl.snu.ac.kr/courses/10/files/20\">notes.txt</a>")
                    "/api/v1/courses/10/files/20", "/api/v1/files/20" -> JSONObject().put("id", 20).put("display_name", "notes.txt").put("hidden_for_user", true)
                        .put("url", "$address/download/20").put("size", content.toByteArray().size)
                    "/download/20" -> content
                    else -> { exchange.sendResponseHeaders(404, -1); return@createContext }
                }
                val bytes = response.toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val paths = AppPaths(root)
            val vault = SecretVault(paths.secrets)
            val materials = Materials(paths, vault, address)
            assertEquals("Test student", materials.connect(token))
            val course = materials.courses().single()
            val list = materials.files(course)
            val file = list.items.single()
            assertNull(file.skip)
            assertEquals("announcement", file.args.getString("source_type"))
            assertEquals(30, file.args.getInt("source_id"))
            assertThrows(EtlException::class.java) { materials.download(JSONObject().put("file_id_or_url", "20")) }
            val downloaded = materials.download(file.args)
            assertEquals("etl-files/20-notes.txt", downloaded.path)
            assertEquals(content, EtlFiles.resolveInside(paths.workspace, downloaded.path).readText())
            val reader = DocumentReader(paths.workspace)
            assertEquals(content, reader.read(downloaded.path).getString("text"))
            val tools = DesktopTools(materials, reader) { listOf(token) }
            val result = tools.call("etl_files", JSONObject().put("course_id", 10))
            assertFalse(result.isError)
            assertFalse(result.text.contains(token))
            assertFalse(result.text.contains("secret-signature"))
            assertTrue(requests.all { it.startsWith("GET ") })
            val contexts=ToolContexts();val owner=contexts.register("background-chat",Project.integrated)
            val cards=mutableListOf<Pair<String,Artifact>>();contexts.onArtifact={id,card->cards+=id to card}
            val routed=DesktopTools(materials,reader,contexts) {listOf(token)}
            val internalArgs=JSONObject(file.args.toString()).put("context",owner.key)
            assertFalse(routed.call("etl_download",internalArgs).isError);assertTrue(cards.isEmpty())
            assertFalse(routed.call("etl_download",JSONObject(internalArgs.toString()).put("purpose","requested")).isError)
            assertEquals("background-chat",cards.single().first);assertEquals(downloaded.path,cards.single().second.path)
            assertTrue(routed.call("etl_files",JSONObject().put("context",owner.key).put("course_id",999)).isError)
            contexts.activate("background-chat",false)
            assertTrue(routed.call("etl_download",JSONObject(internalArgs.toString()).put("purpose","requested")).isError)
            assertEquals(1,cards.size)
            materials.disconnect()
            assertFalse(materials.connected())
            assertTrue(EtlFiles.resolveInside(paths.workspace, downloaded.path).exists())
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun truncatedDownloadNeverReplacesAnExistingFile() {
        val root = Files.createTempDirectory("youngmini-truncated").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val address = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path.startsWith("/api/"))
                JSONObject().put("id", 1).put("display_name", "notes.txt").put("url", "$address/download").put("size", 100).toString() else "short"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        server.start()
        try {
            val paths = AppPaths(root)
            val vault = SecretVault(paths.secrets).also { it.write("etl", "not-a-real-token") }
            val directory = java.io.File(paths.workspace, "etl-files").apply { mkdirs() }
            val existing = java.io.File(directory, "1-notes.txt").apply { writeText("existing valid file") }
            assertThrows(EtlException::class.java) { Materials(paths, vault, address).download(JSONObject().put("file_id_or_url", "1")) }
            assertEquals("existing valid file", existing.readText())
            assertEquals(listOf("1-notes.txt"), directory.listFiles()!!.map { it.name })
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun windowsNamesCannotBecomeDevicesOrDirectories() {
        assertEquals("_CON.pdf", Materials.windowsFileName("CON.pdf", "file"))
        assertEquals("_LPT1", Materials.windowsFileName("LPT1. ", "file"))
        assertEquals("notes.txt", Materials.windowsFileName("..\\folder\\notes.txt", "file"))
        assertEquals("file", Materials.windowsFileName("...", "file"))
    }
}
