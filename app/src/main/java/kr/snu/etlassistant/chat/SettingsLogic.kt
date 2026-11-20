package kr.snu.etlassistant.chat

import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.notify.NotifySettings

/** 설정 화면의 색 톤(상태 줄). */
enum class StatusTone { NEUTRAL, POSITIVE, CAUTION, NEGATIVE }

/** 계정 상태 줄: 문구, 톤, 진행 중(스피너) 여부. */
data class AccountStatus(val text: String, val tone: StatusTone, val loading: Boolean = false)

/** 안드로이드 API에 의존하지 않는 설정 화면 규칙(JVM 단위 테스트 대상). */
object SettingsLogic {

    /** 로그인 코드 유효 시간(기기 코드 방식). */
    const val LOGIN_TTL_MS = 15 * 60_000L

    /** 형식만 대충 본다(실제 유효성은 OpenAI가 판단). 공백, 한글 등은 거른다. */
    fun isPlausibleApiKey(s: String): Boolean = s.startsWith("sk-") && s.length >= 20 && s.all { it.code in 0x21..0x7e }

    /** `sk-…abcd`처럼 끝 4자리만 보인다. */
    fun maskKey(s: String): String = if (s.length <= 8) "••••" else "sk-…" + s.takeLast(4)

    /** eTL 토큰으로 쓸 수 있는 모양인가(공백 없는 ASCII 8자 이상). 실제 유효성은 eTL에 물어봐야 안다. */
    fun isPlausibleEtlToken(s: String): Boolean = Regex("[\\u0021-\\u007e]{8,}").matches(s)

