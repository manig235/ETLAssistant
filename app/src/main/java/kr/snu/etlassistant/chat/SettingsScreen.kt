package kr.snu.etlassistant.chat

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.input.input.control.CheckBoxState
import com.wanted.android.wanted.design.input.input.control.CheckBoxStyle
import com.wanted.android.wanted.design.input.input.control.WantedCheckBox
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextField
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kotlinx.coroutines.delay
import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.LoginKind
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.notify.NotifySettings

/** 화면이 그리는 값. 액티비티가 세션 이벤트와 저장소 상태를 여기에 반영한다. */
class SettingsState {
    var sessionState by mutableStateOf(SessionState.STOPPED)
    var sessionDetail by mutableStateOf<String?>(null)
    var account by mutableStateOf<AccountInfo?>(null)

    /** 로그인 진행 중이면 그 안내(없으면 null). */
    var loginPrompt by mutableStateOf<SessionEvent.LoginPrompt?>(null)
    var loginExpiresAt by mutableLongStateOf(0L)

    var models by mutableStateOf<List<ModelInfo>>(emptyList())
    var modelsLoading by mutableStateOf(false)
    var modelChoice by mutableStateOf(ModelChoice())

    var hasApiKey by mutableStateOf(false)

    /** 마스킹한 API 키. 저장돼 있는데 null이면 복호화에 실패한 것이다. */
    var apiKeyMasked by mutableStateOf<String?>(null)
    var useApiKey by mutableStateOf(false)
    var hasEtlToken by mutableStateOf(false)

    var notify by mutableStateOf(NotifySettings())
    var notifyPermitted by mutableStateOf(true)
    var lastCheckText by mutableStateOf("")
    var lastBriefText by mutableStateOf("")
}

/** 화면이 바깥(액티비티, 세션, 시스템)에 부탁하는 일들. 저장 계열은 성공 여부를 돌려줘서 입력칸을 비울지 정한다. */
class SettingsActions(
    val back: () -> Unit,
    val loginBrowser: () -> Unit,
    val loginDevice: () -> Unit,
    val logout: () -> Unit,
    val cancelLogin: () -> Unit,
    val reopenLogin: (String) -> Unit,
    val copyCode: (String) -> Unit,
    val saveApiKey: (String) -> Boolean,
    val clearApiKey: () -> Unit,
    val setUseApiKey: (Boolean) -> Unit,
    val saveEtlToken: (String) -> Boolean,
    val clearEtlToken: () -> Unit,
    val openEtlSettings: () -> Unit,
    val updateNotify: ((NotifySettings) -> NotifySettings) -> Unit,
    val pickTime: (initialMinutes: Int, onPicked: (Int) -> Unit) -> Unit,
    val requestNotifyPermission: () -> Unit,
    val checkNow: () -> Unit,
    val briefNow: () -> Unit,
    val setModelChoice: (ModelChoice) -> Unit,
    val replayOnboarding: () -> Unit,
    val openDevTools: () -> Unit,
)

