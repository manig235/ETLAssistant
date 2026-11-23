package kr.snu.etlassistant.onboarding

import android.content.Context
import kr.snu.etlassistant.etl.TimetableLogic
import kotlin.random.Random

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
        "새 공지 중 중요한 내용만 정리해줘",
        "오늘 수업 시간과 강의실 알려줘",
        "최근 강의자료 핵심 내용 요약해줘",
        "최근 강의자료 파일 받아줘",
        "최근 과제가 제대로 제출됐는지 확인해줘",
    )

    data class CourseOption(
        val course: TimetableLogic.CourseMeetings,
        val hasMaterials: Boolean = false,
        val hasRecentAssignment: Boolean = false,
    )

    fun eligibleCourse(course: TimetableLogic.CourseMeetings): Boolean =
        course.courseId > 0 && course.credits?.let { it in 3.0..4.0 } == true

    /** 질문별로 실제 콘텐츠가 확인된 3~4학점 과목을 고른다. 가능하면 과목을 겹치지 않는다. */
    fun personalizedSuggestions(courses: List<CourseOption>, random: Random = Random.Default): List<String> {
        val eligible = courses.filter { eligibleCourse(it.course) }.map { option ->
            val name = TimetableLogic.shortName(option.course.name)
                .replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim()
            name to option
        }.filter { it.first.isNotEmpty() }
        val materials = eligible.filter { it.second.hasMaterials }.map { it.first }.distinct().sorted().shuffled(random)
        val assignments = eligible.filter { it.second.hasRecentAssignment }.map { it.first }.distinct().sorted()
        val assignment = assignments.filterNot { it in materials }.randomOrNull(random) ?: assignments.randomOrNull(random)
        val separate = materials.filterNot { it == assignment }
        val summary = separate.firstOrNull() ?: materials.firstOrNull()
        val download = separate.firstOrNull { it != summary } ?: materials.firstOrNull { it != summary }
            ?: materials.randomOrNull(random)
        val picked = listOf(summary, download, assignment)
        return suggestions.mapIndexed { index, question ->
            val course = if (index >= 3) picked[index - 3] else null
            if (course == null) question else "$course $question"
        }
    }
}

/** 안내를 끝냈는지 기록한다(앱 내부 SharedPreferences). */
class OnboardingPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    var completed: Boolean
        get() = prefs.getBoolean("completed", false)
        set(v) = prefs.edit().putBoolean("completed", v).apply()
}
