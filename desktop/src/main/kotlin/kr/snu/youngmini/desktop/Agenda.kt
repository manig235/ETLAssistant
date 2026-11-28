package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The inbox, delivery queue and watch baseline commit together, before any native toast. */
class Agenda(private val root:File, private val clock:()->Long=System::currentTimeMillis) {
    private val file=File(root,"agenda.json")
    private var data=runCatching {JSONObject(file.readText())}.getOrDefault(JSONObject())
    var onChange:()->Unit={}
    init { for(k in listOf("personal","academic","notices"))if(!data.has(k))data.put(k,JSONArray());for(k in listOf("watch","fired","settings"))if(!data.has(k))data.put(k,JSONObject()) }
    @Synchronized fun snapshot():JSONObject=JSONObject(data.toString()).put("settings",settings())
    @Synchronized fun settings():JSONObject=JSONObject().put("onboarded",false).put("deadlines",true).put("announcements",true).put("grades",true).put("personal",true).put("completion",true).also {out->val s=data.getJSONObject("settings");s.keys().forEach {out.put(it,s.get(it))}}
    @Synchronized fun setSettings(o:JSONObject) {val s=data.getJSONObject("settings");for(k in listOf("onboarded","deadlines","announcements","grades","personal","completion"))if(o.has(k))s.put(k,o.getBoolean(k));commit()}
    @Synchronized fun savePersonal(o:JSONObject) {
        val title=o.getString("title").trim();require(title.isNotEmpty()&&title.length<=120)
        val due=o.getLong("due");require(due>0 && due<253402300799000L)
        val id=(o.opt("id") as? String)?.also {require(Regex("[a-f0-9-]{36}").matches(it))}?:UUID.randomUUID().toString()
        val offsets=o.optJSONArray("reminders")?.longs().orEmpty().distinct().filter {it in 0..10080}.ifEmpty {listOf(10)}
        val old=rows("personal").find {it.optString("id")==id}
        val item=JSONObject().put("id",id).put("title",title).put("due",due).put("kind",if(o.optString("kind")=="exam")"exam" else "task").put("notes",o.optString("notes").take(4000)).put("reminders",JSONArray(offsets)).put("done",o.optBoolean("done",old?.optBoolean("done")?:false))
        data.put("personal",JSONArray(rows("personal").filterNot {it.optString("id")==id}+item));commit();tick()
    }
    @Synchronized fun deletePersonal(id:String) {data.put("personal",JSONArray(rows("personal").filterNot {it.optString("id")==id}));commit()}
    @Synchronized fun completePersonal(id:String,done:Boolean) {rows("personal").find {it.optString("id")==id}?.put("done",done);commit()}
    @Synchronized fun notice(kind:String,title:String,body:String,chatId:String?=null,url:String?=null,hiddenOnly:Boolean=false) {
        enqueue(kind,title,body,chatId,url,hiddenOnly);commit()
    }
    private fun enqueue(kind:String,title:String,body:String,chatId:String?=null,url:String?=null,hiddenOnly:Boolean=false) {
        data.getJSONArray("notices").put(JSONObject().put("id",UUID.randomUUID().toString()).put("kind",kind).put("title",title.take(160)).put("body",body.take(1200)).put("at",clock()).put("chatId",chatId?:JSONObject.NULL).put("url",url?:JSONObject.NULL).put("hiddenOnly",hiddenOnly).put("delivered",false).put("read",false))
    }
    @Synchronized fun acknowledge(id:String,read:Boolean=false) {rows("notices").find {it.optString("id")==id}?.apply {put("delivered",true);if(read)put("read",true)};commit()}
    @Synchronized fun readAll() {rows("notices").forEach {it.put("read",true).put("delivered",true)};commit()}
    @Synchronized fun clearAcademic() {data.put("academic",JSONArray());data.put("watch",JSONObject());data.remove("lastSync");data.remove("syncError");commit()}
    @Synchronized fun syncError(message:String) {data.put("syncError",message);commit()}
    /** Called once per successfully fetched course. Failed courses retain their last baseline. */
    @Synchronized fun ingest(course:Course,assignments:List<JSONObject>?,announcements:List<JSONObject>?) {
        val now=clock();val all=data.getJSONObject("watch");val key=course.id.toString();val prev=all.optJSONObject(key)?:JSONObject();val next=JSONObject(prev.toString())
        val prefs=settings()
        if(assignments!=null) {
            val scores=prev.optJSONObject("scores")?:JSONObject();val updated=JSONObject(scores.toString())
            val current=assignments.map {a->
                val id=a.getLong("id");val submission=a.optJSONObject("submission");val score=(submission?.opt("score") as? Number)?.toDouble();val old=(scores.opt(id.toString()) as? Number)?.toDouble()
                if(prev.optBoolean("assignmentsReady")&&scores.has(id.toString())&&score!=null&&old!=score&&prefs.optBoolean("grades"))enqueue("grades","성적 · ${course.name}","${a.optString("name")} · ${score.toString().removeSuffix(".0")}" ,url="https://myetl.snu.ac.kr/courses/${course.id}/assignments/$id")
                updated.put(id.toString(),score?:JSONObject.NULL)
                val due=runCatching {Instant.parse(a.optString("due_at")).toEpochMilli()}.getOrNull()
                JSONObject().put("id","${course.id}:$id").put("title",a.optString("name")).put("course",course.name).put("courseId",course.id).put("due",due?:JSONObject.NULL).put("kind",if(a.optString("name").contains(Regex("시험|고사|exam|quiz",RegexOption.IGNORE_CASE)))"exam" else "task").put("done",submission?.optString("workflow_state") in listOf("submitted","graded","pending_review")).put("score",score?:JSONObject.NULL).put("fetchedAt",now).put("url","https://myetl.snu.ac.kr/courses/${course.id}/assignments/$id")
            }
            data.put("academic",JSONArray(rows("academic").filterNot {it.optLong("courseId")==course.id}+current))
            next.put("scores",updated).put("assignmentsReady",true)
        }
        if(announcements!=null) {
            val ids=prev.optJSONArray("announcements")?.longs().orEmpty().toSet()
            for(a in announcements)if(prev.optBoolean("announcementsReady")&&a.optLong("id") !in ids&&prefs.optBoolean("announcements")) {
                val posted=runCatching {Instant.parse(a.optString("posted_at")).toEpochMilli()}.getOrDefault(now)
                if(now-posted<=14L*86400000)enqueue("announcements","공지 · ${course.name}",a.optString("title"),url="https://myetl.snu.ac.kr/courses/${course.id}/discussion_topics/${a.optLong("id")}")
            }
            next.put("announcements",JSONArray((ids+announcements.map {it.optLong("id")}).toList())).put("announcementsReady",true)
        }
        all.put(key,next);data.put("lastSync",now);data.remove("syncError");commit();tick()
    }
    @Synchronized fun tick() {
        val now=clock();val fired=data.getJSONObject("fired");val prefs=settings();var changed=false
        val candidates=mutableListOf<JSONObject>()
        for(source in listOf("personal","academic"))for(item in rows(source)) {
            if(item.optBoolean("done")||item.isNull("due"))continue
            if(source=="academic"&&now-item.optLong("fetchedAt")>3L*86400000)continue
            val due=item.optLong("due");val offsets=if(source=="personal")item.optJSONArray("reminders").longs() else listOf(4320L,1440L,180L,10L)
            val entered=offsets.filter {now>=due-it*60000}.sorted()
            if(entered.isEmpty())continue
            val keys=entered.map {"$source:${item.getString("id")}:$due:$it"}
            if(keys.all {fired.has(it)})continue
            keys.forEach {fired.put(it,now)};changed=true
            if(!prefs.optBoolean(if(source=="personal")"personal" else "deadlines"))continue
            if(source=="academic"&&now-due>86400000)continue
            val past=now>=due
            val time=DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(due))
            candidates+=JSONObject().put("title",if(past)"놓친 일정 확인" else if(item.optString("kind")=="exam")"시험 일정" else "마감 알림").put("body","${item.optString("course").let {if(it.isBlank())"" else "$it · "}}${item.optString("title")} · $time").put("kind",if(source=="personal")"personal" else "deadlines").put("url",item.opt("url")?:JSONObject.NULL)
        }
        if(candidates.size>3)enqueue("deadlines","확인할 일정 ${candidates.size}개",candidates.joinToString("\n"){it.getString("body")})
        else candidates.forEach {enqueue(it.getString("kind"),it.getString("title"),it.getString("body"),url=it.opt("url") as? String)}
        if(changed)commit()
    }
    private fun rows(key:String):List<JSONObject> {val a=data.getJSONArray(key);return (0 until a.length()).map {a.getJSONObject(it)}}
    private fun commit() {
        // Keep unread/pending entries; bound old delivered history only.
        val notices=rows("notices");val keep=notices.filter {!it.optBoolean("read")||!it.optBoolean("delivered")}+notices.filter {it.optBoolean("read")&&it.optBoolean("delivered")}.takeLast(200)
        data.put("notices",JSONArray(keep.distinctBy {it.getString("id")}))
        atomicWrite(file,data.toString().toByteArray());onChange()
    }
}
