package kr.snu.etlassistant.onboarding

import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.TimetableLogic
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 7번째 추천 질문 "{과목} {중간고사|기말고사|시험} 대비 모의고사 PDF로 만들어줘"의 시험 이름과 과목(순수, KST).
 *
 * 1) 내 일정에 넣은 시험이 [LOOKAHEAD_DAYS]일 안에 있으면 가장 가까운 시험을 따른다: 과목은 그 시험의 과목,
 *    이름은 제목의 "중간"/"기말" → 시험 날짜가 학기 중간·기말고사 기간(7~9주차 / 14~17주차)이면 그 이름 → 그 밖(퀴즈 등)은 "시험".
 * 2) 없으면 오늘의 학기 주차: 1~8주차 "중간고사", 9~16주차 "기말고사", 방학·계절학기 "시험".
 * 학기 시작일은 앱이 가진 데이터에 없다(eTL term은 이름만 쓰고 수강편람·계획서에도 개강일이 없다). 서울대 학사력대로
 * 1학기는 3월 2일, 2학기는 9월 1일이 든 주의 월요일을 1주차로 본다(그날이 주말이면 다음 주).
 */
object MockExam {
    const val MIDTERM = "중간고사"
    const val FINAL = "기말고사"
    const val GENERIC = "시험"

    /** 내 일정의 시험을 이만큼 앞까지만 본다(6주). */
    const val LOOKAHEAD_DAYS = 42L

    /** 내 일정의 시험 하나(`MyDeadline.kind == EXAM`). */
    data class Exam(val title: String, val course: String, val start: Instant, val end: Instant? = null)

    /** [term]: 시험 이름. [course]: 시험에 적힌 과목(없으면 null). [title]: 시험 제목(과목이 비었을 때 과목명을 찾는 데 쓴다). */
    data class Hint(val term: String, val course: String? = null, val title: String? = null)

    /** 그 학기([semester] 1 또는 2) 1주차 월요일. */
    fun semesterStart(year: Int, semester: Int): LocalDate {
        val anchor = if (semester == 1) LocalDate.of(year, 3, 2) else LocalDate.of(year, 9, 1)
        val weekday = if (anchor.dayOfWeek >= DayOfWeek.SATURDAY) anchor.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else anchor
        return weekday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    /** 정규 학기 주차(1부터). 학기 밖이면 null. 1~16주차까지만 학기로 본다. */
    fun semesterWeek(date: LocalDate): Int? = weekIn(date, 16)

    private fun weekIn(date: LocalDate, weeks: Int): Int? {
        for (semester in 1..2) {
            val days = ChronoUnit.DAYS.between(semesterStart(date.year, semester), date)
            if (days in 0 until weeks * 7L) return (days / 7).toInt() + 1
        }
        return null
    }

    /** 오늘 기준 시험 이름: 1~8주차 중간고사, 9~16주차 기말고사, 방학·계절학기는 "시험". */
    fun termByWeek(today: LocalDate): String = when (semesterWeek(today)) {
        null -> GENERIC
        in 1..8 -> MIDTERM
        else -> FINAL
    }

    /** 시험 날짜가 중간고사 기간(7~9주차)이나 기말고사 기간(14~17주차, 밀린 기말 포함)이면 그 이름, 아니면 "시험". */
    fun termOfExamDate(date: LocalDate): String = when (weekIn(date, 17)) {
        in 7..9 -> MIDTERM
        in 14..17 -> FINAL
        else -> GENERIC
    }

    private fun termInTitle(title: String): String? = when {
        "중간" in title -> MIDTERM
        "기말" in title -> FINAL
        else -> null
    }

    /**
     * 추천 질문에 쓸 시험 이름과 과목. [exams] 중 아직 끝나지 않았고 [LOOKAHEAD_DAYS]일 안에 시작하는 가장 가까운 시험을 따르고,
     * 없으면 [now]의 학기 주차로 정한다.
     */
    fun hint(exams: List<Exam>, now: Instant): Hint {
        val limit = now.plus(Duration.ofDays(LOOKAHEAD_DAYS))
        val next = exams.filter { (it.end ?: it.start) > now && it.start <= limit }.minByOrNull { it.start }
            ?: return Hint(termByWeek(now.atZone(EtlLogic.KST).toLocalDate()))
        val term = termInTitle(next.title) ?: termOfExamDate(next.start.atZone(EtlLogic.KST).toLocalDate())
        val course = clean(next.course).takeIf { it.isNotEmpty() }
        return Hint(term, course, next.title.trim().takeIf { it.isNotEmpty() })
    }

    /** 시험 제목에 들어 있는 과목명(가장 긴 것). 과목 칸을 비워 둔 시험("자료구조 중간고사")용. */
    fun courseInTitle(title: String?, names: Collection<String>): String? {
        val t = title ?: return null
        return names.filter { it.isNotEmpty() && it in t }.maxByOrNull { it.length }
    }

    fun question(term: String, course: String?): String =
        listOfNotNull(course?.takeIf { it.isNotBlank() }, term).joinToString(" ") + " 대비 모의고사 PDF로 만들어줘"

    private fun clean(name: String): String =
        TimetableLogic.shortName(name).replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim()
}
