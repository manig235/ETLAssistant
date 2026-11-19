package kr.snu.etlassistant.onboarding

import android.content.Context

/** 첫 실행 안내의 단계. */
enum class Step { WELCOME, TOKEN, LOGIN, NOTIFY, DONE }

/** 안드로이드 API에 의존하지 않는 안내 흐름 규칙(JVM 단위 테스트 대상). */
object OnboardingLogic {

    val steps: List<Step> = Step.values().toList()

    /** 진행률(0..1). 첫 단계도 조금은 차 있게 (인덱스 + 1) / 전체. */
    fun progress(step: Step): Float = (steps.indexOf(step) + 1).toFloat() / steps.size

    fun next(step: Step): Step = steps.getOrElse(steps.indexOf(step) + 1) { Step.DONE }

    fun previous(step: Step): Step = steps.getOrElse(steps.indexOf(step) - 1) { Step.WELCOME }

    /** eTL 토큰으로 쓸 수 있는 모양인가(공백 없는 ASCII 8자 이상). 실제 유효성은 eTL에 물어봐야 안다. */
    fun isPlausibleEtlToken(raw: String): Boolean = Regex("[\\u0021-\\u007e]{8,}").matches(raw.trim())

    /**
     * 첫 실행 안내를 건너뛸지. 이미 끝냈거나, 이미 eTL 토큰을 저장한 기존 사용자면 건너뛴다
     * (로그인 여부는 채팅 화면의 배너가 안내한다).
     */
    fun shouldSkip(completed: Boolean, hasEtlToken: Boolean): Boolean = completed || hasEtlToken

    /** 현재 단계에서 "다음"을 누를 수 있는가. 로그인과 알림은 나중에 해도 되므로 막지 않는다. */
    fun canContinue(step: Step, tokenSaved: Boolean): Boolean = when (step) {
        Step.TOKEN -> tokenSaved
        else -> true
    }

    /** `users/self`의 이름 필드에서 표시용 이름을 뽑는다. "홍길동(2021-12345)" → "홍길동". 비면 null. */
    fun displayName(raw: String?): String? =
        raw?.substringBefore('(')?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** 브리핑 시각 후보(하루 중 분). */
    val briefTimeChoices: List<Int> = listOf(7 * 60, 8 * 60, 9 * 60, 20 * 60)

    val suggestions: List<String> = listOf(
        "이번 주 마감 과제 알려줘",
        "새 공지 있어?",
        "제출 안 한 과제 확인해줘",
    )
}

/** 안내를 끝냈는지 기록한다(앱 내부 SharedPreferences). */
class OnboardingPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    var completed: Boolean
        get() = prefs.getBoolean("completed", false)
        set(v) = prefs.edit().putBoolean("completed", v).apply()
}
