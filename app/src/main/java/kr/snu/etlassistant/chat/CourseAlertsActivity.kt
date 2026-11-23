package kr.snu.etlassistant.chat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kr.snu.etlassistant.etl.CourseAssignments
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlLogic.str
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.etl.TimetableStore
import kr.snu.etlassistant.notify.CourseFilterLogic
import kr.snu.etlassistant.notify.CourseFilterStore
import kr.snu.etlassistant.notify.CourseMode
import kr.snu.etlassistant.notify.CourseRef
import kr.snu.etlassistant.notify.SnapshotCache

/**
 * 설정 → 알림 → 과목별 알림. 이번 학기 과목마다 "알림 받기 / 알림 끄기 / 숨기기"를 고른다([CourseFilterStore]).
 * 목록은 최근에 받은 과목을 바로 보여 주고(없으면 받아 둔 과제·시간표의 과목), 오래됐으면 뒤에서 eTL 강의 목록(GET)을 다시 받는다.
 */
class CourseAlertsActivity : AppCompatActivity() {
    private val store by lazy { CourseFilterStore(this) }
    private var rows by mutableStateOf<List<CourseFilterLogic.CourseRow>>(emptyList())
    private var loading by mutableStateOf(false)
    private var hasToken by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CourseAlertsScreen(
                rows = rows,
                loading = loading,
                hasToken = hasToken,
                onBack = { finish() },
                onRefresh = ::refresh,
                onSelect = { course, mode -> store.setMode(course, mode); reload() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        hasToken = !EtlService.tokens(this).load().isNullOrEmpty()
        reload()
        val knownAt = store.loadAll().knownAt
        if (System.currentTimeMillis() - knownAt !in 0 until STALE_MS) refresh()
    }

    private fun reload() {
        val stored = store.loadAll()
        val fallback = if (stored.known.isNotEmpty()) emptyList() else CourseFilterLogic.fallbackCourses(
            SnapshotCache(this).load()?.assignments.orEmpty(),
            runCatching { TimetableStore(this).loadCatalog()?.courses.orEmpty() }.getOrDefault(emptyList()),
        )
        rows = CourseFilterLogic.rows(stored.known, fallback, stored.filter)
    }

    /** eTL 강의 목록(이번 학기)을 다시 받는다. 토큰은 eTL에만 보내고 결과·오류 문구에 넣지 않는다. */
    private fun refresh() {
        if (loading) return
        val token = EtlService.tokens(this).load()
        if (token.isNullOrEmpty()) return
        loading = true
        Thread {
            val msg = try {
                val list = CourseAssignments.currentCourses(EtlClient(token))
                    .map { CourseRef(it.optLong("id"), it.str("name").orEmpty()) }
                    .filter { it.id > 0 && it.name.isNotBlank() }
                store.saveKnown(list, System.currentTimeMillis())
                null
            } catch (_: EtlAuthException) {
                "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요"
            } catch (e: EtlException) {
                "과목 목록을 새로 받지 못했어요: ${e.message}"
            } catch (e: Exception) {
                "과목 목록을 새로 받지 못했어요(${e.javaClass.simpleName})"
            }
            runOnUiThread {
                loading = false
                reload()
                msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private companion object {
        const val STALE_MS = 6 * 3600 * 1000L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseAlertsScreen(
    rows: List<CourseFilterLogic.CourseRow>,
    loading: Boolean,
    hasToken: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (CourseRef, CourseMode) -> Unit,
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        Column(Modifier.fillMaxSize().background(c.backgroundNeutralPrimary).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary) }
                Text("과목별 알림", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                if (loading) WantedCircularProgressIndicator()
                else if (hasToken) IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "과목 목록 새로 받기", tint = c.foregroundNeutralSecondary) }
            }
            Text(
                "알림 끄기: 이 과목의 마감·공지·새 과제·성적·수업 알림을 보내지 않아요.\n" +
                    "숨기기: 알림도 끄고 내 일정·브리핑·일정 위젯에서도 과제와 공지를 빼요. 시간표는 그대로예요.\n" +
                    "비서에게 물어보면 숨긴 과목도 답해요. 설정은 이 폰에만 저장돼요.",
                style = t.caption1Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(
                        when {
                            !hasToken -> "eTL 토큰을 저장하면 수강 중인 과목이 보여요"
                            loading -> "과목 목록을 받는 중…"
                            else -> "과목을 찾지 못했어요"
                        },
                        style = t.body1Medium, color = c.foregroundNeutralSecondary,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.course.id }) { r ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceNeutralSecondary).padding(16.dp)) {
                            Text(TimetableLogic.shortName(r.course.name), style = t.body1Medium, color = c.foregroundNeutralPrimary)
                            if (r.stale) Text("이번 학기 목록에 없는 과목", style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (m in CourseMode.values()) {
                                    WantedChip(text = m.label, isActive = r.mode == m, onClick = { if (r.mode != m) onSelect(r.course, m) })
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
