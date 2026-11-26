package kr.snu.etlassistant.chat

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
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
import kr.snu.etlassistant.notify.BatteryOpt
import kr.snu.etlassistant.notify.NotifySettings
import kr.snu.etlassistant.etl.WriteScope
import kr.snu.etlassistant.etl.WriteMode
import kr.snu.etlassistant.etl.WritePolicy
import kr.snu.etlassistant.etl.WritePolicySettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** 화면이 그리는 값. 액티비티가 세션 이벤트와 저장소 상태를 여기에 반영한다. */
class SettingsState {
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
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
    var casualTone by mutableStateOf(false)
    /** 공개 기출 자료 찾기(MIT OCW 등, 기본 켬). */
    var studyLookup by mutableStateOf(true)
    var hasEtlToken by mutableStateOf(false)

    var notify by mutableStateOf(NotifySettings())
    var notifyPermitted by mutableStateOf(true)
    var exactAlarmsAllowed by mutableStateOf(true)
    /** 배터리 최적화 예외인가(아니면 앱을 닫았을 때 알림 확인이 늦거나 끊길 수 있다). */
    var batteryUnrestricted by mutableStateOf(true)
    var powerSave by mutableStateOf(false)
    var lastCheckText by mutableStateOf("")
    var lastBriefText by mutableStateOf("")
    /** 과목별 알림 요약(예: "알림 끈 과목 1개 · 숨긴 과목 2개"). */
    var courseAlertsSummary by mutableStateOf("")
    /** 캘린더로 내보내기 요약(예: "꺼짐", "이 폰의 \"영민이\" 캘린더로 내보내는 중"). */
    var calendarSummary by mutableStateOf("")

