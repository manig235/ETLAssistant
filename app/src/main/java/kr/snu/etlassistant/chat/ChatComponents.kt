package kr.snu.etlassistant.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role as SemRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo
import kr.snu.etlassistant.onboarding.OnboardingLogic

// ---- 상단 바 / 모델 칩 --------------------------------------------------------

@Composable
fun ChatTopBar(onMenu: () -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Outlined.Menu, contentDescription = "대화 목록", tint = c.foregroundNeutralPrimary)
        }
        Text("영민이", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
        IconButton(onClick = onNewChat) {
            Icon(Icons.Outlined.AddComment, contentDescription = "새 대화", tint = c.foregroundNeutralPrimary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "설정", tint = c.foregroundNeutralPrimary)
        }
    }
}

@Composable
fun ModelChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surfaceNeutralSecondary)
            .clickable(onClickLabel = "AI 모델 고르기", role = SemRole.Button, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Tune, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("AI 모델 · $label", style = t.caption1Medium, color = c.foregroundNeutralPrimary, maxLines = 1)
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(16.dp))
    }
}

// ---- 배너 ---------------------------------------------------------------------

@Composable
fun StatusBanner(spec: BannerSpec, onClick: () -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val clickable = spec.action != BannerAction.NONE
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceCautionaryPrimary)
            .then(if (clickable) Modifier.clickable(role = SemRole.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = c.foregroundCautionaryPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(spec.text, style = t.body2Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
        if (spec.dismissible && onDismiss != null) {
            // 닫기(나중에). 일정 기간 숨기고, 조건이 그대로면 다시 보인다.
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "나중에", tint = c.foregroundNeutralSecondary, modifier = Modifier.size(18.dp))
            }
        } else if (clickable) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

// ---- 빈 화면 ------------------------------------------------------------------

private val SuggestionIcons = listOf(Icons.Outlined.Event, Icons.Outlined.Campaign, Icons.Outlined.TaskAlt)

@Composable
fun EmptyState(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(c.surfaceBrandSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("무엇을 도와드릴까요?", style = t.title1Bold, color = c.foregroundNeutralPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "마감, 공지, 제출 확인, 강의 파일까지 물어보세요.",
            style = t.body1ReadingRegular,
            color = c.foregroundNeutralSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OnboardingLogic.suggestions.forEachIndexed { i, s ->
                SuggestionCard(SuggestionIcons[i % SuggestionIcons.size], s) { onSuggestion(s) }
            }
        }
    }
}

@Composable
private fun SuggestionCard(icon: ImageVector, text: String, onClick: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceNeutralSecondary) // surfaceNeutralPrimary는 배경과 같은 흰색이라 카드로 보이지 않는다
            .clickable(role = SemRole.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = t.body1Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
    }
}

// ---- 말풍선 -------------------------------------------------------------------

private val BubbleRadius = 20.dp
private val BubbleTail = 6.dp

/** 한 줄의 대화. 사용자는 오른쪽 파랑, 어시스턴트는 왼쪽 회색, 안내문은 가운데 작은 글씨. */
@Composable
fun MessageRow(
    role: Role,
    text: String,
    maxBubbleWidth: Dp,
    onCopy: (String) -> Unit,
    onLink: (String) -> Unit,
    attachments: List<Attachment> = emptyList(),
    attachmentActions: AttachmentActions? = null,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    if (attachments.isNotEmpty() && attachmentActions != null && role == Role.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AttachmentGroup(attachments, alignEnd = true, maxWidth = maxBubbleWidth, actions = attachmentActions)
            if (text.isNotBlank()) MessageRow(role, text, maxBubbleWidth, onCopy, onLink)
        }
        return
    }
    when (role) {
        Role.FILES -> if (attachmentActions != null) {
            AttachmentGroup(attachments, alignEnd = false, maxWidth = maxBubbleWidth, actions = attachmentActions)
        }
        Role.NOTICE -> Text(
            text,
            style = t.caption1Regular,
            color = c.foregroundNeutralTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Role.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            // 길게 누르면 시스템 텍스트 선택(부분 선택·복사). 전체 복사는 접근성 동작으로 남긴다.
            SelectionContainer {
            Text(
                text,
                style = t.body1ReadingRegular,
                color = c.staticWhite,
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .copyOnLongPress(text, onCopy)
                    .clip(RoundedCornerShape(BubbleRadius, BubbleRadius, BubbleTail, BubbleRadius))
                    .background(c.surfaceBrandPrimary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            }
        }
        Role.ASSISTANT -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            val style = t.body1ReadingRegular
            val body = rememberMarkdown(
                text = text.ifEmpty { "…" },
                codeBackground = c.surfaceNeutralTertiary,
                linkColor = c.foregroundBrandPrimary,
                baseFontSize = style.fontSize,
                onLink = onLink,
            )
            SelectionContainer {
            Text(
                body,
                style = style,
                color = c.foregroundNeutralPrimary,
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .copyOnLongPress(text, onCopy)
                    .clip(RoundedCornerShape(BubbleRadius, BubbleRadius, BubbleRadius, BubbleTail))
                    .background(c.surfaceNeutralSecondary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            }
        }
    }
}

/**
 * 손으로 길게 누르면 [SelectionContainer]가 부분 선택을 띄운다. 접근성 서비스에는 "전체 복사" 동작으로 본문 전체를 복사하게 노출한다.
 */
private fun Modifier.copyOnLongPress(text: String, onCopy: (String) -> Unit): Modifier =
    this.semantics { onLongClick(label = "전체 복사") { onCopy(text); true } }

// ---- 활동 상태 한 줄 ------------------------------------------------------------

/** "조회하는 중…" 같은 한 줄. 살아 있다는 느낌을 주는 느린 깜빡임. */
@Composable
fun ActivityLine(label: String, modifier: Modifier = Modifier) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val transition = rememberInfiniteTransition(label = "activity")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "activityAlpha",
    )
    Text(
        label,
        style = t.caption1Medium,
        color = c.foregroundNeutralSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .alpha(alpha),
    )
}

