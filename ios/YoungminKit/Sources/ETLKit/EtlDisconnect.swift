import Foundation

/// 설정의 "eTL 연결 해제". 토큰과, 앱이 eTL에서 **스스로** 받아 둔 캐시를 지운다.
///
/// 지우는 것:
/// - eTL 토큰, (게시판 세션 등) [Stores.extraCleanup]으로 넘긴 메모리 캐시
/// - 과제 목록 캐시(`etl_snapshot`), 새 과제·성적 비교 기준(`notify_watch`), 새 강의자료 비교용 과목별 파일 목록(`notify_files`)
/// - 수강편람에서 받은 정규 시간표(`timetable`의 `weekly`), 비서용 강의계획서 첨부(`etl-files/syllabus/`)
///
/// 남기는 것: 대화와 그 안의 파일, 알림 기록, 내 마감, 직접 넣은 수업·보강/휴강, `Documents/etl/`에 저장한 파일,
/// 알림 중복 방지 기록(`notify_state`), 캘린더 내보내기 매핑(`calendar_export`).
public enum EtlDisconnect {

    /// 지울 저장소들. 기본값은 안드로이드 prefs 이름과 같은 namespace의 UserDefaults 저장소.
    public struct Stores: @unchecked Sendable {
        public var snapshot: KeyValueStore
        public var notifyWatch: KeyValueStore
        public var notifyFiles: KeyValueStore
        public var timetable: KeyValueStore
        /// 비서용 작업 폴더(`etl-files`).
        public var etlFilesDirectory: URL
        /// 그 밖에 지울 것(예: 게시판 세션 토큰). 다른 모듈이 채운다.
        public var extraCleanup: [() -> Void]

        public init(snapshot: KeyValueStore = UserDefaultsStore(namespace: "etl_snapshot"),
                    notifyWatch: KeyValueStore = UserDefaultsStore(namespace: "notify_watch"),
                    notifyFiles: KeyValueStore = UserDefaultsStore(namespace: "notify_files"),
                    timetable: KeyValueStore = UserDefaultsStore(namespace: "timetable"),
                    etlFilesDirectory: URL? = nil,
                    extraCleanup: [() -> Void] = []) {
            self.snapshot = snapshot
            self.notifyWatch = notifyWatch
            self.notifyFiles = notifyFiles
            self.timetable = timetable
            self.etlFilesDirectory = etlFilesDirectory ?? EtlDownloads.workspaceDirectory
            self.extraCleanup = extraCleanup
        }
    }

    private static func clearAll(_ s: KeyValueStore) { for k in s.keys() { s.remove(k) } }

    /// eTL 데이터 캐시만 지운다(토큰 제외). 순서상 토큰을 먼저 지운 뒤 부른다.
    public static func clearCaches(_ stores: Stores = Stores()) {
        for f in stores.extraCleanup { f() }
        clearAll(stores.snapshot)
        clearAll(stores.notifyWatch)
        clearAll(stores.notifyFiles)
        stores.timetable.remove("weekly")
        let fm = FileManager.default
        let root = stores.etlFilesDirectory
        try? fm.removeItem(at: root.appendingPathComponent(EtlDownloads.SYLLABUS_DIR, isDirectory: true))
        // 이전 빌드는 계획서 첨부를 etl-files/ 바로 아래 `syllabus-…`로 두었다.
        if let items = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isRegularFileKey]) {
            for f in items where f.lastPathComponent.hasPrefix("syllabus-") {
                if (try? f.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true { try? fm.removeItem(at: f) }
            }
        }
        EtlEvents.dataChanged()
    }

    /// 토큰을 지우고 캐시를 지운다.
    public static func run(tokens: TokenStore = TokenStore(), stores: Stores = Stores()) throws {
        try tokens.clear()
        clearCaches(stores)
    }
}
