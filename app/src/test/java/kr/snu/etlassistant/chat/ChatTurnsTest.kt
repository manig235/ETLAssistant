package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnsTest {

    @Test fun busyIsPerConversationAndConcurrencyIsUnlimited() {
        val r = TurnRegistry()
        val ids = (1..12).map { "chat-$it" }
        ids.forEach { assertTrue(r.start(it) != null) }
        assertEquals(ids.toSet(), r.running())
        assertEquals(ids, r.running().toList()) // 시작 순서
        assertTrue(r.isBusy("chat-3"))
        assertFalse(r.isBusy("other"))
        // 같은 대화에서 두 번 보내지는 못한다.
        assertNull(r.start("chat-3"))
        r.finish("chat-3")
        assertFalse(r.isBusy("chat-3"))
        assertTrue(r.isBusy("chat-4"))
        assertTrue(r.start("chat-3") != null)
    }

    @Test fun notificationsRouteByThread() {
        val r = TurnRegistry()
        r.start("A"); r.start("B")
        r.bindThread("A", "thr-a"); r.bindThread("B", "thr-b")
        assertEquals("A", r.liveTurnForThread("thr-a")?.chatId)
        assertEquals("B", r.liveTurnForThread("thr-b")?.chatId)
        assertNull(r.liveTurnForThread("thr-x"))
        assertNull(r.liveTurnForThread(null))
        assertEquals(setOf("thr-a", "thr-b"), r.runningThreads())
        // 끝난 대화의 늦은 알림은 쓰지 않지만 스레드 기억은 남는다(다음 턴의 재개).
        r.finish("A")
        assertNull(r.liveTurnForThread("thr-a"))
        assertEquals("A", r.chatForThread("thr-a"))
        // 재개에 실패해 새 스레드를 만들면 옛 스레드는 잊는다.
        r.bindThread("B", "thr-b2")
        assertNull(r.chatForThread("thr-b"))
        assertEquals("B", r.chatForThread("thr-b2"))
    }

    @Test fun deletedConversationReceivesNothing() {
        val r = TurnRegistry()
        r.start("A")!!.also { r.bindThread("A", "t1") }.deleted = true
        assertNull(r.liveTurnForThread("t1"))
        assertEquals(TurnRegistry.ToolRoute.None, r.routeTool("t1"))
        assertEquals(TurnRegistry.ToolRoute.None, r.routeTool(null))
        r.finish("A"); r.forget("A")
        assertNull(r.chatForThread("t1"))
    }

    @Test fun toolEventsRouteByMetaThreadOrFallBack() {
        val r = TurnRegistry()
        assertEquals(TurnRegistry.ToolRoute.None, r.routeTool(null))
        r.start("A"); r.bindThread("A", "ta")
        // 스레드를 모르면 답하는 대화가 하나일 때 그 대화.
        assertEquals(TurnRegistry.ToolRoute.One("A"), r.routeTool(null))
        r.start("B"); r.bindThread("B", "tb")
        assertEquals(TurnRegistry.ToolRoute.One("B"), r.routeTool("tb"))
        assertEquals(TurnRegistry.ToolRoute.One("A"), r.routeTool("ta"))
        // 여럿이면 정하지 못한다(알림은 모두에, 파일은 마지막에 시작한 대화에).
        assertEquals(TurnRegistry.ToolRoute.Ambiguous(listOf("A", "B"), "B"), r.routeTool(null))
        // 답하지 않는 스레드(개발자 도구 시험, 끝난 대화)는 붙이지 않는다.
        assertEquals(TurnRegistry.ToolRoute.None, r.routeTool("unknown"))
        r.finish("A")
        assertEquals(TurnRegistry.ToolRoute.None, r.routeTool("ta"))
    }

    @Test fun interruptBeforeTurnStartIsNotLost() {
        val r = TurnRegistry()
        val t = r.start("A")!!
        r.bindThread("A", "th")
        // turn/start 응답 전에 멈춤: 기록만 하고 대상은 없다.
        assertNull(r.interruptTarget("A"))
        assertTrue(t.interruptRequested)
        // 응답을 받은 쪽이 기록을 보고 멈춘다.
        assertSame(t, r.setTurnId("A", "turn-1"))
        assertEquals("th" to "turn-1", r.interruptTarget("A"))
        // 끝난 뒤의 응답은 무시한다.
        r.finish("A")
        assertNull(r.setTurnId("A", "turn-2"))
        assertNull(r.interruptTarget("A"))
    }

    @Test fun clearEndsEverything() {
        val r = TurnRegistry()
        r.start("A"); r.start("B")
        assertEquals(listOf("A", "B"), r.clear().map { it.chatId })
        assertTrue(r.isEmpty())
    }

    @Test fun finishNoticeDecision() {
        // 다른 대화가 끝남, 채팅 화면이 보임 → 아래 막대.
        assertEquals(FinishNotice.BAR, TurnLogic.finishNotice("A", "B", true, TurnEnd.COMPLETED, false))
        assertEquals(FinishNotice.BAR, TurnLogic.finishNotice("A", "B", true, TurnEnd.FAILED, false))
        // 지금 보는 대화 → 알리지 않음.
        assertEquals(FinishNotice.NONE, TurnLogic.finishNotice("A", "A", true, TurnEnd.COMPLETED, false))
        // 채팅 화면이 안 보임 → 보던 대화여도 기기 알림.
        assertEquals(FinishNotice.NOTIFICATION, TurnLogic.finishNotice("A", "A", false, TurnEnd.COMPLETED, false))
        assertEquals(FinishNotice.NOTIFICATION, TurnLogic.finishNotice("A", "B", false, TurnEnd.FAILED, false))
        // 직접 멈춤, 지운 대화 → 알리지 않음.
        assertEquals(FinishNotice.NONE, TurnLogic.finishNotice("A", "B", true, TurnEnd.INTERRUPTED, false))
        assertEquals(FinishNotice.NONE, TurnLogic.finishNotice("A", "B", false, TurnEnd.INTERRUPTED, false))
        assertEquals(FinishNotice.NONE, TurnLogic.finishNotice("A", "B", false, TurnEnd.COMPLETED, true))
    }

    @Test fun finishText() {
        assertEquals("‘시험 공부’ 답변이 끝났어요", TurnLogic.finishText("시험 공부", true))
        assertEquals("‘시험 공부’ 답변을 마치지 못했어요", TurnLogic.finishText("시험 공부", false))
        assertEquals("‘새 대화’ 답변이 끝났어요", TurnLogic.finishText(null, true))
        assertEquals("‘새 대화’ 답변이 끝났어요", TurnLogic.finishText(" ", true))
    }

    @Test fun approvalUiDecision() {
        assertEquals(TurnLogic.ApprovalUi.None, TurnLogic.approvalUi(emptyList(), "A"))
        // 지금 대화의 요청 → 확인 창.
        assertEquals(TurnLogic.ApprovalUi.Dialog("r1"), TurnLogic.approvalUi(listOf("r1" to "A"), "A"))
        // 다른 대화의 요청만 → 배너(누르면 그 대화).
        assertEquals(TurnLogic.ApprovalUi.Elsewhere("B"), TurnLogic.approvalUi(listOf("r1" to "B"), "A"))
        // 다른 대화의 요청이 먼저 왔어도 지금 대화의 요청을 먼저 묻는다.
        assertEquals(TurnLogic.ApprovalUi.Dialog("r2"), TurnLogic.approvalUi(listOf("r1" to "B", "r2" to "A"), "A"))
        // 대화를 모르는 요청은 예전처럼 지금 화면에서 묻는다.
        assertEquals(TurnLogic.ApprovalUi.Dialog("r1"), TurnLogic.approvalUi(listOf("r1" to null, "r2" to "B"), "A"))
        assertEquals(TurnLogic.ApprovalUi.Elsewhere("C"), TurnLogic.approvalUi(listOf("r1" to "C", "r2" to "B"), "A"))
    }
}
