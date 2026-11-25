import Foundation
import ETLKit

/*
 * 새 강의자료 알림. 주기 확인의 마지막 단계로 [NewFiles.INTERVAL_MS]마다, 현재 학기 강의를 하나씩 차례로
 * `GET /api/v1/courses/{id}/files?sort=updated_at&order=desc&per_page=30`(첫 쪽만)로 보고, 전에 못 본 파일 ID가 있으면 과목별로 묶어 알린다.
 * 파일은 받지 않는다. 판정은 [NewFiles], 저장은 [NewFilesStore], 실행은 [NewFilesCheck].
 */

/// 과목 하나: 본 파일 ID(최근 것부터, 최대 [NewFiles.SEEN_MAX]개)와 파일 탭이 막혀 건너뛰는 기한(epoch millis, 0이면 없음).
public struct CourseFiles: Hashable, Sendable {
    public var seen: [Int64]
    public var skipUntil: Int64
    public init(_ seen: [Int64], skipUntil: Int64 = 0) {
        self.seen = seen
        self.skipUntil = skipUntil
    }
}

public struct FilesWatchState: Hashable, Sendable {
    public var lastRunAt: Int64
    public var courses: [Int64: CourseFiles]

    public init(lastRunAt: Int64 = 0, courses: [Int64: CourseFiles] = [:]) {
        self.lastRunAt = lastRunAt
        self.courses = courses
    }

    public func toJson() -> String {
        let o = JSONObject()
        for id in courses.keys.sorted() {
            let c = courses[id]!
            o.put(String(id), JSONObject().put("seen", JSONArray(c.seen)).put("skipUntil", c.skipUntil))
        }
        return JSONObject().put("version", 1).put("lastRunAt", lastRunAt).put("courses", o).toString()
    }

    public static func fromJson(_ s: String?) -> FilesWatchState {
        guard let s, let o = try? JSONObject(s) else { return FilesWatchState() }
        var courses: [Int64: CourseFiles] = [:]
        if let c = o.optJSONObject("courses") {
            for k in c.keys() {
                guard let id = Int64(k), let x = c.optJSONObject(k) else { continue }
                let arr = x.optJSONArray("seen") ?? JSONArray()
                courses[id] = CourseFiles((0..<arr.length()).map { arr.optLong($0) }, skipUntil: x.optLong("skipUntil"))
            }
        }
        return FilesWatchState(lastRunAt: o.optLong("lastRunAt"), courses: courses)
    }
}

/// 새 강의자료의 파일 하나(ETLKit의 `Queries.FileRef`와 다르다).
public struct FileRef: Hashable, Sendable {
    public var id: Int64
    public var name: String
    public init(_ id: Int64, _ name: String) {
        self.id = id
        self.name = name
    }
}

public enum NewFiles {
    /// 확인 간격(4시간).
    public static let INTERVAL_MS: Int64 = 4 * 3600 * 1000
    /// 주기 작업이 조금 일찍 돌아도 건너뛰지 않게 둔 여유.
    private static let SLACK_MS: Int64 = 10 * 60 * 1000
    public static let SEEN_MAX = 500
    /// 파일 탭이 숨겨진 강의는 이만큼 건너뛴다(7일).
    public static let SKIP_MS: Int64 = 7 * 24 * 3600 * 1000
    /// 연결 실패가 이만큼 이어지면 나머지 강의는 이번에 건너뛴다.
    public static let MAX_CONSECUTIVE_NETWORK_FAILURES = 2
    private static let BIG_LINES = 8
    private static let FILE_NAME_MAX = 60

    public static func path(_ courseId: Int64) -> String { "/api/v1/courses/\(courseId)/files?sort=updated_at&order=desc&per_page=30" }

    /// 확인할 때가 됐는가. 시계가 크게 뒤로 갔으면(저장 시각이 미래) 바로 돈다.
    public static func due(_ state: FilesWatchState, _ nowMs: Int64) -> Bool {
        state.lastRunAt <= 0 || state.lastRunAt > nowMs + 60_000 || nowMs - state.lastRunAt >= INTERVAL_MS - SLACK_MS
    }

    /// 숨김(`hidden_for_user`)·잠김(`locked_for_user`) 파일은 뺀다.
    public static func parse(_ items: [JSONObject]) -> [FileRef] {
        items.filter { !$0.optBoolean("hidden_for_user", false) && !$0.optBoolean("locked_for_user", false) && $0.optLong("id") > 0 }
            .map { FileRef($0.optLong("id"), $0.str("display_name") ?? $0.str("filename") ?? "(이름 없음)") }
    }

    /// 강의 하나를 조회한 결과.
    public enum Outcome: Hashable, Sendable {
        case files([FileRef])
        /// 파일 탭이 숨겨졌거나 권한이 없음(401 권한 없음, 403, 404).
        case denied
        /// eTL이 401을 줬는데 권한 문구가 없어 토큰 거부로 보인 경우.
        case authRejected
        /// 연결 실패 등. 기준선을 바꾸지 않는다.
        case failed
    }

    /// 강의 하나의 비교. 처음 보는 강의는 기준선만 잡는다(알리지 않음). 새 파일은 조회 순서(최근 갱신 순)대로.
    public static func diffCourse(_ prev: CourseFiles?, _ files: [FileRef]) -> (CourseFiles, [FileRef]) {
        let ids = files.map(\.id)
        guard let prev else { return (CourseFiles(Array(distinct(ids).prefix(SEEN_MAX))), []) }
        let seen = Set(prev.seen)
        let fresh = distinctBy(files.filter { !seen.contains($0.id) }) { $0.id }
        return (CourseFiles(Array(distinct(ids + prev.seen).prefix(SEEN_MAX))), fresh)
    }

