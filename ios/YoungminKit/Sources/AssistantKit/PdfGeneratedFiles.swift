import Foundation
import ETLKit

/// AI가 만든 파일 판정의 순수 부분(Kotlin `files/GeneratedFiles.kt`의 `GeneratedFilesLogic`).
public enum GeneratedFilesLogic {
    /// 사용자 폴더(`Documents/etl/`) 아래 AI가 만든 파일을 두는 하위 폴더.
    public static let SUBDIR = "영민이가 만든 파일"
    /// `Documents` 기준 상대 경로(끝에 `/`). 안드로이드는 `Download/etl/영민이가 만든 파일/`.
    public static let RELATIVE_PATH = "etl/\(SUBDIR)/"

    /// 상대 경로(`etl/영민이가 만든 파일/`)나 표시 경로(`etl/영민이가 만든 파일/요약.pdf`)가 AI가 만든 파일 폴더 안인가.
    /// 앞의 `/`·`./`, 대소문자(`ETL/`)와 하위 폴더는 허용한다.
    public static func isGeneratedPath(_ relativePath: String?) -> Bool {
        guard let relativePath else { return false }
        var p = relativePath.kt.replacingOccurrences(of: "\\", with: "/")
        if p.hasPrefix("./") { p = String(p.dropFirst(2)) }
        while p.hasPrefix("/") { p.removeFirst() }
        let withSlash = p.hasSuffix("/") ? p : p + "/"
        return withSlash.lowercased().hasPrefix(RELATIVE_PATH.lowercased())
    }
}

/// AI(make_pdf)가 만든 파일. 사용자 폴더 `Documents/etl/영민이가 만든 파일/`(파일 앱에 보임)에 저장하고,
/// 만든 파일의 상대 경로를 앱 안에 기록해 둔다(prefs `generated_files`, 키 `uris`, 최대 1000개).
/// 받은 eTL 파일 자동 정리(나중 단계)는 [isGenerated]인 파일을 지우지 않는다.
public final class GeneratedFiles: @unchecked Sendable {
    static let KEY = "uris"
    static let MAX_ENTRIES = 1000

    public struct Saved: Sendable, Hashable {
        /// 저장한 파일.
        public let file: URL
        /// 기록한 경로(`etl/영민이가 만든 파일/<이름>`).
        public let uri: String
        /// 사용자에게 보이는 위치(`<userFolderLabel>/영민이가 만든 파일/<이름>`).
        public let shownPath: String
        public init(file: URL, uri: String, shownPath: String) {
            self.file = file
            self.uri = uri
            self.shownPath = shownPath
        }
    }

    public static let shared = GeneratedFiles()

    private let prefs: KeyValueStore
    private let lock = NSLock()

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "generated_files")) {
        self.prefs = prefs
    }

    /// 기록한 경로들(오래된 것부터).
    public func recorded() -> [String] {
        lock.withLock { load() }
    }

    private func load() -> [String] {
        guard let s = prefs.string(GeneratedFiles.KEY), let arr = try? JSONArray(s) else { return [] }
        return (0..<arr.length()).map { arr.optString($0) }.filter { !$0.isEmpty }
    }

    /// [uri](기록한 상대 경로)를 기록했거나 [relativePath]가 AI 파일 폴더 안이면 true.
    public func isGenerated(_ uri: String?, relativePath: String? = nil) -> Bool {
        GeneratedFilesLogic.isGeneratedPath(relativePath) || (uri.map { u in recorded().contains(u) } ?? false)
    }

    private func register(_ uri: String) {
        lock.withLock {
            var list = load().filter { $0 != uri }
            list.append(uri)
            if list.count > GeneratedFiles.MAX_ENTRIES { list = Array(list.suffix(GeneratedFiles.MAX_ENTRIES)) }
            prefs.set(JSONArray(list).toString(), for: GeneratedFiles.KEY)
        }
    }

    /// [src]를 [name]으로 사용자 폴더 [userDir]`/영민이가 만든 파일/`에 저장한다. 같은 이름이 있으면 `이름 (1).pdf`로 바꾸고,
    /// 마친 뒤의 이름을 표시 경로로 쓴다. 실패하면 [EtlException](반쯤 만든 파일은 지운다).
    public func save(_ src: URL, _ name: String, userDir: URL, folderLabel: String) throws -> Saved {
        let fm = FileManager.default
        let dir = userDir.appendingPathComponent(GeneratedFilesLogic.SUBDIR, isDirectory: true)
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            throw EtlException("파일 폴더를 만들 수 없습니다")
        }
        let dest = lock.withLock { EtlDownloads.uniqueFile(in: dir, name: name) }
        do {
            try fm.copyItem(at: src, to: dest)
        } catch {
            try? fm.removeItem(at: dest)
            throw EtlException("폰에 저장하지 못했습니다(\(String(describing: type(of: error))))")
        }
        let uri = GeneratedFilesLogic.RELATIVE_PATH + dest.lastPathComponent
        register(uri)
        return Saved(file: dest, uri: uri, shownPath: "\(folderLabel)/\(GeneratedFilesLogic.SUBDIR)/\(dest.lastPathComponent)")
    }
}
