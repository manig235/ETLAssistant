import ETLKit
import NotifyKit
import SwiftUI
import WidgetKit

/// 시간표 위젯(안드로이드 4×4 `TimetableWidgetProvider`·4×6 `TallTimetableWidgetProvider` → systemLarge 하나).
/// 이번 주(KST 월~) 수업과 등록한 휴강·보강. 휴강은 흐린 블록과 `휴강` 문구로 남긴다. 누르면 앱의 시간표.
struct TimetableWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.timetable, provider: SnapshotProvider()) { entry in
            TimetableWidgetView(entry: entry)
        }
        .configurationDisplayName("시간표")
        .description("이번 주 수업과 등록한 휴강·보강을 한눈에 볼 수 있어요.")
        .supportedFamilies([.systemLarge])
        .contentMarginsDisabled()
    }
}

struct TimetableWidgetView: View {
    let entry: SnapshotEntry
    @Environment(\.dynamicTypeSize) private var dynamicType

    var body: some View {
        let week = entry.snapshot?.week(entry.date)
        VStack(spacing: 0) {
            Text("시간표")
                .font(.system(size: 15, weight: .bold))
                .kerning(0.6)
                .foregroundStyle(WidgetPalette.accent)
                .frame(maxWidth: .infinity, minHeight: 26)
            if let week, !week.entries.isEmpty {
                WeekGrid(week: week, now: entry.date, fontScale: min(max(dynamicType.widgetFontScale, 1), 1.5))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(week.entries.map(describe).joined(separator: "\n"))
            } else {
                Text("등록된 수업이 없어요\n눌러서 시간표 열기")
                    .font(.system(size: 11))
                    .foregroundStyle(WidgetPalette.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(10)
        .containerBackground(WidgetPalette.background, for: .widget)
        .widgetURL(WidgetLinks.url(.timetable))
    }

    private func describe(_ e: TimetableWidgetLogic.Entry) -> String {
        "\(e.date.monthValue)/\(e.date.dayOfMonth) \(e.row.start)~\(e.row.end) \(e.row.course)" +
            (e.row.room.map { " \($0)" } ?? "") + (e.cancelled ? " 휴강" : " \(e.row.kind)")
    }
}

/// 격자(Kotlin `TimetableWidgetRenderer.draw`의 치수: 축 23×배율, 머리 18×배율, 선 0.5, 블록 모서리 3).
private struct WeekGrid: View {
    let week: TimetableWidgetLogic.Week
    let now: Date
    let fontScale: Double

    var body: some View {
        GeometryReader { geo in
            let grid = week.grid
            let width = Double(geo.size.width)
            let axis = 23 * fontScale
            let header = 18 * fontScale
            let top = header + 5
            let bottom = Double(geo.size.height) - 7
            let column = (width - axis) / Double(grid.days.count)
            let hour = (bottom - top) / Double(grid.endHour - grid.startHour)
            let local = EtlLogic.kstParts(now)
            ZStack(alignment: .topLeading) {
                // 선: 시간 가로선, 요일 세로선.
                Path { p in
                    for h in grid.startHour...grid.endHour {
                        let y = top + Double(h - grid.startHour) * hour
                        p.move(to: CGPoint(x: axis, y: y))
                        p.addLine(to: CGPoint(x: width, y: y))
                    }
                    for i in 0...grid.days.count {
                        let x = axis + Double(i) * column
                        p.move(to: CGPoint(x: x, y: top))
                        p.addLine(to: CGPoint(x: x, y: bottom))
                    }
                }
                .stroke(WidgetPalette.separator, lineWidth: 0.5)
                // 요일 머리(오늘은 강조색 굵게). 날짜는 생략한다.
                ForEach(Array(grid.days.enumerated()), id: \.offset) { i, day in
                    let isToday = week.monday.plusDays(i) == local.day
                    Text(day)
                        .font(.system(size: 11 * fontScale, weight: isToday ? .bold : .regular))
                        .foregroundStyle(isToday ? WidgetPalette.accent : WidgetPalette.secondary)
                        .frame(width: column, height: header)
                        .offset(x: axis + Double(i) * column)
                }
                // 시간 축 "09".
                ForEach(grid.startHour...grid.endHour, id: \.self) { h in
                    Text(String(format: "%02d", h))
                        .font(.system(size: 9 * fontScale))
                        .foregroundStyle(WidgetPalette.secondary)
                        .frame(width: axis - 4, height: 12, alignment: .trailing)
                        .offset(y: top + Double(h - grid.startHour) * hour - 6)
                }
                ForEach(Array(grid.blocks.enumerated()), id: \.offset) { _, block in
                    blockView(block, grid: grid, axis: axis, top: top, column: column, hour: hour)
                }
                nowLine(grid: grid, local: local, axis: axis, top: top, column: column, hour: hour)
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: WidgetGridLogic.Block, grid: WidgetGridLogic.Grid, axis: Double, top: Double, column: Double, hour: Double) -> some View {
        if let entry = week.byGridId[block.course.courseId], let dayIndex = grid.days.firstIndex(of: block.meeting.day) {
            let x = axis + Double(dayIndex) * column + Double(block.lane) * column / Double(block.lanes)
            let y = top + (Double(block.startMinute) / 60 - Double(grid.startHour)) * hour
            let left = x + 1.5, right = x + column / Double(block.lanes) - 1.5
            let upper = y + 1, lower = top + (Double(block.endMinute) / 60 - Double(grid.startHour)) * hour - 1
            let w = right - left, h = lower - upper
            if w >= 3 && h >= 3 {
                let pair = WidgetPalette.coursePairs[WidgetText.colorIndex(entry.row.courseId, entry.row.course, WidgetPalette.coursePairs.count)]
                let fill = entry.cancelled ? WidgetPalette.separator : pair.0
                let ink = entry.cancelled ? WidgetPalette.secondary : pair.1
                let fontSize = 11 * fontScale
                let lineHeight = fontSize * 1.2
                let lineExtra = 1 * fontScale
                let maxLines = min(max(Int(((h - 4 + lineExtra) / (lineHeight * 1.12 + lineExtra)).rounded(.down)), 1), 6)
                let room = WidgetText.room(entry.row.room)
                let content = WidgetText.blockTitle(entry) + (room != nil && maxLines >= 3 ? "\n\(room!)" : "")
                RoundedRectangle(cornerRadius: 3)
                    .fill(fill)
                    .overlay {
                        if w > 8 {
                            Text(content)
                                .font(.system(size: fontSize, weight: .bold))
                                .kerning(0.025 * fontSize)
                                .lineSpacing(lineExtra)
                                .foregroundStyle(ink)
                                .multilineTextAlignment(.center)
                                .lineLimit(maxLines)
                                .minimumScaleFactor(0.85)
                                .padding(.horizontal, 3)
                                .padding(.vertical, 2)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                    .frame(width: w, height: h)
                    .offset(x: left, y: upper)
            }
        }
    }

    /// 지금 시각: 오늘 열에 강조색 가로선 + 점(타임라인 15분 간격으로 움직인다).
    @ViewBuilder
    private func nowLine(grid: WidgetGridLogic.Grid, local: (day: DayDate, hour: Int, minute: Int, second: Int),
                         axis: Double, top: Double, column: Double, hour: Double) -> some View {
        let todayIndex = local.day.dayOfWeek.rawValue - 1
        let minute = local.hour * 60 + local.minute
        if todayIndex < grid.days.count && minute >= grid.startHour * 60 && minute < grid.endHour * 60 {
            let y = top + (Double(minute) / 60 - Double(grid.startHour)) * hour
            let x = axis + Double(todayIndex) * column
            Path { p in
                p.move(to: CGPoint(x: x, y: y))
                p.addLine(to: CGPoint(x: x + column, y: y))
            }
            .stroke(WidgetPalette.accent, lineWidth: 1.3)
            Circle()
                .fill(WidgetPalette.accent)
                .frame(width: 4.4, height: 4.4)
                .offset(x: x + 2 - 2.2, y: y - 2.2)
        }
    }
}

#Preview(as: .systemLarge) {
    TimetableWidget()
} timeline: {
    SnapshotEntry(date: Date(), snapshot: WidgetSamples.snapshot(Date()))
}
