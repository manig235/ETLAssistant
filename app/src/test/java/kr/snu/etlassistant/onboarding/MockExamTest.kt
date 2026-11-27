package kr.snu.etlassistant.onboarding

import kr.snu.etlassistant.etl.TimetableLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

class MockExamTest {

    private fun day(s: String) = LocalDate.parse(s)
    private fun kst(s: String): Instant = LocalDateTime.parse(s).toInstant(ZoneOffset.ofHours(9))
    private fun exam(title: String, start: String, end: String? = null, course: String = "") =
        MockExam.Exam(title, course, kst(start), end?.let(::kst))

    @Test fun semesterStartsOnMondayOfTheWeekWithMarch2OrSeptember1() {
        assertEquals(day("2026-03-02"), MockExam.semesterStart(2026, 1)) // 월요일
        assertEquals(day("2026-08-31"), MockExam.semesterStart(2026, 2)) // 9/1 화요일 → 그 주 월요일
        assertEquals(day("2025-03-03"), MockExam.semesterStart(2025, 1)) // 3/2 일요일 → 다음 월요일
        assertEquals(day("2024-09-02"), MockExam.semesterStart(2024, 2)) // 9/1 일요일 → 다음 월요일
        assertEquals(day("2027-08-30"), MockExam.semesterStart(2027, 2)) // 9/1 수요일
    }

    @Test fun termByWeekBoundariesFirstSemester() {
        assertEquals(MockExam.GENERIC, MockExam.termByWeek(day("2026-03-01")))  // 개강 전날(방학)
        assertEquals(MockExam.MIDTERM, MockExam.termByWeek(day("2026-03-02")))  // 1주차 첫날
        assertEquals(MockExam.MIDTERM, MockExam.termByWeek(day("2026-04-26")))  // 8주차 마지막 날(일)
        assertEquals(MockExam.FINAL, MockExam.termByWeek(day("2026-04-27")))    // 9주차 첫날
        assertEquals(MockExam.FINAL, MockExam.termByWeek(day("2026-06-21")))    // 16주차 마지막 날
        assertEquals(MockExam.GENERIC, MockExam.termByWeek(day("2026-06-22")))  // 여름 계절학기
        assertEquals(MockExam.GENERIC, MockExam.termByWeek(day("2026-07-15")))
    }

    @Test fun termByWeekBoundariesSecondSemesterAndWinter() {
        assertEquals(MockExam.GENERIC, MockExam.termByWeek(day("2026-08-30")))
        assertEquals(MockExam.MIDTERM, MockExam.termByWeek(day("2026-08-31")))
        assertEquals(MockExam.MIDTERM, MockExam.termByWeek(day("2026-10-25")))
        assertEquals(MockExam.FINAL, MockExam.termByWeek(day("2026-10-26")))
        assertEquals(MockExam.FINAL, MockExam.termByWeek(day("2026-11-27")))
        assertEquals(MockExam.FINAL, MockExam.termByWeek(day("2026-12-20")))
        assertEquals(MockExam.GENERIC, MockExam.termByWeek(day("2026-12-21")))  // 겨울 계절학기
        assertEquals(MockExam.GENERIC, MockExam.termByWeek(day("2027-01-20")))
        assertEquals(MockExam.GENERIC, MockExam.termByWeek(day("2027-02-28")))
    }

    @Test fun hintWithoutExamUsesTodayInKst() {
        // 4/26 23:59 KST = 8주차, 4/27 00:00 KST = 9주차. UTC 날짜로 판정하면 틀린다.
        assertEquals(MockExam.Hint(MockExam.MIDTERM), MockExam.hint(emptyList(), kst("2026-04-26T23:59")))
        assertEquals(MockExam.Hint(MockExam.FINAL), MockExam.hint(emptyList(), kst("2026-04-27T00:00")))
        assertEquals(MockExam.Hint(MockExam.GENERIC), MockExam.hint(emptyList(), kst("2026-07-01T12:00")))
    }

    @Test fun nearestUpcomingExamWins() {
        val now = kst("2026-11-27T12:00")
        val exams = listOf(
            exam("선형대수 기말고사", "2026-12-15T10:00", course = "2026-2 선형대수학 (001)"),
            exam("자료구조 중간 재시험", "2026-12-03T19:00", course = "자료구조"),   // 제목의 "중간"이 학기 주차보다 우선
            exam("끝난 시험", "2026-11-27T09:00", "2026-11-27T11:00", course = "끝난 과목"),
            exam("6주 넘게 남은 시험", "2027-01-09T10:00", course = "먼 과목"),
        )
        assertEquals(MockExam.Hint(MockExam.MIDTERM, "자료구조", "자료구조 중간 재시험"), MockExam.hint(exams, now))
        // 진행 중인 시험(시작했지만 끝나지 않음)도 대상이다. 과목 칸의 학기·분반 표기는 뗀다.
        val ongoing = listOf(exam("기말", "2026-11-27T11:00", "2026-11-27T13:00", course = "2026-2 회로이론 (002)"))
        assertEquals(MockExam.Hint(MockExam.FINAL, "회로이론", "기말"), MockExam.hint(ongoing, now))
        // 끝 시각이 없으면 시작이 지나면 끝난 것으로 본다.
        assertEquals(MockExam.Hint(MockExam.FINAL), MockExam.hint(listOf(exam("기말", "2026-11-27T11:00")), now))
        // 6주(42일) 경계.
        assertEquals(MockExam.Hint(MockExam.GENERIC, null, "시험"),
            MockExam.hint(listOf(exam("시험", "2027-01-08T12:00")), now))
        assertEquals(MockExam.Hint(MockExam.FINAL), MockExam.hint(listOf(exam("시험", "2027-01-08T12:01")), now))
    }

