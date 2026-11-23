package kr.snu.etlassistant.etl

import android.content.Context
import kr.snu.etlassistant.etl.EtlLogic.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/*
 * 수업 시간표. ttokttok-pet `src/etl/timetable.js`를 옮긴 것이다.
 *
 * eTL(Canvas)에는 수업 시간이 없지만, 과목마다 강의계획서 본문이 서울대 공개 수강편람의 그 과목
 * (sugang.snu.ac.kr/sugang/cc/cc103.action?openSchyy=…&sbjtCd=…&ltNo=…)을 가리킨다. 수강편람의 상세 조회
 * (cc101ajax.action)는 검색 페이지에서 받은 세션 쿠키만 있으면 수업 요일·시간·강의실을 JSON으로 준다.
 * 로그인이 필요 없고 **eTL 토큰은 수강편람에 절대 보내지 않는다**(보내는 것은 공개 과목 코드뿐).
 * 주간 시간표는 하루 동안(또는 수강 과목이 바뀔 때까지) 캐시한다.
 * 하루의 수업 = 주간 시간표의 그 요일 수업 + 그날 eTL 캘린더 일정(교수가 보강 등을 올리는 곳).
 */

object TimetableLogic {
    const val WEEKDAYS = "월화수목금토일" // DayOfWeek.value - 1
    val REF_KEYS = listOf("openSchyy", "openShtmFg", "openDetaShtmFg", "sbjtCd", "ltNo", "sbjtSubhCd")
    private val REF_VALUE = Regex("^[\\w.-]{1,40}$")
    private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val MEETING = Regex("^\\s*([일월화수목금토])\\((\\d{1,2}:\\d{2})~(\\d{1,2}:\\d{2})\\)")

    data class Meeting(val day: String, val start: String, val end: String, val room: String?)
    data class CourseMeetings(val courseId: Long, val name: String, val meetings: List<Meeting>, val professor: String? = null)
    data class Weekly(val ids: String, val fetchedAt: Long, val courses: List<CourseMeetings>, val errors: List<String>, val metadataVersion: Int = 2)
    data class CatalogCourse(val meetings: List<Meeting>, val professor: String?, val metadataError: String? = null, val meetingError: String? = null)
    data class ClassRow(val start: String, val end: String, val course: String, val courseId: Long?, val room: String?, val kind: String, val note: String? = null)

    /** 사용자가 넣은 보강·휴강(ttokttok-pet의 timetable.md 한 줄). 휴강은 [start]가 있으면 그 시각 수업만 뺀다. */
    data class Change(
        val id: Long,
        val date: LocalDate,
        val kind: String, // "보강" | "휴강"
        val course: String,
        val courseId: Long? = null,
        val start: String? = null,
        val end: String? = null,
        val room: String? = null,
        val note: String? = null,
    )

    data class Day(val date: LocalDate, val weekday: String, val classes: List<ClassRow>, val cancelled: List<ClassRow>)

    private fun squash(s: String?) = (s ?: "").lowercase().replace(Regex("[\\s·.:()\\-_]"), "")

    /** 사용자가 쓴 과목명이 이 과목인지: 공백·문장부호를 빼고 한쪽이 다른 쪽을 포함하면 같다("현실공학" ↔ "가상현실공학"). */
    fun sameCourse(written: String?, course: String?): Boolean {
        val a = squash(written)
        val b = squash(shortName(course))
        return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
    }

    private fun matches(c: Change, courseId: Long, name: String) =
        if (c.courseId != null) c.courseId == courseId else sameCourse(c.course, name)

