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
    /** `model/list` 응답의 result를 [ModelInfo] 목록으로. 숨김(hidden) 모델은 뺀다. **스텁**: 모델 담당이 구현. */
    fun parse(result: JSONObject): List<ModelInfo> = emptyList()

    /** 추론 강도의 한국어 이름. 모르는 값은 그대로. **스텁**: 모델 담당이 구현. */
    fun effortLabel(effort: String): String = effort

    /** 지금 선택을 한 줄로(채팅 화면 상단 칩 등). 예: "자동", "gpt-6.1-sol · 높음". **스텁**: 모델 담당이 구현. */
    fun choiceLabel(choice: ModelChoice, models: List<ModelInfo>): String = if (choice.isAuto) "자동" else (choice.model ?: "자동")
}
