import Foundation

/// 답변이 끝났을 때 사용자에게 어떻게 알릴지(Kotlin `FinishNotice`).
public enum FinishNotice: Sendable, Equatable {
    /// 알리지 않는다(지금 보는 대화, 직접 멈춤, 지운 대화).
    case none
    /// 채팅 화면 아래 막대 "‘…’ 답변이 끝났어요 · 보기".
    case bar
    /// 채팅 화면이 보이지 않음(다른 앱, 설정 화면 등): 기기 알림(누르면 그 대화).
    case notification
}

/// 턴이 끝난 모양(Kotlin `TurnEnd`).
public enum TurnEnd: Sendable, Equatable {
    case completed, failed, interrupted
}

/// 승인 요청을 이 화면에서 어떻게 보일지(Kotlin `TurnLogic.ApprovalUi`).
public enum ApprovalUi: Sendable, Equatable {
    case none
    /// 지금 대화의 요청: 확인 창을 띄운다.
    case dialog(requestId: String)
    /// 다른 대화의 요청만 있다: 위쪽 배너 "다른 대화에서 확인을 기다려요"(누르면 그 대화로).
    case elsewhere(chatId: String)
}

/// 대화마다 따로 답하기(2026-11-27)의 판정(Kotlin `TurnLogic`). 대화 A가 답하는 동안 다른 대화를 열고 보낼 수 있고,
/// 동시에 도는 답변 수에는 제한이 없다.
public enum TurnLogic {
    public static let APPROVAL_ELSEWHERE = "다른 대화에서 확인을 기다려요"

    /// - 직접 멈췄거나(interrupted) 지운 대화면 알리지 않는다.
    /// - 채팅 화면이 보이지 않으면 보던 대화였어도 기기 알림으로 알린다.
    /// - 채팅 화면이 보이면 지금 보는 대화는 알리지 않고, 다른 대화면 아래 막대로 알린다.
    public static func finishNotice(chatId: String, visibleChatId: String, chatScreenVisible: Bool, end: TurnEnd, deleted: Bool) -> FinishNotice {
        if deleted || end == .interrupted { return .none }
        if !chatScreenVisible { return .notification }
        return chatId == visibleChatId ? .none : .bar
    }

    public static func finishText(title: String?, ok: Bool) -> String {
        let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let name = trimmed.isEmpty ? "새 대화" : title!
        return ok ? "‘\(name)’ 답변이 끝났어요" : "‘\(name)’ 답변을 마치지 못했어요"
    }

    /// [pending]은 (요청 ID, 그 요청을 낸 대화) 들어온 순서. 대화를 모르는 요청(nil)은 예전처럼 지금 대화에서 묻는다.
    /// 지금 대화의 요청이 먼저다. 다른 대화로 옮겨 간다고 요청이 취소되지는 않는다(취소는 화면을 떠날 때, 턴이 끝날 때, 90초).
    public static func approvalUi(_ pending: [(id: String, chatId: String?)], visibleChatId: String) -> ApprovalUi {
        if let here = pending.first(where: { $0.chatId == nil || $0.chatId == visibleChatId }) { return .dialog(requestId: here.id) }
        if let other = pending.first(where: { $0.chatId != nil }), let chat = other.chatId { return .elsewhere(chatId: chat) }
        return .none
    }
}
