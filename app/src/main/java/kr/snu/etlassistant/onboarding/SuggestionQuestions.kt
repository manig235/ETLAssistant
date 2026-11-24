package kr.snu.etlassistant.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kr.snu.etlassistant.etl.BoardSession
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.etl.Timetable
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.etl.TimetableStore
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.EtlLogic.str
import kr.snu.etlassistant.notify.CourseFilterStore
import kr.snu.etlassistant.notify.SnapshotCache
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 첫 실행 완료와 새 대화가 같은 질문을 사용한다.
 * 1) 폰에 받아 둔 것(시간표·과제 목록·콘텐츠 확인 캐시)만으로 바로 과목명을 붙이고,
 * 2) eTL·수강편람을 조회해 다시 고른다. 과목을 하나도 모르거나(토큰 저장 직후, 네트워크 실패) 조회가 끝나지 않았으면
 *    일반 문구로 굳히지 않고 잠시 뒤 다시 시도한다. 재구성·화면 회전 때는 같은 시드로 같은 과목을 고른다.
 */
@Composable
fun rememberSuggestionQuestions(): List<String> {
    val context = LocalContext.current.applicationContext
    val seed = rememberSaveable { Random.nextInt() }
    var questions by rememberSaveable { mutableStateOf(OnboardingLogic.suggestions) }
    var settled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (settled) return@LaunchedEffect
        val offline = withContext(Dispatchers.IO) { runCatching { suggestionOptions(context, online = false) }.getOrNull() }
        offline?.let { questions = OnboardingLogic.personalizedSuggestions(it.options, Random(seed)) }
        for (attempt in 0 until RETRY_DELAYS_MS.size + 1) {
            val result = withContext(Dispatchers.IO) { runCatching { suggestionOptions(context, online = true) }.getOrNull() }
            if (result != null && result.options.isNotEmpty())
                questions = OnboardingLogic.personalizedSuggestions(result.options, Random(seed))
            if (result?.complete == true) {
                settled = true
                return@LaunchedEffect
            }
            delay(RETRY_DELAYS_MS.getOrNull(attempt) ?: return@LaunchedEffect)
        }
    }
    return questions
}

private val RETRY_DELAYS_MS = listOf(10_000L, 30_000L, 60_000L)

/** [complete]: 토큰으로 이번 학기 과목 목록을 받았고 콘텐츠 확인까지 시도했다(더 다시 할 필요 없음). */
private class SuggestionOptions(val options: List<OnboardingLogic.CourseOption>, val complete: Boolean)

private data class CachedSuggestionContent(val checkedAt: Instant, val content: SuggestionContent)
private val contentCache = ConcurrentHashMap<Pair<String, Long>, CachedSuggestionContent>()

/** 콘텐츠를 확인할 과목 수 상한(3~4학점 과목이 없을 때 다른 과목을 확인하는 경우). */
private const val MAX_CONTENT_CHECKS = 8

/**
 * 콘텐츠 확인은 계정별로 메모리에 15분만 재사용한다. 실패 결과는 캐시하지 않는다.
 * [online]이 false면 네트워크 없이 폰에 있는 것만 쓴다.
 */
private suspend fun suggestionOptions(context: Context, online: Boolean): SuggestionOptions = coroutineScope {
    val token = runCatching { EtlService.tokens(context).load() }.getOrNull()?.takeIf { it.isNotEmpty() }
    val (courses, listed) = suggestionCourses(context, if (online) token else null)
    if (courses.isEmpty()) return@coroutineScope SuggestionOptions(emptyList(), false)
    val owner = token?.let { BoardSession.ownerOf(it) }
    val now = Instant.now()
    contentCache.entries.removeAll { java.time.Duration.between(it.value.checkedAt, now).seconds !in 0 until 900 }
    val eligible = courses.filter(OnboardingLogic::eligibleCourse)
    val toCheck = (eligible.ifEmpty { courses.take(MAX_CONTENT_CHECKS) }).map { it.courseId }.toSet()
    val client = if (online && token != null) runCatching { EtlClient(token) }.getOrNull() else null
    val options = courses.map { course ->
        async {
            val key = owner?.let { it to course.courseId }
            val cached = key?.let { contentCache[it]?.content }
            val content = cached ?: if (client != null && course.courseId in toCheck)
                SuggestionContentLookup(client::getAll, client::getObject).check(course.courseId, now).also {
                    if (key != null && it.hasMaterials != null && it.hasRecentAssignment != null)
                        contentCache[key] = CachedSuggestionContent(now, it)
                }
            else null
            OnboardingLogic.CourseOption(course, content?.hasMaterials, content?.hasRecentAssignment)
        }
    }.awaitAll()
    SuggestionOptions(options, online && client != null && listed)
}

/** 받아 둔 과제 목록·시간표가 이만큼 오래됐으면 지난 학기 것일 수 있어 쓰지 않는다. */
private const val STALE_MS = 60L * 24 * 3600 * 1000

/**
 * 추천 후보 과목과, eTL에서 이번 학기 과목 목록을 새로 받았는지.
 * 시간표(학점이 있으면 3~4학점 우선) → eTL 강의 목록 → 받아 둔 과제 목록 순으로 합치고 숨긴 과목은 뺀다.
 * [token]이 없으면 네트워크 없이 캐시만 쓴다. 학점 없는 옛 캐시는 시간표 조회로 보완한다.
 */
private fun suggestionCourses(context: Context, token: String?): Pair<List<TimetableLogic.CourseMeetings>, Boolean> {
    val store = TimetableStore(context)
    val cached = store.loadCatalog()
    val now = System.currentTimeMillis()
    val age = cached?.let { now - it.fetchedAt }
    val fresh = age != null && age in 0 until Timetable.CACHE_MS
    var timetable = cached?.courses.orEmpty().filter { it.courseId > 0 }.takeIf { age != null && age in 0 until STALE_MS }.orEmpty()
    val extra = mutableListOf<Pair<Long, String>>()
    var listed = false
    if (token != null) {
        val client = runCatching { EtlClient(token) }.getOrNull()
        val hasEligible = timetable.any(OnboardingLogic::eligibleCourse)
        if (client != null) {
            if (fresh && (hasEligible || timetable.isNotEmpty() && timetable.all { it.credits != null })) {
                listed = true
            } else {
                runCatching { Timetable.weekly(client, store, now, force = timetable.any { it.credits == null }).courses }
                    .onSuccess { timetable = it.filter { c -> c.courseId > 0 }; listed = true }
            }
            if (!listed) {
                // 수강편람 조회가 통째로 실패해도 eTL 강의 목록만으로 과목명을 쓴다.
                runCatching {
                    EtlLogic.currentCourses(client.getAll("/api/v1/courses?enrollment_state=active&include[]=term&per_page=100"))
                }.onSuccess { list ->
                    extra += list.map { it.optLong("id") to it.str("name").orEmpty() }
                    listed = true
                }
            }
        }
    }
    // 방금 받은 목록이 있으면 그것이 이번 학기 기준이다. 못 받았을 때만 받아 둔 과제 목록의 과목을 더한다.
    if (!listed) runCatching { SnapshotCache(context).load() }.getOrNull()
        ?.takeIf { now - it.takenAt in 0 until STALE_MS }
        ?.assignments?.forEach { extra += it.courseId to it.courseName }
    val filter = runCatching { CourseFilterStore(context).load() }.getOrNull()
    return OnboardingLogic.knownCourses(timetable, extra) { filter?.hidden(it) == true } to listed
}
