package kr.snu.etlassistant.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.input.input.control.CheckBoxState
import com.wanted.android.wanted.design.input.input.control.CheckBoxStyle
import com.wanted.android.wanted.design.input.input.control.WantedCheckBox
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextField
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextFieldDefaults
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.navigations.progressindicator.WantedLinearProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kr.snu.etlassistant.chat.NotifyUiFormat
import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.LoginKind
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.phase0.R

/** 화면이 바깥(액티비티, 세션, 시스템)에 부탁하는 일들. */
class OnboardingActions(
    val openEtlSettings: () -> Unit,
    val readClipboard: () -> String?,
    val requestNotifications: () -> Unit,
    val ensureSession: () -> Unit,
    val retrySession: () -> Unit,
    val loginBrowser: () -> Unit,
    val loginDevice: () -> Unit,
    val cancelLogin: () -> Unit,
    val reopenLogin: (String) -> Unit,
    val copyCode: (String) -> Unit,
    val applyNotifications: () -> Unit,
    /** 시각 선택 창을 띄운다(하루 중 분). */
    val pickTime: (initial: Int, onPicked: (Int) -> Unit) -> Unit,
    val finish: (prefill: String?) -> Unit,
)

@Composable
fun OnboardingRoot(vm: OnboardingViewModel, a: OnboardingActions) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        BackHandler(enabled = vm.step != Step.WELCOME) { vm.goBack() }
        LaunchedEffect(vm.step) { if (vm.step == Step.LOGIN) a.ensureSession() }

        Column(
            Modifier
                .fillMaxSize()
                .background(c.backgroundNeutralPrimary)
                .systemBarsPadding()
                .imePadding()
        ) {
            TopProgress(vm)
            AnimatedContent(
                targetState = vm.step,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.weight(1f),
                label = "step",
            ) { step ->
                when (step) {
                    Step.WELCOME -> WelcomeStep(vm)
                    Step.TOKEN -> TokenStep(vm, a)
                    Step.LOGIN -> LoginStep(vm, a)
                    Step.NOTIFY -> NotifyStep(vm, a)
                    Step.DONE -> DoneStep(a)
                }
            }
        }
    }
}

@Composable
private fun TopProgress(vm: OnboardingViewModel) {
    val c = DesignSystemTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.step != Step.WELCOME) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "이전", tint = c.foregroundNeutralPrimary)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            WantedLinearProgressIndicator(currentProgress = OnboardingLogic.progress(vm.step), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(48.dp))
    }
}

// ---- 공통 틀 ----------------------------------------------------------------

@Composable
private fun StepFrame(
    title: String,
    subtitle: String? = null,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            header?.invoke(this)
            Text(title, style = t.title1Bold, color = c.foregroundNeutralPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(10.dp))
                Text(subtitle, style = t.body1ReadingRegular, color = c.foregroundNeutralSecondary)
            }
            Spacer(Modifier.height(28.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = bottom,
        )
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
    WantedButton(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        type = ButtonType.PRIMARY,
        size = ButtonSize.LARGE,
        variant = ButtonVariant.SOLID,
        enabled = enabled,
        isLoading = loading,
        onClick = onClick,
    )
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    WantedButton(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        type = ButtonType.ASSISTIVE,
        size = ButtonSize.LARGE,
        variant = ButtonVariant.OUTLINED,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = DesignSystemTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceNeutralSecondary) // surfaceNeutralPrimary는 배경과 같은 흰색이라 카드로 보이지 않는다
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun StatusCard(text: String, positive: Boolean = true) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (positive) c.surfacePositivePrimary else c.surfaceNegativePrimary)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (positive) c.foregroundPositivePrimary else c.foregroundNegativePrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = t.body1Medium, color = c.foregroundNeutralPrimary)
    }
}

// ---- 1. 환영 ----------------------------------------------------------------

