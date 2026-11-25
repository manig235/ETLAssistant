import Foundation
import ETLKit

/*
 * 과목별 알림 끄기·숨기기.
 * - 알림 끄기: 그 과목의 마감·공지·새 과제·마감 변경·성적·수업 알림을 보내지 않는다. 중복 방지 기록과 기준선은 계속 갱신한다.
 * - 숨기기: 알림 끄기 + 내 일정·브리핑·일정 위젯에서 과제·공지를 뺀다. "시간표·오늘 수업에서도 숨기기"([CourseFilter.hideTimetable])를
 *   켜면 시간표·브리핑의 오늘 수업에서도 그 과목의 정규 수업과 보강·휴강을 뺀다.
 * 비서의 eTL 도구에는 적용하지 않는다. 내 마감(강의 ID −1)과 직접 추가한 수업(음수 ID)은 대상이 아니다.
 */

public enum CourseMode: Int, CaseIterable, Hashable, Sendable {
    case SHOW = 0, MUTE, HIDE

    public var key: String {
        switch self {
        case .SHOW: return "show"
        case .MUTE: return "mute"
        case .HIDE: return "hide"
        }
    }

    public var label: String {
        switch self {
        case .SHOW: return "알림 받기"
        case .MUTE: return "알림 끄기"
        case .HIDE: return "숨기기"
        }
    }

    public static func of(_ key: String?) -> CourseMode { allCases.first { $0.key == key } ?? .SHOW }
}

public struct CourseFilter: Hashable, Sendable {
    public var modes: [Int64: CourseMode]
    /// 정한 과목의 이름(ID 없이 과목명으로 넣은 보강 수업을 찾을 때, 목록에서 빠진 과목을 보일 때 쓴다).
    public var names: [Int64: String]
    /// 시간표·오늘 수업에서도 숨긴 과목 ID. [CourseMode.HIDE]인 과목에서만 뜻이 있다.
    public var hideTimetable: Set<Int64>

    public init(_ modes: [Int64: CourseMode] = [:], _ names: [Int64: String] = [:], _ hideTimetable: Set<Int64> = []) {
        self.modes = modes
        self.names = names
        self.hideTimetable = hideTimetable
    }

    public func mode(_ courseId: Int64?) -> CourseMode {
        guard let id = courseId, id > 0 else { return .SHOW }
        return modes[id] ?? .SHOW
    }
    public func muted(_ courseId: Int64?) -> Bool { mode(courseId) != .SHOW }
    public func hidden(_ courseId: Int64?) -> Bool { mode(courseId) == .HIDE }

    public var isEmpty: Bool { modes.values.allSatisfy { $0 == .SHOW } }
    public var mutedCount: Int { modes.values.filter { $0 == .MUTE }.count }
    public var hiddenCount: Int { modes.values.filter { $0 == .HIDE }.count }
    public var timetableHiddenCount: Int { modes.filter { $0.value == .HIDE && hideTimetable.contains($0.key) }.count }

    /// 과목 모드 바꾸기. 새로 숨기면 "시간표에서도 숨기기"를 [hideFromTimetable](기본 켬)로 정하고, 이미 숨긴 과목이면 그대로 둔다.
    /// 숨기기를 풀면 플래그도 지운다.
    public func with(_ course: CourseRef, _ mode: CourseMode, hideFromTimetable: Bool = true) -> CourseFilter {
        if course.id <= 0 { return self }
        var m = modes, n = names, h = hideTimetable
        switch mode {
        case .SHOW:
            m[course.id] = nil
            n[course.id] = nil
            h.remove(course.id)
        case .HIDE:
            let was = hidden(course.id)
            m[course.id] = mode
            n[course.id] = course.name
            if !was { if hideFromTimetable { h.insert(course.id) } else { h.remove(course.id) } }
        case .MUTE:
            m[course.id] = mode
            n[course.id] = course.name
            h.remove(course.id)
        }
        return CourseFilter(m, n, h)
    }

    /// 숨긴 과목의 "시간표·오늘 수업에서도 숨기기" 켜고 끄기. 숨긴 과목이 아니면 그대로.
    public func withTimetable(_ courseId: Int64, _ hide: Bool) -> CourseFilter {
        if !hidden(courseId) { return self }
        var c = self
        if hide { c.hideTimetable.insert(courseId) } else { c.hideTimetable.remove(courseId) }
        return c
    }

    // ---- 과제·공지 --------------------------------------------------------------

