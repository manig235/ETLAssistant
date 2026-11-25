import Foundation
import ETLKit

/// 아직 넣지 않은 시험·과제·동영상 강의·보강·휴강 공지. 알림 확인 때 받은 공지 중 그런 공지만 작게 남겨 두고(최대 50개, 14일),
/// 채팅 화면을 열 때 하단 시트로 보여 준다. 화면을 열 때 네트워크 요청은 하지 않는다.
public struct PendingNotice: Hashable, Sendable {
    public var id: Int64
    public var courseId: Int64
    public var courseName: String
    public var title: String
    /// epoch millis.
    public var postedAt: Int64
    public var kind: MyKind
    /// [ScheduleDraft.toJson].
    public var draft: String
    /// "일정 정리 부탁"으로 입력창에 채울 문구.
    public var prefill: String

    public init(_ id: Int64, _ courseId: Int64, _ courseName: String, _ title: String, _ postedAt: Int64, _ kind: MyKind, _ draft: String, _ prefill: String) {
        self.id = id
        self.courseId = courseId
        self.courseName = courseName
        self.title = title
        self.postedAt = postedAt
        self.kind = kind
        self.draft = draft
        self.prefill = prefill
    }
}

public enum PendingNoticesLogic {
    public static let MAX = 50
    /// 보관 기간(밀리초, 14일).
    public static let KEEP_MS: Int64 = 14 * 24 * 3600 * 1000

    /// 공지 목록에서 시험·과제 공지를 골라 저장 목록에 더한다(같은 ID는 새 값으로, 14일·50개).
    public static func add(_ list: [PendingNotice], _ anns: [AnnouncementItem], _ now: Date) -> [PendingNotice] {
        let fresh = anns.compactMap { a -> PendingNotice? in
            guard let d = ScheduleDrafts.fromAnnouncement(a) else { return nil }
            return PendingNotice(a.id, a.courseId, a.courseName, a.title, a.postedAt.epochMilli, d.kind, d.toJson(), d.prefill)
        }
        let ids = Set(fresh.map(\.id))
        let cutoff = now.epochMilli - KEEP_MS
        let merged = (fresh + list.filter { !ids.contains($0.id) }).filter { $0.postedAt >= cutoff }
        return Array(stableSorted(merged) { $0.postedAt > $1.postedAt }.prefix(MAX))
    }

    /// 보여 줄 공지: 최근 14일, 같은 공지로 넣은 내 마감·시간표 보강·휴강이 없고([registered]), 무시하지 않았고,
    /// 숨긴 과목·알림 끈 과목이 아닌 것. 최신순.
    public static func targets(_ list: [PendingNotice], registered: Set<Int64>, ignored: Set<Int64>, excludedCourse: (Int64) -> Bool, now: Date) -> [PendingNotice] {
        let cutoff = now.epochMilli - KEEP_MS
        return stableSorted(list.filter { $0.postedAt >= cutoff && !registered.contains($0.id) && !ignored.contains($0.id) && !excludedCourse($0.courseId) }) {
            $0.postedAt > $1.postedAt
        }
    }

    /// 자동으로 띄울까: 켜져 있고, 업데이트 팝업이 뜨는 실행이 아니고, 아직 본 적 없는 공지가 있을 때만.
    public static func shouldAutoShow(_ targets: [PendingNotice], seen: Set<Int64>, enabled: Bool, updatePromptShowing: Bool) -> Bool {
        enabled && !updatePromptShowing && targets.contains { !seen.contains($0.id) }
    }

    public static func toJson(_ list: [PendingNotice]) -> String {
        let a = JSONArray()
        for p in list {
            a.put(JSONObject().put("id", p.id).put("cid", p.courseId).put("course", p.courseName).put("title", p.title).put("at", p.postedAt)
                .put("kind", p.kind.id).put("draft", p.draft).put("prefill", p.prefill))
        }
        return a.toString()
    }

    public static func fromJson(_ s: String?) -> [PendingNotice] {
        guard let a = try? JSONArray(s ?? "[]") else { return [] }
        return (0..<a.length()).compactMap { i in
            guard let o = a.optJSONObject(i) else { return nil }
            return PendingNotice(o.optLong("id"), o.optLong("cid"), o.optString("course"), o.optString("title"), o.optLong("at"),
                                 MyKind.parse(o.optString("kind")) ?? .DEADLINE, o.optString("draft"), o.optString("prefill"))
        }
    }

    /// 저장된 ID 목록(순서 유지).
    public static func idList(_ s: String?) -> [Int64] {
        guard let a = try? JSONArray(s ?? "[]") else { return [] }
        return distinct((0..<a.length()).map { a.optLong($0) })
    }

    public static func ids(_ s: String?) -> Set<Int64> { Set(idList(s)) }
}

/// 등록 안 한 공지 저장소(Kotlin prefs `pending_notices`: `items`, `ignored`, `seen`).
public final class PendingNoticeStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSRecursiveLock()

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "pending_notices")) { self.prefs = prefs }

    public func load() -> [PendingNotice] { lock.withLock { PendingNoticesLogic.fromJson(prefs.string("items")) } }

    public func record(_ anns: [AnnouncementItem], _ now: Date) {
        if anns.isEmpty { return }
        lock.withLock { prefs.set(PendingNoticesLogic.toJson(PendingNoticesLogic.add(load(), anns, now)), for: "items") }
    }

    public func ignored() -> Set<Int64> { PendingNoticesLogic.ids(prefs.string("ignored")) }
    public func seen() -> Set<Int64> { PendingNoticesLogic.ids(prefs.string("seen")) }

    /// 무시: 다시 보이지 않는다.
    public func ignore(_ id: Int64) {
        lock.withLock {
            let list = distinct(PendingNoticesLogic.idList(prefs.string("ignored")) + [id])
            prefs.set(JSONArray(Array(list.suffix(200))).toString(), for: "ignored")
        }
    }

    /// 본 것으로 표시(새 공지가 생길 때까지 자동으로 다시 뜨지 않는다).
    public func markSeen(_ ids: [Int64]) {
        lock.withLock {
            let list = distinct(PendingNoticesLogic.idList(prefs.string("seen")) + ids)
            prefs.set(JSONArray(Array(list.suffix(200))).toString(), for: "seen")
        }
    }

    /// 지금 보여 줄 공지(숨긴 과목·알림 끈 과목 제외). [registered]는 같은 공지로 넣은 내 마감·보강/휴강의 공지 ID
    /// (안드로이드 `DraftIntents.registeredIds`: 내 마감의 [MyDeadline.sourceAnnouncementId] + 보강·휴강의 `sourceAnnouncementId`).
    public func targets(courses: CourseFilter, registered: Set<Int64>, now: Date = Date()) -> [PendingNotice] {
        PendingNoticesLogic.targets(load(), registered: registered, ignored: ignored(), excludedCourse: { courses.muted($0) }, now: now)
    }

    /// 같은 공지로 이미 넣은 항목의 공지 ID(안드로이드 `DraftIntents.registeredIds`).
    public static func registeredIds(mine: [MyDeadline], changes: [TimetableLogic.Change]) -> Set<Int64> {
        Set(mine.compactMap(\.sourceAnnouncementId) + changes.compactMap(\.sourceAnnouncementId))
    }
}