    /** 남은 시간(초)을 `m:ss`로. 음수는 0으로 본다. */
    fun formatRemaining(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** 만료 시각까지 남은 초(0 이상). */
    fun remainingSeconds(expiresAtMs: Long, nowMs: Long): Long = ((expiresAtMs - nowMs) / 1000).coerceAtLeast(0)

    /** 계정 상태 줄. 세션 준비 상태와 계정 정보로 정한다. */
    fun accountStatus(state: SessionState, detail: String?, account: AccountInfo?): AccountStatus = when {
        state == SessionState.FAILED ->
            AccountStatus("비서를 시작하지 못했어요${detail?.let { ": $it" } ?: ""}", StatusTone.NEGATIVE)
        state != SessionState.READY -> AccountStatus("준비하는 중이에요…", StatusTone.NEUTRAL, loading = true)
        account == null -> AccountStatus("확인하는 중이에요…", StatusTone.NEUTRAL, loading = true)
        account.kind == AccountInfo.Kind.CHATGPT ->
            AccountStatus("로그인됨 · ${account.email ?: "ChatGPT"}" + (account.plan?.let { " · $it" } ?: ""), StatusTone.POSITIVE)
        account.kind == AccountInfo.Kind.API_KEY -> AccountStatus("API 키로 사용 중이에요", StatusTone.POSITIVE)
        account.kind == AccountInfo.Kind.UNKNOWN -> AccountStatus("로그인됨", StatusTone.POSITIVE)
        else -> AccountStatus("로그인되지 않았어요", StatusTone.NEUTRAL)
    }

    /** 브리핑 시각을 하나 바꾼다. 같은 시각이면 합치고, 정렬·개수 제한은 normalize가 맡는다. */
    fun replaceBriefTime(times: List<Int>, index: Int, picked: Int): List<Int> {
        val base = NotifyUiFormat.normalizeBriefTimes(times)
        if (index !in base.indices) return base
        return NotifyUiFormat.normalizeBriefTimes(base.toMutableList().also { it[index] = picked })
    }

    /** 브리핑 시각을 하나 더한다(최대 2개). 첫 시각의 12시간 뒤를 기본값으로 해서 같은 시각이 되지 않게 한다. */
    fun addBriefTime(times: List<Int>): List<Int> {
        val base = NotifyUiFormat.normalizeBriefTimes(times)
        if (base.size >= 2) return base
        return NotifyUiFormat.normalizeBriefTimes(base + (base.first() + 12 * 60) % 1440)
    }

    /** 두 번째 브리핑 시각을 지운다(최소 1개는 남는다). */
    fun removeSecondBriefTime(times: List<Int>): List<Int> =
        NotifyUiFormat.normalizeBriefTimes(NotifyUiFormat.normalizeBriefTimes(times).take(1))

    // ---- 마감 알림 시점(분) ----------------------------------------------------
    // 저장되는 것은 "켜진 시점 목록" 하나뿐이다. 화면에는 그 목록만 칩으로 보이고, ×로 지우고 + 추가로 더한다.

    /** 칩으로 보일 시점(오름차순: 가까운 것부터). */
    fun windowChips(current: List<Int>): List<Int> = NotifySettings.normalizeWindows(current).sorted()

    /** 추가 대화상자의 빠른 버튼. */
    val quickWindows: List<Int> get() = NotifySettings.DEADLINE_WINDOW_PRESETS

    /** 시점 하나를 지운다. 지우면 하나도 남지 않으면 지우지 않고 null(알림을 끄는 것은 스위치로). */
    fun removeWindow(current: List<Int>, minutes: Int): List<Int>? {
        val base = NotifySettings.normalizeWindows(current)
        if (minutes !in base) return base
        val next = base - minutes
        return if (next.isEmpty()) null else next
    }

    const val LAST_WINDOW_MESSAGE = "알림 시점이 하나는 있어야 해요. 알림을 끄려면 위 스위치를 쓰세요"

    /** 더 추가할 수 있는가(최대 개수). */
    fun canAddWindow(current: List<Int>): Boolean = NotifySettings.normalizeWindows(current).size < NotifySettings.MAX_WINDOWS

    /** 기본값(3일, 24시간, 3시간, 1시간 전)인가. 기본값이면 "기본값으로 되돌리기"를 숨긴다. */
    fun isDefaultWindows(current: List<Int>): Boolean =
        NotifySettings.normalizeWindows(current) == NotifySettings.normalizeWindows(NotifySettings.DEFAULT_DEADLINE_WINDOWS)

    fun defaultWindows(): List<Int> = NotifySettings.normalizeWindows(NotifySettings.DEFAULT_DEADLINE_WINDOWS)

    enum class WindowUnit(val label: String, val minutes: Int) { MINUTE("분", 1), HOUR("시간", 60), DAY("일", 24 * 60) }

    /** 추가할 값 검사 결과. */
    sealed class CustomWindow {
        data class Ok(val minutes: Int) : CustomWindow()
        data class Error(val message: String) : CustomWindow()
    }

    /** 분 값을 추가할 수 있는지 본다(범위, 이미 있음, 개수). */
    fun checkWindow(minutes: Long, current: List<Int>): CustomWindow {
        if (minutes < NotifySettings.MIN_WINDOW_MINUTES) return CustomWindow.Error("${NotifySettings.MIN_WINDOW_MINUTES}분 이상으로 정해 주세요")
        if (minutes > NotifySettings.MAX_WINDOW_MINUTES) return CustomWindow.Error("최대 14일 전까지 정할 수 있어요")
        val base = NotifySettings.normalizeWindows(current)
        if (minutes.toInt() in base) return CustomWindow.Error("이미 있는 시점이에요")
        if (base.size >= NotifySettings.MAX_WINDOWS) {
            return CustomWindow.Error("알림 시점은 ${NotifySettings.MAX_WINDOWS}개까지예요. 다른 시점을 먼저 지워 주세요")
        }
        return CustomWindow.Ok(minutes.toInt())
    }

    /** 직접 입력: 숫자와 단위를 분으로 바꿔 검사한다. */
    fun parseCustomWindow(amountText: String, unit: WindowUnit, current: List<Int>): CustomWindow {
        val amount = amountText.trim().toIntOrNull() ?: return CustomWindow.Error("숫자를 입력해 주세요")
        if (amount <= 0) return CustomWindow.Error("1 이상으로 입력해 주세요")
        return checkWindow(amount.toLong() * unit.minutes, current)
    }

    /** 시점을 더한다(이미 있으면 그대로, 개수 제한 안에서). */
    fun addWindow(current: List<Int>, minutes: Int): List<Int> {
        val base = NotifySettings.normalizeWindows(current)
        if (minutes in base || base.size >= NotifySettings.MAX_WINDOWS) return base
        return NotifySettings.normalizeWindows(base + minutes)
    }

}