    /// 알림 대상만(끈 과목·숨긴 과목 제외).
    public func alertable(_ s: Snapshot) -> Snapshot {
        if isEmpty { return s }
        var c = s
        c.assignments = s.assignments.filter { !muted($0.courseId) }
        c.announcements = s.announcements.filter { !muted($0.courseId) }
        return c
    }

    /// 끈 과목·숨긴 과목 것만(중복 방지 키를 조용히 기록하는 데 쓴다).
    public func mutedPart(_ s: Snapshot) -> Snapshot {
        var c = s
        c.assignments = s.assignments.filter { muted($0.courseId) }
        c.announcements = s.announcements.filter { muted($0.courseId) }
        c.errors = []
        return c
    }

    /// 목록·브리핑용(숨긴 과목 제외). 숨긴 과목의 조회 오류(`강의명: …`)도 뺀다.
    public func visible(_ s: Snapshot) -> Snapshot {
        if hiddenCount == 0 { return s }
        let hiddenNames = Set(modes.filter { $0.value == .HIDE }.keys.compactMap { names[$0] } + s.assignments.filter { hidden($0.courseId) }.map(\.courseName))
        var c = s
        c.assignments = visibleAssignments(s.assignments)
        c.announcements = s.announcements.filter { !hidden($0.courseId) }
        c.errors = s.errors.filter { !($0.contains(":") && hiddenNames.contains($0.substringBefore(":"))) }
        return c
    }

    public func visibleAssignments(_ items: [AssignmentItem]) -> [AssignmentItem] {
        hiddenCount == 0 ? items : items.filter { !hidden($0.courseId) }
    }

    // ---- 수업 -------------------------------------------------------------------

    /// 수업 행의 과목: ID로 찾고, ID가 없으면(과목명으로 넣은 보강) 정해 둔 과목 이름과 맞춰 본다.
    public func classMode(_ row: TimetableLogic.ClassRow) -> CourseMode {
        if let id = row.courseId { return mode(id) }
        return modes.filter { $0.value != .SHOW && TimetableLogic.sameCourse(row.course, names[$0.key]) }.map(\.value).max { $0.rawValue < $1.rawValue } ?? .SHOW
    }

    /// 이 과목을 시간표·오늘 수업에서 뺄지. 직접 추가한 수업(음수 ID)·ID 없는 줄은 false.
    public func hidesTimetable(_ courseId: Int64?) -> Bool {
        guard let id = courseId else { return false }
        return id > 0 && hidden(id) && hideTimetable.contains(id)
    }

    /// 시간표에서 숨긴 과목 이름과 맞는지(과목명으로만 넣은 보강·휴강).
    private func hidesTimetableName(_ name: String?) -> Bool {
        hideTimetable.contains { hidesTimetable($0) && TimetableLogic.sameCourse(name, names[$0]) }
    }

    /// 수업 줄을 시간표에서 뺄지: ID로 보고, ID가 없으면 숨긴 과목 이름과 맞춰 본다.
    public func hidesClass(_ row: TimetableLogic.ClassRow) -> Bool {
        if let id = row.courseId { return hidesTimetable(id) }
        return hidesTimetableName(row.course)
    }

    /// 시간표·오늘 수업에 보일 하루(수업과 휴강 줄 모두).
    public func timetableDay(_ day: TimetableLogic.Day) -> TimetableLogic.Day {
        if timetableHiddenCount == 0 { return day }
        var d = day
        d.classes = day.classes.filter { !hidesClass($0) }
        d.cancelled = day.cancelled.filter { !hidesClass($0) }
        return d
    }

    /// 시간표 화면 격자에 보일 주간 수업.
    public func timetableWeekly(_ w: TimetableLogic.Weekly?) -> TimetableLogic.Weekly? {
        guard let w, timetableHiddenCount != 0 else { return w }
        var c = w
        c.courses = w.courses.filter { !hidesTimetable($0.courseId) }
        return c
    }

    /// 시간표 화면의 보강·휴강 목록. 과목 ID가 없는 항목은 [weekly]의 과목과 먼저 맞춰 보고,
    /// 맞는 과목이 없을 때만 숨긴 과목 이름과 비교한다.
    public func timetableChanges(_ changes: [TimetableLogic.Change], _ weekly: TimetableLogic.Weekly?) -> [TimetableLogic.Change] {
        if timetableHiddenCount == 0 { return changes }
        return changes.filter { ch in
            let id = ch.courseId ?? weekly?.courses.first { TimetableLogic.sameCourse(ch.course, $0.name) }?.courseId
            if let id { return !hidesTimetable(id) }
            return !hidesTimetableName(ch.course)
        }
    }

