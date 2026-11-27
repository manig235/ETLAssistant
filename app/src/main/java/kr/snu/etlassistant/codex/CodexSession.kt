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
import kr.snu.etlassistant.chat.Attachment
import kr.snu.etlassistant.chat.AttachmentLogic
import kr.snu.etlassistant.chat.ChatModel
import kr.snu.etlassistant.chat.ChatStore
import kr.snu.etlassistant.chat.FinishNotice
import kr.snu.etlassistant.chat.TurnEnd
import kr.snu.etlassistant.chat.TurnLogic
import kr.snu.etlassistant.chat.TurnRegistry
import kr.snu.etlassistant.etl.ApiKeyStore
import kr.snu.etlassistant.etl.EtlEvents
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.phase0.CodexRunner
import kr.snu.etlassistant.phase0.RunService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
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
    /** 지금 보는 대화가 답하는 중인가(보내기/멈춤 버튼). 다른 대화의 답변과는 상관없다. */
    data class Busy(val busy: Boolean) : SessionEvent()
    /** 답하는 대화 목록이 바뀜(서랍의 돌아가는 표시). */
    data class Running(val chats: Set<String>) : SessionEvent()
    /** 지금 보지 않는 대화의 답변이 끝남(채팅 화면 아래 막대, "보기"로 그 대화를 연다). */
    data class TurnFinished(val chatId: String, val text: String) : SessionEvent()
    /** 지금 보는 대화가 바뀜. */
    data class ChatSwitched(val chatId: String) : SessionEvent()
    /** 지금 보는 대화의 "지금 하는 일" 한 줄(`조회하는 중…` 등). null이면 숨긴다. */
    data class Activity(val label: String?) : SessionEvent()
    /** 선택 가능한 모델 목록이 갱신됨(또는 선택이 바뀜). */
    data class Models(val models: List<ModelInfo>, val loading: Boolean = false) : SessionEvent()
    data class LoginPrompt(val kind: LoginKind, val url: String, val code: String?) : SessionEvent()
    data class LoginDone(val success: Boolean, val error: String?) : SessionEvent()
    /**
     * 진행 중인 로그인이 없다. [CodexSession.attach]가 화면에 현재 상태를 다시 알려 줄 때 보낸다.
     * 화면이 보이지 않는 동안 로그인이 끝나면 [LoginDone]을 놓치므로, 이것으로 남아 있는 진행 표시를 지운다.
     */
    object LoginIdle : SessionEvent()
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

    /** 예전(대화 하나) 형식의 스레드 ID. 대화 목록으로 옮길 때만 읽는다. 지금은 대화마다 [ChatMeta.threadId]에 둔다. */
    var legacyThreadId: String?
        get() = prefs.getString("threadId", null)
        set(v) = prefs.edit().apply { if (v == null) remove("threadId") else putString("threadId", v) }.apply()

    /** 반말로 대화(기본 꺼짐 = 해요체). thread/start·thread/resume의 developerInstructions로 넘긴다. */
    var casualTone: Boolean
        get() = prefs.getBoolean("casualTone", false)
        set(v) = prefs.edit().putBoolean("casualTone", v).apply()

    /** 마지막으로 열어 둔 대화. 없거나 지워졌으면 빈 새 대화로 시작한다. */
    var currentChatId: String?
        get() = prefs.getString("currentChatId", null)
        set(v) = prefs.edit().apply { if (v == null) remove("currentChatId") else putString("currentChatId", v) }.apply()

    /** 지운 대화의 Codex 스레드 중 아직 `thread/delete`로 지우지 못한 것(Codex가 꺼져 있었거나 실패). 다음 시작 때 다시 지운다. */
    var pendingThreadDeletes: Set<String>
        get() = prefs.getStringSet("pendingThreadDeletes", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().apply { if (v.isEmpty()) remove("pendingThreadDeletes") else putStringSet("pendingThreadDeletes", v.toSet()) }.apply()
}

