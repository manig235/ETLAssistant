package kr.snu.etlassistant.chat

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kr.snu.etlassistant.notify.NotificationPoster
import kr.snu.etlassistant.notify.NotifyScheduler
import kr.snu.etlassistant.notify.NotifySettings
import kr.snu.etlassistant.notify.NotifySettingsStore
import kr.snu.etlassistant.notify.NotifyStateStore
import kr.snu.etlassistant.phase0.CodexRunner
import kr.snu.etlassistant.phase0.MainActivity
import kr.snu.etlassistant.phase0.R

/**
 * 설정 화면: ChatGPT 로그인(브라우저 / 기기 코드), API 키(고급), eTL 토큰, 알림.
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

    // 알림 섹션
    private lateinit var notifyTokenWarning: TextView
    private lateinit var notifyDeadline: CheckBox
    private lateinit var notifyAnnouncement: CheckBox
    private lateinit var notifyBrief: CheckBox
    private lateinit var briefArea: View
    private lateinit var btnBrief1: Button
    private lateinit var btnBrief2: Button
    private lateinit var btnBriefAdd: Button
    private lateinit var briefRow2: View
    private lateinit var intervalGroup: RadioGroup
    private lateinit var notifyQuiet: CheckBox
    private lateinit var quietArea: View
    private lateinit var btnQuietStart: Button
    private lateinit var btnQuietEnd: Button
    private lateinit var notifyHide: CheckBox
    private lateinit var notifyPermStatus: TextView
    private lateinit var btnNotifyPerm: Button
    private lateinit var notifyLastCheck: TextView
    private lateinit var notifyLastBrief: TextView

    private val notifyStore by lazy { NotifySettingsStore(this) }
    private var notifySettings = NotifySettings()
    private val is24h get() = DateFormat.is24HourFormat(this)

    /** Android 13+ 알림 권한 요청. 결과 뒤 상태를 갱신하고, 더는 대화상자가 안 뜨는 상태면 앱 알림 설정을 연다. */
    private val notifyPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        updateNotifyPermission()
        if (!granted && Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openAppNotificationSettings()
        }
    }

    /** 수동 실행 뒤 상태 줄을 다시 읽는다(워커가 끝날 시간을 주기 위해 지연 호출). */
    private val refreshNotifyStatus = Runnable { updateNotifyStatusLines() }

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

        setupNotifySection()
    }

    override fun onStart() {
        super.onStart()
        CodexSession.attach(this)
        CodexSession.ensureStarted()
        updateStatuses()
        updateButtons()
        bindNotifySettings()
        updateNotifyPermission()
        updateNotifyStatusLines()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        main.removeCallbacks(refreshNotifyStatus)
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
        notifyTokenWarning.visibility = if (EtlService.tokens(this).hasToken()) View.GONE else View.VISIBLE
    }

    private fun updateButtons() {
        val ready = CodexSession.state == SessionState.READY
        val loggingIn = lastPrompt != null
        btnLoginBrowser.isEnabled = ready && !loggingIn
        btnLoginDevice.isEnabled = ready && !loggingIn
        btnLogout.isEnabled = ready && CodexSession.account?.kind == AccountInfo.Kind.CHATGPT
    }

    // ---- 알림 섹션 -----------------------------------------------------------

    private fun setupNotifySection() {
        notifyTokenWarning = findViewById(R.id.notifyTokenWarning)
        notifyDeadline = findViewById(R.id.notifyDeadline)
        notifyAnnouncement = findViewById(R.id.notifyAnnouncement)
        notifyBrief = findViewById(R.id.notifyBrief)
        briefArea = findViewById(R.id.briefArea)
        btnBrief1 = findViewById(R.id.btnBrief1)
        btnBrief2 = findViewById(R.id.btnBrief2)
        btnBriefAdd = findViewById(R.id.btnBriefAdd)
        briefRow2 = findViewById(R.id.briefRow2)
        intervalGroup = findViewById(R.id.intervalGroup)
        notifyQuiet = findViewById(R.id.notifyQuiet)
        quietArea = findViewById(R.id.quietArea)
        btnQuietStart = findViewById(R.id.btnQuietStart)
        btnQuietEnd = findViewById(R.id.btnQuietEnd)
        notifyHide = findViewById(R.id.notifyHide)
        notifyPermStatus = findViewById(R.id.notifyPermStatus)
        btnNotifyPerm = findViewById(R.id.btnNotifyPerm)
        notifyLastCheck = findViewById(R.id.notifyLastCheck)
        notifyLastBrief = findViewById(R.id.notifyLastBrief)

        notifyDeadline.setOnCheckedChangeListener { _, c ->
            if (!bindingCheckbox) updateNotify { it.copy(deadlineAlerts = c) }
        }
        notifyAnnouncement.setOnCheckedChangeListener { _, c ->
            if (!bindingCheckbox) updateNotify { it.copy(announcementAlerts = c) }
        }
        notifyBrief.setOnCheckedChangeListener { _, c ->
            if (!bindingCheckbox) updateNotify { it.copy(briefEnabled = c) }
        }
        notifyQuiet.setOnCheckedChangeListener { _, c ->
            if (!bindingCheckbox) updateNotify { it.copy(quietEnabled = c) }
        }
        notifyHide.setOnCheckedChangeListener { _, c ->
            if (!bindingCheckbox) updateNotify { it.copy(hideContentOnLockScreen = c) }
        }

        // 브리핑 시각
        btnBrief1.setOnClickListener { pickBriefTime(0) }
        btnBrief2.setOnClickListener { pickBriefTime(1) }
        btnBriefAdd.setOnClickListener {
            val times = notifySettings.briefMinutesOfDay
            if (times.size < 2) {
                // 첫 시각의 12시간 뒤를 기본값으로 넣는다(같은 시각이 되지 않도록).
                updateNotify { it.copy(briefMinutesOfDay = NotifyUiFormat.normalizeBriefTimes(times + (times.first() + 12 * 60) % 1440)) }
            }
        }
        findViewById<Button>(R.id.btnBriefRemove2).setOnClickListener {
            updateNotify { it.copy(briefMinutesOfDay = NotifyUiFormat.normalizeBriefTimes(it.briefMinutesOfDay.take(1))) }
        }

        // 확인 주기
        intervalGroup.setOnCheckedChangeListener { _, id ->
            if (bindingCheckbox) return@setOnCheckedChangeListener
            val hours = intervalOf(id) ?: return@setOnCheckedChangeListener
            updateNotify { it.copy(checkIntervalHours = hours) }
        }

        // 방해 금지
        btnQuietStart.setOnClickListener {
            pickTime(notifySettings.quietStartMinutes) { m -> updateNotify { it.copy(quietStartMinutes = m) } }
        }
        btnQuietEnd.setOnClickListener {
            pickTime(notifySettings.quietEndMinutes) { m -> updateNotify { it.copy(quietEndMinutes = m) } }
        }

        // 권한
        btnNotifyPerm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openAppNotificationSettings()
        }

        // 수동 실행
        findViewById<Button>(R.id.btnCheckNow).setOnClickListener {
            requestManualRun { NotifyScheduler.checkNow(this) }
        }
        findViewById<Button>(R.id.btnBriefNow).setOnClickListener {
            requestManualRun { NotifyScheduler.briefNow(this) }
        }
    }

    /** 설정을 바꾸고 즉시 저장한 뒤 예약을 다시 맞춘다. */
    private fun updateNotify(change: (NotifySettings) -> NotifySettings) {
        notifySettings = change(notifySettings)
        notifyStore.save(notifySettings)
        NotifyScheduler.apply(this)
        bindNotifySettings()
    }

    /** 저장된 설정을 화면에 반영한다. 리스너가 저장을 되부르지 않도록 가드한다. */
    private fun bindNotifySettings() {
        val s = notifyStore.load()
        notifySettings = s
        bindingCheckbox = true
        notifyDeadline.isChecked = s.deadlineAlerts
        notifyAnnouncement.isChecked = s.announcementAlerts
        notifyBrief.isChecked = s.briefEnabled
        notifyQuiet.isChecked = s.quietEnabled
        notifyHide.isChecked = s.hideContentOnLockScreen
        intervalGroup.check(
            when (s.checkIntervalHours) {
                1 -> R.id.interval1
                6 -> R.id.interval6
                12 -> R.id.interval12
                else -> R.id.interval3
            }
        )
        bindingCheckbox = false

        // 브리핑 시각
        briefArea.visibility = if (s.briefEnabled) View.VISIBLE else View.GONE
        val times = NotifyUiFormat.normalizeBriefTimes(s.briefMinutesOfDay)
        btnBrief1.text = NotifyUiFormat.formatTime(times[0], is24h)
        briefRow2.visibility = if (times.size > 1) View.VISIBLE else View.GONE
        if (times.size > 1) btnBrief2.text = NotifyUiFormat.formatTime(times[1], is24h)
        btnBriefAdd.isEnabled = times.size < 2

        // 확인 주기: 마감·공지 알림이 둘 다 꺼져 있으면 비활성
        val intervalOn = s.needsPeriodicCheck
        intervalGroup.alpha = if (intervalOn) 1f else 0.4f
        for (i in 0 until intervalGroup.childCount) {
            (intervalGroup.getChildAt(i) as? RadioButton)?.isEnabled = intervalOn
        }

        // 방해 금지
        quietArea.visibility = if (s.quietEnabled) View.VISIBLE else View.GONE
        btnQuietStart.text = "시작 " + NotifyUiFormat.formatTime(s.quietStartMinutes, is24h)
        btnQuietEnd.text = "종료 " + NotifyUiFormat.formatTime(s.quietEndMinutes, is24h)
    }

    private fun intervalOf(id: Int): Int? = when (id) {
        R.id.interval1 -> 1
        R.id.interval3 -> 3
        R.id.interval6 -> 6
        R.id.interval12 -> 12
        else -> null
    }

    private fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        val m = initial.coerceIn(0, 1439)
        TimePickerDialog(this, { _, h, min -> onPicked(h * 60 + min) }, m / 60, m % 60, is24h).show()
    }

    private fun pickBriefTime(index: Int) {
        val times = notifySettings.briefMinutesOfDay
        val current = times.getOrNull(index) ?: return
        pickTime(current) { picked ->
            val next = times.toMutableList().also { it[index] = picked }
            // 같은 시각이면 하나로 합치고, 정렬·개수 제한은 normalize가 맡는다.
            updateNotify { it.copy(briefMinutesOfDay = NotifyUiFormat.normalizeBriefTimes(next)) }
        }
    }

    private fun updateNotifyPermission() {
        val ok = NotificationPoster.canPost(this)
        notifyPermStatus.text = if (ok) "알림 권한: 허용됨" else "알림 권한: 꺼져 있음"
        btnNotifyPerm.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "알림 설정을 열지 못했습니다. 시스템 설정에서 직접 허용해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateNotifyStatusLines() {
        val st = NotifyStateStore(this).load()
        notifyLastCheck.text = NotifyUiFormat.describeLastCheck(st.lastCheckAt, st.lastResult)
        notifyLastBrief.text = NotifyUiFormat.describeLastBrief(st.lastBriefAt)
    }

    /** 지금 확인/브리핑 미리 보내기. 권한이 없어도 실행은 하되(워커가 결과를 남긴다) 먼저 안내한다. */
    private fun requestManualRun(run: () -> Unit) {
        if (!NotificationPoster.canPost(this)) {
            Toast.makeText(this, "알림 권한이 꺼져 있어 알림이 뜨지 않습니다. 위에서 권한을 허용하세요.", Toast.LENGTH_LONG).show()
        }
        run()
        Toast.makeText(this, "요청했습니다. 잠시 뒤 결과가 표시됩니다.", Toast.LENGTH_SHORT).show()
        main.removeCallbacks(refreshNotifyStatus)
        main.postDelayed(refreshNotifyStatus, 5_000L)
        main.postDelayed(refreshNotifyStatus, 15_000L)
    }

    companion object {
        /** 형식만 대충 본다(실제 유효성은 OpenAI가 판단). 공백, 한글 등은 거른다. */
        fun isPlausibleApiKey(s: String): Boolean = s.startsWith("sk-") && s.length >= 20 && s.all { it.code in 0x21..0x7e }

        /** `sk-…abcd`처럼 끝 4자리만 보인다. */
        fun maskKey(s: String): String = if (s.length <= 8) "••••" else "sk-…" + s.takeLast(4)
    }
}