    /** 그날: 정규 수업(휴강 제외) + 보강 + eTL 일정, 시작순. 휴강으로 빠진 수업은 [Day.cancelled]. */
    fun day(weekly: Weekly?, date: LocalDate, changes: List<Change> = emptyList(), events: List<ClassRow> = emptyList()): Day {
        val wd = weekdayOf(date)
        val todays = changes.filter { it.date == date }
        val cancels = todays.filter { it.kind == "휴강" }
        val classes = mutableListOf<ClassRow>()
        val cancelled = mutableListOf<ClassRow>()
        for (c in weekly?.courses.orEmpty()) for (m in c.meetings) {
            if (m.day != wd) continue
            val row = ClassRow(m.start, m.end, shortName(c.name), c.courseId, m.room, "정규")
            val off = cancels.firstOrNull { matches(it, c.courseId, c.name) && (it.start == null || it.start == m.start) }
            if (off != null) cancelled += row.copy(note = off.note) else classes += row
        }
        for (ch in todays.filter { it.kind == "보강" && it.start != null && it.end != null }) {
            val course = weekly?.courses?.firstOrNull { matches(ch, it.courseId, it.name) }
            classes += ClassRow(ch.start!!, ch.end!!, course?.let { shortName(it.name) } ?: ch.course.ifBlank { "보강" },
                course?.courseId ?: ch.courseId, ch.room, "보강", ch.note)
        }
        classes += events
        val order = compareBy<ClassRow>({ it.start }, { it.end })
        return Day(date, wd, classes.sortedWith(order), cancelled.sortedWith(order))
    }

