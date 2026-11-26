import Foundation
import Testing
@testable import ETLKit

/// 도구가 알리는 일이 그 도구를 부른 대화에 붙는지(대화마다 따로 답하기, 2026-11-27).
@Suite struct ToolOriginTests {
    private final class Seen: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [(String, String?)] = []
        func add(_ text: String, _ origin: String?) { lock.withLock { items.append((text, origin)) } }
        var all: [(String, String?)] { lock.withLock { items } }
    }

    @Test func subscriberReadsCallingConversation() async {
        let seen = Seen()
        let sub = EtlEvents.subscribe { e in
            if case .notice(let t) = e, t.hasPrefix("tool-origin-test") { seen.add(t, ToolOrigin.chatId) }
        }
        defer { sub.cancel() }
        // 두 대화의 도구가 동시에 돌아도 각자의 대화로 읽힌다.
        await withTaskGroup(of: Void.self) { group in
            for chat in ["chat-a", "chat-b"] {
                group.addTask {
                    await ToolOrigin.$chatId.withValue(chat) {
                        await Task.yield()
                        EtlEvents.notice("tool-origin-test \(chat)")
                    }
                }
            }
        }
        EtlEvents.notice("tool-origin-test none")
        let all = seen.all
        #expect(all.count == 3)
        for (text, origin) in all {
            if text.hasSuffix("chat-a") { #expect(origin == "chat-a") }
            if text.hasSuffix("chat-b") { #expect(origin == "chat-b") }
            if text.hasSuffix("none") { #expect(origin == nil) }
        }
    }
}
