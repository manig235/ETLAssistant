package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

/** Offline iCalendar exchange, not an account or CalendarProvider connection. */
object CalendarFiles {
    private val stamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dayNames=listOf("MO","TU","WE","TH","FR","SA","SU")
    private fun escape(s:String)=s.replace("\\","\\\\").replace("\r","").replace("\n","\\n").replace(";","\\;").replace(",","\\,")
    private fun unescape(s:String):String {val out=StringBuilder();var i=0;while(i<s.length){if(s[i]=='\\'&&i+1<s.length){i++;out.append(if(s[i] in "nN")'\n'else s[i])}else out.append(s[i]);i++};return out.toString()}
    fun export(events:JSONArray):String {
        require(events.length()<=2000);val lines=mutableListOf("BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//Young Mini//Windows//KO","CALSCALE:GREGORIAN","BEGIN:VTIMEZONE","TZID:Asia/Seoul","BEGIN:STANDARD","DTSTART:19700101T000000","TZOFFSETFROM:+0900","TZOFFSETTO:+0900","TZNAME:KST","END:STANDARD","END:VTIMEZONE")
        for(i in 0 until events.length()){val e=events.getJSONObject(i);if(e.optBoolean("done"))continue
            val due=e.getLong("due");fun date(time:Long)=stamp.format(Instant.ofEpochMilli(time).atZone(EtlLogic.KST))
            lines+="BEGIN:VEVENT";lines+="UID:"+escape(e.optString("id","event-$i"))+"@youngmini.local";lines+="DTSTAMP:"+stamp.format(Instant.now().atZone(ZoneOffset.UTC))+"Z";lines+="DTSTART;TZID=Asia/Seoul:"+date(due)
            val end=e.optLong("end");if(end>due)lines+="DTEND;TZID=Asia/Seoul:"+date(end)
            lines+="SUMMARY:"+escape(e.getString("title"));lines+="DESCRIPTION:"+escape(listOf(e.optString("course"),e.optString("notes"),e.optString("supplies")).filter {it.isNotBlank()}.joinToString("\n"));lines+="LOCATION:"+escape(e.optString("location"))
            e.optJSONObject("repeat")?.let {r->val freq=r.getString("freq");val rule=mutableListOf("FREQ="+when(freq){"daily"->"DAILY";"weekly","biweekly"->"WEEKLY";"monthly"->"MONTHLY";else->throw EtlException("지원하지 않는 반복 일정이에요")});if(freq=="biweekly")rule+="INTERVAL=2";val days=r.optJSONArray("weekdays").longs();if(freq in listOf("weekly","biweekly")&&days.isNotEmpty())rule+="BYDAY="+days.joinToString(","){dayNames[it.toInt()-1]};if(r.optInt("count")>0)rule+="COUNT=${r.getInt("count")}" else if(r.optString("until").let {it.isNotBlank()&&it!="null"})rule+="UNTIL="+stamp.format(LocalDate.parse(r.getString("until")).plusDays(1).atStartOfDay(EtlLogic.KST).minusSeconds(1).withZoneSameInstant(ZoneOffset.UTC))+"Z";lines+="RRULE:"+rule.joinToString(";")
                val time=Instant.ofEpochMilli(due).atZone(EtlLogic.KST).toLocalTime();val excluded=listOf("doneDates","skipDates").flatMap {key->e.optJSONArray(key)?.let {a->(0 until a.length()).map {a.getString(it)}}.orEmpty()}.distinct();if(excluded.isNotEmpty())lines+="EXDATE;TZID=Asia/Seoul:"+excluded.joinToString(","){stamp.format(LocalDate.parse(it).atTime(time))}
            }
            lines+="END:VEVENT"
        }
        // Fold by UTF-8 octets, preserving code points (RFC 5545 3.1).
        return lines.joinToString("\r\n",postfix="\r\n"){line->val out=StringBuilder();var size=0;line.codePoints().forEach {cp->val c=String(Character.toChars(cp));val n=c.toByteArray(Charsets.UTF_8).size;if(size+n>73){out.append("\r\n ");size=1};out.append(c);size+=n};out.toString()}
    }
    fun read(text:String):JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size<=2*1024*1024);val lines=text.replace("\r\n ","").replace("\r\n\t","").replace("\n ","").lineSequence().map {it.trimEnd('\r')}.toList();require(lines.firstOrNull()?.removePrefix("\uFEFF")=="BEGIN:VCALENDAR")
        val events=JSONArray();val errors=JSONArray();var current:MutableList<String>?=null;var alarm=false
        for(line in lines){when(line){"BEGIN:VEVENT"->{require(current==null);current=mutableListOf()};"BEGIN:VALARM"->alarm=true;"END:VALARM"->alarm=false;"END:VEVENT"->{val fields=current?:throw EtlException("일정 파일 구조를 확인해 주세요");try {events.put(event(fields))}catch(e:Exception){errors.put(e.message?:"일정 형식 확인 필요")};current=null;require(events.length()+errors.length()<=2000)};else->if(current!=null&&!alarm)current.add(line)}}
        require(current==null);return JSONObject().put("events",events).put("errors",errors).put("note","로컬 파일의 일정 후보예요. 외부 링크·첨부·초대·알림은 실행하지 않았어요. 등록 전 내용과 시간대를 사용자에게 확인하세요.")
    }
    private fun event(lines:List<String>):JSONObject {
        fun field(name:String)=lines.firstOrNull {it.substringBefore(':').substringBefore(';')==name}
        fun value(name:String)=field(name)?.substringAfter(':')?.let(::unescape).orEmpty()
        fun instant(raw:String):Instant {val key=raw.substringBefore(':');val v=raw.substringAfter(':');return when {v.endsWith('Z')->LocalDateTime.parse(v.dropLast(1),stamp).toInstant(ZoneOffset.UTC);key.contains("VALUE=DATE")->LocalDate.parse(v,DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(EtlLogic.KST).toInstant();else->{val zone=key.substringAfter("TZID=","Asia/Seoul").substringBefore(';').trim('"');LocalDateTime.parse(v,stamp).atZone(ZoneId.of(zone)).toInstant()}}}
        val start=field("DTSTART")?:throw EtlException("시작 시간이 없는 일정이에요");val title=value("SUMMARY").ifBlank {"가져온 일정"};require(title.length<=120)
        val out=JSONObject().put("title",title).put("due",instant(start).toEpochMilli()).put("notes",value("DESCRIPTION").take(4000)).put("location",value("LOCATION").take(500)).put("kind","task").put("reminders",JSONArray(listOf(10))).put("done",false).put("uid",value("UID")).put("excluded_dates",JSONArray(lines.filter {it.startsWith("EXDATE")}.flatMap {line->line.substringAfter(':').split(',').map {instant(line.substringBefore(':')+":"+it).atZone(EtlLogic.KST).toLocalDate().toString()}}))
        field("DTEND")?.let {out.put("end",instant(it).toEpochMilli())}
        field("RRULE")?.let {line->val pairs=line.substringAfter(':').split(';').associate {it.substringBefore('=') to it.substringAfter('=')};require(pairs.keys.all {it in listOf("FREQ","INTERVAL","COUNT","UNTIL","BYDAY","WKST")}){"지원하지 않는 반복 규칙이 있어요: $title"};val interval=pairs["INTERVAL"]?.toInt()?:1;val freq=when(pairs["FREQ"]){"DAILY"->"daily";"WEEKLY"->if(interval==2)"biweekly"else "weekly";"MONTHLY"->"monthly";else->throw EtlException("지원하지 않는 반복 규칙: $title")};require(interval==1||(interval==2&&freq=="biweekly"));val repeat=JSONObject().put("freq",freq).put("weekdays",JSONArray(pairs["BYDAY"]?.split(',')?.map {dayNames.indexOf(it).also {n->require(n>=0)}+1}.orEmpty()));pairs["COUNT"]?.let {repeat.put("count",it.toInt())};pairs["UNTIL"]?.let {repeat.put("until",instant("DTSTART:$it").atZone(EtlLogic.KST).toLocalDate().toString())};out.put("repeat",repeat)}
        return out
    }
}