    fun changesToJson(list: List<Change>): String = JSONArray().also { arr ->
        list.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("date", c.date.toString()).put("kind", c.kind).put("course", c.course)
                .put("course_id", c.courseId ?: JSONObject.NULL).put("start", c.start ?: JSONObject.NULL).put("end", c.end ?: JSONObject.NULL)
                .put("room", c.room ?: JSONObject.NULL).put("note", c.note ?: JSONObject.NULL))
        }
    }.toString()

    fun changesFromJson(s: String?): List<Change> {
        val arr = try { JSONArray(s ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = o.optString("kind").takeIf { it == "보강" || it == "휴강" } ?: return@mapNotNull null
            val date = try { LocalDate.parse(o.optString("date")) } catch (_: Exception) { return@mapNotNull null }
            val start = normTime(o.str("start"))
            val end = normTime(o.str("end"))
            if (kind == "보강" && (start == null || end == null)) return@mapNotNull null
            Change(o.optLong("id"), date, kind, o.optString("course"), o.optLong("course_id").takeIf { it > 0 }, start, end, o.str("room"), o.str("note"))
        }
    }

    /** 입력 확인: 문제가 없으면 null. */
    fun validateChange(kind: String, course: String, start: String?, end: String?): String? = when {
        course.isBlank() -> "과목을 고르거나 입력하세요"
        kind == "보강" && (normTime(start) == null || normTime(end) == null) -> "보강은 시작·끝 시각이 필요해요"
        kind == "보강" && normTime(start)!! >= normTime(end)!! -> "끝 시각이 시작보다 늦어야 해요"
        else -> null
    }

    /** `9:30` → `09:30`, 아니면 null. */
    fun normTime(s: String?): String? {
        val m = TIME.find(s?.trim() ?: return null) ?: return null
        return "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
    }

    /** "2026-2 가상현실공학 (001)" → "가상현실공학". */
    fun shortName(name: String?): String {
        val s = (name ?: "").replace(Regex("^\\s*\\d{4}-\\d\\s+"), "").replace(Regex("\\s*\\(\\d{3}\\)\\s*$"), "").trim()
        return s.ifEmpty { name ?: "" }
    }

    /** 강의계획서 HTML 안의 수강편람 과목 참조. 없거나 이상하면 null. */
    fun catalogRef(html: String?): Map<String, String>? {
        val text = (html ?: return null).replace("&amp;", "&", ignoreCase = true).replace("&#38;", "&").replace("&#x26;", "&", ignoreCase = true)
        val m = Regex("sugang\\.snu\\.ac\\.kr/sugang/cc/cc10[13]\\.action\\?([^\"'\\s<>]+)").find(text) ?: return null
        val q = m.groupValues[1].split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()
        val ref = linkedMapOf<String, String>()
        for (k in REF_KEYS) {
            val v = q[k] ?: if (k == "sbjtSubhCd") "000" else return null
            if (!REF_VALUE.matches(v)) return null
            ref[k] = v
        }
        return ref
    }

    /** "301-103(무선랜제공)" → "301-103". */
    fun cleanRoom(s: String?): String? = s?.replace(Regex("\\([^)]*\\)"), "")?.trim()?.ifEmpty { null }

    /** 수강편람 강의계획서의 교수명. 비어 있는 필드는 상세 정보의 교수명으로 보완한다. */
    fun professor(detail: JSONObject?): String? = sequenceOf("LISTTAB03", "LISTTAB01")
        .mapNotNull { detail?.optJSONObject(it)?.str("profNm") }
        .map { EtlLogic.htmlToText(it, 200).trim() }
        .firstOrNull { it.isNotBlank() }

    /** 일부 조회만 실패하면 이미 알던 시간을 보존하고, 새로 확인한 교수 정보는 반영한다. */
    fun refreshedCourse(id: Long, name: String, details: CatalogCourse?, previous: CourseMeetings?): CourseMeetings =
        CourseMeetings(id, name,
            if (details != null && details.meetingError == null) details.meetings else previous?.meetings.orEmpty(),
            details?.professor ?: previous?.professor)

    /** cc101ajax 응답의 수업들. */
    fun parseMeetings(detail: JSONObject?): List<Meeting> {
        val out = mutableListOf<Meeting>()
        val summary = detail?.optJSONObject("LISTTAB01")?.str("lsnTmtablFormaSmryCtnt")
        if (!summary.isNullOrBlank()) {
            for (part in summary.split('/')) {
                val fields = part.split('|')
                val m = MEETING.find(fields.getOrNull(0) ?: "") ?: continue
                val s = normTime(m.groupValues[2]) ?: continue
                val e = normTime(m.groupValues[3]) ?: continue
                out += Meeting(m.groupValues[1], s, e, cleanRoom(fields.getOrNull(2)))
            }
            if (out.isNotEmpty()) return out
        }
        val times = detail?.optJSONArray("ltTime") ?: JSONArray()
        val rooms = detail?.optJSONArray("ltRoom") ?: JSONArray()
        for (i in 0 until times.length()) {
            val m = MEETING.find(times.optString(i)) ?: continue
            val s = normTime(m.groupValues[2]) ?: continue
            val e = normTime(m.groupValues[3]) ?: continue
            out += Meeting(m.groupValues[1], s, e, cleanRoom(rooms.optString(i, "").ifEmpty { null }))
        }
        return out
    }

    fun weekdayOf(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1].toString()

    // ---- 강의계획서(수강편람 cc103ajax) ----------------------------------------

    /** 수강편람 강의계획서 첨부 하나. [no]·[seq]로 [attachmentUrl]을 만든다. */
    data class SyllabusAttachment(val name: String, val lang: String, val no: String, val seq: Int)

    private val ATTACH_NO = Regex("^[\\w-]{1,64}$")
    private const val ATTACH_BASE = "https://shine.snu.ac.kr/com/download/noflt/downloadWebFile.action"

    /** 수강편람 화면이 쓰는 공개 첨부 다운로드 주소. 번호가 이상하면 null. */
    fun attachmentUrl(a: SyllabusAttachment): String? =
        if (!ATTACH_NO.matches(a.no) || a.seq !in 0..999) null
        else "$ATTACH_BASE?cscLocale=ko_KR&attcFileNo=${a.no}&attcFileDetaNo=${a.seq}"

    /** "[430.309A]Syllabus.docx (31.73KB)" → "[430.309A]Syllabus.docx". */
    fun attachmentName(s: String): String = s.replace(Regex("\\s*\\(\\s*[\\d.,]+\\s*[KMG]?B\\s*\\)\\s*$", RegexOption.IGNORE_CASE), "").trim()

    fun syllabusAttachments(detail: JSONObject?): List<SyllabusAttachment> {
        val arr = detail?.optJSONArray("LISTTAB03_LIST_ATTACH") ?: return emptyList()
        val out = mutableListOf<SyllabusAttachment>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            for ((lang, p) in listOf("ko" to "kor", "en" to "eng")) {
                val name = a.str("${p}FileNm")?.let(::attachmentName)?.ifEmpty { null } ?: continue
                val no = a.str("${p}AttachNo") ?: continue
                out += SyllabusAttachment(name, lang, no, a.optInt("${p}AttachDetaNo", 0))
            }
        }
        return out
    }

    private fun koEn(o: JSONObject?, ko: String, en: String, limit: Int = 4000): String? {
        if (o == null) return null
        val s = EtlLogic.htmlToText(o.str(ko)?.ifBlank { null } ?: o.str(en), limit)
        return s.ifBlank { null }
    }

    private val MARKS = listOf("attendance" to "출석", "homeWork" to "과제", "mid" to "중간고사", "final" to "기말고사", "quiz" to "퀴즈", "attitude" to "태도", "etc" to "기타")

    /**
     * cc103ajax(수강편람 '강의계획서' 탭) 응답을 비서가 읽기 쉬운 JSON으로. 장애학생 지원 같은 공통 문구는 뺀다.
     * 첨부는 번호(no)를 숨기고 index로만 알려 준다(받을 때 etl_syllabus의 attachment).
     */
    fun syllabusJson(detail: JSONObject): JSONObject {
        val t1 = detail.optJSONObject("LISTTAB01")
        val t3 = detail.optJSONObject("LISTTAB03")
        val plan = detail.optJSONArray("LISTTAB03_LIST_PLAN")?.optJSONObject(0)
        val o = JSONObject()
        t1?.let { o.put("course", listOfNotNull(it.str("sbjtNm"), it.str("ltNo")?.let { n -> "($n)" }).joinToString(" ")).put("course_code", it.str("sbjtCd")) }
        professor(detail)?.let { o.put("professor", it) }
        t3?.str("profEmail")?.let { o.put("professor_email", it) }
        t1?.str("openPnt")?.let { o.put("credits", it) }
        t1?.str("lsnProgLang")?.let { o.put("language", it) }
        detail.optJSONArray("ltTime")?.let { times ->
            val rooms = detail.optJSONArray("ltRoom")
            o.put("meetings", JSONArray((0 until times.length()).map { i -> times.optString(i) + (rooms?.optString(i, "")?.ifEmpty { null }?.let { " $it" } ?: "") }))
        }
        koEn(t3, "ltPurp", "ltEngPurp")?.let { o.put("goals", it) }
        koEn(plan, "ltPlanCtnt", "ltPlanEngCtnt", 8000)?.let { o.put("plan", it) }
        detail.optJSONObject("LISTTAB03_LIST_MRKS")?.let { m ->
            val g = JSONArray()
            for ((k, label) in MARKS) {
                val pct = m.optInt(k, 0)
                val remark = EtlLogic.htmlToText(m.str("${k}Remk"), 300).ifBlank { null }
                if (pct > 0 || remark != null) g.put(JSONObject().put("item", label).put("percent", pct).apply { remark?.let { put("note", it) } })
            }
            if (g.length() > 0) o.put("grading", g)
        }
        koEn(t3, "etcMrksApprMthd", "etcMrksApprEngMthd")?.let { o.put("grading_note", it) }
        listOfNotNull(t3?.str("mrksRelevalYnNm"), t3?.str("mrksGvMthdFgNm")).takeIf { it.isNotEmpty() }?.let { o.put("grading_type", it.joinToString(", ")) }
        koEn(plan, "attendRegulKorCtnt", "attendRegulEngCtnt", 600)?.let { o.put("attendance_rule", it) }
        detail.optJSONArray("LISTTAB03_LIST_WEEK_PLAN")?.let { weeks ->
            val w = JSONArray()
            for (i in 0 until weeks.length()) {
                val r = weeks.optJSONObject(i) ?: continue
                val text = koEn(r, "wekClsfLtPlanCtnt", "wekClsfLtPlanEngCtnt", 800) ?: continue
                w.put(JSONObject().put("week", r.opt("ltPlanDocWekClsfSeq") ?: (i + 1)).put("plan", text))
            }
            if (w.length() > 0) o.put("weekly_plan", w)
        }
        val books = mutableListOf<String>()
        koEn(t3, "teachmAndRefBookList", "teachmAndRefBookEngList", 1500)?.let { books += it }
        detail.optJSONArray("LISTTAB03_LIST_TEACHM_BOOK")?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                listOfNotNull(b.str("ltTeachmFgNm")?.let { "[$it]" }, b.str("ltTeachmNm"), b.str("ltTeachmAuthNm"), b.str("ltTeachmPubcoNm"), b.str("ltTeachmPublshYy"))
                    .joinToString(" ").ifBlank { null }?.let { books += it }
            }
        }
        if (books.isNotEmpty()) o.put("textbooks", JSONArray(books))
        t3?.str("preCptnSbjtNm")?.ifBlank { null }?.let { o.put("prerequisites", it) }
        koEn(t3, "stdIntrvPl", "stdIntrvEngPl", 500)?.let { o.put("office_hours", it) }
            ?: t1?.str("stdIntrvPosbTmList")?.ifBlank { null }?.let { o.put("office_hours", it) }
        koEn(t3, "genrAiUtlzKorCtnt", "genrAiUtlzEngCtnt", 1500)?.let { o.put("generative_ai_policy", it) }
        koEn(t3, "etcLsnMthd", "etcLsnEngMthd", 500)?.let { o.put("teaching_method_note", it) }
        koEn(t3, "ltPlanDocRemk", "ltPlanDocEngRemk", 1500)?.let { o.put("remarks", it) }
        val atts = syllabusAttachments(detail)
        if (atts.isNotEmpty()) o.put("attachments", JSONArray(atts.mapIndexed { i, a -> JSONObject().put("index", i).put("name", a.name).put("lang", a.lang) }))
        return o
    }

    /** 그날 수업(보강·휴강 반영): [day]의 classes. */
    fun dayClasses(weekly: Weekly?, date: LocalDate, events: List<ClassRow> = emptyList(), changes: List<Change> = emptyList()): List<ClassRow> =
        day(weekly, date, changes, events).classes

    fun classesJson(rows: List<ClassRow>): JSONArray = JSONArray().also { arr ->
        rows.forEach { r ->
            arr.put(
                JSONObject().put("start", r.start).put("end", r.end).put("course", r.course)
                    .put("course_id", r.courseId ?: JSONObject.NULL).put("room", r.room ?: JSONObject.NULL)
                    .put("kind", r.kind).apply { if (r.note != null) put("note", r.note) }
            )
        }
    }

    /** 요일별 주간 시간표(월~일, 수업 있는 요일만). */
    fun weekJson(weekly: Weekly): JSONObject {
        val days = JSONObject()
        for (d in WEEKDAYS) {
            val rows = weekly.courses.flatMap { c -> c.meetings.filter { it.day == d.toString() }.map { ClassRow(it.start, it.end, shortName(c.name), c.courseId, it.room, "정규") } }
                .sortedWith(compareBy({ it.start }, { it.end }))
            if (rows.isNotEmpty()) days.put(d.toString(), classesJson(rows))
        }
        return days
    }

    /** 브리핑용 한 줄씩: `• 17:00~18:15 전기에너지변환 (301-103)`. */
    fun briefLines(rows: List<ClassRow>): List<String> = rows.map { r ->
        "• ${r.start}~${r.end} ${r.course}" + (r.room?.let { " ($it)" } ?: "") + if (r.kind != "정규") " [${r.kind}]" else ""
    }

    fun toJson(w: Weekly): String = JSONObject()
        .put("version", w.metadataVersion)
        .put("ids", w.ids)
        .put("fetchedAt", w.fetchedAt)
        .put("errors", JSONArray(w.errors))
        .put("courses", JSONArray().also { arr ->
            w.courses.forEach { c ->
                arr.put(JSONObject().put("course_id", c.courseId).put("name", c.name).put("professor", c.professor ?: JSONObject.NULL).put("meetings", JSONArray().also { ms ->
                    c.meetings.forEach { m -> ms.put(JSONObject().put("day", m.day).put("start", m.start).put("end", m.end).put("room", m.room ?: JSONObject.NULL)) }
                }))
            }
        }).toString()

    fun fromJson(s: String?): Weekly? = try {
        val o = JSONObject(s ?: return null)
        if (o.optInt("version") !in 1..2) null else Weekly(
            ids = o.optString("ids"),
            fetchedAt = o.optLong("fetchedAt"),
            errors = (0 until (o.optJSONArray("errors")?.length() ?: 0)).map { o.getJSONArray("errors").optString(it) },
            courses = (0 until (o.optJSONArray("courses")?.length() ?: 0)).mapNotNull { i ->
                val c = o.getJSONArray("courses").optJSONObject(i) ?: return@mapNotNull null
                val ms = c.optJSONArray("meetings") ?: JSONArray()
                CourseMeetings(c.optLong("course_id"), c.optString("name"), (0 until ms.length()).mapNotNull { j ->
                    val m = ms.optJSONObject(j) ?: return@mapNotNull null
                    Meeting(m.optString("day"), m.optString("start"), m.optString("end"), m.str("room"))
                }, c.str("professor"))
            },
            metadataVersion = o.optInt("version"),
        )
    } catch (_: Exception) {
        null
    }
}

