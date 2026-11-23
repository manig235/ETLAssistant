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

/** 첫 실행 완료와 새 대화가 같은 질문을 사용한다. 재구성·화면 회전 때 과목을 다시 뽑지 않는다. */
@Composable
fun rememberSuggestionQuestions(): List<String> {
    val context = LocalContext.current.applicationContext
    var questions by rememberSaveable { mutableStateOf(OnboardingLogic.suggestions) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            val courses = withContext(Dispatchers.IO) { suggestionOptions(context) }
            questions = OnboardingLogic.personalizedSuggestions(courses)
            loaded = true
        }
    }
    return questions
}

private data class CachedSuggestionContent(val checkedAt: Instant, val content: SuggestionContent)
private val contentCache = ConcurrentHashMap<Pair<String, Long>, CachedSuggestionContent>()

/** 콘텐츠 확인은 계정별로 메모리에 15분만 재사용한다. 실패 결과는 캐시하지 않는다. */
private suspend fun suggestionOptions(context: Context): List<OnboardingLogic.CourseOption> = coroutineScope {
    val courses = suggestionCourses(context).filter(OnboardingLogic::eligibleCourse)
    if (courses.isEmpty()) return@coroutineScope emptyList()
    val token = runCatching { EtlService.tokens(context).load() }.getOrNull()
        ?: return@coroutineScope emptyList()
    val client = runCatching { EtlClient(token) }.getOrNull() ?: return@coroutineScope emptyList()
    val owner = BoardSession.ownerOf(token)
    val now = Instant.now()
    contentCache.entries.removeAll { java.time.Duration.between(it.value.checkedAt, now).seconds !in 0 until 900 }
    courses.map { course ->
        async {
            val key = owner to course.courseId
            val content = contentCache[key]?.content ?: SuggestionContentLookup(client::getAll, client::getObject)
                .check(course.courseId, now).also {
                    if (it.hasMaterials != null && it.hasRecentAssignment != null)
                        contentCache[key] = CachedSuggestionContent(now, it)
                }
            OnboardingLogic.CourseOption(course, content.hasMaterials == true, content.hasRecentAssignment == true)
        }
    }.awaitAll()
}

/** 학점 없는 옛 캐시는 기존 시간표 조회로 보완한다. 실패하면 과목을 지어 넣지 않는다. */
private fun suggestionCourses(context: Context): List<TimetableLogic.CourseMeetings> {
    val store = TimetableStore(context)
    val cached = store.loadCatalog()
    val now = System.currentTimeMillis()
    val fresh = cached != null && now - cached.fetchedAt in 0 until Timetable.CACHE_MS
    val known = cached?.courses.orEmpty().filter { it.courseId > 0 }
    val hasEligible = known.any { it.credits?.let { c -> c in 3.0..4.0 } == true }
    if (fresh && (hasEligible || known.isNotEmpty() && known.all { it.credits != null })) return known
    return try {
        val token = EtlService.tokens(context).load()
        if (token.isNullOrEmpty()) known else Timetable.weekly(EtlClient(token), store, now,
            force = known.any { it.credits == null }).courses
    } catch (_: Exception) {
        known
    }
}
