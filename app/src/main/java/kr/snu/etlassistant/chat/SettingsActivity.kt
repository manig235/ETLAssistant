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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kr.snu.etlassistant.codex.AppSettings
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.etl.ApiKeyStore
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.notify.BatteryOpt
import kr.snu.etlassistant.notify.NotificationPoster
import kr.snu.etlassistant.notify.NotifyScheduler
import kr.snu.etlassistant.notify.NotifySettings
import kr.snu.etlassistant.notify.NotifySettingsStore
import kr.snu.etlassistant.notify.NotifyStateStore
import kr.snu.etlassistant.onboarding.OnboardingActivity
import kr.snu.etlassistant.phase0.CodexRunner
import kr.snu.etlassistant.update.UpdateChecker
import kr.snu.etlassistant.update.UpdateLogic
import kr.snu.etlassistant.update.UpdateScheduler
import kr.snu.etlassistant.update.UpdateStore

/**
 * 설정 화면: ChatGPT 계정(브라우저 / 기기 코드), eTL 토큰, 알림, AI 모델, API 키(고급). 화면은 Montage(Wanted 디자인 시스템)로 그린다.
 * 이 화면이 열려 있는 동안에도 Codex 세션이 유지돼야 로그인 알림을 받을 수 있으므로 세션 리스너로 붙는다.
 */
class SettingsActivity : AppCompatActivity(), CodexSession.Listener {

    private val state = SettingsState()
    private val apiKeys by lazy { ApiKeyStore(this) }
    private val notifyStore by lazy { NotifySettingsStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private val updateStore by lazy { UpdateStore(this) }

    /** 로그인 코드 만료 시각의 기준이 되는 마지막 로그인 URL(같은 로그인이 다시 전달돼도 15분을 리셋하지 않는다). */
    private var lastPromptUrl: String? = null

    /** Android 13+ 알림 권한 요청. 결과 뒤 상태를 갱신하고, 더는 대화상자가 안 뜨는 상태면 앱 알림 설정을 연다. */
    private val notifyPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshNotifyPermission()
        if (!granted && Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openAppNotificationSettings()
        }
    }

    /** 수동 실행 뒤 상태 줄을 다시 읽는다(워커가 끝날 시간을 주기 위해 지연 호출). */
    private val refreshNotifyStatus = Runnable { refreshNotifyStatusLines() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = SettingsActions(
            back = { finish() },
            loginBrowser = { CodexSession.loginBrowser() },
            loginDevice = { CodexSession.loginDeviceCode() },
            logout = { CodexSession.logout() },
            cancelLogin = { CodexSession.cancelLogin() },
            reopenLogin = { url -> CodexRunner.openUrl(url) },
            copyCode = { code ->
                CodexSession.copyToClipboard(code)
                toast("코드를 복사했어요")
            },
            saveApiKey = ::saveApiKey,
            clearApiKey = ::clearApiKey,
            setUseApiKey = ::setUseApiKey,
            saveEtlToken = ::saveEtlToken,
            clearEtlToken = ::clearEtlToken,
            openEtlSettings = {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myetl.snu.ac.kr/profile/settings")))
            },
            updateNotify = ::updateNotify,
            pickTime = ::pickTime,
            requestNotifyPermission = {
                if (Build.VERSION.SDK_INT >= 33) notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openAppNotificationSettings()
            },
            requestBatteryExemption = {
                BatteryOpt.request(this)
                toast(BatteryOpt.SAMSUNG_HINT, long = true)
            },
            checkNow = { requestManualRun { NotifyScheduler.checkNow(this) } },
            briefNow = { requestManualRun { NotifyScheduler.briefNow(this) } },
            setModelChoice = { choice ->
                CodexSession.setModelChoice(choice)
                state.modelChoice = CodexSession.modelChoice
            },
            replayOnboarding = { startActivity(Intent(this, OnboardingActivity::class.java)) },
            openHistory = { startActivity(Intent(this, NotifyHistoryActivity::class.java)) },
            openTimetable = { startActivity(Intent(this, TimetableActivity::class.java)) },
            openMyDeadlines = { startActivity(Intent(this, MyDeadlinesActivity::class.java)) },
            setCasualTone = { v ->
                AppSettings.casualTone = v
                state.casualTone = v
            },
            setUpdateAlerts = { v ->
                updateStore.enabled = v
                state.updateAlerts = v
                UpdateScheduler.apply(this)
            },
            checkUpdate = ::checkUpdate,
            setThemeMode = { mode ->
                state.themeMode = mode
                AppAppearance.save(this, mode)
            },
            openUpdate = {
                val url = state.updateUrl
                if (url != null && UpdateLogic.isAllowedDownloadUrl(url)) {
                    if (runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure) {
                        toast("링크를 열 앱이 없어요: $url", long = true)
                    }
                }
            },
        )
        setContent { SettingsRoot(state, actions) }
    }

