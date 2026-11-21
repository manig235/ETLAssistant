package kr.snu.etlassistant.onboarding

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlLogic.str
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.notify.NotifyScheduler
import kr.snu.etlassistant.notify.NotifySettingsStore

/** eTL 토큰 확인 상태. */
sealed class TokenState {
    object Idle : TokenState()
    object Checking : TokenState()
    /** 저장됨. [name]은 방금 확인한 이름(이미 저장돼 있던 토큰이면 null). */
    data class Saved(val name: String?) : TokenState()
    data class Error(val message: String) : TokenState()
}

/** 첫 실행 안내의 화면 상태. 네트워크 확인과 설정 저장도 여기서 한다. */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    var step by mutableStateOf(Step.WELCOME)

    // ---- eTL 토큰 ----
    var tokenInput by mutableStateOf("")
    var tokenState by mutableStateOf<TokenState>(
        if (EtlService.tokens(app).hasToken()) TokenState.Saved(null) else TokenState.Idle
    )

    // ---- ChatGPT 로그인(세션 이벤트로 갱신) ----
    var sessionState by mutableStateOf(SessionState.STOPPED)
    var sessionDetail by mutableStateOf<String?>(null)
    var account by mutableStateOf<AccountInfo?>(null)
    var loginPrompt by mutableStateOf<SessionEvent.LoginPrompt?>(null)
    var loginMessage by mutableStateOf<String?>(null)

    // ---- 알림 ----
    var notifyGranted by mutableStateOf(false)
    var deadlineAlerts by mutableStateOf(true)
    var announcementAlerts by mutableStateOf(true)
    var briefEnabled by mutableStateOf(true)
    var briefMinutes by mutableStateOf(OnboardingLogic.briefTimeChoices[1])
    /** 방해 금지: 기본 꺼짐. 켜면 23:00~07:00(바꿀 수 있음). */
    var quietEnabled by mutableStateOf(false)
    var quietStart by mutableStateOf(23 * 60)
    var quietEnd by mutableStateOf(7 * 60)
    /** 마감 알림 시점(안내에서는 보여 주기만 하고, 바꾸는 것은 설정 화면에서). */
    val deadlineWindows: List<Int> = NotifySettingsStore(app).load().deadlineWindowsMinutes

    val tokenSaved: Boolean get() = tokenState is TokenState.Saved

    /** 이미 저장된 토큰이 있는가(안내를 다시 보는 기존 사용자). */
    val hasStoredToken: Boolean get() = EtlService.tokens(getApplication()).hasToken()

    init {
        // 기존 사용자가 안내를 다시 볼 때는 저장된 알림 설정을 그대로 보여 준다(기본값으로 덮어쓰지 않도록).
        if (hasStoredToken) {
            val s = NotifySettingsStore(app).load()
            deadlineAlerts = s.deadlineAlerts
            announcementAlerts = s.announcementAlerts
            briefEnabled = s.briefEnabled
            briefMinutes = s.briefMinutesOfDay.firstOrNull() ?: briefMinutes
            quietEnabled = s.quietEnabled
            quietStart = s.quietStartMinutes
            quietEnd = s.quietEndMinutes
        }
    }

    fun goNext() {
        step = OnboardingLogic.next(step)
    }

    fun goBack() {
        step = OnboardingLogic.previous(step)
    }

    fun onSessionEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.State -> {
                sessionState = e.state
                sessionDetail = e.detail
            }
            // 로그인 진행 화면은 LoginDone에서만 닫는다. 계정 전환 중에는 이전 계정이 계속 보고될 수 있어서,
            // 계정 이벤트로 닫으면 진행 화면이 사라져 버린다.
            is SessionEvent.Account -> account = e.info
            is SessionEvent.LoginPrompt -> {
                loginPrompt = e
                loginMessage = null
            }
            is SessionEvent.LoginDone -> {
                loginPrompt = null
                loginMessage = if (e.success) null else (e.error ?: "로그인하지 못했어요")
            }
            // 화면이 꺼져 있는 동안 로그인이 끝났거나 취소할 로그인이 없을 때. 결과 문구는 건드리지 않는다.
            SessionEvent.LoginIdle -> loginPrompt = null
            else -> {}
        }
    }

    /**
     * 입력한 토큰으로 eTL에 **실제로 접속해 확인**하고, 맞으면 Keystore에 암호화해 저장한다.
     * 토큰 원문은 화면 상태에만 있고 로그와 오류 문구에는 넣지 않는다.
     */
    fun checkAndSaveToken() {
        val token = tokenInput.trim()
        if (!OnboardingLogic.isPlausibleEtlToken(token)) {
            tokenState = TokenState.Error("토큰 모양이 아니에요. 공백 없이 그대로 붙여넣어 주세요")
            return
        }
        tokenState = TokenState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val me = EtlClient(token).getObject("/api/v1/users/self")
                    val name = OnboardingLogic.displayName(me.str("name") ?: me.str("short_name"))
                    EtlService.tokens(getApplication()).save(token)
                    TokenState.Saved(name)
                } catch (e: EtlAuthException) {
                    TokenState.Error("eTL이 이 토큰을 받아주지 않았어요. 다시 만들어서 붙여넣어 주세요")
                } catch (e: EtlException) {
                    TokenState.Error("eTL에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요")
                } catch (e: Exception) {
                    TokenState.Error("확인하지 못했어요(${e.javaClass.simpleName})")
                }
            }
            tokenState = result
            if (result is TokenState.Saved) tokenInput = ""
        }
    }

    /** 이미 저장된 토큰을 두고 새 토큰으로 바꾸려는 경우. */
    fun replaceToken() {
        tokenState = TokenState.Idle
        tokenInput = ""
    }

    /** 새 토큰 입력을 그만두고 저장돼 있던 토큰을 그대로 쓴다. */
    fun keepExistingToken() {
        if (hasStoredToken) {
            tokenState = TokenState.Saved(null)
            tokenInput = ""
        }
    }

    /** 알림 선택(방해 금지 포함)을 저장하고 예약한다. 다른 알림 설정은 기존 값을 유지한다. */
    fun applyNotificationChoices() {
        val app = getApplication<Application>()
        val store = NotifySettingsStore(app)
        store.save(
            store.load().copy(
                deadlineAlerts = deadlineAlerts,
                announcementAlerts = announcementAlerts,
                briefEnabled = briefEnabled,
                briefMinutesOfDay = listOf(briefMinutes),
                quietEnabled = quietEnabled,
                quietStartMinutes = quietStart,
                quietEndMinutes = quietEnd,
            )
        )
        NotifyScheduler.apply(app)
        // 첫 확인을 바로 해서 공지 기준선을 잡는다(그래야 이후의 새 공지만 알린다).
        if (deadlineAlerts || announcementAlerts) NotifyScheduler.checkNow(app)
    }
}