/**
 * Codex 세션(code-mode host + app-server + eTL MCP 서버)을 들고 있는 싱글턴.
 *
 * - **채팅 화면이 열려 있을 때만** 돌린다. 화면이 모두 사라지면 그 자리에서 멈춘다(턴이나 로그인 중이면 그것이 끝날 때 멈춘다).
 * - 답변(턴)은 대화마다 따로 돈다(2026-11-27, [TurnRegistry]). 대화 A가 답하는 동안 다른 대화를 열거나 새 대화에서 보낼 수 있고
 *   동시에 도는 답변 수에 제한이 없다. Codex 알림은 `threadId`로, eTL 도구가 알리는 일은 MCP `_meta.threadId`로 그 대화에 붙인다.
 *   화면이 모두 사라지면 **모든** 답변이 끝난 뒤 멈춘다.
 *   예전에는 2분 뒤에 멈췄는데, 삼성이 그 전에 앱을 얼려 타이머가 돌지 못해 Codex 프로세스가 남았다(2026-11-22 S22 실측).
 *   화면 회전처럼 화면이 다시 만들어질 때만 [CONFIG_CHANGE_STOP_MS] 뒤로 미룬다(새 화면이 곧 붙어 취소된다).
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

    /** 대화 목록. */
    lateinit var chats: ChatStore
        private set

    /** 지금 화면에 열린 대화(아직 아무것도 보내지 않았으면 목록에는 없다). */
    @Volatile var chatId: String = ""
        private set

    @Volatile var state = SessionState.STOPPED
        private set
    @Volatile var stateDetail: String? = null
        private set
    @Volatile var account: AccountInfo? = null
        private set
    @Volatile var lastLogin: SessionEvent.LoginPrompt? = null
        private set

    /** 대화마다 따로 도는 답변. */
    private val turns = TurnRegistry()
    private class PendingInput(
        val client: AppServerClient,
        val turn: TurnRegistry.Turn,
        val input: String,
        val attempt: ModelFallback.Attempt,
    ) {
        @Volatile var actualModel: String? = null
    }
    private val pendingInputs = ConcurrentHashMap<String, PendingInput>()
    private val modelFallback = ModelFallback()
    /** 메모리에 둔 대화(지금 대화, 답하는 대화, 이번 실행에 열어 본 대화). 메인 스레드에서만 만진다. */
    private val liveModels = HashMap<String, ChatModel>()
    private val trackers = HashMap<String, ActivityTracker>() // 메인 스레드에서만
    private val activities = HashMap<String, String>() // 메인 스레드에서만

    /** 지금 보는 대화가 답하는 중인가(보내기 대신 멈춤 버튼). */
    val busy: Boolean get() = turns.isBusy(chatId)
    /** 어느 대화든 답하는 중인가(포그라운드 서비스, 자동 정지). */
    val anyBusy: Boolean get() = !turns.isEmpty()
    /** 답하는 대화(서랍 표시). */
    val runningChats: Set<String> get() = turns.running()
    /** 지금 보는 대화의 "지금 하는 일" 한 줄. 메인 스레드에서 읽는다. */
    val activity: String? get() = activities[chatId]

    /** 승인 요청을 낸 Codex 스레드 → 대화(모르면 null). */
    fun chatForThread(threadId: String?): String? = turns.chatForThread(threadId)

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
    private fun effectiveChoice(): ModelChoice = modelFallback.choice(ModelCatalog.sanitize(modelChoice, models), models)

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

    @Volatile private var client: AppServerClient? = null
    @Volatile private var proc: Process? = null
    @Volatile private var loginId: String? = null
    private var turnHold = false
    private var loginHold = false
    private lateinit var chatGptAuth: ChatGptAuthManager
    // Serialize vault commits, token installation and logout; network calls never hold a UI lock.
    private val authMutations = Executors.newSingleThreadExecutor { r -> Thread(r, "codex-auth").apply { isDaemon = true } }
    private val loginStateLock = Any()
    private val idleStop = Runnable { if (isIdle()) stopIfIdle() }

    private fun isIdle() = listeners.isEmpty() && turns.isEmpty() && loginId == null

    /**
     * 화면이 모두 사라진 뒤의 자동 정지. 정지가 대기열에서 기다리는 사이 화면이 돌아오면 그 화면의
     * [ensureStarted]는 아직 READY인 상태를 보고 그냥 돌아가므로, 정지 직전에 다시 확인하고 정지 뒤에도 다시 띄운다.
     */
    private fun stopIfIdle() {
        control.execute {
            if (!isIdle()) return@execute
            teardown()
            setState(SessionState.STOPPED)
            if (listeners.isNotEmpty()) main.post { ensureStarted() }
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        AppSettings.init(app)
        chatGptAuth = ChatGptAuthManager(app)
        chats = ChatStore(File(app.filesDir, "chats"), kr.snu.etlassistant.files.WorkFileCleanup.root(app)).also { it.load() }
        // 예전 형식(chat.json 하나 + 스레드 하나)이 있으면 첫 대화로 옮긴다.
        chats.migrateLegacy(File(app.filesDir, "chat.json"), AppSettings.legacyThreadId, System.currentTimeMillis())?.let {
            AppSettings.currentChatId = it
        }
        AppSettings.legacyThreadId = null
        val current = AppSettings.currentChatId?.takeIf { ChatStore.isSafeId(it) } ?: chats.list().firstOrNull()?.id ?: chats.newId()
        chatId = current
        AppSettings.currentChatId = current
        model = ChatModel(chats.fileFor(current)).also { it.load() }
        liveModels[current] = model
        // 비서가 eTL 파일을 받거나 PDF 쪽 이미지를 만들면 그 도구를 부른 대화에 카드로 붙인다. 답변 중일 때만(개발자 도구의 시험 실행은 제외).
        // 어느 대화인지는 도구 호출 순간에 정한다(곧 이어지는 turn/completed보다 먼저 메인 스레드에 올라간다).
        EtlEvents.listener = object : EtlEvents.Listener {
            override fun onFilesSaved(files: List<EtlEvents.SavedFile>, pagePreview: Boolean, origin: String?) {
                val target = when (val r = turns.routeTool(origin)) {
                    is TurnRegistry.ToolRoute.One -> r.chatId
                    is TurnRegistry.ToolRoute.Ambiguous -> r.latest
                    TurnRegistry.ToolRoute.None -> return
                }
                val atts = files.map { f ->
                    Attachment(f.name, f.mime, f.size, f.file.absolutePath, f.phoneUri, f.shownPath, pagePreview, referenceOnly = pagePreview || f.referenceOnly)
                }
                main.post { modelFor(target)?.addFiles(atts) }
            }

            // 바뀐 데이터 알림은 사용자가 꼭 봐야 하므로(프롬프트 인젝션 대책) 대화를 확실히 모르면 답하는 대화 모두에, 답하는 대화가 없으면 지금 대화에 남긴다.
            override fun onNotice(text: String, origin: String?) {
                val targets: List<String?> = when (val r = turns.routeTool(origin)) {
                    is TurnRegistry.ToolRoute.One -> listOf(r.chatId)
                    is TurnRegistry.ToolRoute.Ambiguous -> r.all
                    TurnRegistry.ToolRoute.None -> listOf(null)
                }
                main.post { targets.forEach { id -> (if (id == null) model else modelFor(id) ?: model).addNotice(text) } }
            }
        }
    }

    // ---- 화면 연결 -----------------------------------------------------------

    /** 마지막으로 세션에 붙은 화면의 클래스 이름. 로그인 완료 알림이 이 화면으로 돌아간다. */
    @Volatile private var lastScreen: String? = null
    /** 보이는 채팅 화면. 없으면 답변이 끝날 때 기기 알림으로 알린다. */
    private val chatScreens = CopyOnWriteArraySet<Listener>()

    /** [chatScreen]: 채팅 화면이 붙는다(답변이 끝났을 때 막대로 알릴 수 있다). */
    fun attach(l: Listener, chatScreen: Boolean = false) {
        listeners += l
        if (chatScreen) chatScreens += l
        if (l is android.app.Activity) lastScreen = l.javaClass.name
        main.removeCallbacks(idleStop)
        l.onEvent(SessionEvent.State(state, stateDetail))
        account?.let { l.onEvent(SessionEvent.Account(it)) }
        l.onEvent(SessionEvent.Busy(busy))
        l.onEvent(SessionEvent.Running(turns.running()))
        l.onEvent(SessionEvent.Activity(activity))
        l.onEvent(SessionEvent.Models(models, modelsLoading))
        // 진행 중인 로그인이 없어도 알려야 한다. 화면이 꺼져 있는 동안 로그인이 끝나면 LoginDone을 놓치기 때문이다.
        l.onEvent(lastLogin ?: SessionEvent.LoginIdle)
    }

    /**
     * 화면이 세션에서 떨어진다. [changingConfigurations]가 참이면(화면 회전 등) 같은 화면이 곧 다시 붙으므로
     * 바로 멈추지 않는다. 그 밖에는 마지막 화면이 떨어지는 즉시, 턴·로그인이 없으면 멈춘다.
     */
    fun detach(l: Listener, changingConfigurations: Boolean = false) {
        listeners -= l
        chatScreens -= l
        if (listeners.isNotEmpty()) return
        main.removeCallbacks(idleStop)
        if (changingConfigurations) main.postDelayed(idleStop, CONFIG_CHANGE_STOP_MS) else main.post(idleStop)
    }

    /** 턴이나 로그인이 끝났을 때, 화면이 없으면 바로 멈춘다(타이머에 기대지 않는다). */
    private fun stopSoonIfIdle() {
        if (!isIdle()) return
        main.removeCallbacks(idleStop)
        main.post(idleStop)
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
                setState(SessionState.FAILED, e.message ?: "시작하지 못했어요")
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
        val prep = CodexRunner.prepare(CodexRunner.appServerArgs(), options(), "채팅 세션")
            ?: throw IOException("Codex 실행 파일을 준비하지 못했어요")
        val p = prep.pb.start()
        proc = p
        CodexRunner.pump(p.errorStream, "err")

        val holder = arrayOfNulls<AppServerClient>(1)
        val c = AppServerClient(p, ::onNotification, ::onServerRequest, refreshChatGptTokens = { params, reply ->
            calls.execute {
                try {
                    if (client !== holder[0] || AppSettings.authMode != AppSettings.AUTH_CHATGPT) { reply(null); return@execute }
                    val previous = params.optString("previousAccountId").takeIf { it.isNotBlank() && it != "null" }
                    reply(chatGptAuth.credentials(forceRefresh = true, previousAccountId = previous)?.externalParams())
                } catch (_: Exception) { reply(null) }
            }
        }) { onClosed(holder[0]) }
        holder[0] = c
        c.start()
        c.request("initialize", JSONObject()
            .put("clientInfo", JSONObject().put("name", "etl-android").put("title", "영민이").put("version", "0.1.0"))
            .put("capabilities", JSONObject().put("experimentalApi", true)))
        c.notify("initialized")
        client = c
        authMutations.submit {
            if (client === c && AppSettings.authMode == AppSettings.AUTH_CHATGPT) chatGptAuth.credentials()?.let { installChatGptCredentials(c, it) }
        }.get(90, java.util.concurrent.TimeUnit.SECONDS)
        refreshAccountBlocking()
        setState(SessionState.READY)
        CodexRunner.log.line("[session] 준비됨")
        refreshModels()
        calls.execute {
            try {
                purgeOnStart(c)
            } catch (e: Exception) {
                CodexRunner.log.line("[session] 지운 대화 정리 실패: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun teardown() {
        kr.snu.etlassistant.etl.AiWriteApproval.cancelAll()
        if (::chatGptAuth.isInitialized) chatGptAuth.cancelLogin()
        val old = client
        client = null // 먼저 비워서, 닫힘 콜백이 이 클라이언트를 현재 것으로 착각하지 않게 한다
        old?.close()
        proc?.destroy()
        proc = null
        EtlService.stopServer()
        val ended = turns.clear()
        pendingInputs.clear()
        modelFallback.clear()
        loginId = null
        lastLogin = null
        main.post {
            for (t in ended) {
                trackers.remove(t.chatId)
                setActivity(t.chatId, null)
                if (t.deleted) {
                    t.threadId?.let { queueThreadDelete(it) }
                    continue
                }
                modelFor(t.chatId)?.let {
                    it.closeStreaming()
                    it.addNotice("답변이 중간에 끊겼어요")
                }
                announceFinish(t.chatId, TurnEnd.FAILED)
            }
            turnsChanged()
            releaseLoginHold()
        }
    }

    private fun onClosed(closed: AppServerClient?) {
        if (closed == null || client !== closed || state == SessionState.STOPPED) return
        CodexRunner.log.line("[session] app-server가 종료됐습니다")
        control.execute {
            teardown()
            setState(SessionState.FAILED, "비서 프로세스가 종료됐어요")
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
        if (before != null && before != info) {
            modelFallback.clear()
            refreshModels()
        }
    }

    fun loginBrowser() = startLogin(LoginKind.BROWSER)

    fun loginDeviceCode() = startLogin(LoginKind.DEVICE_CODE)

    private fun installChatGptCredentials(c: AppServerClient, credentials: ChatGptCredentials) {
        c.request("account/login/start", credentials.externalParams().put("type", "chatgptAuthTokens"), 30)
    }

    private fun startLogin(kind: LoginKind) {
        val c = client ?: return
        val attempt = chatGptAuth.newLogin()
        val id = java.util.UUID.randomUUID().toString()
        loginId = id
        lastLogin = null
        main.post { if (loginId == id) acquireLoginHold() }
        calls.execute {
            try {
                val prompt: (String, String?) -> Unit = { url, code ->
                    attempt.check()
                    if (loginId != id || client !== c) throw IOException("로그인을 취소했어요")
                    val event = SessionEvent.LoginPrompt(kind, url, code)
                    lastLogin = event
                    emit(event)
                    if (code != null) copyToClipboard(code)
                    // OAuth URLs contain state and PKCE data. Open directly without CodexRunner's URL logging.
                    main.post { runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
                val credentials = if (kind == LoginKind.BROWSER) ChatGptOAuth.browser(attempt, prompt) else ChatGptOAuth.device(attempt, prompt)
                authMutations.execute finish@ {
                    var installed = false
                    try {
                        if (loginId != id || client !== c || !chatGptAuth.finishLogin(attempt, credentials)) return@finish
                        if (loginId == id && client === c) {
                            installChatGptCredentials(c, credentials)
                            installed = true
                        }
                        val confirmed = synchronized(loginStateLock) {
                            if (!installed || loginId != id || client !== c || !chatGptAuth.confirmLogin(attempt)) false
                            else { loginId = null; lastLogin = null; true }
                        }
                        if (!confirmed) {
                            if (installed && client === c) runCatching { c.request("account/logout", JSONObject(), 30) }
                            chatGptAuth.rollbackLogin(attempt)
                            if (client === c) restart() // Reinstall the previous encrypted grant after clearing a canceled install.
                            return@finish
                        }
                        main.post { releaseLoginHold(); if (!isVisible) notifyLoginDone() }
                        emit(SessionEvent.LoginDone(true, null))
                        refreshAccount()
                    } catch (_: Exception) {
                        runCatching { chatGptAuth.rollbackLogin(attempt) }
                        // A timed-out installation may still have reached the server. Destroy that process.
                        if (client === c) restart()
                        if (loginId == id) {
                            loginId = null
                            lastLogin = null
                            main.post { releaseLoginHold() }
                            emit(SessionEvent.LoginDone(false, "로그인을 저장하거나 적용하지 못했어요. 다시 시도해 주세요."))
                        }
                    }
                }
            } catch (_: Exception) {
                if (loginId != id) return@execute
                chatGptAuth.cancelLogin()
                loginId = null
                lastLogin = null
                main.post { releaseLoginHold() }
                emit(SessionEvent.LoginDone(false, "로그인하지 못했어요. 네트워크를 확인하고 다시 시도하거나 기기 코드 로그인을 사용해 주세요."))
            }
        }
    }

    fun cancelLogin() {
        synchronized(loginStateLock) {
            chatGptAuth.cancelLogin()
            loginId = null
            lastLogin = null
        }
        main.post { releaseLoginHold() }
        emit(SessionEvent.LoginIdle)
    }

    fun logout() {
        // Clear local encrypted credentials even when app-server is stopped or failed.
        synchronized(loginStateLock) {
            chatGptAuth.cancelLogin()
            loginId = null
            lastLogin = null
        }
        authMutations.execute {
            val previous = try { chatGptAuth.clear() }
            catch (_: Exception) {
                emit(SessionEvent.LoginDone(false, "저장된 로그인을 지우지 못했어요. 다시 시도해 주세요."))
                stop()
                return@execute
            }
            val c = client
            if (c != null) runCatching { c.request("account/logout", JSONObject(), 30) }
            account = AccountInfo(AccountInfo.Kind.NONE)
            emit(SessionEvent.Account(account!!))
            emit(SessionEvent.LoginIdle)
            main.post { releaseLoginHold() }
            // Destroy an unresponsive server too; it must not retain the previous access token.
            restart()
            if (previous != null) calls.execute { runCatching { ChatGptOAuth.revoke(previous) } }
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

    /**
     * 지금 보는 대화에서 보낸다. 다른 대화가 답하는 중이어도 보낼 수 있다(동시에 도는 답변 수 제한 없음).
     * @return 보낼 수 없는 상태면 이유 문구, 보냈으면 null
     */
    fun send(text: String, attachments: List<Attachment> = emptyList()): String? {
        val c = client
        if (state != SessionState.READY || c == null) return "비서가 아직 준비되지 않았어요"
        if (account?.loggedIn != true) return "먼저 로그인해야 해요"
        val forChat = chatId
        if (turns.isBusy(forChat)) return "이전 답변이 끝날 때까지 기다려 주세요"
        val workspace = File(app.filesDir, "workspace")
        val images = attachments.filter { it.isImage && it.path != null }
        val files = (attachments - images.toSet()).filter { it.path != null }.map { a ->
            Triple(File(a.path!!).relativeTo(workspace).path, a.mime, a.size)
        }
        val prompt = AttachmentLogic.promptText(text, files, images.size)
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", prompt).put("text_elements", JSONArray()))
        // 사진은 Codex가 직접 읽어 모델에게 이미지로 보낸다.
        images.forEach { input.put(JSONObject().put("type", "localImage").put("path", it.path)) }
        // 모델 선택은 보내는 순간의 것으로 이 턴에 묶는다(다른 대화에서 바꿔도 이미 도는 답변은 그대로).
        val choice = effectiveChoice()
        val turn = turns.start(forChat, modelChoice.model == null) ?: return "이전 답변이 끝날 때까지 기다려 주세요"
        model.addUser(text, attachments)
        chats.noteUserMessage(forChat, text, attachments, System.currentTimeMillis())
        val tracker = ActivityTracker().also { trackers[forChat] = it }
        setActivity(forChat, tracker.turnStarted())
        turnsChanged()
        val request = PendingInput(c, turn, input.toString(), ModelFallback.Attempt(turn.modelAuto, choice))
        pendingInputs[forChat] = request
        startAttempt(request)
        return null
    }

    private fun currentAttempt(request: PendingInput, generation: Int): Boolean =
        client === request.client && pendingInputs[request.turn.chatId] === request &&
            turns.get(request.turn.chatId) === request.turn && request.attempt.generation == generation

    /** 같은 질문을 재시도해도 사용자 메시지와 첨부 카드는 한 번만 추가한다. */
    private fun startAttempt(request: PendingInput) {
        val forChat = request.turn.chatId
        val turn = request.turn
        val generation = request.attempt.generation
        val choice = request.attempt.choice
        val c = request.client
        calls.execute {
            try {
                if (!currentAttempt(request, generation)) return@execute
                if (turn.interruptRequested || turn.deleted) {
                    main.post { if (currentAttempt(request, generation)) finishAttempt(request, TurnEnd.INTERRUPTED, null) }
                    return@execute
                }
                val (thread, resolvedModel) = ensureThread(c, forChat, choice)
                request.actualModel = resolvedModel ?: choice.model
                if (!currentAttempt(request, generation)) return@execute
                if (turn.interruptRequested || turn.deleted) {
                    main.post { if (currentAttempt(request, generation)) finishAttempt(request, TurnEnd.INTERRUPTED, null) }
                    return@execute
                }
                val turnParams = JSONObject().put("threadId", thread).put("input", JSONArray(request.input))
                choice.model?.let { turnParams.put("model", it) }
                choice.effort?.let { turnParams.put("effort", it) }
                kr.snu.etlassistant.etl.AiWriteApproval.beginTurn(thread)
                val r = c.request("turn/start", turnParams)
                main.post {
                    // 빠른 실패 후 재시도가 시작됐다면 이전 turn/start 응답으로 새 턴 ID를 덮지 않는다.
                    if (!currentAttempt(request, generation)) return@post
                    val started = turns.setTurnId(forChat, r.optJSONObject("turn")?.optString("id")?.takeIf { it.isNotEmpty() })
                    if (started === turn && turn.interruptRequested) turns.interruptTarget(forChat)?.let { (th, tu) ->
                        calls.execute { requestInterrupt(c, th, tu) }
                    }
                }
            } catch (e: IOException) {
                main.post {
                    if (currentAttempt(request, generation)) finishAttempt(request, TurnEnd.FAILED, "보내지 못했어요: ${e.message}", modelHint = false)
                }
            }
        }
    }

    /** [chatId] 대화(기본: 지금 대화)의 답변만 멈춘다. 다른 대화의 답변과 확인 창은 그대로다. */
    fun interrupt(chatId: String = this.chatId) {
        val t = turns.get(chatId) ?: return
        kr.snu.etlassistant.etl.AiWriteApproval.cancelFor(t.threadId)
        val target = turns.interruptTarget(chatId) ?: return // 아직 turn/start 응답 전: 응답을 받는 대로 멈춘다
        val c = client ?: return
        calls.execute { requestInterrupt(c, target.first, target.second) }
    }

    private fun requestInterrupt(c: AppServerClient, thread: String, turn: String) {
        try {
            c.request("turn/interrupt", JSONObject().put("threadId", thread).put("turnId", turn), 15)
        } catch (_: IOException) {
        }
    }

    /**
     * 빈 새 대화를 연다. 지금 대화는 목록에 그대로 남는다(아무것도 보내지 않은 대화는 목록에 쌓이지 않는다).
     * 답하는 대화가 있어도 열 수 있다(그 답변은 뒤에서 계속된다). 메인 스레드에서 부른다.
     */
    fun newConversation(): Boolean {
        if (chats.get(chatId) == null && model.messages.isEmpty()) return true // 이미 빈 새 대화
        openChat(chats.newId())
        return true
    }

    /** 목록의 대화를 연다. 메인 스레드에서 부른다. 없는 대화면 false. */
    fun openConversation(id: String): Boolean {
        if (!ChatStore.isSafeId(id)) return false
        if (id == chatId) return true
        if (chats.get(id) == null && !turns.isBusy(id)) return false
        openChat(id)
        return true
    }

    /** 메모리에 있는 대화 모델. 없으면 저장된 대화에서 읽는다(지운 대화는 null). 메인 스레드에서만. */
    private fun modelFor(id: String): ChatModel? {
        if (id == chatId) return model
        liveModels[id]?.let { return it }
        if (chats.get(id) == null) return null
        return ChatModel(chats.fileFor(id)).also { it.load(); liveModels[id] = it }
    }

    private fun openChat(id: String) {
        val old = model
        val oldId = chatId
        if (chats.get(oldId) != null) old.save() // 아무것도 보내지 않은 빈 대화는 파일을 만들지 않는다
        else if (!turns.isBusy(oldId)) liveModels.remove(oldId)
        val l = old.listener
        old.listener = null
        chatId = id
        AppSettings.currentChatId = id
        model = liveModels.getOrPut(id) { ChatModel(chats.fileFor(id)).also { it.load() } }
        model.listener = l
        l?.onReset()
        cancelFinishNotification(id)
        emit(SessionEvent.ChatSwitched(id))
        emit(SessionEvent.Busy(busy))
        emit(SessionEvent.Activity(activity))
    }

    /** 답하는 중에도 바꿀 수 있다. */
    fun renameConversation(id: String, title: String) = chats.rename(id, title)

    /**
     * 대화를 지운다(색인과 메시지 파일). Codex 쪽 스레드도 `thread/delete`로 지운다(rollout 파일과 Codex 내부 DB의 행).
     * Codex가 꺼져 있거나 실패하면 rollout 파일만 바로 지우고, 스레드 ID를 남겨 다음 시작 때 다시 `thread/delete`한다.
     * 지금 대화를 지우면 빈 새 대화로 바꾼다. 답하는 중이면 그 답변을 먼저 멈추고, 스레드는 답변이 끝난 뒤 지운다.
     * 메인 스레드에서 부른다.
     */
    fun deleteConversation(id: String): Boolean {
        val running = turns.get(id)
        if (running != null) {
            running.deleted = true
            interrupt(id)
        }
        val removed = chats.delete(id)
        if (id == chatId) openChat(chats.newId())
        liveModels.remove(id)?.discard()
        cancelFinishNotification(id)
        if (running == null) turns.forget(id)
        removed?.threadId?.takeIf { ThreadPurge.isThreadId(it) }?.let { queueThreadDelete(it) }
        turnsChanged()
        return true
    }

    /** 지울 스레드를 기록하고 지운다. 답하는 중인 스레드는 그 답변이 끝날 때까지 미룬다([purgeThreads]). */
    private fun queueThreadDelete(thread: String) {
        if (!ThreadPurge.isThreadId(thread)) return
        synchronized(purgeLock) { AppSettings.pendingThreadDeletes = AppSettings.pendingThreadDeletes + thread }
        calls.execute { purgeThreads(client) }
    }

    private val purgeLock = Any()

    private val codexHome: File get() = File(app.filesDir, "codex-home")

    /**
     * 지우기로 한 스레드를 정리한다. [c]가 있으면 `thread/delete`를 먼저 부르고, 결과와 상관없이 남은 rollout 파일을 지운다.
     * `thread/delete`가 성공했거나 스레드가 이미 없을 때만 목록에서 뺀다(그 밖의 실패는 다음 시작 때 다시 시도).
     */
    private fun purgeThreads(c: AppServerClient?) {
        // 답하는 중인 스레드(답변 중에 지운 대화)는 그 답변이 끝난 뒤 지운다.
        val pending = synchronized(purgeLock) { AppSettings.pendingThreadDeletes } - turns.runningThreads()
        if (pending.isEmpty()) return
        val done = mutableSetOf<String>()
        for (t in pending) {
            if (!ThreadPurge.isThreadId(t)) { done += t; continue }
            var gone = false
            if (c != null) {
                try {
                    c.request("thread/delete", JSONObject().put("threadId", t), 30)
                    gone = true
                } catch (e: IOException) {
                    val msg = e.message.orEmpty()
                    gone = msg.contains("not found", ignoreCase = true)
                    if (!gone) CodexRunner.log.line("[session] 스레드 삭제 실패(다음에 다시 시도): $msg")
                }
            }
            val files = ThreadPurge.delete(codexHome, t)
            if (files > 0) CodexRunner.log.line("[session] 지운 대화의 기록 파일 ${files}개를 지웠습니다")
            if (gone) done += t
        }
        if (done.isNotEmpty()) synchronized(purgeLock) { AppSettings.pendingThreadDeletes = AppSettings.pendingThreadDeletes - done }
    }

    /**
     * 시작할 때 한 번: 예전 버전이 대화를 지울 때 보관(thread/archive)만 해 둔 스레드와, 지우다 만 스레드를 지운다.
     * 보관함은 앱이 대화를 지울 때만 쓰므로, 대화 목록에 없는 보관 스레드는 모두 지운 대화의 것이다.
     */
    private fun purgeOnStart(c: AppServerClient) {
        val keep = chats.list().mapNotNull { it.threadId }.toSet()
        val orphans = ThreadPurge.archivedOrphans(codexHome, keep)
        if (orphans.isNotEmpty()) {
            CodexRunner.log.line("[session] 지운 대화의 보관 기록 ${orphans.size}개를 정리합니다")
            synchronized(purgeLock) { AppSettings.pendingThreadDeletes = AppSettings.pendingThreadDeletes + orphans }
        }
        purgeThreads(c)
    }

    /**
     * 말투 지침. 기본 규칙(해요체, 짧고 담백, 정확성 우선)은 AGENTS.md에 있고, 여기서는 설정의 "반말로 대화"에 따라
     * 어느 쪽을 쓸지만 정한다. 턴마다 thread/resume에 실어 보내 기존 대화에도 다음 턴부터 반영되게 한다.
     */
    private fun toneInstructions(): String = if (AppSettings.casualTone) {
        "말투: 사용자가 설정에서 \"반말로 대화\"를 켰다. 지금부터 친구처럼 반말(\"~야\", \"~해\", \"~할까?\")로 답한다. " +
            "앞선 답이 해요체였어도 이번 답부터 반말로 쓴다. 짧고 담백하게 쓰고, 과한 이모지·감탄사·인사말은 넣지 않으며, 정확성을 먼저 지킨다. " +
            "예: \"이번 주 마감은 2개야. 회로이론 HW5가 내일 23:59까지라 제일 급해.\""
    } else {
        "말투: 친근한 해요체(\"~예요\", \"~해요\")로 답한다. 앞선 답이 반말이었어도 이번 답부터 해요체로 쓴다. " +
            "\"~입니다/~습니다\"체는 쓰지 않는다. 짧고 담백하게 쓰고, 과한 이모지·감탄사·인사말은 넣지 않으며, 정확성을 먼저 지킨다."
    }

    private fun ensureThread(c: AppServerClient, forChat: String, choice: ModelChoice): Pair<String, String?> {
        val cwd = File(app.filesDir, "workspace").path
        val saved = chats.get(forChat)?.threadId
        CodexRunner.log.line("[session] 모델: ${choice.model ?: "자동"}, 강도: ${choice.effort ?: "기본"}")
        if (saved != null) {
            turns.bindThread(forChat, saved)
            try {
                val resume = JSONObject().put("threadId", saved).put("cwd", cwd)
                    .put("approvalPolicy", "never").put("sandbox", "read-only")
                    .put("developerInstructions", toneInstructions())
                choice.model?.let { resume.put("model", it) }
                val r = c.request("thread/resume", resume)
                return saved to r.optJSONObject("thread")?.optString("model")?.takeIf { it.isNotBlank() && it != "null" }
            } catch (e: IOException) {
                // 모델 거절은 대화 이력 손상이 아니다. 같은 스레드에서 다른 모델로 재개한다.
                if (ModelFallback.unavailableModel(e.message) != null) throw e
                CodexRunner.log.line("[session] 스레드 재개 실패, 새로 시작합니다: ${e.message}")
                chats.setThread(forChat, null)
                main.post { if (turns.get(forChat)?.deleted != true) modelFor(forChat)?.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.") }
            }
        }
        val start = JSONObject().put("cwd", cwd)
            .put("approvalPolicy", "never").put("sandbox", "read-only")
            .put("developerInstructions", toneInstructions())
        choice.model?.let { start.put("model", it) }
        val r = c.request("thread/start", start)
        val id = r.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotEmpty() }
            ?: throw IOException("스레드를 만들지 못했어요")
        turns.bindThread(forChat, id)
        chats.setThread(forChat, id)
        return id to r.optJSONObject("thread")?.optString("model")?.takeIf { it.isNotBlank() && it != "null" }
    }

    // ---- 서버 알림 ------------------------------------------------------------

    /** 알림의 `threadId`로 그 알림이 쓸 대화(답하는 중이고 지우지 않은 대화)를 찾는다. */
    private fun liveChat(p: JSONObject): String? = turns.liveTurnForThread(p.optString("threadId").takeIf { it.isNotEmpty() })?.chatId

    /** 이전 모델의 늦은 항목·오류를 새 재시도에 붙이지 않는다. 메인 스레드에서 사용한다. */
    private fun acceptsEvent(chat: String, p: JSONObject): Boolean {
        val request = pendingInputs[chat] ?: return false
        val turnId = p.optString("turnId").takeIf { it.isNotBlank() && it != "null" }
        return turns.get(chat) === request.turn && request.attempt.accepts(turnId)
    }

    private fun onNotification(method: String, p: JSONObject) {
        when (method) {
            "item/agentMessage/delta" -> {
                val chat = liveChat(p) ?: return
                val id = p.optString("itemId")
                val d = p.optString("delta")
                main.post {
                    if (!acceptsEvent(chat, p)) return@post
                    pendingInputs[chat]?.attempt?.hasProgress = true
                    modelFor(chat)?.appendAssistant(id, d)
                    trackers[chat]?.let { setActivity(chat, it.textDelta()) }
                }
            }
            "item/started", "item/completed" -> {
                val chat = liveChat(p) ?: return
                p.optJSONObject("item")?.let { value ->
                    main.post {
                        if (!acceptsEvent(chat, p)) return@post
                        // 도구·답변이 시작됐다면 다시 보내서 일정 추가 등을 중복 실행하지 않는다.
                        if (value.optString("type") !in setOf("reasoning", "userMessage")) pendingInputs[chat]?.attempt?.hasProgress = true
                        item(chat, value, method == "item/completed")
                    }
                }
            }
            "turn/completed" -> {
                val threadId = p.optString("threadId").takeIf { it.isNotEmpty() }
                val chat = turns.chatForThread(threadId) ?: return
                val t = turns.get(chat) ?: return
                val turn = p.optJSONObject("turn")
                val finishedId = turn?.optString("id")?.takeIf { it.isNotEmpty() && it != "null" }
                val status = turn?.optString("status") ?: "?"
                val err = turn?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() && it != "null" }
                val end = when (status) {
                    "failed" -> TurnEnd.FAILED
                    "interrupted" -> TurnEnd.INTERRUPTED
                    else -> TurnEnd.COMPLETED
                }
                main.post {
                    val request = pendingInputs[chat] ?: return@post
                    if (request.turn !== t || !request.attempt.accepts(finishedId)) return@post
                    if (t.turnId != null && finishedId != null && t.turnId != finishedId) return@post
                    finishAttempt(request, end, err, finishedId)
                }
            }
            "error" -> {
                val threadId = p.optString("threadId").takeIf { it.isNotEmpty() }
                if (!p.optBoolean("willRetry", false)) {
                    val msg = p.optJSONObject("error")?.optString("message").orEmpty()
                    val chat = liveChat(p)
                    if (msg.isNotEmpty() && (chat != null || threadId == null)) main.post {
                        if (chat != null) {
                            if (!acceptsEvent(chat, p)) return@post
                            kr.snu.etlassistant.etl.AiWriteApproval.cancelFor(threadId)
                            // turn/completed에도 같은 오류가 온다. 거기서 재시도하거나 한 번만 표시한다.
                            pendingInputs[chat]?.attempt?.error = msg
                        } else model.addNotice(msg)
                    }
                }
            }
            "account/login/completed" -> {
                // External-token installation has no interactive login ID. App-owned OAuth emits its own result.
                if (p.isNull("loginId")) { refreshAccount(); return }
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

    private fun item(chat: String, item: JSONObject, completed: Boolean) {
        val id = item.optString("id")
        when (item.optString("type")) {
            "agentMessage" -> if (completed) {
                val text = item.optString("text")
                main.post {
                    modelFor(chat)?.finishAssistant(id, text)
                    trackers[chat]?.let { setActivity(chat, it.messageDone()) }
                }
            }
            // 도구 호출은 대화에 남기지 않고, 진행 중인 일을 한 줄로만 알린다.
            "mcpToolCall" -> {
                val tool = item.optString("tool")
                main.post { trackers[chat]?.let { setActivity(chat, if (completed) it.toolFinished(id) else it.toolStarted(id, tool)) } }
            }
            "imageGeneration" -> if (completed && item.optString("status") == "completed") {
                val m = modelFor(chat) ?: return
                try {
                    val attachment = GeneratedImages.attachment(item, listOf(
                        File(app.filesDir, "workspace/generated_images"),
                        File(app.filesDir, "codex-home/generated_images"),
                    ), File(kr.snu.etlassistant.files.WorkFileCleanup.root(app), "generated_images"))
                    if (attachment == null) m.addNotice("이미지는 생성됐지만 채팅에 불러오지 못했어요.")
                    else if (m.messages.none { message -> message.attachments.any { it.path == attachment.path } }) {
                        m.addFiles(listOf(attachment))
                    }
                } catch (_: Exception) {
                    m.addNotice("이미지는 생성됐지만 채팅에 저장하지 못했어요.")
                }
            }
        }
    }

    /** [chat] 대화의 "지금 하는 일". 지금 보는 대화면 화면에 알린다. 메인 스레드에서만. */
    private fun setActivity(chat: String, label: String?) {
        val before = activities[chat]
        if (label == null) activities.remove(chat) else activities[chat] = label
        if (before != label && chat == chatId) emit(SessionEvent.Activity(label))
    }

    private fun onServerRequest(method: String, params: JSONObject?) {
        CodexRunner.log.line("[session] 서버 요청을 거절했습니다: $method")
        val chat = params?.let { liveChat(it) }
        main.post { (chat?.let { modelFor(it) } ?: model).addNotice("지원하지 않는 요청이라 거절했어요: $method") }
    }

    /** 모델 거절만 같은 논리 턴 안에서 재시도한다. 완료/알림/사용자 메시지를 중복 만들지 않는다. */
    private fun finishAttempt(
        request: PendingInput,
        end: TurnEnd,
        error: String?,
        finishedTurnId: String? = null,
        modelHint: Boolean = true,
    ) {
        val turn = request.turn
        val chat = turn.chatId
        if (pendingInputs[chat] !== request || turns.get(chat) !== turn) return
        val finalEnd = if (turn.interruptRequested) TurnEnd.INTERRUPTED else end
        val detail = error ?: request.attempt.error
        if (finalEnd == TurnEnd.FAILED && !turn.deleted) {
            val next = modelFallback.next(request.attempt, detail, models)
            val previousTurnId = finishedTurnId ?: turn.turnId
            if (next != null && turns.retry(chat, turn)) {
                kr.snu.etlassistant.etl.AiWriteApproval.endTurn(turn.threadId)
                request.attempt.retry(next, previousTurnId)
                request.actualModel = null
                val name = models.firstOrNull { it.model == next.model }?.displayName ?: next.model
                modelFor(chat)?.addNotice("선택된 모델을 사용할 수 없어 ${name}로 다시 시도할게요.")
                trackers[chat]?.let { setActivity(chat, it.turnStarted()) }
                startAttempt(request)
                return
            }
        }
        if (finalEnd == TurnEnd.COMPLETED) modelFallback.succeeded(request.actualModel ?: request.attempt.choice.model)
        endTurn(chat, turn, finalEnd, detail, modelHint)
    }

    /**
     * 한 대화의 답변이 끝났다(메인 스레드). [expected]와 다른 턴이면(이미 끝나고 새로 보낸 경우) 무시한다.
     * [modelHint]: 실패했을 때 모델 선택 안내를 덧붙일지(보내기 자체가 실패한 경우는 덧붙이지 않는다).
     */
    private fun endTurn(chat: String, expected: TurnRegistry.Turn, end: TurnEnd, error: String?, modelHint: Boolean = true) {
        if (turns.get(chat) !== expected) return
        pendingInputs.remove(chat)
        turns.finish(chat)
        kr.snu.etlassistant.etl.AiWriteApproval.endTurn(expected.threadId)
        trackers.remove(chat)
        setActivity(chat, null)
        if (expected.deleted) {
            turns.forget(chat)
            expected.threadId?.let { queueThreadDelete(it) }
        } else {
            modelFor(chat)?.let { m ->
                m.closeStreaming()
                when (end) {
                    TurnEnd.FAILED -> {
                        m.addNotice(error ?: "답변을 만들지 못했어요")
                        if (modelHint && !expected.modelAuto) m.addNotice("선택한 모델 설정 때문일 수 있어요. 설정에서 '자동'으로 되돌려 보세요.")
                    }
                    TurnEnd.INTERRUPTED -> m.addNotice("답변을 멈췄어요")
                    TurnEnd.COMPLETED -> {}
                }
            }
            announceFinish(chat, end)
        }
        turnsChanged()
    }

    /** 지금 보지 않는 대화가 끝나면 채팅 화면 아래 막대로, 채팅 화면이 안 보이면 기기 알림으로 알린다. */
    private fun announceFinish(chat: String, end: TurnEnd) {
        val text = TurnLogic.finishText(chats.get(chat)?.title, end == TurnEnd.COMPLETED)
        when (TurnLogic.finishNotice(chat, chatId, chatScreens.isNotEmpty(), end, deleted = false)) {
            FinishNotice.NONE -> {}
            FinishNotice.BAR -> emit(SessionEvent.TurnFinished(chat, text))
            FinishNotice.NOTIFICATION -> notifyTurnFinished(chat, text)
        }
    }

    // ---- 포그라운드 서비스, 알림 ------------------------------------------------

    /** 답하는 대화가 바뀜(메인 스레드): 포그라운드 서비스는 하나라도 답하는 동안 붙잡는다. 모두 끝나고 화면이 없으면 멈춘다. */
    private fun turnsChanged() {
        val any = !turns.isEmpty()
        if (any && !turnHold) {
            turnHold = true
            runCatching { RunService.acquire(app) }
        } else if (!any && turnHold) {
            turnHold = false
            RunService.release(app)
        }
        emit(SessionEvent.Running(turns.running()))
        emit(SessionEvent.Busy(busy))
        if (!any) stopSoonIfIdle()
    }

    private fun finishNotificationId(chat: String) = ("answer:$chat").hashCode()

    /** 채팅 화면이 안 보일 때 끝난 답변. 누르면 그 대화가 열린다. 잠금화면에는 대화 이름을 숨긴다. */
    private fun notifyTurnFinished(chat: String, text: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ANSWER_CHANNEL, "답변 완료", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "다른 화면에 있을 때 비서의 답변이 끝나면 알려요"
        })
        val intent = Intent().setClassName(app, "kr.snu.etlassistant.chat.ChatActivity")
            .putExtra(EXTRA_OPEN_CHAT, chat)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = finishNotificationId(chat)
        val pi = PendingIntent.getActivity(app, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = android.app.Notification.Builder(app, ANSWER_CHANNEL)
            .setSmallIcon(kr.snu.etlassistant.phase0.R.drawable.ic_stat_etl)
            .setContentTitle("영민이 답변이 끝났어요")
            .build()
        val n = android.app.Notification.Builder(app, ANSWER_CHANNEL)
            .setSmallIcon(kr.snu.etlassistant.phase0.R.drawable.ic_stat_etl)
            .setContentTitle(text)
            .setContentText("탭하면 대화를 열어요")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { nm.notify(id, n) }
    }

    private fun cancelFinishNotification(chat: String) {
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(finishNotificationId(chat)) }
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
        stopSoonIfIdle()
    }

    private fun notifyLoginDone() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("login", "로그인", NotificationManager.IMPORTANCE_HIGH))
        // 로그인하러 나가기 직전에 보던 화면(첫 실행 안내, 설정 등)으로 돌아간다. CLEAR_TOP|SINGLE_TOP이라
        // 그 화면이 살아 있으면 새로 만들지 않고 그대로 앞으로 온다. ChatActivity(singleTask)를 열면 그 위의
        // 안내 화면이 지워지거나 안내를 건너뛰므로 기본값으로만 쓴다.
        val intent = Intent().setClassName(app, lastScreen ?: "kr.snu.etlassistant.chat.ChatActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(app, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(app, "login")
            .setSmallIcon(kr.snu.etlassistant.phase0.R.drawable.ic_stat_etl)
            .setContentTitle("로그인이 끝났어요")
            .setContentText("탭하면 영민이로 돌아가요")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2, n) }
    }

    /** 화면 회전 등으로 화면이 다시 만들어질 때만 쓰는 유예. 새 화면이 붙으면 취소된다. */
    const val CONFIG_CHANGE_STOP_MS = 5_000L
    /** 답변 완료 알림을 누르면 열 대화(ChatActivity 인텐트). */
    const val EXTRA_OPEN_CHAT = "openChat"
    private const val ANSWER_CHANNEL = "answer_done"
    private const val MODELS_MAX_PAGES = 5
}
