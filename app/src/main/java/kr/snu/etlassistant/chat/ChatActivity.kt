package kr.snu.etlassistant.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.onboarding.OnboardingActivity
import kr.snu.etlassistant.onboarding.OnboardingLogic
import kr.snu.etlassistant.onboarding.OnboardingPrefs
import kr.snu.etlassistant.phase0.R

/**
 * 채팅 화면. Codex 세션은 이 화면이 열려 있을 때만 돌린다([CodexSession]이 화면이 모두 사라지면 일정 시간 뒤 멈춘다).
 */
class ChatActivity : AppCompatActivity(), CodexSession.Listener, ChatModel.Listener {

    private lateinit var list: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var btnSend: Button
    private lateinit var banner: TextView
    private lateinit var empty: TextView
    private val main = Handler(Looper.getMainLooper())
    private var bannerAction: (() -> Unit)? = null
    private val changed = HashSet<Int>()
    private var flushPosted = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 처음 쓰는 사람(안내를 끝내지 않았고 eTL 토큰도 없음)은 첫 실행 안내로 보낸다.
        if (!OnboardingLogic.shouldSkip(OnboardingPrefs(this).completed, EtlService.tokens(this).hasToken())) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_chat)
        val root = findViewById<View>(R.id.chatRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        list = findViewById(R.id.list)
        input = findViewById(R.id.input)
        btnSend = findViewById(R.id.btnSend)
        banner = findViewById(R.id.banner)
        empty = findViewById(R.id.empty)

        adapter = ChatAdapter(this, CodexSession.model)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        list.itemAnimator = null

        btnSend.setOnClickListener { onSendClicked() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnNew).setOnClickListener {
            if (CodexSession.busy) Toast.makeText(this, "답변이 끝난 뒤에 새 대화를 시작할 수 있습니다", Toast.LENGTH_SHORT).show()
            else CodexSession.newConversation()
        }
        banner.setOnClickListener { bannerAction?.invoke() }
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
        applyPrefill(intent)
    }

    /** 첫 실행 안내에서 고른 추천 질문을 입력창에 채워 둔다(자동으로 보내지는 않는다). */
    private fun applyPrefill(i: Intent?) {
        val text = i?.getStringExtra(EXTRA_PREFILL) ?: return
        i.removeExtra(EXTRA_PREFILL)
        input.setText(text)
        input.setSelection(text.length)
    }

    override fun onStart() {
        super.onStart()
        CodexSession.model.listener = this
        adapter.notifyDataSetChanged()
        scrollToEnd()
        updateEmpty()
        CodexSession.attach(this)
        CodexSession.ensureStarted()
        updateBanner()
    }

    override fun onStop() {
        CodexSession.detach(this)
        CodexSession.model.listener = null
        super.onStop()
    }

    // ---- 보내기 --------------------------------------------------------------

    private fun onSendClicked() {
        if (CodexSession.busy) {
            CodexSession.interrupt()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val problem = CodexSession.send(text)
        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_SHORT).show() else input.setText("")
    }

    // ---- 세션 이벤트 -----------------------------------------------------------

    override fun onEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.Busy -> btnSend.text = if (e.busy) "중단" else "보내기"
            is SessionEvent.LoginDone -> if (e.success) Toast.makeText(this, "로그인했습니다", Toast.LENGTH_SHORT).show()
            else -> {}
        }
        updateBanner()
    }

    /** 지금 상태에 맞는 안내를 위쪽 배너로 보여 준다. 누르면 해당 화면으로 간다. */
    private fun updateBanner() {
        val goSettings = { startActivity(Intent(this, SettingsActivity::class.java)); Unit }
        val (text, action) = when {
            !EtlService.tokens(this).hasToken() -> "eTL 토큰이 필요합니다. 눌러서 설정으로 이동" to goSettings
            CodexSession.state == SessionState.FAILED ->
                "비서를 시작하지 못했습니다: ${CodexSession.stateDetail ?: ""}\n눌러서 다시 시도" to { CodexSession.restart(); Unit }
            CodexSession.state != SessionState.READY -> "비서를 준비하는 중입니다…" to null
            CodexSession.account?.loggedIn != true -> "로그인이 필요합니다. 눌러서 설정으로 이동" to goSettings
            else -> null to null
        }
        bannerAction = action
        banner.visibility = if (text == null) View.GONE else View.VISIBLE
        banner.text = text
    }

    // ---- 대화 변경 -------------------------------------------------------------

    override fun onAdded(index: Int) {
        val atBottom = !list.canScrollVertically(1)
        adapter.notifyItemInserted(index)
        updateEmpty()
        if (atBottom || index == adapter.itemCount - 1) scrollToEnd()
    }

    /** 스트리밍 중에는 변경이 아주 잦으므로 묶어서 갱신한다. */
    override fun onChanged(index: Int) {
        changed += index
        if (!flushPosted) {
            flushPosted = true
            main.postDelayed({
                flushPosted = false
                val atBottom = !list.canScrollVertically(1)
                changed.forEach { if (it < adapter.itemCount) adapter.notifyItemChanged(it) }
                changed.clear()
                if (atBottom) scrollToEnd()
            }, 70)
        }
    }

    override fun onReset() {
        adapter.notifyDataSetChanged()
        updateEmpty()
        scrollToEnd()
    }

    private fun updateEmpty() {
        empty.visibility = if (CodexSession.model.messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun scrollToEnd() {
        val n = adapter.itemCount
        if (n > 0) list.scrollToPosition(n - 1)
    }

    companion object {
        const val EXTRA_PREFILL = "prefill"
    }
}