@Composable
private fun WelcomeStep(vm: OnboardingViewModel) {
    StepFrame(
        title = "안녕하세요,\n영민이예요",
        subtitle = "마감과 공지는 챙기고, 궁금한 건 물어보고, 강의 파일은 바로 받아 보세요.",
        header = {
            Image(
                painter = painterResource(R.drawable.etl_logo),
                contentDescription = "영민이 로고",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(120.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(28.dp))
        },
        bottom = { PrimaryButton("시작하기") { vm.goNext() } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FeatureRow(Icons.Outlined.NotificationsNone, "마감·공지 알림", "마감이 다가오면 미리 알려드려요")
            FeatureRow(Icons.Outlined.ChatBubbleOutline, "채팅으로 물어보기", "이번 주 할 일, 제출 확인, 파일 내용까지")
            FeatureRow(Icons.Outlined.FileDownload, "강의 파일 받기", "필요한 자료를 폰에 바로 저장해요")
        }
        Spacer(Modifier.height(28.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = DesignSystemTheme.colors.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("개인정보는 이 폰 안에서만", style = DesignSystemTheme.typography.body2Bold, color = DesignSystemTheme.colors.foregroundNeutralPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "개발자 서버가 없어요. eTL 토큰, 파일, 대화 기록은 이 폰에만 저장돼요. 대화 내용은 AI가 답하려고 내 ChatGPT 계정을 통해 OpenAI로 보내져요.",
                style = DesignSystemTheme.typography.caption1Regular,
                color = DesignSystemTheme.colors.foregroundNeutralSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "서울대학교 공식 앱이 아니에요. 학생이 직접 만든 앱이에요.",
            style = DesignSystemTheme.typography.caption1Regular,
            color = DesignSystemTheme.colors.foregroundNeutralTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surfaceBrandSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = t.headline1Bold, color = c.foregroundNeutralPrimary)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = t.body2Regular, color = c.foregroundNeutralSecondary)
        }
    }
}

// ---- 2. eTL 토큰 ---------------------------------------------------------------

@Composable
private fun TokenStep(vm: OnboardingViewModel, a: OnboardingActions) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val state = vm.tokenState
    val saved = state is TokenState.Saved
    StepFrame(
        title = "eTL에 연결할게요",
        subtitle = "eTL에서 내 토큰을 만들어 붙여넣으면, 앱이 eTL을 읽기 전용으로 조회해요. 제출이나 수정은 하지 않아요.",
        bottom = {
            if (saved) {
                PrimaryButton("다음") { vm.goNext() }
                WantedTextButton(text = "다른 토큰으로 바꾸기", modifier = Modifier.fillMaxWidth(), onClick = { vm.replaceToken() })
            } else {
                PrimaryButton(
                    "확인하고 저장",
                    enabled = OnboardingLogic.isPlausibleEtlToken(vm.tokenInput) && state !is TokenState.Checking,
                    loading = state is TokenState.Checking,
                ) { vm.checkAndSaveToken() }
                if (vm.hasStoredToken) {
                    WantedTextButton(text = "기존 토큰 그대로 쓰기", modifier = Modifier.fillMaxWidth(), onClick = { vm.keepExistingToken() })
                }
            }
        },
    ) {
        if (!saved) {
            Card {
                Step("1", "아래 버튼으로 eTL 설정 페이지를 열어요")
                Spacer(Modifier.height(10.dp))
                Step("2", "'새 액세스 토큰'을 만들어요 (용도는 아무거나)")
                Spacer(Modifier.height(10.dp))
                Step("3", "만들어진 토큰을 복사해서 아래에 붙여넣어요")
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton("eTL 설정 페이지 열기") { a.openEtlSettings() }
            Spacer(Modifier.height(24.dp))
            WantedTextField(
                text = vm.tokenInput,
                placeholder = "토큰을 붙여넣어 주세요",
                title = "eTL 토큰",
                description = (state as? TokenState.Error)?.message ?: "이 폰의 Keystore에 암호화해서 저장해요. 설정에서 언제든 삭제할 수 있어요.",
                status = if (state is TokenState.Error) WantedTextFieldDefaults.Status.Negative else WantedTextFieldDefaults.Status.Normal,
                trailingButton = "붙여넣기",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                onClickTrailingButton = { a.readClipboard()?.trim()?.takeIf { it.isNotEmpty() }?.let { vm.tokenInput = it } },
                onValueChange = { vm.tokenInput = it },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val name = (state as TokenState.Saved).name
            StatusCard(if (name != null) "${name}님으로 연결됐어요" else "이미 연결돼 있어요")
            Spacer(Modifier.height(12.dp))
            Text(
                "토큰은 이 폰에 암호화돼 저장됐어요. 이제 마감, 공지, 파일을 조회할 수 있어요.",
                style = t.body2Regular,
                color = c.foregroundNeutralSecondary,
            )
        }
    }
}

@Composable
private fun Step(no: String, text: String) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(c.surfaceBrandPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(no, style = t.caption1Bold, color = c.staticWhite) // 파란 원 위라 테마와 무관하게 흰색
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = t.body2Medium, color = c.foregroundNeutralPrimary)
    }
}

