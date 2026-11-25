import Foundation

/// ChatKit의 파일 위치(새로 만듦, ETLKit `AppPaths`와 같은 규칙). ChatKit은 ETLKit을 가져오지 않으므로 따로 둔다.
/// 앱 Application Support 아래 `Youngmin/`(백업 제외).
public enum ChatPaths {
    public static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var url = base.appendingPathComponent("Youngmin", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }

    /// 하위 폴더(없으면 만든다).
    public static func directory(_ name: String) -> URL {
        let url = root.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// 대화 목록·메시지(안드로이드 `filesDir/chats`).
    public static var chats: URL { directory("chats") }
    /// 대화마다 모델에 다시 보내는 Responses 항목 목록(안드로이드의 Codex 스레드 기록에 해당).
    public static var threads: URL { directory("threads") }
}

/// 대화 하나의 색인 항목(Kotlin `ChatMeta`). 메시지는 `chats/{id}.json`에 따로 있다(`ChatModel` 형식).
public struct ChatMeta: Sendable, Hashable {
    public var id: String
    public var title: String
    /// epoch 밀리초(저장 형식 그대로).
    public var createdAt: Int64
    public var updatedAt: Int64
    /// 이 대화의 스레드(iOS: `ConversationThreadStore`의 항목 목록 ID). 처음 보낼 때 정해진다.
    public var threadId: String?
    /// 사용자가 이름을 직접 바꿨는가(그러면 자동 제목으로 덮지 않는다).
    public var renamed: Bool

    public init(_ id: String, _ title: String, _ createdAt: Int64, _ updatedAt: Int64, threadId: String? = nil, renamed: Bool = false) {
        self.id = id
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.threadId = threadId
        self.renamed = renamed
    }

    public func toJson() -> [String: Any] {
        ["id": id, "title": title, "createdAt": createdAt, "updatedAt": updatedAt,
         "threadId": threadId ?? NSNull(), "renamed": renamed]
    }

    public static func fromJson(_ o: [String: Any]) -> ChatMeta? {
        let id = OrgJSON.optString(o, "id")
        guard !id.isEmpty, ChatStore.isSafeId(id) else { return nil }
        let title = OrgJSON.optString(o, "title")
        let thread: String? = OrgJSON.isNull(o, "threadId") ? nil : OrgJSON.optString(o, "threadId")
        return ChatMeta(
            id, title.isEmpty ? ChatTitles.DEFAULT : title,
            OrgJSON.optLong(o, "createdAt"), OrgJSON.optLong(o, "updatedAt"),
            threadId: (thread?.isEmpty ?? true) ? nil : thread,
            renamed: OrgJSON.optBoolean(o, "renamed", false))
    }
}

/// 대화 제목과 목록 묶음 규칙(Kotlin `ChatTitles`, 순수 함수).
public enum ChatTitles {
    public static let DEFAULT = "새 대화"
    public static let MAX_LEN = 40

    /// 첫 사용자 메시지로 제목을 만든다. 글이 없으면 첨부를 요약한다("사진 1장", "파일: 보고서.pdf").
    public static func from(_ text: String, _ attachments: [Attachment]) -> String {
        let t = kotlinTrim(collapseJavaWhitespace(text))
        if !t.isEmpty { return cut(t) }
        let images = attachments.filter(\.isImage).count
        let files = attachments.filter { !$0.isImage }
        if images > 0 && files.isEmpty { return "사진 \(images)장" }
        if images == 0 && files.count == 1 { return cut("파일: " + files[0].name) }
        if images == 0 && files.count > 1 { return "파일 \(files.count)개" }
        if images > 0 { return "사진 \(images)장, 파일 \(files.count)개" }
        return DEFAULT
    }

    /// 길이(UTF-16)가 `max`를 넘으면 자르고 "…"를 붙인다. 서로게이트 쌍은 쪼개지 않는다.
    public static func cut(_ s: String, max: Int = MAX_LEN) -> String {
        let t = T16.trim(T16.units(s))
        if t.count <= max { return T16.string(t) }
        var end = max - 1
        if T16.isHighSurrogate(t[end - 1]) { end -= 1 }
        return T16.string(T16.trimEnd(Array(t[0..<end]))) + "…"
    }

    public enum Group: Int, Sendable, Hashable, CaseIterable {
        case today, yesterday, week, older
        public var label: String {
            switch self {
            case .today: return "오늘"
            case .yesterday: return "어제"
            case .week: return "지난 7일"
            case .older: return "이전"
            }
        }
    }

