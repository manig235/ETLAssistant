package kr.snu.etlassistant.codex

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import kr.snu.etlassistant.chat.ActivityTracker
import kr.snu.etlassistant.chat.ChatModel
import kr.snu.etlassistant.etl.ApiKeyStore
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.phase0.CodexRunner
import kr.snu.etlassistant.phase0.RunService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

enum class SessionState { STOPPED, STARTING, READY, FAILED }

enum class LoginKind { BROWSER, DEVICE_CODE }

data class AccountInfo(val kind: Kind, val email: String? = null, val plan: String? = null) {
    enum class Kind { NONE, CHATGPT, API_KEY, UNKNOWN }

    val loggedIn: Boolean get() = kind != Kind.NONE
}

sealed class SessionEvent {
    data class State(val state: SessionState, val detail: String?) : SessionEvent()
    data class Account(val info: AccountInfo) : SessionEvent()
    data class Busy(val busy: Boolean) : SessionEvent()
    /** 답변을 만드는 동안 "지금 하는 일" 한 줄(`조회하는 중…` 등). null이면 숨긴다. */
    data class Activity(val label: String?) : SessionEvent()
    /** 선택 가능한 모델 목록이 갱신됨(또는 선택이 바뀜). */
    data class Models(val models: List<ModelInfo>, val loading: Boolean = false) : SessionEvent()
    data class LoginPrompt(val kind: LoginKind, val url: String, val code: String?) : SessionEvent()
    data class LoginDone(val success: Boolean, val error: String?) : SessionEvent()
}

/** 앱 설정(앱 내부 SharedPreferences). 비밀값은 여기에 두지 않는다. */
object AppSettings {
    const val AUTH_CHATGPT = "chatgpt"
    const val AUTH_API_KEY = "apikey"

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    var authMode: String
        get() = prefs.getString("authMode", AUTH_CHATGPT) ?: AUTH_CHATGPT
        set(v) = prefs.edit().putString("authMode", v).apply()

    /** 선택한 모델(null = 자동, Codex 기본값). */
    var model: String?
        get() = prefs.getString("model", null)
        set(v) = prefs.edit().apply { if (v == null) remove("model") else putString("model", v) }.apply()

    /** 선택한 추론 강도(null = 모델 기본값). */
    var effort: String?
        get() = prefs.getString("effort", null)
        set(v) = prefs.edit().apply { if (v == null) remove("effort") else putString("effort", v) }.apply()

    var threadId: String?
        get() = prefs.getString("threadId", null)
        set(v) = prefs.edit().apply { if (v == null) remove("threadId") else putString("threadId", v) }.apply()
}

/**
 * Codex 세션(code-mode host + app-server + eTL MCP 서버)을 들고 있는 싱글턴.
 *
 * - **채팅 화면이 열려 있을 때만** 돌린다. 화면이 모두 사라지면 [IDLE_STOP_MS] 뒤에 멈춘다(턴이나 로그인 중이면 멈추지 않는다).
 * - 턴이나 로그인이 진행되는 동안은 포그라운드 서비스를 붙잡는다. 앱이 백그라운드가 되면 앱의 DNS가 막히기 때문이다.
 * - 이벤트는 메인 스레드에서 [Listener]로 전달한다. 대화 내용은 [model]이 들고 있다.
 */
object CodexSession {

