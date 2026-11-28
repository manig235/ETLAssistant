package kr.snu.youngmini.desktop
import kr.snu.youngmini.portable.ModelFallback
import kr.snu.etlassistant.codex.*

import org.junit.Assert.*
import org.junit.Test

class ModelFallbackTest {
    private fun unavailable(model: String) = "The '$model' model is not supported when using Codex with a ChatGPT account."
    private fun model(name: String, hidden: Boolean = false, efforts: List<String> = listOf("low", "high")) =
        ModelInfo(name, name, name, "", false, hidden, "low", efforts.map { EffortOption(it, "") })
    private val models = listOf(model("gpt-6.2-sol"), model("gpt-6.1-sol"), model("gpt-6-sol"))

    @Test fun autoFallsBackFromRejectedModelAndRemembersWorkingModel() {
        val policy = ModelFallback()
        val attempt = ModelFallback.Attempt(true, ModelChoice())
        assertEquals(ModelChoice(), policy.choice(ModelChoice(), models))
        val next = policy.next(attempt, unavailable("gpt-6.2-sol"), models)!!
        assertEquals("gpt-6.1-sol", next.model)
        attempt.retry(next, "failed-6.2")
        assertEquals(1, attempt.generation)
        assertFalse(attempt.accepts("failed-6.2"))
        assertTrue(attempt.accepts("next-turn"))
        assertEquals("gpt-6.1-sol", policy.choice(ModelChoice(), models).model)
        policy.succeeded("gpt-6.1-sol")
        // 다른 질문·대화에서도 같은 실행 동안 사용이 확인된 모델을 우선한다.
        assertEquals("gpt-6.1-sol", policy.choice(ModelChoice(), models).model)
    }

    @Test fun manualSelectionAndNonModelErrorsDoNotRetry() {
        val policy = ModelFallback()
        assertNull(policy.next(ModelFallback.Attempt(false, ModelChoice("gpt-6.2-sol")), unavailable("gpt-6.2-sol"), models))
        for (error in listOf("network timeout", "rate limit exceeded", "invalid access token", "not supported", null)) {
            assertNull(policy.next(ModelFallback.Attempt(true, ModelChoice()), error, models))
        }
        assertEquals(ModelChoice("gpt-6.2-sol"), policy.choice(ModelChoice("gpt-6.2-sol"), models))
        assertEquals("gpt-6.1-sol", policy.choice(ModelChoice(), models).model)
    }

    @Test fun outputOrToolExecutionPreventsResendingTheQuestion() {
        val attempt = ModelFallback.Attempt(true, ModelChoice()).also { it.hasProgress = true }
        assertNull(ModelFallback().next(attempt, unavailable("gpt-6.2-sol"), models))
    }

    @Test fun retryIsBoundedAndDoesNotRepeatRejectedModels() {
        val policy = ModelFallback()
        val attempt = ModelFallback.Attempt(true, ModelChoice())
        val first = policy.next(attempt, unavailable("gpt-6.2-sol"), models)!!
        attempt.retry(first, "first")
        val second = policy.next(attempt, unavailable("gpt-6.1-sol"), models)!!
        assertEquals("gpt-6-sol", second.model)
        attempt.retry(second, "second")
        assertNull(policy.next(attempt, unavailable("gpt-6-sol"), models + model("other")))
        assertFalse(attempt.accepts("first"))
        assertFalse(attempt.accepts("second"))
    }

    @Test fun onlyListedVisibleModelsAndSupportedEffortsAreUsed() {
        val policy = ModelFallback()
        val attempt = ModelFallback.Attempt(true, ModelChoice(null, "xhigh"))
        val list = listOf(model("gpt-6.2-sol"), model("gpt-6.1-sol", hidden = true), model("gpt-6-sol"))
        val choice = policy.next(attempt, unavailable("gpt-6.2-sol"), list)!!
        assertEquals(ModelChoice("gpt-6-sol"), choice)
        assertNull(ModelFallback().next(ModelFallback.Attempt(true, ModelChoice()), unavailable("gpt-6.2-sol"), listOf(model("gpt-6.2-sol"))))
        assertEquals("gpt-6.1-sol", ModelFallback().next(ModelFallback.Attempt(true, ModelChoice()), unavailable("gpt-6.2-sol"), emptyList())?.model)
    }

    @Test fun accountOrSessionResetClearsObservedAvailability() {
        val policy = ModelFallback()
        policy.succeeded("gpt-6.1-sol")
        assertEquals("gpt-6.1-sol", policy.choice(ModelChoice(), models).model)
        policy.clear()
        assertEquals(ModelChoice(), policy.choice(ModelChoice(), models))
    }

    @Test fun successfulManualModelIsPreferredWhenReturningToAuto() {
        val policy = ModelFallback()
        policy.succeeded("gpt-6-sol")
        assertEquals("gpt-6-sol", policy.choice(ModelChoice(), models).model)
        assertEquals(ModelChoice("gpt-6.2-sol", "high"), policy.choice(ModelChoice("gpt-6.2-sol", "high"), models))
    }
}
