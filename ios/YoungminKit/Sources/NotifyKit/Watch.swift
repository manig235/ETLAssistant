import Foundation
import ETLKit

/*
 * 새 과제·마감 변경·성적 등록 판정과 수업별 알림. 순수 함수. 저장은 [WatchStore], 실행은 [NotifyRunner].
 */

/// 과제 하나에 대해 지난 확인에서 본 것. [submittedAt]은 제출 직후 점검이 이미 본 제출 시각(epoch millis).
public struct WatchRecord: Hashable, Sendable {
    public var name: String
    public var course: String
    public var due: Int64?
    public var score: Double?
    public var submittedAt: Int64?
    public init(_ name: String, _ course: String, _ due: Int64?, _ score: Double?, _ submittedAt: Int64? = nil) {
        self.name = name
        self.course = course
        self.due = due
        self.score = score
        self.submittedAt = submittedAt
    }
}

/// [initialized]가 false인 첫 확인은 기준선만 잡고 알리지 않는다.
/// [submitInitialized]: 제출 시각을 기록하기 시작했는가. 처음에는 기준선만 잡는다.
public struct WatchState: Hashable, Sendable {
    public var initialized: Bool
    public var records: [String: WatchRecord]
    public var submitInitialized: Bool

    public init(_ initialized: Bool = false, _ records: [String: WatchRecord] = [:], submitInitialized: Bool = false) {
        self.initialized = initialized
        self.records = records
        self.submitInitialized = submitInitialized
    }

    public func toJson() -> String {
        let o = JSONObject()
        for k in records.keys.sorted() {
            let r = records[k]!
            o.put(k, JSONObject().put("name", r.name).put("course", r.course)
                .put("due", r.due ?? JSONObject.NULL).put("score", r.score ?? JSONObject.NULL)
                .put("sub", r.submittedAt ?? JSONObject.NULL))
        }
        return JSONObject().put("version", 2).put("initialized", initialized).put("submitInitialized", submitInitialized).put("records", o).toString()
    }

    public static func fromJson(_ s: String?) -> WatchState {
        guard let s, let o = try? JSONObject(s) else { return WatchState() }
        var recs: [String: WatchRecord] = [:]
        if let r = o.optJSONObject("records") {
            for k in r.keys() {
                guard let x = r.optJSONObject(k) else { continue }
                recs[k] = WatchRecord(
                    x.optString("name"), x.optString("course"),
                    (x.isNull("due") || !x.has("due")) ? nil : x.optLong("due"),
                    (x.isNull("score") || !x.has("score")) ? nil : x.optDouble("score"),
                    (x.isNull("sub") || !x.has("sub")) ? nil : x.optLong("sub")
                )
            }
        }
        return WatchState(o.optBoolean("initialized", false), recs, submitInitialized: o.optBoolean("submitInitialized", false))
    }
}

public enum AssignmentWatch {
    /// 한 번에 이보다 많으면 하나의 묶음 알림으로 보낸다.
    private static let INDIVIDUAL_MAX = 3

    private static func scoreText(_ d: Double) -> String {
        if d == d.rounded(.down) { return String(Int64(d)) }
        var s = String(format: "%.2f", d)
        while s.hasSuffix("0") { s.removeLast() }
        while s.hasSuffix(".") { s.removeLast() }
        return s
    }

    /// Kotlin `Double.toString()`과 같은 표기(9.0 → "9.0", 9.5 → "9.5").
    private static func kotlinDouble(_ d: Double) -> String { "\(d)" }

    /// 제출 직후 점검 알림의 키(중복 방지·알림 교체).
    public static func submitKey(_ a: AssignmentItem) -> String {
        "submit:\(a.courseId):\(a.assignmentId):\(a.submittedAt.map { String($0.epochMilli) } ?? "null")"
    }

    /// 이 과제의 제출이 지난 확인 뒤 새로 생겼거나 바뀌었는가(제출 직후 점검 대상).
    public static func isNewSubmission(_ prev: WatchState, _ a: AssignmentItem) -> Bool {
        if !prev.initialized || !prev.submitInitialized || a.check == nil { return false }
        guard let at = a.submittedAt?.epochMilli else { return false }
        return prev.records["\(a.courseId):\(a.assignmentId)"]?.submittedAt != at
    }

    private struct Event { let kind: String; let item: AssignmentItem; let line: String; let tag: String }

