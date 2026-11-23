package kr.snu.etlassistant.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.SubmissionCheck

/** "내 일정"의 "제출 확인" 대화상자 상태. */
sealed class SubmissionCheckUi {
    abstract val title: String
    data class Loading(override val title: String) : SubmissionCheckUi()
    data class Done(override val title: String, val result: SubmissionCheck.Live) : SubmissionCheckUi()
    data class Failed(override val title: String, val message: String) : SubmissionCheckUi()
}

/**
 * 제출 확인 결과. eTL 제출 기록(과제 + 내 제출, GET 두 번)만 보고 파일은 받지 않는다.
 * 파일 이름·문구는 eTL 데이터라 그대로 보여 주기만 한다.
 */
@Composable
internal fun SubmissionCheckDialog(state: SubmissionCheckUi, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = c.backgroundNeutralPrimary,
            title = {
                Text(
                    when (state) {
                        is SubmissionCheckUi.Loading -> "제출 확인 중"
                        is SubmissionCheckUi.Failed -> "제출을 확인하지 못했어요"
                        is SubmissionCheckUi.Done -> when {
                            state.result.ok && state.result.warnings.isEmpty() -> "제출에 문제 없어요"
                            state.result.ok -> "제출됐어요 · 참고할 점 있음"
                            !state.result.submitted -> "제출 기록이 없어요"
                            else -> "확인이 필요해요"
                        }
                    },
                    style = t.headline2Bold, color = c.foregroundNeutralPrimary,
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.title, style = t.body2Medium, color = c.foregroundNeutralPrimary)
                    when (state) {
                        is SubmissionCheckUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WantedCircularProgressIndicator()
                            Text("eTL 제출 기록을 받는 중…", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                        }
                        is SubmissionCheckUi.Failed -> Text(state.message, style = t.body2Regular, color = c.foregroundNeutralSecondary)
                        is SubmissionCheckUi.Done -> {
                            val r = state.result
                            Text(
                                "제출 ${r.submittedAt?.let { EtlLogic.fmtKst(it) } ?: "없음"} · 마감 ${EtlLogic.fmtKst(r.due)}",
                                style = t.caption1Regular, color = c.foregroundNeutralSecondary,
                            )
                            if (r.files.isNotEmpty()) {
                                Text("제출한 파일: " + r.files.joinToString(", "), style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                            }
                            for (p in r.problems) Text("• $p", style = t.body2Regular, color = c.foregroundNegativePrimary)
                            for (w in r.warnings) Text("• $w", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                            Text(
                                "eTL 제출 기록만 봤어요(파일 내용은 받지 않았어요).",
                                style = t.caption1Regular, color = c.foregroundNeutralTertiary,
                            )
                        }
                    }
                }
            },
            confirmButton = { WantedTextButton(text = "닫기", onClick = onDismiss) },
            dismissButton = {
                val url = (state as? SubmissionCheckUi.Done)?.result?.url
                if (url != null) WantedTextButton(text = "eTL에서 열기", onClick = { onOpen(url) })
            },
        )
    }
}
