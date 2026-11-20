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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import kr.snu.etlassistant.chat.ChatActivity
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.phase0.CodexRunner

/**
 * 첫 실행 안내(환영 → eTL 연결 → ChatGPT 로그인 → 알림 → 완료). 화면은 Montage(Wanted 디자인 시스템)로 그린다.
 * Codex 세션은 로그인 단계에 들어갈 때 시작하고, 이 화면이 보이는 동안만 이벤트를 받는다.
 */
class OnboardingActivity : ComponentActivity(), CodexSession.Listener {

    private val vm: OnboardingViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshNotifyGranted()
        // 두 번 거절했거나 시스템이 대화상자를 띄우지 않는 경우: 앱 알림 설정으로 안내한다.
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshNotifyGranted()
        val actions = OnboardingActions(
            openEtlSettings = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myetl.snu.ac.kr/profile/settings"))) },
            readClipboard = {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
            },
            requestNotifications = { requestNotifications() },
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
        // 로그인하러 나가 있는 동안 세션이 유휴 시간(IDLE_STOP_MS)을 넘겨 멈췄을 수 있다.
        // 단계가 바뀌지 않았으니 화면의 LaunchedEffect는 다시 돌지 않는다. 여기서 다시 띄운다.
        if (vm.step == Step.LOGIN) CodexSession.ensureStarted()
        refreshNotifyGranted()
    }

    override fun onStop() {
        CodexSession.detach(this)
        super.onStop()
    }

    override fun onEvent(e: SessionEvent) = vm.onSessionEvent(e)

    private fun refreshNotifyGranted() {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        vm.notifyGranted = permitted && NotificationManagerCompat.from(this).areNotificationsEnabled()
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
