import Foundation

/// 비서가 읽을 수 있게 앱 전용 작업 폴더(`etl-files/`)에 두는 파일들의 규칙(Kotlin `EtlFiles`).
///
/// iOS에서는 도구 상자의 `workspace`가 **`etl-files` 폴더 자체**다(안드로이드는 그 부모). 모델이 보는 상대 경로는
/// 안드로이드와 같은 `etl-files/<이름>` 꼴이고, [resolveInside]가 이것을 `workspace/<이름>`으로 바꾼다.
/// 사용자용 사본(`Documents/etl`, 파일 앱)은 [EtlDownloads.userDirectory]다.
public enum EtlFiles {
    public static let DIR = EtlDownloads.DIR
    public static let RENDER_DIR = "render"
    /// 강의계획서 첨부를 두는 하위 폴더. 비서가 정보를 얻으려고만 읽는 파일이라 채팅에 쪽 미리보기나 파일 카드를 띄우지 않는다([isPrivateRead]).
    public static let SYLLABUS_DIR = EtlDownloads.SYLLABUS_DIR
    public static let MAX_RENDER_PAGES = 6
    public static let DEFAULT_TEXT_CHARS = 20_000
    public static let MAX_TEXT_CHARS = 60_000
    /// 작업 폴더에 둔 파일은 이 기간이 지나면 정리한다.
    public static let KEEP_DAYS = EtlDownloads.KEEP_DAYS

    public static let TEXT_EXT: Set<String> = [
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "tex", "yaml", "yml", "ini",
        "c", "h", "cpp", "hpp", "cc", "java", "kt", "py", "m", "js", "ts", "sh", "sql", "r", "v", "sv", "asm",
    ]

    /// Kotlin `name.substringAfterLast('.', "").lowercase()`.
    public static func ext(_ name: String) -> String {
        guard let i = name.lastIndex(of: ".") else { return "" }
        return String(name[name.index(after: i)...]).lowercased()
    }

    public static func isTextLike(_ name: String) -> Bool { TEXT_EXT.contains(ext(name)) }

    public static func isPdf(_ name: String) -> Bool { ext(name) == "pdf" }

    /// 글을 뽑아 읽을 수 있는 Office 문서(docx, pptx). 강의계획서 첨부가 docx인 경우가 많다.
    public static func isOoxml(_ name: String) -> Bool { ["docx", "pptx"].contains(ext(name)) }

    public static func storedName(_ fileId: Int64, _ safeName: String, maxBytes: Int = 200) -> String {
        EtlDownloads.storedName(fileId, safeName, maxBytes: maxBytes)
    }

    public static func fitName(_ full: String, maxBytes: Int = 200) -> String { EtlDownloads.fitName(full, maxBytes: maxBytes) }

    @discardableResult
    public static func prune(_ dir: URL, nowMs: Int64, maxAgeMs: Int64 = Int64(KEEP_DAYS) * 24 * 3600 * 1000) -> Int {
        EtlDownloads.prune(dir, nowMs: nowMs, maxAgeMs: maxAgeMs)
    }

    // MARK: 경로

    /// 실제 경로(심볼릭 링크·`..`를 푼 것). 없는 파일이면 있는 조상까지 풀고 나머지는 글자대로 붙인다(Java `canonicalFile`).
    static func canonicalPath(_ path: String) -> String {
        if let r = realPath(path) { return r }
        let std = URL(fileURLWithPath: path).standardizedFileURL.path
        var head = std
        var tail: [String] = []
        while head != "/" && !head.isEmpty {
            if let r = realPath(head) {
                return tail.isEmpty ? r : (r == "/" ? "" : r) + "/" + tail.reversed().joined(separator: "/")
            }
            tail.append((head as NSString).lastPathComponent)
            head = (head as NSString).deletingLastPathComponent
        }
        return std
    }

    private static func realPath(_ path: String) -> String? {
        guard let p = realpath(path, nil) else { return nil }
        defer { free(p) }
        return String(cString: p)
    }

