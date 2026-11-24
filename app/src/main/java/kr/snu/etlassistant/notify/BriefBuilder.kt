package kr.snu.etlassistant.notify

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 정기 브리핑 본문 생성(순수 함수, AI 미사용). */
object BriefBuilder {
    private const val UPCOMING_DAYS = 7L
    private const val UPCOMING_LINES = 8
    private const val MISSING_LINES = 5
    private const val ANN_LINES = 5
    private const val EMPTY = "급한 마감, 누락, 새 공지가 없어요"

    fun build(
        snapshot: Snapshot,
        now: Instant,
        settings: NotifySettings,
        zone: ZoneId = ZoneId.systemDefault(),
        lookbackHours: Int = 24,
        /** 오늘 수업 줄(시간표). 비면 섹션을 넣지 않는다. */
        todayClasses: List<String> = emptyList(),
        /** eTL 조회가 실패해 받아 둔 과제 정보를 썼으면 그 저장 시각. 맨 끝에 "N시간 전 정보" 줄을 붙인다. */
        staleSince: Instant? = null,
        /** 7일 안의 시험 줄([MyDeadlines.examLines]). 비면 섹션을 넣지 않는다. */
        exams: List<String> = emptyList(),
        /** 오늘 캘린더 일정 줄(내 캘린더 일정 보기, [kr.snu.etlassistant.calendar.CalendarReadLogic.briefLines]). */
        todayEvents: List<String> = emptyList(),
    ): Notice {
        val today = now.atZone(zone).toLocalDate()

        // 마감 예정: now <= due <= now+7일, 미제출(누락 표시된 과제는 아래 누락 섹션에서만 센다).
        val limit = now.plus(Duration.ofDays(UPCOMING_DAYS))
        val upcoming = snapshot.assignments
            .filter { a -> a.due.let { it != null && !it.isBefore(now) && !it.isAfter(limit) } && !a.submitted && !a.missing }
            .sortedWith(compareBy<AssignmentItem>({ it.due }, { it.courseName }, { it.assignmentId }))
        // 누락: 최근에 놓친 것을 먼저 보인다(마감 없는 항목은 맨 뒤).
        val missing = snapshot.assignments
            .filter { it.missing && !it.submitted }
            .sortedWith(compareByDescending<AssignmentItem> { it.due ?: Instant.MIN }.thenBy { it.assignmentId })
        val since = now.minus(Duration.ofHours(lookbackHours.toLong().coerceAtLeast(0)))
        val recent = snapshot.announcements
            .filter { !it.postedAt.isBefore(since) }
            .sortedWith(compareByDescending<AnnouncementItem> { it.postedAt }.thenByDescending { it.id })

        val title = "eTL 브리핑 · ${NoticeText.dayLabel(now, zone)}"
        val allEmpty = upcoming.isEmpty() && missing.isEmpty() && recent.isEmpty()
        val counts = if (allEmpty) EMPTY else "마감 임박 ${upcoming.size}건 · 누락 ${missing.size}건 · 새 공지 ${recent.size}건"
        // 오늘·내일 시험은 맨 앞에 알린다(시험 당일/전날 강조).
        val soonExams = exams.count { it.startsWith("• [오늘]") || it.startsWith("• [내일]") }
        val text = listOfNotNull(
            soonExams.takeIf { it > 0 }?.let { "시험 ${it}개(오늘·내일)" },
            todayClasses.size.takeIf { it > 0 }?.let { "수업 ${it}개" },
            todayEvents.size.takeIf { it > 0 }?.let { "일정 ${it}개" },
        ).joinToString(" · ").let { if (it.isEmpty()) counts else "$it · $counts" }

        val sections = mutableListOf<String>()
        if (exams.isNotEmpty()) sections += "■ 시험 (7일 이내)\n" + NoticeText.joinLimited(exams, UPCOMING_LINES)
        if (todayClasses.isNotEmpty()) sections += "■ 오늘 수업\n" + todayClasses.joinToString("\n")
        if (todayEvents.isNotEmpty()) sections += "■ 오늘 일정\n" + NoticeText.joinLimited(todayEvents, UPCOMING_LINES)
        if (upcoming.isNotEmpty()) {
            val lines = upcoming.map { a ->
                val isToday = a.due!!.atZone(zone).toLocalDate() == today
                NoticeText.dueLine(a, now, if (isToday) "[오늘] " else "")
            }
            sections += "■ 마감 예정 (7일 이내, 미제출)\n" + NoticeText.joinLimited(lines, UPCOMING_LINES)
        }
        if (missing.isNotEmpty()) {
            val lines = missing.map { a ->
                "• ${NoticeText.dateLabel(a.due)} · ${NoticeText.course(a.courseName)} — ${NoticeText.name(a.name)}"
            }
            sections += "■ 누락 (마감 지남, 미제출)\n" + NoticeText.joinLimited(lines, MISSING_LINES)
        }
        if (recent.isNotEmpty()) {
            sections += "■ 새 공지 (최근 ${lookbackHours}시간)\n" + NoticeText.joinLimited(recent.map { NoticeText.annLine(it) }, ANN_LINES)
        }
        if (allEmpty && exams.isEmpty() && todayEvents.isEmpty()) sections += EMPTY
        if (staleSince != null) sections += SnapshotFallback.staleLine(staleSince, now)
        else if (snapshot.errors.isNotEmpty()) sections += "※ 일부 항목을 조회하지 못했어요"

        return Notice(
            channel = Channel.BRIEF,
            title = NoticeText.cut(title, NoticeText.TITLE_MAX),
            text = text,
            bigText = sections.joinToString("\n\n"),
            url = null,
            tag = "brief",
            firedKeys = emptyList(),
        )
    }
}