/** 공개 수강편람 조회(로그인 없음, eTL 토큰 없음). sugang.snu.ac.kr과 강의계획서 첨부용 shine.snu.ac.kr(GET)에만 보낸다. */
object CatalogClient {
    private const val SUGANG = "https://sugang.snu.ac.kr"
    private const val SEARCH_PATH = "/sugang/cc/cc100InterfaceSrch.action"
    private const val DETAIL_PATH = "/sugang/cc/cc101ajax.action"
    private const val SYLLABUS_PATH = "/sugang/cc/cc103ajax.action"
    private const val TIMEOUT_MS = 15_000

    /** 참조마다 수업 시간·교수 정보 또는 오류 문구. 한 번에 하나씩 묻는다. */
    fun fetch(refs: List<Map<String, String>>): List<Result<TimetableLogic.CatalogCourse>> {
        if (refs.isEmpty()) return emptyList()
        val cookie = try {
            session()
        } catch (e: IOException) {
            val err = Exception(netError(e))
            return refs.map { Result.failure(err) }
        }
        return refs.map { ref ->
            try {
                val syllabus = runCatching { post(SYLLABUS_PATH, ref, cookie) }
                var detail = syllabus.getOrNull()
                var meetings = TimetableLogic.parseMeetings(detail)
                var meetingError: String? = null
                if (meetings.isEmpty()) {
                    val fallback = runCatching { post(DETAIL_PATH, ref, cookie) }
                    if (fallback.isSuccess) {
                        detail = fallback.getOrThrow()
                        meetings = TimetableLogic.parseMeetings(detail)
                    } else if (syllabus.isFailure) {
                        throw fallback.exceptionOrNull()!!
                    } else {
                        meetingError = "수업 시간을 가져오지 못했어요"
                    }
                }
                val professor = TimetableLogic.professor(syllabus.getOrNull()) ?: TimetableLogic.professor(detail)
                Result.success(TimetableLogic.CatalogCourse(meetings, professor,
                    if (syllabus.isFailure && professor == null) "교수 정보를 가져오지 못했어요" else null, meetingError))
            } catch (e: IOException) {
                Result.failure(Exception(netError(e)))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** 수강편람 '강의계획서' 탭(cc103ajax)의 원본 응답. 실패하면 사용자에게 보여 줄 문구를 담은 [IOException]. */
    fun syllabus(ref: Map<String, String>): JSONObject = try {
        post(SYLLABUS_PATH, ref, session())
    } catch (e: IOException) {
        throw IOException(netError(e))
    } catch (e: Exception) {
        throw IOException(e.message ?: "수강편람 조회 실패")
    }

    /**
     * 강의계획서 첨부(shine.snu.ac.kr 공개 다운로드)를 [out]에 쓴다. 로그인·eTL 토큰 없이 GET만 보낸다.
     * 리다이렉트는 따라가지 않고, [maxBytes]를 넘으면 중단한다. 받은 바이트 수를 돌려준다.
     */
    fun downloadAttachment(url: String, out: java.io.OutputStream, maxBytes: Long): Long {
        require(url.startsWith("https://shine.snu.ac.kr/")) { "수강편람 첨부 주소가 아니에요" }
        val c = connect(url, "GET")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("첨부파일 응답 오류($code)")
            if ((c.getHeaderField("Content-Type") ?: "").startsWith("text/html", ignoreCase = true)) throw IOException("첨부파일 대신 웹 페이지가 왔어요")
            var total = 0L
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("첨부파일이 너무 커요")
                    out.write(buf, 0, n)
                }
            }
            return total
        } finally {
            c.disconnect()
        }
    }

