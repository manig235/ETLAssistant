import ETLKit
import Foundation

/// 앱이 App Group 폴더에 써 두는 위젯용 자료(안드로이드는 위젯이 앱 저장소를 바로 읽는다; iOS 위젯은 다른 프로세스라 사본을 쓴다).
/// 위젯은 이 사본만 읽고 네트워크를 쓰지 않는다. 숨긴 과목은 이미 뺐고, 시간표에서 숨길 과목은 [courseFilter]로 그때그때 뺀다.
public struct WidgetSnapshot: Codable, Hashable, Sendable {
    public static let VERSION = 1

    public var version: Int
    public var writtenAt: Date
    /// 마지막 과제 조회 시각. nil이면 저장된 과제 정보가 없다("저장된 과제 정보가 없어요").
    public var assignmentsTakenAt: Date?
    /// 과목별 알림에서 숨긴 과목을 뺀, 제출 전·마감 전 과제.
    public var assignments: [AssignmentItem]
    /// 내 마감·시험(`MyDeadlines.toJson` 형식). 반복 회차는 위젯이 그때그때 펼친다.
    public var mine: String
    /// 수강편람 + 직접 등록한 수업.
    public var weekly: TimetableLogic.Weekly?
    /// 이번 주 월요일부터 8일 뒤까지의 휴강·보강.
    public var changes: [TimetableLogic.Change]
    /// 과목별 알림 상태(`CourseFilterLogic.toJson` 형식). 시간표·다음 수업은 "시간표에서도 숨기기"를 켠 과목만 뺀다.
    public var courseFilter: String

    public init(version: Int = WidgetSnapshot.VERSION, writtenAt: Date, assignmentsTakenAt: Date?, assignments: [AssignmentItem],
                mine: String, weekly: TimetableLogic.Weekly?, changes: [TimetableLogic.Change], courseFilter: String) {
        self.version = version
        self.writtenAt = writtenAt
        self.assignmentsTakenAt = assignmentsTakenAt
        self.assignments = assignments
        self.mine = mine
        self.weekly = weekly
        self.changes = changes
        self.courseFilter = courseFilter
    }

    /// 앱 저장소 값으로 만든다. 지난·제출한 과제, 완료한 한 번뿐인 마감, 지난 주 보강·휴강은 넣지 않는다.
    public static func make(snapshot: CachedSnapshot?, mine: [MyDeadline], weekly: TimetableLogic.Weekly?,
                            changes: [TimetableLogic.Change], filter: CourseFilter, now: Date) -> WidgetSnapshot {
        let assignments = filter.visibleAssignments(snapshot?.assignments ?? []).filter { a in
            guard let due = a.due else { return false }
            return !a.submitted && due > now
        }
        let keptMine = mine.filter { $0.repeat != nil || (!$0.done && $0.due > now) }
        let today = EtlLogic.kstParts(now).day
        let monday = today.previousOrSame(.monday)
        let last = today.plusDays(8)
        let keptChanges = changes.filter { $0.date >= monday && $0.date <= last }
        return WidgetSnapshot(writtenAt: now, assignmentsTakenAt: snapshot.map { Date(epochMilli: $0.takenAt) }, assignments: assignments,
                              mine: MyDeadlines.toJson(keptMine), weekly: weekly, changes: keptChanges,
                              courseFilter: CourseFilterLogic.toJson(filter, [], 0))
    }

    public func encoded() -> Data {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        e.outputFormatting = [.sortedKeys]
        return (try? e.encode(self)) ?? Data()
    }

    /// 읽지 못하거나 다른 판이면 nil.
    public static func decode(_ data: Data?) -> WidgetSnapshot? {
        guard let data, !data.isEmpty else { return nil }
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        guard let s = try? d.decode(WidgetSnapshot.self, from: data), s.version == VERSION else { return nil }
        return s
    }

    // ---- 위젯이 쓰는 값 ----

    public var myDeadlines: [MyDeadline] { MyDeadlines.fromJson(mine) }
    public var filter: CourseFilter { CourseFilterLogic.fromJson(courseFilter).filter }

