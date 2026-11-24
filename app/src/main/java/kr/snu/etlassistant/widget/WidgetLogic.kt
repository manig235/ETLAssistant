package kr.snu.etlassistant.widget

import kr.snu.etlassistant.chat.TimetableGridLogic
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.notify.AssignmentItem
import kr.snu.etlassistant.notify.MyDeadline
import kr.snu.etlassistant.notify.MyDeadlines
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** 위젯은 내 일정의 기간/지난 마감 필터와 독립적으로, 앞으로 할 일만 마감순으로 보여 준다. */
object DeadlineWidgetLogic {
    data class Item(
        val key: String, val title: String, val source: String, val due: Instant,
        val url: String? = null, val mineId: Long? = null,
        /** 반복 내 마감이면 그 회차 날짜(KST). 완료 버튼이 이 회차만 완료로 표시한다. */
        val mineDate: LocalDate? = null,
        /** 시험이면 완료 버튼을 두지 않는다. */
        val exam: Boolean = false,
    )

    fun items(assignments: List<AssignmentItem>, mine: List<MyDeadline>, now: Instant): List<Item> =
        (assignments.mapNotNull { a ->
            val due = a.due ?: return@mapNotNull null
            if (a.submitted || !due.isAfter(now)) return@mapNotNull null
            Item("etl-${a.courseId}-${a.assignmentId}", a.name, TimetableLogic.shortName(a.courseName), due,
                assignmentUrl(a))
        } + MyDeadlines.active(mine, now).filter { !it.done && it.due.isAfter(now) }.map {
            // 반복 마감은 다음 회차 하나만 보인다.
            // 시험은 "[시험] 과목 제목"과 장소로 구분한다(시작 시각까지 보인다).
            if (it.isExam) Item("exam-${it.id}", MyDeadlines.examTitle(it), listOf("시험", it.location).filter { s -> s.isNotBlank() }.joinToString(" · "), it.due, mineId = it.id, exam = true)
            else Item("mine-${it.id}" + (it.occurrence?.let { o -> "-$o" } ?: ""), it.title, "내 마감", it.due, mineId = it.id, mineDate = it.occurrence)
        }).distinctBy { it.key }.sortedWith(compareBy({ it.due }, { it.key }))

    private fun assignmentUrl(a: AssignmentItem): String {
        // 과제 링크는 eTL 안에서만 연다. 응답에 링크가 없으면 과제 ID로 복원한다.
        val fallback = "https://myetl.snu.ac.kr/courses/${a.courseId}/assignments/${a.assignmentId}"
        val uri = runCatching { java.net.URI(a.url ?: return fallback) }.getOrNull() ?: return fallback
        return if (uri.scheme == "https" && uri.host == "myetl.snu.ac.kr" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)) uri.toString() else fallback
    }

    /** 별도의 개수 상한 없이 실제 높이와 글자 크기에 들어가는 만큼 채운다. */
    fun capacity(heightDp: Int, fontScale: Float, compact: Boolean = false): Int {
        val scale = fontScale.coerceAtLeast(1f)
        val available = heightDp - if (compact) 12f else 14f + 22f * scale
        val row = (if (compact) 32f else 48f) * scale
        return ((available + 1f) / (row + 1f)).toInt().coerceAtLeast(1)
    }

    fun dueLabel(due: Instant, now: Instant): String {
        val at = due.atZone(EtlLogic.KST)
        val date = when (at.toLocalDate()) {
            now.atZone(EtlLogic.KST).toLocalDate() -> "오늘"
            now.atZone(EtlLogic.KST).toLocalDate().plusDays(1) -> "내일"
            else -> "${at.monthValue}/${at.dayOfMonth}"
        }
        return "$date ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    fun remaining(due: Instant, now: Instant): String {
        val minutes = Duration.between(now, due).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 1 -> "곧 마감"
            minutes < 60 -> "${minutes}분 남음"
            minutes < 1440 -> "${minutes / 60}시간 ${minutes % 60}분 남음"
            else -> "${minutes / 1440}일 ${minutes % 1440 / 60}시간 남음"
        }
    }

    /** 마감 경계와 KST 자정에는 저장된 정보만 다시 그린다. 네트워크 조회는 하지 않는다. */
    fun nextBoundary(items: List<Item>, now: Instant): Instant = minOf(
        now.atZone(EtlLogic.KST).toLocalDate().plusDays(1).atStartOfDay(EtlLogic.KST).toInstant(),
        items.firstOrNull { it.due.isAfter(now) }?.due ?: Instant.MAX,
    )
}

object TimetableWidgetLogic {
    data class Entry(val date: LocalDate, val row: TimetableLogic.ClassRow, val cancelled: Boolean)
    data class Week(
        val monday: LocalDate, val entries: List<Entry>, val grid: TimetableGridLogic.Grid,
        val byGridId: Map<Long, Entry>,
    )

    data class NextClass(val entry: Entry, val startsAt: Instant, val switchesAt: Instant)

    /** 시작 30분 뒤(더 짧은 수업은 종료 때) 다음 수업으로 넘긴다. 휴강은 제외한다. */
    fun nextClass(weekly: TimetableLogic.Weekly?, changes: List<TimetableLogic.Change>, now: Instant): NextClass? {
        val today = now.atZone(EtlLogic.KST).toLocalDate()
        return (0L..7L).asSequence().flatMap { offset ->
            val date = today.plusDays(offset)
            TimetableLogic.day(weekly, date, changes).classes.asSequence().mapNotNull { row ->
                val start = runCatching { date.atTime(LocalTime.parse(row.start)).atZone(EtlLogic.KST).toInstant() }.getOrNull()
                    ?: return@mapNotNull null
                val end = runCatching { date.atTime(LocalTime.parse(row.end)).atZone(EtlLogic.KST).toInstant() }.getOrNull()
                    ?: return@mapNotNull null
                if (!end.isAfter(start)) return@mapNotNull null
                val cutoff = minOf(end, start.plusSeconds(30 * 60))
                if (cutoff.isAfter(now)) NextClass(Entry(date, row, false), start, cutoff) else null
            }
        }.minByOrNull { it.startsAt }
    }

    fun week(weekly: TimetableLogic.Weekly?, changes: List<TimetableLogic.Change>, today: LocalDate): Week {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val entries = (0L..6L).flatMap { offset ->
            val date = monday.plusDays(offset)
            val day = TimetableLogic.day(weekly, date, changes)
            day.classes.map { Entry(date, it, false) } + day.cancelled.map { Entry(date, it, true) }
        }
        // 같은 과목의 정규/보강 블록도 각각 배치한다. 원래 ID는 색상과 접근성 설명에 유지한다.
        val byId = entries.mapIndexed { i, entry -> (i + 1L) to entry }.toMap()
        val grid = TimetableGridLogic.build(byId.map { (id, entry) ->
            TimetableLogic.CourseMeetings(id, entry.row.course, listOf(TimetableLogic.Meeting(
                TimetableLogic.weekdayOf(entry.date), entry.row.start, entry.row.end, entry.row.room,
            )))
        })
        return Week(monday, entries, grid, byId)
    }
}