    private fun netError(e: IOException) = when {
        e is java.net.SocketTimeoutException -> "수강편람 응답 시간 초과"
        e.message?.startsWith("수강편람") == true || e.message?.startsWith("첨부") == true -> e.message!!
        else -> "수강편람 연결 실패"
    }

    /** 검색 페이지를 GET해 세션 쿠키를 받는다(지금은 302로 메인으로 보내지만 쿠키는 준다. 상세 조회는 이 쿠키가 없으면 302). */
    private fun session(): String {
        val c = connect(SUGANG + SEARCH_PATH, "GET")
        try {
            c.responseCode
            return c.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }.map { it.substringBefore(';') }.filter { it.isNotBlank() }.joinToString("; ")
        } finally {
            c.disconnect()
        }
    }

    private fun post(path: String, ref: Map<String, String>, cookie: String): JSONObject {
        val body = (ref + ("t_profPersNo" to "")).entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val c = connect(SUGANG + path, "POST")
        try {
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("Referer", SUGANG + SEARCH_PATH)
            if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw Exception("수강편람 응답 오류($code)")
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            return try { JSONObject(text) } catch (_: Exception) { throw Exception("수강편람 응답을 해석할 수 없어요") }
        } finally {
            c.disconnect()
        }
    }

    private fun connect(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = method
            useCaches = false
        }
}