    public func deadlineItems(_ now: Date) -> [DeadlineWidgetLogic.Item] {
        DeadlineWidgetLogic.items(assignments, myDeadlines, now)
    }

    public func week(_ now: Date) -> TimetableWidgetLogic.Week {
        TimetableWidgetLogic.week(weekly, changes, EtlLogic.kstParts(now).day, filter)
    }

    public func nextClass(_ now: Date) -> TimetableWidgetLogic.NextClass? {
        TimetableWidgetLogic.nextClass(weekly, changes, now, filter)
    }

    /// 위젯의 완료 버튼: 그 회차(반복이 아니면 항목)를 완료로 둔 사본. 이미 완료했거나 없으면 그대로.
    public func markingDone(_ id: Int64, _ occurrence: DayDate?, _ now: Date) -> WidgetSnapshot {
        var list = myDeadlines
        guard let i = list.firstIndex(where: { $0.id == id && !$0.done }) else { return self }
        list[i] = MyDeadlines.setDone(list[i], occurrence, true, now)
        var c = self
        c.mine = MyDeadlines.toJson(list)
        return c
    }
}

/// 위젯에서 누른 "완료"(앱이 다음에 활성화될 때 내 마감 저장소에 반영한다).
public struct WidgetCompletion: Codable, Hashable, Sendable {
    public var id: Int64
    public var occurrence: DayDate?
    public var at: Date

    public init(id: Int64, occurrence: DayDate?, at: Date) {
        self.id = id
        self.occurrence = occurrence
        self.at = at
    }

    /// 저장된 목록에 반영한 결과. 바뀐 항목만 돌려준다. 항상 완료로 둔다(두 번 눌러도 미완료로 돌아가지 않는다). 반복 마감은 그 회차만.
    public static func apply(_ completions: [WidgetCompletion], to list: [MyDeadline]) -> [MyDeadline] {
        var current = list
        var changed: [Int64: MyDeadline] = [:]
        for c in completions {
            guard let i = current.firstIndex(where: { $0.id == c.id && !$0.done }) else { continue }
            let updated = MyDeadlines.setDone(current[i], c.occurrence, true, c.at)
            if updated != current[i] {
                current[i] = updated
                changed[c.id] = updated
            }
        }
        return current.filter { changed[$0.id] != nil }
    }

    public func encoded() -> Data {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        return (try? e.encode(self)) ?? Data()
    }

    public static func decode(_ data: Data?) -> WidgetCompletion? {
        guard let data else { return nil }
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return try? d.decode(WidgetCompletion.self, from: data)
    }
}

/// 위젯 타임라인 시각(Kotlin `HomeWidgets.render`의 경계 예약 + 15분 주기 작업).
public enum WidgetTimelineLogic {
    /// [now]부터 [horizon] 안의 다시 그릴 시각: 15분마다, 마감 시각, 다음 수업 전환, KST 자정. 오름차순·중복 없음. 첫 값은 [now].
    public static func entryDates(_ s: WidgetSnapshot?, _ now: Date, horizon: TimeInterval = 6 * 3600, step: TimeInterval = 15 * 60) -> [Date] {
        let end = now.addingTimeInterval(horizon)
        var dates: [Date] = [now]
        var t = now.addingTimeInterval(step)
        while t <= end {
            dates.append(t)
            t = t.addingTimeInterval(step)
        }
        var midnight = EtlLogic.kstParts(now).day.plusDays(1).startOfDay
        while midnight <= end {
            dates.append(midnight)
            midnight = midnight.addingTimeInterval(86_400)
        }
        if let s {
            for item in s.deadlineItems(now) where item.due <= end { dates.append(item.due) }
            var at = now
            for _ in 0..<64 {
                guard let next = s.nextClass(at), next.switchesAt <= end, next.switchesAt > at else { break }
                dates.append(next.switchesAt)
                at = next.switchesAt
            }
        }
        var out: [Date] = []
        for d in dates.sorted() where d >= now {
            if let last = out.last, d.timeIntervalSince(last) < 1 { continue }
            out.append(d)
        }
        return out
    }
}
