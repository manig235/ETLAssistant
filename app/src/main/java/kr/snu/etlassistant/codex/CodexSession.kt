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

    /** 마지막으로 열어 둔 대화. 없거나 지워졌으면 빈 새 대화로 시작한다. */
    var currentChatId: String?
        get() = prefs.getString("currentChatId", null)
        set(v) = prefs.edit().apply { if (v == null) remove("currentChatId") else putString("currentChatId", v) }.apply()
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
    private val idleStop = Runnable { if (isIdle()) stopIfIdle() }

    private fun isIdle() = listeners.isEmpty() && !busy && loginId == null

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
        chats = ChatStore(File(app.filesDir, "chats")).also { it.load() }
        // 예전 형식(chat.json 하나 + 스레드 하나)이 있으면 첫 대화로 옮긴다.
        chats.migrateLegacy(File(app.filesDir, "chat.json"), AppSettings.legacyThreadId, System.currentTimeMillis())?.let {
            AppSettings.currentChatId = it
        }
        AppSettings.legacyThreadId = null
        val current = AppSettings.currentChatId?.takeIf { ChatStore.isSafeId(it) } ?: chats.list().firstOrNull()?.id ?: chats.newId()
        chatId = current
        AppSettings.currentChatId = current
        model = ChatModel(chats.fileFor(current)).also { it.load() }
        // 비서가 eTL 파일을 받거나 PDF 쪽 이미지를 만들면 대화에 카드로 붙인다. 답변 중일 때만(개발자 도구의 시험 실행은 제외).
        EtlEvents.listener = object : EtlEvents.Listener {
            override fun onFilesSaved(files: List<EtlEvents.SavedFile>, pagePreview: Boolean) {
                if (!busy) return
                val atts = files.map { f ->
                    Attachment(f.name, f.mime, f.size, f.file.absolutePath, f.phoneUri, f.shownPath, pagePreview)
                }
                main.post { model.addFiles(atts) }
            }

            override fun onNotice(text: String) {
                main.post { model.addNotice(text) }
            }
        }
    }

    // ---- 화면 연결 -----------------------------------------------------------

    /** 마지막으로 세션에 붙은 화면의 클래스 이름. 로그인 완료 알림이 이 화면으로 돌아간다. */
    @Volatile private var lastScreen: String? = null

    fun attach(l: Listener) {
        listeners += l
        if (l is android.app.Activity) lastScreen = l.javaClass.name
        main.removeCallbacks(idleStop)
        l.onEvent(SessionEvent.State(state, stateDetail))
        account?.let { l.onEvent(SessionEvent.Account(it)) }
        l.onEvent(SessionEvent.Busy(busy))
        l.onEvent(SessionEvent.Activity(activity))
        l.onEvent(SessionEvent.Models(models, modelsLoading))
        // 진행 중인 로그인이 없어도 알려야 한다. 화면이 꺼져 있는 동안 로그인이 끝나면 LoginDone을 놓치기 때문이다.
        l.onEvent(lastLogin ?: SessionEvent.LoginIdle)
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
            .put("clientInfo", JSONObject().put("name", "etl-android").put("title", "미니 eTL 비서").put("version", "0.1.0"))
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
        val id = loginId
        val c = client
        loginId = null
        lastLogin = null
        if (id == null || c == null) {
            // 취소할 로그인이 이미 없다(끝났거나 세션이 멈춤). 화면에 남은 진행 표시만 지워서 빠져나올 수 있게 한다.
            main.post { releaseLoginHold() }
            emit(SessionEvent.LoginIdle)
            return
        }
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
    fun send(text: String, attachments: List<Attachment> = emptyList()): String? {
        val c = client
        if (state != SessionState.READY || c == null) return "비서가 아직 준비되지 않았습니다"
        if (account?.loggedIn != true) return "먼저 로그인해야 합니다"
        if (busy) return "이전 답변이 끝날 때까지 기다려 주세요"
        val workspace = File(app.filesDir, "workspace")
        val images = attachments.filter { it.isImage && it.path != null }
        val files = (attachments - images.toSet()).filter { it.path != null }.map { a ->
            Triple(File(a.path!!).relativeTo(workspace).path, a.mime, a.size)
        }
        val prompt = AttachmentLogic.promptText(text, files, images.size)
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", prompt).put("text_elements", JSONArray()))
        // 사진은 Codex가 직접 읽어 모델에게 이미지로 보낸다.
        images.forEach { input.put(JSONObject().put("type", "localImage").put("path", it.path)) }
        model.addUser(text, attachments)
        val forChat = chatId
        chats.noteUserMessage(forChat, text, attachments, System.currentTimeMillis())
        setBusy(true)
        setActivity(tracker.turnStarted())
        calls.execute {
            try {
                val thread = ensureThread(c, forChat)
                val turnParams = JSONObject().put("threadId", thread).put("input", input)
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
        val thread = chats.get(chatId)?.threadId ?: return
        val turn = currentTurnId ?: return
        calls.execute {
            try {
                c.request("turn/interrupt", JSONObject().put("threadId", thread).put("turnId", turn), 15)
            } catch (_: IOException) {
            }
        }
    }

    /**
     * 빈 새 대화를 연다. 지금 대화는 목록에 그대로 남는다(아무것도 보내지 않은 대화는 목록에 쌓이지 않는다).
     * 메인 스레드에서 부른다. 답변 중이면 false.
     */
    fun newConversation(): Boolean {
        if (busy) return false
        if (chats.get(chatId) == null && model.messages.isEmpty()) return true // 이미 빈 새 대화
        openChat(chats.newId())
        return true
    }

    /** 목록의 대화를 연다. 메인 스레드에서 부른다. 답변 중이면 false. */
    fun openConversation(id: String): Boolean {
        if (busy) return false
        if (!ChatStore.isSafeId(id) || id == chatId) return true
        openChat(id)
        return true
    }

    private fun openChat(id: String) {
        if (chats.get(chatId) != null) model.save() // 아무것도 보내지 않은 빈 대화는 파일을 만들지 않는다
        chatId = id
        AppSettings.currentChatId = id
        model.switchTo(chats.fileFor(id))
        setActivity(null)
    }

    fun renameConversation(id: String, title: String) = chats.rename(id, title)

    /**
     * 대화를 지운다(색인과 메시지 파일). Codex 쪽 스레드는 thread/archive로 보관 처리한다(실패해도 무시).
     * 지금 대화를 지우면 빈 새 대화로 바꾼다. 메인 스레드에서 부른다. 답변 중이면 false.
     */
    fun deleteConversation(id: String): Boolean {
        if (busy) return false
        val removed = chats.delete(id) ?: return true
        if (id == chatId) openChat(chats.newId())
        val thread = removed.threadId
        val c = client
        if (thread != null && c != null) {
            calls.execute {
                try {
                    c.request("thread/archive", JSONObject().put("threadId", thread), 15)
                } catch (e: IOException) {
                    CodexRunner.log.line("[session] 스레드 보관 실패(무시): ${e.message}")
                }
            }
        }
        return true
    }

    @Synchronized
    private fun ensureThread(c: AppServerClient, forChat: String): String {
        val cwd = File(app.filesDir, "workspace").path
        val saved = chats.get(forChat)?.threadId
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
                chats.setThread(forChat, null)
                main.post { if (chatId == forChat) model.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 갑니다.") }
            }
        }
        val start = JSONObject().put("cwd", cwd)
            .put("approvalPolicy", "never").put("sandbox", "read-only")
        choice.model?.let { start.put("model", it) }
        val r = c.request("thread/start", start)
        val id = r.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotEmpty() }
            ?: throw IOException("스레드를 만들지 못했습니다")
        chats.setThread(forChat, id)
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
        // 로그인하러 나가기 직전에 보던 화면(첫 실행 안내, 설정 등)으로 돌아간다. CLEAR_TOP|SINGLE_TOP이라
        // 그 화면이 살아 있으면 새로 만들지 않고 그대로 앞으로 온다. ChatActivity(singleTask)를 열면 그 위의
        // 안내 화면이 지워지거나 안내를 건너뛰므로 기본값으로만 쓴다.
        val intent = Intent().setClassName(app, lastScreen ?: "kr.snu.etlassistant.chat.ChatActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(app, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(app, "login")
            .setSmallIcon(kr.snu.etlassistant.phase0.R.drawable.ic_stat_etl)
            .setContentTitle("로그인이 완료됐습니다")
            .setContentText("탭하면 미니 eTL 비서로 돌아갑니다")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2, n) }
    }

    const val IDLE_STOP_MS = 120_000L
    private const val MODELS_MAX_PAGES = 5
}
