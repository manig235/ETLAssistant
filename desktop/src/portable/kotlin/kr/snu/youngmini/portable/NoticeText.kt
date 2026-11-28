package kr.snu.youngmini.portable

import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.Queries
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [NotificationPlanner.plan]의 결과.
 * @property notices 띄울 알림들. 호출자는 **실제로 띄운 뒤** 각 [Notice.firedKeys]를 [NotifyState.fired]에 기록한다.
 * @property silentKeys 알림 없이 "본 것"으로만 기록할 키들(첫 실행의 기존 공지 등).
 * @property markBaselineDone true면 호출자가 [NotifyState.announcementBaselineDone]을 true로 바꾼다.
 */
data class Plan(
    val notices: List<Notice>,
    val silentKeys: List<String> = emptyList(),
    val markBaselineDone: Boolean = false,
)

/**
 * 알림 문구 공통 도구(패키지 내부용). eTL에서 온 문자열은 신뢰할 수 없는 입력이므로
 * 알림에 넣기 전에 반드시 [clean]/[cut]을 거친다.
 */
internal object NoticeText {
    const val TITLE_MAX = 80
    const val LINE_MAX = 140
    private const val COURSE_MAX = 30
    private const val NAME_MAX = 50
    const val ETL_PREFIX = "https://myetl.snu.ac.kr/"
    private const val WEEKDAYS = "월화수목금토일"

    /** 방향 제어·폭 없는 공백 등 화면을 속일 수 있는 문자(Cf 일부). 지운다. */
    private val INVISIBLE = setOf(0x200B, 0x200C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2060, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF)

    /** 제어문자(개행 포함)와 각종 공백을 한 칸 공백으로 줄이고 앞뒤를 다듬는다. */
    fun clean(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        var lastSpace = true
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in INVISIBLE) continue
            val type = Character.getType(cp)
            val space = Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            if (space) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.appendCodePoint(cp)
                lastSpace = false
            }
        }
        return sb.toString().trimEnd()
    }

    /** [max]자(말줄임표 포함)로 자른다. 서로게이트 쌍을 가운데서 자르지 않는다. */
    fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = (max - 1).coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    fun safe(s: String?, max: Int): String = cut(clean(s), max)

    fun course(a: String?): String = safe(a, COURSE_MAX)
    fun name(a: String?): String = safe(a, NAME_MAX)

    /** https://myetl.snu.ac.kr/ 로 시작하고 공백·제어문자가 없을 때만 통과. */
    fun safeUrl(url: String?): String? {
        val u = url?.trim() ?: return null
        if (!u.startsWith(ETL_PREFIX)) return null
        if (u.any { Character.isISOControl(it) || Character.isWhitespace(it) }) return null
        return u
    }

    /** 내 일정은 eTL로 보내지 않는다. eTL 과제 링크가 없거나 부적절하면 ID로 복원한다. */
    fun assignmentUrl(a: AssignmentItem): String? {
        if (a.courseId <= 0) return null
        return safeUrl(a.url) ?: if (a.assignmentId > 0)
            "${ETL_PREFIX}courses/${a.courseId}/assignments/${a.assignmentId}"
        else "${ETL_PREFIX}courses/${a.courseId}"
    }

    fun announcementUrl(a: AnnouncementItem): String = safeUrl(a.url) ?: when {
        a.courseId > 0 && a.id > 0 -> "${ETL_PREFIX}courses/${a.courseId}/discussion_topics/${a.id}"
        a.courseId > 0 -> "${ETL_PREFIX}courses/${a.courseId}"
        else -> ETL_PREFIX
    }

    /** 한 강의의 묶음은 강의 화면으로, 여러 강의는 eTL 홈으로 연결한다. 내 일정만 있으면 앱을 연다. */
    fun collectionUrl(courseIds: List<Long>): String? {
        val etl = courseIds.filter { it > 0 }.distinct()
        return when (etl.size) {
            0 -> null
            1 -> "${ETL_PREFIX}courses/${etl.single()}"
            else -> ETL_PREFIX
        }
    }

    /** KST 고정 `11/23(월) 15:00`. */
    fun dateLabel(t: Instant?): String {
        if (t == null) return "마감일 없음"
        val d = ZonedDateTime.ofInstant(t, EtlLogic.KST)
        return "%02d/%02d(%s) %02d:%02d".format(d.monthValue, d.dayOfMonth, WEEKDAYS[d.dayOfWeek.value - 1], d.hour, d.minute)
    }

    /** 지정한 시간대 기준 `11/19(목)`. */
    fun dayLabel(t: Instant, zone: ZoneId): String {
        val d = ZonedDateTime.ofInstant(t, zone)
        return "%02d/%02d(%s)".format(d.monthValue, d.dayOfMonth, WEEKDAYS[d.dayOfWeek.value - 1])
    }

    /** `• 11/23(월) 15:00 · 강의 — 과제 (1일 2시간 남음)` + 오프라인이면 ` (오프라인)`. [prefix]는 `[오늘] ` 같은 머리말. */
    fun dueLine(a: AssignmentItem, now: Instant, prefix: String = ""): String {
        val rem = EtlLogic.remaining(a.due, now)
        return "• $prefix${dateLabel(a.due)} · ${course(a.courseName)} — ${name(a.name)} ($rem)" +
            if (a.online) "" else " (오프라인)"
    }

    fun annLine(a: AnnouncementItem): String = "• [${course(a.courseName)}] ${safe(a.title, NAME_MAX + 30)}"

    /** [lines]를 [max]줄까지 이어 붙이고, 넘치면 `외 N건`을 덧붙인다. */
    fun joinLimited(lines: List<String>, max: Int): String {
        if (lines.size <= max) return lines.joinToString("\n")
        return lines.take(max).joinToString("\n") + "\n외 ${lines.size - max}건"
    }
}