    /// 수업 알림 대상만 남긴 하루.
    public func alertDay(_ day: TimetableLogic.Day) -> TimetableLogic.Day {
        if isEmpty { return day }
        var d = day
        d.classes = day.classes.filter { classMode($0) == .SHOW }
        return d
    }
}

/// 끈 과목을 뺀 마감·공지 알림 계획. 끈 과목에서 나왔을 알림의 키는 [Plan.silentKeys]로 넘겨 "보낸 것"으로만 기록한다. 순수 함수.
public enum CourseMute {
    public static func plan(_ snapshot: Snapshot, _ state: NotifyState, _ now: Date, _ settings: NotifySettings, _ filter: CourseFilter,
                            _ zone: TimeZone = .current) -> Plan {
        if filter.isEmpty { return NotificationPlanner.plan(snapshot, state, now, settings, zone) }
        var main = NotificationPlanner.plan(filter.alertable(snapshot), state, now, settings, zone)
        let muted = NotificationPlanner.plan(filter.mutedPart(snapshot), state, now, settings, zone)
        main.silentKeys = distinct(main.silentKeys + muted.silentKeys + muted.notices.flatMap(\.firedKeys))
        return main
    }
}

public enum CourseFilterLogic {
    public static func toJson(_ f: CourseFilter, _ known: [CourseRef], _ knownAt: Int64) -> String {
        let courses = JSONObject()
        for id in f.modes.keys.sorted() {
            let m = f.modes[id]!
            if m == .SHOW { continue }
            let o = JSONObject().put("mode", m.key).put("name", f.names[id] ?? "")
            if m == .HIDE { o.put("timetable", f.hideTimetable.contains(id)) }
            courses.put(String(id), o)
        }
        let arr = JSONArray()
        for c in known { arr.put(JSONObject().put("id", c.id).put("name", c.name)) }
        return JSONObject().put("version", 1).put("courses", courses).put("known", arr).put("knownAt", knownAt).toString()
    }

    public struct Stored: Hashable, Sendable {
        public var filter: CourseFilter
        public var known: [CourseRef]
        public var knownAt: Int64
        public init(_ filter: CourseFilter, _ known: [CourseRef], _ knownAt: Int64) {
            self.filter = filter
            self.known = known
            self.knownAt = knownAt
        }
    }

    public static func fromJson(_ s: String?) -> Stored {
        guard let s, let o = try? JSONObject(s) else { return Stored(CourseFilter(), [], 0) }
        var modes: [Int64: CourseMode] = [:]
        var names: [Int64: String] = [:]
        var hideTimetable = Set<Int64>()
        if let c = o.optJSONObject("courses") {
            for k in c.keys() {
                guard let id = Int64(k), id > 0, let x = c.optJSONObject(k) else { continue }
                let m = CourseMode.of(x.optString("mode"))
                if m == .SHOW { continue }
                modes[id] = m
                names[id] = x.optString("name")
                // 플래그가 없는 옛 저장값은 시간표에 보이던 그대로(false).
                if m == .HIDE && x.optBoolean("timetable", false) { hideTimetable.insert(id) }
            }
        }
        let arr = o.optJSONArray("known") ?? JSONArray()
        let known: [CourseRef] = (0..<arr.length()).compactMap { i in
            guard let x = arr.optJSONObject(i) else { return nil }
            let id = x.optLong("id")
            return id > 0 ? CourseRef(id, x.optString("name")) : nil
        }
        return Stored(CourseFilter(modes, names, hideTimetable), known, o.optLong("knownAt"))
    }

    /// 설정 화면에 보일 과목 한 줄. 끄거나 숨겨 둔 과목이 목록에 없으면 되돌릴 수 있게 맨 뒤에 붙인다([stale]).
    public struct CourseRow: Hashable, Sendable {
        public var course: CourseRef
        public var mode: CourseMode
        public var stale: Bool
        public init(_ course: CourseRef, _ mode: CourseMode, _ stale: Bool) {
            self.course = course
            self.mode = mode
            self.stale = stale
        }
    }

    private static func byName(_ a: CourseRef, _ b: CourseRef) -> Bool {
        let x = TimetableLogic.shortName(a.name), y = TimetableLogic.shortName(b.name)
        if x != y { return x.kLess(y) }
        return a.id < b.id
    }

