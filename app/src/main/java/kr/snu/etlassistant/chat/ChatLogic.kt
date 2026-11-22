package kr.snu.etlassistant.chat

import kr.snu.etlassistant.chat.MarkdownLite.Kind
import kr.snu.etlassistant.codex.SessionState

/** 위쪽 배너를 눌렀을 때 할 일. */
enum class BannerAction { SETTINGS, RETRY, NOTIFICATIONS, NONE }

/** [dismissible]이면 ×(나중에)로 닫을 수 있다. */
data class BannerSpec(val text: String, val action: BannerAction, val dismissible: Boolean = false)

/** 알림이 오지 않는 이유. [AppOff]는 권한이나 앱 전체 알림이 꺼짐, [ChannelOff]는 쓰는 채널 하나가 꺼짐. */
sealed class NotifyProblem {
    object AppOff : NotifyProblem()
    data class ChannelOff(val channelId: String) : NotifyProblem()
    /** 알림은 켜져 있지만 배터리 최적화 대상이라 앱을 닫으면 확인이 늦거나 안 될 수 있다. */
    object BatteryOptimized : NotifyProblem()
    /** 배터리 최적화 대상인데 절전 모드까지 켜져 있다: 앱을 닫아 두면 새 조회가 막힌다(받아 둔 마감 알림은 온다). */
    object PowerSave : NotifyProblem()
}

/** 전송 버튼의 모습: 보내기 / 답변 중단 / 비활성. */
enum class SendMode { SEND, STOP, DISABLED }

/** 마크다운 한 구간을 화면에서 어떻게 그릴지(글꼴 굵기, 고정폭, 배경, 크기 배율, 링크 주소). 안드로이드·Compose에 의존하지 않는다. */
data class MdStyle(
    val start: Int,
    val end: Int,
    val kind: Kind,
    val bold: Boolean,
    val mono: Boolean,
    val background: Boolean,
    val sizeScale: Float,
    val url: String?,
)

/** 채팅 화면의 순수 판정 로직(단위 테스트 대상). */
object ChatLogic {

    /** 알림 꺼짐 배너를 닫으면 이 기간 동안 숨긴다. */
    const val NOTIFY_BANNER_SNOOZE_MS = 7L * 24 * 3600 * 1000

    /**
     * 지금 상태에 맞는 안내 배너. 우선순위: 토큰 없음 → 세션 실패 → 준비 중 → 로그인 안 됨 → 알림 꺼짐. 보여 줄 게 없으면 null.
     * 알림 꺼짐은 닫은 뒤 [NOTIFY_BANNER_SNOOZE_MS] 동안 숨긴다([notifyDismissedAt], 0이면 닫은 적 없음).
     */
    fun banner(
        hasToken: Boolean,
        state: SessionState,
        detail: String?,
        loggedIn: Boolean,
        notifyProblem: NotifyProblem? = null,
        notifyDismissedAt: Long = 0L,
        nowMs: Long = 0L,
    ): BannerSpec? = when {
        !hasToken -> BannerSpec("eTL 토큰이 필요해요. 눌러서 설정으로 이동", BannerAction.SETTINGS)
        state == SessionState.FAILED ->
            BannerSpec("비서를 시작하지 못했어요: ${detail ?: ""}\n눌러서 다시 시도", BannerAction.RETRY)
        state != SessionState.READY -> BannerSpec("비서를 준비하는 중이에요…", BannerAction.NONE)
        !loggedIn -> BannerSpec("로그인이 필요해요. 눌러서 설정으로 이동", BannerAction.SETTINGS)
        notifyProblem != null && !(notifyDismissedAt > 0 && nowMs - notifyDismissedAt < NOTIFY_BANNER_SNOOZE_MS) ->
            BannerSpec(
                when (notifyProblem) {
                    NotifyProblem.BatteryOptimized -> "배터리 최적화 때문에 앱을 닫아 두면 알림이 늦거나 안 올 수 있어요. 눌러서 해제"
                    NotifyProblem.PowerSave -> "절전 모드에서는 새 과제·공지 알림이 늦을 수 있어요(받아 둔 마감 알림은 와요). 눌러서 배터리 제한 풀기"
                    else -> "알림이 꺼져 있어서 마감·공지 알림이 오지 않아요. 눌러서 켜기"
                },
                BannerAction.NOTIFICATIONS, dismissible = true,
            )
        else -> null
    }

    /**
     * 켜 둔 알림(마감/공지/브리핑)이 실제로 올 수 있는지. 하나도 켜지 않았으면 null.
     * [usedChannels]는 켜 둔 알림의 채널 ID, [blockedChannels]는 중요도가 NONE인 채널 ID.
     * 알림이 다 켜져 있어도 [batteryOptimized](배터리 최적화 예외가 아님)면 [NotifyProblem.BatteryOptimized],
     * 거기에 [powerSave](절전 모드)까지면 [NotifyProblem.PowerSave]. 예외 상태면 절전 모드의 네트워크 차단에서 빠지므로 경고하지 않는다.
     */
    fun notifyProblem(
        appEnabled: Boolean,
        usedChannels: List<String>,
        blockedChannels: Set<String>,
        batteryOptimized: Boolean = false,
        powerSave: Boolean = false,
    ): NotifyProblem? = when {
        usedChannels.isEmpty() -> null
        !appEnabled -> NotifyProblem.AppOff
        else -> usedChannels.firstOrNull { it in blockedChannels }?.let { NotifyProblem.ChannelOff(it) }
            ?: when {
                batteryOptimized && powerSave -> NotifyProblem.PowerSave
                batteryOptimized -> NotifyProblem.BatteryOptimized
                else -> null
            }
    }

    /** 글이 비어 있어도 첨부가 있으면 보낼 수 있다. 첨부를 준비하는 중이면 기다린다. */
    fun sendMode(busy: Boolean, input: String, hasAttachments: Boolean = false, importing: Boolean = false): SendMode = when {
        busy -> SendMode.STOP
        importing -> SendMode.DISABLED
        input.isBlank() && !hasAttachments -> SendMode.DISABLED
        else -> SendMode.SEND
    }

    /**
     * [MarkdownLite] 스팬을 그리기용 구간으로 바꾼다. 글 길이를 넘는 구간은 잘라 내고, 빈 구간과 주소 없는 링크는 버린다.
     * 시작 위치 순(같으면 긴 구간 먼저)으로 돌려줘서, 겹치면 나중 것이 위에 덧입혀진다.
     */
    fun styleRanges(r: MarkdownLite.Result): List<MdStyle> {
        val len = r.text.length
        return r.spans.mapNotNull { s ->
            val start = s.start.coerceAtLeast(0)
            val end = s.end.coerceAtMost(len)
            if (end <= start) return@mapNotNull null
            when (s.kind) {
                Kind.BOLD -> MdStyle(start, end, s.kind, bold = true, mono = false, background = false, sizeScale = 1f, url = null)
                Kind.CODE -> MdStyle(start, end, s.kind, bold = false, mono = true, background = true, sizeScale = 0.92f, url = null)
                Kind.CODE_BLOCK -> MdStyle(start, end, s.kind, bold = false, mono = true, background = true, sizeScale = 0.88f, url = null)
                Kind.HEADER -> MdStyle(start, end, s.kind, bold = true, mono = false, background = false, sizeScale = 1.1f, url = null)
                Kind.LINK -> {
                    val url = s.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
                    MdStyle(start, end, s.kind, bold = false, mono = false, background = false, sizeScale = 1f, url = url)
                }
            }
        }.sortedWith(compareBy<MdStyle> { it.start }.thenByDescending { it.end })
    }
}