/** 사용자가 넣은 보강·휴강(앱 내부 SharedPreferences). 30일 지난 항목은 저장할 때 정리한다. */
class TimetableChangeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("timetable_changes", Context.MODE_PRIVATE)
    @Synchronized fun load(): List<TimetableLogic.Change> = TimetableLogic.changesFromJson(prefs.getString("items", null))
    @Synchronized fun save(list: List<TimetableLogic.Change>) {
        val cutoff = LocalDate.now(EtlLogic.KST).minusDays(30)
        prefs.edit().putString("items", TimetableLogic.changesToJson(list.filter { !it.date.isBefore(cutoff) }.sortedWith(compareBy({ it.date }, { it.start ?: "" })))).apply()
    }
    @Synchronized fun add(c: TimetableLogic.Change) = save(load().filter { it.id != c.id } + c)
    @Synchronized fun delete(id: Long) = save(load().filter { it.id != id })
}

/** 주간 시간표 캐시(앱 내부 SharedPreferences). */
class TimetableStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("timetable", Context.MODE_PRIVATE)
    @Synchronized fun loadCatalog(): TimetableLogic.Weekly? = TimetableLogic.fromJson(prefs.getString("weekly", null))
    @Synchronized fun customCourses(): List<TimetableLogic.CourseMeetings> =
        TimetableLogic.fromJson(prefs.getString("custom", null))?.courses.orEmpty().mapNotNull(CustomTimetableLogic::normalize)
    @Synchronized fun withCustom(w: TimetableLogic.Weekly?): TimetableLogic.Weekly? = CustomTimetableLogic.merge(w, customCourses())
    @Synchronized fun load(): TimetableLogic.Weekly? = withCustom(loadCatalog())
    @Synchronized fun save(w: TimetableLogic.Weekly) {
        prefs.edit().putString("weekly", TimetableLogic.toJson(w.copy(courses = w.courses.filter { it.courseId >= 0 }))).apply()
    }
    @Synchronized fun saveCustom(course: TimetableLogic.CourseMeetings) {
        val valid = requireNotNull(CustomTimetableLogic.normalize(course))
        saveCustomList(customCourses().filter { it.courseId != valid.courseId } + valid)
    }
    @Synchronized fun deleteCustom(id: Long) { saveCustomList(customCourses().filter { it.courseId != id }) }
    private fun saveCustomList(courses: List<TimetableLogic.CourseMeetings>) {
        prefs.edit().putString("custom", TimetableLogic.toJson(TimetableLogic.Weekly("", 0, courses, emptyList()))).apply()
    }
}

