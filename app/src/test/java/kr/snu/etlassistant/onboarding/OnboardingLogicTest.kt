package kr.snu.etlassistant.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kr.snu.etlassistant.etl.TimetableLogic
import kotlin.random.Random

class OnboardingLogicTest {

    @Test fun stepsAreOrderedAndNavigationClamps() {
        assertEquals(listOf(Step.WELCOME, Step.TOKEN, Step.LOGIN, Step.NOTIFY, Step.DONE), OnboardingLogic.steps)
        assertEquals(Step.TOKEN, OnboardingLogic.next(Step.WELCOME))
        assertEquals(Step.DONE, OnboardingLogic.next(Step.DONE))        // 끝에서 더 가지 않는다
        assertEquals(Step.WELCOME, OnboardingLogic.previous(Step.WELCOME))
        assertEquals(Step.LOGIN, OnboardingLogic.previous(Step.NOTIFY))
    }

    @Test fun progressIncreasesToOne() {
        val p = OnboardingLogic.steps.map { OnboardingLogic.progress(it) }
        assertTrue(p.zipWithNext().all { (a, b) -> a < b })
        assertTrue(p.first() > 0f)
        assertEquals(1f, p.last(), 0f)
    }

    @Test fun tokenShapeCheck() {
        assertTrue(OnboardingLogic.isPlausibleEtlToken("abcd1234efgh"))
        assertTrue(OnboardingLogic.isPlausibleEtlToken("  1234~abcdEF  "))   // 앞뒤 공백은 무시
        assertFalse(OnboardingLogic.isPlausibleEtlToken(""))
        assertFalse(OnboardingLogic.isPlausibleEtlToken("short"))
        assertFalse(OnboardingLogic.isPlausibleEtlToken("has space inside123"))
        assertFalse(OnboardingLogic.isPlausibleEtlToken("토큰입니다토큰입니다"))
        assertFalse(OnboardingLogic.isPlausibleEtlToken("abc\ndef12345"))
    }

    @Test fun skipForFinishedOrExistingUsers() {
        assertFalse(OnboardingLogic.shouldSkip(completed = false, hasEtlToken = false)) // 새 사용자
        assertTrue(OnboardingLogic.shouldSkip(completed = true, hasEtlToken = false))
        assertTrue(OnboardingLogic.shouldSkip(completed = false, hasEtlToken = true))   // 이미 설정한 기존 사용자
    }

    @Test fun onlyTokenStepBlocksContinue() {
        assertFalse(OnboardingLogic.canContinue(Step.TOKEN, tokenSaved = false))
        assertTrue(OnboardingLogic.canContinue(Step.TOKEN, tokenSaved = true))
        for (s in listOf(Step.WELCOME, Step.LOGIN, Step.NOTIFY, Step.DONE)) assertTrue(OnboardingLogic.canContinue(s, false))
    }

    @Test fun displayNameDropsStudentNumber() {
        assertEquals("홍길동", OnboardingLogic.displayName("홍길동(2021-12345)"))
        assertEquals("Alex Kim", OnboardingLogic.displayName("Alex Kim"))
        assertNull(OnboardingLogic.displayName(null))
        assertNull(OnboardingLogic.displayName(""))
        assertNull(OnboardingLogic.displayName("null"))
        assertNull(OnboardingLogic.displayName("(123)"))
    }

    private fun course(id: Long, name: String, credits: Double?, materials: Boolean? = true, assignment: Boolean? = true) =
        OnboardingLogic.CourseOption(TimetableLogic.CourseMeetings(id, name, emptyList(), credits = credits), materials, assignment)

    private fun courseOf(question: String, index: Int): String? =
        question.removeSuffix(" ${OnboardingLogic.suggestions[index]}").takeIf { it != question }

    @Test fun courseQuestionsUseDifferentConfirmedThreeToFourCreditCourses() {
        val courses = listOf(
            course(1, "2026-2 기계공학개론 (001)", 3.0),
            course(2, "전기에너지변환", 4.0), course(3, "제어공학개론", 3.0),
            course(4, "체력단련", 1.0), course(5, "미확인 수업", null),
            course(-1, "직접 추가 수업", 3.0), course(6, "2학점 수업", 2.0),
            course(7, "5학점 수업", 5.0), course(8, "   ", 3.0),
            course(9, "기계공학개론", 3.0),
        )
        val questions = OnboardingLogic.personalizedSuggestions(courses, Random(13))
        assertEquals(OnboardingLogic.suggestions.take(3), questions.take(3))
        val picked = questions.drop(3).zip(OnboardingLogic.suggestions.drop(3)) { question, template ->
            assertTrue(question.endsWith(" $template"))
            question.removeSuffix(" $template")
        }
        assertEquals(setOf("기계공학개론", "전기에너지변환", "제어공학개론"), picked.toSet())
        assertEquals(questions, OnboardingLogic.personalizedSuggestions(courses.reversed(), Random(13)))
    }

    @Test fun genericOnlyWhenNoCourseIsKnown() {
        assertEquals(OnboardingLogic.suggestions, OnboardingLogic.personalizedSuggestions(emptyList()))
        // 직접 추가한 수업과 이름 없는 과목은 과목으로 치지 않는다.
        assertEquals(OnboardingLogic.suggestions,
            OnboardingLogic.personalizedSuggestions(listOf(course(-3, "직접 추가", 3.0), course(4, " \u200b ", 3.0))))
        val one = OnboardingLogic.personalizedSuggestions(listOf(course(1, "확인된 수업", 3.0)))
        assertEquals(OnboardingLogic.suggestions.drop(3).map { "확인된 수업 $it" }, one.drop(3))
        assertTrue(one.none { "[과목명]" in it })
    }

