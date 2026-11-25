import ETLKit
import Foundation

/// 시간표 위젯 격자의 배치(Kotlin `chat/TimetableGridLogic`). 앱의 `TimetableGridLogic`(앱 타깃)과 같은 규칙을
/// 위젯 확장에서도 쓰도록 NotifyKit에 둔다. 화면 크기와 독립적이다.
public enum WidgetGridLogic {
    public struct Block: Hashable, Sendable {
        public var course: TimetableLogic.CourseMeetings
        public var meeting: TimetableLogic.Meeting
        public var startMinute: Int
        public var endMinute: Int
        public var lane = 0
        public var lanes = 1
        public var duration: Int { endMinute - startMinute }
        public init(course: TimetableLogic.CourseMeetings, meeting: TimetableLogic.Meeting, startMinute: Int, endMinute: Int,
                    lane: Int = 0, lanes: Int = 1) {
            self.course = course
            self.meeting = meeting
            self.startMinute = startMinute
            self.endMinute = endMinute
            self.lane = lane
            self.lanes = lanes
        }
    }

    public struct Grid: Hashable, Sendable {
        public var days: [String]
        public var startHour: Int
        public var endHour: Int
        public var blocks: [Block]
        public init(days: [String], startHour: Int, endHour: Int, blocks: [Block]) {
            self.days = days
            self.startHour = startHour
            self.endHour = endHour
            self.blocks = blocks
        }
    }

    private static func minutes(_ time: String) -> Int {
        (Int(time.prefix(2)) ?? 0) * 60 + (Int(time.suffix(2)) ?? 0)
    }

    private static func dayIndex(_ d: String) -> Int { TimetableLogic.weekdayLabels.firstIndex(of: d) ?? -1 }

    /// 월~금은 항상, 수업이 있으면 주말 열까지. 시간 축은 9~18시를 기본으로 수업에 맞춰 넓힌다. 겹치는 수업은 열을 나눈다.
    public static func build(_ courses: [TimetableLogic.CourseMeetings]) -> Grid {
        var seen = Set<[Int64]>()
        let valid: [Block] = courses.flatMap { course in
            course.meetings.compactMap { meeting -> Block? in
                guard meeting.day.count == 1, dayIndex(meeting.day) >= 0,
                      let start = TimetableLogic.normTime(meeting.start), let end = TimetableLogic.normTime(meeting.end), start < end else { return nil }
                var m = meeting
                m.start = start
                m.end = end
                return Block(course: course, meeting: m, startMinute: minutes(start), endMinute: minutes(end))
            }
        }.filter { seen.insert([$0.course.courseId, Int64(dayIndex($0.meeting.day)), Int64($0.startMinute), Int64($0.endMinute)]).inserted }
        let dayCount = max(5, (valid.map { dayIndex($0.meeting.day) }.max() ?? 4) + 1)
        let days = Array(TimetableLogic.weekdayLabels.prefix(dayCount))
        var positioned: [Block] = []
        for day in days {
            var group: [Block] = []
            var groupEnd = -1
            func flush() {
                var ends: [Int] = []
                let assigned: [Block] = group.map { block in
                    let lane: Int
                    if let free = ends.firstIndex(where: { $0 <= block.startMinute }) { lane = free } else { lane = ends.count; ends.append(0) }
                    ends[lane] = block.endMinute
                    var b = block
                    b.lane = lane
                    return b
                }
                positioned += assigned.map { var b = $0; b.lanes = ends.count; return b }
                group.removeAll()
            }
            let sorted = valid.filter { $0.meeting.day == day }.sorted { a, b in
                if a.startMinute != b.startMinute { return a.startMinute < b.startMinute }
                if a.endMinute != b.endMinute { return a.endMinute < b.endMinute }
                return a.course.courseId < b.course.courseId
            }
            for block in sorted {
                if !group.isEmpty && block.startMinute >= groupEnd { flush() }
                groupEnd = group.isEmpty ? block.endMinute : max(groupEnd, block.endMinute)
                group.append(block)
            }
            if !group.isEmpty { flush() }
        }
        return Grid(
            days: days,
            startHour: min(9, valid.map { $0.startMinute / 60 }.min() ?? 9),
            endHour: min(max(18, valid.map { ($0.endMinute + 59) / 60 }.max() ?? 18), 24),
            blocks: positioned
        )
    }
}