    /// 지난 상태와 이번 조회를 비교해 새 과제, 마감 변경, 성적 등록([changes]), 제출 직후 점검([submits]) 알림을 만든다.
    /// 내 마감(강의 ID −1)은 보지 않는다. 이번에 못 본 과제는 기록을 남긴다.
    /// 과제가 하나도 오지 않고 오류만 있으면 상태를 바꾸지 않는다.
    /// [muted]인 과목은 알리지 않지만 기록은 갱신한다.
    public static func diff(_ prev: WatchState, _ snapshot: Snapshot, _ now: Date, _ muted: (Int64) -> Bool = { _ in false },
                            changes: Bool = true, submits: Bool = false, submitOk: Bool = false) -> (WatchState, [Notice]) {
        let items = snapshot.assignments.filter { $0.courseId != MyDeadlines.COURSE_ID }
        if items.isEmpty && !snapshot.errors.isEmpty { return (prev, []) }
        let first = !prev.initialized
        var records: [String: WatchRecord] = [:]
        var events: [Event] = []
        var submitEvents: [AssignmentItem] = []
        for a in items {
            let key = "\(a.courseId):\(a.assignmentId)"
            let old = prev.records[key]
            let subAt: Int64?
            if a.check == nil && a.submittedAt != nil {
                // 점검 정보가 없는 항목(받아 둔 옛 저장본)은 지난 기록을 그대로 둔다.
                subAt = old?.submittedAt
            } else if isNewSubmission(prev, a) && submits && !muted(a.courseId) && a.check?.attachmentsKnown == false {
                subAt = old?.submittedAt
            } else {
                subAt = a.submittedAt?.epochMilli
            }
            let rec = WatchRecord(a.name, a.courseName, a.due?.epochMilli, a.score, subAt)
            let name = NoticeText.name(a.name)
            if let old {
                if old.due != rec.due {
                    let fmt = { (ms: Int64?) -> String in ms.map { NoticeText.dateLabel(Date(epochMilli: $0)) } ?? "없음" }
                    events.append(Event(kind: "마감 변경", item: a, line: "\(name) · \(fmt(old.due)) → \(fmt(rec.due))",
                                        tag: "due:\(key):\(rec.due.map { String($0) } ?? "none")"))
                }
                if let score = rec.score, old.score != score {
                    let pts = a.points.map { " / \(scoreText($0))" } ?? ""
                    events.append(Event(kind: "성적", item: a, line: "\(name) · \(scoreText(score))\(pts)", tag: "grade:\(key):\(kotlinDouble(score))"))
                }
            } else if !first {
                let due = a.due.map { "마감 \(NoticeText.dateLabel($0)) (\(EtlLogic.remaining($0, now)))" } ?? "마감일 없음"
                events.append(Event(kind: "새 과제", item: a, line: "\(name) · \(due)", tag: "new:\(key)"))
            }
            if submits && isNewSubmission(prev, a) && rec.submittedAt == a.submittedAt?.epochMilli && !muted(a.courseId) &&
                (a.check?.problems.isEmpty == false || submitOk) {
                submitEvents.append(a)
            }
            records[key] = rec
        }
        for (k, r) in prev.records where records[k] == nil { records[k] = r }
        let state = WatchState(true, records, submitInitialized: true)
        if !changes { events.removeAll() }
        events.removeAll { muted($0.item.courseId) }
        let submitNotices = stableSorted(submitEvents) { lessNullsFirst($0.submittedAt, $1.submittedAt) }
            .map { ($0.check?.problems.isEmpty ?? true) ? submitOkNotice($0) : submitNotice($0) }
        if events.isEmpty { return (state, submitNotices) }

        let order = ["마감 변경": 0, "새 과제": 1, "성적": 2]
        let sorted = stableSorted(events) { x, y in
            let ox = order[x.kind] ?? 9, oy = order[y.kind] ?? 9
            if ox != oy { return ox < oy }
            return (x.item.due ?? .distantFuture) < (y.item.due ?? .distantFuture)
        }
        if sorted.count <= INDIVIDUAL_MAX {
            return (state, sorted.map { e in
                Notice(.CHANGE, NoticeText.cut("\(e.kind) · \(NoticeText.clean(e.item.courseName))", NoticeText.TITLE_MAX),
                       NoticeText.cut(e.line, NoticeText.LINE_MAX), url: NoticeText.assignmentUrl(e.item), tag: e.tag)
            } + submitNotices)
        }
        let lines = sorted.map { "• [\($0.kind)] \(NoticeText.course($0.item.courseName)) — \($0.line)" }
        var kindOrder: [String] = []
        var counts: [String: Int] = [:]
        for e in sorted {
            if counts[e.kind] == nil { kindOrder.append(e.kind) }
            counts[e.kind, default: 0] += 1
        }
        let countText = stableSorted(kindOrder) { (order[$0] ?? 9) < (order[$1] ?? 9) }.map { "\($0) \(counts[$0]!)" }.joined(separator: " · ")
        return (state, [
            Notice(.CHANGE, "eTL 변경 \(sorted.count)건", NoticeText.cut(countText, NoticeText.LINE_MAX),
                   bigText: NoticeText.joinLimited(lines, 8),
                   url: NoticeText.collectionUrl(sorted.map(\.item.courseId)) ?? NoticeText.ETL_PREFIX, tag: "change:summary"),
        ] + submitNotices)
    }

