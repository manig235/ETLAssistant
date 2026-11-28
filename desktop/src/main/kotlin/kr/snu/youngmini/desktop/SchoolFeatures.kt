package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.*
import kr.snu.youngmini.portable.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Portable Android school features; no Android provider, browser profile, or secret storage. */
class SchoolFeatures(private val paths:AppPaths,private val materials:Materials,private val agenda:Agenda) {
    private val file=File(paths.root,"school.json")
    private val data=runCatching {JSONObject(file.readText())}.getOrDefault(JSONObject())
    private val refreshLock=Any()
    private var academicRevision=0
    var onChange:()->Unit={}
    init {for(k in listOf("settings","courseModes"))if(!data.has(k))data.put(k,JSONObject());for(k in listOf("customClasses","changes"))if(!data.has(k))data.put(k,JSONArray())}
    @Synchronized fun settings()=JSONObject().put("theme","light").put("casual",false).put("study",true).put("updates",true).put("quiet",false).put("quietStart",1320).put("quietEnd",480).put("brief",false).put("briefTimes",JSONArray(listOf(480))).put("classAlerts",true).put("classLead",10).put("newFiles",true).put("changes",true).put("submissions",true).put("submitOk",true).put("examDone",true).put("hideContent",false).put("retentionDays",0).put("deadlineWindows",JSONArray(listOf(4320,1440,180,10))).put("hideTimetable",JSONArray()).also {out->val s=data.getJSONObject("settings");s.keys().forEach {out.put(it,s.get(it))}}
    @Synchronized fun settings(o:JSONObject) {
        val s=JSONObject(data.getJSONObject("settings").toString())
        if(o.has("theme")){require(o.getString("theme") in listOf("light","dark","system"));s.put("theme",o.getString("theme"))}
        for(k in listOf("casual","study","updates","quiet","brief","classAlerts","newFiles","changes","submissions","submitOk","examDone","hideContent"))if(o.has(k))s.put(k,o.getBoolean(k))
        for(k in listOf("quietStart","quietEnd","classLead"))if(o.has(k)){val n=o.getInt(k);require(n in if(k=="classLead")1..120 else 0..1439);s.put(k,n)}
        if(o.has("briefTimes")){val list=o.getJSONArray("briefTimes").longs().distinct();require(list.size in 1..2&&list.all {it in 0..1439});s.put("briefTimes",JSONArray(list))}
        if(o.has("deadlineWindows")){val values=o.getJSONArray("deadlineWindows").longs().distinct().sortedDescending();require(values.size in 1..12&&values.all {it in 10..20160});s.put("deadlineWindows",JSONArray(values))}
        if(o.has("hideTimetable")){val values=o.getJSONArray("hideTimetable").longs().distinct();require(values.size<=500&&values.all {it>0});s.put("hideTimetable",JSONArray(values))}
        if(o.has("retentionDays")){val days=o.getInt("retentionDays");require(days in listOf(0,2,7,30));s.put("retentionDays",days)}
        if(o.optBoolean("brief")&&!data.optBoolean("briefWasOn"))data.put("briefEnabledAt",System.currentTimeMillis())
        data.put("settings",s);data.put("briefWasOn",s.optBoolean("brief"));commit()
    }
    @Synchronized fun courseMode(id:Long)=data.getJSONObject("courseModes").optString(id.toString(),"show")
    @Synchronized fun courseMode(id:Long,mode:String){require(id>0&&mode in listOf("show","mute","hide"));val before=courseMode(id);data.getJSONObject("courseModes").put(id.toString(),mode);if(mode=="hide"&&before!="hide"){val ids=settings().getJSONArray("hideTimetable").longs();data.getJSONObject("settings").put("hideTimetable",JSONArray((ids+id).distinct()))};commit()}
    @Synchronized fun courseModes()=JSONObject(data.getJSONObject("courseModes").toString())
    private fun scores()=ScoreStats.fromJson(data.optJSONObject("scores")?.toString())
    @Synchronized fun scoreRows()=JSONArray(ScoreStats.rows(scores().items).map {ScoreStats.toolJson(it).put("ids",JSONArray(it.ids))})
    @Synchronized fun saveScore(o:JSONObject):String {
        fun number(k:String)=if(o.has(k)&&!o.isNull(k))o.getDouble(k)else null
        val st=o.optJSONObject("stats")?:JSONObject()
        fun stat(k:String)=if(st.has(k)&&!st.isNull(k))st.getDouble(k)else null
        val stats=ScoreStatistics(stat("min"),stat("max"),stat("mean"),stat("q1"),stat("median"),stat("q3"),stat("stddev"),if(st.has("count")&&!st.isNull("count"))st.getInt("count")else null)
        val my=number("my_score");val points=number("points_possible");ScoreStats.validate(stats,my,points)?.let {throw EtlException(it)}
        val title=o.getString("title").trim();val course=o.optString("course").trim();require(title.length in 1..60&&course.length<=120)
        val old=scores();val wanted=o.optString("id").takeIf {it.isNotBlank()};require(wanted==null||old.items.any {it.id==wanted&&it.source!=ScoreSource.ETL})
        val id=wanted?:"manual:${UUID.randomUUID()}"
        val item=ScoreStatEntry(id,ScoreSource.MANUAL,if(o.has("course_id")&&!o.isNull("course_id"))o.getLong("course_id")else null,course,if(o.has("assignment_id")&&!o.isNull("assignment_id"))o.getLong("assignment_id")else null,title,stats,my,points,System.currentTimeMillis())
        data.put("scores",JSONObject(ScoreStats.toJson(old.copy(items=ScoreStats.trim(old.items.filterNot {it.id==id}+item)))));commit();return id
    }
    @Synchronized fun deleteScore(ids:List<String>){val s=scores();val gone=s.items.filter {it.id in ids};data.put("scores",JSONObject(ScoreStats.toJson(s.copy(items=s.items-gone.toSet(),hidden=s.hidden+gone.filter {it.source==ScoreSource.ETL||it.source==ScoreSource.ANNOUNCEMENT}.map {it.id}))));commit()}
    @Synchronized fun ingest(course:Course,assignments:List<JSONObject>?,announcements:List<JSONObject>?) {
        val fresh=assignments.orEmpty().mapNotNull {a->
            val st=a.optJSONObject("score_statistics")?:return@mapNotNull null
            fun n(k:String)=(st.opt(k) as? Number)?.toDouble()
            ScoreStats.fromEtl(course.id,course.name,a.optLong("id"),a.optString("name"),ScoreStatistics(n("min"),n("max"),n("mean"),n("lower_q"),n("median"),n("upper_q"),n("stddev")),(a.optJSONObject("submission")?.opt("score") as? Number)?.toDouble(),(a.opt("points_possible") as? Number)?.toDouble(),System.currentTimeMillis())
        }
        var s=ScoreStats.mergeEtl(scores(),fresh)
        for(a in announcements.orEmpty()) {
            if(!(a.optString("title")+" "+EtlLogic.htmlToText(a.optString("message"),20000)).contains(Regex("성적|점수|채점|분포|score|grade|distribution|statistics",RegexOption.IGNORE_CASE)))continue
            val parsed=ScoreStatsParser.parse(a.optString("title"),ScoreStatsParser.textFromHtml(a.optString("message")))
            if(!parsed.confident)continue
            parsed.items.forEachIndexed {index,p->val id="ann:${course.id}:${a.optLong("id")}:$index";if(id !in s.hidden){val e=ScoreStatEntry(id,ScoreSource.ANNOUNCEMENT,course.id,course.name,null,p.label?:a.optString("title"),p.stats,null,p.points,System.currentTimeMillis(),a.optLong("id"));s=s.copy(items=s.items.filterNot {it.id==id}+e)}}
        }
        data.put("scores",JSONObject(ScoreStats.toJson(s)));commit()
    }
    private fun catalog()=TimetableLogic.fromJson(data.optJSONObject("catalog")?.toString())
    private fun custom()=TimetableLogic.fromJson(JSONObject().put("version",2).put("courses",data.getJSONArray("customClasses")).toString())?.courses.orEmpty()
    @Synchronized fun weekly()=CustomTimetableLogic.merge(catalog(),custom())?:TimetableLogic.Weekly("",0,emptyList(),emptyList())
    @Synchronized fun changes()=TimetableLogic.changesFromJson(data.getJSONArray("changes").toString())
    @Synchronized fun saveClass(o:JSONObject):Long {
        val id=o.optLong("course_id").takeIf {it<0}?:-System.currentTimeMillis()
        val raw=JSONObject().put("version",2).put("courses",JSONArray(listOf(JSONObject(o.toString()).put("course_id",id))))
        val course=TimetableLogic.fromJson(raw.toString())?.courses?.singleOrNull()?:throw EtlException("수업 정보를 확인해 주세요")
        val valid=CustomTimetableLogic.normalize(course)?:throw EtlException(CustomTimetableLogic.validationError(course)?:"수업 정보를 확인해 주세요")
        data.put("customClasses",JSONObject(TimetableLogic.toJson(TimetableLogic.Weekly("",0,custom().filterNot {it.courseId==id}+valid,emptyList()))).getJSONArray("courses"));commit();return id
    }
    @Synchronized fun deleteClass(id:Long){require(id<0);data.put("customClasses",JSONObject(TimetableLogic.toJson(TimetableLogic.Weekly("",0,custom().filterNot {it.courseId==id},emptyList()))).getJSONArray("courses"));commit()}
    @Synchronized fun saveChange(o:JSONObject):Long {
        val kind=o.getString("kind");val course=o.getString("course").trim();val start=o.optString("start").takeIf {it.isNotBlank()};val end=o.optString("end").takeIf {it.isNotBlank()}
        TimetableLogic.validateChange(kind,course,start,end)?.let {throw EtlException(it)}
        val date=LocalDate.parse(o.getString("date"));val id=o.optLong("id").takeIf {it>0}?:System.currentTimeMillis()
        val ch=TimetableLogic.Change(id,date,kind,course,o.optLong("course_id").takeIf {it!=0L},start,end,o.optString("room").take(80),o.optString("note").take(500))
        data.put("changes",JSONArray(TimetableLogic.changesToJson(changes().filterNot {it.id==id}+ch)));commit();return id
    }
    @Synchronized fun deleteChange(id:Long){data.put("changes",JSONArray(TimetableLogic.changesToJson(changes().filterNot {it.id==id})));commit()}
    fun refreshTimetable(force:Boolean=false):Unit=synchronized(refreshLock) {
        val revision=synchronized(this){academicRevision}
        val all=materials.courses();val cached=synchronized(this){catalog()};val ids=all.map {it.id}.sorted().joinToString(",")
        if(!force&&cached?.ids==ids&&System.currentTimeMillis()-cached.fetchedAt<86400000)return@synchronized
        val etl=materials.client();val refs=all.map {runCatching {TimetableLogic.catalogRef(etl.getObject("/api/v1/courses/${it.id}?include[]=syllabus_body").optString("syllabus_body"))}.getOrNull()}
        val asked=refs.indices.filter {refs[it]!=null};val answers=CatalogClient.fetch(asked.map {refs[it]!!});val errors=mutableListOf<String>()
        val courses=all.mapIndexed {index,c->val result=asked.indexOf(index).takeIf {it>=0}?.let {answers[it]};if(result==null||result.isFailure)errors+="${c.name}: 수업 정보를 확인하지 못했어요";TimetableLogic.refreshedCourse(c.id,c.name,result?.getOrNull(),cached?.courses?.find {it.courseId==c.id})}
        synchronized(this){if(revision!=academicRevision)return@synchronized;data.put("catalog",JSONObject(TimetableLogic.toJson(TimetableLogic.Weekly(ids,System.currentTimeMillis(),courses,errors))));commit()}
    }
    @Synchronized fun snapshot():JSONObject {
        val w=weekly();val today=TimetableLogic.day(w,LocalDate.now(EtlLogic.KST),changes())
        return JSONObject().put("settings",settings()).put("courseModes",courseModes()).put("stats",scoreRows()).put("timetable",JSONObject(TimetableLogic.toJson(w))).put("changes",JSONArray(TimetableLogic.changesToJson(changes()))).put("todayClasses",TimetableLogic.classesJson(today.classes)).put("cancelledClasses",TimetableLogic.classesJson(today.cancelled)).put("briefText",data.optString("briefText")).put("briefAt",data.optLong("briefAt")).put("lastCleanup",data.optJSONObject("lastCleanup")?:JSONObject.NULL)
    }
    @Synchronized fun briefing(snapshot:JSONObject,now:Long=System.currentTimeMillis()):String {
        val today=Instant.ofEpochMilli(now).atZone(EtlLogic.KST).toLocalDate();val classes=TimetableLogic.day(weekly(),today,changes()).classes.filter {it.courseId==null||it.courseId<0||!(courseMode(it.courseId)=="hide"&&it.courseId in settings().getJSONArray("hideTimetable").longs())}
        val tasks=listOf("personal","academic").flatMap {key->snapshot.getJSONArray(key).let {a->(0 until a.length()).map {a.getJSONObject(it)}}}.filter {!it.optBoolean("done")&&!it.isNull("due")&&it.optLong("due") in now..now+7*86400000L}.sortedBy {it.optLong("due")}
        val announcements=snapshot.getJSONArray("announcements").let {a->(0 until a.length()).map {a.getJSONObject(it)}}.filter {!it.optBoolean("read")&&it.optLong("at")>=now-7*86400000L}
        val text=buildString {
            append("${today.monthValue}월 ${today.dayOfMonth}일 브리핑\n\n오늘 수업\n")
            append(if(classes.isEmpty())"오늘 수업이 없어요." else TimetableLogic.briefLines(classes).joinToString("\n"))
            append("\n\n다가오는 마감 · 시험\n")
            append(if(tasks.isEmpty())"앞으로 7일의 남은 일정이 없어요." else tasks.take(12).joinToString("\n"){"${EtlLogic.fmtKst(Instant.ofEpochMilli(it.optLong("due")))} · ${it.optString("course")} ${it.optString("title")}"})
            append("\n\n읽지 않은 공지 ${announcements.size}개")
            announcements.take(5).forEach {append("\n${it.optString("course")} · ${it.optString("title")}")}
        }
        data.put("briefText",text);data.put("briefAt",now);commit();return text
    }
    @Synchronized fun tick(snapshot:JSONObject,now:Long=System.currentTimeMillis()):List<JSONObject> {
        val prefs=settings();val fired=data.optJSONObject("reminderKeys")?:JSONObject().also {data.put("reminderKeys",it)};val out=mutableListOf<JSONObject>();var changed=false
        val day=Instant.ofEpochMilli(now).atZone(EtlLogic.KST).toLocalDate()
        for(time in prefs.getJSONArray("briefTimes").longs())if(prefs.optBoolean("brief")) {
            val due=day.atStartOfDay(EtlLogic.KST).plusMinutes(time).toInstant().toEpochMilli();val key="brief:$day:$time"
            if(now>=due&&now-due<3600000&&due>=data.optLong("briefEnabledAt")&&!fired.has(key)){fired.put(key,now);changed=true;out+=JSONObject().put("kind","brief").put("title","오늘의 학습 브리핑").put("body",briefing(snapshot,now))}
        }
        val lead=prefs.optInt("classLead",10)
        for(c in TimetableLogic.day(weekly(),day,changes()).classes) {
            val due=runCatching {java.time.LocalDateTime.parse("$day"+"T"+c.start).atZone(EtlLogic.KST).toInstant().toEpochMilli()}.getOrNull()?:continue
            val key="class:$day:${c.courseId}:${c.course}:${c.start}"
            if(now>=due-lead*60000&&now<due+10*60000&&!fired.has(key)){fired.put(key,now);changed=true;if(prefs.optBoolean("classAlerts")&&courseMode(c.courseId?:0)=="show")out+=JSONObject().put("kind","classes").put("title","곧 수업이 있어요").put("body","${c.start}–${c.end} ${c.course}"+(c.room?.let {" · $it"}?:""))}
        }
        if(changed){val old=fired.keys().asSequence().filter {now-fired.optLong(it)>30*86400000L}.toList();old.forEach {fired.remove(it)};commit()}
        return out
    }
    @Synchronized fun clearAcademic(){academicRevision++;data.remove("catalog");data.remove("scores");commit()}
    @Synchronized fun cleanupResult(result:JSONObject){data.put("lastCleanup",result);commit()}
    private fun commit(){atomicWrite(file,data.toString().toByteArray(Charsets.UTF_8));onChange()}
}
