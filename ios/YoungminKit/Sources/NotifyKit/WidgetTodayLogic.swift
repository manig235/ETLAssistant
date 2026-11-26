import ETLKit
import Foundation

/// 시간표 위젯 중간 크기(iOS `systemMedium`)의 "오늘 수업" 목록 규칙. 안드로이드에는 없는 크기라 iOS에만 있다.
/// 주간 격자는 중간 크기에 너무 작아서, 오늘(KST) 수업을 시작순 목록으로 보인다. 휴강으로 빠진 수업은 빼고 보강은 넣는다.
/// 오늘 남은 수업이 없으면 이번 주(일요일까지) 다음 수업 날의 첫 수업을 함께 준다.
public enum TodayClassesWidgetLogic {
    public enum Phase: String, Hashable, Sendable {
        /// 끝난 수업(흐리게).
        case done
        /// 듣는 중(강조).
        case ongoing
        case upcoming
    }

    public struct Row: Hashable, Sendable {
        public var entry: TimetableWidgetLogic.Entry
        public var startsAt: Date
        public var endsAt: Date
        public var phase: Phase
    }

    public struct Today: Hashable, Sendable {
        public var date: DayDate
        /// 오늘 수업(휴강 제외, 보강 포함), 시작순. 시각을 읽을 수 없는 수업은 뺀다(다음 수업 위젯과 같다).
        public var rows: [Row]
        /// 오늘 남은(끝나지 않은) 수업이 없을 때만: 이번 주 안에서 다음 수업 날의 첫 수업. 없으면 nil.
        public var next: TimetableWidgetLogic.Entry?
        public var hasRemaining: Bool { rows.contains { $0.phase != .done } }
    }

    /// 목록에 실제로 그릴 줄. [rows]가 칸보다 많으면 끝난 수업부터 앞에서 빼고, 그래도 많으면 한 칸을 "+N개 더"에 쓴다.
    public struct Window: Hashable, Sendable {
        public var rows: [Row]
        /// 앞에서 뺀 끝난 수업 수.
        public var skippedDone: Int
        /// 뒤에 더 있는 수업 수("+N개 더").
        public var more: Int
    }

    /// [filter]: 과목별 알림에서 "시간표에서도 숨기기"를 켠 과목은 뺀다(격자·다음 수업과 같은 `CourseFilter.timetableDay`).
    public static func today(_ weekly: TimetableLogic.Weekly?, _ changes: [TimetableLogic.Change], _ now: Date,
                             _ filter: CourseFilter = CourseFilter()) -> Today {
        let today = EtlLogic.kstParts(now).day
        let rows = classes(weekly, changes, today, filter).map { r -> Row in
            let phase: Phase = now >= r.endsAt ? .done : now >= r.startsAt ? .ongoing : .upcoming
            return Row(entry: r.entry, startsAt: r.startsAt, endsAt: r.endsAt, phase: phase)
        }
        var result = Today(date: today, rows: rows, next: nil)
        if !result.hasRemaining {
            let sunday = today.nextOrSame(.sunday)
            var date = today.plusDays(1)
            while date <= sunday {
                if let first = classes(weekly, changes, date, filter).first {
                    result.next = first.entry
                    break
                }
                date = date.plusDays(1)
            }
        }
        return result
    }

    private static func classes(_ weekly: TimetableLogic.Weekly?, _ changes: [TimetableLogic.Change], _ date: DayDate,
                                _ filter: CourseFilter) -> [(entry: TimetableWidgetLogic.Entry, startsAt: Date, endsAt: Date)] {
        filter.timetableDay(TimetableLogic.day(weekly, date, changes)).classes.compactMap { row in
            guard let s = TimetableWidgetLogic.parseTime(row.start), let e = TimetableWidgetLogic.parseTime(row.end) else { return nil }
            let start = date.atTime(s.0, s.1, s.2)
            let end = date.atTime(e.0, e.1, e.2)
            guard end > start else { return nil }
            return (TimetableWidgetLogic.Entry(date, row, false), start, end)
        }
    }

    /// 높이(pt)와 글자 배율에 들어가는 줄 수. 머리 22×배율, 줄 24×배율 + 사이 2.
    public static func capacity(_ height: Double, _ fontScale: Double) -> Int {
        let scale = max(fontScale, 1)
        let available = height - 22 * scale
        return max(Int(((available + 2) / (24 * scale + 2)).rounded(.towardZero)), 1)
    }

    public static func window(_ rows: [Row], _ capacity: Int) -> Window {
        let cap = max(capacity, 1)
        if rows.count <= cap { return Window(rows: rows, skippedDone: 0, more: 0) }
        let done = rows.prefix { $0.phase == .done }.count
        // 끝난 수업만 빼서 들어가면 "+N개 더" 칸이 필요 없다.
        if rows.count - done <= cap {
            let skip = rows.count - cap
            return Window(rows: Array(rows.dropFirst(skip)), skippedDone: skip, more: 0)
        }
        let slots = max(cap - 1, 1)
        let shown = Array(rows.dropFirst(done).prefix(slots))
        return Window(rows: shown, skippedDone: done, more: rows.count - done - shown.count)
    }

    /// `09:30~10:45`.
    public static func timeRange(_ row: TimetableLogic.ClassRow) -> String { "\(row.start)~\(row.end)" }

    /// 머리 오른쪽 날짜: `11/27(금)`.
    public static func dateLabel(_ date: DayDate) -> String {
        "\(date.monthValue)/\(date.dayOfMonth)(\(TimetableLogic.weekdayOf(date)))"
    }

    /// 오늘 남은 수업이 없을 때의 첫 줄.
    public static func emptyMessage(_ today: Today) -> String {
        today.rows.isEmpty ? "오늘은 수업이 없어요" : "오늘 수업이 끝났어요"
    }

    /// 오늘 남은 수업이 없을 때의 둘째 줄 머리: `다음 수업 · 내일 09:30` / `다음 수업 · 목요일 09:30` / `이번 주 남은 수업이 없어요`.
    /// 다음 수업이 있으면 그 아래에 과목·강의실 줄을 그린다.
    public static func nextMessage(_ today: Today) -> String {
        guard let next = today.next else { return "이번 주 남은 수업이 없어요" }
        return "다음 수업 · \(WidgetText.dayLabel(next.date, today.date)) \(next.row.start)"
    }

    /// VoiceOver 한 줄: `9시 30분부터 10시 45분까지 자료구조, 301-101, 진행 중`.
    public static func accessibilityLine(_ row: Row) -> String {
        let r = row.entry.row
        let phase: String
        switch row.phase {
        case .done: phase = ", 끝남"
        case .ongoing: phase = ", 진행 중"
        case .upcoming: phase = ""
        }
        return "\(spoken(r.start))부터 \(spoken(r.end))까지 \(WidgetText.blockTitle(row.entry))" +
            (WidgetText.room(r.room).map { ", \($0)" } ?? "") + phase
    }

    private static func spoken(_ time: String) -> String {
        guard let t = TimetableWidgetLogic.parseTime(time) else { return time }
        return t.1 == 0 ? "\(t.0)시" : "\(t.0)시 \(t.1)분"
    }
}
