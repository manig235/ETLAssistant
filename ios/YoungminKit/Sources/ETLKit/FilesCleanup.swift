import Foundation

/*
 * 받은 eTL 파일 자동 정리(Kotlin `files/DownloadCleanup`·`WorkFileCleanup`의 iOS 판).
 * - 대상: 앱이 `Documents/etl/`(파일 앱 › 영민이 › etl, 안드로이드 폰 `Download/etl/`에 해당)에 저장한 파일 중
 *   그 폴더에 들어온 지 N일(2·7·30) 지난 것. 기준 시각은 폴더에 추가된 시각(`addedToDirectoryDate`, MediaStore DATE_ADDED에 해당).
 *   사본은 작업 폴더 파일을 복사해 만들어서 만든·수정 시각이 원본 것을 따라가므로 쓰지 않는다.
 * - 제외: 하위 폴더 전체(AI가 만든 파일 `영민이가 만든 파일/` 포함), 숨김 파일, 심볼릭 링크, [generated]가 참이라 한 파일.
 *   iOS에는 MediaStore 소유자 개념이 없어 "앱이 만든 항목만"은 `etl` 폴더 맨 위의 파일로 대신한다.
 * - 기간을 고르기 전에는 아무것도 지우지 않는다. 하루 한 번(20시간 간격), 네트워크 없이. 알림 없이 설정에 "최근 정리: 11/24, 3개".
 * - 지운 파일은 `Documents` 기준 상대 경로로 기록해(최대 500개) 채팅 파일 카드가 "자동 정리된 파일이에요"로 보인다.
 * - 앱 작업 폴더(`etl-files`) 14일 정리([WorkFiles.prune])는 따로 돈다.
 */

/// 정리 기간(Kotlin `CleanupPeriod`). [days]가 nil이면 정리하지 않는다.
public enum CleanupPeriod: String, CaseIterable, Sendable, Hashable {
    case TWO = "2", SEVEN = "7", THIRTY = "30", NEVER = "never"

    public var key: String { rawValue }
    public var days: Int? {
        switch self {
        case .TWO: return 2
        case .SEVEN: return 7
        case .THIRTY: return 30
        case .NEVER: return nil
        }
    }
    public var label: String {
        switch self {
        case .TWO: return "2일"
        case .SEVEN: return "7일"
        case .THIRTY: return "30일"
        case .NEVER: return "정리 안 함"
        }
    }

    public static func of(_ key: String?) -> CleanupPeriod? { allCases.first { $0.key == key } }
    /// 고르기 화면의 기본 선택.
    public static let DEFAULT = CleanupPeriod.TWO
}

/// 정리 판정의 순수 부분(Kotlin `DownloadCleanupLogic`).
public enum DownloadCleanupLogic {
    public static let DAY_MS: Int64 = 24 * 3600 * 1000
    /// 하루 한 번: 마지막 실행 뒤 이만큼 지나야 다시 돈다(시각이 조금씩 밀려도 하루를 건너뛰지 않게 20시간).
    public static let MIN_INTERVAL_MS: Int64 = 20 * 3600 * 1000
    /// 기존 사용자에게 고르기 창을 띄우는 최대 횟수(닫기만 한 경우).
    public static let MAX_ASKS = 2
    /// 사용자용 폴더 이름(`Documents/etl`).
    public static let USER_DIR = "etl"
    /// AI가 만든 파일 폴더(AssistantKit `GeneratedFilesLogic.SUBDIR`와 같은 값).
    public static let GENERATED_SUBDIR = "영민이가 만든 파일"

    /// `Documents` 기준 상대 경로가 `etl/` 아래인가(대소문자 무시, 하위 폴더 포함; Kotlin `isEtlDownload`와 같음).
    /// 실제 정리([DownloadCleanup.run])는 `etl` 맨 위의 파일만 본다.
    public static func isEtlDownload(_ relativePath: String?) -> Bool {
        guard var p = relativePath?.trimmingCharacters(in: .whitespacesAndNewlines) else { return false }
        p = p.replacingOccurrences(of: "\\", with: "/")
        while p.hasPrefix("/") { p.removeFirst() }
        let prefix = USER_DIR + "/"
        return p.lowercased().hasPrefix(prefix) || p.lowercased() == USER_DIR
    }

    public static func isGeneratedPath(_ relativePath: String?) -> Bool {
        guard let p = relativePath else { return false }
        return p.replacingOccurrences(of: "\\", with: "/").contains("/\(GENERATED_SUBDIR)/") || p.hasPrefix("\(GENERATED_SUBDIR)/")
    }

    /// 이 파일을 지울지. [addedAtMs]는 폴더에 들어온 시각. 기간을 고르지 않았거나 "정리 안 함"이면 지우지 않는다.
    /// 정확히 N일이 되면 지운다. 시각이 미래(시계가 바뀐 경우)거나 모르면 지우지 않는다.
    public static func shouldDelete(_ relativePath: String?, addedAtMs: Int64?, nowMs: Int64, period: CleanupPeriod?,
                                    generated: Bool, owned: Bool = true) -> Bool {
        guard let days = period?.days else { return false }
        if !owned || generated || !isEtlDownload(relativePath) || isGeneratedPath(relativePath) { return false }
        guard let added = addedAtMs, added > 0 else { return false }
        let age = nowMs - added
        return age >= 0 && age >= Int64(days) * DAY_MS
    }