// ---- 3. ChatGPT 로그인 -----------------------------------------------------------

@Composable
private fun LoginStep(vm: OnboardingViewModel, a: OnboardingActions) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val account = vm.account
    val loggedIn = account?.loggedIn == true
    val prompt = vm.loginPrompt
    val ready = vm.sessionState == SessionState.READY

    StepFrame(
        title = "ChatGPT 계정으로\n로그인해요",
        subtitle = "AI 비서는 내 ChatGPT 계정으로 실행돼요. 별도의 API 요금은 없고, 내 플랜의 사용량 안에서 쓰여요.",
        bottom = {
            when {
                // 진행 중인 로그인이 있으면(계정 전환 포함) 로그인 여부와 상관없이 먼저 보여 준다.
                prompt != null -> {
                    SecondaryButton("브라우저 다시 열기") { a.reopenLogin(prompt.url) }
                    // 코드 방식은 평소엔 숨기고, 브라우저 로그인이 막혔을 때만 예비로 보여 준다.
                    if (prompt.kind == LoginKind.BROWSER) {
                        WantedTextButton(
                            text = "브라우저가 안 되나요? 코드로 로그인",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { a.cancelLogin(); a.loginDevice() },
                        )
                    }
                    WantedTextButton(text = "취소", modifier = Modifier.fillMaxWidth(), onClick = { a.cancelLogin() })
                }
                loggedIn -> {
                    PrimaryButton("다음") { vm.goNext() }
                    if (ready) {
                        WantedTextButton(text = "다른 계정으로 로그인", modifier = Modifier.fillMaxWidth(), onClick = { a.loginBrowser() })
                    }
                }
                ready -> {
                    PrimaryButton("ChatGPT로 로그인") { a.loginBrowser() }
                    WantedTextButton(text = "나중에 할게요", modifier = Modifier.fillMaxWidth(), onClick = { vm.goNext() })
                }
                vm.sessionState == SessionState.FAILED -> {
                    PrimaryButton("다시 시도") { a.retrySession() }
                    WantedTextButton(text = "나중에 할게요", modifier = Modifier.fillMaxWidth(), onClick = { vm.goNext() })
                }
                else -> WantedTextButton(text = "나중에 할게요", modifier = Modifier.fillMaxWidth(), onClick = { vm.goNext() })
            }
        },
    ) {
        when {
            prompt != null -> {
                Card {
                    if (prompt.kind == LoginKind.DEVICE_CODE && prompt.code != null) {
                        Text("아래 코드를 브라우저에 붙여넣어 주세요", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            prompt.code,
                            style = t.title1Bold,
                            color = c.foregroundNeutralPrimary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("코드는 복사해 두었어요. 하이픈을 포함해 9자예요.", style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                        Spacer(Modifier.height(10.dp))
                        WantedChip(text = "코드 복사", onClick = { a.copyCode(prompt.code) })
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WantedCircularProgressIndicator()
                            Spacer(Modifier.width(12.dp))
                            Text("브라우저에서 로그인을 마쳐 주세요", style = t.body1Medium, color = c.foregroundNeutralPrimary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "승인하면 이 화면이 자동으로 이어져요. 끝나면 알림도 드려요.",
                            style = t.caption1Regular,
                            color = c.foregroundNeutralSecondary,
                        )
                    }
                }
            }
            loggedIn -> {
                val who = when (account?.kind) {
                    AccountInfo.Kind.CHATGPT -> account.email ?: "ChatGPT"
                    AccountInfo.Kind.API_KEY -> "API 키"
                    else -> "계정"
                }
                StatusCard("로그인됐어요 · $who")
                Spacer(Modifier.height(12.dp))
                Text(
                    "다른 계정을 쓰고 싶으면 아래 '다른 계정으로 로그인'을 눌러요. 브라우저에 이미 로그인된 계정이 있으면 그 계정이 먼저 선택될 수 있어요. 원하는 계정이 아니면 로그인 화면에서 계정을 바꿔 주세요.",
                    style = t.caption1Regular,
                    color = c.foregroundNeutralSecondary,
                )
            }
            ready -> {
                Card {
                    Text("브라우저로 로그인하면 코드를 입력할 필요 없이 승인만 누르면 돼요.", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                }
            }
            vm.sessionState == SessionState.FAILED -> {
                StatusCard("비서를 시작하지 못했어요${vm.sessionDetail?.let { " · $it" } ?: ""}", positive = false)
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WantedCircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("비서를 준비하고 있어요…", style = t.body1Medium, color = c.foregroundNeutralPrimary)
                }
            }
        }
        vm.loginMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = t.body2Regular, color = c.foregroundNegativePrimary)
        }
    }
}

