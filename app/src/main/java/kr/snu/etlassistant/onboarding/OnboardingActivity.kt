package kr.snu.etlassistant.onboarding

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import kr.snu.etlassistant.chat.ChatActivity
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.notify.BatteryOpt
import kr.snu.etlassistant.phase0.CodexRunner

/**
 * 첫 실행 안내(환영 → eTL 연결 → ChatGPT 로그인 → 알림 → 완료). 화면은 Montage(Wanted 디자인 시스템)로 그린다.
 * Codex 세션은 로그인 단계에 들어갈 때 시작하고, 이 화면이 보이는 동안만 이벤트를 받는다.
 */
class OnboardingActivity : AppCompatActivity(), CodexSession.Listener {

    private val vm: OnboardingViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshNotifyGranted()
        // 두 번 거절했거나 시스템이 대화상자를 띄우지 않는 경우: 앱 알림 설정으로 안내한다.
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
    }

    /** 서울대 로그인 화면의 결과. 토큰은 그 화면이 이미 확인·저장했고, 여기서는 표시만 바꾼다. */
    private val etlLogin = registerForActivityResult(EtlLoginActivity.Contract()) { outcome ->
        when (outcome) {
            is EtlLoginActivity.Outcome.Connected -> vm.onLoginConnected(outcome.name)
            EtlLoginActivity.Outcome.Paste -> vm.showPaste = true
            EtlLoginActivity.Outcome.Canceled -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshNotifyGranted()
        val actions = OnboardingActions(
            loginWithSnu = { etlLogin.launch(Unit) },
            // 직접 발급: LearningX에는 토큰 메뉴가 없어 eTL 웹 설정을 연다. 돌아오면 클립보드를 한 번 본다.
            openEtlSettings = {
                vm.awaitingClipboard = true
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myetl.snu.ac.kr/profile/settings")))
            },
            readClipboard = { readClipboardText() },
            requestNotifications = { requestNotifications() },
            requestBatteryExemption = {
                BatteryOpt.request(this)
                Toast.makeText(this, BatteryOpt.SAMSUNG_HINT, Toast.LENGTH_LONG).show()
            },
            ensureSession = { CodexSession.ensureStarted() },
            retrySession = { CodexSession.restart() },
            loginBrowser = { CodexSession.loginBrowser() },
            loginDevice = { CodexSession.loginDeviceCode() },
            cancelLogin = { CodexSession.cancelLogin() },
            reopenLogin = { url -> CodexRunner.openUrl(url) },
            copyCode = { code ->
                CodexSession.copyToClipboard(code)
                Toast.makeText(this, "코드를 복사했어요", Toast.LENGTH_SHORT).show()
            },
            applyNotifications = { vm.applyNotificationChoices() },
            pickTime = { initial, onPicked ->
                val m = initial.coerceIn(0, 1439)
                TimePickerDialog(this, { _, h, min -> onPicked(h * 60 + min) }, m / 60, m % 60, DateFormat.is24HourFormat(this)).show()
            },
            finish = { prefill -> finishOnboarding(prefill) },
        )
        setContent { OnboardingRoot(vm, actions) }
    }

    override fun onStart() {
        super.onStart()
        CodexSession.attach(this)
        // 로그인하러 나가 있는 동안 세션이 멈췄을 수 있다(화면이 없으면 바로 멈춘다).
        // 단계가 바뀌지 않았으니 화면의 LaunchedEffect는 다시 돌지 않는다. 여기서 다시 띄운다.
        if (vm.step == Step.LOGIN) CodexSession.ensureStarted()
        refreshNotifyGranted()
    }

    override fun onStop() {
        CodexSession.detach(this, isChangingConfigurations)
        super.onStop()
    }

    override fun onEvent(e: SessionEvent) = vm.onSessionEvent(e)

    /**
     * 토큰을 만들러 브라우저에 다녀온 뒤 처음 화면에 돌아왔을 때 한 번만 클립보드를 본다(Android 10+는 창에 포커스가 있어야 읽힌다).
     * 그 버튼을 누르지 않았으면 클립보드를 읽지 않는다.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !vm.awaitingClipboard) return
        vm.awaitingClipboard = false
        if (vm.step == Step.TOKEN) vm.offerClipboard(readClipboardText())
    }

    private fun readClipboardText(): String? = runCatching {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
    }.getOrNull()

    private fun refreshNotifyGranted() {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        vm.notifyGranted = permitted && NotificationManagerCompat.from(this).areNotificationsEnabled()
        vm.batteryUnrestricted = BatteryOpt.isIgnoring(this)
        vm.powerSave = BatteryOpt.isPowerSaveMode(this) && !BatteryOpt.isIgnoring(this)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings() // 권한은 있는데 앱 알림이 꺼져 있는 경우
        }
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun finishOnboarding(prefill: String?) {
        OnboardingPrefs(this).completed = true
        startActivity(
            Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (prefill != null) putExtra(ChatActivity.EXTRA_PREFILL, prefill) }
        )
        finish()
    }
}
