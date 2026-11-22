package kr.snu.etlassistant.update

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.theme.DesignSystemTheme

/**
 * 채팅 화면을 열었을 때 새 버전이 있으면 띄우는 팝업(2026-11-22 사용자 요청: "업데이트 팝업이 앞에 뜨면 좋겠어").
 *
 * 마지막 확인 결과([UpdateStore.latest])로 바로 띄우고, 뒤에서 확인이 끝나면([UpdateChecker.checkedTick]) 다시 본다.
 * "나중에"·"받기"·바깥 누름 모두 그 버전을 하루 미룬다([UpdateLogic.shouldPrompt]). 업데이트 알림을 꺼 두면 띄우지 않는다.
 * 받기는 Release 페이지(허용된 주소만)를 브라우저로 열 뿐 앱이 APK를 받지 않는다.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val tick = UpdateChecker.checkedTick.value
    var snoozeTick by remember { mutableStateOf(0) }
    val store = remember { UpdateStore(context) }
    val info = remember(tick, snoozeTick) {
        val latest = store.latest
        val show = store.enabled && UpdateLogic.shouldPrompt(
            latest, UpdateChecker.currentVersion(context), store.promptSnoozedTag, store.promptSnoozedAtMs, System.currentTimeMillis(),
        )
        if (show) latest else null
    } ?: return

    fun snooze() {
        store.snoozePrompt(info.tag)
        snoozeTick++
    }

    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val body = UpdateLogic.promptBody(info, UpdateLogic.promptSummary(info.notes))
    AlertDialog(
        onDismissRequest = { snooze() },
        containerColor = c.backgroundNeutralPrimary,
        title = { Text(UpdateLogic.notificationTitle(info), style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
        text = if (body.isBlank()) null else {
            { Text(body, style = t.body2Regular, color = c.foregroundNeutralSecondary) }
        },
        confirmButton = {
            WantedTextButton(text = "받기", onClick = {
                snooze()
                if (UpdateLogic.isAllowedUrl(info.htmlUrl)) {
                    val opened = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    if (!opened) Toast.makeText(context, "링크를 열 앱이 없어요: ${info.htmlUrl}", Toast.LENGTH_LONG).show()
                }
            })
        },
        dismissButton = { WantedTextButton(text = "나중에", onClick = { snooze() }) },
    )
}