    public static func rows(_ known: [CourseRef], _ fallback: [CourseRef], _ filter: CourseFilter) -> [CourseRow] {
        // 과목명 순으로 고정한다.
        let base = stableSorted(distinctBy((known.isEmpty ? fallback : known).filter { $0.id > 0 && !$0.name.kIsBlank }) { $0.id }, by: byName)
        let ids = Set(base.map(\.id))
        let extra = stableSorted(filter.modes.filter { $0.value != .SHOW && !ids.contains($0.key) }.keys.sorted()
            .map { id in CourseRef(id, filter.names[id].flatMap { $0.nilIfBlank } ?? "과목 \(id)") }, by: byName)
        return base.map { CourseRow($0, filter.mode($0.id), false) } + extra.map { CourseRow($0, filter.mode($0.id), true) }
    }

    /// 화면을 보는 중에 목록을 새로 받았을 때: 이미 보이던 과목은 보이던 순서 그대로 두고, 새 과목만 끝에 붙인다.
    public static func keepOrder(_ shown: [Int64], _ rows: [CourseRow]) -> [CourseRow] {
        if shown.isEmpty { return rows }
        var pos: [Int64: Int] = [:]
        for (i, id) in shown.enumerated() { pos[id] = i }
        let old = rows.filter { pos[$0.course.id] != nil }
        let new = rows.filter { pos[$0.course.id] == nil }
        return stableSorted(old) { pos[$0.course.id]! < pos[$1.course.id]! } + new
    }

    /// 받아 둔 과제·시간표에서 과목 모으기(이름순).
    public static func fallbackCourses(_ assignments: [AssignmentItem], _ timetable: [TimetableLogic.CourseMeetings]) -> [CourseRef] {
        let all = timetable.filter { $0.courseId > 0 }.map { CourseRef($0.courseId, $0.name) } +
            assignments.filter { $0.courseId > 0 }.map { CourseRef($0.courseId, $0.courseName) }
        return stableSorted(distinctBy(all.filter { !$0.name.kIsBlank }) { $0.id }) { $0.name.kLess($1.name) }
    }

    /// 설정 화면 요약 한 줄.
    public static func summary(_ f: CourseFilter) -> String {
        if f.isEmpty { return "모든 과목 알림 받는 중" }
        var parts: [String] = []
        if f.mutedCount > 0 { parts.append("알림 끈 과목 \(f.mutedCount)개") }
        if f.hiddenCount > 0 {
            parts.append("숨긴 과목 \(f.hiddenCount)개" + (f.timetableHiddenCount > 0 ? "(시간표에서도 \(f.timetableHiddenCount)개)" : ""))
        }
        return parts.joined(separator: " · ")
    }
}

/// 과목별 알림 설정 저장소(Kotlin prefs `course_filter`의 `state`). 바꿀 때마다 [EtlEvents.dataChanged]와 [onChanged]를 부른다.
public final class CourseFilterStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSRecursiveLock()
    public var onChanged: (@Sendable () -> Void)?

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "course_filter"), onChanged: (@Sendable () -> Void)? = nil) {
        self.prefs = prefs
        self.onChanged = onChanged
    }

    public func loadAll() -> CourseFilterLogic.Stored { lock.withLock { CourseFilterLogic.fromJson(prefs.string("state")) } }
    public func load() -> CourseFilter { loadAll().filter }

    public func setMode(_ course: CourseRef, _ mode: CourseMode) {
        lock.withLock {
            var s = loadAll()
            s.filter = s.filter.with(course, mode)
            save(s)
        }
        EtlEvents.dataChanged()
        onChanged?()
    }

    /// 숨긴 과목의 "시간표·오늘 수업에서도 숨기기".
    public func setHideTimetable(_ courseId: Int64, _ hide: Bool) {
        lock.withLock {
            var s = loadAll()
            s.filter = s.filter.withTimetable(courseId, hide)
            save(s)
        }
        EtlEvents.dataChanged()
        onChanged?()
    }

    /// 최근에 받은 이번 학기 과목 목록. 정해 둔 과목의 이름도 새 이름으로 맞춘다.
    public func saveKnown(_ courses: [CourseRef], _ at: Int64) {
        lock.withLock {
            let s = loadAll()
            var byId: [Int64: CourseRef] = [:]
            for c in courses { byId[c.id] = c }
            var f = s.filter
            f.names = s.filter.names.reduce(into: [:]) { acc, kv in acc[kv.key] = byId[kv.key]?.name ?? kv.value }
            save(CourseFilterLogic.Stored(f, courses, at))
        }
    }

    private func save(_ s: CourseFilterLogic.Stored) {
        prefs.set(CourseFilterLogic.toJson(s.filter, s.known, s.knownAt), for: "state")
    }
}
