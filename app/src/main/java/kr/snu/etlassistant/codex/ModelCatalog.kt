package kr.snu.etlassistant.codex

import org.json.JSONObject

/*
 * AI 모델 / 추론 강도 선택의 공통 계약. 여러 구현이 이 파일의 타입에 맞춰 병렬로 작성된다.
 * 시그니처를 바꾸려면 먼저 조율할 것.
 */

/** 모델이 지원하는 추론 강도 하나. [effort]는 프로토콜 값(`none`, `low`, `medium`, `high` 등 모델이 알려 주는 문자열). */
data class EffortOption(val effort: String, val description: String)

/** `model/list`가 돌려주는 모델 하나. */
data class ModelInfo(
    /** 목록의 고유 ID. */
    val id: String,
    /** `thread/start`·`turn/start`의 `model`에 넣는 값. */
    val model: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val hidden: Boolean,
    val defaultEffort: String?,
    val efforts: List<EffortOption>,
)

/**
 * 사용자의 선택. null은 "자동"(Codex 기본값)이다.
 * [model]이 null이면 모델은 자동, [effort]가 null이면 추론 강도는 그 모델의 기본값.
 */
data class ModelChoice(val model: String? = null, val effort: String? = null) {
    val isAuto: Boolean get() = model == null && effort == null
}

object ModelCatalog {
    /**
     * `model/list` 응답의 result를 [ModelInfo] 목록으로. 숨김(hidden) 모델은 뺀다.
     * 필드가 빠졌거나 타입이 이상한 항목은 조용히 건너뛴다(예외를 던지지 않는다).
     * 순서는 응답 그대로 두되 기본 모델(isDefault)은 맨 앞으로 올린다.
     */
    fun parse(result: JSONObject): List<ModelInfo> {
        val data = result.optJSONArray("data") ?: return emptyList()
        val list = ArrayList<ModelInfo>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val info = parseOne(o) ?: continue
            if (!info.hidden) list += info
        }
        // 안정 정렬이라 기본 모델만 앞으로 가고 나머지 순서는 유지된다.
        return list.sortedBy { if (it.isDefault) 0 else 1 }
    }

    private fun parseOne(o: JSONObject): ModelInfo? {
        val id = str(o, "id")?.takeIf { it.isNotEmpty() } ?: return null
        val model = str(o, "model")?.takeIf { it.isNotEmpty() } ?: return null
        val name = str(o, "displayName")?.takeIf { it.isNotEmpty() } ?: return null
        val efforts = ArrayList<EffortOption>()
        val arr = o.optJSONArray("supportedReasoningEfforts")
        if (arr != null) {
            for (j in 0 until arr.length()) {
                val e = arr.optJSONObject(j) ?: continue
                val v = str(e, "reasoningEffort")?.takeIf { it.isNotEmpty() } ?: continue
                efforts += EffortOption(v, str(e, "description").orEmpty())
            }
        }
        return ModelInfo(
            id = id,
            model = model,
            displayName = name,
            description = str(o, "description").orEmpty(),
            isDefault = o.opt("isDefault") == true,
            hidden = o.opt("hidden") == true,
            defaultEffort = str(o, "defaultReasoningEffort")?.takeIf { it.isNotEmpty() },
            efforts = efforts,
        )
    }

    /** 문자열 필드만 받는다(없거나 null, 숫자 등 다른 타입이면 null). */
    private fun str(o: JSONObject, key: String): String? = o.opt(key) as? String

    /** 추론 강도의 한국어 이름. 모르는 값은 그대로. */
    fun effortLabel(effort: String): String = when (effort) {
        "none" -> "끔(가장 빠름)"
        "minimal" -> "최소"
        "low" -> "낮음"
        "medium" -> "보통"
        "high" -> "높음"
        "xhigh" -> "매우 높음"
        else -> effort
    }

    /** 지금 선택을 한 줄로(채팅 화면 상단 칩 등). 예: "자동", "gpt-6.1-sol · 높음". */
    fun choiceLabel(choice: ModelChoice, models: List<ModelInfo>): String {
        val modelName = choice.model?.let { m -> models.firstOrNull { it.model == m }?.displayName ?: m }
        val effort = choice.effort?.let { effortLabel(it) }
        return when {
            modelName == null && effort == null -> "자동"
            modelName == null -> "자동 · $effort"
            effort == null -> modelName
            else -> "$modelName · $effort"
        }
    }

    /**
     * 요청을 만들 때 쓸 "유효한" 선택으로 다듬는다. 저장값을 바꾸는 게 아니라 이번 요청에서만 쓴다.
     * 목록이 비어 있으면(아직 모르면) 입력 그대로 돌려준다.
     * - 선택한 모델이 목록에 없으면 모델을 null(자동)로
     * - 선택한 강도가 (선택한 모델, 자동이면 기본 모델)의 지원 목록에 없으면 강도를 null로
     */
    fun sanitize(choice: ModelChoice, models: List<ModelInfo>): ModelChoice {
        if (models.isEmpty()) return choice
        val chosen = choice.model?.let { m -> models.firstOrNull { it.model == m } }
        val model = if (choice.model != null && chosen == null) null else choice.model
        var effort = choice.effort
        if (effort != null) {
            val target = chosen ?: models.firstOrNull { it.isDefault } ?: models.first()
            if (target.efforts.none { it.effort == effort }) effort = null
        }
        return ModelChoice(model, effort)
    }
}
