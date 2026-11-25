import Foundation

/// 스레드 ID 규칙(Kotlin `codex.ThreadPurge`). Responses 파일은 ConversationThreadStore가,
/// 네이티브 Codex 기록은 엔진의 thread/delete가 지운다.
public enum ThreadPurge {
    public static func isThreadId(_ s: String) -> Bool {
        let parts = s.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.map(\.count) == [8, 4, 4, 4, 12] else { return false }
        return parts.allSatisfy { $0.allSatisfy(\.isHexDigit) && $0.allSatisfy(\.isASCII) }
    }
}

/// Responses 항목 또는 네이티브 Codex 스레드 참조를 `threads/{threadId}.json`에 보관한다.
/// Responses 모드에서는 대화 전문과 도구 결과가, Codex 모드에서는 엔진의 thread ID가 들어 있다.
///
/// 대화를 지우면(`ChatStore.delete`가 돌려준 `threadId`) 이 파일도 바로 지운다. 지우기에 실패하면
/// `ChatSettings.pendingThreadDeletes`에 남겨 다음 시작 때 `purgePending`으로 다시 지운다(안드로이드 규칙과 같음).
public final class ConversationThreadStore: @unchecked Sendable {
    private let dir: URL
    private let lock = NSLock()

    public init(_ dir: URL) { self.dir = dir }

    public func newThreadId() -> String { UUID().uuidString.lowercased() }

    func fileFor(_ threadId: String) -> URL { dir.appendingPathComponent("\(threadId.lowercased()).json") }

    /// 저장된 항목 목록. 없거나 망가졌거나 잘못된 ID면 빈 목록.
    public func load(_ threadId: String) -> [ChatJSON] {
        guard ThreadPurge.isThreadId(threadId) else { return [] }
        return lock.withLock {
            guard let data = try? Data(contentsOf: fileFor(threadId)), let json = try? ChatJSON(parsing: data) else { return [] }
            return json.array ?? []
        }
    }

    public func save(_ threadId: String, _ items: [ChatJSON]) {
        guard ThreadPurge.isThreadId(threadId) else { return }
        lock.withLock {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? ChatJSON.array(items).serialized().write(to: fileFor(threadId), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// 지웠거나 원래 없으면 true.
    @discardableResult
    public func delete(_ threadId: String) -> Bool {
        guard ThreadPurge.isThreadId(threadId) else { return true }
        return lock.withLock {
            let f = fileFor(threadId)
            if !FileManager.default.fileExists(atPath: f.path) { return true }
            return (try? FileManager.default.removeItem(at: f)) != nil
        }
    }

    /// 대화 목록에 없는 스레드 파일(앱이 대화를 지우다 멈춘 경우 등).
    public func orphans(keep: Set<String>) -> Set<String> {
        let k = Set(keep.map { $0.lowercased() })
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return Set(names.compactMap { n -> String? in
            guard n.hasSuffix(".json") else { return nil }
            let id = String(n.dropLast(5))
            return ThreadPurge.isThreadId(id) && !k.contains(id) ? id : nil
        })
    }

    /// 남은 삭제를 처리하고 아직 못 지운 것을 돌려준다(`ChatSettings.pendingThreadDeletes`에 다시 넣는다).
    public func purgePending(_ pending: Set<String>) -> Set<String> {
        Set(pending.filter { !delete($0) })
    }
}