    /** 업데이트 알림(GitHub Release 확인) 켬/끔. */
    var updateAlerts by mutableStateOf(true)
    /** 지금보다 새 버전(예: "0.1.8")과 그 APK 직접 다운로드 링크. 없으면 null. */
    var updateVersion by mutableStateOf<String?>(null)
    var updateUrl by mutableStateOf<String?>(null)
    var updateChecking by mutableStateOf(false)
    /** "업데이트 확인"을 누른 뒤 보일 짧은 결과(예: "최신 버전이에요"). */
    var updateResult by mutableStateOf<String?>(null)
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
    val requestBatteryExemption: () -> Unit,
    val requestExactAlarms: () -> Unit = {},
    val checkNow: () -> Unit,
    val briefNow: () -> Unit,
    val setModelChoice: (ModelChoice) -> Unit,
    val replayOnboarding: () -> Unit,
    val openHistory: () -> Unit = {},
    val openPrivacyPolicy: () -> Unit = {},
    val openCourseAlerts: () -> Unit = {},
    val openCalendarExport: () -> Unit = {},
    val openTimetable: () -> Unit = {},
    val openMyDeadlines: () -> Unit = {},
    val setCasualTone: (Boolean) -> Unit = {},
    val setStudyLookup: (Boolean) -> Unit = {},
    val setUpdateAlerts: (Boolean) -> Unit = {},
    val checkUpdate: () -> Unit = {},
    val openUpdate: () -> Unit = {},
    val setThemeMode: (ThemeMode) -> Unit = {},
    val addDeadlineWidget: () -> Unit = {},
    val addTimetableWidget: () -> Unit = {},
    val addNextClassWidget: () -> Unit = {},
    val addTallTimetableWidget: () -> Unit = {},
    val addCompactDeadlineWidget: () -> Unit = {},
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
                CalendarSection(s, a)
                Spacer(Modifier.height(32.dp))
                WritePermissionsSection()
                Spacer(Modifier.height(32.dp))
                Section("받은 파일") { SettingsCard { kr.snu.etlassistant.files.DownloadCleanupSettingsContent() } }
                Spacer(Modifier.height(32.dp))
                ToneSection(s, a)
                Spacer(Modifier.height(32.dp))
                ModelSection(s, a)
                Spacer(Modifier.height(32.dp))
                ApiKeySection(s, a)
                Spacer(Modifier.height(32.dp))
                MiscSection(s, a)
                Spacer(Modifier.height(32.dp))
                AboutSection(s, a)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ---- 공통 조각 ----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WritePermissionsSection() {
    val context = LocalContext.current
    val store = remember(context) { WritePolicySettings(context) }
    var modes by remember(store) { mutableStateOf(WriteScope.entries.associateWith { store.mode(it) }) }
    var consenting by remember { mutableStateOf<WriteScope?>(null) }
    fun save(scope: WriteScope, mode: WriteMode, consented: Boolean = false) {
        store.save(scope, mode, consented)
        modes = modes + (scope to mode)
    }
    Section("비서 쓰기 권한") {
        SettingsCard {
            Caption("비서가 요청받은 변경을 처리하는 방식을 정해요. 수정·삭제는 항상 확인해요. 캘린더는 캘린더 연동 설정도 켜져 있어야 해요.")
            for (scope in WriteScope.entries) {
                Spacer(Modifier.height(16.dp))
                Text(scope.label, style = DesignSystemTheme.typography.body1Medium, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mode in WriteMode.entries) {
                        WantedChip(text = mode.label, isActive = modes[scope] == mode, onClick = {
                            if (mode == WriteMode.AUTO_ADD && modes[scope] != mode) consenting = scope
                            else if (mode != WriteMode.AUTO_ADD) save(scope, mode)
                        })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Caption("끔은 바로 적용돼요. 꺼 둔 도구를 다시 켜면 채팅 화면을 닫았다 열어 주세요.")
        }
    }
    consenting?.let { scope ->
        AlertDialog(
            onDismissRequest = { consenting = null },
            title = { Text("${scope.label}: 추가를 자동으로 할까요?") },
            text = { Text("직접 요청한 추가는 답변 한 번에 최대 ${WritePolicy.MAX_AUTO_PER_TURN}개까지 확인 없이 처리해요. 그 이후 추가와 수정·삭제는 매번 확인해요. 처리한 내용은 채팅에 남아요. 캘린더 연동이나 계정 동기화가 켜져 있으면 일정이 해당 캘린더 계정에도 반영될 수 있어요.") },
            confirmButton = { WantedTextButton(text = "동의하고 켜기", onClick = { save(scope, WriteMode.AUTO_ADD, consented = true); consenting = null }) },
            dismissButton = { WantedTextButton(text = "취소", onClick = { consenting = null }) },
        )
    }
}

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

/** 눌러서 바꿀 수 있는 값(시각 등). 테두리와 연필 아이콘으로 바꿀 수 있다는 것을 보여 준다. */
@Composable
private fun EditableValue(text: String, clickLabel: String, onClick: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.lineNeutralPrimary, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = t.body2Medium, color = c.foregroundNeutralPrimary)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(16.dp))
    }
}

