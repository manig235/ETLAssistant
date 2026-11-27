package kr.snu.etlassistant.chat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextField
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.etl.ScorePosition
import kr.snu.etlassistant.etl.ScoreRow
import kr.snu.etlassistant.etl.ScoreSource
import kr.snu.etlassistant.etl.ScoreStats
import kr.snu.etlassistant.etl.ScoreStatsStore
import kr.snu.etlassistant.notify.CourseFilterStore
import kr.snu.etlassistant.notify.SnapshotCache
import kr.snu.etlassistant.notify.SnapshotFetcher
import java.time.Instant

/**
 * 성적 통계(2026-11-28, 서랍 → 성적 통계). 과목별로 저장된 점수 분포와 내 점수 위치 어림을 보이고 지울 수 있게 한다.
 * eTL 분포는 알림 확인·이 화면의 새로 고침(과제 목록 GET) 때 모이고, 공지에서 읽은 분포는 비서가 저장한다.
 * 숨긴 과목(과목별 알림 → 숨기기)은 빼고 보인다. 이 폰에만 저장된다.
 */
class ScoreStatsActivity : AppCompatActivity() {
    private val store by lazy { ScoreStatsStore(this) }
    private var rows by mutableStateOf<List<ScoreRow>>(emptyList())
    private var hiddenCount by mutableStateOf(0)
    private var loading by mutableStateOf(false)
    private var autoRefreshed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScoreStatsScreen(
                rows = rows, hiddenCount = hiddenCount, loading = loading,
                onBack = { finish() }, onRefresh = { refresh() },
                onDelete = { r -> store.delete(r.ids); reload() },
                onSetScore = { r, my, pts -> store.setMyScore(r.ids.first(), my, pts); reload() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
        // 받아 둔 과제 목록이 없거나 3시간보다 오래됐으면 뒤에서 한 번 새로 받는다(내 일정 화면과 같은 기준).
        val cachedAt = runCatching { SnapshotCache(this).load()?.takenAt }.getOrNull()
        if (!autoRefreshed && (cachedAt == null || System.currentTimeMillis() - cachedAt > 3 * 3600_000L)) {
            autoRefreshed = true
            refresh(quiet = true)
        }
    }

    private fun reload() {
        val filter = CourseFilterStore(this).load()
        val all = store.rows()
        rows = all.filter { !filter.hidden(it.entry.courseId) }
        hiddenCount = all.size - rows.size
    }

    private fun refresh(quiet: Boolean = false) {
        if (loading) return
        val token = EtlService.tokens(this).load()
        if (token.isNullOrEmpty()) { if (!quiet) toast("eTL에 연결하면 eTL 점수 분포를 받아 와요"); return }
        loading = true
        Thread {
            val msg = try {
                SnapshotCache(this).save(SnapshotFetcher.fetch(EtlClient(token), Instant.now(), announcementDays = 1))
                null
            } catch (_: EtlAuthException) {
                "eTL이 토큰을 거부했어요. 설정에서 다시 연결해 주세요"
            } catch (e: EtlException) {
                "새로 받지 못했어요: ${e.message}"
            } catch (e: Exception) {
                "새로 받지 못했어요(${e.javaClass.simpleName})"
            }
            runOnUiThread {
                loading = false
                reload()
                if (msg != null && !quiet) toast(msg)
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ScoreStatsScreen(
    rows: List<ScoreRow>, hiddenCount: Int, loading: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onDelete: (ScoreRow) -> Unit,
    onSetScore: (ScoreRow, Double?, Double?) -> Unit,
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        var deleting by remember { mutableStateOf<ScoreRow?>(null) }
        var editing by remember { mutableStateOf<ScoreRow?>(null) }
        Column(Modifier.fillMaxSize().background(c.backgroundNeutralPrimary).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary) }
                Text("성적 통계", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                if (loading) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { WantedCircularProgressIndicator(modifier = Modifier.size(20.dp)) }
                else IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "새로 받기", tint = c.foregroundNeutralSecondary) }
            }
            Text("시험·과제 점수 분포로 내 점수가 어디쯤인지 어림해요. 정확한 등수는 아니에요. 이 폰에만 저장돼요.",
                style = t.caption1Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("아직 저장된 성적 통계가 없어요", style = t.body1Medium, color = c.foregroundNeutralSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text("교수가 eTL에서 점수 분포를 공개하면 자동으로 모여요. 공지에 올라온 분포는 비서에게 \"공지 보고 성적 통계 저장해줘\"라고 해 보세요.",
                        style = t.body2Regular, color = c.foregroundNeutralTertiary)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val groups = rows.groupBy { it.entry.courseName.ifBlank { "과목 정보 없음" } }
                    for ((course, list) in groups) {
                        item(key = "h-$course") {
                            Text(NoticeCourse.short(course), style = t.body2Medium, color = c.foregroundNeutralSecondary,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp).semantics { heading() })
                        }
                        items(list, key = { it.ids.joinToString() }) { r -> ScoreCard(r, onDelete = { deleting = r }, onEdit = { editing = r }) }
                    }
                    if (hiddenCount > 0) item(key = "hidden") {
                        Text("숨긴 과목의 통계 ${hiddenCount}개는 빼고 보여요", style = t.caption1Regular, color = c.foregroundNeutralTertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
        editing?.let { r -> MyScoreDialog(r, onDismiss = { editing = null }, onSave = { my, pts -> onSetScore(r, my, pts); editing = null }) }
        deleting?.let { r ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                containerColor = c.backgroundNeutralPrimary,
                title = { Text("이 통계를 지울까요?", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
                text = {
                    Text("${r.entry.title} 통계를 이 폰에서 지워요." + if (ScoreSource.ETL in r.sources) " eTL에서 온 통계는 다시 받아 오지 않아요." else "",
                        style = t.body2Regular, color = c.foregroundNeutralSecondary)
                },
                confirmButton = { WantedTextButton(text = "지우기", onClick = { onDelete(r); deleting = null }) },
                dismissButton = { WantedTextButton(text = "취소", onClick = { deleting = null }) },
            )
        }
    }
}

/** 과목명 앞의 "2026-2 "와 뒤의 분반 "(001)"을 떼어 짧게. */
private object NoticeCourse {
    fun short(name: String): String = name.replace(Regex("^\\d{4}-\\d\\s+"), "").replace(Regex("\\s*\\(\\d{3}\\)$"), "")
}

@Composable
private fun ScoreCard(r: ScoreRow, onDelete: () -> Unit, onEdit: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val e = r.entry
    val pos = ScoreStats.position(e.stats, e.myScore)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceNeutralSecondary).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = t.body1Medium, color = c.foregroundNeutralPrimary)
                Text("${r.sourceLabel} · ${myDeadlineLabel(Instant.ofEpochMilli(e.fetchedAt))}", style = t.caption1Regular, color = c.foregroundNeutralTertiary)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "${e.title} 통계 지우기", tint = c.foregroundNeutralSecondary) }
        }
        Column(Modifier.padding(end = 12.dp)) {
            pos?.let {
                Spacer(Modifier.height(4.dp))
                Text(it.label, style = t.body1Bold, color = c.foregroundBrandPrimary)
            }
            e.myScore?.let { my ->
                Text("내 점수 ${ScoreStats.num(my)}" + (e.pointsPossible?.let { p -> " / ${ScoreStats.num(p)}" } ?: ""),
                    style = t.body2Medium, color = c.foregroundNeutralPrimary)
            }
            Spacer(Modifier.height(4.dp))
            Text(ScoreStats.statsText(e.stats), style = t.body2Regular, color = c.foregroundNeutralSecondary)
            ScoreStats.bar(e.stats, e.myScore, e.pointsPossible)?.let { b ->
                Spacer(Modifier.height(10.dp))
                ScoreBar(b, track = c.foregroundNeutralTertiary.copy(alpha = 0.35f), box = c.foregroundNeutralTertiary.copy(alpha = 0.6f),
                    mean = c.foregroundNeutralSecondary, mine = c.foregroundBrandPrimary,
                    description = listOfNotNull(pos?.label, e.myScore?.let { "내 점수 ${ScoreStats.num(it)}" }, ScoreStats.statsText(e.stats)).joinToString(", "))
                Spacer(Modifier.height(2.dp))
                Text(legend(b), style = t.caption1Regular, color = c.foregroundNeutralTertiary)
            }
            // 공지·직접 항목은 내 점수를 넣거나 고칠 수 있다(eTL에서 온 점수는 eTL 값 그대로).
            if (r.entry.source != ScoreSource.ETL) {
                val label = if (e.myScore == null) "내 점수 넣기" else "내 점수 고치기"
                WantedTextButton(text = label, onClick = onEdit, modifier = Modifier.semantics { contentDescription = "${e.title} $label" })
            }
            when (pos?.kind) {
                ScorePosition.Kind.QUARTILES -> Note("사분위 사이는 고르게 퍼졌다고 보고 어림했어요.")
                ScorePosition.Kind.NORMAL -> Note("평균과 표준편차만 있어 정규분포로 가정해 어림했어요.")
                ScorePosition.Kind.MEAN -> if (!e.stats.hasQuartiles && e.stats.stddev == null) Note("eTL은 최저·최고·평균만 알려 줘서 몇 % 인지는 어림하지 않아요.")
                null -> if (e.myScore == null) Note("내 점수가 없어 위치는 어림하지 않았어요.")
            }
        }
    }
}

