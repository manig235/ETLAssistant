import Foundation
import CryptoKit
import UniformTypeIdentifiers

/// eTL 파일 받기 규칙(Kotlin `EtlTools.saveAttachment`의 파일 부분과 `EtlFiles`의 이름 규칙).
///
/// - 비서가 읽는 사본: 앱 전용 `AppPaths.directory("etl-files")/{파일ID}-{이름}` (같은 파일이면 덮어쓴다).
/// - 사용자가 요청한 사본: 앱 `Documents/etl/` (파일 앱에 보인다). 안드로이드의 폰 `Download/etl/`에 해당.
/// - 잠긴 파일(`locked_for_user`)은 받지 않는다. 300MB 상한, 메타의 size와 받은 크기를 비교한다.
/// - 동영상·숨김 파일 판정과 채팅 알림([EtlEvents.fileSaved])은 호출하는 쪽(비서 도구)이 한다.
public enum EtlDownloads {
    public static let DIR = "etl-files"
    /// 강의계획서 첨부(비서만 읽는 파일) 하위 폴더.
    public static let SYLLABUS_DIR = "syllabus"
    /// 작업 폴더에 둔 파일은 이 기간이 지나면 정리한다.
    public static let KEEP_DAYS = 14

    /// 비서용 작업 폴더(`Application Support/Youngmin/etl-files`).
    public static var workspaceDirectory: URL { AppPaths.directory(DIR) }

    /// 비서만 읽는 강의계획서 첨부 폴더(`etl-files/syllabus`). 채팅에 미리보기·파일 카드를 띄우지 않는다.
    public static var syllabusDirectory: URL {
        let url = workspaceDirectory.appendingPathComponent(SYLLABUS_DIR, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// 사용자용 폴더(`Documents/etl`, 파일 앱에서 보인다).
    public static var userDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = docs.appendingPathComponent("etl", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// 작업 폴더에 저장할 이름: `{파일ID}-{정리된 이름}`. UTF-8 [maxBytes] 바이트 안으로 줄인다.
    public static func storedName(_ fileId: Int64, _ safeName: String, maxBytes: Int = 200) -> String {
        fitName("\(fileId)-\(safeName)", maxBytes: maxBytes)
    }

    /// [full]을 확장자를 지키며 UTF-8 [maxBytes] 바이트 안으로 줄인다.
    public static func fitName(_ full: String, maxBytes: Int = 200) -> String {
        if full.utf8.count <= maxBytes { return full }
        let dot = full.lastIndex(of: ".")
        var ext = ""
        var base = full
        if let dot, dot > full.startIndex, full.utf16.count - full[..<dot].utf16.count <= 10 {
            ext = String(full[dot...])
            base = String(full[..<dot])
        }
        let budget = maxBytes - ext.utf8.count
        while !base.isEmpty && base.utf8.count > budget { base.removeLast() }
        return base + ext
    }

    /// [dir] 안에서 [maxAgeMs]보다 오래된 파일을 지운다(하위 폴더 포함). 지운 개수.
    @discardableResult
    public static func prune(_ dir: URL, nowMs: Int64, maxAgeMs: Int64 = Int64(KEEP_DAYS) * 24 * 3600 * 1000) -> Int {
        var n = 0
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey, .contentModificationDateKey]) else { return 0 }
        for f in items {
            let v = try? f.resourceValues(forKeys: [.isDirectoryKey, .contentModificationDateKey])
            if v?.isDirectory == true {
                n += prune(f, nowMs: nowMs, maxAgeMs: maxAgeMs)
            } else if let m = v?.contentModificationDate, nowMs - m.epochMilli > maxAgeMs, (try? fm.removeItem(at: f)) != nil {
                n += 1
            }
        }
        return n
    }

    /// 이름의 확장자로 MIME 형식(모르면 `application/octet-stream`).
    public static func mimeType(forName name: String) -> String {
        let ext = name.contains(".") ? name.substringAfterLast(".").lowercased() : ""
        return UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
    }

    /// [dir] 안에서 겹치지 않는 이름(`이름 (1).pdf` …).
    public static func uniqueFile(in dir: URL, name: String) -> URL {
        let fm = FileManager.default
        var url = dir.appendingPathComponent(name)
        if !fm.fileExists(atPath: url.path) { return url }
        let ext = (name as NSString).pathExtension
        let stem = (name as NSString).deletingPathExtension
        var i = 1
        repeat {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)")
            i += 1
        } while fm.fileExists(atPath: url.path)
        return url
    }

