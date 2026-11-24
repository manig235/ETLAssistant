package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.Queries
import kr.snu.etlassistant.etl.Queries.AnnCategory
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 공지에서 "내 일정에 등록"/"시간표에 반영"(2026-11-24, 사용자 요청). 시험·과제·동영상 강의 공지는 내 마감 추가 창을,
 * 보강·휴강 공지는 시간표의 보강·휴강 추가 창을 미리 채워 연다. AI 없이 키워드와 정해진 날짜 모양만 본다.
 * 날짜(+시각)가 **정확히 하나**만 잡힐 때만 미리 넣고(사용자가 확인), 아니면 비운다(지어내지 않음).
 * 저장은 사용자가 창에서 직접 누를 때만 한다. 같은 공지로 이미 넣었으면 [MyDeadline.sourceAnnouncementId]·
 * [kr.snu.etlassistant.etl.TimetableLogic.Change.sourceAnnouncementId]로 알아본다.
 * 창의 "✨ 비서가 공지 읽고 채우기"를 누르면 [prefill]을 채팅 입력창에 채운다(공지 내용은 이때만 비서로 간다).
 */
data class ScheduleDraft(
    val announcementId: Long,
    val kind: MyKind,
    val title: String,
    val course: String,
    val memo: String,
    val supplies: String = "",
    /** 공지에서 찾은 시작(시험·보강)·마감(과제·동영상) 시각. 하나만 확실할 때만. */
    val start: Instant? = null,
    /** 공지에서 찾은 끝 시각(시험·보강, "19:00~21:00"). */
    val end: Instant? = null,
    /** "✨ 비서가 공지 읽고 채우기"를 누르면 채팅 입력창에 채울 요청(공지 ID 포함, 보내지는 않는다). */
    val prefill: String = "",
    val category: AnnCategory = if (kind == MyKind.EXAM) AnnCategory.EXAM else AnnCategory.ASSIGNMENT,
    /** 휴강·보강·동영상 강의(대면 수업 대체)의 그날(시각 없이도). 하나만 확실할 때만. */
    val day: LocalDate? = null,
    /** 동영상 강의 공지에 "대면 수업 없음/대체" 단서가 있어 그날을 휴강으로 표시할 수 있는가. */
    val replacesClass: Boolean = false,
) {
    val foundDate: Boolean get() = start != null || (category.timetable && day != null)
    val timetable: Boolean get() = category.timetable
    val actionLabel: String get() = if (timetable) ScheduleDrafts.TIMETABLE_ACTION else ScheduleDrafts.ACTION

    fun toJson(): String = JSONObject().put("ann", announcementId).put("kind", kind.id).put("title", title).put("course", course).put("memo", memo)
        .put("supplies", supplies).put("start", start?.toEpochMilli() ?: JSONObject.NULL).put("end", end?.toEpochMilli() ?: JSONObject.NULL)
        .put("prefill", prefill).put("cat", category.name).put("day", day?.toString() ?: JSONObject.NULL).put("replaces", replacesClass).toString()

    companion object {
        fun fromJson(s: String?): ScheduleDraft? = try {
            val o = JSONObject(s ?: return null)
            val kind = MyKind.parse(o.optString("kind")) ?: MyKind.DEADLINE
            ScheduleDraft(
                o.optLong("ann"), kind,
                o.optString("title").take(MyDeadlines.TITLE_MAX), o.optString("course").take(MyDeadlines.COURSE_MAX), o.optString("memo").take(MyDeadlines.MEMO_MAX),
                o.optString("supplies").take(MyDeadlines.SUPPLIES_MAX),
                if (o.isNull("start")) null else Instant.ofEpochMilli(o.optLong("start")),
                if (o.isNull("end")) null else Instant.ofEpochMilli(o.optLong("end")),
                o.optString("prefill").take(500),
                runCatching { AnnCategory.valueOf(o.optString("cat")) }.getOrElse { if (kind == MyKind.EXAM) AnnCategory.EXAM else AnnCategory.ASSIGNMENT },
                if (!o.has("day") || o.isNull("day")) null else runCatching { LocalDate.parse(o.optString("day")) }.getOrNull(),
                o.optBoolean("replaces", false),
            )
        } catch (_: Exception) {
            null
        }
    }
}

