package kr.snu.youngmini.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.snu.etlassistant.codex.EffortOption
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MultiSessionTest {
    private class Backend : ChatBackend {
        override var onNotification: (String, JSONObject) -> Unit = { _, _ -> }
        override var onClosed: () -> Unit = {}
        override val authWarning: String? = null
        data class Sent(val thread: String, val text: String, val choice: ModelChoice)
        val sent = CopyOnWriteArrayList<Sent>()
        val interrupted = CopyOnWriteArrayList<Pair<String,String>>()
        override fun start(progress: (String) -> Unit) {}
        override fun loggedIn() = true
        override fun models() = listOf(ModelInfo("sol", "sol", "Sol", "", true, false, "medium", listOf("low","medium","high","xhigh").map { EffortOption(it,it) }))
        override fun login(device: Boolean, prompt: (String, String?) -> Unit) {}
        override fun cancelLogin() {}
        override fun logout() {}
        override fun thread(saved: String?, choice: ModelChoice,instructions:String) = saved ?: UUID.randomUUID().toString()
        override fun send(thread: String, text: String, choice: ModelChoice): String { sent += Sent(thread,text,choice); return "turn-$thread"+if(sent.count {it.thread==thread}>1)"-retry"else "" }
        override fun interrupt(thread: String, turn: String) { interrupted += thread to turn }
        override fun close() {}
        fun delta(thread: String, text: String) = onNotification("item/agentMessage/delta", JSONObject().put("threadId",thread).put("itemId","same-item-id").put("delta",text))
        fun done(thread: String) = onNotification("turn/completed",JSONObject().put("threadId",thread).put("turn",JSONObject().put("id","turn-$thread").put("status","completed")))
    }

    @Test fun automaticModelRejectionRetriesOnceBeforeAnyProgressAndKeepsAutoPreference() = runBlocking {
        val root=Files.createTempDirectory("youngmini-fallback").toFile();val backend=Backend();val app=AppController(AppPaths(root),backend=backend)
        try {
            withContext(Dispatchers.Main){app.start()};withTimeout(3000){app.state.first {it.ready&&it.models.isNotEmpty()}}
            val id=app.state.value.activeChatId;withContext(Dispatchers.Main){app.send(id,"자동 모델 질문")};withTimeout(3000){while(backend.sent.isEmpty())delay(10)};val thread=backend.sent[0].thread
            backend.onNotification("turn/completed",JSONObject().put("threadId",thread).put("turn",JSONObject().put("id","turn-$thread").put("status","failed").put("error",JSONObject().put("message","The 'gpt-6.2-sol' model is not supported when using Codex with a ChatGPT account."))))
            withTimeout(3000){while(backend.sent.size<2)delay(10)};assertEquals("sol",backend.sent[1].choice.model);assertNull(app.state.value.sessions.single().choice.model);assertEquals(1,app.state.value.sessions.single().messages.size)
            backend.onNotification("turn/completed",JSONObject().put("threadId",thread).put("turn",JSONObject().put("id","turn-$thread-retry").put("status","completed")));withTimeout(3000){app.state.first {it.sessions.single().phase=="done"}};assertEquals(2,backend.sent.size)
        }finally{withContext(Dispatchers.Main){app.close()};root.deleteRecursively()}
    }
    @Test fun attachedDocumentsAreListedForTheReaderAndGeneratedImageEventsBecomeCards() = runBlocking {
        val root=Files.createTempDirectory("youngmini-document-input").toFile();val backend=Backend();val paths=AppPaths(root);val app=AppController(paths,backend=backend)
        try {
            withContext(Dispatchers.Main){app.start()};withTimeout(3000){app.state.first {it.ready}}
            val id=app.state.value.activeChatId;val fileId=UUID.randomUUID().toString();val file=java.io.File(paths.workspace,"uploads/$fileId.txt").apply {parentFile.mkdirs();writeText("한글 첨부 문서")};val artifact=Artifact(fileId,"요약할 문서.txt","uploads/$fileId.txt",file.length(),"document-attachment")
            withContext(Dispatchers.Main){app.attachImages(id,listOf(artifact));app.send(id,"")};withTimeout(3000){while(backend.sent.isEmpty())delay(10)};assertTrue(backend.sent[0].text.contains(artifact.path));assertTrue(backend.sent[0].text.contains("etl_read_file"))
            val image=java.util.Base64.getEncoder().encodeToString(java.io.File("ui/public/robot.png").readBytes());backend.onNotification("item/completed",JSONObject().put("threadId",backend.sent[0].thread).put("item",JSONObject().put("type","imageGeneration").put("status","completed").put("id","pic1").put("result",image)))
            withTimeout(3000){app.state.first {it.sessions.single().artifacts.any {a->a.kind=="generated"}}};assertTrue(app.artifactFile(app.state.value.sessions.single().artifacts.last().id).isFile)
        }finally{withContext(Dispatchers.Main){app.close()};root.deleteRecursively()}
    }
    @Test fun concurrentSessionsKeepTheirOwnEffortMessagesAndCompletionEvenWhenFocusChanges() = runBlocking {
        val root=Files.createTempDirectory("youngmini-multi").toFile(); val backend=Backend(); val app=AppController(AppPaths(root),backend=backend)
        try {
            withContext(Dispatchers.Main) {app.start()}
            withTimeout(3000) {app.state.first {it.ready && it.models.isNotEmpty()}}
            val first=app.state.value.activeChatId
            val second=withContext(Dispatchers.Main) {app.setChoice(first,"sol","xhigh"); app.send(first,"first question"); app.newChat().also {app.setChoice(it,"sol","low"); app.send(it,"second question")}}
            withTimeout(3000) {while(backend.sent.size<2) delay(10)}
            val one=backend.sent.single {it.text=="first question"}; val two=backend.sent.single {it.text=="second question"}
            assertEquals("xhigh",one.choice.effort);assertEquals("low",two.choice.effort);assertNotEquals(one.thread,two.thread)
            withContext(Dispatchers.Main) {app.focus(second)}
            backend.delta(one.thread,"FIRST-ONLY");backend.delta(two.thread,"SECOND-ONLY");backend.done(one.thread)
            withTimeout(3000) {app.state.first {it.sessions.find {c->c.id==first}?.phase=="done"}}
            val a=app.state.value.sessions.single {it.id==first};val b=app.state.value.sessions.single {it.id==second}
            assertEquals("FIRST-ONLY",a.messages.last().text);assertEquals("SECOND-ONLY",b.messages.last().text)
            assertTrue(a.unread);assertTrue(b.busy);assertFalse(first in app.state.value.visibleChatIds)
            withContext(Dispatchers.Main) {app.openChat(first);app.interrupt(second)}
            withTimeout(3000) {while(backend.interrupted.isEmpty()) delay(10)}
            assertEquals(two.thread,backend.interrupted.single().first)
            assertFalse(app.state.value.sessions.single {it.id==first}.unread)
            backend.done(two.thread)
        } finally {withContext(Dispatchers.Main) {app.close()};root.deleteRecursively()}
    }

    @Test fun eightSessionsCanWorkInBackgroundWithoutOpeningExtraChatPanels() = runBlocking {
        val root=Files.createTempDirectory("youngmini-eight").toFile();val backend=Backend();val app=AppController(AppPaths(root),backend=backend)
        try {
            withContext(Dispatchers.Main){app.start()};withTimeout(3000){app.state.first {it.ready}}
            withContext(Dispatchers.Main){repeat(8){n->val id=if(n==0)app.state.value.activeChatId else app.newChat();app.send(id,"question-$n")}}
            withTimeout(3000){while(backend.sent.size<8)delay(10)}
            assertEquals(8,app.state.value.sessions.count {it.busy});assertEquals(1,app.state.value.visibleChatIds.size)
            backend.sent.forEach {backend.delta(it.thread,it.text);backend.done(it.thread)}
            withTimeout(3000){app.state.first {it.sessions.all {c->c.phase=="done"}}}
            assertEquals(8,app.state.value.sessions.map {it.messages.last().text}.toSet().size)
            assertEquals(1,app.state.value.visibleChatIds.size)
        }finally{withContext(Dispatchers.Main){app.close()};root.deleteRecursively()}
    }

    @Test fun unsupportedEffortIsDroppedAndTurnRequestSendsTheSupportedValue() {
        val params=DesktopEngine.turnParams("thread","question",ModelChoice("sol","xhigh"))
        assertEquals("xhigh",params.getString("effort"));assertEquals("sol",params.getString("model"))
        val auto=DesktopEngine.turnParams("thread","question",ModelChoice())
        assertFalse(auto.has("effort"));assertFalse(auto.has("model"))
    }

    @Test fun sessionsHaveNoNumericCapButOnlyOneIsVisible() = runBlocking {
        val root=Files.createTempDirectory("youngmini-unlimited").toFile(); val app=AppController(AppPaths(root),backend=Backend())
        try {
            withContext(Dispatchers.Main) {app.start()}
            withTimeout(3000) {app.state.first {it.ready}}
            val first=app.state.value.activeChatId
            withContext(Dispatchers.Main) {repeat(5) {app.newChat()}}
            val ids=app.state.value.sessions.map {it.id}
            assertEquals(6,ids.size);assertEquals(listOf(ids.last()),app.state.value.visibleChatIds)
            withContext(Dispatchers.Main) {app.openChat(first)}
            withContext(Dispatchers.Main){app.openProject("integrated")}
            assertEquals(listOf(first),app.state.value.visibleChatIds)
            assertEquals(6,app.state.value.sessions.size)

        } finally {withContext(Dispatchers.Main) {app.close()};root.deleteRecursively()}
    }

    @Test fun renamedEmptySessionKeepsItsNameAndDeletedHistoryCannotBeResurrected() = runBlocking {
        val root=Files.createTempDirectory("youngmini-delete").toFile();val paths=AppPaths(root);val backend=Backend();val app=AppController(paths,backend=backend)
        try {
            withContext(Dispatchers.Main){app.start()};withTimeout(3000){app.state.first {it.ready&&it.models.isNotEmpty()}}
            val first=app.state.value.activeChatId
            withContext(Dispatchers.Main){app.renameChat(first,"직접 정한 이름");app.send(first,"첫 질문")}
            withTimeout(3000){while(backend.sent.isEmpty())delay(10)}
            assertEquals("직접 정한 이름",app.state.value.sessions.single().title)
            backend.done(backend.sent.single().thread);withTimeout(3000){app.state.first {it.sessions.single().phase=="done"}}
            val second=withContext(Dispatchers.Main){app.newChat().also {app.setDraft(it,"남길 초안")}}
            withContext(Dispatchers.Main){repeat(50){app.setDraft(first,"저장 중 $it")};app.deleteChat(first)}
            withTimeout(3000){app.state.first {s->s.sessions.none {it.id==first}}}
            assertEquals(second,app.state.value.activeChatId);assertFalse(java.io.File(paths.chats,"$first.json").exists())
            withContext(Dispatchers.Main){app.setDraft(first,"늦은 초안");app.close()}
            val restored=ChatHistory(paths.chats).list();assertFalse(restored.any {it.id==first});assertEquals("남길 초안",restored.single {it.id==second}.draft)
        }finally{withContext(Dispatchers.Main){app.close()};root.deleteRecursively()}
    }
    @Test fun busySessionIsProtectedAndDeletingIdleSessionKeepsOtherWorkRunning() = runBlocking {
        val root=Files.createTempDirectory("youngmini-delete-busy").toFile();val backend=Backend();val app=AppController(AppPaths(root),backend=backend)
        try {
            withContext(Dispatchers.Main){app.start()};withTimeout(3000){app.state.first {it.ready}}
            val first=app.state.value.activeChatId
            val second=withContext(Dispatchers.Main){app.send(first,"실행 중");app.newChat()}
            withTimeout(3000){while(backend.sent.isEmpty())delay(10)}
            withContext(Dispatchers.Main){app.renameChat(first,"바꾸면 안 됨");app.deleteChat(first);app.deleteChat(second)}
            withTimeout(3000){app.state.first {s->s.sessions.none {it.id==second}}}
            assertEquals(first,app.state.value.activeChatId);assertTrue(app.state.value.sessions.single().busy);assertEquals("실행 중",app.state.value.sessions.single().title);assertTrue(backend.interrupted.isEmpty())
            backend.done(backend.sent.single().thread);withTimeout(3000){app.state.first {it.sessions.single().phase=="done"}}
            withContext(Dispatchers.Main){app.deleteChat(first)}
            withTimeout(3000){app.state.first {s->s.sessions.none {it.id==first}}}
            assertEquals(1,app.state.value.sessions.size);assertEquals("integrated",app.state.value.activeProjectId);assertEquals("새 대화",app.state.value.sessions.single().title)
        }finally{withContext(Dispatchers.Main){app.close()};root.deleteRecursively()}
    }
    @Test fun onlyTheExplicit2026SecondSemesterIsShown() {
        for (name in listOf("2026년 2학기","2026학년도 2학기","2026-2","26-2","2026 Fall","[2026-2] 전기에너지변환")) assertTrue(name,TermScope.matches(name))
        for (name in listOf("2026년 1학기","2025년 2학기","2027년 2학기","2026년 동계학기","2026-20","학기 없음")) assertFalse(name,TermScope.matches(name))
    }

    @Test fun persistedModelEffortAndAttachmentsSurviveRestartWithoutACourseBinding() {
        val root=Files.createTempDirectory("youngmini-session-save").toFile()
        try {
            val session=ChatSession(choice=ModelChoice("sol","high"),files=listOf(Downloaded(20,"notes.pdf","etl-files/20-notes.pdf",100)),draft="continue here")
            ChatHistory(root).save(session.saved())
            val restored=ChatSession.from(ChatHistory(root).list().single())
            assertEquals(session.choice,restored.choice);assertEquals(session.files,restored.files);assertEquals(session.draft,restored.draft)
            assertFalse(JSONObject(java.io.File(root,"${session.id}.json").readText()).has("course_id"))
        } finally {root.deleteRecursively()}
    }
}