@Composable
fun SettingsRoot(s: SettingsState, a: SettingsActions) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        Column(
            Modifier
                .fillMaxSize()
                .background(c.backgroundNeutralPrimary)
                .systemBarsPadding()
                .imePadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = a.back) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary)
                }
                Text("설정", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                AccountSection(s, a)
                Spacer(Modifier.height(32.dp))
                EtlTokenSection(s, a)
                Spacer(Modifier.height(32.dp))
                NotifySection(s, a)
                Spacer(Modifier.height(32.dp))
                ModelSection(s, a)
                Spacer(Modifier.height(32.dp))
                ApiKeySection(s, a)
                Spacer(Modifier.height(32.dp))
                MiscSection(a)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ---- 공통 조각 ----------------------------------------------------------------

/** 소제목 + 카드(들). 카드가 여럿이면 12dp 간격으로 쌓는다. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = DesignSystemTheme.typography.headline1Bold, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DesignSystemTheme.colors.surfaceNeutralSecondary) // surfaceNeutralPrimary는 배경과 같은 흰색이라 카드로 보이지 않는다
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun StatusBanner(text: String, tone: StatusTone, loading: Boolean = false) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val bg = when (tone) {
        StatusTone.POSITIVE -> c.surfacePositivePrimary
        StatusTone.NEGATIVE -> c.surfaceNegativePrimary
        StatusTone.CAUTION -> c.surfaceCautionaryPrimary
        StatusTone.NEUTRAL -> c.backgroundNeutralPrimary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            WantedCircularProgressIndicator()
        } else {
            val (icon, tint) = when (tone) {
                StatusTone.POSITIVE -> Icons.Outlined.CheckCircle to c.foregroundPositivePrimary
                StatusTone.NEGATIVE -> Icons.Outlined.ErrorOutline to c.foregroundNegativePrimary
                StatusTone.CAUTION -> Icons.Outlined.WarningAmber to c.foregroundCautionaryPrimary
                StatusTone.NEUTRAL -> Icons.Outlined.Info to c.foregroundNeutralSecondary
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = t.body2Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = DesignSystemTheme.typography.caption1Regular,
        color = DesignSystemTheme.colors.foregroundNeutralSecondary,
        modifier = modifier,
    )
}

/** 왼쪽 제목+보조설명, 오른쪽 스위치. */
@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body1Medium, color = c.foregroundNeutralPrimary)
            if (desc != null) {
                Spacer(Modifier.height(2.dp))
                Text(desc, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        WantedCheckBox(
            onCheckedChange = onChange,
            style = CheckBoxStyle.Switch,
            checkState = if (checked) CheckBoxState.Checked else CheckBoxState.Unchecked,
            enabled = enabled,
        )
    }
}

/** 카드 안 보조 버튼(윤곽선). */
@Composable
private fun SoftButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    WantedButton(
        text = text,
        type = ButtonType.ASSISTIVE,
        size = ButtonSize.MEDIUM,
        variant = ButtonVariant.OUTLINED,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun MainButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    WantedButton(
        text = text,
        type = ButtonType.PRIMARY,
        size = ButtonSize.MEDIUM,
        variant = ButtonVariant.SOLID,
        enabled = enabled,
        onClick = onClick,
    )
}