    @Test fun unknownCreditsAndUncheckedContentStillGiveCourseNames() {
        // 수강편람 조회 실패(학점 없음) + 파일 탭 숨김(콘텐츠 미확인): 예전에는 일반 문구였다.
        val unknown = listOf(course(1, "2026-2 선형대수학 (001)", null, null, null), course(2, "물리학", 1.0, null, null))
        val questions = OnboardingLogic.personalizedSuggestions(unknown, Random(3))
        (3..5).forEach { assertTrue(courseOf(questions[it], it) in setOf("선형대수학", "물리학")) }
        assertEquals(setOf("선형대수학", "물리학"), setOf(courseOf(questions[3], 3), courseOf(questions[4], 4)))
    }

    @Test fun fallbackChainPrefersConfirmedThenEligibleThenAnyCourse() {
        // 자료 확인 과목이 있으면 그것만(겹쳐도) 쓴다.
        val confirmed = listOf(course(1, "자료 수업", 1.0, true, null), course(2, "3학점 미확인", 3.0, null, null))
        val q1 = OnboardingLogic.personalizedSuggestions(confirmed, Random(1))
        assertEquals("자료 수업", courseOf(q1[3], 3))
        assertEquals("자료 수업", courseOf(q1[4], 4))
        // 확인된 과목이 없으면 3~4학점(미확인 > 없음 확인) > 다른 과목(미확인 > 없음 확인) 순.
        val ranked = listOf(
            course(1, "다른 없음", 2.0, false, false), course(2, "다른 미확인", null, null, null),
            course(3, "3학점 없음", 3.0, false, false), course(4, "3학점 미확인", 4.0, null, null),
        )
        repeat(5) { seed ->
            val q = OnboardingLogic.personalizedSuggestions(ranked.shuffled(Random(seed)), Random(seed))
            assertEquals("3학점 미확인", courseOf(q[5], 5))
            assertEquals("3학점 없음", courseOf(q[3], 3))
            assertEquals("다른 미확인", courseOf(q[4], 4))
        }
        // 3~4학점 과목이 없으면 아무 이번 학기 과목이라도.
        val q3 = OnboardingLogic.personalizedSuggestions(listOf(course(9, "교양 수업", 2.0, false, false)))
        (3..5).forEach { assertEquals("교양 수업", courseOf(q3[it], it)) }
    }

    @Test fun eachQuestionPrefersItsOwnVerifiedContent() {
        val options = listOf(
            course(1, "자료만 있는 수업", 3.0, assignment = false),
            course(2, "자료와 과제 수업", 4.0),
            course(3, "과제만 있는 수업", 3.0, materials = false),
            course(4, "빈 수업", 3.0, materials = false, assignment = false),
        )
        val questions = OnboardingLogic.personalizedSuggestions(options, Random(7))
        assertEquals(setOf("자료만 있는 수업", "자료와 과제 수업"),
            questions.slice(3..4).zip(OnboardingLogic.suggestions.slice(3..4)) { q, template -> q.removeSuffix(" $template") }.toSet())
        assertEquals("과제만 있는 수업 ${OnboardingLogic.suggestions[5]}", questions[5])
        // 과제가 확인된 과목이 없으면 확인 안 된 과목명이라도 쓴다.
        val noRecent = OnboardingLogic.personalizedSuggestions(listOf(options[0]))
        assertTrue(noRecent[3].startsWith("자료만 있는 수업 "))
        assertEquals("자료만 있는 수업 ${OnboardingLogic.suggestions[5]}", noRecent[5])
        val empty = OnboardingLogic.personalizedSuggestions(listOf(options[3]))
        (3..5).forEach { assertEquals("빈 수업", courseOf(empty[it], it)) }
    }

    @Test fun knownCoursesMergesSourcesAndDropsHiddenAndCustom() {
        val timetable = listOf(
            TimetableLogic.CourseMeetings(1, "2026-2 역학 (001)", emptyList(), credits = 3.0),
            TimetableLogic.CourseMeetings(-5, "직접 추가", emptyList(), credits = 3.0),
            TimetableLogic.CourseMeetings(2, "숨긴 과목", emptyList(), credits = 3.0),
        )
        val extra = listOf(1L to "다른 이름", 3L to "2026-2 열역학 (002)", -1L to "내 마감", 4L to "  ", 3L to "중복")
        val known = OnboardingLogic.knownCourses(timetable, extra) { it == 2L }
        assertEquals(listOf(1L, 3L), known.map { it.courseId })
        assertEquals(3.0, known[0].credits)
        assertEquals("2026-2 열역학 (002)", known[1].name)
        val q = OnboardingLogic.personalizedSuggestions(known.map { OnboardingLogic.CourseOption(it) }, Random(2))
        assertEquals("역학", courseOf(q[5], 5)) // 3학점(미확인)이 학점 모르는 과목보다 먼저
        assertEquals("열역학", courseOf(q[3], 3)) // 질문끼리 가능하면 겹치지 않게
        assertTrue((3..5).none { courseOf(q[it], it) == "숨긴 과목" })
    }

    @Test fun choicesAreSane() {
        assertTrue(OnboardingLogic.briefTimeChoices.all { it in 0..1439 })
        assertEquals(OnboardingLogic.briefTimeChoices.sorted(), OnboardingLogic.briefTimeChoices)
        assertTrue(OnboardingLogic.suggestions.isNotEmpty())
    }
}
