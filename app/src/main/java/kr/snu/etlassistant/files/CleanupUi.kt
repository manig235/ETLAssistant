package kr.snu.etlassistant.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.theme.DesignSystemTheme

/** 고르기 화면 공통 설명. */
const val CLEANUP_QUESTION = "받은 강의자료를 폰에 얼마나 둘까요?"
const val CLEANUP_DESC = "비서가 폰 Download/etl에 받아 둔 eTL 파일을 고른 기간이 지나면 지워요. 비서가 만든 파일(영민이가 만든 파일 폴더)은 지우지 않아요."
const val CLEANUP_KEEP_HINT = "계속 두고 싶은 자료는 파일 카드 옆 공유 아이콘을 눌러 다른 곳에 저장하세요."

/** 기간 칩(2일 · 7일 · 30일 · 정리 안 함). [selected]가 null이면 아무것도 고르지 않은 상태. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanupChoices(selected: CleanupPeriod?, onSelect: (CleanupPeriod) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in CleanupPeriod.values()) {
            WantedChip(text = p.label, isActive = selected == p, onClick = { onSelect(p) })
        }
    }
}

/** 설정 화면 카드 안의 내용: 기간 칩, 설명, 최근 정리. 바꾸면 바로 저장한다. */
@Composable
fun DownloadCleanupSettingsContent() {
    val context = LocalContext.current.applicationContext
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    var selected by remember { mutableStateOf(DownloadCleanup.period(context)) }
    val last = remember(selected) { DownloadCleanup.lastRunLabel(context) }
    Text("받은 강의자료 자동 정리", style = t.body2Medium, color = c.foregroundNeutralPrimary)
    Spacer(Modifier.height(4.dp))
    Text(CLEANUP_DESC, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
    Text(CLEANUP_KEEP_HINT, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
    Spacer(Modifier.height(10.dp))
    CleanupChoices(selected) {
        selected = it
        DownloadCleanup.setPeriod(context, it)
    }
    Spacer(Modifier.height(8.dp))
    val status = when {
        selected == null -> "아직 고르지 않았어요. 고르기 전에는 아무것도 지우지 않아요."
        last != null -> last
        else -> null
    }
    status?.let { Text(it, style = t.caption1Regular, color = c.foregroundNeutralSecondary) }
}

/**
 * 이미 안내를 마친 사용자에게 업데이트 뒤 채팅 화면을 처음 열 때 한 번 기간을 묻는다.
 * "나중에" 버튼은 없다. 고르면 저장하고, 바깥을 눌러 닫으면 고른 것으로 치지 않고 다음에 한 번 더 묻는다
 * ([DownloadCleanupLogic.MAX_ASKS]번 닫으면 더 묻지 않고 정리하지 않는다. 설정에서 언제든 고를 수 있다).
 */
/** 이번에 받은 파일 정리 선택 창이 뜰 차례인가(다른 자동 창이 겹치지 않게 미룬다). */
fun cleanupPromptPending(context: android.content.Context): Boolean =
    runCatching { DownloadCleanupLogic.shouldAsk(DownloadCleanup.period(context), DownloadCleanup.asks(context)) }.getOrDefault(false)

@Composable
fun CleanupPrompt() {
    val context = LocalContext.current.applicationContext
    var handled by rememberSaveable { mutableStateOf(false) }
    val show = remember { DownloadCleanupLogic.shouldAsk(DownloadCleanup.period(context), DownloadCleanup.asks(context)) }
    if (!show || handled) return
    var selected by rememberSaveable { mutableStateOf(CleanupPeriod.DEFAULT) }
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    AlertDialog(
        onDismissRequest = {
            DownloadCleanup.markAsked(context)
            handled = true
        },
        containerColor = c.backgroundNeutralPrimary,
        title = { Text(CLEANUP_QUESTION, style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
        text = {
            Column {
                Text(CLEANUP_DESC, style = t.body2Regular, color = c.foregroundNeutralSecondary)
                Spacer(Modifier.height(12.dp))
                CleanupChoices(selected) { selected = it }
                Spacer(Modifier.height(8.dp))
                Text("$CLEANUP_KEEP_HINT 설정 → 받은 파일에서 언제든 바꿀 수 있어요.", style = t.caption1Regular, color = c.foregroundNeutralTertiary)
            }
        },
        confirmButton = {
            WantedTextButton(text = "저장", onClick = {
                DownloadCleanup.setPeriod(context, selected)
                handled = true
            })
        },
    )
}
