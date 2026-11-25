import ETLKit
import Foundation

/// 홈 화면 위젯의 순수 규칙(Kotlin `widget/WidgetLogic.kt`의 `DeadlineWidgetLogic`).
/// 위젯은 내 일정의 기간/지난 마감 필터와 독립적으로, 앞으로 할 일만 마감순으로 보여 준다.
public enum DeadlineWidgetLogic {
    public struct Item: Hashable, Sendable, Codable {
        public var key: String
        public var title: String
        public var source: String
        public var due: Date
        public var url: String?
        public var mineId: Int64?
        /// 반복 내 마감이면 그 회차 날짜(KST). 완료 버튼이 이 회차만 완료로 표시한다.
        public var mineDate: DayDate?
        /// 시험이면 완료 버튼을 두지 않는다.
        public var exam: Bool

        public init(_ key: String, _ title: String, _ source: String, _ due: Date, url: String? = nil, mineId: Int64? = nil,
                    mineDate: DayDate? = nil, exam: Bool = false) {
            self.key = key
            self.title = title
            self.source = source
            self.due = due
            self.url = url
            self.mineId = mineId
            self.mineDate = mineDate
            self.exam = exam
        }
    }

    public static func items(_ assignments: [AssignmentItem], _ mine: [MyDeadline], _ now: Date) -> [Item] {
        let etl: [Item] = assignments.compactMap { a in
            guard let due = a.due else { return nil }
            if a.submitted || !(due > now) { return nil }
            return Item("etl-\(a.courseId)-\(a.assignmentId)", a.name, TimetableLogic.shortName(a.courseName), due, url: assignmentUrl(a))
        }
        let mineItems: [Item] = MyDeadlines.active(mine, now).filter { !$0.done && $0.due > now }.map { d in
            // 반복 마감은 다음 회차 하나만 보인다.
            // 시험은 "[시험] 과목 제목"과 장소로 구분한다(시작 시각까지 보인다).
            if d.isExam {
                return Item("exam-\(d.id)", MyDeadlines.examTitle(d), ["시험", d.location].filter { !$0.kIsBlank }.joined(separator: " · "), d.due,
                            mineId: d.id, exam: true)
            }
            return Item("mine-\(d.id)" + (d.occurrence.map { "-\($0)" } ?? ""), d.title, "내 마감", d.due, mineId: d.id, mineDate: d.occurrence)
        }
        return distinctBy(etl + mineItems) { $0.key }.sorted { a, b in
            if a.due != b.due { return a.due < b.due }
            return a.key.utf16.lexicographicallyPrecedes(b.key.utf16)
        }
    }

    /// 과제 링크는 eTL 안에서만 연다. 응답에 링크가 없으면 과제 ID로 복원한다.
    public static func assignmentUrl(_ a: AssignmentItem) -> String {
        let fallback = "https://myetl.snu.ac.kr/courses/\(a.courseId)/assignments/\(a.assignmentId)"
        guard let raw = a.url, let uri = URLComponents(string: raw) else { return fallback }
        let portOk = uri.port == nil || uri.port == 443
        return uri.scheme == "https" && uri.host == "myetl.snu.ac.kr" && uri.user == nil && uri.password == nil && portOk ? raw : fallback
    }

    /// 별도의 개수 상한 없이 실제 높이와 글자 크기에 들어가는 만큼 채운다.
    public static func capacity(_ heightDp: Double, _ fontScale: Double, compact: Bool = false) -> Int {
        let scale = max(fontScale, 1)
        let available = heightDp - (compact ? 12 : 14 + 22 * scale)
        let row = (compact ? 32.0 : 48.0) * scale
        return max(Int(((available + 1) / (row + 1)).rounded(.towardZero)), 1)
    }

    public static func dueLabel(_ due: Date, _ now: Date) -> String {
        let at = EtlLogic.kstParts(due)
        let today = EtlLogic.kstParts(now).day
        let date: String
        switch at.day {
        case today: date = "오늘"
        case today.plusDays(1): date = "내일"
        default: date = "\(at.day.monthValue)/\(at.day.dayOfMonth)"
        }
        return date + String(format: " %02d:%02d", at.hour, at.minute)
    }

    public static func remaining(_ due: Date, _ now: Date) -> String {
        let minutes = max(Int64((due.timeIntervalSince(now) / 60).rounded(.towardZero)), 0)
        if minutes < 1 { return "곧 마감" }
        if minutes < 60 { return "\(minutes)분 남음" }
        if minutes < 1440 { return "\(minutes / 60)시간 \(minutes % 60)분 남음" }
        return "\(minutes / 1440)일 \(minutes % 1440 / 60)시간 남음"
    }

    /// 마감 경계와 KST 자정에는 저장된 정보만 다시 그린다. 네트워크 조회는 하지 않는다.
    public static func nextBoundary(_ items: [Item], _ now: Date) -> Date {
        let midnight = EtlLogic.kstParts(now).day.plusDays(1).startOfDay
        guard let due = items.first(where: { $0.due > now })?.due else { return midnight }
        return min(midnight, due)
    }
}

/// 시간표·다음 수업 위젯의 순수 규칙(Kotlin `widget/WidgetLogic.kt`의 `TimetableWidgetLogic`).
public enum TimetableWidgetLogic {
    public struct Entry: Hashable, Sendable {
        public var date: DayDate
        public var row: TimetableLogic.ClassRow
        public var cancelled: Bool
        public init(_ date: DayDate, _ row: TimetableLogic.ClassRow, _ cancelled: Bool) {
            self.date = date
            self.row = row
            self.cancelled = cancelled
        }
    }

