package kr.snu.etlassistant.security

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** App-UID smoke tests with synthetic data and a private, temporary Codex home only. */
class SecuritySmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var success = true
        for ((name, test) in listOf<Pair<String, () -> Unit>>(
            "native_private_stdio" to { results.putString("native_socket_check", PrivateCodeModeSmoke.run(targetContext)) },
            "storage_image_boundaries" to { SecurityStorageImageSmoke.run(targetContext) },
        )) {
            try {
                test()
                results.putString(name, "passed")
                sendStatus(0, Bundle().apply { putString("test", name); putString("status", "passed") })
            } catch (error: Throwable) {
                success = false
                // Do not print native output, file contents, credentials, or user paths.
                val safeReasons = setOf("runtime_missing", "helper_transport", "helper_fds_unreadable", "helper_stdio_not_pipes",
                    "helper_tcp_socket_present", "native_rpc_error", "native_rpc_timeout", "native_turn_failed",
                    "native_js_result_missing", "native_private_transport_not_observed", "native_pid_marker_missing")
                val reason = error.message?.takeIf { it in safeReasons }?.let { ":$it" }.orEmpty()
                val status = "failed:${error.javaClass.simpleName}$reason"
                results.putString(name, status)
                sendStatus(-1, Bundle().apply { putString("test", name); putString("status", status) })
            }
        }
        results.putString("stream", if (success) "Synthetic security smoke tests passed." else "Synthetic security smoke tests failed.")
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }
}

