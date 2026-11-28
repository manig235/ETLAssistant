package kr.snu.youngmini.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProjectsAndAgendaTest {
    @Test fun silentBaselineZeroScoreChangesFailedCoursesAndDeliverySurviveRestart() {
        val root=Files.createTempDirectory("youngmini-watch").toFile();var now=1800000000000L
        try {
            val agenda=Agenda(root){now};val course=Course(10,"전기에너지변환","2026-2",true)
            fun a(score:Any)=JSONObject().put("id",20).put("name","HW1").put("submission",JSONObject().put("score",score).put("workflow_state","graded"))
            fun notices(store:Agenda)=store.snapshot().getJSONArray("notices")
            agenda.ingest(course,listOf(a(JSONObject.NULL)),listOf(JSONObject().put("id",1).put("title","기존 공지")))
            assertEquals(0,notices(agenda).length())
            now+=1000;agenda.ingest(course,listOf(a(0)),null)
            assertEquals(1,notices(agenda).length());assertTrue(notices(agenda).getJSONObject(0).getString("body").endsWith("· 0"))
            agenda.ingest(course,null,null) // An API failure must not erase the score baseline.
            val restarted=Agenda(root){now};assertFalse(notices(restarted).getJSONObject(0).getBoolean("delivered"))
            restarted.ingest(course,listOf(a(0)),emptyList());assertEquals(1,notices(restarted).length())
            restarted.ingest(course,listOf(a(95)),listOf(JSONObject().put("id",2).put("title","새 공지")))
            assertEquals(3,notices(restarted).length())
            val id=notices(restarted).getJSONObject(0).getString("id");restarted.acknowledge(id)
            assertTrue(Agenda(root){now}.snapshot().getJSONArray("notices").getJSONObject(0).getBoolean("delivered"))
        } finally{root.deleteRecursively()}
    }
    @Test fun personalRemindersArePersistentDeduplicatedEditableAndCompletionCancelsThem() {
        val root=Files.createTempDirectory("youngmini-personal").toFile();var now=1800000000000L
        try {
            var agenda=Agenda(root){now}
            agenda.savePersonal(JSONObject().put("title","기말고사").put("due",now+3600000).put("kind","exam").put("reminders",JSONArray(listOf(10,0))))
            val item=agenda.snapshot().getJSONArray("personal").getJSONObject(0)
            now+=51*60000;agenda.tick();assertEquals(1,agenda.snapshot().getJSONArray("notices").length())
            agenda=Agenda(root){now};agenda.tick();assertEquals(1,agenda.snapshot().getJSONArray("notices").length())
            agenda.completePersonal(item.getString("id"),true);now+=10*60000;agenda.tick();assertEquals(1,agenda.snapshot().getJSONArray("notices").length())
            item.put("title","시험 변경").put("due",now+60000).put("done",false);agenda.savePersonal(item)
            assertEquals(1,agenda.snapshot().getJSONArray("personal").length());assertEquals(2,agenda.snapshot().getJSONArray("notices").length())
            agenda.deletePersonal(item.getString("id"));assertEquals(0,Agenda(root).snapshot().getJSONArray("personal").length())
        }finally{root.deleteRecursively()}
    }
    @Test fun wakeAfterSeveralThresholdsFiresOnlyOneCatchUpAndMutingAdvancesKeys() {
        val root=Files.createTempDirectory("youngmini-catchup").toFile();var now=1800000000000L
        try {
            val agenda=Agenda(root){now};agenda.savePersonal(JSONObject().put("title","마감").put("due",now+86400000).put("reminders",JSONArray(listOf(1440,180,10,0))))
            assertEquals(1,agenda.snapshot().getJSONArray("notices").length())
            agenda.setSettings(JSONObject().put("personal",false));now+=86400000+60000;agenda.tick()
            agenda.setSettings(JSONObject().put("personal",true));agenda.tick();assertEquals(1,agenda.snapshot().getJSONArray("notices").length())
        }finally{root.deleteRecursively()}
    }
    @Test fun contextsStayWithOwningSessionAndRevokedContextCannotPublish() {
        val contexts=ToolContexts();val p=Project("custom","시험준비","custom",listOf(10,11),"풀이 자세히")
        val received=mutableListOf<String>();contexts.onArtifact={id,_->received+=id}
        val a=contexts.register("one",p);val b=contexts.register("two",Project.integrated)
        val file=Artifact(name="문제.pdf",path="generated/test.pdf",bytes=100,kind="generated")
        contexts.emit(a,file);contexts.emit(b,file);assertEquals(listOf("one","two"),received)
        contexts.activate("one",false);assertThrows(Exception::class.java){contexts.resolve(a.key)}
        contexts.register("one",p);contexts.emit(a,file);assertEquals(2,received.size)
        assertTrue(contexts.instructions(b).contains("2026년 2학기"));assertTrue(contexts.instructions(a).contains("풀이 자세히"))
    }
    @Test fun projectsAndIndependentChatHistoriesPersistAndReconcileOffline() {
        val root=Files.createTempDirectory("youngmini-projects").toFile()
        try {
            val store=ProjectStore(root);val custom=store.custom("기말 대비",listOf(10,11,10),"상세히")
            val projects=store.reconcile(store.load()+custom,listOf(Course(10,"에너지","2026-2",true)))
            store.save(projects);assertEquals(listOf(10L,11L),store.load().find{it.id==custom.id}!!.courseIds)
            assertEquals(projects,store.reconcile(store.load(),emptyList()))
            val history=ChatHistory(java.io.File(root,"chats"));val one=ChatSession(projectId=custom.id,messages=listOf(ChatMessage("m","user","첫째")));val two=ChatSession(projectId=custom.id,messages=listOf(ChatMessage("m","user","둘째")))
            history.save(one.saved());history.save(two.saved());assertEquals(setOf("첫째","둘째"),history.list().map{it.messages.single().text}.toSet())
            assertTrue(history.list().all {it.projectId==custom.id})
        }finally{root.deleteRecursively()}
    }
}