/** 내 점수·만점 넣기. 숫자만, 분포와 같은 기준이어야 한다. */
@Composable
private fun MyScoreDialog(r: ScoreRow, onDismiss: () -> Unit, onSave: (Double?, Double?) -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val e = r.entry
    var my by remember { mutableStateOf(e.myScore?.let(ScoreStats::num).orEmpty()) }
    var pts by remember { mutableStateOf(e.pointsPossible?.let(ScoreStats::num).orEmpty()) }
    val myV = my.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val ptsV = pts.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val badNumber = (my.isNotBlank() && myV == null) || (pts.isNotBlank() && ptsV == null)
    val error = if (badNumber) "숫자만 넣어 주세요" else ScoreStats.validate(e.stats, myV, ptsV)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.backgroundNeutralPrimary,
        title = { Text("${e.title} 내 점수", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
        text = {
            Column {
                Text("통계와 같은 기준(배점)으로 넣어 주세요. 이 폰에만 저장돼요.", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                Spacer(Modifier.height(12.dp))
                WantedTextField(text = my, placeholder = "내 점수", background = c.surfaceNeutralSecondary,
                    onValueChange = { my = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                WantedTextField(text = pts, placeholder = "만점(모르면 비워 두기)", background = c.surfaceNeutralSecondary,
                    onValueChange = { pts = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) }, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = t.caption1Regular, color = c.foregroundNegativePrimary)
                }
            }
        },
        confirmButton = { WantedTextButton(text = "저장", onClick = { if (error == null) onSave(myV, ptsV) }) },
        dismissButton = { WantedTextButton(text = "취소", onClick = onDismiss) },
    )
}

private fun legend(b: ScoreStats.Bar): String = listOfNotNull(
    "● 내 점수".takeIf { b.my != null }, "| 평균".takeIf { b.mean != null }, "▭ 하위~상위 25%".takeIf { b.q1 != null && b.q3 != null },
    "양 끝 최저·최고".takeIf { b.hasMin && b.hasMax },
).joinToString("  ")

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = DesignSystemTheme.typography.caption1Regular, color = DesignSystemTheme.colors.foregroundNeutralTertiary)
}

/** 최저~최고 막대 위에 사분위 상자, 평균 눈금, 내 점수 점. */
@Composable
private fun ScoreBar(b: ScoreStats.Bar, track: Color, box: Color, mean: Color, mine: Color, description: String) {
    Canvas(Modifier.fillMaxWidth().height(22.dp).clearAndSetSemantics { contentDescription = description }) {
        val y = size.height / 2
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        fun x(f: Float) = pad + w * f
        drawLine(track, Offset(pad, y), Offset(pad + w, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        if (b.q1 != null && b.q3 != null) {
            val h = 12.dp.toPx()
            drawRoundRect(box, topLeft = Offset(x(b.q1), y - h / 2), size = Size((x(b.q3) - x(b.q1)).coerceAtLeast(2f), h), cornerRadius = CornerRadius(3.dp.toPx()))
        }
        b.mean?.let { drawLine(mean, Offset(x(it), y - 9.dp.toPx()), Offset(x(it), y + 9.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
        b.my?.let { drawCircle(mine, radius = 6.dp.toPx(), center = Offset(x(it), y)) }
    }
}