// ---- 1. 계정 -----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSection(s: SettingsState, a: SettingsActions) {
    val status = SettingsLogic.accountStatus(s.sessionState, s.sessionDetail, s.account)
    val ready = s.sessionState == SessionState.READY
    val prompt = s.loginPrompt
    val loggingIn = prompt != null
    val loggedIn = s.account?.loggedIn == true
    Section("ChatGPT 계정") {
        SettingsCard {
            StatusBanner(status.text, status.tone, status.loading)
            if (prompt != null) {
                Spacer(Modifier.height(12.dp))
                LoginPanel(prompt, s.loginExpiresAt, a)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WantedButton(
                    text = if (loggedIn) "다른 계정으로 로그인" else "브라우저로 로그인",
                    type = if (loggedIn) ButtonType.ASSISTIVE else ButtonType.PRIMARY,
                    size = ButtonSize.MEDIUM,
                    variant = if (loggedIn) ButtonVariant.OUTLINED else ButtonVariant.SOLID,
                    enabled = ready && !loggingIn,
                    onClick = a.loginBrowser,
                )
                SoftButton("로그아웃", enabled = ready && s.account?.kind == AccountInfo.Kind.CHATGPT, onClick = a.logout)
            }
            if (loggedIn && !loggingIn) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "다른 ChatGPT 계정으로 바꿀 수 있어요. 브라우저에 이미 로그인된 계정이 있으면 그 계정이 먼저 선택될 수 있으니, 원하는 계정이 아니면 로그인 화면에서 계정을 바꿔 주세요.",
                    style = DesignSystemTheme.typography.caption1Regular,
                    color = DesignSystemTheme.colors.foregroundNeutralSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginPanel(prompt: SessionEvent.LoginPrompt, expiresAt: Long, a: SettingsActions) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val device = prompt.kind == LoginKind.DEVICE_CODE
    var left by remember(expiresAt) { mutableLongStateOf(SettingsLogic.remainingSeconds(expiresAt, System.currentTimeMillis())) }
    LaunchedEffect(expiresAt, device) {
        if (!device) return@LaunchedEffect
        while (true) {
            left = SettingsLogic.remainingSeconds(expiresAt, System.currentTimeMillis())
            delay(1000)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.backgroundNeutralPrimary)
            .padding(16.dp)
    ) {
        if (device) {
            val code = prompt.code.orEmpty()
            SelectionContainer {
                Text(
                    code,
                    style = t.title1Bold.copy(fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 3.sp),
                    color = c.foregroundNeutralPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "코드를 클립보드에 복사해 두었어요. 브라우저의 코드 입력칸에 붙여넣으세요(하이픈 포함 9자).",
                style = t.body2Regular,
                color = c.foregroundNeutralSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text("남은 시간 ${SettingsLogic.formatRemaining(left)}", style = t.body2Medium, color = c.foregroundNeutralPrimary)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WantedCircularProgressIndicator()
                Spacer(Modifier.width(12.dp))
                Text(
                    "브라우저에서 ChatGPT 로그인을 마치세요. 끝나면 알림이 오고, 이 앱으로 돌아오면 돼요.",
                    style = t.body2Medium,
                    color = c.foregroundNeutralPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (device && prompt.code != null) MainButton("코드 복사") { a.copyCode(prompt.code) }
            SoftButton("브라우저 다시 열기") { a.reopenLogin(prompt.url) }
            // 코드 방식은 평소엔 숨기고, 브라우저 로그인이 막혔을 때만 예비로 보여 준다.
            if (!device) SoftButton("브라우저가 안 되나요? 코드로 로그인") { a.cancelLogin(); a.loginDevice() }
            SoftButton("취소", onClick = a.cancelLogin)
        }
    }
}

// ---- 2. eTL 토큰 ----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EtlTokenSection(s: SettingsState, a: SettingsActions) {
    var input by remember { mutableStateOf("") }
    Section("eTL 토큰") {
        SettingsCard {
            if (s.hasEtlToken) StatusBanner("저장됨 (Keystore로 암호화)", StatusTone.POSITIVE)
            else StatusBanner("저장된 토큰이 없어요", StatusTone.CAUTION)
            Spacer(Modifier.height(12.dp))
            SoftButton("eTL 토큰 만들러 가기", onClick = a.openEtlSettings)
            Spacer(Modifier.height(16.dp))
            WantedTextField(
                text = input,
                placeholder = "토큰을 붙여넣어 주세요",
                title = if (s.hasEtlToken) "다른 토큰으로 바꾸기" else "eTL 토큰",
                description = "이 폰의 Keystore에 암호화해서 저장해요.",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                background = DesignSystemTheme.colors.backgroundNeutralPrimary,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MainButton("저장", enabled = input.isNotBlank()) {
                    if (a.saveEtlToken(input)) input = ""
                }
                SoftButton("삭제", enabled = s.hasEtlToken, onClick = a.clearEtlToken)
            }
        }
    }
}

// ---- 3. 알림 -----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotifySection(s: SettingsState, a: SettingsActions) {
    val n = s.notify
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val times = NotifyUiFormat.normalizeBriefTimes(n.briefMinutesOfDay)
    val intervalOn = n.needsPeriodicCheck
    Section("알림") {
        SettingsCard {
            Caption("마감·공지 확인은 몇 시간마다 백그라운드에서 해요. AI는 쓰지 않고, eTL 토큰이 필요해요.")
            if (!s.hasEtlToken) {
                Spacer(Modifier.height(12.dp))
                StatusBanner("eTL 토큰을 먼저 저장해야 알림이 동작해요.", StatusTone.CAUTION)
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("마감 임박 알림", NotifyUiFormat.windowsSummary(n.deadlineWindowsHours), n.deadlineAlerts) { v -> a.updateNotify { it.copy(deadlineAlerts = v) } }
            if (n.deadlineAlerts) {
                val windows = NotifySettings.normalizeWindows(n.deadlineWindowsHours)
                FlowRow(
                    Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (h in NotifySettings.DEADLINE_WINDOW_CHOICES) {
                        WantedChip(
                            text = NotifyUiFormat.windowLabel(h) + " 전",
                            isActive = h in windows,
                            onClick = { a.updateNotify { it.copy(deadlineWindowsHours = SettingsLogic.toggleWindow(it.deadlineWindowsHours, h)) } },
                        )
                    }
                }
            }
            SwitchRow("새 공지 알림", "수강 중인 강의에 새 공지가 올라오면 알려요", n.announcementAlerts) { v -> a.updateNotify { it.copy(announcementAlerts = v) } }
            SwitchRow("정기 브리핑", "마감·누락·새 공지를 정해진 시각에 요약해서 보내요", n.briefEnabled) { v -> a.updateNotify { it.copy(briefEnabled = v) } }
            if (n.briefEnabled) {
                Spacer(Modifier.height(4.dp))
                Text("브리핑 시각", style = DesignSystemTheme.typography.body2Medium, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
                Spacer(Modifier.height(8.dp))
                times.forEachIndexed { index, minutes ->
                    Row(
                        Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WantedButton(
                            text = NotifyUiFormat.formatTime(minutes, is24h),
                            type = ButtonType.ASSISTIVE,
                            size = ButtonSize.SMALL,
                            variant = ButtonVariant.OUTLINED,
                            onClick = {
                                a.pickTime(minutes) { picked ->
                                    a.updateNotify { it.copy(briefMinutesOfDay = SettingsLogic.replaceBriefTime(it.briefMinutesOfDay, index, picked)) }
                                }
                            },
                        )
                        if (index == 1) {
                            Spacer(Modifier.width(4.dp))
                            WantedTextButton(
                                text = "삭제",
                                onClick = { a.updateNotify { it.copy(briefMinutesOfDay = SettingsLogic.removeSecondBriefTime(it.briefMinutesOfDay)) } },
                            )
                        }
                    }
                }
                if (times.size < 2) {
                    WantedTextButton(
                        text = "+ 시각 추가",
                        onClick = { a.updateNotify { it.copy(briefMinutesOfDay = SettingsLogic.addBriefTime(it.briefMinutesOfDay)) } },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("확인 주기", style = DesignSystemTheme.typography.body1Medium, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
            Spacer(Modifier.height(2.dp))
            Caption(if (intervalOn) "마감·공지를 몇 시간마다 확인할지 정해요" else "마감 임박·새 공지 알림을 켜면 쓸 수 있어요")
            Spacer(Modifier.height(10.dp))
            // 옆으로 밀어야만 보이는 선택지는 놓치기 쉽다. 한 줄에 다 안 들어가면 다음 줄로 넘긴다.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in SettingsLogic.intervalChoices) {
                    WantedChip(
                        text = "${h}시간",
                        isActive = n.checkIntervalHours == h,
                        isEnable = intervalOn,
                        onClick = { if (intervalOn) a.updateNotify { it.copy(checkIntervalHours = h) } },
                    )
                }
            }
        }

        SettingsCard {
            SwitchRow("방해 금지", "방해 금지 시간에는 마감·공지 알림을 미뤄요", n.quietEnabled) { v -> a.updateNotify { it.copy(quietEnabled = v) } }
            if (n.quietEnabled) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("시작 " + NotifyUiFormat.formatTime(n.quietStartMinutes, is24h)) {
                        a.pickTime(n.quietStartMinutes) { m -> a.updateNotify { it.copy(quietStartMinutes = m) } }
                    }
                    SoftButton("종료 " + NotifyUiFormat.formatTime(n.quietEndMinutes, is24h)) {
                        a.pickTime(n.quietEndMinutes) { m -> a.updateNotify { it.copy(quietEndMinutes = m) } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Caption("정기 브리핑은 방해 금지와 무관하게 정해진 시각에 와요.")
                Spacer(Modifier.height(8.dp))
            }
            SwitchRow("잠금화면에서 알림 내용 숨기기", null, n.hideContentOnLockScreen) { v -> a.updateNotify { it.copy(hideContentOnLockScreen = v) } }
        }

        SettingsCard {
            if (s.notifyPermitted) {
                StatusBanner("알림 권한: 허용됨", StatusTone.POSITIVE)
            } else {
                StatusBanner("알림 권한: 꺼져 있어요", StatusTone.CAUTION)
                Spacer(Modifier.height(10.dp))
                SoftButton("권한 요청", onClick = a.requestNotifyPermission)
            }
            Spacer(Modifier.height(16.dp))
            Caption(s.lastCheckText)
            Spacer(Modifier.height(2.dp))
            Caption(s.lastBriefText)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("지금 확인", onClick = a.checkNow)
                SoftButton("브리핑 미리 보내기", onClick = a.briefNow)
            }
        }
    }
}

// ---- 4. AI 모델 ---------------------------------------------------------------

@Composable
private fun ModelSection(s: SettingsState, a: SettingsActions) {
    // 선택 조각은 자체 스크롤이 없고 흰 배경 위에 그리도록 만들어졌으므로 카드로 감싸지 않는다.
    Section("AI 모델") {
        ModelPickerContent(
            models = s.models,
            choice = s.modelChoice,
            loading = s.modelsLoading,
            onChoice = a.setModelChoice,
        )
    }
}

// ---- 5. OpenAI API 키(고급) -------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiKeySection(s: SettingsState, a: SettingsActions) {
    var input by remember { mutableStateOf("") }
    Section("OpenAI API 키 (고급)") {
        SettingsCard {
            Caption(
                "ChatGPT 계정 대신 본인의 API 키를 쓸 수 있어요. 사용한 만큼 본인의 OpenAI 계정에 요금이 청구돼요. " +
                    "키는 이 폰의 Keystore에 암호화해 저장하고, 실행할 때만 환경변수로 넘겨요."
            )
            Spacer(Modifier.height(12.dp))
            val masked = s.apiKeyMasked
            when {
                !s.hasApiKey -> StatusBanner("저장된 API 키가 없어요", StatusTone.NEUTRAL)
                masked == null -> StatusBanner("저장된 키를 읽지 못했어요(삭제 후 다시 저장하세요)", StatusTone.CAUTION)
                else -> StatusBanner("저장됨 · $masked", StatusTone.POSITIVE)
            }
            Spacer(Modifier.height(16.dp))
            WantedTextField(
                text = input,
                placeholder = "sk-…",
                title = "API 키",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                background = DesignSystemTheme.colors.backgroundNeutralPrimary,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MainButton("저장", enabled = input.isNotBlank()) {
                    if (a.saveApiKey(input)) input = ""
                }
                SoftButton("삭제", enabled = s.hasApiKey, onClick = a.clearApiKey)
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("API 키로 사용", "끄면 ChatGPT 계정을 써요", s.useApiKey, onChange = a.setUseApiKey)
        }
    }
}

// ---- 6. 기타 -----------------------------------------------------------------

@Composable
private fun MiscSection(a: SettingsActions) {
    Section("기타") {
        SettingsCard {
            LinkRow("처음 안내 다시 보기", onClick = a.replayOnboarding)
            LinkRow("개발자 도구", onClick = a.openDevTools)
        }
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    val c = DesignSystemTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = DesignSystemTheme.typography.body1Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.foregroundNeutralSecondary)
    }
}
