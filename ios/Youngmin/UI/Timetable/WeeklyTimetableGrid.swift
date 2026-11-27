import ETLKit
import NotifyKit
import SwiftUI

/// 시간·요일·겹치는 수업의 배치(Kotlin `TimetableGridLogic`). 화면 크기와 독립적이다.
enum TimetableGridLogic {
    struct Block: Hashable {
        var course: TimetableLogic.CourseMeetings
        var meeting: TimetableLogic.Meeting
        var startMinute: Int
        var endMinute: Int
        var lane = 0
        var lanes = 1
        var duration: Int { endMinute - startMinute }
    }

    struct Grid: Hashable {
        var days: [String]
        var startHour: Int
        var endHour: Int
        var blocks: [Block]
    }

    private static func minutes(_ time: String) -> Int {
        (Int(time.prefix(2)) ?? 0) * 60 + (Int(time.suffix(2)) ?? 0)
    }

    private static func dayIndex(_ d: String) -> Int { TimetableLogic.weekdayLabels.firstIndex(of: d) ?? -1 }

    static func build(_ courses: [TimetableLogic.CourseMeetings]) -> Grid {
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

    /// "301-103" → "301동 103호". 없으면 "등록된 강의실 정보가 없어요".
    static func roomLabel(_ raw: String?) -> String {
        guard let room = TimetableLogic.cleanRoom(raw) else { return "등록된 강의실 정보가 없어요" }
        let ns = room as NSString
        if let m = ROOM.firstMatch(in: room, range: NSRange(location: 0, length: ns.length)) {
            return "\(ns.substring(with: m.range(at: 1)))동 \(ns.substring(with: m.range(at: 2)))호"
        }
        return room
    }

    private static let ROOM = try! NSRegularExpression(pattern: "^(\\d+(?:-\\d+)?)-(\\d+)$")
}

/// 과목색 9쌍(Kotlin `WeeklyTimetable.kt` `courseColors`): 배경 / 글자. courseId의 Kotlin `Long.hashCode()`로 고른다.
enum TimetableColors {
    static let pairs: [(Color, Color)] = [
        (Color(hex: 0x9ACBF0), Color(hex: 0x19364E)), (Color(hex: 0xE5AFBC), Color(hex: 0x492A34)),
        (Color(hex: 0xAFA9E4), Color(hex: 0x302C55)), (Color(hex: 0x88CDBD), Color(hex: 0x183D35)),
        (Color(hex: 0xECC596), Color(hex: 0x49351E)), (Color(hex: 0xE3CC8E), Color(hex: 0x473C1A)),
        (Color(hex: 0xC6B1DD), Color(hex: 0x3E2D4D)), (Color(hex: 0xB4CE9E), Color(hex: 0x2F4023)),
        (Color(hex: 0x9DB3CF), Color(hex: 0x24364D)),
    ]

    /// Kotlin `Math.floorMod(id.hashCode(), size)`; `Long.hashCode() = (v xor (v ushr 32)).toInt()`.
    static func of(_ id: Int64) -> (Color, Color) {
        let u = UInt64(bitPattern: id)
        let h = Int(Int32(truncatingIfNeeded: u ^ (u >> 32)))
        let n = pairs.count
        return pairs[((h % n) + n) % n]
    }

    /// 휴강 블록(시간표 위젯과 같은 구분선색·보조 글자색).
    static let cancelledBackground = YMColor.dynamic(light: 0xDCE1EC, dark: 0x414859)
}

/// 이번 주에 휴강으로 빠지는 수업을 고르는 열쇠(과목 + 요일 + 시작).
struct TimetableCancelKey: Hashable {
    let courseId: Int64
    let day: String
    let start: String
}

/// 주간 격자(Kotlin `WeeklyTimetable`): 시간 축은 고정, 머리줄과 격자는 함께 가로로 스크롤.
struct WeeklyTimetableGrid: View {
    let weekly: TimetableLogic.Weekly
    /// 이번 주 휴강으로 빠지는 수업(회색으로 보인다).
    var cancelled: Set<TimetableCancelKey> = []
    var onEditCustom: ((TimetableLogic.CourseMeetings) -> Void)?

    @ScaledMetric(relativeTo: .body) private var fontUnit: CGFloat = 1
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var width: CGFloat = 0
    @State private var selected: SelectedCourse?

    private struct SelectedCourse: Identifiable { let id: Int64 }

