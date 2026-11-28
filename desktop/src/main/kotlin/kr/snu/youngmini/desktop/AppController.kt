package kr.snu.youngmini.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.snu.etlassistant.codex.ModelCatalog
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlFiles
import kr.snu.etlassistant.etl.EtlLogic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(), val title: String = "새 대화", val threadId: String? = null,
    val messages: List<ChatMessage> = emptyList(), val files: List<Downloaded> = emptyList(), val draft: String = "",
    val choice: ModelChoice = ModelChoice(), val phase: String = "idle", val status: String = "대화를 시작해 보세요",
    val error: String? = null, val unread: Boolean = false, val updatedAt: Long = System.currentTimeMillis(),
    val projectId:String="integrated",val artifacts:List<Artifact> = emptyList(), val pdfBusy:Boolean=false,
) {
    val busy get() = phase == "running"
    fun saved() = SavedChat(id, title, threadId, messages, choice.model, choice.effort, files, draft, updatedAt, projectId, artifacts)
    companion object {
        fun from(chat: SavedChat) = ChatSession(chat.id, chat.title, chat.thread, chat.messages, chat.files, chat.draft,
            ModelChoice(chat.model, chat.effort), updatedAt = chat.updatedAt,projectId=chat.projectId,artifacts=chat.artifacts + chat.files.filter {f->chat.artifacts.none {it.path==f.path}}.map {f->Artifact(UUID.nameUUIDFromBytes((chat.id+f.path).toByteArray()).toString(),f.name,f.path,f.bytes,"requested")})
    }
}

data class AppState(
    val starting: Boolean = true, val ready: Boolean = false, val status: String = "영민이를 준비하고 있어요", val error: String? = null,
    val loggedIn: Boolean = false, val loginBusy: Boolean = false, val loginUrl: String? = null, val loginCode: String? = null,
    val etlConnected: Boolean = false, val etlBusy: Boolean = false, val courses: List<Course> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val sessions: List<ChatSession> = emptyList(), val activeChatId: String = "", val visibleChatIds: List<String> = emptyList(),
    val projects:List<Project> = listOf(Project.integrated),val activeProjectId:String="integrated",val agenda:JSONObject=JSONObject(),
) { val busy get() = sessions.any { it.busy } }