    /// `updatedAt`·`now`는 epoch 밀리초.
    public static func groupOf(_ updatedAt: Int64, now: Int64, zone: TimeZone) -> Group {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let today = cal.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000))
        let day = cal.startOfDay(for: Date(timeIntervalSince1970: Double(updatedAt) / 1000))
        let diff = cal.dateComponents([.day], from: day, to: today).day ?? 0
        if diff <= 0 { return .today }
        if diff == 1 { return .yesterday }
        if diff <= 6 { return .week }
        return .older
    }

    /// 최근 순으로 정렬해 날짜 묶음별로 나눈다(빈 묶음은 뺀다).
    public static func grouped(_ list: [ChatMeta], now: Int64, zone: TimeZone) -> [(Group, [ChatMeta])] {
        let sorted = stableSorted(list) { $0.updatedAt > $1.updatedAt }
        var buckets: [Group: [ChatMeta]] = [:]
        for m in sorted { buckets[groupOf(m.updatedAt, now: now, zone: zone), default: []].append(m) }
        return Group.allCases.compactMap { g in buckets[g].map { (g, $0) } }
    }
}

/// 대화 목록 저장소(Kotlin `ChatStore`). `dir/index.json`에 색인, `dir/{id}.json`에 대화마다 메시지를 둔다.
/// 빈 대화(아직 아무것도 보내지 않은 대화)는 색인에 넣지 않는다. 여러 스레드에서 부르므로 잠근다.
public final class ChatStore: @unchecked Sendable {
    private let dir: URL
    private let attachmentRoot: URL?
    private var items: [ChatMeta] = []
    private let lock = NSRecursiveLock()

    public init(_ dir: URL, attachmentRoot: URL? = nil) {
        self.dir = dir
        self.attachmentRoot = attachmentRoot
    }

    public func load() {
        lock.withLock {
            items.removeAll()
            let f = dir.appendingPathComponent(Self.INDEX)
            guard let data = try? Data(contentsOf: f), let arr = OrgJSON.parseArray(data) else { return }
            for case let o as [String: Any] in arr { if let m = ChatMeta.fromJson(o) { items.append(m) } }
        }
    }

    public func list() -> [ChatMeta] { lock.withLock { stableSorted(items) { $0.updatedAt > $1.updatedAt } } }

    public func get(_ id: String) -> ChatMeta? { lock.withLock { items.first { $0.id == id } } }

    public func fileFor(_ id: String) -> URL { dir.appendingPathComponent("\(id).json") }

    public func newId() -> String { UUID().uuidString.lowercased() }

    /// 사용자가 메시지를 보냈다: 색인에 없으면(첫 메시지) 제목을 붙여 넣고, 있으면 수정 시각만 올린다.
    public func noteUserMessage(_ id: String, _ text: String, _ attachments: [Attachment], now: Int64) {
        lock.withLock {
            if let i = items.firstIndex(where: { $0.id == id }) { items[i].updatedAt = now }
            else { items.append(ChatMeta(id, ChatTitles.from(text, attachments), now, now)) }
            save()
        }
    }

    public func setThread(_ id: String, _ threadId: String?) {
        lock.withLock {
            guard let i = items.firstIndex(where: { $0.id == id }) else { return }
            items[i].threadId = threadId
            save()
        }
    }

    public func rename(_ id: String, _ title: String) {
        lock.withLock {
            let t = ChatTitles.cut(collapseJavaWhitespace(title))
            guard let i = items.firstIndex(where: { $0.id == id }), !t.isEmpty else { return }
            items[i].title = t
            items[i].renamed = true
            save()
        }
    }

