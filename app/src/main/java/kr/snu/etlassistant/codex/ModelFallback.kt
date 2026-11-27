package kr.snu.etlassistant.codex

/** 계정이 모델 사용을 거절한 경우만 재시도한다. 연결·한도 오류나 도구 실행 뒤에는 재전송하지 않는다. */
class ModelFallback {
    class Attempt(val automatic: Boolean, var choice: ModelChoice) {
        @Volatile var generation = 0
            private set
        var hasProgress = false
        var error: String? = null
        private val retiredTurns = hashSetOf<String>()
        val tried = linkedSetOf<String>().apply { choice.model?.let(::add) }

        fun accepts(turnId: String?): Boolean = turnId == null || turnId !in retiredTurns

        fun retry(choice: ModelChoice, finishedTurnId: String?) {
            finishedTurnId?.let(retiredTurns::add)
            this.choice = choice
            tried += choice.model!!
            generation++
            error = null
        }
    }

    private val rejected = hashSetOf<String>()
    private var successful: String? = null

    @Synchronized fun clear() { rejected.clear(); successful = null }

    @Synchronized fun succeeded(model: String?) {
        if (model != null) { successful = model; rejected.remove(model) }
    }

    /** 자동 모드는 같은 세션에서 거절된 모델을 매번 다시 고르지 않는다. */
    @Synchronized fun choice(choice: ModelChoice, models: List<ModelInfo>): ModelChoice {
        if (choice.model != null || (successful == null && rejected.isEmpty())) return choice
        val target = candidates(models).firstOrNull { it !in rejected } ?: return choice
        return forModel(target, choice.effort, models)
    }

    @Synchronized fun next(attempt: Attempt, error: String?, models: List<ModelInfo>): ModelChoice? {
        val failed = unavailableModel(error) ?: return null
        rejected += failed
        attempt.tried += failed
        if (!attempt.automatic || attempt.hasProgress || attempt.generation >= MAX_RETRIES) return null
        val target = candidates(models).firstOrNull { it !in rejected && it !in attempt.tried } ?: return null
        return forModel(target, attempt.choice.effort, models)
    }

    private fun candidates(models: List<ModelInfo>): List<String> {
        val listed = models.filterNot { it.hidden }.map { it.model }
        return (listOfNotNull(successful) + listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-6.1-terra", "gpt-6-luna") + listed)
            .distinct().filter { models.isEmpty() || it in listed }
    }

    private fun forModel(model: String, effort: String?, models: List<ModelInfo>): ModelChoice =
        if (models.isEmpty()) ModelChoice(model) else ModelCatalog.sanitize(ModelChoice(model, effort), models)

    companion object {
        const val MAX_RETRIES = 2
        private val UNSUPPORTED = Regex("""The ['"]([a-zA-Z0-9._:-]+)['"] model is not supported when using Codex with a ChatGPT account""", RegexOption.IGNORE_CASE)
        fun unavailableModel(error: String?): String? = error?.let { UNSUPPORTED.find(it)?.groupValues?.get(1) }
    }
}
