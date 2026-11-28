package kr.snu.youngmini.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/** Private newline JSON pipe to the sandboxed desktop shell; never a network listener. */
fun main() {
    System.setProperty("java.awt.headless", "true")
    val output = System.out.bufferedWriter(Charsets.UTF_8)
    fun emit(value: JSONObject) = synchronized(output) { output.write(value.toString()); output.newLine(); output.flush() }
    val paths = System.getProperty("youngmini.data")?.let { AppPaths(java.io.File(it)) } ?: AppPaths()
    val lock = try { paths.lock() } catch (_: Exception) {
        emit(JSONObject().put("type", "fatal").put("message", "영민이가 이미 실행 중이거나 사용자 폴더를 열지 못했어요.")); return
    }
    val controller = AppController(paths)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    controller.pdfs.onRequest={ job->emit(JSONObject().put("type","renderPdf").put("id",job.id).put("name",job.name).put("markdown",job.markdown).put("path",job.target.path)) }
    controller.onBrowse = { emit(JSONObject().put("type", "browse").put("url", it)) }
    val latch = CountDownLatch(1)
    val shutdown = Thread { controller.close(); scope.cancel(); runCatching { lock.close() }; latch.countDown() }
    Runtime.getRuntime().addShutdownHook(shutdown)
    scope.launch { controller.state.collect { emit(JSONObject().put("type", "state").put("state", StateJson.encode(it))) } }
    scope.launch { controller.start() }
    try {
        // Read with a cap before parsing, so a malformed local command cannot allocate indefinitely.
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return
                if (c == '\n'.code) break
                if (line.length >= 100_000) return
                line.append(c.toChar())
            }
            val command = runCatching { JSONObject(line.toString()) }.getOrNull() ?: continue
            scope.launch {
                try {
                    if(command.optString("action")=="artifactText") {
                        val id=command.getString("id");controller.artifactFile(id)
                        val text=withContext(Dispatchers.IO){controller.artifactText(id)}
                        emit(JSONObject().put("type","artifactText").put("requestId",command.getString("requestId")).put("text",text))
                    } else dispatch(controller, command)
                }
                catch (_: Exception) { emit(JSONObject().put("type", "commandError").put("message", "요청을 처리하지 못했어요. 다시 시도해 주세요.")) }
            }
        }
    } finally {
        controller.close(); scope.cancel(); lock.close()
        Runtime.getRuntime().removeShutdownHook(shutdown)
    }
}

internal fun dispatch(controller: AppController, c: JSONObject) {
    val id = c.optString("chatId")
    when (c.getString("action")) {
        "start" -> controller.start()
        "login" -> controller.login(c.optBoolean("device"))
        "cancelLogin" -> controller.cancelLogin()
        "logout" -> controller.logout()
        "connectEtl" -> controller.connectEtl(c.getString("token"))
        "disconnectEtl" -> controller.disconnectEtl()
        "loadCourses" -> controller.loadCourses()
        "newChat" -> controller.newChat(c.optString("projectId",controller.state.value.activeProjectId))
        "openProject" -> controller.openProject(c.getString("projectId"))
        "saveProject" -> controller.saveProject(c.opt("projectId") as? String,c.optString("name"),c.optString("instructions"),c.optJSONArray("courseIds").longs())
        "createPdf" -> controller.createPdf(id,c.getString("messageId"))
        "pdfRendered" -> controller.pdfs.complete(c.getString("id"),c.optBoolean("success"))
        "savePersonal" -> controller.agenda.savePersonal(c.getJSONObject("item"))
        "deletePersonal" -> controller.agenda.deletePersonal(c.getString("id"))
        "completePersonal" -> controller.agenda.completePersonal(c.getString("id"),c.optBoolean("done"))
        "settings" -> controller.agenda.setSettings(c.getJSONObject("settings"))
        "noticeAck" -> controller.agenda.acknowledge(c.getString("id"),c.optBoolean("read"))
        "readAll" -> controller.agenda.readAll()
        "testNotice" -> controller.agenda.notice("test","영민이 알림 테스트","이 PC에서 일정, 공지와 성적 알림을 받을 수 있어요.")
        "refreshAcademic" -> controller.refreshAcademic()
        "openChat" -> controller.openChat(id)
        "focus" -> controller.focus(id)
        "setDraft" -> controller.setDraft(id, c.optString("text"))
        "setChoice" -> controller.setChoice(id, c.opt("model") as? String, c.opt("effort") as? String)
        "send" -> controller.send(id, c.getString("text"))
        "interrupt" -> controller.interrupt(id)
        "acknowledge" -> controller.acknowledge(id)
        "dismissError" -> controller.dismissError(c.opt("chatId") as? String)
        else -> throw IllegalArgumentException("Unsupported action")
    }
}

object StateJson {
    fun encode(s: AppState): JSONObject = JSONObject()
        .put("starting", s.starting).put("ready", s.ready).put("status", s.status).put("error", s.error ?: JSONObject.NULL)
        .put("loggedIn", s.loggedIn).put("loginBusy", s.loginBusy).put("loginUrl", s.loginUrl ?: JSONObject.NULL).put("loginCode", s.loginCode ?: JSONObject.NULL)
        .put("etlConnected", s.etlConnected).put("etlBusy", s.etlBusy)
        .put("courses", JSONArray(s.courses.map { JSONObject().put("id", it.id).put("name", it.name).put("term", it.term) }))
        .put("activeChatId", s.activeChatId).put("visibleChatIds", JSONArray(s.visibleChatIds))
        .put("models", JSONArray(s.models.map { m -> JSONObject().put("model", m.model).put("displayName", m.displayName).put("isDefault", m.isDefault)
            .put("defaultEffort", m.defaultEffort ?: JSONObject.NULL).put("efforts", JSONArray(m.efforts.map { JSONObject().put("effort", it.effort).put("description", it.description) })) }))
        .put("sessions", JSONArray(s.sessions.map { chat -> JSONObject().put("id", chat.id).put("title", chat.title).put("draft", chat.draft).put("projectId",chat.projectId).put("artifacts",JSONArray(chat.artifacts.map {it.json()})).put("pdfBusy",chat.pdfBusy)
            .put("model", chat.choice.model ?: JSONObject.NULL).put("effort", chat.choice.effort ?: JSONObject.NULL).put("phase", chat.phase).put("status", chat.status)
            .put("error", chat.error ?: JSONObject.NULL).put("unread", chat.unread).put("updatedAt", chat.updatedAt)
            .put("messages", JSONArray((if (chat.id in s.visibleChatIds) chat.messages else emptyList()).map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text) })) }))
        .put("projects",JSONArray(s.projects.map {it.json()})).put("activeProjectId",s.activeProjectId).put("agenda",s.agenda)
}
