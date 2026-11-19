package kr.snu.etlassistant.phase0

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kr.snu.etlassistant.etl.EtlService
import org.json.JSONObject

class MainActivity : AppCompatActivity(), LogStore.Listener {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var optProxy: CheckBox
    private lateinit var optSsl: CheckBox
    private lateinit var optSandbox: CheckBox
    private lateinit var optEtl: CheckBox
    private lateinit var etlStatus: TextView

    private val prefs by lazy { getSharedPreferences("phase0", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val pad = (8 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }

        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        optProxy = findViewById(R.id.optProxy)
        optSsl = findViewById(R.id.optSsl)
        optSandbox = findViewById(R.id.optSandbox)

        bindToggle(optProxy, "useProxy")
        bindToggle(optSsl, "setSslCertFile")
        bindToggle(optSandbox, "dangerFullAccess")

        val bin = CodexRunner.binary
        findViewById<TextView>(R.id.info).text = buildString {
            append("codex: ").append(CodexRunner.bundledCodexVersion()).append('\n')
            append(bin.path).append('\n')
            append("exists=").append(bin.exists()).append(" size=").append(if (bin.exists()) bin.length() else -1)
        }

        findViewById<Button>(R.id.btnVersion).setOnClickListener {
            CodexRunner.run("codex --version", listOf("--version"), options(), openUrl = false)
        }
        findViewById<Button>(R.id.btnExec).setOnClickListener {
            val opts = options()
            val args = mutableListOf("exec", "--skip-git-repo-check")
            if (opts.dangerFullAccess) args += listOf("--sandbox", "danger-full-access")
            args += "안녕이라고만 답해"
            CodexRunner.run("codex exec", args, opts, openUrl = false)
        }
        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            CodexRunner.run("codex login", listOf("login"), options(), openUrl = true)
        }
        findViewById<Button>(R.id.btnDeviceAuth).setOnClickListener {
            CodexRunner.run("codex login --device-auth", listOf("login", "--device-auth"), options(), openUrl = true)
        }
        findViewById<Button>(R.id.btnLoginStatus).setOnClickListener {
            CodexRunner.run("codex login status", listOf("login", "status"), options(), openUrl = false)
        }
        val custom = findViewById<EditText>(R.id.customArgs)
        custom.setText(prefs.getString("customArgs", "--help"))
        findViewById<Button>(R.id.btnCustom).setOnClickListener {
            val text = custom.text.toString()
            prefs.edit().putString("customArgs", text).apply()
            val args = CodexRunner.splitArgs(text)
            // 커스텀 인자는 그대로 실행한다(sandbox 토글은 적용하지 않음). login이면 URL 자동 열기.
            CodexRunner.run("codex $text", args, options(), openUrl = args.firstOrNull() == "login")
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { CodexRunner.stop() }
        findViewById<Button>(R.id.btnOpenUrl).setOnClickListener {
            val url = CodexRunner.lastUrl
            if (url == null) toast("아직 추출된 URL이 없습니다") else CodexRunner.openUrl(url)
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("codex phase0 log", CodexRunner.log.snapshot()))
            toast("로그를 복사했습니다")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { CodexRunner.log.clear() }

        // ---- eTL 도구 ----
        optEtl = findViewById(R.id.optEtl)
        bindToggle(optEtl, "useEtlTools")
        etlStatus = findViewById(R.id.etlStatus)
        val tokenInput = findViewById<EditText>(R.id.etlToken)
        findViewById<Button>(R.id.btnTokenSave).setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (!Regex("[\\u0021-\\u007e]{8,}").matches(token)) {
                toast("토큰 형식이 올바르지 않습니다(공백 없는 ASCII 문자)")
            } else {
                try {
                    EtlService.tokens(this).save(token)
                    tokenInput.setText("")
                    CodexRunner.log.line("[app] eTL 토큰을 Keystore로 암호화해 저장했습니다.")
                } catch (e: Exception) {
                    CodexRunner.log.line("[app] 토큰 저장 실패: ${e.javaClass.simpleName}")
                }
                updateEtlStatus()
            }
        }
        findViewById<Button>(R.id.btnTokenClear).setOnClickListener {
            EtlService.tokens(this).clear()
            CodexRunner.log.line("[app] eTL 토큰을 삭제했습니다.")
            updateEtlStatus()
        }
        findViewById<Button>(R.id.btnEtlTest).setOnClickListener { selfTestEtlTools() }
        findViewById<Button>(R.id.btnExecEtl).setOnClickListener {
            val opts = options().copy(useEtlTools = true)
            val args = mutableListOf("exec", "--skip-git-repo-check")
            if (opts.dangerFullAccess) args += listOf("--sandbox", "danger-full-access")
            args += "etl_courses 도구로 내 이번 학기 강의 이름만 한 줄씩 알려줘."
            CodexRunner.run("codex exec (eTL)", args, opts, openUrl = false)
        }
        updateEtlStatus()
    }

    private fun updateEtlStatus() {
        etlStatus.text = "eTL 토큰: " + if (EtlService.tokens(this).hasToken()) "저장됨(Keystore 암호화)" else "없음"
    }

    /** Codex 없이 도구를 직접 호출해 eTL 연결을 확인한다. 결과는 로그에 일부만 찍는다. */
    private fun selfTestEtlTools() {
        val tools = EtlService.tools(this)
        CodexRunner.log.line("")
        CodexRunner.log.line("===== eTL 도구 직접 테스트 =====")
        Thread({
            for ((name, args) in listOf("etl_courses" to JSONObject(), "etl_digest" to JSONObject().put("days", 7))) {
                try {
                    val r = tools.call(name, args)
                    val shown = if (r.text.length > 1800) r.text.substring(0, 1800) + "…(${r.text.length}자)" else r.text
                    CodexRunner.log.line("[etl] $name ${if (r.isError) "오류" else "ok"}: $shown")
                } catch (e: Exception) {
                    CodexRunner.log.line("[etl] $name 예외: ${e.javaClass.simpleName}")
                }
            }
        }, "etl-selftest").start()
    }

    override fun onStart() {
        super.onStart()
        CodexRunner.log.setListener(this)
    }

    override fun onStop() {
        CodexRunner.log.setListener(null)
        super.onStop()
    }

    override fun onAppend(text: String) {
        val atBottom = !logScroll.canScrollVertically(1)
        logView.append(text)
        if (atBottom) scrollToBottom()
    }

    override fun onReset(full: String) {
        logView.text = full
        scrollToBottom()
    }

    private fun scrollToBottom() {
        logScroll.post { logScroll.scrollTo(0, logView.height) }
    }

    private fun bindToggle(box: CheckBox, key: String) {
        box.isChecked = prefs.getBoolean(key, false)
        box.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    private fun options() = CodexRunner.Options(
        useProxy = optProxy.isChecked,
        setSslCertFile = optSsl.isChecked,
        dangerFullAccess = optSandbox.isChecked,
        useEtlTools = optEtl.isChecked,
    )

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