    /// 이번에 조회할 강의(건너뛰는 기한이 지난 것).
    public static func toCheck(_ state: FilesWatchState, _ courses: [CourseRef], _ nowMs: Int64) -> [CourseRef] {
        distinctBy(courses.filter { $0.id > 0 }) { $0.id }.filter { (state.courses[$0.id]?.skipUntil ?? 0) <= nowMs }
    }

    /// 조회 결과를 반영한다. [courses]는 이번 학기 강의 전체(목록에 없는 강의의 기록은 지운다).
    /// [muted]인 과목은 알리지 않지만 기준선은 갱신한다. 모든 강의가 [Outcome.authRejected]면 토큰 문제로 보고 실패로 둔다.
    /// 성공한 강의가 하나도 없으면 [FilesWatchState.lastRunAt]을 바꾸지 않는다.
    public static func apply(_ state: FilesWatchState, _ nowMs: Int64, _ courses: [CourseRef], _ results: [Int64: Outcome],
                             muted: (Int64) -> Bool = { _ in false }) -> (FilesWatchState, [Notice]) {
        let allAuth = !results.isEmpty && results.values.allSatisfy { $0 == .authRejected }
        let current = Set(courses.map(\.id))
        var next = state.courses.filter { current.contains($0.key) || current.isEmpty }
        var notices: [Notice] = []
        var anyOk = false
        for c in courses {
            guard let r = results[c.id] else { continue }
            switch r {
            case .files(let files):
                anyOk = true
                let (rec, fresh) = diffCourse(state.courses[c.id], files)
                next[c.id] = rec
                if !fresh.isEmpty && !muted(c.id) { notices.append(notice(c, fresh)) }
            case .denied:
                anyOk = true
                var x = state.courses[c.id] ?? CourseFiles([])
                x.skipUntil = nowMs + SKIP_MS
                next[c.id] = x
            case .authRejected:
                if !allAuth {
                    anyOk = true
                    var x = state.courses[c.id] ?? CourseFiles([])
                    x.skipUntil = nowMs + SKIP_MS
                    next[c.id] = x
                }
            case .failed:
                break
            }
        }
        return (FilesWatchState(lastRunAt: anyOk ? nowMs : state.lastRunAt, courses: next), notices)
    }

    /// 채팅 입력창에 채워 둘 문구(보내지는 않는다). 파일 이름은 eTL 데이터라 정리하고 자른다.
    public static func prefill(_ course: CourseRef, _ files: [FileRef]) -> String {
        let names = files.map { NoticeText.safe($0.name, FILE_NAME_MAX) }
        let what = names.count <= 3 ? names.joined(separator: ", ") : "\(names[0]) 외 \(names.count - 1)개"
        return "\(NoticeText.course(course.name)) 새 강의자료 \(what) 받아줘"
    }

    /// 과목별 묶음 알림. 누르면 채팅이 열리고 받아 달라는 문구가 입력창에 채워진다. "eTL에서 열기"는 그 과목의 파일 화면.
    public static func notice(_ course: CourseRef, _ files: [FileRef]) -> Notice {
        Notice(.NEW_FILES, NoticeText.cut("새 강의자료 \(files.count)개 · \(NoticeText.clean(course.name))", NoticeText.TITLE_MAX),
               NoticeText.cut(files.map { NoticeText.safe($0.name, FILE_NAME_MAX) }.joined(separator: ", "), NoticeText.LINE_MAX),
               bigText: files.count > 1 ? NoticeText.joinLimited(files.map { "• " + NoticeText.safe($0.name, FILE_NAME_MAX) }, BIG_LINES) : nil,
               url: "\(NoticeText.ETL_PREFIX)courses/\(course.id)/files", tag: "files:\(course.id)",
               prefill: prefill(course, files), prefillAction: "자료 받아줘")
    }
}

/// 새 강의자료 비교 기준 저장소(Kotlin prefs `notify_files`의 `state`).
public final class NewFilesStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSLock()
    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "notify_files")) { self.prefs = prefs }
    public func load() -> FilesWatchState { lock.withLock { FilesWatchState.fromJson(prefs.string("state")) } }
    public func save(_ s: FilesWatchState) { lock.withLock { prefs.set(s.toJson(), for: "state") } }
}

/// 네트워크 부분: 강의를 하나씩 차례로(병렬 없이) 조회한다. GET만, 첫 쪽만.
public enum NewFilesCheck {
    public static func fetch(_ client: EtlClient, _ courses: [CourseRef]) async throws -> [Int64: NewFiles.Outcome] {
        try await fetch({ try await client.get($0) }, courses)
    }

    /// [get]은 eTL GET(`EtlClient.get`). 테스트에서 바꿔 끼운다. 결과 순서는 [courses] 순서.
    public static func fetch(_ get: (String) async throws -> Any, _ courses: [CourseRef]) async throws -> [Int64: NewFiles.Outcome] {
        var out: [Int64: NewFiles.Outcome] = [:]
        var networkFailures = 0
        for c in courses {
            if networkFailures >= NewFiles.MAX_CONSECUTIVE_NETWORK_FAILURES {
                out[c.id] = .failed
                continue
            }
            do {
                guard let arr = try await get(NewFiles.path(c.id)) as? JSONArray else { throw EtlException("목록이 아닌 응답") }
                networkFailures = 0
                out[c.id] = .files(NewFiles.parse(arr.objects()))
            } catch is EtlAuthException {
                networkFailures = 0
                out[c.id] = .authRejected
            } catch let e as EtlException {
                switch e.kind {
                case "forbidden", "http-403", "http-404":
                    networkFailures = 0
                    out[c.id] = .denied
                case "timeout", "network":
                    networkFailures += 1
                    out[c.id] = .failed
                default:
                    out[c.id] = .failed
                }
            }
        }
        return out
    }
}
