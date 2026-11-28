package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlLogic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SchoolFeaturesTest {
    @Test fun manualTimetableChangesScoresAndSettingsPersistWithoutAccounts() {
        val root=Files.createTempDirectory("school-features").toFile();val paths=AppPaths(root)
        fun open()=SchoolFeatures(paths,Materials(paths,SecretVault(paths.secrets)),Agenda(root))
        try {
            val school=open();val today=LocalDate.now(EtlLogic.KST)
            val id=school.saveClass(JSONObject().put("name","미적분학").put("professor","담당 교수").put("credits",3).put("meetings",JSONArray().put(JSONObject().put("day","월화수목금토일"[today.dayOfWeek.value-1].toString()).put("start","09:00").put("end","10:15").put("room","301"))))
            assertTrue(id<0);assertEquals(1,school.snapshot().getJSONArray("todayClasses").length())
            school.saveChange(JSONObject().put("course","미적분학").put("course_id",id).put("kind","휴강").put("date",today.toString()))
            assertEquals(0,school.snapshot().getJSONArray("todayClasses").length());assertEquals(1,open().snapshot().getJSONArray("cancelledClasses").length())
            school.saveScore(JSONObject("""{"title":"중간고사","course":"미적분학","stats":{"min":0,"max":100,"mean":60,"q1":40,"median":60,"q3":80},"my_score":90,"points_possible":100}"""))
            assertEquals(1,open().scoreRows().length());assertFalse(school.scoreRows().getJSONObject(0).isNull("position"))
            school.settings(JSONObject().put("quiet",true).put("theme","dark").put("retentionDays",7));school.courseMode(10,"hide")
            val restored=open();assertEquals("dark",restored.settings().getString("theme"));assertEquals("hide",restored.courseMode(10));assertTrue(10L in restored.settings().getJSONArray("hideTimetable").longs())
            assertThrows(Exception::class.java){school.saveScore(JSONObject("""{"title":"오류","stats":{"min":90,"max":10}}"""))}
        }finally{root.deleteRecursively()}
    }
    @Test fun repeatedOccurrencesAreIndependentAndCalendarRoundTripsKoreanAndExclusions() {
        val root=Files.createTempDirectory("school-repeat").toFile();val now=1800000000000L;val due=now+86400000
        try {
            val agenda=Agenda(root){now};agenda.savePersonal(JSONObject().put("title","한글, 복습; 일정").put("due",due).put("notes","첫째\n둘째").put("repeat",JSONObject().put("freq","daily").put("count",3)))
            val occurrences=agenda.snapshot().getJSONArray("personal");assertEquals(3,occurrences.length());val one=occurrences.getJSONObject(0)
            agenda.completePersonal(one.getString("id"),true,one.getString("occurrence"));assertTrue(agenda.snapshot().getJSONArray("personal").getJSONObject(0).getBoolean("done"));assertFalse(agenda.snapshot().getJSONArray("personal").getJSONObject(1).getBoolean("done"))
            val two=agenda.snapshot().getJSONArray("personal").getJSONObject(1);agenda.deletePersonal(two.getString("id"),two.getString("occurrence"));assertEquals(2,Agenda(root){now}.snapshot().getJSONArray("personal").length())
            val text=CalendarFiles.export(agenda.snapshot().getJSONArray("personalSeries"));val parsed=CalendarFiles.read(text);assertEquals(0,parsed.getJSONArray("errors").length());val event=parsed.getJSONArray("events").getJSONObject(0);assertEquals("한글, 복습; 일정",event.getString("title"));assertEquals(due/1000,event.getLong("due")/1000);assertEquals(2,event.getJSONArray("excluded_dates").length());assertEquals(3,event.getJSONObject("repeat").getInt("count"))
            for(line in text.split("\r\n"))assertTrue(line.toByteArray(Charsets.UTF_8).size<=75)
        }finally{root.deleteRecursively()}
    }
    @Test fun notificationBaselinesMuteAndSubmissionChangesArePersistent() {
        val root=Files.createTempDirectory("school-alerts").toFile();var now=1800000000000L
        try {
            val agenda=Agenda(root){now};val course=Course(10,"미적분","2026-2",true);var muted=false;agenda.mutedCourse={muted};agenda.hiddenCourse={muted}
            fun assignment(due:Long=now+30*86400000,submitted:Boolean=false)=JSONObject().put("id",5).put("name","과제 1").put("due_at",java.time.Instant.ofEpochMilli(due).toString()).put("submission_types",JSONArray(listOf("online_text_entry"))).put("submission",JSONObject().put("workflow_state",if(submitted)"submitted"else "unsubmitted").put("submission_type","online_text_entry").put("submitted_at",if(submitted)java.time.Instant.ofEpochMilli(now).toString()else JSONObject.NULL))
            agenda.ingest(course,listOf(assignment()),emptyList());agenda.ingestFiles(course,listOf(JSONObject().put("id",1).put("updated_at","a")));assertEquals(0,agenda.snapshot().getJSONArray("notices").length())
            now+=1000;agenda.ingest(course,listOf(assignment(now+20*86400000,true)),null);val notices=agenda.snapshot().getJSONArray("notices");assertTrue(notices.toString().contains("과제 변경"));assertTrue(notices.toString().contains("제출했어요"))
            val n=notices.length();agenda.ingestFiles(course,listOf(JSONObject().put("id",1).put("updated_at","a"),JSONObject().put("id",2).put("display_name","추가 자료").put("updated_at","b")));assertEquals(n+1,agenda.snapshot().getJSONArray("notices").length())
            muted=true;agenda.ingest(course,listOf(assignment(now+10*86400000)),null);assertEquals(0,agenda.snapshot().getJSONArray("academic").length());muted=false;agenda.ingest(course,listOf(assignment(now+10*86400000)),null);assertEquals(n+1,agenda.snapshot().getJSONArray("notices").length())
        }finally{root.deleteRecursively()}
    }
    @Test fun localApprovalIsOwnedCancelableAndChecksForStaleState() {
        val approvals=LocalApprovals();val worker=Executors.newSingleThreadExecutor();var changed=false;var stale=false
        try {
            val pending=worker.submit<Any>{approvals.execute("chat","일정 변경","미리보기",{check(!stale)}){changed=true;"done"}}
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(approvals.snapshot().length()==0&&System.nanoTime()<until)Thread.yield();val id=approvals.snapshot().getJSONObject(0).getString("id")
            assertThrows(Exception::class.java){approvals.decide(id,true,"other")};assertFalse(changed);stale=true;approvals.decide(id,true,"chat");assertThrows(Exception::class.java){pending.get(2,TimeUnit.SECONDS)};assertFalse(changed)
            val canceled=worker.submit<Any>{approvals.execute("chat","추가","내용",{}){changed=true}}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(approvals.snapshot().length()==0&&System.nanoTime()<deadline)Thread.yield();approvals.cancel("chat");assertThrows(Exception::class.java){canceled.get(2,TimeUnit.SECONDS)};assertFalse(changed)
        }finally{approvals.cancel();worker.shutdownNow()}
    }
    @Test fun expiredApprovalNeverWritesAndSuccessfulApprovalWritesOnlyOnce() {
        val approvals=LocalApprovals(20);var count=0
        assertThrows(Exception::class.java){approvals.execute("chat","변경","내용",{}){count++}};assertEquals(0,count);assertEquals(0,approvals.snapshot().length())
        val worker=Executors.newSingleThreadExecutor();val normal=LocalApprovals()
        try{val pending=worker.submit<Any>{normal.execute("chat","변경","내용",{}){count++;"ok"}};val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(normal.snapshot().length()==0&&System.nanoTime()<until)Thread.yield();val id=normal.snapshot().getJSONObject(0).getString("id");normal.decide(id,true,"chat");normal.decide(id,true,"chat");assertEquals("ok",pending.get(2,TimeUnit.SECONDS));assertEquals(1,count)}finally{normal.cancel();worker.shutdownNow()}
    }
    @Test fun cleanupProtectsEveryReferencedFileAndExternalCopies() {
        val root=Files.createTempDirectory("school-retention").toFile();val paths=AppPaths(root);val generated=java.io.File(paths.workspace,"generated").apply {mkdirs()}
        try {
            val keep=java.io.File(generated,"keep.txt").apply {writeText("keep");setLastModified(1)};val old=java.io.File(generated,"old.txt").apply {writeText("old");setLastModified(1)};val external=java.io.File(root,"my-copy.txt").apply {writeText("copy");setLastModified(1)}
            val result=WorkFiles.cleanup(paths.workspace,setOf("generated/keep.txt"),2);assertEquals(1,result.getInt("count"));assertTrue(keep.exists());assertFalse(old.exists());assertTrue(external.exists())
        }finally{root.deleteRecursively()}
    }
}