object Timetable {
    const val CACHE_MS = 24 * 3600 * 1000L
    private const val CONTEXTS_PER_REQUEST = 10

    /** 이번 학기 과목과 수업. 하루 동안, 또는 수강 과목이 바뀔 때까지 캐시를 쓴다. 조회에 실패한 과목은 이전에 알던 수업을 남긴다. */
    fun weekly(etl: EtlClient, store: TimetableStore, nowMs: Long = System.currentTimeMillis(), force: Boolean = false): TimetableLogic.Weekly {
        val list = EtlLogic.currentCourses(etl.getAll("/api/v1/courses?enrollment_state=active&include[]=term&include[]=syllabus_body&per_page=100"))
        val ids = list.map { it.optLong("id") }.sorted().joinToString(",")
        val cached = store.loadCatalog()
        if (!force && cached != null && cached.metadataVersion >= 2 && cached.ids == ids &&
            nowMs >= cached.fetchedAt && nowMs - cached.fetchedAt < CACHE_MS) return store.withCustom(cached)!!

        val refs = list.map { TimetableLogic.catalogRef(it.str("syllabus_body")) }
        val asked = refs.indices.filter { refs[it] != null }
        val answers = CatalogClient.fetch(asked.map { refs[it]!! })
        val courses = mutableListOf<TimetableLogic.CourseMeetings>()
        val errors = mutableListOf<String>()
        list.forEachIndexed { i, c ->
            val id = c.optLong("id")
            val name = c.str("name").orEmpty()
            val k = asked.indexOf(i)
            val got = if (k >= 0) answers[k] else Result.failure(Exception("강의계획서에 수강편람 링크가 없어요"))
            got.fold(
                onSuccess = {
                    courses += TimetableLogic.refreshedCourse(id, name, it, cached?.courses?.firstOrNull { old -> old.courseId == id })
                    listOfNotNull(it.metadataError, it.meetingError).forEach { error -> errors += "${TimetableLogic.shortName(name)}: $error" }
                },
                onFailure = { e ->
                    errors += "${TimetableLogic.shortName(name)}: ${e.message}"
                    courses += TimetableLogic.refreshedCourse(id, name, null, cached?.courses?.firstOrNull { it.courseId == id })
                },
            )
        }
        val result = TimetableLogic.Weekly(ids, nowMs, courses, errors)
        store.save(result)
        return store.withCustom(result)!!
    }