// ---- 4. 알림 --------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotifyStep(vm: OnboardingViewModel, a: OnboardingActions) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    StepFrame(
        title = "알림을 켜 볼까요?",
        subtitle = "마감과 공지는 AI 없이 앱이 한 시간마다 조용히 확인해서 알려드려요.",
        bottom = {
            PrimaryButton("다음") {
                a.applyNotifications()
                vm.goNext()
            }
            WantedTextButton(text = "나중에 할게요", modifier = Modifier.fillMaxWidth(), onClick = { vm.goNext() })
        },
    ) {
        if (vm.notifyGranted) {
            StatusCard("알림이 허용돼 있어요")
        } else {
            Card {
                Text("알림 권한이 필요해요", style = t.body1Bold, color = c.foregroundNeutralPrimary)
                Spacer(Modifier.height(4.dp))
                Text("허용하지 않으면 마감·공지 알림이 오지 않아요.", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("알림 허용하기") { a.requestNotifications() }
            }
        }
        Spacer(Modifier.height(20.dp))
        SwitchRow("마감 임박 알림", NotifyUiFormat.windowsSummary(vm.deadlineWindows) + " (설정에서 바꿀 수 있어요)", vm.deadlineAlerts) { vm.deadlineAlerts = it }
        SwitchRow("새 공지 알림", "수강 중인 강의에 새 공지가 올라오면", vm.announcementAlerts) { vm.announcementAlerts = it }
        SwitchRow("정기 브리핑", "마감·누락·새 공지를 한 번에 요약해서", vm.briefEnabled) { vm.briefEnabled = it }
        if (vm.briefEnabled) {
            Spacer(Modifier.height(8.dp))
            // 한 줄에 다 안 들어가면 다음 줄로 넘긴다(옆으로 밀어야만 보이는 선택지는 놓치기 쉽고, 잘리기까지 했다).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (m in OnboardingLogic.briefTimeChoices) {
                    WantedChip(
                        text = NotifyUiFormat.formatTime(m, is24h = false),
                        isActive = vm.briefMinutes == m,
                        onClick = { vm.briefMinutes = m },
                    )
                }
                // 목록에 없는 시각은 "직접 선택"으로 고른다. 고른 뒤에는 그 시각이 칩에 보인다.
                val custom = vm.briefMinutes !in OnboardingLogic.briefTimeChoices
                WantedChip(
                    text = if (custom) NotifyUiFormat.formatTime(vm.briefMinutes, is24h = false) + " · 바꾸기" else "직접 선택",
                    isActive = custom,
                    onClick = { a.pickTime(vm.briefMinutes) { vm.briefMinutes = it } },
                )
            }
        }
        SwitchRow("방해 금지 시간", "이 시간에는 마감·공지 알림을 미뤘다가 끝나면 보내요(브리핑과 직접 정한 알림은 그대로)", vm.quietEnabled) { vm.quietEnabled = it }
        if (vm.quietEnabled) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WantedChip(text = NotifyUiFormat.formatTime(vm.quietStart, is24h = false) + " 부터", isActive = true,
                    onClick = { a.pickTime(vm.quietStart) { vm.quietStart = it } })
                WantedChip(text = NotifyUiFormat.formatTime(vm.quietEnd, is24h = false) + " 까지", isActive = true,
                    onClick = { a.pickTime(vm.quietEnd) { vm.quietEnd = it } })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body1Medium, color = c.foregroundNeutralPrimary)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
        }
        WantedCheckBox(
            onCheckedChange = onChange,
            style = CheckBoxStyle.Switch,
            checkState = if (checked) CheckBoxState.Checked else CheckBoxState.Unchecked,
        )
    }
}

// ---- 5. 완료 --------------------------------------------------------------------

@Composable
private fun DoneStep(a: OnboardingActions) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    StepFrame(
        title = "준비됐어요!",
        subtitle = "이렇게 물어볼 수 있어요. 눌러서 바로 시작해 보세요.",
        bottom = { PrimaryButton("비서 시작하기") { a.finish(null) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in OnboardingLogic.suggestions) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surfaceNeutralSecondary)
                        .clickable { a.finish(s) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = c.foregroundBrandPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s, style = t.body1Medium.copy(fontWeight = FontWeight.Medium), color = c.foregroundNeutralPrimary)
                }
            }
        }
    }
}
