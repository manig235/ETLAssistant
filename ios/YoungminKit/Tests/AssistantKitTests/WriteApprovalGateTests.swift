import Foundation
import Testing
import ETLKit
@testable import AssistantKit

/// Kotlin `WriteApprovalGateTest` 이식 + Task 취소.
@Suite struct WriteApprovalGateTests {
    typealias Denied = WriteApprovalGate.Denied

    /// 거절이면 true, 실행됐으면 false.
    private func deniedCall(_ gate: WriteApprovalGate, _ op: String, _ details: String, destructive: Bool = false, _ writes: Counter) -> Task<Bool, Never> {
        Task {
            do {
                _ = try await gate.execute(op, details, destructive: destructive) { writes.inc() }
                return false
            } catch {
                return error is Denied
            }
        }
    }

    @Test func noForegroundHostCannotWrite() async {
        let writes = Counter()
        let gate = WriteApprovalGate()
        await #expect(throws: Denied.self) { try await gate.execute("delete", "a") { writes.inc() } }
        #expect(writes.value == 0)
    }

    @Test func approvalIsBoundToOneCallAndCannotBeReplayed() async throws {
        let gate = WriteApprovalGate()
        let host = gate.attachHost()
        let requests = RequestObserver(gate)
        let writes = Counter()
        defer { gate.cancelAll() }
        let first = Task { try await gate.execute("add", "A, 학교, 2026-11-25 09:00") { writes.inc() } }
        let a = try #require(await requests.next())
        #expect(writes.value == 0)
        #expect(a.details == "A, 학교, 2026-11-25 09:00")
        requests.poke() // 반복된 변경 알림이 새 호출처럼 보이면 안 된다.
        #expect(!gate.decide(hostId: "wrong host", requestId: a.id, approved: true))
        #expect(!gate.decide(hostId: host, requestId: "made up token", approved: true))
        #expect(gate.decide(hostId: host, requestId: a.id, approved: true))
        #expect(try await first.value == 1)
        #expect(!gate.decide(hostId: host, requestId: a.id, approved: true))
        let second = deniedCall(gate, "delete", "B", destructive: true, writes)
        let b = try #require(await requests.next())
        #expect(a.id != b.id)
        #expect(b.destructive)
        #expect(!gate.decide(hostId: host, requestId: a.id, approved: true))
        #expect(gate.decide(hostId: host, requestId: b.id, approved: false))
        #expect(await second.value)
        #expect(writes.value == 1)
    }

    @Test func detachAndTurnCancellationReleaseWaitersWithoutWriting() async throws {
        for detach in [false, true] {
            let gate = WriteApprovalGate()
            let host = gate.attachHost()
            let requests = RequestObserver(gate)
            let writes = Counter()
            let result = deniedCall(gate, "update", "resolved write", writes)
            let request = try #require(await requests.next())
            if detach { gate.detachHost(host) } else { gate.cancelAll() }
            #expect(await result.value)
            #expect(!gate.decide(hostId: host, requestId: request.id, approved: true))
            #expect(writes.value == 0)
            #expect(gate.pending().isEmpty)
        }
    }

    @Test func replacementHostRejectsPendingRequestFromPreviousChat() async throws {
        let gate = WriteApprovalGate()
        let oldHost = gate.attachHost()
        let requests = RequestObserver(gate)
        let writes = Counter()
        defer { gate.cancelAll() }
        let oldCall = deniedCall(gate, "delete", "old chat", writes)
        let oldRequest = try #require(await requests.next())
        let newHost = gate.attachHost()
        #expect(await oldCall.value)
        #expect(!gate.decide(hostId: oldHost, requestId: oldRequest.id, approved: true))
        #expect(!gate.decide(hostId: newHost, requestId: oldRequest.id, approved: true))
        gate.detachHost(oldHost)
        let newCall = Task { try await gate.execute("add", "new chat") { writes.inc() } }
        let newRequest = try #require(await requests.next())
        #expect(oldRequest.id != newRequest.id)
        #expect(gate.decide(hostId: newHost, requestId: newRequest.id, approved: true))
        #expect(try await newCall.value == 1)
    }

    @Test func timeoutDeniesAndNewHostInvalidatesOldDialog() async throws {
        let gate = WriteApprovalGate(timeout: 0.04)
        let oldHost = gate.attachHost()
        let writes = Counter()
        await #expect(throws: Denied.self) { try await gate.execute("add", "never answered") { writes.inc() } }
        #expect(writes.value == 0)
        #expect(gate.pending().isEmpty)
        let activeGate = WriteApprovalGate()
        let newHost = activeGate.attachHost()
        activeGate.detachHost(oldHost) // 옛 화면이 새 화면을 떼어 내면 안 된다.
        let requests = RequestObserver(activeGate)
        defer { activeGate.cancelAll() }
        let result = Task { try await activeGate.execute("add", "new chat") { writes.inc() } }
        let request = try #require(await requests.next())
        #expect(!activeGate.decide(hostId: oldHost, requestId: request.id, approved: true))
        #expect(activeGate.decide(hostId: newHost, requestId: request.id, approved: true))
        #expect(try await result.value == 1)
    }

    /// 사용자가 멈춰 도구를 기다리던 Task가 취소되면 거절된다(안드로이드의 스레드 인터럽트 자리).
    @Test func cancelledTaskIsDenied() async throws {
        let gate = WriteApprovalGate()
        let host = gate.attachHost()
        let requests = RequestObserver(gate)
        let writes = Counter()
        let call = deniedCall(gate, "add", "stopped", writes)
        let request = try #require(await requests.next())
        call.cancel()
        #expect(await call.value)
        #expect(!gate.decide(hostId: host, requestId: request.id, approved: true))
        #expect(writes.value == 0)
        #expect(gate.pending().isEmpty)
    }

    /// 대화마다 따로 답하기(2026-11-27): 요청은 그 도구를 부른 대화에 묶이고, 턴 경계는 그 대화의 요청만 거절한다.
    @Test func turnBoundaryCancelsOnlyThatConversationsReview() async throws {
        let gate = WriteApprovalGate()
        let host = gate.attachHost()
        let requests = RequestObserver(gate)
        let writes = Counter()
        defer { gate.cancelAll() }
        let a = Task { () -> Bool in
            do {
                _ = try await ToolOrigin.$chatId.withValue("chat-a") { try await gate.execute("add", "A") { writes.inc() } }
                return false
            } catch { return error is Denied }
        }
        let ra = try #require(await requests.next())
        let b = Task { try await ToolOrigin.$chatId.withValue("chat-b") { try await gate.execute("add", "B") { writes.inc() } } }
        let rb = try #require(await requests.next())
        #expect(ra.origin == "chat-a")
        #expect(rb.origin == "chat-b")
        gate.cancel(origin: "chat-a")
        #expect(await a.value)
        #expect(gate.pending().map(\.id) == [rb.id])
        #expect(!gate.decide(hostId: host, requestId: ra.id, approved: true))
        #expect(gate.decide(hostId: host, requestId: rb.id, approved: true))
        #expect(try await b.value == 1)
        #expect(writes.value == 1)
    }

    @Test func unknownOriginIsCancelledByAnyTurnBoundary() async throws {
        let gate = WriteApprovalGate()
        _ = gate.attachHost()
        let requests = RequestObserver(gate)
        let writes = Counter()
        defer { gate.cancelAll() }
        let call = deniedCall(gate, "add", "?", writes)
        let r = try #require(await requests.next())
        #expect(r.origin == nil)
        gate.cancel(origin: "chat-x")
        #expect(await call.value)
        #expect(writes.value == 0)
    }
}
