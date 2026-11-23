package kr.snu.etlassistant.phase0

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import kr.snu.etlassistant.etl.CodexConfig
import kr.snu.etlassistant.etl.EtlService
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Codex 프로세스를 실행/관리하는 싱글턴.
 * 액티비티와 수명이 분리돼 있어서, `codex login`이 localhost:1455 콜백을 기다리는 동안
 * 사용자가 브라우저로 나갔다 와도(액티비티 재생성 포함) 프로세스가 유지된다.
 */
object CodexRunner {

    data class Options(
        val useProxy: Boolean,
        val setSslCertFile: Boolean,
        val dangerFullAccess: Boolean,
        val useEtlTools: Boolean = false,
        /** 설정하면 OPENAI_API_KEY 환경변수로만 넘긴다(auth.json에 남기지 않는다). */
        val apiKey: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    val log = LogStore(main)

    private lateinit var app: Context
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "codex-runner").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    @Volatile private var process: Process? = null
    @Volatile var lastUrl: String? = null
        private set
    private var proxy: LocalConnectProxy? = null

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]")
    private val URL = Regex("https://[^\\s\"'<>\\u001B]+")
    private val PROXY_VARS = listOf("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy")

    fun init(context: Context) {
        app = context.applicationContext
        val logFile = File(app.filesDir, "phase0.log")
        // 0.2.8까지의 로그 파일은 크기 제한이 없었고 로그인 주소·기기 코드를 그대로 남겼다. 한 번만 지우고 새로 시작한다.
        val redactedMark = File(app.filesDir, "phase0.log.redacted")
        if (!redactedMark.exists()) {
            logFile.delete()
            File(logFile.path + ".1").delete()
            try { redactedMark.createNewFile() } catch (_: IOException) {}
        }
        log.mirror = logFile
    }

    val isRunning: Boolean get() = busy.get()

    val binary: File get() = File(app.applicationInfo.nativeLibraryDir, "libcodex.so")

    fun bundledCodexVersion(): String = try {
        app.assets.open("codex-version.txt").bufferedReader().use { it.readText().trim() }
    } catch (_: IOException) {
        "(codex-version.txt 없음)"
    }

    /**
     * @param args codex 뒤에 붙는 인자
     * @param openUrl 출력에서 처음 나온 https:// URL을 브라우저로 연다(login용)
     */
    fun run(label: String, args: List<String>, opts: Options, openUrl: Boolean) {
        if (!busy.compareAndSet(false, true)) {
            log.line("[app] 이미 실행 중인 프로세스가 있습니다. 먼저 Stop을 누르세요.")
            return
        }
        try {
            RunService.acquire(app)
        } catch (t: Throwable) {
            log.line("[app] 포그라운드 서비스 시작 실패: ${t.javaClass.name}: ${t.message}")
        }
        executor.execute {
            try {
                runBlocking(label, args, opts, openUrl)
            } catch (t: Throwable) {
                log.line("[app] 예외: ${t.javaClass.name}: ${t.message}")
            } finally {
                process = null
                busy.set(false)
                EtlService.stopServer()
                RunService.release(app)
            }
        }
    }

    fun stop() {
        val p = process
        if (p == null) {
            log.line("[app] 실행 중인 프로세스가 없습니다.")
            return
        }
        log.line("[app] Stop: destroy()")
        p.destroy()
        main.postDelayed({
            if (p.isAlive) {
                log.line("[app] 2초 후에도 살아 있어 destroyForcibly()")
                p.destroyForcibly()
            }
        }, 2000)
    }

    /** 시작 직전까지 준비된 프로세스. */
    class Prepared(val pb: ProcessBuilder, val workspace: File, val args: List<String>)

    /**
     * 환경변수, 프록시, CA 번들, eTL MCP 서버, config.toml까지 준비하고 시작 직전의 [ProcessBuilder]를 돌려준다.
     * 개발 화면(exec, login 등)과 채팅 세션(app-server)이 같이 쓴다.
     */
    fun prepare(args: List<String>, opts: Options, label: String? = null): Prepared? {
        if (label != null) {
            log.line("")
            log.line("===== $label =====")
        }
        log.line("[app] device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ABIs=${Build.SUPPORTED_ABIS.joinToString(",")}")
        log.line("[app] bundled codex: ${bundledCodexVersion()}")
        log.line("[app] options: proxy=${opts.useProxy}, SSL_CERT_FILE=${opts.setSslCertFile}, danger-full-access=${opts.dangerFullAccess}, apiKey=${opts.apiKey != null}")

        val bin = binary
        log.line("[app] binary: ${bin.path}")
        log.line("[app]   exists=${bin.exists()} size=${if (bin.exists()) bin.length() else -1} canExecute=${bin.canExecute()}")
        if (!bin.exists()) {
            log.line("[app] 바이너리가 없습니다. scripts/fetch-codex.sh를 실행한 뒤 다시 빌드하세요.")
            return null
        }

        val files = app.filesDir
        val home = File(files, "home").apply { mkdirs() }
        val codexHome = File(files, "codex-home").apply { mkdirs() }
        val workspace = File(files, "workspace").apply { mkdirs() }

        val pb = ProcessBuilder(listOf(bin.path) + args).directory(workspace)
        val env = pb.environment()
        val set = LinkedHashMap<String, String>()
        set["HOME"] = home.path
        set["CODEX_HOME"] = codexHome.path
        set["TMPDIR"] = app.cacheDir.path
        set["SHELL"] = "/system/bin/sh"
        val inheritedPath = env["PATH"].orEmpty()
        set["PATH"] = if (inheritedPath.split(':').contains("/system/bin")) inheritedPath
        else listOf("/system/bin", inheritedPath).filter { it.isNotEmpty() }.joinToString(":")
        set["RUST_BACKTRACE"] = "1"
        set["NO_COLOR"] = "1"

        val unset = mutableListOf<String>()
        PROXY_VARS.forEach { env.remove(it) }
        if (opts.useProxy) {
            val p = ensureProxy()
            if (p == null) {
                log.line("[app] 프록시를 시작하지 못했습니다. 프록시 없이 진행합니다.")
            } else {
                // 프록시를 띄울 때(앱 프로세스마다) 새로 만든 자격 증명이 든 주소. 로그에는 사용자 정보를 가려 남긴다.
                val url = p.url
                set["HTTPS_PROXY"] = url
                set["HTTP_PROXY"] = url
                set["https_proxy"] = url
                set["http_proxy"] = url
                set["ALL_PROXY"] = url
                set["NO_PROXY"] = "localhost,127.0.0.1"
            }
        }

        env.remove("SSL_CERT_FILE")
        if (opts.setSslCertFile) {
            val pem = File(files, "cacert.pem")
            try {
                app.assets.open("cacert.pem").use { input -> pem.outputStream().use { input.copyTo(it) } }
                set["SSL_CERT_FILE"] = pem.path
                if (env.remove("SSL_CERT_DIR") != null) unset += "SSL_CERT_DIR"
                log.line("[app] cacert.pem 복사: ${pem.path} (${pem.length()} bytes)")
            } catch (e: IOException) {
                log.line("[app] cacert.pem 복사 실패: ${e.message} (assets에 없으면 fetch-codex.sh를 실행하세요)")
            }
        }

        // 사용자가 넣은 API 키는 환경변수로만 넘긴다. 이미 환경에 있던 키는 쓰지 않는다.
        env.remove("OPENAI_API_KEY")
        env.remove("CODEX_API_KEY")
        opts.apiKey?.let { set["OPENAI_API_KEY"] = it }

        // eTL 도구(MCP): 실행마다 서버를 새로 띄우고 새 비밀값을 환경변수로만 넘긴다.
        var mcpPort: Int? = null
        if (opts.useEtlTools) {
            if (!EtlService.tokens(app).hasToken()) {
                log.line("[app] eTL 토큰이 저장돼 있지 않아 eTL 도구 없이 진행합니다.")
            } else {
                val server = EtlService.startServer(app) { log.line(it) }
                mcpPort = server.port
                set[CodexConfig.SECRET_ENV] = server.secret
                log.line("[app] eTL MCP 서버: 127.0.0.1:$mcpPort")
            }
        }
        CodexConfig.write(codexHome, mcpPort)

        env.putAll(set)

        log.line("[app] cwd: ${workspace.path}")
        log.line("[app] cmd: ${(listOf(bin.path) + args).joinToString(" ") { shellQuote(it) }}")
        set.forEach { (k, v) -> log.line("[app] env $k=${if (k in SECRET_ENVS) "***" else if (k in PROXY_VARS) v.replace(Regex("//[^@/]*@"), "//***@") else v}") }
        unset.forEach { log.line("[app] env unset $it") }
        return Prepared(pb, workspace, args)
    }

    /** code-mode host 프로세스와 포트. */
    class Host(val process: Process, val port: Int) {
        fun stop() {
            process.destroy()
        }
    }

    /**
     * code-mode host를 띄운다. ChatGPT 계정 모델은 전부 code_mode_only라서 MCP 도구를 부르려면 필요하다.
     * `libcodex-code-mode-host.so`는 `nativeLibraryDir`에서 직접 실행한다.
     */
    fun startHost(workspace: File): Host? {
        val hostBin = File(app.applicationInfo.nativeLibraryDir, "libcodex-code-mode-host.so")
        if (!hostBin.exists()) {
            log.line("[app] code-mode host가 없습니다(libcodex-code-mode-host.so). scripts/fetch-codex.sh를 실행하세요.")
            return null
        }
        val port = freePort()
        val proc = try {
            ProcessBuilder(hostBin.path, "--listen", "grpc://127.0.0.1:$port").directory(workspace).start()
        } catch (e: IOException) {
            log.line("[app] code-mode host 실행 실패: ${e.message}")
            return null
        }
        pump(proc.inputStream, "host")
        pump(proc.errorStream, "host-err")
        if (!waitForPort(port, 8000)) {
            log.line("[app] code-mode host가 8초 안에 포트를 열지 않았습니다.")
            proc.destroyForcibly()
            return null
        }
        log.line("[app] code-mode host: 127.0.0.1:$port")
        return Host(proc, port)
    }

    /** app-server를 stdio로 띄우고 호스트에 연결하는 인자. */
    fun appServerArgs(host: Host): List<String> =
        listOf("app-server", "--listen", "stdio://", "--code-mode-host", "http://127.0.0.1:${host.port}", "--enable", "code_mode_host")

    fun pump(stream: InputStream, tag: String) {
        readerThread(stream, tag, AtomicBoolean(true))
    }

    private val SECRET_ENVS = setOf(CodexConfig.SECRET_ENV, "OPENAI_API_KEY", "CODEX_API_KEY")

    private fun runBlocking(label: String, args: List<String>, opts: Options, openUrl: Boolean) {
        val prep = prepare(args, opts, label) ?: return
        val started = System.nanoTime()
        val p = try {
            prep.pb.start()
        } catch (e: IOException) {
            log.line("[app] 실행 실패(start): ${e.message}")
            return
        }
        process = p
        log.line("[app] started")
        try { p.outputStream.close() } catch (_: IOException) {} // stdin을 닫아 둔다(exec가 stdin을 기다리지 않도록).

        val urlOpened = AtomicBoolean(!openUrl)
        val tOut = readerThread(p.inputStream, "out", urlOpened)
        val tErr = readerThread(p.errorStream, "err", urlOpened)
        val code = p.waitFor()
        tOut.join(3000)
        tErr.join(3000)
        val ms = (System.nanoTime() - started) / 1_000_000
        val sig = if (code > 128) " (signal ${code - 128}?)" else ""
        log.line("[app] exit code: $code$sig, duration: ${ms} ms")
    }

    private fun readerThread(stream: InputStream, tag: String, urlOpened: AtomicBoolean): Thread {
        val t = Thread({
            try {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        val line = raw.replace(ANSI, "")
                        log.line("[$tag] $line")
                        if (!urlOpened.get()) {
                            URL.find(line)?.value?.trimEnd('.', ',', ')', ']')?.let { url ->
                                if (urlOpened.compareAndSet(false, true)) openUrl(url)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                log.line("[app] $tag reader closed: ${e.message}")
            }
        }, "codex-$tag")
        t.isDaemon = true
        t.start()
        return t
    }

    fun openUrl(url: String) {
        lastUrl = url
        log.line("[app] 브라우저로 URL 열기: $url")
        main.post {
            try {
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                log.line("[app] URL을 열 앱이 없습니다: ${e.message}")
            } catch (e: Exception) {
                log.line("[app] URL 열기 실패: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun ensureProxy(): LocalConnectProxy? {
        proxy?.let { return it }
        return try {
            LocalConnectProxy({ log.line(it) }).start().also { proxy = it }
        } catch (e: IOException) {
            log.line("[proxy] start 실패: ${e.message}")
            null
        }
    }

    private fun freePort(): Int = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }
                return true
            } catch (_: IOException) {
                Thread.sleep(150)
            }
        }
        return false
    }

    private fun shellQuote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_./=:,@%+" }) s
        else "'" + s.replace("'", "'\\''") + "'"

    /** 간단한 셸식 인자 분리(작은따옴표, 큰따옴표, 백슬래시). */
    fun splitArgs(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inArg = false
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote == '\'' -> if (c == '\'') quote = null else cur.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = null
                    c == '\\' && i + 1 < input.length && input[i + 1] in "\"\\$`" -> { cur.append(input[i + 1]); i++ }
                    else -> cur.append(c)
                }
                c == '\'' || c == '"' -> { quote = c; inArg = true }
                c == '\\' && i + 1 < input.length -> { cur.append(input[i + 1]); i++; inArg = true }
                c.isWhitespace() -> if (inArg) { out += cur.toString(); cur.setLength(0); inArg = false }
                else -> { cur.append(c); inArg = true }
            }
            i++
        }
        if (inArg) out += cur.toString()
        return out
    }
}