private object PrivateCodeModeSmoke {
    fun run(context: Context): String {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "private-stdio-smoke-").toFile()
        val home = File(root, "test-home").apply { mkdirs() }
        val temp = File(root, "tmp").apply { mkdirs() }
        val model = ModelFixture()
        var process: Process? = null
        val watching = AtomicBoolean(true)
        var watcher: Thread? = null
        val helperPids = CopyOnWriteArrayList<Int>()
        try {
            val bin = File(context.applicationInfo.nativeLibraryDir, "libcodex.so")
            val helper = File(bin.parentFile, "libcodex-modehost.so")
            check(bin.isFile && helper.isFile) { "runtime_missing" }
            File(home, "config.toml").writeText("""
                model = "mock-model"
                model_provider = "local_test"
                approval_policy = "never"
                sandbox_mode = "read-only"
                web_search = "disabled"
                cli_auth_credentials_store = "ephemeral"
                [features]
                code_mode_only = true
                apps = false
                plugins = false
                shell_tool = false
                unified_exec = false
                [analytics]
                enabled = false
                [feedback]
                enabled = false
                [otel]
                metrics_exporter = "none"
                [model_providers.local_test]
                name = "Synthetic local fixture"
                base_url = "http://127.0.0.1:${model.port}/v1"
                wire_api = "responses"
                request_max_retries = 0
                stream_max_retries = 0
            """.trimIndent())
            // Android's public Process API has no pid(). The fixed shell prints its PID and then
            // execs Codex in the same process, without interpolating any arguments or secrets.
            val pb = ProcessBuilder("/system/bin/sh", "-c", "echo CODEX_SMOKE_PID=\$\$ >&2; exec \"\$@\"", "private-stdio-smoke",
                bin.path, "app-server", "--listen", "stdio://", "--enable", "code_mode_host").directory(root)
            pb.environment().apply {
                clear()
                put("CODEX_HOME", home.path)
                put("HOME", home.path)
                put("TMPDIR", temp.path)
                put("PATH", "/system/bin")
                put("LANG", "C.UTF-8")
            }
            val p = pb.start().also { process = it }
            val pid = AtomicInteger()
            Thread({
                try { p.errorStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.startsWith("CODEX_SMOKE_PID=")) line.substringAfter('=').toIntOrNull()?.let(pid::set)
                } } } catch (_: java.io.IOException) {}
            }, "smoke-native-stderr").apply { isDaemon = true; start() }
            val messages = LinkedBlockingQueue<JSONObject>()
            Thread({
                try { p.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    try { messages.offer(JSONObject(line)) } catch (_: Exception) {}
                } } } catch (_: java.io.IOException) {}
            }, "smoke-native-stdout").apply { isDaemon = true; start() }
            val observed = AtomicBoolean(false)
            val tcpInspected = AtomicBoolean(false)
            val observationError = AtomicReference<Throwable?>()
            watcher = Thread({
                while (watching.get()) {
                    try {
                        if (pid.get() > 0) {
                            val tasks = File("/proc/${pid.get()}/task").listFiles().orEmpty()
                            val childPids = mutableSetOf<Int>()
                            for (task in tasks) {
                                val children = try { File(task, "children").readText().trim().split(Regex("\\s+")) } catch (_: Exception) { continue }
                                childPids += children.mapNotNull { it.toIntOrNull() }
                            }
                            // Some Android kernels omit /proc/<pid>/task/<tid>/children.
                            // Only inspect cmdlines after status confirms our UID and parent PID.
                            if (childPids.isEmpty()) {
                                for (entry in File("/proc").listFiles().orEmpty()) {
                                    val child = entry.name.toIntOrNull() ?: continue
                                    val status = try { File(entry, "status").readLines() } catch (_: Exception) { continue }
                                    fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.substringBefore('\t')?.substringBefore(' ')?.toIntOrNull()
                                    if (field("Uid") == android.os.Process.myUid() && field("PPid") == pid.get()) childPids += child
                                }
                            }
                            for (child in childPids) {
                                val command = try { File("/proc/$child/cmdline").readText().split('\u0000') } catch (_: Exception) { continue }
                                if (command.firstOrNull() != helper.path) continue
                                if (!helperPids.contains(child)) helperPids.add(child)
                                check(command.drop(1).take(2) == listOf("--listen", "grpc+stdio://")) { "helper_transport" }
                                val descriptors = File("/proc/$child/fd").listFiles() ?: error("helper_fds_unreadable")
                                val links = descriptors.associate { it.name to Files.readSymbolicLink(it.toPath()).toString() }
                                check(links["0"]?.startsWith("pipe:[") == true && links["1"]?.startsWith("pipe:[") == true) { "helper_stdio_not_pipes" }
                                // Tokio/V8 can own Unix wakeup sockets. Compare actual TCP
                                // inodes when procfs permits it; Android may block /proc/net.
                                val socketInodes = links.values.filter { it.startsWith("socket:[") }.map { it.removePrefix("socket:[").removeSuffix("]") }.toSet()
                                val tcpTables = listOf("tcp", "tcp6").map { table ->
                                    runCatching { File("/proc/$child/net/$table").readLines() }.getOrNull()
                                }
                                if (socketInodes.isEmpty() || tcpTables.all { it != null }) {
                                    val tcpInodes = tcpTables.filterNotNull().flatMap { rows -> rows.drop(1).mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(9) } }.toSet()
                                    check(socketInodes.intersect(tcpInodes).isEmpty()) { "helper_tcp_socket_present" }
                                    tcpInspected.set(true)
                                }
                                observed.set(true)
                            }
                        }
                    } catch (_: java.nio.file.NoSuchFileException) {
                        // Native threads/descriptors may finish between /proc reads.
                    } catch (error: Throwable) {
                        observationError.set(error)
                        return@Thread
                    }
                    Thread.sleep(15)
                }
            }, "smoke-native-inspect").apply { isDaemon = true; start() }
            val output = p.outputStream.bufferedWriter()
            fun send(id: Int, method: String, params: JSONObject) {
                output.write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
                output.newLine()
                output.flush()
            }
            fun await(predicate: (JSONObject) -> Boolean): JSONObject {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                while (System.nanoTime() < end) {
                    val message = messages.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (predicate(message)) {
                        check(!message.has("error")) { "native_rpc_error" }
                        return message
                    }
                }
                error("native_rpc_timeout")
            }
            send(1, "initialize", JSONObject().put("clientInfo", JSONObject().put("name", "private_stdio_smoke").put("version", "1")))
            await { it.optInt("id", -1) == 1 }
            output.write("{\"method\":\"initialized\",\"params\":{}}\n")
            output.flush()
            send(2, "thread/start", JSONObject().put("cwd", root.path).put("approvalPolicy", "never").put("sandbox", "read-only"))
            val thread = await { it.optInt("id", -1) == 2 }.getJSONObject("result").getJSONObject("thread").getString("id")
            send(3, "turn/start", JSONObject().put("threadId", thread).put("input", JSONArray().put(JSONObject().put("type", "text").put("text", "Run the private transport fixture."))))
            await { it.optInt("id", -1) == 3 }
            val completed = await { it.optString("method") == "turn/completed" }
            check(completed.getJSONObject("params").getJSONObject("turn").getString("status") == "completed") { "native_turn_failed" }
            check(model.sawToolResult.get()) { "native_js_result_missing" }
            check(pid.get() > 0) { "native_pid_marker_missing" }
            observationError.get()?.let { throw it }
            check(observed.get()) { "native_private_transport_not_observed" }
            output.close()
            return if (tcpInspected.get()) "proc_tcp_verified" else "stdio_args_and_pipes_verified"
        } finally {
            watching.set(false)
            watcher?.join(2000)
            try { process?.outputStream?.close() } catch (_: Exception) {}
            process?.destroy()
            if (process?.waitFor(3, TimeUnit.SECONDS) == false) {
                process?.destroyForcibly()
                process?.waitFor(3, TimeUnit.SECONDS)
            }
            helperPids.forEach { pid ->
                runCatching {
                    if (File("/proc/$pid/cmdline").readText().substringBefore('\u0000').endsWith("/libcodex-modehost.so")) android.os.Process.killProcess(pid)
                }
            }
            model.close()
            root.deleteRecursively()
        }
    }

    private class ModelFixture : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val port: Int get() = server.localPort
        val sawToolResult = AtomicBoolean(false)
        private val calls = AtomicInteger()
        private val thread = Thread({
            try { while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    s.soTimeout = 10_000
                    val input = BufferedInputStream(s.getInputStream())
                    val first = line(input) ?: return@use
                    var length = 0
                    while (true) {
                        val header = line(input) ?: return@use
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    if (length !in 0..(1 shl 20)) return@use
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        if (read < 0) return@use
                        offset += read
                    }
                    if (!first.startsWith("POST /v1/responses ")) {
                        s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return@use
                    }
                    val call = calls.incrementAndGet()
                    if (call > 1) {
                        val items = JSONObject(String(body, Charsets.UTF_8)).getJSONArray("input")
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            if (item.optString("type") == "custom_tool_call_output" && item.opt("output").toString().contains("PRIVATE_STDIO_OK")) sawToolResult.set(true)
                        }
                    }
                    val events = mutableListOf<JSONObject>()
                    if (call == 1) {
                        events += JSONObject().put("type", "response.created").put("response", JSONObject().put("id", "response-1"))
                        events += JSONObject().put("type", "response.output_item.done").put("item", JSONObject()
                            .put("type", "custom_tool_call").put("call_id", "private-call").put("name", "exec")
                            .put("input", "text('PRIVATE_STDIO_OK'); await new Promise(resolve => setTimeout(resolve, 1200));"))
                    } else {
                        events += JSONObject().put("type", "response.output_item.done").put("item", JSONObject()
                            .put("type", "message").put("role", "assistant").put("id", "message-1")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Synthetic fixture completed."))))
                    }
                    events += JSONObject().put("type", "response.completed").put("response", JSONObject().put("id", "response-$call")
                        .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0).put("total_tokens", 0)))
                    val payload = events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }.toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload)
                        flush()
                    }
                }
            } } catch (_: Exception) {
                // The awaiting native request times out and fails the test; background exceptions
                // must never terminate the application's instrumentation process.
            }
        }, "smoke-model-fixture").apply { isDaemon = true; start() }

        private fun line(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 16 * 1024) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(b)
            }
            return null
        }

        override fun close() {
            server.close()
            thread.join(2000)
        }
    }
}
