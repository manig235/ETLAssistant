package kr.snu.etlassistant.chat

import kr.snu.etlassistant.chat.MarkdownLite.Kind
import kr.snu.etlassistant.codex.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {

    // ---- 배너 ----

    @Test fun bannerNoTokenComesFirst() {
        val b = ChatLogic.banner(hasToken = false, state = SessionState.FAILED, detail = "x", loggedIn = false)!!
        assertEquals(BannerAction.SETTINGS, b.action)
        assertTrue(b.text.contains("토큰"))
    }

    @Test fun bannerFailedOffersRetry() {
        val b = ChatLogic.banner(true, SessionState.FAILED, "프로세스 종료", false)!!
        assertEquals(BannerAction.RETRY, b.action)
        assertTrue(b.text.contains("프로세스 종료"))
    }

    @Test fun bannerFailedWithoutDetail() {
        assertNotNull(ChatLogic.banner(true, SessionState.FAILED, null, true))
    }

    @Test fun bannerStartingIsInfoOnly() {
        for (s in listOf(SessionState.STOPPED, SessionState.STARTING)) {
            assertEquals(BannerAction.NONE, ChatLogic.banner(true, s, null, false)!!.action)
        }
    }

    @Test fun bannerNotLoggedInGoesToSettings() {
        assertEquals(BannerAction.SETTINGS, ChatLogic.banner(true, SessionState.READY, null, false)!!.action)
    }

    @Test fun bannerHiddenWhenAllGood() {
        assertNull(ChatLogic.banner(true, SessionState.READY, null, true))
    }

    // ---- 전송 버튼 ----

    @Test fun sendModes() {
        assertEquals(SendMode.STOP, ChatLogic.sendMode(busy = true, input = ""))
        assertEquals(SendMode.STOP, ChatLogic.sendMode(busy = true, input = "안녕"))
        assertEquals(SendMode.DISABLED, ChatLogic.sendMode(busy = false, input = "  \n"))
        assertEquals(SendMode.SEND, ChatLogic.sendMode(busy = false, input = "안녕"))
    }

    // ---- 마크다운 스팬 → 스타일 ----

    @Test fun styleKinds() {
        val r = MarkdownLite.render("## 제목\n**굵게** 와 `코드` 와 [링크](https://a.kr/x)\n```\nblock\n```")
        val by = ChatLogic.styleRanges(r).associateBy { it.kind }
        assertTrue(by.getValue(Kind.BOLD).bold)
        assertTrue(by.getValue(Kind.CODE).mono && by.getValue(Kind.CODE).background)
        assertTrue(by.getValue(Kind.CODE_BLOCK).mono && by.getValue(Kind.CODE_BLOCK).sizeScale < by.getValue(Kind.CODE).sizeScale)
        assertTrue(by.getValue(Kind.HEADER).bold && by.getValue(Kind.HEADER).sizeScale > 1f)
        assertEquals("https://a.kr/x", by.getValue(Kind.LINK).url)
        assertEquals("링크", r.text.substring(by.getValue(Kind.LINK).start, by.getValue(Kind.LINK).end))
    }

    @Test fun rangesAreSortedAndClamped() {
        val r = MarkdownLite.Result(
            "abcdef",
            listOf(
                MarkdownLite.Span(3, 99, Kind.BOLD),
                MarkdownLite.Span(0, 4, Kind.CODE),
                MarkdownLite.Span(0, 6, Kind.HEADER),
                MarkdownLite.Span(5, 5, Kind.BOLD), // 빈 구간
                MarkdownLite.Span(7, 9, Kind.BOLD), // 글 밖
            ),
        )
        val out = ChatLogic.styleRanges(r)
        assertEquals(listOf(Kind.HEADER, Kind.CODE, Kind.BOLD), out.map { it.kind })
        assertEquals(6, out.last().end)
    }

    @Test fun nonHttpLinkIsDropped() {
        val r = MarkdownLite.Result("x", listOf(MarkdownLite.Span(0, 1, Kind.LINK, "javascript:alert(1)"), MarkdownLite.Span(0, 1, Kind.LINK, null)))
        assertTrue(ChatLogic.styleRanges(r).isEmpty())
    }

    @Test fun nestedBoldInsideHeaderKeepsBoth() {
        val r = MarkdownLite.render("# 가 **나** 다")
        val kinds = ChatLogic.styleRanges(r).map { it.kind }
        assertEquals(listOf(Kind.HEADER, Kind.BOLD), kinds)
    }

    // ---- 알림 꺼짐 배너 --------------------------------------------------------

    private val day = 24L * 3600 * 1000

    @Test fun notifyBannerHasLowestPriority() {
        val p = NotifyProblem.AppOff
        assertEquals(BannerAction.SETTINGS, ChatLogic.banner(false, SessionState.READY, null, true, p)!!.action)
        assertEquals(BannerAction.RETRY, ChatLogic.banner(true, SessionState.FAILED, null, true, p)!!.action)
        assertEquals(BannerAction.NONE, ChatLogic.banner(true, SessionState.STARTING, null, true, p)!!.action)
        assertEquals(BannerAction.SETTINGS, ChatLogic.banner(true, SessionState.READY, null, false, p)!!.action)
        val b = ChatLogic.banner(true, SessionState.READY, null, true, p)!!
        assertEquals(BannerAction.NOTIFICATIONS, b.action)
        assertTrue(b.dismissible)
        assertEquals(null, ChatLogic.banner(true, SessionState.READY, null, true, null))
    }

    @Test fun notifyBannerSnoozesFor7Days() {
        val p = NotifyProblem.AppOff
        val now = 100 * day
        assertEquals(null, ChatLogic.banner(true, SessionState.READY, null, true, p, notifyDismissedAt = now - 6 * day, nowMs = now))
        assertNotNull(ChatLogic.banner(true, SessionState.READY, null, true, p, notifyDismissedAt = now - 7 * day, nowMs = now))
        assertNotNull(ChatLogic.banner(true, SessionState.READY, null, true, p, notifyDismissedAt = 0, nowMs = now))
    }

    @Test fun notifyProblemDetection() {
        // 알림을 모두 껐으면 경고하지 않는다
        assertEquals(null, ChatLogic.notifyProblem(false, emptyList(), emptySet()))
        assertEquals(NotifyProblem.AppOff, ChatLogic.notifyProblem(false, listOf("deadline"), emptySet()))
        assertEquals(NotifyProblem.ChannelOff("announcement"), ChatLogic.notifyProblem(true, listOf("deadline", "announcement"), setOf("announcement", "brief")))
        // 꺼진 채널이 쓰지 않는 알림이면 괜찮다
        assertEquals(null, ChatLogic.notifyProblem(true, listOf("deadline"), setOf("brief")))
    }
}