object ScheduleDrafts {
    const val ACTION = "내 일정에 등록"
    const val TIMETABLE_ACTION = "시간표에 반영"
    const val ASSIGNMENT_HINT = "eTL 과제로 안 올라온 마감이면 등록해 두세요."
    const val FOUND_HINT = "공지에서 찾은 날짜예요. 맞는지 확인해 주세요."
    const val NOT_FOUND_HINT = "공지에서 날짜를 하나로 정하지 못했어요. 날짜와 시각을 직접 골라 주세요."
    const val ASK_HINT = "공지에서 날짜를 찾지 못했어요. 비서가 공지를 읽고 채워 드릴까요?"
    const val ASK_LINK = "✨ 비서가 공지 읽고 채우기"

    /** 시험 공지면 시험, 과제·동영상 공지면 마감, 그 밖(보강·휴강 포함)은 null. */
    fun kindOf(title: String?, text: String?): MyKind? = when (Queries.announcementCategory(title, text)) {
        AnnCategory.EXAM -> MyKind.EXAM
        AnnCategory.ASSIGNMENT, AnnCategory.VIDEO -> MyKind.DEADLINE
        else -> null
    }

    /** 공지 하나로 만든 초안. 시험·과제·동영상 강의·보강·휴강 공지가 아니면 null. */
    fun fromAnnouncement(a: AnnouncementItem): ScheduleDraft? {
        val cat = Queries.announcementCategory(a.title, a.text) ?: return null
        val kind = if (cat == AnnCategory.EXAM) MyKind.EXAM else MyKind.DEADLINE
        val course = kr.snu.etlassistant.etl.TimetableLogic.shortName(a.courseName).take(MyDeadlines.COURSE_MAX)
        val found = single(a.text, a.postedAt) ?: single(a.title, a.postedAt)
        val days = (findDays(a.title, a.postedAt) + findDays(a.text, a.postedAt)).distinct()
        val link = NoticeText.announcementUrl(a)
        val cleaned = cleanTitle(a.title, a.courseName, course)
        val title = when (cat) {
            AnnCategory.VIDEO -> "[동영상] " + listOf(course, cleaned).filter { it.isNotEmpty() }.joinToString(" ")
            AnnCategory.EXAM -> cleaned.ifEmpty { "시험" }
            AnnCategory.ASSIGNMENT -> cleaned.ifEmpty { "과제" }
            AnnCategory.MAKEUP, AnnCategory.CANCEL -> cleaned
        }.take(MyDeadlines.TITLE_MAX)
        val day = when (cat) {
            AnnCategory.MAKEUP -> found?.first?.atZone(EtlLogic.KST)?.toLocalDate() ?: days.singleOrNull()
            AnnCategory.CANCEL -> days.singleOrNull()
            // 동영상: 대면 수업 날짜. 날짜가 하나면 그날, 둘이고 하나가 시청 기한이면 다른 날(앞 날).
            AnnCategory.VIDEO -> days.singleOrNull() ?: days.takeIf { it.size == 2 && found != null }?.firstOrNull { it != found!!.first.atZone(EtlLogic.KST).toLocalDate() }
            else -> null
        }
        return ScheduleDraft(
            announcementId = a.id,
            kind = kind,
            title = title,
            course = course,
            memo = when (cat) {
                AnnCategory.ASSIGNMENT, AnnCategory.VIDEO -> (if (course.isNotEmpty()) "$course · " else "") + "공지: $link"
                AnnCategory.MAKEUP, AnnCategory.CANCEL -> "공지: $link"
                else -> "공지: $link"
            }.take(MyDeadlines.MEMO_MAX),
            supplies = if (cat == AnnCategory.EXAM) MyDeadlines.normalizeSupplies(Queries.suppliesLines(a.text).joinToString(", ")) else "",
            start = found?.first?.takeIf { cat != AnnCategory.CANCEL },
            end = found?.second.takeIf { cat == AnnCategory.EXAM || cat == AnnCategory.MAKEUP },
            prefill = prefill(a, cat),
            category = cat,
            day = day,
            replacesClass = cat == AnnCategory.VIDEO && Queries.replacesClass(a.text) && day != null,
        )
    }

