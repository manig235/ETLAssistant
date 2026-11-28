package kr.snu.youngmini.desktop

import com.sun.net.httpserver.HttpServer
import kr.snu.etlassistant.codex.AppServerClient
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.mcp.McpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real app-server → private code-mode host → authenticated MCP → document reader.
 * The model provider is a local SSE fixture. No account, API key, or OpenAI call is used.
 */
class RealCodexTransportTest {
    @Test fun actualRuntimeReadsAMaterialCreatesPdfAndStreamsAnAnswer() {
        val binary = System.getenv("YOUNGMIN_TEST_CODEX")?.let(::File)
        assumeTrue("Set YOUNGMIN_TEST_CODEX to run the real runtime integration", binary?.isFile == true)
        val root = Files.createTempDirectory("youngmini-real-codex").toFile()
        val paths = AppPaths(root)
        val vault = SecretVault(paths.secrets)
        val marker = "YOUNGMINI_LECTURE_EIGENVALUE_42"
        val photo=File(paths.workspace,"image.png")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(12,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",photo)
        File(paths.workspace, "etl-files").mkdirs()
        File(paths.workspace, "etl-files/1-lecture.txt").writeText(marker)
        val contexts=ToolContexts();val context=contexts.register("test-chat",Project.integrated)
        val pdfs=PdfJobs(paths);val delivered=CopyOnWriteArrayList<Pair<String,Artifact>>()
        contexts.onArtifact={id,file->delivered+=id to file}
        pdfs.onRequest={job->org.apache.pdfbox.pdmodel.PDDocument().use {pdf->pdf.addPage(org.apache.pdfbox.pdmodel.PDPage());pdf.save(job.target)};pdfs.complete(job.id,true);job.target.copyTo(File(paths.workspace,"generated/visual-check.pdf"),overwrite=true)}
        val tools = DesktopTools(Materials(paths, vault), DocumentReader(paths.workspace),contexts,pdfs,files=FileTools(paths,DocumentReader(paths.workspace),contexts)) { emptyList() }
        val mcp = McpServer(tools).start()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<JSONObject>()
        val stderr = CopyOnWriteArrayList<String>()
        model.createContext("/") { exchange ->
            try {
                val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                requests += request
                val events = mutableListOf<JSONObject>()
                events += JSONObject().put("type", "response.created").put("response", JSONObject().put("id", "r${requests.size}"))
                if (requests.size == 1) events += JSONObject().put("type", "response.output_item.done").put("item", JSONObject()
                    .put("type", "custom_tool_call").put("call_id", "read-lecture").put("name", "exec")
                    .put("input", "text(await tools.mcp__etl__etl_read_file({context: '${context.key}', path: 'etl-files/1-lecture.txt'})); text(await tools.mcp__etl__make_pdf({context: '${context.key}', name: 'study.pdf', markdown: '# Study notes'})); text(await tools.mcp__etl__skill_list({context: '${context.key}'})); text(await tools.mcp__etl__office_create({context: '${context.key}', kind:'docx', name:'study.docx', spec:{title:'Study notes',paragraphs:['Source: selected lecture']}})); const rendered = await tools.mcp__etl__etl_render_pages({context:'${context.key}',path:'generated/visual-check.pdf',pages:'1'}); for(const part of rendered.content||[]) { if(part.type==='image') image(part); else if(part.type==='text') text(part.text); }"))
                else events += JSONObject().put("type", "response.output_item.done").put("item", JSONObject()
                    .put("type", "message").put("role", "assistant").put("id", "answer-1")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "The lecture discusses eigenvalues."))))
                events += JSONObject().put("type", "response.completed").put("response", JSONObject().put("id", "r${requests.size}")
                    .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0).put("total_tokens", 0)))
                val bytes = events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }.toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        model.start()
        // TOML root fields must precede sections.
        File(paths.codexHome, "config.toml").writeText("""
            model = "fixture-model"
            model_provider = "fixture"
        """.trimIndent() + "\n" + DesktopEngine.config(mcp.port).replace("[features]", "[features]\ncode_mode_only = true") + """

            [model_providers.fixture]
            name = "Local fixture"
            base_url = "http://127.0.0.1:${model.address.port}/v1"
            wire_api = "responses"
            request_max_retries = 0
            stream_max_retries = 0
        """.trimIndent())
        val builder = ProcessBuilder(binary!!.absolutePath, "app-server", "--listen", "stdio://", "--enable", "code_mode_host").directory(paths.workspace)
        val keep = builder.environment().filterKeys { it in setOf("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LANG", "SSL_CERT_FILE") }.toMap()
        builder.environment().clear(); builder.environment().putAll(keep)
        builder.environment()["CODEX_HOME"] = paths.codexHome.path
        builder.environment()["ETL_MCP_SECRET"] = mcp.secret
        val process = builder.start()
        Thread { process.errorStream.bufferedReader().forEachLine { stderr += it } }.apply { isDaemon = true }.start()
        val completed = CountDownLatch(1)
        val notifications = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val rpc = AppServerClient(process, { method, params ->
            notifications += method to params
            if (method == "turn/completed") completed.countDown()
        }, { _, _ -> }) {}
        rpc.start()
        try {
            rpc.request("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "youngmini_transport_test").put("version", "1"))
                .put("capabilities", JSONObject().put("experimentalApi", true)), 15)
            rpc.notify("initialized")
            val status = rpc.request("mcpServerStatus/list", JSONObject(), 15)
            assertTrue("eTL tools were not connected: $status", status.toString().contains("etl_read_file"))
            val thread = rpc.request("thread/start", JSONObject().put("cwd", paths.workspace.path).put("approvalPolicy", "never").put("sandbox", "read-only").put("developerInstructions",contexts.instructions(context)), 15)
                .getJSONObject("thread").getString("id")
            rpc.request("turn/start", DesktopEngine.turnParams(thread,"Read the selected lecture and attached image.",ModelChoice(effort="high"),listOf(photo)), 15)
            assertTrue("Timed out; ${stderr.takeLast(5)}", completed.await(30, TimeUnit.SECONDS))
            val turn = notifications.last { it.first == "turn/completed" }.second.getJSONObject("turn")
            assertEquals("Turn failed: $turn", "completed", turn.getString("status"))
            assertEquals("The model should receive a follow-up containing the actual file contents", 2, requests.size)
            assertEquals("high", requests.first().getJSONObject("reasoning").getString("effort"))
            assertTrue("Photo missing from model request",requests.first().getJSONArray("input").toString().contains("data:image/png;base64,"))
            val secondImages=Regex("data:image/png;base64,").findAll(requests[1].getJSONArray("input").toString()).count()
            assertTrue("Rendered PDF page must reach the model as an image, not just base64 text",secondImages>=2)
            assertTrue("MCP file content missing from code-mode output: ${requests[1].getJSONArray("input")}", requests[1].getJSONArray("input").toString().contains(marker))
            assertTrue(notifications.any { it.first == "item/completed" && it.second.optJSONObject("item")?.optString("text") == "The lecture discusses eigenvalues." })
            assertEquals(setOf("test-chat"),delivered.map {it.first}.toSet());assertEquals(2,delivered.size);assertTrue(delivered.any {it.second.name.endsWith(".docx")});assertTrue(requests[1].toString().contains("documents"))
            assertEquals("generated",delivered.first().second.kind)
            assertTrue(File(paths.workspace,delivered.first().second.path).isFile)
            assertFalse(File(paths.codexHome, "auth.json").exists())
        } finally {
            rpc.close(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            model.stop(0); mcp.stop(); root.deleteRecursively()
        }
    }
}
