import Foundation
import Testing
@testable import ChatKit

/// Kotlin `ChatTurnsTest`의 판정 부분(대화마다 따로 답하기, 2026-11-27).
@Suite struct ChatTurnsTests {
    @Test func finishNoticeDecision() {
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "B", chatScreenVisible: true, end: .completed, deleted: false) == .bar)
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "B", chatScreenVisible: true, end: .failed, deleted: false) == .bar)
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "A", chatScreenVisible: true, end: .completed, deleted: false) == FinishNotice.none)
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "A", chatScreenVisible: false, end: .completed, deleted: false) == .notification)
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "B", chatScreenVisible: false, end: .failed, deleted: false) == .notification)
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "B", chatScreenVisible: true, end: .interrupted, deleted: false) == FinishNotice.none)
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "B", chatScreenVisible: false, end: .interrupted, deleted: false) == FinishNotice.none)
        #expect(TurnLogic.finishNotice(chatId: "A", visibleChatId: "B", chatScreenVisible: false, end: .completed, deleted: true) == FinishNotice.none)
    }

    @Test func finishText() {
        #expect(TurnLogic.finishText(title: "시험 공부", ok: true) == "‘시험 공부’ 답변이 끝났어요")
        #expect(TurnLogic.finishText(title: "시험 공부", ok: false) == "‘시험 공부’ 답변을 마치지 못했어요")
        #expect(TurnLogic.finishText(title: nil, ok: true) == "‘새 대화’ 답변이 끝났어요")
        #expect(TurnLogic.finishText(title: " ", ok: true) == "‘새 대화’ 답변이 끝났어요")
    }

    @Test func approvalUiDecision() {
        #expect(TurnLogic.approvalUi([], visibleChatId: "A") == ApprovalUi.none)
        #expect(TurnLogic.approvalUi([(id: "r1", chatId: "A")], visibleChatId: "A") == .dialog(requestId: "r1"))
        #expect(TurnLogic.approvalUi([(id: "r1", chatId: "B")], visibleChatId: "A") == .elsewhere(chatId: "B"))
        #expect(TurnLogic.approvalUi([(id: "r1", chatId: "B"), (id: "r2", chatId: "A")], visibleChatId: "A") == .dialog(requestId: "r2"))
        #expect(TurnLogic.approvalUi([(id: "r1", chatId: nil), (id: "r2", chatId: "B")], visibleChatId: "A") == .dialog(requestId: "r1"))
        #expect(TurnLogic.approvalUi([(id: "r1", chatId: "C"), (id: "r2", chatId: "B")], visibleChatId: "A") == .elsewhere(chatId: "C"))
    }
}
