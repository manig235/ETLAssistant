package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.EtlLogic
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

/**
 * 마감·공지 알림 계획(순수 함수).
 * [state]는 읽기만 한다(바꾸지 않는다).
 */
object NotificationPlanner {
    private const val DEADLINE_BIG_LINES = 6
    private const val ANN_BIG_LINES = 8
    private const val ANN_INDIVIDUAL_MAX = 3
    private const val ANN_BODY_MAX = 300

    fun plan(
        snapshot: Snapshot,
        state: NotifyState,
        now: Instant,
        settings: NotifySettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        val local = ZonedDateTime.ofInstant(now, zone)
        val quiet = Quiet.isQuiet(local.hour * 60 + local.minute, settings)

        val notices = mutableListOf<Notice>()
        val silent = mutableListOf<String>()
        var baseline = false

        if (!quiet && settings.deadlineAlerts) notices += deadlineNotices(snapshot, state, now, settings)

        if (settings.announcementAlerts) {
            if (!state.announcementBaselineDone) {
                // 첫 확인: 기존 공지는 알리지 않고 "본 것"으로만 기록한다(방해 금지와 무관).
                silent += snapshot.announcements.map { "ann:${it.id}" }.distinct()
                baseline = true
            } else if (!quiet) {
                notices += announcementNotices(snapshot, state)
            }
        }
        return Plan(notices, silent, baseline)
    }

    // ---- 마감 ---------------------------------------------------------------

    private fun deadlineNotices(snapshot: Snapshot, state: NotifyState, now: Instant, settings: NotifySettings): List<Notice> {
        val windows = settings.deadlineWindowsHours.filter { it > 0 }.distinct().sorted()
        if (windows.isEmpty()) return emptyList()

        // 창 → (과제, 함께 기록할 키들)
        val groups = sortedMapOf<Int, MutableList<AssignmentItem>>()
        val keys = linkedMapOf<Int, LinkedHashSet<String>>()
        val seen = HashSet<Pair<Long, Long>>()
        for (a in snapshot.assignments) {
            val due = a.due ?: continue
            if (a.submitted || a.missing || !due.isAfter(now)) continue
            if (!seen.add(a.courseId to a.assignmentId)) continue
            val left = Duration.between(now, due)
            val w = windows.firstOrNull { left <= Duration.ofHours(it.toLong()) } ?: continue
            if (state.fired.containsKey(key(a, w))) continue
            groups.getOrPut(w) { mutableListOf() } += a
            val ks = keys.getOrPut(w) { linkedSetOf() }
            ks += key(a, w)
            windows.filter { it > w }.forEach { ks += key(a, it) } // 더 느슨한 창은 다시 알리지 않는다
        }

        return groups.map { (w, list) ->
            val items = list.sortedWith(compareBy<AssignmentItem>({ it.due }, { it.courseName }, { it.assignmentId }))
            val fired = keys.getValue(w).toList()
            if (items.size == 1) single(items[0], now, fired) else group(items, w, now, fired)
        }
    }

    private fun key(a: AssignmentItem, w: Int) = "deadline:${a.courseId}:${a.assignmentId}:$w"

    private fun single(a: AssignmentItem, now: Instant, fired: List<String>): Notice {
        val title = NoticeText.cut("마감 임박 · " + NoticeText.clean(a.courseName), NoticeText.TITLE_MAX)
        val text = NoticeText.cut(
            "${NoticeText.name(a.name)} · ${NoticeText.dateLabel(a.due)} (${EtlLogic.remaining(a.due, now)})" +
                if (a.online) "" else " (오프라인)",
            NoticeText.LINE_MAX,
        )
        return Notice(
            channel = Channel.DEADLINE,
            title = title,
            text = text,
            bigText = NoticeText.dueLine(a, now),
            url = NoticeText.safeUrl(a.url),
            tag = "deadline:${a.courseId}:${a.assignmentId}",
            firedKeys = fired,
        )
    }

    private fun group(items: List<AssignmentItem>, w: Int, now: Instant, fired: List<String>): Notice {
        val first = items[0]
        val head = "${NoticeText.course(first.courseName)} — ${NoticeText.name(first.name)} (${EtlLogic.remaining(first.due, now)})"
        val text = NoticeText.cut(head + " 외 ${items.size - 1}건", NoticeText.LINE_MAX)
        return Notice(
            channel = Channel.DEADLINE,
            title = NoticeText.cut("마감 임박 ${items.size}건 · ${windowLabel(w)} 이내", NoticeText.TITLE_MAX),
            text = text,
            bigText = NoticeText.joinLimited(items.map { NoticeText.dueLine(it, now) }, DEADLINE_BIG_LINES),
            url = null,
            tag = "deadline:w$w",
            firedKeys = fired,
        )
    }

    /** 48시간 이상이고 24의 배수면 `N일`, 아니면 `N시간`. */
    private fun windowLabel(w: Int) = if (w >= 48 && w % 24 == 0) "${w / 24}일" else "${w}시간"

    // ---- 공지 ---------------------------------------------------------------

    private fun announcementNotices(snapshot: Snapshot, state: NotifyState): List<Notice> {
        val fresh = snapshot.announcements
            .filter { !state.fired.containsKey("ann:${it.id}") }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<AnnouncementItem> { it.postedAt }.thenByDescending { it.id })
        if (fresh.isEmpty()) return emptyList()

        if (fresh.size <= ANN_INDIVIDUAL_MAX) {
            return fresh.map { a ->
                val body = NoticeText.clean(a.text)
                Notice(
                    channel = Channel.ANNOUNCEMENT,
                    title = NoticeText.cut(NoticeText.clean(a.courseName) + " · 새 공지", NoticeText.TITLE_MAX),
                    text = NoticeText.safe(a.title, NoticeText.LINE_MAX),
                    bigText = if (body.isEmpty()) null else NoticeText.cut(body, ANN_BODY_MAX),
                    url = NoticeText.safeUrl(a.url),
                    tag = "ann:${a.id}",
                    firedKeys = listOf("ann:${a.id}"),
                )
            }
        }
        val lines = fresh.map { NoticeText.annLine(it) }
        return listOf(
            Notice(
                channel = Channel.ANNOUNCEMENT,
                title = "새 공지 ${fresh.size}건",
                text = NoticeText.cut(lines[0].removePrefix("• ") + " 외 ${fresh.size - 1}건", NoticeText.LINE_MAX),
                bigText = NoticeText.joinLimited(lines, ANN_BIG_LINES),
                url = null,
                tag = "ann:summary",
                firedKeys = fresh.map { "ann:${it.id}" },
            ),
        )
    }
}