    private var scale: CGFloat { min(max(fontUnit, 1), 1.6) }
    private let axisWidth: CGFloat = 32

    var body: some View {
        let grid = TimetableGridLogic.build(weekly.courses)
        // 넓은 화면(iPad regular 폭)에서는 요일 칸이 넓어지므로 시간 칸도 키워 블록이 납작해 보이지 않게 한다.
        let hourHeight = 46 * scale * (sizeClass == .regular ? 1.3 : 1)
        let gridHeight = hourHeight * CGFloat(grid.endHour - grid.startHour)
        let dayWidth = max((max(width, 200) - axisWidth) / CGFloat(grid.days.count), 64 * scale)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 0) {
                // 시간 축(머리줄 44 아래).
                ZStack(alignment: .topLeading) {
                    ForEach(grid.startHour...grid.endHour, id: \.self) { hour in
                        Text(String(format: "%02d", hour))
                            .font(.system(size: 10))
                            .foregroundStyle(YMColor.labelSecondary)
                            .frame(width: axisWidth - 5, height: 16, alignment: .trailing)
                            .offset(y: hourHeight * CGFloat(hour - grid.startHour))
                    }
                }
                .frame(width: axisWidth, height: gridHeight + 16, alignment: .topLeading)
                .padding(.top, 44)
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(spacing: 0) {
                        HStack(spacing: 0) {
                            ForEach(grid.days, id: \.self) { day in
                                Text(day)
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(YMColor.labelSecondary)
                                    .frame(width: dayWidth, height: 44)
                            }
                        }
                        ZStack(alignment: .topLeading) {
                            lines(grid, hourHeight: hourHeight, dayWidth: dayWidth)
                            ForEach(Array(grid.blocks.enumerated()), id: \.offset) { _, block in
                                blockView(block, grid: grid, hourHeight: hourHeight, dayWidth: dayWidth)
                            }
                        }
                        .frame(width: dayWidth * CGFloat(grid.days.count), height: gridHeight + 16, alignment: .topLeading)
                    }
                }
            }
            Text("수업 블록을 누르면 자세한 정보를 볼 수 있어요")
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .padding(.leading, 16)
                .padding(.top, 10)
                .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GeometryReader { geo in
            Color.clear
                .onAppear { width = geo.size.width }
                .onChange(of: geo.size.width) { _, w in width = w }
        })
        .sheet(item: $selected) { sel in
            if let course = weekly.courses.first(where: { $0.courseId == sel.id }) {
                TimetableCourseSheet(course: course, onEditCustom: onEditCustom.map { edit in
                    { selected = nil; edit(course) }
                }, onClose: { selected = nil })
            }
        }
    }

    private func lines(_ grid: TimetableGridLogic.Grid, hourHeight: CGFloat, dayWidth: CGFloat) -> some View {
        Canvas { ctx, size in
            let line = GraphicsContext.Shading.color(YMColor.labelSecondary.opacity(0.13))
            let half = GraphicsContext.Shading.color(YMColor.labelSecondary.opacity(0.06))
            let top: CGFloat = 8
            let bottom = size.height - 8
            for i in 0...((grid.endHour - grid.startHour) * 2) {
                let y = top + CGFloat(i) * hourHeight / 2
                var p = Path()
                p.move(to: CGPoint(x: 0, y: y))
                p.addLine(to: CGPoint(x: size.width, y: y))
                ctx.stroke(p, with: i % 2 == 0 ? line : half, lineWidth: 0.5)
            }
            for d in 0...grid.days.count {
                let x = dayWidth * CGFloat(d)
                var p = Path()
                p.move(to: CGPoint(x: x, y: top))
                p.addLine(to: CGPoint(x: x, y: bottom))
                ctx.stroke(p, with: line, lineWidth: 0.5)
            }
        }
    }

    private func blockView(_ block: TimetableGridLogic.Block, grid: TimetableGridLogic.Grid, hourHeight: CGFloat, dayWidth: CGFloat) -> some View {
        let isCancelled = cancelled.contains(TimetableCancelKey(courseId: block.course.courseId, day: block.meeting.day, start: block.meeting.start))
        let pair = TimetableColors.of(block.course.courseId)
        let background = isCancelled ? TimetableColors.cancelledBackground : pair.0
        let foreground = isCancelled ? YMColor.labelSecondary : pair.1
        let w = dayWidth / CGFloat(block.lanes) - 4
        let narrow = w < 56 * scale
        let top = hourHeight * CGFloat(block.startMinute - grid.startHour * 60) / 60 + 10
        let h = hourHeight * CGFloat(block.duration) / 60 - 4
        let x = dayWidth * CGFloat(grid.days.firstIndex(of: block.meeting.day) ?? 0) + dayWidth / CGFloat(block.lanes) * CGFloat(block.lane) + 2
        let name = (isCancelled ? "휴강 · " : "") + TimetableLogic.shortName(block.course.name)
        let room = block.meeting.room.map(SchedFormat.trim).flatMap { ($0.isEmpty || $0 == "미정" || $0 == "강의실 미정") ? nil : $0 }
        let lines = block.duration < 60 || narrow ? 1 : (block.duration <= 90 ? 2 : 4)
        return Button { selected = SelectedCourse(id: block.course.courseId) } label: {
            VStack(alignment: .leading, spacing: block.duration < 60 ? 2 : 3) {
                Text(name)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(foreground)
                    .lineLimit(lines)
                    .lineSpacing(1)
                if let room {
                    Text(room)
                        .font(.system(size: 9))
                        .foregroundStyle(foreground.opacity(0.85))
                        .lineLimit(narrow ? 2 : 1)
                }
            }
            .padding(.horizontal, 5)
            .padding(.vertical, block.duration < 60 ? 3 : 5)
            .frame(width: w, height: max(h, 1), alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 6).fill(background))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .contentShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(YMPressStyle())
        .offset(x: x, y: top)
        .accessibilityLabel("\(name), \(block.meeting.day)요일, \(block.meeting.start)~\(block.meeting.end), \(TimetableGridLogic.roomLabel(block.meeting.room))")
        .accessibilityHint("수업 상세 보기")
    }
}

