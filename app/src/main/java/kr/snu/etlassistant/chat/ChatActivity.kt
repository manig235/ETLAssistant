package kr.snu.etlassistant.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.onboarding.OnboardingActivity
import kr.snu.etlassistant.onboarding.OnboardingLogic
import kr.snu.etlassistant.onboarding.OnboardingPrefs

/**
 * 채팅 화면(Montage). Codex 세션은 이 화면이 열려 있을 때만 돌린다([CodexSession]이 화면이 모두 사라지면 일정 시간 뒤 멈춘다).
 * 그리기는 [ChatRoot]가 하고, 이 클래스는 세션 이벤트와 대화 변경을 [ChatViewModel]의 상태로 옮겨 준다.
 */
class ChatActivity : ComponentActivity(), CodexSession.Listener, ChatModel.Listener {

    private val vm: ChatViewModel by viewModels()
    private val main = Handler(Looper.getMainLooper())
    private var flushPosted = false
    private var ready = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 스트리밍 중에는 변경이 아주 잦으므로 묶어서(약 70ms) 한 번에 다시 그린다. */
    private val flush = Runnable {
        flushPosted = false
        vm.bump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 처음 쓰는 사람(안내를 끝내지 않았고 eTL 토큰도 없음)은 첫 실행 안내로 보낸다.
        if (!OnboardingLogic.shouldSkip(OnboardingPrefs(this).completed, EtlService.tokens(this).hasToken())) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        ready = true
        enableEdgeToEdge()
        val actions = ChatActions(
            onSend = { onSendClicked() },
            onSuggestion = { text -> send(text, clearInput = false) },
            onNewChat = {
                if (CodexSession.busy) Toast.makeText(this, "답변이 끝난 뒤에 새 대화를 시작할 수 있어요", Toast.LENGTH_SHORT).show()
                else CodexSession.newConversation()
            },
            onSettings = { openSettings() },
            onBanner = { action ->
                when (action) {
                    BannerAction.SETTINGS -> openSettings()
                    BannerAction.RETRY -> CodexSession.restart()
                    BannerAction.NONE -> {}
                }
            },
            onCopy = { text -> copy(text) },
            onLink = { url -> openLink(url) },
            onRefreshModels = { CodexSession.refreshModels() },
            onModelChoice = { choice ->
                vm.choice = choice
                CodexSession.setModelChoice(choice)
            },
        )
        setContent { ChatRoot(vm, actions) }
        applyPrefill(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("askedNotif", false).not()
        ) {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) applyPrefill(intent)
    }

    /** 첫 실행 안내에서 고른 추천 질문을 입력창에 채워 둔다(자동으로 보내지는 않는다). */
    private fun applyPrefill(i: Intent?) {
        val text = i?.getStringExtra(EXTRA_PREFILL) ?: return
        i.removeExtra(EXTRA_PREFILL)
        vm.setInput(text)
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        CodexSession.model.listener = this
        vm.syncFromSession()
        vm.bump()
        vm.scrollToBottom()
        CodexSession.attach(this)
        CodexSession.ensureStarted()
        updateBanner()
    }

    override fun onStop() {
        if (ready) {
            main.removeCallbacks(flush)
            flushPosted = false
            CodexSession.detach(this)
            CodexSession.model.listener = null
        }
        super.onStop()
    }

    // ---- 보내기 --------------------------------------------------------------

    private fun onSendClicked() {
        if (CodexSession.busy) {
            CodexSession.interrupt()
            return
        }
        val text = vm.input.text.trim()
        if (text.isEmpty()) return
        send(text, clearInput = true)
    }

    /** 문제가 있으면(예: 아직 준비 중) 이유를 토스트로 알리고 입력은 그대로 둔다. */
    private fun send(text: String, clearInput: Boolean) {
        val problem = CodexSession.send(text)
        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_SHORT).show()
        else if (clearInput) vm.setInput("")
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show()
    }

    /** 답변 속 링크(http/https만 남겨 둔 것)를 브라우저로 연다. */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "링크를 열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- 세션 이벤트 -----------------------------------------------------------

    override fun onEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.Busy -> vm.busy = e.busy
            is SessionEvent.Activity -> vm.activity = e.label
            is SessionEvent.Models -> {
                vm.models = e.models
                vm.modelsLoading = e.loading
                vm.choice = CodexSession.modelChoice
            }
            is SessionEvent.LoginDone -> if (e.success) Toast.makeText(this, "로그인했어요", Toast.LENGTH_SHORT).show()
            else -> {}
        }
        updateBanner()
    }

    /** 지금 상태에 맞는 안내를 위쪽 배너로 보여 준다. 누르면 해당 화면으로 가거나 다시 시도한다. */
    private fun updateBanner() {
        vm.banner = ChatLogic.banner(
            hasToken = EtlService.tokens(this).hasToken(),
            state = CodexSession.state,
            detail = CodexSession.stateDetail,
            loggedIn = CodexSession.account?.loggedIn == true,
        )
    }

    // ---- 대화 변경 -------------------------------------------------------------

    override fun onAdded(index: Int) {
        vm.bump()
        vm.scrollToBottom()
    }

    override fun onChanged(index: Int) {
        if (!flushPosted) {
            flushPosted = true
            main.postDelayed(flush, 70)
        }
    }

    override fun onReset() {
        vm.bump()
        vm.scrollToBottom()
    }

    companion object {
        const val EXTRA_PREFILL = "prefill"
    }
}
