package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.*
import kr.snu.youngmini.portable.MyDeadline
import kr.snu.youngmini.portable.MyDeadlines
import kr.snu.youngmini.portable.MyRepeat
import kr.snu.youngmini.portable.MyKind
import java.time.LocalDate
import java.time.DayOfWeek
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
    var schoolSettings:()->JSONObject = {JSONObject()}
    var mutedCourse:(Long)->Boolean={false}
    var hiddenCourse:(Long)->Boolean={false}
    var deadlineWindows:()->List<Long> = {listOf(4320,1440,180,10)}
    init { for(k in listOf("personal","academic","announcements","notices"))if(!data.has(k))data.put(k,JSONArray());for(k in listOf("watch","fired","settings"))if(!data.has(k))data.put(k,JSONObject()) }
    @Synchronized fun snapshot():JSONObject {
        val out=JSONObject(data.toString()).put("settings",settings()).put("personalSeries",JSONArray(rows("personal"))).put("personal",JSONArray(personalRows(clock())))
        for(key in listOf("academic","announcements"))out.put(key,JSONArray(rows(key).filterNot {hiddenCourse(it.optLong("courseId"))}))
        out.put("notices",JSONArray(rows("notices").filterNot {row->Regex("/courses/(\\d+)").find(row.optString("url"))?.groupValues?.get(1)?.toLongOrNull()?.let(hiddenCourse)?:false}))
        return out
    }
    private fun deadline(o:JSONObject):MyDeadline {
        val uuid=UUID.fromString(o.getString("id"));val due=Instant.ofEpochMilli(o.getLong("due"));val r=o.optJSONObject("repeat")
        val repeat=r?.optString("freq")?.let(MyRepeat.Freq::parse)?.let {freq->MyRepeat(freq,r.optJSONArray("weekdays").longs().map {DayOfWeek.of(it.toInt())}.toSet(),r.optString("until").takeIf {it.isNotBlank()}?.let(LocalDate::parse),r.optInt("count").takeIf {it>0})}
        fun dates(key:String)=o.optJSONArray(key)?.let {a->(0 until a.length()).map {LocalDate.parse(a.getString(it))}.toSet()}.orEmpty()
        return MyDeadline((uuid.mostSignificantBits xor uuid.leastSignificantBits) and Long.MAX_VALUE,o.getString("title"),due,o.optString("notes"),o.optBoolean("done"),o.optJSONArray("reminders").longs().map {due.minusSeconds(it*60)},repeat,dates("doneDates"),dates("skipDates"),kind=if(o.optString("kind")=="exam")MyKind.EXAM else MyKind.DEADLINE,end=(o.opt("end") as? Number)?.toLong()?.let(Instant::ofEpochMilli),location=o.optString("location"),course=o.optString("course"),supplies=o.optString("supplies"))
    }
    private fun personalRows(now:Long):List<JSONObject> = rows("personal").flatMap {o->
        val d=deadline(o)
        MyDeadlines.agendaItems(listOf(d),Instant.ofEpochMilli(now),Instant.ofEpochMilli(now).plusSeconds(60*86400L)).map {item->JSONObject(o.toString()).put("due",item.due.toEpochMilli()).put("seriesDue",d.due.toEpochMilli()).put("done",item.done).put("occurrence",item.occurrence?.toString()?:JSONObject.NULL).put("repeatLabel",MyDeadlines.repeatLabel(d))}
    }
    @Synchronized fun settings():JSONObject=JSONObject().put("onboarded",false).put("deadlines",true).put("announcements",true).put("grades",true).put("personal",true).put("completion",true).also {out->val s=data.getJSONObject("settings");s.keys().forEach {out.put(it,s.get(it))}}
    @Synchronized fun setSettings(o:JSONObject) {val s=data.getJSONObject("settings");for(k in listOf("onboarded","deadlines","announcements","grades","personal","completion"))if(o.has(k))s.put(k,o.getBoolean(k));commit()}
    @Synchronized fun savePersonal(o:JSONObject) {
        val title=o.getString("title").trim();require(title.isNotEmpty()&&title.length<=120)
        val due=o.getLong("due");require(due>0 && due<253402300799000L)
        val id=(o.opt("id") as? String)?.also {require(Regex("[a-f0-9-]{36}").matches(it))}?:UUID.randomUUID().toString()
        val offsets=o.optJSONArray("reminders")?.longs().orEmpty().distinct().filter {it in 0..10080}.ifEmpty {listOf(10)}
        val old=rows("personal").find {it.optString("id")==id}
        val item=JSONObject().put("id",id).put("title",title).put("due",due).put("kind",if(o.optString("kind")=="exam")"exam" else "task").put("notes",o.optString("notes").take(4000)).put("reminders",JSONArray(offsets)).put("done",o.optBoolean("done",old?.optBoolean("done")?:false))
        for(key in listOf("end","location","course","supplies","repeat"))if(o.has(key))item.put(key,o.get(key))else if(old?.has(key)==true)item.put(key,old.get(key))
        for(key in listOf("location","course","supplies"))if(item.optString(key).length>500)throw IllegalArgumentException("일정 정보가 너무 길어요")
        for(key in listOf("doneDates","skipDates"))item.put(key,old?.optJSONArray(key)?:JSONArray())
        val parsed=deadline(item);MyDeadlines.validateRepeat(parsed)?.let {throw EtlException(it)};MyDeadlines.validateExam(parsed)?.let {throw EtlException(it)}
        data.put("personal",JSONArray(rows("personal").filterNot {it.optString("id")==id}+item));commit();tick()
    }
    @Synchronized fun deletePersonal(id:String,occurrence:String?=null) {
        if(occurrence!=null){LocalDate.parse(occurrence);rows("personal").find {it.optString("id")==id}?.let {it.put("skipDates",JSONArray((it.optJSONArray("skipDates")?.let {a->(0 until a.length()).map {a.getString(it)}}.orEmpty()+occurrence).distinct()))}}
        else data.put("personal",JSONArray(rows("personal").filterNot {it.optString("id")==id}))
        commit()
    }
    @Synchronized fun completePersonal(id:String,done:Boolean,occurrence:String?=null) {
        rows("personal").find {it.optString("id")==id}?.let {item->
            if(item.optJSONObject("repeat")!=null){val date=occurrence?:personalRows(clock()).firstOrNull {it.optString("id")==id&&!it.optBoolean("done")}?.optString("occurrence");if(!date.isNullOrBlank()){LocalDate.parse(date);val old=item.optJSONArray("doneDates")?.let {a->(0 until a.length()).map {a.getString(it)}}.orEmpty();item.put("doneDates",JSONArray(if(done)(old+date).distinct()else old-date))}}
            else item.put("done",done)
        };commit()
    }
    @Synchronized fun notice(kind:String,title:String,body:String,chatId:String?=null,url:String?=null,hiddenOnly:Boolean=false) {
        enqueue(kind,title,body,chatId,url,hiddenOnly);commit()
    }
    @Synchronized fun windowsUpdate(version:String,url:String) {
        require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(version))
        require(url=="https://github.com/manig235/ETLAssistant/releases/download/windows-v$version/youngmin-windows-x64-v$version.zip")
        val seen=(data.optJSONArray("notifiedWindowsVersions")?.let {a->(0 until a.length()).map {a.getString(it)}}.orEmpty()+data.optString("notifiedWindowsVersion")).filter {it.isNotBlank()}
        if(version in seen)return
        data.put("notifiedWindowsVersion",version).put("notifiedWindowsVersions",JSONArray((seen+version).distinct().takeLast(100)))
        enqueue("updates","새 Windows 버전이 있어요","영민이 Windows $version · 눌러서 업데이트를 확인해 주세요.",url=url)
        data.getJSONArray("notices").getJSONObject(data.getJSONArray("notices").length()-1).put("updateVersion",version)
        commit()
    }
    private fun enqueue(kind:String,title:String,body:String,chatId:String?=null,url:String?=null,hiddenOnly:Boolean=false) {
        data.getJSONArray("notices").put(JSONObject().put("id",UUID.randomUUID().toString()).put("kind",kind).put("title",title.take(160)).put("body",body.take(1200)).put("at",clock()).put("chatId",chatId?:JSONObject.NULL).put("url",url?:JSONObject.NULL).put("hiddenOnly",hiddenOnly).put("delivered",false).put("read",false))
    }
    @Synchronized fun acknowledge(id:String,read:Boolean=false) {rows("notices").find {it.optString("id")==id}?.apply {put("delivered",true);if(read){put("read",true);val url=optString("url");rows("announcements").filter {it.optString("url")==url}.forEach {it.put("read",true)}}};commit()}
    @Synchronized fun readAnnouncement(id:String) {require(Regex("[0-9]+:[0-9]+").matches(id));rows("announcements").find {it.optString("id")==id}?.apply {put("read",true);val url=getString("url");rows("notices").filter {it.optString("url")==url}.forEach {it.put("read",true).put("delivered",true)}};commit()}
    @Synchronized fun readAll() {rows("notices").forEach {it.put("read",true).put("delivered",true)};rows("announcements").forEach {it.put("read",true)};commit()}
    @Synchronized fun removeChat(id:String) {data.put("notices",JSONArray(rows("notices").filterNot {it.optString("chatId")==id}));commit()}
    @Synchronized fun clearAcademic() {data.put("academic",JSONArray());data.put("announcements",JSONArray());data.put("watch",JSONObject());data.remove("lastSync");data.remove("syncError");commit()}
    @Synchronized fun syncError(message:String) {data.put("syncError",message);commit()}
    /** Called once per successfully fetched course. Failed courses retain their last baseline. */
    @Synchronized fun ingest(course:Course,assignments:List<JSONObject>?,announcements:List<JSONObject>?) {
        val now=clock();val all=data.getJSONObject("watch");val key=course.id.toString();val prev=all.optJSONObject(key)?:JSONObject();val next=JSONObject(prev.toString())
        val prefs=settings();val school=schoolSettings()
        if(assignments!=null) {
            val scores=prev.optJSONObject("scores")?:JSONObject();val updated=JSONObject(scores.toString())
            val metadata=JSONObject();val prior=prev.optJSONObject("metadata")?:JSONObject()
            val current=assignments.map {a->
                val id=a.getLong("id");val submission=a.optJSONObject("submission");val score=(submission?.opt("score") as? Number)?.toDouble();val old=(scores.opt(id.toString()) as? Number)?.toDouble()
                if(prev.optBoolean("assignmentsReady")&&scores.has(id.toString())&&score!=null&&old!=score&&prefs.optBoolean("grades")&&!mutedCourse(course.id))enqueue("grades","성적 · ${course.name}","${a.optString("name")} · ${score.toString().removeSuffix(".0")}" ,url="https://myetl.snu.ac.kr/courses/${course.id}/assignments/$id")
                updated.put(id.toString(),score?:JSONObject.NULL)
                val due=runCatching {Instant.parse(a.optString("due_at")).toEpochMilli()}.getOrNull()
                val before=prior.optJSONObject(id.toString());val url="https://myetl.snu.ac.kr/courses/${course.id}/assignments/$id"
                val fingerprint=JSONObject().put("due",due?:JSONObject.NULL).put("name",a.optString("name")).put("submittedAt",submission?.optString("submitted_at")?:"").put("attempt",submission?.optInt("attempt")?:0)
                metadata.put(id.toString(),fingerprint)
                if(prev.optBoolean("metadataReady")&&!mutedCourse(course.id)) {
                    if(school.optBoolean("changes",true)&&(before==null||before.opt("due").toString()!=fingerprint.opt("due").toString()||before.optString("name")!=a.optString("name")))enqueue("changes",if(before==null)"새 과제 · ${course.name}" else "과제 변경 · ${course.name}",a.optString("name")+(due?.let {" · "+EtlLogic.fmtKst(Instant.ofEpochMilli(it))}?:" · 마감 미정"),url=url)
                    if(before!=null&&(before.optString("submittedAt")!=fingerprint.optString("submittedAt")||before.optInt("attempt")!=fingerprint.optInt("attempt"))) {
                        kr.snu.youngmini.portable.SubmissionCheck.evaluate(a,submission,Instant.ofEpochMilli(now))?.let {check->
                            if(check.problems.isNotEmpty()&&school.optBoolean("submissions",true))enqueue("submissions","제출 확인 · ${course.name}",a.optString("name")+" · "+check.problems.joinToString(" · "),url=url)
                            else if(check.attachmentsKnown&&school.optBoolean("submitOk",true))enqueue("submitOk","제출했어요 · ${course.name}",a.optString("name")+(check.files?.let {" · 파일 ${it}개"}?:"")+(if(check.late)" · 지각 제출" else " · 메타데이터 이상 없음"),url=url)
                        }
                    }
                }
                JSONObject().put("id","${course.id}:$id").put("title",a.optString("name")).put("course",course.name).put("courseId",course.id).put("due",due?:JSONObject.NULL).put("kind",if(a.optString("name").contains(Regex("시험|고사|exam|quiz",RegexOption.IGNORE_CASE)))"exam" else "task").put("done",submission?.optString("workflow_state") in listOf("submitted","graded","pending_review")).put("score",score?:JSONObject.NULL).put("fetchedAt",now).put("url","https://myetl.snu.ac.kr/courses/${course.id}/assignments/$id")
            }
            data.put("academic",JSONArray(rows("academic").filterNot {it.optLong("courseId")==course.id}+current))
            next.put("scores",updated).put("assignmentsReady",true).put("metadata",metadata).put("metadataReady",true)
        }
        if(announcements!=null) {
            val saved=rows("announcements").associateBy {it.optString("id")}
            val cached=announcements.map {a->
                val id="${course.id}:${a.getLong("id")}"
                JSONObject().put("id",id).put("courseId",course.id).put("course",course.name).put("title",a.optString("title"))
                    .put("at",runCatching {Instant.parse(a.optString("posted_at")).toEpochMilli()}.getOrDefault(0L))
                    .put("url","https://myetl.snu.ac.kr/courses/${course.id}/discussion_topics/${a.getLong("id")}")
                    .put("read",saved[id]?.optBoolean("read")?:false).put("body",EtlLogic.htmlToText(a.optString("message"),12000)).put("category",when {Regex("휴강|보강|수업.*변경").containsMatchIn(a.optString("title"))->"수업 변경";Regex("시험|고사|exam|quiz",RegexOption.IGNORE_CASE).containsMatchIn(a.optString("title"))->"시험";Regex("과제|제출|assignment",RegexOption.IGNORE_CASE).containsMatchIn(a.optString("title"))->"과제";else->"공지"})
            }.sortedByDescending {it.optLong("at")}.take(200)
            data.put("announcements",JSONArray(rows("announcements").filterNot {it.optLong("courseId")==course.id}+cached))
            val ids=prev.optJSONArray("announcements")?.longs().orEmpty().toSet()
            for(a in announcements)if(prev.optBoolean("announcementsReady")&&a.optLong("id") !in ids&&prefs.optBoolean("announcements")&&!mutedCourse(course.id)) {
                val posted=runCatching {Instant.parse(a.optString("posted_at")).toEpochMilli()}.getOrDefault(now)
                if(now-posted<=14L*86400000)enqueue("announcements","공지 · ${course.name}",a.optString("title"),url="https://myetl.snu.ac.kr/courses/${course.id}/discussion_topics/${a.optLong("id")}")
            }
            next.put("announcements",JSONArray((ids+announcements.map {it.optLong("id")}).toList())).put("announcementsReady",true)
        }
        all.put(key,next);data.put("lastSync",now);data.remove("syncError");commit();tick()
    }
    @Synchronized fun ingestFiles(course:Course,files:List<JSONObject>) {
        val watch=data.getJSONObject("watch");val key=course.id.toString();val state=watch.optJSONObject(key)?:JSONObject().also {watch.put(key,it)};val old=state.optJSONObject("files")?:JSONObject();val next=JSONObject()
        for(f in files){if(f.optBoolean("locked_for_user")||f.optBoolean("hidden_for_user")||f.optBoolean("locked")||f.optBoolean("hidden"))continue;val id=f.optLong("id").toString();val stamp=f.optString("updated_at");next.put(id,stamp)
            if(state.optBoolean("filesReady")&&(!old.has(id)||old.optString(id)!=stamp)&&schoolSettings().optBoolean("newFiles",true)&&!mutedCourse(course.id))enqueue("newFiles","새 자료 · ${course.name}",f.optString("display_name",f.optString("filename")),url="https://myetl.snu.ac.kr/courses/${course.id}/files/$id")
        };state.put("files",next).put("filesReady",true);commit()
    }
    @Synchronized fun tick() {
        val now=clock();val fired=data.getJSONObject("fired");val prefs=settings();var changed=false
        val candidates=mutableListOf<JSONObject>()
        for(source in listOf("personal","academic"))for(item in if(source=="personal")personalRows(now)else rows(source)) {
            if(item.optBoolean("done")||item.isNull("due"))continue
            if(source=="academic"&&now-item.optLong("fetchedAt")>3L*86400000)continue
            val due=item.optLong("due");val offsets=if(source=="personal")item.optJSONArray("reminders").longs() else deadlineWindows()
            val entered=offsets.filter {now>=due-it*60000}.sorted()
            if(entered.isEmpty())continue
            val keys=entered.map {"$source:${item.getString("id")}:$due:$it"}
            if(keys.all {fired.has(it)})continue
            keys.forEach {fired.put(it,now)};changed=true
            if(!prefs.optBoolean(if(source=="personal")"personal" else "deadlines")||(source=="academic"&&mutedCourse(item.optLong("courseId"))))continue
            if(source=="academic"&&now-due>86400000)continue
            val past=now>=due
            val time=DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(Instant.ofEpochMilli(due))
            candidates+=JSONObject().put("title",if(past)"놓친 일정 확인" else if(item.optString("kind")=="exam")"시험 일정" else "마감 알림").put("body","${item.optString("course").let {if(it.isBlank())"" else "$it · "}}${item.optString("title")} · $time").put("kind",if(source=="personal")"personal" else "deadlines").put("url",item.opt("url")?:JSONObject.NULL)
        }
        if(schoolSettings().optBoolean("examDone",true))for(item in rows("personal")) {
            val end=item.optLong("end");val key="exam-end:${item.optString("id")}:$end"
            if(item.optString("kind")=="exam"&&end>0&&now>=end&&now-end<=3600000&&!fired.has(key)){fired.put(key,now);changed=true;if(prefs.optBoolean("personal"))enqueue("examDone","시험 보느라 수고했어요",item.optString("title")+" · 잠깐 쉬어 가요.")}
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