/// 수업 정보 바텀 시트(Kotlin `WeeklyTimetable`의 `ModalBottomSheet`).
struct TimetableCourseSheet: View {
    let course: TimetableLogic.CourseMeetings
    let onEditCustom: (() -> Void)?
    let onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 7) {
                    Circle().fill(TimetableColors.of(course.courseId).0).frame(width: 9, height: 9)
                    Text(course.courseId < 0 ? "직접 추가한 수업" : "수업 정보")
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                }
                Spacer().frame(height: 12)
                Text(TimetableLogic.shortName(course.name))
                    .font(YMFont.title2Bold)
                    .foregroundStyle(YMColor.label)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer().frame(height: 24)
                detail("person", "교수님", course.professor.flatMap { SchedFormat.trim($0).isEmpty ? nil : $0 }
                    ?? (course.courseId < 0 ? "등록된 교수 정보가 없어요" : "교수 정보를 확인하지 못했어요"))
                detail("mappin.and.ellipse", "강의실", distinct(course.meetings.map { TimetableGridLogic.roomLabel($0.room) }).joined(separator: "\n"))
                detail("clock", "수업 시간", course.meetings.sorted { a, b in
                    let ia = TimetableLogic.weekdayLabels.firstIndex(of: a.day) ?? -1
                    let ib = TimetableLogic.weekdayLabels.firstIndex(of: b.day) ?? -1
                    return ia != ib ? ia < ib : a.start < b.start
                }.map { "\($0.day)요일  \($0.start) – \($0.end)" }.joined(separator: "\n"))
                Spacer().frame(height: 20)
                if course.courseId < 0, let onEditCustom {
                    YMOutlineButton("수정 · 삭제", action: onEditCustom)
                    Spacer().frame(height: 8)
                }
                YMPrimaryButton("확인", action: onClose)
                Spacer().frame(height: 24)
            }
            .padding(.horizontal, 24)
            .padding(.top, 28)
        }
        .background(YMColor.background.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationCornerRadius(24)
        .presentationDragIndicator(.visible)
    }

    private func distinct(_ list: [String]) -> [String] {
        var seen = Set<String>()
        return list.filter { seen.insert($0).inserted }
    }

    private func detail(_ icon: String, _ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 0) {
            Image(systemName: icon)
                .font(.system(size: 17))
                .foregroundStyle(YMColor.labelSecondary)
                .frame(width: 20, height: 20)
            Spacer().frame(width: 12)
            Text(label)
                .font(YMFont.body2Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .frame(width: 76, alignment: .leading)
            Text(value)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.label)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 11)
    }
}
