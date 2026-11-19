package kr.snu.etlassistant.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.AppSettings
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.LoginKind
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.etl.ApiKeyStore
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.phase0.CodexRunner
import kr.snu.etlassistant.phase0.MainActivity
import kr.snu.etlassistant.phase0.R

/**
 * 설정 화면: ChatGPT 로그인(브라우저 / 기기 코드), API 키(고급), eTL 토큰.
 * 이 화면이 열려 있는 동안에도 Codex 세션이 유지돼야 로그인 알림을 받을 수 있으므로 세션 리스너로 붙는다.
 */
class SettingsActivity : AppCompatActivity(), CodexSession.Listener {

    private lateinit var accountStatus: TextView
    private lateinit var btnLoginBrowser: Button
    private lateinit var btnLoginDevice: Button
    private lateinit var btnLogout: Button
    private lateinit var loginPanel: LinearLayout
    private lateinit var loginInfo: TextView
    private lateinit var loginCode: TextView
    private lateinit var btnCopyCode: Button
    private lateinit var apiKeyStatus: TextView
    private lateinit var useApiKey: CheckBox
    private lateinit var etlStatus: TextView

    private val apiKeys by lazy { ApiKeyStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private var lastPrompt: SessionEvent.LoginPrompt? = null
    private var expiresAt = 0L
    private var bindingCheckbox = false

    private val tick = object : Runnable {
        override fun run() {
            val p = lastPrompt ?: return
            if (p.kind == LoginKind.DEVICE_CODE && expiresAt > 0) {
                val left = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                loginInfo.text = deviceInfo() + "\n남은 시간 %d:%02d".format(left / 60, left % 60)
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val root = findViewById<View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        accountStatus = findViewById(R.id.accountStatus)
        btnLoginBrowser = findViewById(R.id.btnLoginBrowser)
        btnLoginDevice = findViewById(R.id.btnLoginDevice)
        btnLogout = findViewById(R.id.btnLogout)
        loginPanel = findViewById(R.id.loginPanel)
        loginInfo = findViewById(R.id.loginInfo)
        loginCode = findViewById(R.id.loginCode)
        btnCopyCode = findViewById(R.id.btnCopyCode)
        apiKeyStatus = findViewById(R.id.apiKeyStatus)
        useApiKey = findViewById(R.id.useApiKey)
        etlStatus = findViewById(R.id.etlStatus)

        btnLoginBrowser.setOnClickListener { CodexSession.loginBrowser() }
        btnLoginDevice.setOnClickListener { CodexSession.loginDeviceCode() }
        btnLogout.setOnClickListener { CodexSession.logout() }
        btnCopyCode.setOnClickListener {
            lastPrompt?.code?.let {
                CodexSession.copyToClipboard(it)
                Toast.makeText(this, "코드를 복사했습니다", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnOpenLogin).setOnClickListener { lastPrompt?.let { CodexRunner.openUrl(it.url) } }
        findViewById<Button>(R.id.btnCancelLogin).setOnClickListener { CodexSession.cancelLogin() }

        // API 키
        val apiInput = findViewById<EditText>(R.id.apiKeyInput)
        findViewById<Button>(R.id.btnApiKeySave).setOnClickListener {
            val key = apiInput.text.toString().trim()
            if (!isPlausibleApiKey(key)) {
                Toast.makeText(this, "API 키 형식이 아닙니다(sk-로 시작, 공백 없음)", Toast.LENGTH_LONG).show()
            } else {
                try {
                    apiKeys.save(key)
                    apiInput.setText("")
                    Toast.makeText(this, "API 키를 저장했습니다", Toast.LENGTH_SHORT).show()
                    if (AppSettings.authMode == AppSettings.AUTH_API_KEY) CodexSession.restart()
                } catch (e: Exception) {
                    Toast.makeText(this, "저장하지 못했습니다(${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
                }
                updateStatuses()
            }
        }
        findViewById<Button>(R.id.btnApiKeyClear).setOnClickListener {
            apiKeys.clear()
            if (AppSettings.authMode == AppSettings.AUTH_API_KEY) {
                AppSettings.authMode = AppSettings.AUTH_CHATGPT
                CodexSession.restart()
            }
            updateStatuses()
        }
        useApiKey.setOnCheckedChangeListener { _, checked ->
            if (bindingCheckbox) return@setOnCheckedChangeListener
            if (checked && !apiKeys.has()) {
                Toast.makeText(this, "먼저 API 키를 저장하세요", Toast.LENGTH_SHORT).show()
                bindingCheckbox = true
                useApiKey.isChecked = false
                bindingCheckbox = false
            } else {
                AppSettings.authMode = if (checked) AppSettings.AUTH_API_KEY else AppSettings.AUTH_CHATGPT
                CodexSession.restart()
                updateStatuses()
            }
        }

        // eTL 토큰
        val tokenInput = findViewById<EditText>(R.id.etlTokenInput)
        findViewById<Button>(R.id.btnTokenSave).setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (!Regex("[\\u0021-\\u007e]{8,}").matches(token)) {
                Toast.makeText(this, "토큰 형식이 올바르지 않습니다(공백 없는 ASCII 문자)", Toast.LENGTH_LONG).show()
            } else {
                try {
                    EtlService.tokens(this).save(token)
                    tokenInput.setText("")
                    Toast.makeText(this, "eTL 토큰을 저장했습니다", Toast.LENGTH_SHORT).show()
                    CodexSession.restart() // MCP 서버를 새로 등록해야 한다
                } catch (e: Exception) {
                    Toast.makeText(this, "저장하지 못했습니다(${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
                }
                updateStatuses()
            }
        }
        findViewById<Button>(R.id.btnTokenClear).setOnClickListener {
            EtlService.tokens(this).clear()
            CodexSession.restart()
            updateStatuses()
        }
        findViewById<Button>(R.id.btnOpenEtl).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myetl.snu.ac.kr/profile/settings")))
        }
        findViewById<Button>(R.id.btnDev).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
    }

    override fun onStart() {
        super.onStart()
        CodexSession.attach(this)
        CodexSession.ensureStarted()
        updateStatuses()
        updateButtons()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        CodexSession.detach(this)
        super.onStop()
    }

    // ---- 이벤트 --------------------------------------------------------------

    override fun onEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.LoginPrompt -> showLogin(e)
            is SessionEvent.LoginDone -> {
                hideLogin()
                Toast.makeText(this, if (e.success) "로그인했습니다" else "로그인하지 못했습니다: ${e.error ?: ""}", Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
        updateStatuses()
        updateButtons()
    }

    private fun showLogin(p: SessionEvent.LoginPrompt) {
        val first = lastPrompt?.url != p.url
        lastPrompt = p
        if (first) expiresAt = System.currentTimeMillis() + 15 * 60_000L
        loginPanel.visibility = View.VISIBLE
        val device = p.kind == LoginKind.DEVICE_CODE
        loginCode.visibility = if (device) View.VISIBLE else View.GONE
        btnCopyCode.visibility = if (device) View.VISIBLE else View.GONE
        loginCode.text = p.code.orEmpty()
        loginInfo.text = if (device) deviceInfo() else "브라우저에서 ChatGPT 로그인을 마치세요. 끝나면 알림이 오고, 이 앱으로 돌아오면 됩니다."
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun deviceInfo() = "코드를 클립보드에 복사해 두었습니다. 브라우저의 코드 입력칸에 붙여넣으세요(하이픈 포함 9자)."

    private fun hideLogin() {
        lastPrompt = null
        loginPanel.visibility = View.GONE
        main.removeCallbacks(tick)
    }

    // ---- 상태 표시 -----------------------------------------------------------

    private fun updateStatuses() {
        val a = CodexSession.account
        accountStatus.text = when {
            CodexSession.state == SessionState.FAILED -> "비서를 시작하지 못했습니다: ${CodexSession.stateDetail ?: ""}"
            CodexSession.state != SessionState.READY -> "준비하는 중…"
            a == null -> "확인하는 중…"
            a.kind == AccountInfo.Kind.CHATGPT -> "로그인됨 · ${a.email ?: "ChatGPT"}" + (a.plan?.let { " · $it" } ?: "")
            a.kind == AccountInfo.Kind.API_KEY -> "API 키로 사용 중"
            a.kind == AccountInfo.Kind.UNKNOWN -> "로그인됨"
            else -> "로그인되지 않음"
        }
        val key = if (apiKeys.has()) apiKeys.load() else null
        apiKeyStatus.text = when {
            !apiKeys.has() -> "저장된 API 키 없음"
            key == null -> "저장된 키를 읽지 못했습니다(삭제 후 다시 저장하세요)"
            else -> "저장됨 · " + maskKey(key)
        }
        bindingCheckbox = true
        useApiKey.isChecked = AppSettings.authMode == AppSettings.AUTH_API_KEY
        bindingCheckbox = false
        etlStatus.text = if (EtlService.tokens(this).hasToken()) "저장됨(Keystore 암호화)" else "저장된 토큰 없음"
    }

    private fun updateButtons() {
        val ready = CodexSession.state == SessionState.READY
        val loggingIn = lastPrompt != null
        btnLoginBrowser.isEnabled = ready && !loggingIn
        btnLoginDevice.isEnabled = ready && !loggingIn
        btnLogout.isEnabled = ready && CodexSession.account?.kind == AccountInfo.Kind.CHATGPT
    }

    companion object {
        /** 형식만 대충 본다(실제 유효성은 OpenAI가 판단). 공백, 한글 등은 거른다. */
        fun isPlausibleApiKey(s: String): Boolean = s.startsWith("sk-") && s.length >= 20 && s.all { it.code in 0x21..0x7e }

        /** `sk-…abcd`처럼 끝 4자리만 보인다. */
        fun maskKey(s: String): String = if (s.length <= 8) "••••" else "sk-…" + s.takeLast(4)
    }
}