    interface Listener {
        fun onEvent(e: SessionEvent)
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "codex-session").apply { isDaemon = true } }
    private val calls = Executors.newCachedThreadPool { r -> Thread(r, "codex-call").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArraySet<Listener>()

    lateinit var model: ChatModel
        private set

    @Volatile var state = SessionState.STOPPED
        private set
    @Volatile var stateDetail: String? = null
        private set
    @Volatile var account: AccountInfo? = null
        private set
    @Volatile var busy = false
        private set
    @Volatile var lastLogin: SessionEvent.LoginPrompt? = null
        private set
    @Volatile var activity: String? = null
        private set

    /** `model/list` 결과. **스텁**: 모델 담당이 불러오기를 구현한다. */
    @Volatile var models: List<ModelInfo> = emptyList()
        private set
    @Volatile var modelsLoading: Boolean = false
        private set

    /** 지금 저장된 선택. */
    val modelChoice: ModelChoice get() = ModelChoice(AppSettings.model, AppSettings.effort)

    /** 선택을 저장한다. 다음 질문부터 적용된다. */
    fun setModelChoice(c: ModelChoice) {
        AppSettings.model = c.model
        AppSettings.effort = c.effort
        emit(SessionEvent.Models(models, modelsLoading))
    }

    private val modelsLock = Any()
    @Volatile private var modelsClient: AppServerClient? = null // 지금 목록을 불러오는 중인 클라이언트

    /** 요청을 만들 때 쓰는 유효한 선택. 저장값은 건드리지 않고, 목록에 없는 모델/강도는 이번 요청에서만 뺀다. */
    private fun effectiveChoice(): ModelChoice = ModelCatalog.sanitize(modelChoice, models)

    /** 모델 목록을 (다시) 불러온다. 세션이 없거나 이미 불러오는 중이면 아무것도 하지 않는다. */
    fun refreshModels() {
        val c = client ?: return
        synchronized(modelsLock) {
            if (modelsLoading && modelsClient === c) return
            modelsLoading = true
            modelsClient = c
        }
        emit(SessionEvent.Models(models, true))
        calls.execute {
            var loaded: List<ModelInfo>? = null
            try {
                val all = ArrayList<ModelInfo>()
                var cursor: String? = null
                for (page in 0 until MODELS_MAX_PAGES) {
                    val params = JSONObject().put("limit", 100)
                    if (cursor != null) params.put("cursor", cursor)
                    val r = c.request("model/list", params, 30)
                    all += ModelCatalog.parse(r)
                    cursor = r.optString("nextCursor").takeIf { it.isNotEmpty() && it != "null" }
                    if (cursor == null) break
                }
                loaded = all
                CodexRunner.log.line("[session] 모델 ${all.size}개를 불러왔습니다")
            } catch (e: IOException) {
                CodexRunner.log.line("[session] 모델 목록을 불러오지 못했습니다: ${e.message}")
            } catch (e: Exception) {
                CodexRunner.log.line("[session] 모델 목록을 불러오지 못했습니다: ${e.javaClass.simpleName}")
            } finally {
                synchronized(modelsLock) {
                    if (modelsClient === c) {
                        modelsClient = null
                        modelsLoading = false
                    }
                }
            }
            if (loaded != null && client === c) models = loaded
            emit(SessionEvent.Models(models, modelsLoading))
        }
    }

    private val tracker = ActivityTracker() // 메인 스레드에서만

    @Volatile private var client: AppServerClient? = null
    @Volatile private var host: CodexRunner.Host? = null
    @Volatile private var proc: Process? = null
    @Volatile private var currentTurnId: String? = null
    @Volatile private var loginId: String? = null
    private var turnHold = false
    private var loginHold = false
    private val idleStop = Runnable { if (listeners.isEmpty() && !busy && loginId == null) stop() }

    fun init(context: Context) {
        app = context.applicationContext
        AppSettings.init(app)
        model = ChatModel(File(app.filesDir, "chat.json")).also { it.load() }
    }

    // ---- 화면 연결 -----------------------------------------------------------

    fun attach(l: Listener) {
        listeners += l
        main.removeCallbacks(idleStop)
        l.onEvent(SessionEvent.State(state, stateDetail))
        account?.let { l.onEvent(SessionEvent.Account(it)) }
        l.onEvent(SessionEvent.Busy(busy))
        l.onEvent(SessionEvent.Activity(activity))
        l.onEvent(SessionEvent.Models(models, modelsLoading))
        lastLogin?.let { l.onEvent(it) }
    }

    fun detach(l: Listener) {
        listeners -= l
        if (listeners.isEmpty()) main.postDelayed(idleStop, IDLE_STOP_MS)
    }

    val isVisible: Boolean get() = listeners.isNotEmpty()

    private fun emit(e: SessionEvent) {
        main.post { listeners.forEach { it.onEvent(e) } }
    }

    private fun setState(s: SessionState, detail: String? = null) {
        state = s
        stateDetail = detail
        emit(SessionEvent.State(s, detail))
    }

    // ---- 시작/중지 -----------------------------------------------------------

    private fun options(): CodexRunner.Options {
        val apiKey = if (AppSettings.authMode == AppSettings.AUTH_API_KEY) ApiKeyStore(app).load() else null
        return CodexRunner.Options(
            useProxy = true,
            setSslCertFile = true,
            dangerFullAccess = false,
            useEtlTools = EtlService.tokens(app).hasToken(),
            apiKey = apiKey,
        )
    }

    @Synchronized
    fun ensureStarted() {
        if (state == SessionState.STARTING || state == SessionState.READY) return
        setState(SessionState.STARTING)
        control.execute {
            try {
                startBlocking()
            } catch (e: Exception) {
                CodexRunner.log.line("[session] 시작 실패: ${e.javaClass.simpleName}: ${e.message}")
                teardown()
                setState(SessionState.FAILED, e.message ?: "시작하지 못했습니다")
            }
        }
    }

    /** 설정(토큰, 인증 방식)이 바뀌었을 때 새 설정으로 다시 시작한다. */
    fun restart() {
        control.execute {
            teardown()
            state = SessionState.STOPPED
            main.post { ensureStarted() }
        }
    }

    fun stop() {
        control.execute {
            teardown()
            setState(SessionState.STOPPED)
        }
    }

    private fun startBlocking() {
        val workspace = File(app.filesDir, "workspace").apply { mkdirs() }
        val h = CodexRunner.startHost(workspace) ?: throw IOException("code-mode host를 시작하지 못했습니다")
        host = h
        val prep = CodexRunner.prepare(CodexRunner.appServerArgs(h), options(), "채팅 세션")
            ?: throw IOException("Codex 실행 파일을 준비하지 못했습니다")
        val p = prep.pb.start()
        proc = p
        CodexRunner.pump(p.errorStream, "err")

        val holder = arrayOfNulls<AppServerClient>(1)
        val c = AppServerClient(p, ::onNotification, ::onServerRequest) { onClosed(holder[0]) }
        holder[0] = c
        c.start()
        c.request("initialize", JSONObject()
            .put("clientInfo", JSONObject().put("name", "etl-android").put("title", "eTL 비서").put("version", "0.1.0"))
            .put("capabilities", JSONObject().put("experimentalApi", false)))
        c.notify("initialized")
        client = c
        refreshAccountBlocking()
        setState(SessionState.READY)
        CodexRunner.log.line("[session] 준비됨")
        refreshModels()
    }

    private fun teardown() {
        val old = client
        client = null // 먼저 비워서, 닫힘 콜백이 이 클라이언트를 현재 것으로 착각하지 않게 한다
        old?.close()
        proc?.destroy()
        proc = null
        host?.stop()
        host = null
        EtlService.stopServer()
        currentTurnId = null
        loginId = null
        lastLogin = null
        main.post {
            model.closeStreaming()
            setActivity(tracker.turnEnded())
            setBusy(false)
            releaseLoginHold()
        }
    }

    private fun onClosed(closed: AppServerClient?) {
        if (closed == null || client !== closed || state == SessionState.STOPPED) return
        CodexRunner.log.line("[session] app-server가 종료됐습니다")
        control.execute {
            teardown()
            setState(SessionState.FAILED, "비서 프로세스가 종료됐습니다")
        }
    }

    // ---- 계정 ---------------------------------------------------------------

    fun refreshAccount() {
        calls.execute {
            try {
                refreshAccountBlocking()
            } catch (_: IOException) {
            }
        }
    }

    private fun refreshAccountBlocking() {
        val c = client ?: return
        val r = c.request("account/read", JSONObject().put("refreshToken", false), 30)
        val a = r.optJSONObject("account")
        val info = when (a?.optString("type")) {
            null, "" -> AccountInfo(AccountInfo.Kind.NONE)
            "chatgpt" -> AccountInfo(AccountInfo.Kind.CHATGPT, a.optString("email").takeIf { it != "null" && it.isNotEmpty() }, a.optString("planType").takeIf { it != "null" && it.isNotEmpty() })
            "apiKey" -> AccountInfo(AccountInfo.Kind.API_KEY)
            else -> AccountInfo(AccountInfo.Kind.UNKNOWN)
        }
        val before = account
        account = info
        emit(SessionEvent.Account(info))
        // 계정이 바뀌면 쓸 수 있는 모델도 달라질 수 있다(처음 읽을 때는 시작 과정에서 불러온다).
        // 같은 ChatGPT라도 플랜(Plus→Free 등)이나 계정이 바뀌면 쓸 수 있는 모델이 달라진다.
        if (before != null && before != info) refreshModels()
    }

    fun loginBrowser() = startLogin(LoginKind.BROWSER)

    fun loginDeviceCode() = startLogin(LoginKind.DEVICE_CODE)

    private fun startLogin(kind: LoginKind) {
        val c = client ?: return
        calls.execute {
            try {
                val r = c.request("account/login/start",
                    JSONObject().put("type", if (kind == LoginKind.BROWSER) "chatgpt" else "chatgptDeviceCode"))
                loginId = r.optString("loginId").takeIf { it.isNotEmpty() }
                val url = if (kind == LoginKind.BROWSER) r.optString("authUrl") else r.optString("verificationUrl")
                val code = if (kind == LoginKind.DEVICE_CODE) r.optString("userCode").takeIf { it.isNotEmpty() } else null
                if (url.isEmpty()) throw IOException("로그인 주소를 받지 못했습니다")
                val prompt = SessionEvent.LoginPrompt(kind, url, code)
                lastLogin = prompt
                main.post { acquireLoginHold() }
                emit(prompt)
                if (code != null) copyToClipboard(code)
                CodexRunner.openUrl(url)
            } catch (e: IOException) {
                emit(SessionEvent.LoginDone(false, e.message))
            }
        }
    }

    fun cancelLogin() {
        val id = loginId ?: return
        val c = client ?: return
        loginId = null
        lastLogin = null
        calls.execute {
            try {
                c.request("account/login/cancel", JSONObject().put("loginId", id), 15)
            } catch (_: IOException) {
            }
            main.post { releaseLoginHold() }
            emit(SessionEvent.LoginDone(false, "취소했습니다"))
        }
    }

    fun logout() {
        val c = client ?: return
        calls.execute {
            try {
                c.request("account/logout", JSONObject(), 30)
            } catch (_: IOException) {
            }
            try {
                refreshAccountBlocking()
            } catch (_: IOException) {
            }
        }
    }

    /** 일회용 코드를 클립보드에 넣는다(민감 정보 표시로 미리보기에서 가린다). */
    fun copyToClipboard(text: String) {
        main.post {
            val cm = app.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("code", text)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            cm.setPrimaryClip(clip)
        }
    }

    // ---- 대화 ---------------------------------------------------------------

    /** @return 보낼 수 없는 상태면 이유 문구, 보냈으면 null */
    fun send(text: String): String? {
        val c = client
        if (state != SessionState.READY || c == null) return "비서가 아직 준비되지 않았습니다"
        if (account?.loggedIn != true) return "먼저 로그인해야 합니다"
        if (busy) return "이전 답변이 끝날 때까지 기다려 주세요"
        model.addUser(text)
        setBusy(true)
        setActivity(tracker.turnStarted())
        calls.execute {
            try {
                val thread = ensureThread(c)
                val turnParams = JSONObject().put("threadId", thread)
                    .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text).put("text_elements", JSONArray())))
                val choice = effectiveChoice()
                choice.model?.let { turnParams.put("model", it) }
                choice.effort?.let { turnParams.put("effort", it) }
                val r = c.request("turn/start", turnParams)
                currentTurnId = r.optJSONObject("turn")?.optString("id")
            } catch (e: IOException) {
                main.post {
                    model.addNotice("보내지 못했습니다: ${e.message}")
                    model.closeStreaming()
                    setActivity(tracker.turnEnded())
                    setBusy(false)
                }
            }
        }
        return null
    }

    fun interrupt() {
        val c = client ?: return
        val thread = AppSettings.threadId ?: return
        val turn = currentTurnId ?: return
        calls.execute {
            try {
                c.request("turn/interrupt", JSONObject().put("threadId", thread).put("turnId", turn), 15)
            } catch (_: IOException) {
            }
        }
    }

    /** 새 대화를 시작한다(저장된 스레드를 잊는다). */
    fun newConversation() {
        AppSettings.threadId = null
        main.post { model.clear() }
    }

    @Synchronized
    private fun ensureThread(c: AppServerClient): String {
        val cwd = File(app.filesDir, "workspace").path
        val saved = AppSettings.threadId
        val choice = effectiveChoice()
        CodexRunner.log.line("[session] 모델: ${choice.model ?: "자동"}, 강도: ${choice.effort ?: "기본"}")
        if (saved != null) {
            try {
                val resume = JSONObject().put("threadId", saved).put("cwd", cwd)
                    .put("approvalPolicy", "never").put("sandbox", "read-only")
                choice.model?.let { resume.put("model", it) }
                c.request("thread/resume", resume)
                return saved
            } catch (e: IOException) {
                CodexRunner.log.line("[session] 스레드 재개 실패, 새로 시작합니다: ${e.message}")
                AppSettings.threadId = null
            }
        }
        val start = JSONObject().put("cwd", cwd)
            .put("approvalPolicy", "never").put("sandbox", "read-only")
        choice.model?.let { start.put("model", it) }
        val r = c.request("thread/start", start)
        val id = r.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotEmpty() }
            ?: throw IOException("스레드를 만들지 못했습니다")
        AppSettings.threadId = id
        return id
    }

    // ---- 서버 알림 ------------------------------------------------------------

    private fun onNotification(method: String, p: JSONObject) {
        when (method) {
            "item/agentMessage/delta" -> {
                val id = p.optString("itemId")
                val d = p.optString("delta")
                main.post {
                    model.appendAssistant(id, d)
                    setActivity(tracker.textDelta())
                }
            }
            "item/started", "item/completed" -> p.optJSONObject("item")?.let { item(it, method == "item/completed") }
            "turn/completed" -> {
                val turn = p.optJSONObject("turn")
                val status = turn?.optString("status") ?: "?"
                val err = turn?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() && it != "null" }
                main.post {
                    model.closeStreaming()
                    if (status == "failed") {
                        model.addNotice(err ?: "답변을 만들지 못했습니다")
                        if (!modelChoice.isAuto) model.addNotice("선택한 모델 설정 때문일 수 있어요. 설정에서 '자동'으로 되돌려 보세요.")
                    }
                    if (status == "interrupted") model.addNotice("답변을 중단했습니다")
                    currentTurnId = null
                    setActivity(tracker.turnEnded())
                    setBusy(false)
                }
            }
            "error" -> {
                if (!p.optBoolean("willRetry", false)) {
                    val msg = p.optJSONObject("error")?.optString("message").orEmpty()
                    if (msg.isNotEmpty()) main.post { model.addNotice(msg) }
                }
            }
            "account/login/completed" -> {
                val ok = p.optBoolean("success", false)
                val err = p.optString("error").takeIf { it.isNotEmpty() && it != "null" }
                loginId = null
                lastLogin = null
                main.post {
                    releaseLoginHold()
                    if (ok && !isVisible) notifyLoginDone()
                }
                emit(SessionEvent.LoginDone(ok, err))
                refreshAccount()
            }
            "account/updated" -> refreshAccount()
        }
    }

    private fun item(item: JSONObject, completed: Boolean) {
        val id = item.optString("id")
        when (item.optString("type")) {
            "agentMessage" -> if (completed) {
                val text = item.optString("text")
                main.post {
                    model.finishAssistant(id, text)
                    setActivity(tracker.messageDone())
                }
            }
            // 도구 호출은 대화에 남기지 않고, 진행 중인 일을 한 줄로만 알린다.
            "mcpToolCall" -> {
                val tool = item.optString("tool")
                main.post { setActivity(if (completed) tracker.toolFinished(id) else tracker.toolStarted(id, tool)) }
            }
        }
    }

    private fun setActivity(label: String?) {
        if (activity == label) return
        activity = label
        emit(SessionEvent.Activity(label))
    }

    private fun onServerRequest(method: String, params: JSONObject?) {
        CodexRunner.log.line("[session] 서버 요청을 거절했습니다: $method")
        main.post { model.addNotice("지원하지 않는 요청을 거절했습니다: $method") }
    }

    // ---- 포그라운드 서비스, 알림 ------------------------------------------------

    private fun setBusy(b: Boolean) {
        if (busy == b) return
        busy = b
        if (b && !turnHold) {
            turnHold = true
            runCatching { RunService.acquire(app) }
        } else if (!b && turnHold) {
            turnHold = false
            RunService.release(app)
        }
        emit(SessionEvent.Busy(b))
        if (!b && listeners.isEmpty()) main.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun acquireLoginHold() {
        if (loginHold) return
        loginHold = true
        runCatching { RunService.acquire(app) }
    }

    private fun releaseLoginHold() {
        if (!loginHold) return
        loginHold = false
        RunService.release(app)
    }

    private fun notifyLoginDone() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("login", "로그인", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent().setClassName(app, "kr.snu.etlassistant.chat.ChatActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(app, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(app, "login")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("로그인이 완료됐습니다")
            .setContentText("탭하면 eTL 비서로 돌아갑니다")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2, n) }
    }

    const val IDLE_STOP_MS = 120_000L
    private const val MODELS_MAX_PAGES = 5
}