    override fun onStart() {
        super.onStart()
        state.themeMode = AppAppearance.load(this)
        // attach가 진행 중인 로그인(없으면 LoginIdle)을 알려 주므로 로그인 패널은 그것으로 맞춰진다.
        CodexSession.attach(this)
        CodexSession.ensureStarted()
        syncSession()
        refreshSecrets()
        refreshNotifySettings()
        refreshNotifyPermission()
        refreshNotifyStatusLines()
        refreshUpdate()
        state.modelChoice = CodexSession.modelChoice
        maybeLoadModels()
    }

    override fun onStop() {
        main.removeCallbacks(refreshNotifyStatus)
        CodexSession.detach(this, isChangingConfigurations)
        super.onStop()
    }

    // ---- 세션 이벤트 -----------------------------------------------------------

    override fun onEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.LoginPrompt -> {
                if (lastPromptUrl != e.url) {
                    lastPromptUrl = e.url
                    state.loginExpiresAt = System.currentTimeMillis() + SettingsLogic.LOGIN_TTL_MS
                }
                state.loginPrompt = e
            }
            is SessionEvent.LoginDone -> {
                state.loginPrompt = null
                lastPromptUrl = null
                toast(if (e.success) "로그인했어요" else "로그인하지 못했어요: ${e.error ?: ""}", long = !e.success)
            }
            SessionEvent.LoginIdle -> {
                state.loginPrompt = null
                lastPromptUrl = null
            }
            is SessionEvent.Models -> {
                state.models = e.models
                state.modelsLoading = e.loading
                state.modelChoice = CodexSession.modelChoice
            }
            is SessionEvent.State -> if (e.state == SessionState.READY) maybeLoadModels()
            else -> {}
        }
        syncSession()
    }

    private fun syncSession() {
        state.sessionState = CodexSession.state
        state.sessionDetail = CodexSession.stateDetail
        state.account = CodexSession.account
    }

    /** 목록이 비어 있고 불러오는 중도 아니면 다시 불러온다(세션이 준비되기 전이면 준비된 뒤 한 번 더 시도한다). */
    private fun maybeLoadModels() {
        if (CodexSession.models.isEmpty() && !CodexSession.modelsLoading) CodexSession.refreshModels()
    }

    // ---- 업데이트 확인 -------------------------------------------------------------

    /** 저장된 최신 Release가 지금 버전보다 새것일 때만 "새 버전" 링크를 보인다(이미 업데이트했으면 사라진다). */
    private fun refreshUpdate() {
        state.updateAlerts = updateStore.enabled
        val latest = updateStore.latest?.takeIf { UpdateLogic.isNewer(it.tag, UpdateChecker.currentVersion(this)) }
        state.updateVersion = latest?.version
        state.updateUrl = latest?.downloadUrl
    }

    /** "업데이트 확인": 사용자가 직접 누른 것이라 알림 설정이 꺼져 있어도 확인하고, 알림 대신 화면에 결과를 보인다. */
    private fun checkUpdate() {
        if (state.updateChecking) return
        state.updateChecking = true
        state.updateResult = null
        val app = applicationContext
        Thread {
            val outcome = UpdateChecker.check(app, notify = false)
            main.post {
                state.updateChecking = false
                state.updateResult = when (outcome) {
                    is UpdateChecker.Outcome.Available -> null
                    is UpdateChecker.Outcome.UpToDate -> "설치된 버전 ${UpdateChecker.currentVersion(app) ?: "?"} · 최신 공개 버전 ${outcome.latestVersion}"
                    is UpdateChecker.Outcome.Failed -> "확인하지 못했어요(${outcome.reason}). 인터넷 연결을 확인해 주세요"
                }
                refreshUpdate()
            }
        }.start()
    }

    // ---- API 키 / eTL 토큰 -------------------------------------------------------

    /** 저장소 상태를 화면에 반영한다. 키 복호화가 들어 있어 필요한 때만 부른다. */
    private fun refreshSecrets() {
        val has = apiKeys.has()
        state.hasApiKey = has
        state.apiKeyMasked = if (has) apiKeys.load()?.let { SettingsLogic.maskKey(it) } else null
        state.useApiKey = AppSettings.authMode == AppSettings.AUTH_API_KEY
        state.casualTone = AppSettings.casualTone
        state.hasEtlToken = EtlService.tokens(this).hasToken()
    }

    /** @return 저장했으면 true(입력칸을 비운다). */
    private fun saveApiKey(raw: String): Boolean {
        val key = raw.trim()
        if (!SettingsLogic.isPlausibleApiKey(key)) {
            toast("API 키 형식이 아니에요(sk-로 시작, 공백 없음)", long = true)
            return false
        }
        var saved = false
        try {
            apiKeys.save(key)
            saved = true
            toast("API 키를 저장했어요")
            if (AppSettings.authMode == AppSettings.AUTH_API_KEY) CodexSession.restart()
        } catch (e: Exception) {
            toast("저장하지 못했어요(${e.javaClass.simpleName})", long = true)
        }
        refreshSecrets()
        return saved
    }

    private fun clearApiKey() {
        apiKeys.clear()
        if (AppSettings.authMode == AppSettings.AUTH_API_KEY) {
            AppSettings.authMode = AppSettings.AUTH_CHATGPT
            CodexSession.restart()
        }
        refreshSecrets()
    }

    private fun setUseApiKey(checked: Boolean) {
        if (checked && !apiKeys.has()) {
            toast("먼저 API 키를 저장하세요")
            refreshSecrets() // 스위치는 저장된 상태를 따라가므로 꺼진 채로 남는다
            return
        }
        AppSettings.authMode = if (checked) AppSettings.AUTH_API_KEY else AppSettings.AUTH_CHATGPT
        CodexSession.restart()
        refreshSecrets()
    }

    /** @return 저장했으면 true(입력칸을 비운다). 토큰 값은 메시지에 넣지 않는다. */
    private fun saveEtlToken(raw: String): Boolean {
        val token = raw.trim()
        if (!SettingsLogic.isPlausibleEtlToken(token)) {
            toast("토큰 형식이 올바르지 않아요(공백 없는 ASCII 문자)", long = true)
            return false
        }
        var saved = false
        try {
            EtlService.tokens(this).save(token)
            saved = true
            toast("eTL 토큰을 저장했어요")
            CodexSession.restart() // MCP 서버를 새로 등록해야 한다
        } catch (e: Exception) {
            toast("저장하지 못했어요(${e.javaClass.simpleName})", long = true)
        }
        refreshSecrets()
        return saved
    }

    private fun clearEtlToken() {
        EtlService.tokens(this).clear()
        CodexSession.restart()
        refreshSecrets()
    }

    // ---- 알림 ----------------------------------------------------------------

    /** 설정을 바꾸고 즉시 저장한 뒤 예약을 다시 맞춘다. */
    private fun updateNotify(change: (NotifySettings) -> NotifySettings) {
        notifyStore.save(change(notifyStore.load()))
        NotifyScheduler.apply(this)
        refreshNotifySettings()
    }

    private fun refreshNotifySettings() {
        state.notify = notifyStore.load()
    }

    private fun refreshNotifyPermission() {
        state.notifyPermitted = NotificationPoster.canPost(this)
        state.batteryUnrestricted = BatteryOpt.isIgnoring(this)
        state.powerSave = BatteryOpt.isPowerSaveMode(this) && !BatteryOpt.isIgnoring(this)
    }

    private fun refreshNotifyStatusLines() {
        val st = NotifyStateStore(this).load()
        state.lastCheckText = NotifyUiFormat.describeLastCheck(st.lastCheckAt, st.lastResult)
        state.lastBriefText = NotifyUiFormat.describeLastBrief(st.lastBriefAt)
    }

    private fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        val m = initial.coerceIn(0, 1439)
        TimePickerDialog(this, { _, h, min -> onPicked(h * 60 + min) }, m / 60, m % 60, DateFormat.is24HourFormat(this)).show()
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (_: Exception) {
            toast("알림 설정을 열지 못했어요. 시스템 설정에서 직접 허용해 주세요.", long = true)
        }
    }

    /** 지금 확인/브리핑 미리 보내기. 권한이 없어도 실행은 하되(워커가 결과를 남긴다) 먼저 안내한다. */
    private fun requestManualRun(run: () -> Unit) {
        if (!NotificationPoster.canPost(this)) {
            toast("알림 권한이 꺼져 있어 알림이 뜨지 않아요. 위에서 권한을 허용하세요.", long = true)
        }
        run()
        toast("요청했어요. 잠시 뒤 결과가 표시돼요")
        main.removeCallbacks(refreshNotifyStatus)
        main.postDelayed(refreshNotifyStatus, 5_000L)
        main.postDelayed(refreshNotifyStatus, 15_000L)
    }

    private fun toast(text: String, long: Boolean = false) {
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 형식만 대충 본다(실제 유효성은 OpenAI가 판단). 공백, 한글 등은 거른다. */
        fun isPlausibleApiKey(s: String): Boolean = SettingsLogic.isPlausibleApiKey(s)

        /** `sk-…abcd`처럼 끝 4자리만 보인다. */
        fun maskKey(s: String): String = SettingsLogic.maskKey(s)
    }
}
