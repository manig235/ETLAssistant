package kr.snu.etlassistant.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.NotificationsNone
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
import androidx.compose.ui.semantics.Role as SemRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextField
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import java.time.ZoneId

/**
 * 대화 목록(서랍). 맨 위 "새 대화", 아래는 오늘/어제/지난 7일/이전으로 묶은 대화.
 * 항목마다 연필(이름 바꾸기)과 휴지통(삭제)을 항상 보인다(길게 누르기는 찾기 어려워서). 길게 누르기 메뉴도 남겨 둔다.
 * 답하는 대화([running])는 제목 옆에 작은 돌아가는 표시를 둔다(2026-11-27). 답하는 중에도 열기·이름 바꾸기가 되고,
 * 지우면 그 답변을 먼저 멈춘 뒤 지운다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatDrawer(
    chats: List<ChatMeta>,
    currentId: String,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    running: Set<String> = emptySet(),
    onMyDeadlines: () -> Unit = {},
    onTimetable: () -> Unit = {},
    onHistory: () -> Unit = {},
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    var menuFor by remember { mutableStateOf<ChatMeta?>(null) }
    var renaming by remember { mutableStateOf<ChatMeta?>(null) }
    var deleting by remember { mutableStateOf<ChatMeta?>(null) }
    val groups = remember(chats) { ChatTitles.grouped(chats, System.currentTimeMillis(), ZoneId.systemDefault()) }

    Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
        Text("대화", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = SemRole.Button, onClick = onNew)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AddComment, contentDescription = null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("새 대화", style = t.body1Medium, color = c.foregroundBrandPrimary)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = SemRole.Button, onClick = onMyDeadlines)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.EventNote, contentDescription = null, tint = c.foregroundNeutralPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("내 일정", style = t.body1Medium, color = c.foregroundNeutralPrimary)
        }
        for ((icon, label, action) in listOf(
            Triple(Icons.Outlined.CalendarViewWeek, "시간표", onTimetable),
            Triple(Icons.Outlined.NotificationsNone, "알림 기록", onHistory),
        )) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = SemRole.Button, onClick = action)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = c.foregroundNeutralPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(label, style = t.body1Medium, color = c.foregroundNeutralPrimary)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (chats.isEmpty()) {
            Text("아직 대화가 없어요", style = t.body2Regular, color = c.foregroundNeutralTertiary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            for ((group, list) in groups) {
                item(key = "h-${group.name}") {
                    Text(
                        group.label,
                        style = t.caption1Bold,
                        color = c.foregroundNeutralSecondary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(list, key = { it.id }) { m ->
                    val selected = m.id == currentId
                    val iconTint = c.foregroundNeutralSecondary
                    val answering = m.id in running
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(if (selected) Modifier.background(c.surfaceBrandSubtle) else Modifier)
                            .combinedClickable(
                                role = SemRole.Button,
                                onClickLabel = "열기",
                                onLongClickLabel = "이름 바꾸기·삭제",
                                onClick = { onOpen(m.id) },
                                onLongClick = { menuFor = m },
                            )
                            .semantics { if (answering) stateDescription = "답변 중" }
                            .padding(start = 12.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            m.title,
                            style = t.body2Medium,
                            color = if (selected) c.foregroundBrandPrimary else c.foregroundNeutralPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp),
                        )
                        if (answering) {
                            Spacer(Modifier.width(8.dp))
                            WantedCircularProgressIndicator(Modifier.size(14.dp).clearAndSetSemantics { })
                        }
                        Spacer(Modifier.weight(1f).height(1.dp))
                        IconButton(
                            onClick = { renaming = m },
                            modifier = Modifier.size(40.dp).semantics { contentDescription = "'${m.title}' 이름 바꾸기" },
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { deleting = m },
                            modifier = Modifier.size(40.dp).semantics { contentDescription = "'${m.title}' 지우기" },
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { m ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = c.backgroundNeutralPrimary,
            title = { Text(m.title, style = t.headline2Bold, color = c.foregroundNeutralPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    WantedTextButton(text = "이름 바꾸기", onClick = { menuFor = null; renaming = m })
                    WantedTextButton(text = "삭제", onClick = { menuFor = null; deleting = m })
                }
            },
            confirmButton = { WantedTextButton(text = "닫기", onClick = { menuFor = null }) },
        )
    }

    renaming?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = c.backgroundNeutralPrimary,
            title = { Text("이름 바꾸기", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
            text = {
                WantedTextField(
                    text = name,
                    placeholder = "대화 이름",
                    background = c.backgroundNeutralPrimary,
                    onValueChange = { name = it.take(ChatTitles.MAX_LEN) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                WantedTextButton(text = "저장", onClick = {
                    if (name.isNotBlank()) onRename(m.id, name)
                    renaming = null
                })
            },
            dismissButton = { WantedTextButton(text = "취소", onClick = { renaming = null }) },
        )
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = c.backgroundNeutralPrimary,
            title = { Text("이 대화를 지울까요?", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
            text = {
                val tail = if (m.id in running) " 답하는 중인 답변은 멈춰요." else ""
                Text("\"${m.title}\" 대화가 목록에서 사라지고 되돌릴 수 없어요.$tail", style = t.body2Regular, color = c.foregroundNeutralSecondary)
            },
            confirmButton = { WantedTextButton(text = "삭제", onClick = { onDelete(m.id); deleting = null }) },
            dismissButton = { WantedTextButton(text = "취소", onClick = { deleting = null }) },
        )
    }
}
