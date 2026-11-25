import Foundation

/// 다른 앱에서 "공유 → 영민이"로 받은 내용의 순수 해석(Kotlin `chat/ShareReceiverActivity.kt`의 `ShareIntentLogic`).
/// iOS는 `content://` 주소 대신 공유 확장이 받은 파일을 App Group 폴더로 복사하므로 파일 쪽 규칙(주소 거르기)은 없다.
enum ShareIntentLogic {
    static let MAX_TEXT_CHARS = 4000

    /// 입력창에 채울 글. 제목(메일 제목·페이지 제목)이 본문에 없으면 앞줄에 붙이고 길이를 자른다.
    /// 파일과 함께 온 제목만 있는 경우(메일 앱 등)는 입력창에 넣지 않는다. 비면 nil.
    static func combinedText(text: String?, subject: String?, hasFiles: Bool) -> String? {
        let body = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let subj = (subject ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let combined: String
        if !subj.isEmpty && !body.isEmpty && !body.contains(subj) {
            combined = "\(subj)\n\(body)"
        } else if !body.isEmpty {
            combined = body
        } else if !subj.isEmpty && !hasFiles {
            combined = subj
        } else {
            combined = ""
        }
        let cut = take16(combined, MAX_TEXT_CHARS)
        return cut.isEmpty ? nil : cut
    }

    /// Kotlin `String.take(n)`(UTF-16 단위). 이어진 글자(서로게이트 쌍)를 반으로 자르지 않는다.
    static func take16(_ s: String, _ n: Int) -> String {
        if s.utf16.count <= n { return s }
        var out = ""
        var used = 0
        for ch in s {
            let w = String(ch).utf16.count
            if used + w > n { break }
            out.append(ch)
            used += w
        }
        return out
    }
}

/// 공유 확장이 App Group에 남기는 받은 내용 한 건(Kotlin `ShareInbox.Shared`). 앱이 활성화되면 새 대화의 입력창·첨부로 옮긴다.
/// 확장은 파일을 `share/{id}/` 폴더에 먼저 복사하고 마지막에 `share.json`을 쓴다(앱이 반쯤 쓴 것을 읽지 않게).
struct PendingShare: Codable, Equatable {
    struct File: Codable, Equatable {
        /// 보일 이름(원래 파일 이름).
        var name: String
        var mime: String
        /// `share/{id}/` 안의 파일 이름.
        var stored: String
    }

    var id: String
    var createdAt: Date
    var text: String?
    var files: [File]
    /// 가져오지 못한 파일의 사유(토스트로 보인다).
    var failures: [String]
    /// 한 번에 첨부할 수 있는 개수를 넘어 버린 수.
    var dropped: Int

    /// 이보다 오래된 공유는 버린다(앱을 한참 뒤에 열었을 때 갑자기 채워지지 않게).
    static let maxAge: TimeInterval = 30 * 60
    static let manifest = "share.json"

    static var root: URL? { AppGroup.directory("share") }

    static func folder(_ id: String) -> URL? { root?.appendingPathComponent(id, isDirectory: true) }

    func write() throws {
        guard let dir = PendingShare.folder(id) else { throw CocoaError(.fileNoSuchFile) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        try e.encode(self).write(to: dir.appendingPathComponent(PendingShare.manifest), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func read(_ id: String) -> PendingShare? {
        guard let dir = folder(id), let data = try? Data(contentsOf: dir.appendingPathComponent(manifest)) else { return nil }
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return try? d.decode(PendingShare.self, from: data)
    }

    /// 다 쓴 공유(오래된 순). 쓰는 중인 폴더(아직 share.json 없음)는 건너뛰고, 오래된 것과 깨진 것은 지운다.
    static func pending(now: Date = Date()) -> [PendingShare] {
        guard let root, let dirs = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.creationDateKey]) else { return [] }
        var out: [PendingShare] = []
        for dir in dirs {
            let id = dir.lastPathComponent
            if let s = read(id) {
                if now.timeIntervalSince(s.createdAt) > maxAge { remove(id) } else { out.append(s) }
            } else {
                let created = (try? dir.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? now
                if now.timeIntervalSince(created) > maxAge { remove(id) }
            }
        }
        return out.sorted { $0.createdAt < $1.createdAt }
    }

    func fileURL(_ f: File) -> URL? { PendingShare.folder(id)?.appendingPathComponent(f.stored) }

    static func remove(_ id: String) {
        if let dir = folder(id) { try? FileManager.default.removeItem(at: dir) }
    }
}