    public static func due(lastRunMs: Int64, nowMs: Int64) -> Bool {
        lastRunMs <= 0 || nowMs < lastRunMs || nowMs - lastRunMs >= MIN_INTERVAL_MS
    }

    /// 기존 사용자에게 고르기 창을 띄울지: 아직 고르지 않았고 닫은 횟수가 [MAX_ASKS] 미만.
    public static func shouldAsk(_ period: CleanupPeriod?, asks: Int) -> Bool { period == nil && asks < MAX_ASKS }

    /// 설정 화면 표시: "최근 정리: 11/24, 3개". 한 번도 돌지 않았으면 nil.
    public static func lastRunLabel(lastRunMs: Int64, count: Int, zone: TimeZone = EtlLogic.kst) -> String? {
        if lastRunMs <= 0 { return nil }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let d = cal.dateComponents([.month, .day], from: Date(epochMilli: lastRunMs))
        return "최근 정리: \(d.month ?? 0)/\(d.day ?? 0), \(count)개"
    }

    /// `file://…/Documents/etl/a.pdf` 또는 경로 → `Documents` 기준 상대 경로(`etl/a.pdf`). 앱 폴더 위치(UUID)가 바뀌어도 같다.
    public static func documentsRelative(_ uriOrPath: String?) -> String? {
        guard let s = uriOrPath, !s.isEmpty else { return nil }
        let path: String
        if let u = URL(string: s), u.isFileURL { path = u.path } else { path = s }
        guard let r = path.range(of: "/Documents/") else { return nil }
        // 파일 시스템이 돌려준 이름은 한글이 풀어 쓴(NFD) 꼴일 수 있어 NFC로 맞춘다.
        let rel = String(path[r.upperBound...]).precomposedStringWithCanonicalMapping
        return rel.isEmpty ? nil : rel
    }

    // MARK: 문구(Kotlin `CleanupUi` 상수, iOS 위치로)

    public static let QUESTION = "받은 강의자료를 폰에 얼마나 둘까요?"
    /// 안드로이드 "폰 Download/etl" → iOS 파일 앱의 이 앱 폴더.
    public static let DESC = "비서가 파일 앱 › 영민이 › etl에 받아 둔 eTL 파일을 고른 기간이 지나면 지워요. 비서가 만든 파일(영민이가 만든 파일 폴더)은 지우지 않아요."
    public static let KEEP_HINT = "계속 두고 싶은 자료는 파일 카드 옆 공유 아이콘을 눌러 다른 곳에 저장하세요."
    public static let NOT_CHOSEN = "아직 고르지 않았어요. 고르기 전에는 아무것도 지우지 않아요."
}

/// 설정 저장 + 정리 실행(Kotlin `DownloadCleanup`). 키 이름은 원본과 같다(`period`, `asks`, `lastRun`, `lastCount`, `cleaned`).
public final class DownloadCleanup: @unchecked Sendable {
    public static let PREFS = "download_cleanup"
    public static let MAX_CLEANED = 500

    private let prefs: KeyValueStore
    private let documents: URL
    private let generated: @Sendable (String) -> Bool
    private let lock = NSLock()