    /** 비서에게 맡길 요청: 공지 ID를 붙여 비서가 my_deadline_add·timetable_change_add의 source_announcement_id로 남기게 한다. */
    fun prefill(a: AnnouncementItem, cat: AnnCategory): String {
        val named = "${NoticeText.course(a.courseName)} ‘${NoticeText.safe(a.title, 60)}’ 공지 보고 "
        val ask = when (cat) {
            AnnCategory.EXAM -> return NotificationPlanner.examPrefill(listOf(a)) + " (공지 ID ${a.id})"
            AnnCategory.ASSIGNMENT -> return NotificationPlanner.assignmentPrefill(listOf(a)) + " (공지 ID ${a.id})"
            AnnCategory.VIDEO -> "동영상 강의 시청 기한을 내 일정에 넣고, 대면 수업이 없으면 시간표에 휴강으로 표시해줘"
            AnnCategory.MAKEUP -> "보강을 시간표에 넣어줘"
            AnnCategory.CANCEL -> "휴강을 시간표에 넣어줘"
        }
        return "$named$ask (공지 ID ${a.id})"
    }

    private val PREFIX = Regex("^\\s*(?:[\\[(【][^\\])】]{0,30}[\\])】]\\s*)+")
    private val NOTICE_WORD = Regex("^\\s*(?:공지|안내|알림|필독|중요)\\s*[:：-]?\\s*")

    /** 공지 제목 정리: 앞의 `[과목명]`·`(공지)` 같은 괄호 머리말, "공지:" 머리말, 앞의 과목명을 뗀다. */
    fun cleanTitle(title: String, courseName: String, shortCourse: String): String {
        var t = NoticeText.clean(title)
        repeat(3) {
            t = t.replace(PREFIX, "").replace(NOTICE_WORD, "")
            for (c in listOf(NoticeText.clean(courseName), shortCourse).filter { it.length >= 2 }) {
                if (t.startsWith(c)) t = t.removePrefix(c).trimStart(' ', '-', ':', '·', '|')
            }
        }
        return t.trim()
    }