    /** 캐시가 하루 안이면 네트워크 없이 그대로, 아니면 [weekly]로 새로 받는다(알림 확인·브리핑용). */
    fun cachedOrFetch(etl: EtlClient, store: TimetableStore, nowMs: Long = System.currentTimeMillis()): TimetableLogic.Weekly {
        store.load()?.let { if (nowMs - it.fetchedAt < CACHE_MS) return it }
        return weekly(etl, store, nowMs)
    }

    /** 그날(KST) eTL 캘린더 일정(종일 일정 제외)을 kind "일정"인 수업으로. */
    fun calendarClasses(etl: EtlClient, courses: List<TimetableLogic.CourseMeetings>, date: LocalDate): List<TimetableLogic.ClassRow> {
        val names = courses.associate { "course_${it.courseId}" to it }
        val out = mutableListOf<TimetableLogic.ClassRow>()
        for (chunk in names.keys.chunked(CONTEXTS_PER_REQUEST)) {
            val ctx = chunk.joinToString("&") { "context_codes[]=$it" }
            for (e in etl.getAll("/api/v1/calendar_events?type=event&start_date=$date&end_date=$date&per_page=100&$ctx")) {
                if (e.optBoolean("all_day", false)) continue
                val start = EtlLogic.parseInstant(e.str("start_at")) ?: continue
                val end = EtlLogic.parseInstant(e.str("end_at")) ?: start
                val s = ZonedDateTime.ofInstant(start, EtlLogic.KST)
                if (s.toLocalDate() != date) continue
                val hm = { t: Instant -> ZonedDateTime.ofInstant(t, EtlLogic.KST).let { "%02d:%02d".format(it.hour, it.minute) } }
                val c = names[e.str("context_code")]
                out += TimetableLogic.ClassRow(
                    hm(start), hm(end), c?.let { TimetableLogic.shortName(it.name) } ?: e.str("context_name").orEmpty(),
                    c?.courseId, e.str("location_name"), "일정", e.str("title")?.take(100),
                )
            }
        }
        return out
    }
}
