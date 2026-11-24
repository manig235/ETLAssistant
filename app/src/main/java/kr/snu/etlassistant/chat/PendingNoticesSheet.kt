package kr.snu.etlassistant.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kr.snu.etlassistant.notify.MyKind
import kr.snu.etlassistant.notify.NoticeText
import kr.snu.etlassistant.notify.PendingNotice
import kr.snu.etlassistant.notify.PendingNoticeStore
import kr.snu.etlassistant.notify.ScheduleDraft
import java.time.Instant

/**
 * 채팅 화면의 "등록 안 한 시험·과제 공지" 하단 시트(2026-11-24). 행마다 [내 일정에 등록](미리 채운 추가 창) [비서에게 부탁](입력창 채우기)
 * [무시]. 하단 "나중에"는 닫기만 한다(새 공지가 올 때까지 자동으로 다시 뜨지 않는다). 공지는 알림 확인 때 받아 둔 것만 쓴다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PendingNoticesSheet(vm: ChatViewModel) {
    val context = LocalContext.current
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val list = vm.pendingNotices
    if (list.isEmpty()) { vm.pendingSheet = false; return }
    fun close() { vm.pendingSheet = false }
    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.backgroundNeutralPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text("등록 안 한 공지 ${list.size}개", style = t.headline2Bold, color = c.foregroundNeutralPrimary)
            Spacer(Modifier.height(4.dp))
            Text("누르면 공지 내용을 채운 창이 열려요(그 창에서 비서에게 채워 달라고 할 수도 있어요). eTL 과제로 이미 올라온 마감이면 넣지 않아도 돼요.",
                style = t.caption1Regular, color = c.foregroundNeutralSecondary)
            Spacer(Modifier.height(12.dp))
            for (p in list.take(20)) {
                PendingRow(p,
                    onRegister = {
                        close()
                        runCatching { context.startActivity(DraftIntents.open(context, p.draft)) }
                    },
                    onIgnore = {
                        PendingNoticeStore(context).ignore(p.id)
                        vm.pendingNotices = vm.pendingNotices.filter { it.id != p.id }
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WantedTextButton(text = "나중에", onClick = ::close,
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "나중에"; role = Role.Button })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingRow(p: PendingNotice, onRegister: () -> Unit, onIgnore: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val draft = ScheduleDraft.fromJson(p.draft)
    val exam = p.kind == MyKind.EXAM
    val label = draft?.category?.label ?: if (exam) "시험" else "과제"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceNeutralSecondary).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = t.caption1Bold,
                color = if (exam) c.foregroundCautionaryPrimary else c.foregroundBrandPrimary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (exam) c.surfaceCautionaryPrimary else c.backgroundNeutralPrimary).padding(horizontal = 6.dp, vertical = 1.dp))
            Spacer(Modifier.width(6.dp))
            Text(NoticeText.course(p.courseName) + " · " + NoticeText.dateLabel(Instant.ofEpochMilli(p.postedAt)).substringBefore(' '),
                style = t.caption1Regular, color = c.foregroundNeutralSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Text(NoticeText.safe(p.title, 80), style = t.body1Medium, color = c.foregroundNeutralPrimary)
        (draft?.start?.let { NoticeText.dateLabel(it) + (draft.end?.let { e -> "~" + NoticeText.dateLabel(e).substringAfter(' ') } ?: "") }
            ?: draft?.day?.let { kr.snu.etlassistant.notify.Agenda.dateLabel(it) })?.let {
            Text("공지에서 찾은 날짜: $it", style = t.caption1Regular, color = c.foregroundBrandPrimary)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val action = draft?.actionLabel ?: "내 일정에 등록"
            WantedButton(text = action, type = ButtonType.PRIMARY, size = ButtonSize.SMALL, variant = ButtonVariant.SOLID, onClick = onRegister,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${p.title} $action"; role = Role.Button })
            WantedTextButton(text = "무시", onClick = onIgnore,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${p.title} 무시"; role = Role.Button })
        }
    }
}