    /** 공지 본문의 날짜(시각 없이도) 전부. 연도가 없으면 [posted] 기준 가장 가까운 미래. 휴강·동영상 강의의 그날에 쓴다. */
    fun findDays(text: String?, posted: Instant): List<LocalDate> {
        val s = text.orEmpty()
        val postedDate = posted.atZone(EtlLogic.KST).toLocalDate()
        val out = linkedSetOf<LocalDate>()
        val used = mutableListOf<IntRange>()
        for (re in DATE_PATTERNS) {
            for (m in re.findAll(s)) {
                if (used.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                // MM/DD·MM.DD 모양은 뒤에 요일이나 시각이 붙을 때만(점수·소수와 헷갈리지 않게).
                if (re === DATE_PATTERNS[2] && !Regex("^$WD\\s*(?:\\d|[(（]|오전|오후|수업|휴강|보강|강의|$)").containsMatchIn(s.substring(m.range.last + 1).take(12))) continue
                val date = dateOf(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull() ?: continue, m.groupValues[3].toIntOrNull() ?: continue, postedDate) ?: continue
                used += m.range
                out += date
            }
        }
        return out.toList()
    }


    // ---- 날짜·시각 찾기 ---------------------------------------------------------

    private const val WD = "(?:\\s*\\(?\\s*[월화수목금토일](?:요일)?\\s*\\)?)?"
    private const val TIME = "(?:(오전|오후|AM|PM|am|pm)\\s*)?(\\d{1,2})(?::(\\d{2})|\\s*시(?:\\s*(\\d{1,2})\\s*분)?(?:\\s*(반))?)"
    private val DATE_PATTERNS = listOf(
        // 2026-11-28, 2026.11.28, 2026/11/28
        Regex("(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})\\.?"),
        // 2026년 11월 28일, 11월 28일
        Regex("(?:(\\d{4})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"),
        // 11/28, 11.28.
        Regex("(?<![\\d.])()(\\d{1,2})\\s*[/.]\\s*(\\d{1,2})\\.?(?![\\d])"),
    )
    private val TIME_RE = Regex("^$WD\\s*,?\\s*$TIME(?:\\s*[~∼〜\\-–]\\s*$TIME)?")

    private fun hour(ampm: String, h: Int): Int? {
        val a = ampm.lowercase()
        return when {
            h !in 0..24 -> null
            a == "오후" || a == "pm" -> if (h < 12) h + 12 else h
            a == "오전" || a == "am" -> if (h == 12) 0 else h
            else -> h
        }?.takeIf { it in 0..23 }
    }

    private fun time(g: List<String>, base: Int): LocalTime? {
        val h = g.getOrNull(base + 1)?.toIntOrNull() ?: return null
        val m = g.getOrNull(base + 2)?.toIntOrNull() ?: g.getOrNull(base + 3)?.toIntOrNull() ?: if (g.getOrNull(base + 4) == "반") 30 else 0
        val hh = hour(g.getOrNull(base).orEmpty(), h) ?: return null
        return if (m in 0..59) LocalTime.of(hh, m) else null
    }

    /**
     * 본문에서 "날짜 + 시각"을 모두 찾는다(시각 없는 날짜는 세지 않는다). 연도가 없으면 [posted] 날짜(KST) 기준 가장 가까운 미래.
     * 끝 시각("19:00~21:00")이 있으면 함께. 같은 시각은 하나로 센다.
     */
    fun findAll(text: String?, posted: Instant): List<Pair<Instant, Instant?>> {
        val s = text.orEmpty()
        val postedDate = posted.atZone(EtlLogic.KST).toLocalDate()
        val out = linkedMapOf<Instant, Instant?>()
        val used = mutableListOf<IntRange>()
        for (re in DATE_PATTERNS) {
            for (m in re.findAll(s)) {
                if (used.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                val y = m.groupValues[1].toIntOrNull()
                val mo = m.groupValues[2].toIntOrNull() ?: continue
                val d = m.groupValues[3].toIntOrNull() ?: continue
                val date = dateOf(y, mo, d, postedDate) ?: continue
                val tm = TIME_RE.find(s.substring(m.range.last + 1)) ?: continue
                val g = tm.groupValues
                val start = time(g, 1) ?: continue
                val end = time(g, 6)
                used += m.range
                val st = LocalDateTime.of(date, start).atZone(EtlLogic.KST).toInstant()
                val en = end?.let { e -> LocalDateTime.of(date, e).atZone(EtlLogic.KST).toInstant().let { if (it.isAfter(st)) it else null } }
                if (st !in out || out[st] == null) out[st] = en
            }
        }
        return out.entries.map { it.key to it.value }
    }

    /** 정확히 하나일 때만. */
    fun single(text: String?, posted: Instant): Pair<Instant, Instant?>? = findAll(text, posted).singleOrNull()

    private fun dateOf(y: Int?, mo: Int, d: Int, posted: LocalDate): LocalDate? {
        if (mo !in 1..12 || d !in 1..31) return null
        if (y != null) return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
        val same = runCatching { LocalDate.of(posted.year, mo, d) }.getOrNull()
        return if (same != null && !same.isBefore(posted)) same else runCatching { LocalDate.of(posted.year + 1, mo, d) }.getOrNull()
    }

    /** 여러 공지 중 첫 시험·과제 공지의 초안(묶음 알림용). */
    fun firstOf(items: List<AnnouncementItem>): ScheduleDraft? = items.firstNotNullOfOrNull { fromAnnouncement(it) }
}