    /// - Parameters:
    ///   - documents: 앱 `Documents` 폴더(사용자용 `etl` 폴더의 부모).
    ///   - generated: `Documents` 기준 상대 경로가 AI가 만든 파일인지(앱이 AssistantKit `GeneratedFiles`로 판정).
    public init(prefs: KeyValueStore = UserDefaultsStore(namespace: DownloadCleanup.PREFS),
                documents: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0],
                generated: @escaping @Sendable (String) -> Bool = { _ in false }) {
        self.prefs = prefs
        self.documents = documents
        self.generated = generated
    }

    /// 고른 기간. 아직 고르지 않았으면 nil(이때는 지우지 않는다).
    public var period: CleanupPeriod? { CleanupPeriod.of(prefs.string("period")) }

    /// 기간을 저장하고 바로 한 번 정리한다(고른 결과가 보이게, 하루 한 번 제한과 별개). 지운 개수.
    @discardableResult
    public func setPeriod(_ p: CleanupPeriod, now: Date = Date()) -> Int {
        prefs.set(p.key, for: "period")
        return run(force: true, now: now)
    }

    public var asks: Int { prefs.int("asks", default: 0) }
    public func markAsked() { prefs.set(asks + 1, for: "asks") }
    public var shouldAsk: Bool { DownloadCleanupLogic.shouldAsk(period, asks: asks) }

    public var lastRunLabel: String? {
        DownloadCleanupLogic.lastRunLabel(lastRunMs: prefs.int64("lastRun") ?? 0, count: prefs.int("lastCount", default: 0))
    }

    /// 설정 화면 상태 줄: 고르지 않았으면 안내, 돈 적이 있으면 "최근 정리: …".
    public var statusLabel: String? { period == nil ? DownloadCleanupLogic.NOT_CHOSEN : lastRunLabel }

    private var cleaned: [String] {
        guard let s = prefs.string("cleaned"), let d = s.data(using: .utf8),
              let a = try? JSONSerialization.jsonObject(with: d) as? [String] else { return [] }
        return a
    }

    /// 자동 정리로 지운 파일인가(파일 카드의 `uri`: `file://…/Documents/etl/…`).
    public func wasCleaned(_ uri: String?) -> Bool {
        guard let rel = DownloadCleanupLogic.documentsRelative(uri) else { return false }
        return cleaned.contains(rel)
    }

    /// 하루 한 번만 돈다.
    @discardableResult
    public func runIfDue(now: Date = Date()) -> Int { run(force: false, now: now) }

    /// 지운 개수. 기간을 고르지 않았거나 "정리 안 함"이면 0이고 기록도 남기지 않는다.
    @discardableResult
    public func run(force: Bool, now: Date = Date()) -> Int {
        lock.lock()
        defer { lock.unlock() }
        guard let period, period.days != nil else { return 0 }
        let nowMs = now.epochMilli
        if !force && !DownloadCleanupLogic.due(lastRunMs: prefs.int64("lastRun") ?? 0, nowMs: nowMs) { return 0 }
        var removed: [String] = []
        let fm = FileManager.default
        let dir = documents.appendingPathComponent(DownloadCleanupLogic.USER_DIR, isDirectory: true)
        let keys: [URLResourceKey] = [.isRegularFileKey, .isSymbolicLinkKey, .addedToDirectoryDateKey, .isHiddenKey]
        let items = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
        for f in items {
            guard let v = try? f.resourceValues(forKeys: Set(keys)), v.isRegularFile == true, v.isSymbolicLink != true else { continue }
            let rel = (DownloadCleanupLogic.USER_DIR + "/" + f.lastPathComponent).precomposedStringWithCanonicalMapping
            let added = v.addedToDirectoryDate?.epochMilli
            if DownloadCleanupLogic.shouldDelete(rel, addedAtMs: added, nowMs: nowMs, period: period, generated: generated(rel)),
               (try? fm.removeItem(at: f)) != nil {
                removed.append(rel)
            }
        }
        var list = cleaned.filter { !removed.contains($0) } + removed
        if list.count > Self.MAX_CLEANED { list = Array(list.suffix(Self.MAX_CLEANED)) }
        prefs.set(Int64(nowMs), for: "lastRun")
        prefs.set(removed.count, for: "lastCount")
        if let d = try? JSONSerialization.data(withJSONObject: list), let s = String(data: d, encoding: .utf8) { prefs.set(s, for: "cleaned") }
        return removed.count
    }
}

/// 앱 전용 작업 사본 정리(Kotlin `WorkFiles.prune`). 사용자용 사본(`Documents/etl`)은 대상이 아니다.
public enum WorkFiles {
    /// [root] 아래(하위 폴더 포함)에서 수정한 지 [EtlDownloads.KEEP_DAYS]일 지난 파일을 지운다. 심볼릭 링크는 따라가지도 지우지도 않는다.
    @discardableResult
    public static func prune(_ root: URL, nowMs: Int64, keepDays: Int = EtlDownloads.KEEP_DAYS) -> Int {
        let fm = FileManager.default
        let keys: Set<URLResourceKey> = [.isSymbolicLinkKey, .isDirectoryKey, .isRegularFileKey, .contentModificationDateKey]
        if (try? root.resourceValues(forKeys: [.isSymbolicLinkKey]))?.isSymbolicLink == true { return 0 }
        guard let items = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: Array(keys)) else { return 0 }
        var count = 0
        let maxAge = Int64(keepDays) * DownloadCleanupLogic.DAY_MS
        for f in items {
            guard let v = try? f.resourceValues(forKeys: keys), v.isSymbolicLink != true else { continue }
            if v.isDirectory == true {
                count += prune(f, nowMs: nowMs, keepDays: keepDays)
            } else if v.isRegularFile == true, let m = v.contentModificationDate, nowMs - m.epochMilli >= maxAge,
                      (try? fm.removeItem(at: f)) != nil {
                count += 1
            }
        }
        return count
    }

    private static let lastRunKey = "workFilesLastRun"

    /// 하루 한 번 작업 폴더(`etl-files`)를 정리한다(Kotlin `WorkFileCleanup` 주기 작업 대신 앱이 깨어날 때). 지운 개수.
    @discardableResult
    public static func runIfDue(root: URL = EtlDownloads.workspaceDirectory,
                                prefs: KeyValueStore = UserDefaultsStore(namespace: DownloadCleanup.PREFS),
                                now: Date = Date()) -> Int {
        let nowMs = now.epochMilli
        guard DownloadCleanupLogic.due(lastRunMs: prefs.int64(lastRunKey) ?? 0, nowMs: nowMs) else { return 0 }
        prefs.set(Int64(nowMs), for: lastRunKey)
        return prune(root, nowMs: nowMs)
    }
}