    @Test fun examTitleWithoutTermUsesExamWeek() {
        val now = kst("2026-03-20T09:00")
        // 8주차(4/20~4/26) 시험 → 중간고사, 4주차 시험 → 퀴즈일 수 있어 "시험".
        assertEquals(MockExam.MIDTERM, MockExam.hint(listOf(exam("물리 시험", "2026-04-21T10:00")), now).term)
        assertEquals(MockExam.GENERIC, MockExam.hint(listOf(exam("물리 퀴즈", "2026-03-24T10:00")), now).term)
        // 15주차·밀린 17주차 → 기말고사, 그 다음 주는 "시험".
        val late = kst("2026-06-01T09:00")
        assertEquals(MockExam.FINAL, MockExam.hint(listOf(exam("물리 시험", "2026-06-09T10:00")), late).term)
        assertEquals(MockExam.FINAL, MockExam.hint(listOf(exam("물리 시험", "2026-06-23T10:00")), late).term)
        assertEquals(MockExam.GENERIC, MockExam.hint(listOf(exam("물리 시험", "2026-06-29T10:00")), late).term)
        // 계절학기 시험은 날짜로 중간·기말을 알 수 없다. 제목에 있으면 그 말.
        val summer = kst("2026-07-01T09:00")
        assertEquals(MockExam.GENERIC, MockExam.hint(listOf(exam("계절 시험", "2026-07-20T10:00")), summer).term)
        assertEquals(MockExam.FINAL, MockExam.hint(listOf(exam("계절 기말", "2026-07-20T10:00")), summer).term)
    }

    @Test fun questionWording() {
        assertEquals("자료구조 중간고사 대비 모의고사 PDF로 만들어줘", MockExam.question(MockExam.MIDTERM, "자료구조"))
        assertEquals("기말고사 대비 모의고사 PDF로 만들어줘", MockExam.question(MockExam.FINAL, null))
        assertEquals("시험 대비 모의고사 PDF로 만들어줘", MockExam.question(MockExam.GENERIC, " "))
    }

    private fun option(id: Long, name: String, credits: Double?, materials: Boolean? = null) =
        OnboardingLogic.CourseOption(TimetableLogic.CourseMeetings(id, name, emptyList(), credits = credits), materials, null)

    @Test fun suggestionUsesExamCourseThenTitleThenMaterialsCourse() {
        val courses = listOf(
            option(1, "선형대수학", 3.0, true), option(2, "자료구조", 3.0, true),
            option(3, "회로이론", 4.0, true), option(4, "물리학", 3.0, true), option(5, "교양", 1.0, false),
        )
        val withCourse = OnboardingLogic.personalizedSuggestions(courses, Random(1), MockExam.Hint(MockExam.MIDTERM, "직접 쓴 과목"))
        assertEquals("직접 쓴 과목 중간고사 대비 모의고사 PDF로 만들어줘", withCourse[6])
        // 과목 칸이 비면 제목에 든 이번 학기 과목명(가장 긴 것).
        val fromTitle = OnboardingLogic.personalizedSuggestions(courses, Random(1), MockExam.Hint(MockExam.FINAL, null, "자료구조 기말 (2차)"))
        assertEquals("자료구조 기말고사 대비 모의고사 PDF로 만들어줘", fromTitle[6])
        // 시험이 없으면 자료가 확인된 3~4학점 과목 중 앞의 세 질문에 쓰지 않은 과목.
        repeat(5) { seed ->
            val q = OnboardingLogic.personalizedSuggestions(courses.shuffled(Random(seed)), Random(seed), MockExam.Hint(MockExam.FINAL))
            val used = (3..5).map { q[it].substringBefore(" 최근") }.toSet()
            val mock = q[6].removeSuffix(" 기말고사 대비 모의고사 PDF로 만들어줘")
            assertEquals(setOf("선형대수학", "자료구조", "회로이론", "물리학"), used + mock)
            assertEquals(7, q.size)
        }
        // 겹침을 피하려고 1학점 과목으로 내려가지 않는다(자료 확인된 3~4학점 과목이 하나뿐이면 그 과목).
        val few = OnboardingLogic.personalizedSuggestions(
            listOf(option(1, "선형대수학", 3.0, true), option(2, "체력단련", 1.0, true)), Random(4), MockExam.Hint(MockExam.MIDTERM))
        assertEquals("선형대수학 중간고사 대비 모의고사 PDF로 만들어줘", few[6])
        assertNull(MockExam.courseInTitle(null, listOf("자료구조")))
        assertEquals("선형대수학", MockExam.courseInTitle("선형대수학 중간", listOf("대수", "선형대수학", "")))
    }
}
