import Foundation

/// 비서 도구가 앱 데이터(내 일정·시간표 등)를 바꾸기 전에 화면의 버튼으로만 받는 승인 요청(Kotlin `WriteApprovalGate.Request`).
struct WriteApprovalRequest: Identifiable, Hashable, Sendable {
    let id: String
    /// 제목(도구가 정한 짧은 작업 이름).
    let operation: String
    /// 본문(바뀔 내용 미리보기).
    let details: String
    /// 원본 UI에서는 쓰지 않는다.
    let destructive: Bool

    init(id: String, operation: String, details: String, destructive: Bool = false) {
        self.id = id
        self.operation = operation
        self.details = details
        self.destructive = destructive
    }
}

/// 채팅 화면이 승인 창을 띄우는 데 쓰는 계약(Kotlin `AiWriteApproval` = `WriteApprovalGate`의 화면 쪽 API).
/// AssistantKit의 승인 게이트가 이것을 구현한다. 모델의 "승인했다"는 글은 무시하고, 지금 화면(host)에서 누른 버튼만 한 번의 변경을 승인한다.
///
/// - 채팅 화면이 보이면 `attachHost()`, 사라지면 `detachHost(_:)`(대기 요청은 모두 거절된다).
/// - `pending()`의 첫 요청을 창으로 띄우고, 누른 버튼을 `decide`로 알린다.
/// - 대기 목록이 바뀌면 `setChangeHandler`로 받은 처리기를 부른다(어느 스레드든 괜찮다; 화면이 메인으로 넘긴다).
protocol WriteApprovalSource: AnyObject, Sendable {
    func attachHost() -> String
    func detachHost(_ hostId: String)
    func pending() -> [WriteApprovalRequest]
    @discardableResult
    func decide(hostId: String, requestId: String, approved: Bool) -> Bool
    /// 오류로 턴이 끝나거나 사용자가 멈추면 대기 요청을 모두 거절한다.
    func cancelAll()
    func setChangeHandler(_ handler: (@Sendable () -> Void)?)
}

/// 승인 요청이 생기지 않는 기본값(AssistantKit을 끼우기 전).
final class NoWriteApprovals: WriteApprovalSource, @unchecked Sendable {
    func attachHost() -> String { UUID().uuidString }
    func detachHost(_ hostId: String) {}
    func pending() -> [WriteApprovalRequest] { [] }
    func decide(hostId: String, requestId: String, approved: Bool) -> Bool { false }
    func cancelAll() {}
    func setChangeHandler(_ handler: (@Sendable () -> Void)?) {}
}