    /// 문제없는 제출의 조용한 알림: 제목 `제출 확인됐어요`, 본문 `자료구조 HW1 · 파일 2개 · 11/24(화) 12:03`(늦으면 ` · 늦은 제출`).
    private static func submitOkNotice(_ a: AssignmentItem) -> Notice {
        let key = submitKey(a)
        let parts = [
            "\(NoticeText.course(a.courseName)) \(NoticeText.name(a.name))",
            a.check?.files.flatMap { $0 > 0 ? "파일 \($0)개" : nil },
            a.submittedAt.map { NoticeText.dateLabel($0) },
            a.check?.late == true ? "늦은 제출" : nil,
        ].compactMap { $0 }
        return Notice(.SUBMIT_OK, "제출 확인됐어요", NoticeText.cut(parts.joined(separator: " · "), NoticeText.LINE_MAX),
                      url: NoticeText.assignmentUrl(a), tag: key, firedKeys: [key])
    }

    /// 제출에 문제가 있을 때의 알림. 문제 문구는 eTL 파일 이름이 들어갈 수 있어 정리해서 쓴다.
    private static func submitNotice(_ a: AssignmentItem) -> Notice {
        let problems = (a.check?.problems ?? []).map { NoticeText.clean($0) }
        let late = a.check?.late == true ? ["늦은 제출로 표시됨"] : []
        let key = submitKey(a)
        return Notice(.SUBMIT, NoticeText.cut("제출 확인 필요 · \(NoticeText.clean(a.courseName))", NoticeText.TITLE_MAX),
                      NoticeText.cut("\(NoticeText.name(a.name)) · \(problems[0])" + (problems.count > 1 ? " 외 \(problems.count - 1)건" : ""), NoticeText.LINE_MAX),
                      bigText: NoticeText.joinLimited([NoticeText.name(a.name)] + (problems + late).map { "• \($0)" }, 8),
                      url: NoticeText.assignmentUrl(a), tag: key, firedKeys: [key])
    }
}

/// 수업 시작 [NotifySettings.classLeadMinutes]분 전 알림. 키: `class:{날짜}:{시작}:{과목}`.
public enum ClassNotices {
    public static func key(_ date: DayDate, _ r: TimetableLogic.ClassRow) -> String {
        "class:\(date):\(r.start):\(r.courseId.map { String($0) } ?? r.course)"
    }

    /// 시작 시각. "HH:mm"이 아니면 nil(안드로이드는 예외).
    private static func startAt(_ date: DayDate, _ hm: String, _ zone: TimeZone) -> Date? {
        guard let t = DayTime.parse(hm) else { return nil }
        return ZoneClock.instant(date, t.hour, t.minute, 0, zone)
    }

    /// 지금 알릴 수업: 시작 [leadMin]분 전부터 시작 전까지이고 아직 알리지 않은 것.
    public static func plan(_ day: TimetableLogic.Day, _ now: Date, _ zone: TimeZone, _ leadMin: Int, _ fired: Set<String>) -> [Notice] {
        day.classes.compactMap { r -> Notice? in
            guard let start = startAt(day.date, r.start, zone) else { return nil }
            guard !(now < start.addingTimeInterval(-Double(leadMin) * 60)) && now < start && !fired.contains(key(day.date, r)) else { return nil }
            let mins = max(Int64(start.timeIntervalSince(now) / 60), 1)
            let kind = r.kind == "정규" ? "" : " [\(r.kind)]"
            return Notice(.CLASS, NoticeText.cut("\(mins)분 뒤 수업 · \(NoticeText.clean(r.course))\(kind)", NoticeText.TITLE_MAX),
                          NoticeText.cut("\(r.start)~\(r.end)" + (r.room.map { " · \($0)" } ?? "") + (r.note.map { " · \(NoticeText.clean($0))" } ?? ""), NoticeText.LINE_MAX),
                          url: r.courseId.flatMap { $0 > 0 ? "\(NoticeText.ETL_PREFIX)courses/\($0)" : nil },
                          tag: key(day.date, r), firedKeys: [key(day.date, r)])
        }
    }

    /// 다음 수업 알림 시각(오늘·내일 중 가장 이른 것).
    public static func nextAt(_ days: [TimetableLogic.Day], _ now: Date, _ zone: TimeZone, _ leadMin: Int, _ fired: Set<String>) -> Date? {
        days.flatMap { d in
            d.classes.filter { !fired.contains(key(d.date, $0)) }.compactMap { startAt(d.date, $0.start, zone)?.addingTimeInterval(-Double(leadMin) * 60) }
        }.filter { $0 > now }.min()
    }
}

/// 새 과제·성적 비교 기준 저장소(Kotlin prefs `notify_watch`의 `state`).
public final class WatchStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSLock()
    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "notify_watch")) { self.prefs = prefs }
    public func load() -> WatchState { lock.withLock { WatchState.fromJson(prefs.string("state")) } }
    public func save(_ s: WatchState) { lock.withLock { prefs.set(s.toJson(), for: "state") } }
}
