package kr.snu.etlassistant.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun choicesAreSane() {
        assertTrue(OnboardingLogic.briefTimeChoices.all { it in 0..1439 })
        assertEquals(OnboardingLogic.briefTimeChoices.sorted(), OnboardingLogic.briefTimeChoices)
        assertTrue(OnboardingLogic.suggestions.isNotEmpty())
    }
}
