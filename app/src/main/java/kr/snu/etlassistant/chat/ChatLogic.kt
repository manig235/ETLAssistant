package kr.snu.etlassistant.chat

import kr.snu.etlassistant.chat.MarkdownLite.Kind
import kr.snu.etlassistant.codex.SessionState

/** 위쪽 배너를 눌렀을 때 할 일. */
enum class BannerAction { SETTINGS, RETRY, NONE }

data class BannerSpec(val text: String, val action: BannerAction)

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

    /** 지금 상태에 맞는 안내 배너. 우선순위: 토큰 없음 → 세션 실패 → 준비 중 → 로그인 안 됨. 보여 줄 게 없으면 null. */
    fun banner(hasToken: Boolean, state: SessionState, detail: String?, loggedIn: Boolean): BannerSpec? = when {
        !hasToken -> BannerSpec("eTL 토큰이 필요해요. 눌러서 설정으로 이동", BannerAction.SETTINGS)
        state == SessionState.FAILED ->
            BannerSpec("비서를 시작하지 못했어요: ${detail ?: ""}\n눌러서 다시 시도", BannerAction.RETRY)
        state != SessionState.READY -> BannerSpec("비서를 준비하는 중이에요…", BannerAction.NONE)
        !loggedIn -> BannerSpec("로그인이 필요해요. 눌러서 설정으로 이동", BannerAction.SETTINGS)
        else -> null
    }

    fun sendMode(busy: Boolean, input: String): SendMode = when {
        busy -> SendMode.STOP
        input.isBlank() -> SendMode.DISABLED
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