    /// 색인과 메시지 파일을 지운다. 지운 항목(스레드 정리용)을 돌려준다.
    /// `attachmentRoot`가 있으면 다른 대화가 참조하지 않는 첨부 사본도 지운다.
    @discardableResult
    public func delete(_ id: String) -> ChatMeta? {
        lock.withLock {
            guard let i = items.firstIndex(where: { $0.id == id }) else { return nil }
            let attachments = attachmentRoot.flatMap { _ in WorkFiles.attachments(fileFor(id)) }
            let removed = items.remove(at: i)
            try? FileManager.default.removeItem(at: fileFor(id))
            save()
            if let root = attachmentRoot, let attachments {
                // 저장 실패로 남은 대화도 참조에 포함해 다른 대화의 파일을 잘못 지우지 않는다.
                if let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
                    let others = names.filter { $0.hasSuffix(".json") && $0 != Self.INDEX && $0 != "\(id).json" }
                        .map { dir.appendingPathComponent($0) }
                    WorkFiles.deleteUnshared(root, attachments, others)
                }
            }
            return removed
        }
    }

    /// 예전 형식(대화 하나: `chat.json` + 설정의 threadId)을 첫 대화로 옮긴다. 색인이 비어 있고 예전 파일에 메시지가 있을 때만.
    /// 옮겼으면 새 대화 ID를, 아니면 nil을 돌려준다. 예전 파일은 옮긴 뒤 지운다.
    public func migrateLegacy(_ legacy: URL, _ legacyThreadId: String?, now: Int64) -> String? {
        lock.withLock {
            let fm = FileManager.default
            if !items.isEmpty || !fm.fileExists(atPath: legacy.path) { return nil }
            let probe = ChatModel(legacy)
            probe.load()
            guard !probe.messages.isEmpty, let firstUser = probe.messages.first(where: { $0.role == .user }) else {
                try? fm.removeItem(at: legacy)
                return nil
            }
            let id = newId()
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            do {
                if fm.fileExists(atPath: fileFor(id).path) { try fm.removeItem(at: fileFor(id)) }
                try fm.copyItem(at: legacy, to: fileFor(id))
            } catch {
                return nil
            }
            let modified = (try? fm.attributesOfItem(atPath: legacy.path)[.modificationDate] as? Date).map { Int64($0.timeIntervalSince1970 * 1000) }
            let stamp = (modified ?? 0) > 0 ? modified! : now
            items.append(ChatMeta(id, ChatTitles.from(firstUser.text, firstUser.attachments), stamp, stamp, threadId: legacyThreadId))
            save()
            try? fm.removeItem(at: legacy)
            return id
        }
    }

    private func save() {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        guard let data = OrgJSON.write(items.map { $0.toJson() }) else { return }
        try? data.write(to: dir.appendingPathComponent(Self.INDEX), options: .atomic)
    }

    public static let INDEX = "index.json"

    /// 파일 이름으로 쓸 수 있는 ID인가(색인이 망가져도 다른 경로를 가리키지 않게).
    public static func isSafeId(_ id: String) -> Bool {
        let u = Array(id.utf16)
        guard (1...64).contains(u.count) else { return false }
        return u.allSatisfy { c in
            (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c == 0x2D
        }
    }
}

/// 대화가 참조하는 첨부 사본 찾기·지우기(Kotlin `files/WorkFiles`의 `attachments`·`deleteUnshared`). 내부용.
enum WorkFiles {
    /// 읽기에 실패하면 nil: 다른 대화가 참조하는지 알 수 없으므로 삭제하지 않는다.
    static func attachments(_ chat: URL) -> Set<String>? {
        guard let data = try? Data(contentsOf: chat), let messages = OrgJSON.parseArray(data) else { return nil }
        var out = Set<String>()
        for case let m as [String: Any] in messages {
            guard let files = m["attachments"] as? [Any] else { continue }
            for case let f as [String: Any] in files {
                let p = OrgJSON.optString(f, "path")
                if !p.trimmingCharacters(in: .whitespaces).isEmpty && p != "null" { out.insert(p) }
            }
        }
        return out
    }

    /// 다른 대화에서 참조하지 않는 첨부 사본만 지운다. 경로 탈출과 심볼릭 링크를 따라가지 않는다.
    @discardableResult
    static func deleteUnshared(_ root: URL, _ candidates: Set<String>, _ otherChats: [URL]) -> Int {
        var references = Set<String>()
        for chat in otherChats {
            guard let refs = attachments(chat) else { return 0 }
            references.formUnion(refs)
        }
        let fm = FileManager.default
        let base = root.standardizedFileURL.path
        if isSymlink(base) { return 0 }
        let normalizedRefs = Set(references.map { URL(fileURLWithPath: $0).standardizedFileURL.path })
        var count = 0
        for path in candidates {
            let file = URL(fileURLWithPath: path).standardizedFileURL.path
            guard file.hasPrefix(base + "/"), file != base else { continue }
            var cursor = file
            var linked = false
            while cursor != base && cursor.count > base.count {
                if isSymlink(cursor) { linked = true; break }
                cursor = (cursor as NSString).deletingLastPathComponent
            }
            if linked || normalizedRefs.contains(file) { continue }
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: file, isDirectory: &isDir), !isDir.boolValue else { continue }
            if (try? fm.removeItem(atPath: file)) != nil { count += 1 }
        }
        return count
    }

    private static func isSymlink(_ path: String) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: path) else { return false }
        return (attrs[.type] as? FileAttributeType) == .typeSymbolicLink
    }
}
