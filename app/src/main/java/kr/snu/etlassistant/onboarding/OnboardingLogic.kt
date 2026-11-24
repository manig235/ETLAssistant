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

    /**
     * 추천 질문 후보 과목 하나. 콘텐츠 값은 null이면 미확인(조회 실패·아직 조회 안 함), false면 확인했는데 없음.
     */
    data class CourseOption(
        val course: TimetableLogic.CourseMeetings,
        val hasMaterials: Boolean? = null,
        val hasRecentAssignment: Boolean? = null,
    )

    fun eligibleCourse(course: TimetableLogic.CourseMeetings): Boolean =
        course.courseId > 0 && course.credits?.let { it in 3.0..4.0 } == true

    /**
     * 추천에 쓸 이번 학기 과목 목록. 시간표(학점 포함)를 먼저, 그다음 eTL 강의 목록·받아 둔 과제 목록([extra], ID와 이름)에서
     * 시간표에 없던 과목을 더한다. 직접 추가한 수업(ID ≤ 0), 이름 없는 과목, 숨긴 과목([hidden])은 뺀다.
     */
    fun knownCourses(
        timetable: List<TimetableLogic.CourseMeetings>,
        extra: List<Pair<Long, String>>,
        hidden: (Long) -> Boolean = { false },
    ): List<TimetableLogic.CourseMeetings> {
        val out = LinkedHashMap<Long, TimetableLogic.CourseMeetings>()
        for (c in timetable) if (c.courseId > 0 && c.courseId !in out) out[c.courseId] = c
        for ((id, name) in extra) if (id > 0 && id !in out) out[id] = TimetableLogic.CourseMeetings(id, name, emptyList())
        return out.values.filter { !hidden(it.courseId) && cleanName(it.name).isNotEmpty() }
    }

    private fun cleanName(name: String): String =
        TimetableLogic.shortName(name).replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim()

    /**
     * 과목 질문 3개(자료 요약, 자료 받기, 과제 제출 확인)에 구체적인 과목명을 붙인다. 이번 학기 과목을 하나라도 알면 일반 문구로 두지 않는다.
     * 질문마다 후보 순위: 콘텐츠 확인됨(3~4학점 → 그 외) > 미확인(3~4학점) > 없음 확인(3~4학점) > 미확인(그 외) > 없음 확인(그 외).
     * 콘텐츠가 확인된 과목이 하나라도 있으면 그 질문은 확인된 과목만 쓴다(겹쳐도). 가능하면 질문끼리 과목을 겹치지 않는다.
     */
    fun personalizedSuggestions(courses: List<CourseOption>, random: Random = Random.Default): List<String> {
        val named = courses.filter { it.course.courseId > 0 }
            .map { cleanName(it.course.name) to it }
            .filter { it.first.isNotEmpty() }
        fun rank(option: CourseOption, has: Boolean?): Int {
            val eligible = eligibleCourse(option.course)
            return when (has) {
                true -> if (eligible) 0 else 1
                null -> if (eligible) 2 else 4
                false -> if (eligible) 3 else 5
            }
        }
        /** 질문 하나의 후보(이름, 순위)를 순위순으로. */
        fun candidates(content: (CourseOption) -> Boolean?): List<Pair<String, Int>> {
            // 같은 이름은 가장 좋은 순위 하나로 합친다. 이름순 정렬 후 섞어 입력 순서와 무관하게 한다.
            val best = named.groupBy({ it.first }, { rank(it.second, content(it.second)) }).mapValues { it.value.min() }
            val confirmed = best.filterValues { it <= 1 }
            val pool = if (confirmed.isNotEmpty()) confirmed else best
            return pool.keys.sorted().shuffled(random).sortedBy { pool.getValue(it) }.map { it to pool.getValue(it) }
        }
        /** 피할 과목을 빼고 고른다. 남는 게 없으면 첫 번째로 피할 과목만 빼고, 그래도 없으면 아무거나. */
        fun pick(list: List<String>, vararg avoid: String?): String? =
            list.firstOrNull { it !in avoid } ?: list.firstOrNull { it != avoid.firstOrNull() } ?: list.firstOrNull()

        val materials = candidates { it.hasMaterials }
        val assignments = candidates { it.hasRecentAssignment }
        // 과제 질문은 같은 순위 안에서 자료가 확인되지 않은 과목을 먼저 골라 자료 질문에 쓸 과목을 남긴다.
        val materialConfirmed = materials.filter { it.second <= 1 }.map { it.first }.toSet()
        val assignment = assignments.firstOrNull()?.let { (top, topRank) ->
            assignments.firstOrNull { it.second == topRank && it.first !in materialConfirmed }?.first ?: top
        }
        val materialNames = materials.map { it.first }
        val summary = pick(materialNames, assignment)
        val download = pick(materialNames, summary, assignment)
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