/** 켜진 알림 시점 하나. ×를 누르면 지운다. */
@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceBrandSubtle)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = t.body2Medium, color = c.foregroundBrandPrimary)
        Box(
            Modifier
                .size(36.dp)
                .clickable(onClickLabel = "$label 지우기", role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "$label 지우기" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/** 알림 시점 추가: 자주 쓰는 시점 빠른 버튼 + 숫자·단위(분/시간/일) 직접 입력. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomWindowDialog(current: List<Int>, onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(SettingsLogic.WindowUnit.HOUR) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.backgroundNeutralPrimary,
        title = { Text("알림 시점 추가", style = t.title3Bold, color = c.foregroundNeutralPrimary) },
        text = {
            Column {
                Text("자주 쓰는 시점", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in SettingsLogic.quickWindows) {
                        val has = m in current
                        WantedChip(
                            text = NotifyUiFormat.windowLabel(m) + if (has) " 전 ✓" else " 전",
                            isEnable = !has,
                            onClick = {
                                if (has) return@WantedChip
                                when (val r = SettingsLogic.checkWindow(m.toLong(), current)) {
                                    is SettingsLogic.CustomWindow.Ok -> onAdd(r.minutes)
                                    is SettingsLogic.CustomWindow.Error -> error = r.message
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("직접 입력", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                Spacer(Modifier.height(8.dp))
                WantedTextField(
                    text = amount,
                    placeholder = "예: 30",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    background = c.backgroundNeutralPrimary,
                    onValueChange = { v -> amount = v.filter { it.isDigit() }.take(4); error = null },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (u in SettingsLogic.WindowUnit.values()) {
                        WantedChip(text = u.label + " 전", isActive = unit == u, onClick = { unit = u; error = null })
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = t.caption1Regular, color = c.foregroundNegativePrimary)
                }
            }
        },
        confirmButton = {
            WantedTextButton(text = "추가", onClick = {
                when (val r = SettingsLogic.parseCustomWindow(amount, unit, current)) {
                    is SettingsLogic.CustomWindow.Ok -> onAdd(r.minutes)
                    is SettingsLogic.CustomWindow.Error -> error = r.message
                }
            })
        },
        dismissButton = { WantedTextButton(text = "취소", onClick = onDismiss) },
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
    var confirmDisconnect by remember { mutableStateOf(false) }
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
                SoftButton("eTL 연결 해제", enabled = s.hasEtlToken) { confirmDisconnect = true }
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("eTL 연결을 해제할까요?") },
            text = {
                Text("저장한 eTL 토큰과, 앱이 eTL에서 받아 둔 과제 목록·시간표·강의계획서 캐시를 이 폰에서 지워요. " +
                    "대화, 받은 파일, 알림 기록, 내 마감은 그대로 남아요. eTL 웹사이트의 토큰은 eTL 설정에서 따로 지워 주세요.")
            },
            confirmButton = { WantedTextButton(text = "해제", onClick = { confirmDisconnect = false; a.clearEtlToken() }) },
            dismissButton = { WantedTextButton(text = "취소", onClick = { confirmDisconnect = false }) },
        )
    }
}

// ---- 3. 알림 -----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotifySection(s: SettingsState, a: SettingsActions) {
    val n = s.notify
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val times = NotifyUiFormat.normalizeBriefTimes(n.briefMinutesOfDay)
    Section("알림") {
        SettingsCard {
            if (!s.hasEtlToken) {
                StatusBanner("eTL 토큰을 먼저 저장해야 알림이 동작해요.", StatusTone.CAUTION)
                Spacer(Modifier.height(8.dp))
            }
            SwitchRow("마감 임박 알림", NotifyUiFormat.windowsSummary(n.deadlineWindowsMinutes), n.deadlineAlerts) { v -> a.updateNotify { it.copy(deadlineAlerts = v) } }
            if (n.deadlineAlerts) {
                val windows = NotifySettings.normalizeWindows(n.deadlineWindowsMinutes)
                var adding by remember { mutableStateOf(false) }
                var windowError by remember { mutableStateOf<String?>(null) }
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    Modifier.padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (m in SettingsLogic.windowChips(windows)) {
                        RemovableChip(NotifyUiFormat.windowLabel(m) + " 전") {
                            val next = SettingsLogic.removeWindow(windows, m)
                            if (next == null) windowError = SettingsLogic.LAST_WINDOW_MESSAGE
                            else {
                                windowError = null
                                a.updateNotify { it.copy(deadlineWindowsMinutes = next) }
                            }
                        }
                    }
                    if (SettingsLogic.canAddWindow(windows)) {
                        WantedChip(text = "+ 추가", onClick = { windowError = null; adding = true })
                    }
                }
                windowError?.let {
                    Text(it, style = DesignSystemTheme.typography.caption1Regular, color = DesignSystemTheme.colors.foregroundNegativePrimary)
                }
                if (!SettingsLogic.isDefaultWindows(windows)) {
                    WantedTextButton(
                        text = "기본값으로 되돌리기",
                        onClick = {
                            windowError = null
                            a.updateNotify { it.copy(deadlineWindowsMinutes = SettingsLogic.defaultWindows()) }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (adding) {
                    CustomWindowDialog(
                        current = windows,
                        onDismiss = { adding = false },
                        onAdd = { m ->
                            adding = false
                            a.updateNotify { it.copy(deadlineWindowsMinutes = SettingsLogic.addWindow(it.deadlineWindowsMinutes, m)) }
                        },
                    )
                }
            }
            SwitchRow("새 공지 알림", "수강 중인 강의에 새 공지가 올라오면 알려줘요(보강·휴강 공지는 따로 표시)", n.announcementAlerts) { v -> a.updateNotify { it.copy(announcementAlerts = v) } }
            SwitchRow("새 과제·마감 변경·성적 알림", "과제가 새로 올라오거나, 마감이 바뀌거나, 점수가 등록되면 알려줘요", n.changeAlerts) { v -> a.updateNotify { it.copy(changeAlerts = v) } }
            SwitchRow("제출 직후 확인", "과제를 내면 eTL 제출 기록을 살펴보고 빈 파일·허용되지 않은 형식·첨부 없음 같은 문제가 있으면 바로 알려줘요", n.submitAlerts) { v -> a.updateNotify { it.copy(submitAlerts = v) } }
            if (n.submitAlerts) {
                SwitchRow("정상 제출도 알리기", "문제가 없을 때도 \"제출 확인됐어요\"를 소리·팝업 없이 알림창에 남겨요", n.submitOkAlerts) { v -> a.updateNotify { it.copy(submitOkAlerts = v) } }
            }
            SwitchRow("새 강의자료 알림", "4시간마다 과목별 파일 목록을 보고 새 자료가 올라오면 알려줘요. 누르면 비서에게 받아 달라고 할 수 있어요", n.newFileAlerts) { v -> a.updateNotify { it.copy(newFileAlerts = v) } }
            SwitchRow("앱을 열 때 등록할 공지 보여 주기", "아직 넣지 않은 시험·과제·동영상 강의·보강·휴강 공지가 새로 오면 채팅 화면에서 한 번 보여 줘요", n.pendingSheet) { v -> a.updateNotify { it.copy(pendingSheet = v) } }
            SwitchRow("시험 끝나면 수고했어요 알림", "내 일정에 넣은 시험에 끝 시각이 있으면 끝난 뒤 한 번 알려줘요(방해 금지 시간에는 보내지 않아요)", n.examDoneAlerts) { v -> a.updateNotify { it.copy(examDoneAlerts = v) } }
            SwitchRow("수업 알림", "시간표(수강편람 + 보강·휴강)의 수업 시작 ${n.classLeadMinutes}분 전에 알려줘요", n.classAlerts) { v -> a.updateNotify { it.copy(classAlerts = v) } }
            if (n.classAlerts) {
                Spacer(Modifier.height(4.dp))
                FlowRow(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(5, 10, 15, 30)) {
                        WantedChip(text = "${m}분 전", isActive = n.classLeadMinutes == m, onClick = { a.updateNotify { it.copy(classLeadMinutes = m) } })
                    }
                }
            }
            LinkRow("과목별 알림", onClick = a.openCourseAlerts, desc = s.courseAlertsSummary.ifBlank { null })
            SwitchRow("정기 브리핑", "마감·누락·새 공지를 정해진 시각에 요약해서 보내줘요", n.briefEnabled) { v -> a.updateNotify { it.copy(briefEnabled = v) } }
            if (n.briefEnabled) {
                Spacer(Modifier.height(4.dp))
                Text("브리핑 시각", style = DesignSystemTheme.typography.body2Medium, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
                Spacer(Modifier.height(8.dp))
                times.forEachIndexed { index, minutes ->
                    Row(
                        Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditableValue(NotifyUiFormat.formatTime(minutes, is24h), "브리핑 시각 바꾸기") {
                            a.pickTime(minutes) { picked ->
                                a.updateNotify { it.copy(briefMinutesOfDay = SettingsLogic.replaceBriefTime(it.briefMinutesOfDay, index, picked)) }
                            }
                        }
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
        }

        SettingsCard {
            SwitchRow("방해 금지", "방해 금지 시간에는 마감·공지 알림을 미뤄요", n.quietEnabled) { v -> a.updateNotify { it.copy(quietEnabled = v) } }
            if (n.quietEnabled) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditableValue("시작 " + NotifyUiFormat.formatTime(n.quietStartMinutes, is24h), "방해 금지 시작 시각 바꾸기") {
                        a.pickTime(n.quietStartMinutes) { m -> a.updateNotify { it.copy(quietStartMinutes = m) } }
                    }
                    EditableValue("종료 " + NotifyUiFormat.formatTime(n.quietEndMinutes, is24h), "방해 금지 종료 시각 바꾸기") {
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
            Spacer(Modifier.height(10.dp))
            StatusBanner(
                if (s.exactAlarmsAllowed) "정확한 알림: 허용됨" else "정확한 알림: 허용 안 됨",
                if (s.exactAlarmsAllowed) StatusTone.POSITIVE else StatusTone.CAUTION,
            )
            Spacer(Modifier.height(6.dp))
            Caption("내 일정·마감·수업 알림 시각에 사용해요. 허용하지 않으면 절전 중 알림이 오래 늦어질 수 있어요. 서로 가까운 알림도 시스템 제한으로 늦어질 수 있어요. 정기 브리핑·새 공지 확인은 정확한 시각을 보장하지 않아요.")
            if (!s.exactAlarmsAllowed) {
                Spacer(Modifier.height(10.dp))
                SoftButton("정확한 알림 허용", onClick = a.requestExactAlarms)
            }
            Spacer(Modifier.height(10.dp))
            if (s.batteryUnrestricted) {
                StatusBanner("배터리: 제한 없음", StatusTone.POSITIVE)
            } else {
                StatusBanner("배터리 최적화가 켜져 있어 앱을 닫아 두면 알림이 늦거나 안 올 수 있어요", StatusTone.CAUTION)
                Spacer(Modifier.height(10.dp))
                SoftButton("배터리 제한 풀기", onClick = a.requestBatteryExemption)
            }
            Spacer(Modifier.height(6.dp))
            Caption(BatteryOpt.SAMSUNG_HINT)
            if (s.powerSave) {
                Spacer(Modifier.height(10.dp))
                StatusBanner(BatteryOpt.POWER_SAVE_HINT, StatusTone.CAUTION)
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

// ---- 캘린더 연동 -------------------------------------------------------------------

@Composable
private fun CalendarSection(s: SettingsState, a: SettingsActions) {
    Section("캘린더") {
        SettingsCard {
            LinkRow("캘린더 연동", onClick = a.openCalendarExport, desc = s.calendarSummary.ifBlank { "꺼짐" })
            Caption("과제·내 마감·시험을 삼성·구글 캘린더에 자동으로 넣고, 내 캘린더 일정을 내 일정과 브리핑에 함께 보여 줘요.")
        }
    }
}

// ---- 대화 말투 -------------------------------------------------------------------

@Composable
private fun ToneSection(s: SettingsState, a: SettingsActions) {
    Section("대화") {
        SettingsCard {
            SwitchRow("반말로 대화", "영민이가 친구처럼 반말로 답해요", s.casualTone, onChange = a.setCasualTone)
            SwitchRow("공개 기출 자료 찾기", "시험 자료를 만들 때 MIT OpenCourseWare의 공개 기출·연습 문제를 찾아 함께 써요", s.studyLookup, onChange = a.setStudyLookup)
            Caption("찾을 주제(검색어)만 MIT로 가요. eTL 정보·파일·개인 정보는 보내지 않아요.")
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
private fun MiscSection(s: SettingsState, a: SettingsActions) {
    var showWidgetPicker by remember { mutableStateOf(false) }
    Section("기타") {
        SettingsCard {
            Text("화면 모드", style = DesignSystemTheme.typography.body2Medium, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
            Spacer(Modifier.height(10.dp))
            ThemeModePicker(s.themeMode, a.setThemeMode)
            Spacer(Modifier.height(20.dp))
            SwitchRow("업데이트 알림", "새 버전이 나오면 알려줘요(GitHub에 최신 버전만 물어봐요)", s.updateAlerts, onChange = a.setUpdateAlerts)
            LinkRow("알림 기록", onClick = a.openHistory)
            LinkRow("시간표 · 보강/휴강", onClick = a.openTimetable)
            LinkRow("내 일정", onClick = a.openMyDeadlines)
            LinkRow("홈 화면에 위젯 추가하기", onClick = { showWidgetPicker = true })
            LinkRow("처음 안내 다시 보기", onClick = a.replayOnboarding)
        }
    }
    if (showWidgetPicker) {
        AlertDialog(
            onDismissRequest = { showWidgetPicker = false },
            title = { Text("홈 화면에 위젯 추가하기") },
            text = {
                Column {
                    LinkRow("일정 · 2×2", onClick = { showWidgetPicker = false; a.addDeadlineWidget() })
                    LinkRow("일정 · 2×1", onClick = { showWidgetPicker = false; a.addCompactDeadlineWidget() })
                    LinkRow("시간표 · 4×4", onClick = { showWidgetPicker = false; a.addTimetableWidget() })
                    LinkRow("시간표 · 4×6", onClick = { showWidgetPicker = false; a.addTallTimetableWidget() })
                    LinkRow("다음 수업 · 2×1", onClick = { showWidgetPicker = false; a.addNextClassWidget() })
                }
            },
            confirmButton = { WantedTextButton(text = "닫기", onClick = { showWidgetPicker = false }) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeModePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            WantedChip(text = mode.label, isActive = selected == mode, onClick = { onSelect(mode) })
        }
    }
}

@Composable
private fun AboutSection(s: SettingsState, a: SettingsActions) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var feedbackOpen by remember { mutableStateOf(false) }
    Section("앱 정보") {
        SettingsCard {
            Text("영민이", style = DesignSystemTheme.typography.body1Bold, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
            Spacer(Modifier.height(4.dp))
            Caption("만든 사람: 박영민 · 버전 $version")
            Spacer(Modifier.height(10.dp))
            Caption("서울대학교의 공식 앱이 아니에요. 개발자는 개인정보를 수집하지 않아요. 질문·첨부와 비서가 불러온 정보는 OpenAI로 전송돼요.")
            Spacer(Modifier.height(4.dp))
            LinkRow("개인정보 처리방침", onClick = a.openPrivacyPolicy)
            HyperLink("건의사항 · 오류 제보", onClickLabel = "제보 안내 열기") { feedbackOpen = true }
            val newVersion = s.updateVersion
            val canDownload = newVersion != null && s.updateUrl != null
            HyperLink(
                text = when {
                    s.updateChecking -> "확인하는 중…"
                    canDownload -> "새 버전 $newVersion 받기"
                    else -> "업데이트 확인"
                },
                onClickLabel = if (canDownload) "업데이트 APK 받기" else "새 버전 확인",
                enabled = !s.updateChecking,
                onClick = if (canDownload) a.openUpdate else a.checkUpdate,
            )
            s.updateResult?.let { Caption(it) }
        }
    }

    if (feedbackOpen) {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        AlertDialog(
            onDismissRequest = { feedbackOpen = false },
            containerColor = c.backgroundNeutralPrimary,
            title = { Text("건의사항 · 오류 제보", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
            text = {
                Text(
                    "앱에 오류가 있나요? 오픈채팅에 접속해서 혼내주세요!\n영민이한테 원하는 기능도 여기서 알려주세요!",
                    style = t.body2Regular,
                    color = c.foregroundNeutralSecondary,
                )
            },
            confirmButton = {
                WantedTextButton(text = "오픈채팅 열기", onClick = {
                    feedbackOpen = false
                    val opened = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL)))
                    }.isSuccess
                    if (!opened) Toast.makeText(context, "링크를 열 앱이 없어요: $FEEDBACK_URL", Toast.LENGTH_LONG).show()
                })
            },
            dismissButton = { WantedTextButton(text = "닫기", onClick = { feedbackOpen = false }) },
        )
    }
}

/** 화살표 줄 대신 하이퍼링크처럼: 브랜드 색 + 밑줄. 터치 영역은 48dp 높이로 둔다. */
@Composable
private fun HyperLink(text: String, onClickLabel: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = DesignSystemTheme.typography.body2Medium,
            color = if (enabled) DesignSystemTheme.colors.foregroundBrandPrimary else DesignSystemTheme.colors.foregroundNeutralTertiary,
            textDecoration = if (enabled) TextDecoration.Underline else null,
        )
    }
}

/** 건의사항·오류 제보용 카카오톡 오픈채팅. */
private const val FEEDBACK_URL = "https://open.kakao.com/o/sR3ebBYi"

@Composable
private fun LinkRow(title: String, onClick: () -> Unit, desc: String? = null) {
    val c = DesignSystemTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DesignSystemTheme.typography.body1Medium, color = c.foregroundNeutralPrimary)
            if (desc != null) Text(desc, style = DesignSystemTheme.typography.caption1Regular, color = c.foregroundNeutralSecondary)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.foregroundNeutralSecondary)
    }
}