    /// 파일의 SHA-256(16진수).
    public static func sha256(_ file: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: file)
        defer { try? h.close() }
        var hasher = SHA256()
        while let chunk = try h.read(upToCount: 1 << 16), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// 받은 파일 하나.
    public struct Saved: Sendable, Hashable {
        public let id: Int64
        /// eTL의 표시 이름(없으면 정리된 이름).
        public let displayName: String
        /// 정리된 파일 이름([EtlLogic.safeName]).
        public let name: String
        public let mime: String
        /// 비서용 작업 폴더 사본.
        public let file: URL
        /// `Documents/etl/` 사본(요청했고 성공했을 때).
        public let userFile: URL?
        /// 사용자 사본 저장 실패 문구.
        public let userError: String?
        public let bytes: Int64
        public let sha256: String
        /// 이미 받아 둔 같은 파일을 다시 썼는가.
        public let reused: Bool
        public let expectedBytes: Int64?
        public var sizeOk: Bool { expectedBytes == nil || expectedBytes == bytes }

        public init(id: Int64, displayName: String, name: String, mime: String, file: URL, userFile: URL?, userError: String?,
                    bytes: Int64, sha256: String, reused: Bool, expectedBytes: Int64?) {
            self.id = id
            self.displayName = displayName
            self.name = name
            self.mime = mime
            self.file = file
            self.userFile = userFile
            self.userError = userError
            self.bytes = bytes
            self.sha256 = sha256
            self.reused = reused
            self.expectedBytes = expectedBytes
        }
    }

    /// 파일 메타([meta], `/api/v1/files/<id>` 응답)의 파일을 받는다. 먼저 작업 폴더에 저장하고,
    /// [saveToUser]면 `Documents/etl/`에도 사본을 둔다. 잠긴 파일이거나 URL이 없으면 [EtlException].
    public static func saveAttachment(_ client: EtlClient, meta: JSONObject, saveToUser: Bool = true,
                                      workspace: URL? = nil, userDir: URL? = nil, now: Date = Date()) async throws -> Saved {
        let fid = meta.optLong("id")
        if meta.optBoolean("locked_for_user", false) {
            throw EtlException("파일 \(fid) 은(는) 잠겨 있습니다: \(meta.str("lock_explanation") ?? "아직 열람할 수 없음")")
        }
        guard let url = meta.str("url") else { throw EtlException("파일 \(fid) 에 다운로드 URL이 없습니다") }
        let name = EtlLogic.safeName(meta.str("display_name") ?? meta.str("filename"), "file-\(fid)")
        let mime = meta.str("content-type") ?? mimeType(forName: name)

        let fm = FileManager.default
        let dir = workspace ?? workspaceDirectory
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        prune(dir, nowMs: now.epochMilli)
        let stored = dir.appendingPathComponent(storedName(fid, name))
        let expectedSize: Int64? = (meta.has("size") && !meta.isNull("size")) ? meta.optLong("size") : nil
        let updated = EtlLogic.parseInstant(meta.str("updated_at"))
        // 이미 받아 둔 파일이 그대로면(크기 같고 eTL에서 그 뒤로 바뀌지 않음) 다시 받지 않는다.
        let attrs = try? fm.attributesOfItem(atPath: stored.path)
        let storedSize = (attrs?[.size] as? NSNumber)?.int64Value
        let storedMtime = attrs?[.modificationDate] as? Date
        let reused = attrs != nil && expectedSize != nil && storedSize == expectedSize && updated != nil && storedMtime != nil && storedMtime! >= updated!
        let result: EtlClient.Download
        if reused {
            result = EtlClient.Download(bytes: storedSize ?? 0, sha256: try sha256(stored))
        } else {
            let tmp = dir.appendingPathComponent(stored.lastPathComponent + ".part")
            do {
                result = try await client.download(url, to: tmp)
            } catch {
                try? fm.removeItem(at: tmp)
                throw error
            }
            try? fm.removeItem(at: stored)
            try fm.moveItem(at: tmp, to: stored)
        }

        var userFile: URL?
        var userError: String?
        if saveToUser {
            do {
                let udir = userDir ?? userDirectory
                try fm.createDirectory(at: udir, withIntermediateDirectories: true)
                let target = uniqueFile(in: udir, name: name)
                try fm.copyItem(at: stored, to: target)
                userFile = target
            } catch {
                userError = "기기 사본을 저장하지 못했습니다(\(EtlClient.errorName(error))). 작업 폴더 사본은 있습니다"
            }
        }
        return Saved(id: fid, displayName: meta.str("display_name") ?? name, name: name, mime: mime, file: stored, userFile: userFile,
                     userError: userError, bytes: result.bytes, sha256: result.sha256, reused: reused, expectedBytes: expectedSize)
    }
}