    public struct Week: Hashable, Sendable {
        public var monday: DayDate
        public var entries: [Entry]
        public var grid: WidgetGridLogic.Grid
        /// 격자 블록의 ID(1부터) → 이번 주 항목. 원래 과목 ID는 색상과 접근성 설명에 쓴다.
        public var byGridId: [Int64: Entry]
    }

    public struct NextClass: Hashable, Sendable {
        public var entry: Entry
        public var startsAt: Date
        public var switchesAt: Date
    }

    /// `LocalTime.parse`: `HH:mm` 또는 `HH:mm:ss`. 아니면 nil.
    static func parseTime(_ s: String) -> (Int, Int, Int)? {
        let parts = s.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2 || parts.count == 3, parts.allSatisfy({ $0.count == 2 && $0.allSatisfy(\.isASCII) && Int($0) != nil }),
              let h = Int(parts[0]), let m = Int(parts[1]), (0...23).contains(h), (0...59).contains(m) else { return nil }
        let sec = parts.count == 3 ? Int(parts[2])! : 0
        guard (0...59).contains(sec) else { return nil }
        return (h, m, sec)
    }

    /// 시작 30분 뒤(더 짧은 수업은 종료 때) 다음 수업으로 넘긴다. 휴강은 제외한다.
    public static func nextClass(_ weekly: TimetableLogic.Weekly?, _ changes: [TimetableLogic.Change], _ now: Date,
                                 _ filter: CourseFilter = CourseFilter()) -> NextClass? {
        let today = EtlLogic.kstParts(now).day
        var best: NextClass?
        for offset in 0...7 {
            let date = today.plusDays(offset)
            for row in filter.timetableDay(TimetableLogic.day(weekly, date, changes)).classes {
                guard let s = parseTime(row.start), let e = parseTime(row.end) else { continue }
                let start = date.atTime(s.0, s.1, s.2)
                let end = date.atTime(e.0, e.1, e.2)
                if !(end > start) { continue }
                let cutoff = min(end, start.addingTimeInterval(30 * 60))
                guard cutoff > now else { continue }
                if best == nil || start < best!.startsAt { best = NextClass(entry: Entry(date, row, false), startsAt: start, switchesAt: cutoff) }
            }
        }
        return best
    }

    /// [filter]: 과목별 알림에서 "시간표에서도 숨기기"를 켠 과목은 뺀다.
    public static func week(_ weekly: TimetableLogic.Weekly?, _ changes: [TimetableLogic.Change], _ today: DayDate,
                            _ filter: CourseFilter = CourseFilter()) -> Week {
        let monday = today.previousOrSame(.monday)
        let entries: [Entry] = (0...6).flatMap { offset -> [Entry] in
            let date = monday.plusDays(offset)
            let day = filter.timetableDay(TimetableLogic.day(weekly, date, changes))
            return day.classes.map { Entry(date, $0, false) } + day.cancelled.map { Entry(date, $0, true) }
        }
        // 같은 과목의 정규/보강 블록도 각각 배치한다. 원래 ID는 색상과 접근성 설명에 유지한다.
        var byId: [Int64: Entry] = [:]
        var courses: [TimetableLogic.CourseMeetings] = []
        for (i, entry) in entries.enumerated() {
            let id = Int64(i + 1)
            byId[id] = entry
            courses.append(TimetableLogic.CourseMeetings(id, entry.row.course, [
                TimetableLogic.Meeting(TimetableLogic.weekdayOf(entry.date), entry.row.start, entry.row.end, entry.row.room),
            ]))
        }
        return Week(monday: monday, entries: entries, grid: WidgetGridLogic.build(courses), byGridId: byId)
    }
}

/// 위젯 표시 보조 규칙(문구·색 선택). 화면 코드와 독립적이다.
public enum WidgetText {
    /// 미정인 강의실은 생략한다.
    public static func room(_ raw: String?) -> String? {
        guard let r = raw?.kTrim, !r.isEmpty, r != "미정", r != "강의실 미정" else { return nil }
        return r
    }

    /// 다음 수업 두 번째 줄: `{오늘|내일|{요}요일} {HH:mm}[ · {강의실}]`.
    public static func nextClassDetail(_ next: TimetableWidgetLogic.NextClass, _ now: Date) -> String {
        let today = EtlLogic.kstParts(now).day
        let day: String
        switch next.entry.date {
        case today: day = "오늘"
        case today.plusDays(1): day = "내일"
        default: day = TimetableLogic.weekdayOf(next.entry.date) + "요일"
        }
        return "\(day) \(next.entry.row.start)" + (room(next.entry.row.room).map { " · \($0)" } ?? "")
    }

    /// 시간표 블록 글: `휴강 · {과목}` / `보강 · {과목}` / `{과목}`.
    public static func blockTitle(_ entry: TimetableWidgetLogic.Entry) -> String {
        (entry.cancelled ? "휴강 · " : entry.row.kind == "보강" ? "보강 · " : "") + entry.row.course
    }

    /// 앱 시간표와 같은 과목색 고르기: Kotlin `Math.floorMod(id.hashCode(), n)`(`Long.hashCode() = (v xor (v ushr 32)).toInt()`).
    /// 과목 ID가 없으면(과목명으로만 넣은 보강) 과목명의 Java `String.hashCode()`.
    public static func colorIndex(_ courseId: Int64?, _ name: String, _ count: Int) -> Int {
        let h: Int32
        if let id = courseId {
            let u = UInt64(bitPattern: id)
            h = Int32(truncatingIfNeeded: u ^ (u >> 32))
        } else {
            var x: Int32 = 0
            for c in name.utf16 { x = x &* 31 &+ Int32(c) }
            h = x
        }
        let n = Int(count)
        return ((Int(h) % n) + n) % n
    }
}