// ---- 입력창 -------------------------------------------------------------------

/** 첨부를 어디서 고를지. */
enum class AttachSource { PHOTO, CAMERA, FILE }

@Composable
fun InputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    mode: SendMode,
    onSend: () -> Unit,
    onAttach: (AttachSource) -> Unit,
    attachEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surfaceNeutralSecondary)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        AttachButton(enabled = attachEnabled, onAttach = onAttach, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            textStyle = t.body1Regular.copy(color = c.foregroundNeutralPrimary),
            cursorBrush = SolidColor(c.foregroundBrandPrimary),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text("메시지를 입력하세요", style = t.body1Regular, color = c.foregroundNeutralQuaternary)
                    }
                    inner()
                }
            },
        )
        SendButton(mode, onSend, Modifier.padding(6.dp))
    }
}

@Composable
private fun AttachButton(enabled: Boolean, onAttach: (AttachSource) -> Unit, modifier: Modifier = Modifier) {
    val c = DesignSystemTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "첨부",
                tint = if (enabled) c.foregroundNeutralPrimary else c.foregroundDisablePrimary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.backgroundNeutralPrimary) {
            AttachItem(Icons.Outlined.PhotoLibrary, "사진") { open = false; onAttach(AttachSource.PHOTO) }
            AttachItem(Icons.Outlined.PhotoCamera, "사진 찍기") { open = false; onAttach(AttachSource.CAMERA) }
            AttachItem(Icons.Outlined.AttachFile, "파일") { open = false; onAttach(AttachSource.FILE) }
        }
    }
}

@Composable
private fun AttachItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    DropdownMenuItem(
        text = { Text(label, style = t.body1Regular, color = c.foregroundNeutralPrimary) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = c.foregroundNeutralSecondary) },
        onClick = onClick,
    )
}

@Composable
private fun SendButton(mode: SendMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DesignSystemTheme.colors
    val (bg, fg) = when (mode) {
        SendMode.SEND -> c.surfaceBrandPrimary to c.staticWhite
        SendMode.STOP -> c.surfaceNeutralInverse to c.foregroundNeutralInverse
        SendMode.DISABLED -> c.surfaceDisablePrimary to c.foregroundDisablePrimary
    }
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = mode != SendMode.DISABLED, role = SemRole.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (mode == SendMode.STOP) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
            contentDescription = if (mode == SendMode.STOP) "답변 중단" else "보내기",
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---- 모델 바텀시트 내용 ---------------------------------------------------------

/** 바텀시트 안: 제목 + 닫기, 아래에 스크롤되는 모델 선택. 시트 껍데기는 [ChatScreen]이 만든다. */
@Composable
fun ModelSheetContent(
    models: List<ModelInfo>,
    choice: ModelChoice,
    loading: Boolean,
    onChoice: (ModelChoice) -> Unit,
    onClose: () -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI 모델", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
            WantedTextButton(text = "완료", onClick = onClose)
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            ModelPickerContent(models = models, choice = choice, loading = loading, onChoice = onChoice)
        }
    }
}
