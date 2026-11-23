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

    private fun course(id: Long, name: String, credits: Double?, materials: Boolean = true, assignment: Boolean = true) =
        OnboardingLogic.CourseOption(TimetableLogic.CourseMeetings(id, name, emptyList(), credits = credits), materials, assignment)

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

    @Test fun missingCreditsNeverBecomeGuessedCourseNames() {
        val unknown = listOf(course(1, "1학점", 1.0), course(2, "이름에 3학점이 있어도 미확인", null))
        assertEquals(OnboardingLogic.suggestions, OnboardingLogic.personalizedSuggestions(unknown))
        assertEquals(OnboardingLogic.suggestions, OnboardingLogic.personalizedSuggestions(emptyList()))
        val one = OnboardingLogic.personalizedSuggestions(listOf(course(1, "확인된 수업", 3.0)))
        assertEquals(OnboardingLogic.suggestions.drop(3).map { "확인된 수업 $it" }, one.drop(3))
        assertTrue(one.none { "[과목명]" in it })
    }

    @Test fun eachQuestionRequiresItsOwnVerifiedContent() {
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
        val noRecent = OnboardingLogic.personalizedSuggestions(listOf(options[0]))
        assertTrue(noRecent[3].startsWith("자료만 있는 수업 "))
        assertEquals(OnboardingLogic.suggestions[5], noRecent[5])
        assertEquals(OnboardingLogic.suggestions, OnboardingLogic.personalizedSuggestions(listOf(options[3])))
    }

    @Test fun choicesAreSane() {
        assertTrue(OnboardingLogic.briefTimeChoices.all { it in 0..1439 })
        assertEquals(OnboardingLogic.briefTimeChoices.sorted(), OnboardingLogic.briefTimeChoices)
        assertTrue(OnboardingLogic.suggestions.isNotEmpty())
    }
}