class AppController(val paths: AppPaths, private val vault: SecretVault = SecretVault(paths.secrets), backend: ChatBackend? = null) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = ChatHistory(paths.chats)
    private val materials = Materials(paths, vault)
    private val reader = DocumentReader(paths.workspace)
    private val projectStore=ProjectStore(paths.root)
    private val contexts=ToolContexts()
    val pdfs=PdfJobs(paths)
    val agenda=Agenda(paths.root)
    private val engine = backend ?: DesktopEngine(paths, DesktopAuth(vault), DesktopTools(materials, reader, contexts, pdfs) { listOfNotNull(vault.read("etl")) })
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    var onBrowse: (String) -> Unit = {}
    private val loaded = mutableSetOf<String>()
    private val threadOwners = mutableMapOf<String, String>()
    private val turns = mutableMapOf<String, String>()
    private val runs = mutableMapOf<String, Long>()
    private val saves = mutableMapOf<String, Mutex>()
    private val focusByProject=mutableMapOf<String,String>()
    private var etlRevision = 0
    private var authRevision = 0
    private var loginJob: Job? = null
    @Volatile private var closed=false
    private var starting = false
    private var pollJob:Job?=null
    private var refreshJob:Job?=null
    private var watchGeneration=0

    init {
        contexts.onArtifact={id,artifact->scope.launch { addArtifact(id,artifact) }}
        agenda.onChange={scope.launch {mutable.update {it.copy(agenda=agenda.snapshot())}}}
        mutable.update {it.copy(projects=projectStore.load(),agenda=agenda.snapshot())}
        scope.launch {while(isActive){withContext(Dispatchers.IO){agenda.tick()};delay(15_000)}}
        engine.onNotification = { method, params -> scope.launch { notification(method, params) } }
        engine.onClosed = { scope.launch {
            loaded.clear(); turns.clear();state.value.sessions.forEach {contexts.activate(it.id,false)}
            mutable.update { s -> s.copy(ready = false, starting = false, status = "연결 끊김", error = "비서 연결이 끊어졌어요. 다시 연결해 주세요.",
                sessions = s.sessions.map { if (it.busy) it.copy(phase = "failed", status = "연결이 끊어졌어요", error = "다시 연결한 뒤 이어서 질문해 주세요.", unread = true) else it }) }
            state.value.sessions.forEach { save(it.id) }
        } }
    }

    fun start() {
        if (state.value.ready || starting) return
        starting = true
        mutable.update { it.copy(starting = true, error = null) }
        scope.launch {
            try {
                if (state.value.sessions.isEmpty()) {
                    val saved = withContext(Dispatchers.IO) { history.list() }.map(ChatSession::from)
                    val sessions = saved.ifEmpty { listOf(ChatSession()) }
                    sessions.forEach { chat -> chat.threadId?.let { threadOwners[it] = chat.id } }
                    mutable.update { it.copy(sessions = sessions, activeChatId = sessions.first().id, activeProjectId=sessions.first().projectId,visibleChatIds = listOf(sessions.first().id)) }
                }
                withContext(Dispatchers.IO) { engine.start { progress -> mutable.update { it.copy(status = progress) } } }
                val logged = withContext(Dispatchers.IO) { engine.loggedIn() }
                var warning = engine.authWarning
                val connected = try { withContext(Dispatchers.IO) { materials.connected() } } catch (_: Exception) { warning = "eTL 토큰을 다시 연결해 주세요."; false }
                mutable.update { it.copy(starting = false, ready = true, loggedIn = logged, etlConnected = connected, status = "준비됐어요", error = warning) }
                if (logged) loadModels()
                if (connected) {loadCourses();startPolling()}
            } catch (e: Exception) { mutable.update { it.copy(starting = false, ready = false, status = "연결하지 못했어요", error = errorMessage(e)) } }
            finally { starting = false }
        }
    }

    fun login(device: Boolean = false) {
        if (!state.value.ready || state.value.loginBusy || state.value.busy) return
        val revision = ++authRevision
        mutable.update { it.copy(loginBusy = true, loginUrl = null, loginCode = null, error = null) }
        loginJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { engine.login(device) { url, code ->
                    if (revision == authRevision) { mutable.update { it.copy(loginUrl = url, loginCode = code) }; onBrowse(url) }
                } }
                if (revision == authRevision) { mutable.update { it.copy(loggedIn = true, loginBusy = false, loginUrl = null, loginCode = null) }; loadModels() }
            } catch (_: CancellationException) { }
            catch (e: Exception) { if (revision == authRevision) mutable.update { it.copy(loginBusy = false, loginUrl = null, loginCode = null, error = errorMessage(e)) } }
        }
    }
    fun cancelLogin() {
        ++authRevision; loginJob?.cancel()
        mutable.update { it.copy(loginBusy = false, loginUrl = null, loginCode = null, ready = false) }
        scope.launch { withContext(Dispatchers.IO) { engine.cancelLogin() }; start() }
    }
    fun logout() {
        if (state.value.busy || state.value.loginBusy || starting) return
        ++authRevision
        mutable.update { it.copy(loggedIn = false, ready = false, models = emptyList(), error = null) }
        scope.launch {
            try { withContext(Dispatchers.IO) { engine.logout() }; loaded.clear(); start() }
            catch (e: Exception) { showError(e) }
        }
    }
    private fun loadModels() = scope.launch {
        try {
            val models = withContext(Dispatchers.IO) { engine.models() }
            mutable.update { s -> s.copy(models = models, sessions = s.sessions.map { it.copy(choice = ModelCatalog.sanitize(it.choice, models)) }) }
        } catch (_: Exception) { mutable.update { it.copy(error = "모델 목록을 받지 못했어요. 다시 연결해 주세요.") } }
    }
    fun setChoice(id: String, model: String?, effort: String?) {
        if (session(id)?.busy != false) return
        updateChat(id) { it.copy(choice = ModelCatalog.sanitize(ModelChoice(model, effort), state.value.models)) }; save(id)
    }

    fun connectEtl(token: String) {
        if (state.value.etlBusy || state.value.busy) return
        ++etlRevision
        ++watchGeneration;pollJob?.cancel();refreshJob?.cancel()
        mutable.update { it.copy(etlBusy = true, error = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { materials.connect(token) }
                mutable.update { it.copy(etlConnected = true, etlBusy = false, courses = emptyList()) }
                agenda.clearAcademic();loadCourses();startPolling()
            } catch (e: Exception) { mutable.update { it.copy(etlBusy = false, error = errorMessage(e)) };if(state.value.etlConnected)startPolling() }
        }
    }
    fun disconnectEtl() {
        if (state.value.etlBusy || state.value.busy) return
        ++etlRevision
        ++watchGeneration;pollJob?.cancel();refreshJob?.cancel()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { materials.disconnect();agenda.clearAcademic() }
                mutable.update { it.copy(etlConnected = false, courses = emptyList()) }
            } catch (e: Exception) { showError(e) }
        }
    }
    fun loadCourses() {
        if (!state.value.etlConnected || state.value.etlBusy) return
        val revision = etlRevision
        mutable.update { it.copy(etlBusy = true, error = null) }
        scope.launch {
            try {
                val courses = withContext(Dispatchers.IO) { materials.courses() }
                if (revision == etlRevision) {
                    val projects=projectStore.reconcile(state.value.projects,courses)
                    withContext(Dispatchers.IO){projectStore.save(projects)}
                    mutable.update { it.copy(courses = courses, projects=projects,etlBusy = false) }
                }
            } catch (e: Exception) { if (revision == etlRevision) mutable.update { it.copy(etlBusy = false, error = errorMessage(e)) } }
        }
    }
    fun newChat(projectId:String=state.value.activeProjectId): String {
        val project=state.value.projects.find {it.id==projectId}?:return ""
        val chat = ChatSession(projectId=project.id)
        focusByProject[project.id]=chat.id
        mutable.update { s -> s.copy(sessions = s.sessions + chat, activeChatId = chat.id,activeProjectId=project.id,visibleChatIds=listOf(chat.id)) }
        save(chat.id);return chat.id
    }
    fun openProject(id:String) {
        if(state.value.projects.none {it.id==id})return
        val chat=focusByProject[id]?.let(::session)?:state.value.sessions.filter {it.projectId==id}.maxByOrNull {it.updatedAt}
        if(chat==null)newChat(id) else openChat(chat.id)
    }
    fun saveProject(id:String?,name:String,instructions:String,courseIds:List<Long>) {
        val old=state.value.projects.find {it.id==id}
        if(state.value.sessions.any {it.projectId==id&&it.busy})return
        require(courseIds.all {cid->state.value.courses.any {it.id==cid} || old?.courseIds?.contains(cid)==true})
        val project=if(old!=null&&old.kind!="custom")old.copy(instructions=instructions.take(8000)) else projectStore.custom(name,courseIds,instructions,id)
        val projects=state.value.projects.filterNot {it.id==project.id}+project
        scope.launch {try {
            withContext(Dispatchers.IO){projectStore.save(projects)}
            mutable.update {it.copy(projects=projects.sortedBy {p->if(p.kind=="integrated")0 else if(p.kind=="course")1 else 2})}
            state.value.sessions.filter {it.projectId==project.id}.forEach {loaded.remove(it.id);contexts.activate(it.id,false)}
            openProject(project.id)
        }catch(e:Exception){showError(e)}}
    }
    fun openChat(id: String) {
        val chat=session(id)?:return
        focusByProject[chat.projectId]=id
        mutable.update {it.copy(activeChatId=id,activeProjectId=chat.projectId,visibleChatIds=listOf(id))};acknowledge(id)
    }
    fun focus(id:String)=openChat(id)
    fun createPdf(chatId:String,messageId:String) {
        val chat=session(chatId)?:return
        val message=chat.messages.find {it.id==messageId&&it.role=="assistant"}?:return
        if(chat.pdfBusy)return
        updateChat(chatId){it.copy(pdfBusy=true,error=null)}
        scope.launch {try {
            val artifact=withContext(Dispatchers.IO){pdfs.create(chat.title+".pdf",message.text)}
            addArtifact(chatId,artifact.copy(messageId=messageId));agenda.notice("completion","PDF가 완성됐어요",chat.title,chatId,hiddenOnly=true)
        }catch(e:Exception){updateChat(chatId){it.copy(error=errorMessage(e))}}finally{updateChat(chatId){it.copy(pdfBusy=false)}}}
    }
    private fun addArtifact(id:String,artifact:Artifact) {
        updateChat(id){it.copy(artifacts=it.artifacts.filterNot {a->a.id==artifact.id}+artifact)};save(id)
    }
    fun artifact(id:String):Artifact?=state.value.sessions.asSequence().flatMap {it.artifacts.asSequence()}.find {it.id==id}
    fun artifactFile(id:String):File {
        val a=artifact(id)?:throw EtlException("파일을 찾지 못했어요")
        val f=EtlFiles.resolveInside(paths.workspace,a.path)
        require(f.isFile);require(f.canonicalFile.toPath().startsWith(File(paths.workspace,"etl-files").canonicalFile.toPath())||f.canonicalFile.toPath().startsWith(File(paths.workspace,"generated").canonicalFile.toPath()))
        return f
    }
    fun artifactText(id:String):String=reader.read(artifact(id)?.path?:throw EtlException("파일을 찾지 못했어요"),limit=60_000).optString("text")
    fun refreshAcademic() {
        if(!state.value.etlConnected||refreshJob?.isActive==true)return
        val generation=watchGeneration
        refreshJob=scope.launch {withContext(Dispatchers.IO) {
            try {
                val courses=materials.courses();val client=materials.client();var failed=false
                for(course in courses) {
                    if(generation!=watchGeneration)return@withContext
                    fun fetch(path:String):List<JSONObject>?=try {client.getAll(path)}catch(e:kr.snu.etlassistant.etl.EtlAuthException){throw e}catch(_:Exception){failed=true;null}
                    val assignments=fetch(kr.snu.etlassistant.etl.CourseAssignments.assignmentsPath(course.id))
                    val announcements=fetch("/api/v1/announcements?context_codes[]=course_${course.id}&start_date=1970-01-01&end_date=${java.time.LocalDate.now(EtlLogic.KST).plusDays(1)}&per_page=50")
                    if(generation!=watchGeneration)return@withContext
                    agenda.ingest(course,assignments,announcements)
                }
                if(failed&&generation==watchGeneration)agenda.syncError("일부 과목을 확인하지 못했어요. 다음 확인 때 다시 시도해요.")
            }catch(_:kr.snu.etlassistant.etl.EtlAuthException){if(generation==watchGeneration){agenda.syncError("eTL 토큰을 다시 연결해 주세요.");pollJob?.cancel()}}
            catch(_:Exception){if(generation==watchGeneration)agenda.syncError("eTL을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.")}
        }}
    }
    private fun startPolling(){pollJob?.cancel();pollJob=scope.launch {while(isActive){refreshAcademic();delay(30*60*1000L)}}}
    fun setDraft(id: String, text: String) { if (session(id)?.draft != text) { updateChat(id) { it.copy(draft = text.take(32_000)) }; save(id) } }
    fun acknowledge(id: String) { updateChat(id) { it.copy(unread = false) } }

    fun send(id: String, text: String) {
        val s = state.value
        val chat = session(id) ?: return
        if (!s.ready || !s.loggedIn || chat.busy || s.loginBusy || text.isBlank()) return
        if (text.length > 32_000) { updateChat(id) { it.copy(error = "질문을 32,000자 이내로 줄여 주세요") }; return }
        val choice = ModelCatalog.sanitize(chat.choice, s.models)
        val display = text.trim()
        val prompt = text.trim()
        val run = (runs[id] ?: 0L) + 1
        runs[id] = run
        updateChat(id) { it.copy(phase = "running", status = "생각하고 있어요", error = null, unread = false, draft = "", choice = choice,
            title = if (it.messages.isEmpty()) text.lineSequence().first().take(40) else it.title,
            messages = it.messages + ChatMessage(UUID.randomUUID().toString(), "user", display), updatedAt = System.currentTimeMillis()) }
        contexts.activate(id,true)
        scope.launch {
            try {
                var thread = session(id)?.threadId
                if (id !in loaded) {
                    val project=state.value.projects.find {it.id==chat.projectId}?:Project.integrated
                    val context=contexts.register(id,project)
                    thread = withContext(Dispatchers.IO) { engine.thread(thread, choice, contexts.instructions(context)) }
                    threadOwners[thread] = id; loaded += id
                    updateChat(id) { it.copy(threadId = thread) }
                }
                save(id)
                val turn = withContext(Dispatchers.IO) { engine.send(thread!!, prompt, choice) }
                if (session(id)?.busy == true && runs[id] == run) turns[id] = turn
            } catch (e: Exception) { if (runs[id] == run) { updateChat(id) { it.copy(phase = "failed", status = "보내지 못했어요", error = errorMessage(e), unread = true) }; save(id) } }
        }
    }
    fun interrupt(id: String) {
        val thread = session(id)?.threadId ?: return
        val turn = turns[id] ?: return
        scope.launch { try { withContext(Dispatchers.IO) { engine.interrupt(thread, turn) } } catch (e: Exception) { updateChat(id) { it.copy(error = errorMessage(e)) } } }
    }
    fun dismissError(id: String? = null) { if (id == null) mutable.update { it.copy(error = null) } else updateChat(id) { it.copy(error = null) } }
    private fun session(id: String) = state.value.sessions.find { it.id == id }
    private fun updateChat(id: String, block: (ChatSession) -> ChatSession) { mutable.update { s -> s.copy(sessions = s.sessions.map { if (it.id == id) block(it) else it }) } }
    private fun save(id: String) {
        val lock = saves.getOrPut(id) { Mutex() }
        scope.launch { lock.withLock {
            val current = session(id) ?: return@withLock
            try { withContext(Dispatchers.IO) { synchronized(history){if(!closed)history.save(current.saved())} } }
            catch (_: Exception) { mutable.update { it.copy(error = "대화를 저장하지 못했어요. 저장 공간을 확인해 주세요.") } }
        } }
    }

    private fun notification(method: String, params: JSONObject) {
        val id = (params.opt("threadId") as? String)?.let(threadOwners::get)
        if (id == null) {
            if (method == "error" && !params.optBoolean("willRetry")) mutable.update { it.copy(error = safe(params.optJSONObject("error")?.optString("message") ?: "비서 요청에 실패했어요")) }
            return
        }
        when (method) {
            "turn/started" -> params.optJSONObject("turn")?.optString("id")?.let { turns[id] = it }
            "item/agentMessage/delta" -> {
                val item = params.optString("itemId"); val delta = params.optString("delta")
                updateChat(id) { chat -> chat.copy(status = "답변하고 있어요", messages = if (chat.messages.any { it.id == item }) chat.messages.map { if (it.id == item) it.copy(text = it.text + delta) else it } else chat.messages + ChatMessage(item, "assistant", delta)) }
            }
            "item/started", "item/completed" -> params.optJSONObject("item")?.let { item ->
                if (item.optString("type") == "mcpToolCall") updateChat(id) { it.copy(status = if (method == "item/completed") "답변을 준비하고 있어요" else "자료를 읽고 있어요") }
                if (item.optString("type") == "agentMessage" && method == "item/completed" && item.optString("text").isNotBlank()) {
                    val itemId = item.getString("id"); val text = item.getString("text")
                    updateChat(id) { chat -> chat.copy(messages = if (chat.messages.any { it.id == itemId }) chat.messages.map { if (it.id == itemId) it.copy(text = text) else it } else chat.messages + ChatMessage(itemId, "assistant", text)) }
                }
            }
            "turn/completed" -> {
                val turn = params.optJSONObject("turn"); val failed = turn?.optString("status") == "failed"
                turns.remove(id);contexts.activate(id,false)
                updateChat(id) { it.copy(phase = if (failed) "failed" else "done", status = if (failed) "답변하지 못했어요" else if (turn?.optString("status") == "interrupted") "답변을 멈췄어요" else "답변이 도착했어요",
                    error = if (failed) safe(turn?.optJSONObject("error")?.optString("message") ?: "다시 질문해 주세요") else null,
                    unread = true, updatedAt = System.currentTimeMillis()) }
                val last=session(id)?.messages?.lastOrNull {it.role=="assistant"}?.id
                updateChat(id){it.copy(artifacts=it.artifacts.map {a->if(a.messageId==null)a.copy(messageId=last)else a})}
                val chat=session(id)
                if(chat!=null&&turn?.optString("status")!="interrupted")agenda.notice("completion",chat.status,chat.title,id,hiddenOnly=true)
                save(id)
            }
            "error" -> if (!params.optBoolean("willRetry")) updateChat(id) { it.copy(error = safe(params.optJSONObject("error")?.optString("message") ?: "비서 요청에 실패했어요")) }
        }
    }
    private fun safe(text: String): String = EtlLogic.unsignText(listOfNotNull(runCatching { vault.read("etl") }.getOrNull()).fold(text) { result, secret -> result.replace(secret, "***") })
        .replace(Regex("(?i)Bearer\\s+\\S+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "***").take(1000)
    private fun errorMessage(e: Exception) = if (e is java.io.IOException || e is EtlException) safe(e.message ?: "연결을 확인해 주세요") else "요청을 처리하지 못했어요. 다시 시도해 주세요."
    private fun showError(e: Exception) { mutable.update { it.copy(error = errorMessage(e)) } }
    override fun close() {
        if(closed)return
        closed=true;++watchGeneration;scope.cancel();pdfs.close();engine.close()
        synchronized(history){state.value.sessions.forEach {runCatching {history.save(it.saved())}}}
    }
}