    /// 작업 폴더 기준 상대 경로 [rel](`etl-files/…`)을 [workspace](`etl-files` 폴더) 안의 파일로 바꾼다.
    /// 절대 경로, `..`, 심볼릭 링크로 밖을 가리키면 [EtlException]. 파일이 없어도 예외.
    public static func resolveInside(_ workspace: URL, _ rel: String) throws -> URL {
        let root = canonicalPath(workspace.path)
        var cleaned = rel.kTrim
        if cleaned.hasPrefix("./") { cleaned = String(cleaned.dropFirst(2)) }
        if cleaned.isEmpty || cleaned.hasPrefix("/") || cleaned.contains("\u{0}") { throw EtlException("허용되지 않은 경로입니다") }
        // 모델이 보는 경로의 첫 칸 `etl-files`는 작업 폴더 자체다. 다른 첫 칸은 작업 폴더의 부모 기준(=밖)으로 본다.
        let joined: String
        if cleaned == DIR {
            joined = workspace.path
        } else if cleaned.hasPrefix(DIR + "/") {
            joined = workspace.path + "/" + cleaned.dropFirst(DIR.count + 1)
        } else {
            joined = workspace.deletingLastPathComponent().path + "/" + cleaned
        }
        let full = canonicalPath(joined)
        guard full.hasPrefix(root + "/") else { throw EtlException("etl-files/ 안의 파일만 열 수 있습니다") }
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: full, isDirectory: &isDir), !isDir.boolValue else {
            throw EtlException("파일이 없습니다: \(cleaned)")
        }
        return URL(fileURLWithPath: full)
    }

    /// 비서만 읽고 사용자에게는 보여 주지 않는 파일인가(강의계획서 첨부). `etl-files/syllabus/` 아래이거나,
    /// 이전 빌드가 `etl-files/` 바로 아래에 둔 `syllabus-…` 파일.
    public static func isPrivateRead(_ workspace: URL, _ f: URL) -> Bool {
        let root = canonicalPath(workspace.path)
        let c = canonicalPath(f.path)
        let parent = (c as NSString).deletingLastPathComponent
        return c.hasPrefix(root + "/" + SYLLABUS_DIR + "/") || (parent == root && (c as NSString).lastPathComponent.hasPrefix("syllabus-"))
    }

    /// 작업 폴더 기준 상대 경로 표기(`etl-files/…`).
    public static func relPath(_ workspace: URL, _ f: URL) -> String {
        let root = canonicalPath(workspace.path)
        let c = canonicalPath(f.path)
        let rest = c.hasPrefix(root + "/") ? String(c.dropFirst(root.count + 1)) : f.lastPathComponent
        return "\(DIR)/\(rest)"
    }

    // MARK: 쪽 지정

    private static let PAGE_PART = KRegex("^(\\d+)(?:\\s*-\\s*(\\d+))?$")

    /// "1-3,5" 같은 쪽 지정(1부터)을 쪽 번호 목록으로. 범위를 벗어난 값은 버리고, 중복을 없애 오름차순으로 [max]개까지.
    /// 비어 있거나 해석할 수 없으면 앞쪽부터 [max]개.
    public static func parsePages(_ spec: String?, _ total: Int, max: Int = MAX_RENDER_PAGES) -> [Int] {
        if total <= 0 { return [] }
        var out = Set<Int>()
        for part in (spec ?? "").split(separator: ",", omittingEmptySubsequences: false) {
            let p = String(part).kTrim
            if p.isEmpty { continue }
            guard let m = PAGE_PART.find(p), let a = Int32(m.groupValues[1]).map(Int.init) else { continue }
            let b = Int32(m.groupValues[2]).map(Int.init) ?? a
            let lo = Swift.min(a, b)
            let hi = Swift.min(Swift.max(a, b), total)
            var i = Swift.max(lo, 1)
            while i <= hi && out.count < 10_000 {
                out.insert(i)
                i += 1
            }
        }
        let list = out.isEmpty ? Array(1...total) : out.sorted()
        return Array(list.prefix(max))
    }

    // MARK: 글 파일

    /// UTF-8 글 파일의 앞부분 [limit]글자(UTF-16 단위). 잘렸으면 `truncated`.
    public static func readTextPrefix(_ f: URL, limit: Int) throws -> (text: String, truncated: Bool) {
        let h: FileHandle
        do { h = try FileHandle(forReadingFrom: f) } catch { throw EtlException("파일을 읽지 못했습니다") }
        defer { try? h.close() }
        // UTF-16 한 단위는 UTF-8로 최대 3바이트(대리쌍은 4바이트에 두 단위)라 이만큼이면 limit+1 단위를 넘게 읽는다.
        let want = (limit + 1) * 3 + 4
        let data: Data = ((try? h.read(upToCount: want)) ?? nil) ?? Data()
        let s = String(decoding: data, as: UTF8.self)
        let units = s.utf16.count
        let n = Swift.min(units, limit + 1)
        return (take16(s, Swift.min(n, limit)), n > limit)
    }

    /// Kotlin `take(n)`(UTF-16 단위, 대리쌍은 자르지 않는다).
    public static func take16(_ s: String, _ n: Int) -> String {
        if s.utf16.count <= n { return s }
        var out = ""
        var used = 0
        for ch in s {
            let c = ch.utf16.count
            if used + c > n { break }
            out.append(ch)
            used += c
        }
        return out
    }
}
