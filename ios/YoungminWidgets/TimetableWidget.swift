import ETLKit
import NotifyKit
import SwiftUI
import WidgetKit

/// 시간표 위젯(안드로이드 4×4 `TimetableWidgetProvider`·4×6 `TallTimetableWidgetProvider`).
/// - systemLarge: 이번 주(KST 월~) 격자. 등록한 휴강은 흐린 블록과 `휴강` 문구로 남긴다.
/// - systemExtraLarge(iPad에만 있음): 같은 격자를 더 큰 글자·여백으로.
/// - systemMedium(iOS에만 있음): 격자는 너무 작아서 오늘 수업 목록(휴강 제외, 보강 포함). 오늘 남은 수업이 없으면 이번 주 다음 수업 날의 첫 수업.
/// 누르면 앱의 시간표.
struct TimetableWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.timetable, provider: SnapshotProvider()) { entry in
            TimetableWidgetView(entry: entry)
        }
        .configurationDisplayName("시간표")
        .description("중간 크기는 오늘 수업을, 큰 크기는 이번 주 수업과 등록한 휴강·보강을 보여줘요.")
        .supportedFamilies([.systemMedium, .systemLarge, .systemExtraLarge])
        .contentMarginsDisabled()
    }
}

struct TimetableWidgetView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.dynamicTypeSize) private var dynamicType

    /// 시간표 위젯 글자 배율(격자와 같이 1~1.5).
    private var scale: Double { min(max(dynamicType.widgetFontScale, 1), 1.5) }

    var body: some View {
        Group {
            if family == .systemMedium {
                TodayClassesView(today: entry.snapshot?.todayClasses(entry.date), scale: scale)
            } else {
                weekView
            }
        }
        .containerBackground(WidgetPalette.background, for: .widget)
        .widgetURL(WidgetLinks.url(.timetable))
    }

    /// 큰 크기·아주 큰 크기(iPad): 이번 주 격자. 아주 큰 크기는 글자와 여백을 키운다.
    private var weekView: some View {
        let week = entry.snapshot?.week(entry.date)
        let extra = family == .systemExtraLarge
        let gridScale = scale * (extra ? 1.3 : 1)
        return VStack(spacing: 0) {
            Text("시간표")
                .font(.system(size: extra ? 18 : 15, weight: .bold))
                .kerning(0.6)
                .foregroundStyle(WidgetPalette.accent)
                .frame(maxWidth: .infinity, minHeight: extra ? 34 : 26)
            if let week, !week.entries.isEmpty {
                WeekGrid(week: week, now: entry.date, fontScale: gridScale)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(week.entries.map(describe).joined(separator: "\n"))
            } else {
                Text("등록된 수업이 없어요\n눌러서 시간표 열기")
                    .font(.system(size: extra ? 14 : 11))
                    .foregroundStyle(WidgetPalette.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(extra ? 18 : 10)
    }

    private func describe(_ e: TimetableWidgetLogic.Entry) -> String {
        "\(e.date.monthValue)/\(e.date.dayOfMonth) \(e.row.start)~\(e.row.end) \(e.row.course)" +
            (e.row.room.map { " \($0)" } ?? "") + (e.cancelled ? " 휴강" : " \(e.row.kind)")
    }
}

/// 중간 크기: 오늘 수업 목록. 줄 수는 실제 높이와 글자 크기로 정한다(`TodayClassesWidgetLogic.capacity`).
private struct TodayClassesView: View {
    /// nil이면 앱이 아직 위젯 사본을 쓰지 않았다.
    let today: TodayClassesWidgetLogic.Today?
    let scale: Double

    var body: some View {
        GeometryReader { geo in
            VStack(alignment: .leading, spacing: 0) {
                header
                if let today, !today.rows.isEmpty {
                    list(today, height: Double(geo.size.height))
                } else {
                    empty
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
        }
        .padding(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 14))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text("오늘 수업")
                .font(.system(size: 14 * scale, weight: .bold))
                .foregroundStyle(WidgetPalette.accent)
            Spacer(minLength: 4)
            if let today {
                Text(TodayClassesWidgetLogic.dateLabel(today.date))
                    .font(.system(size: 11 * scale))
                    .foregroundStyle(WidgetPalette.secondary)
            }
        }
        .lineLimit(1)
        .frame(minHeight: 22 * scale)
    }

    @ViewBuilder
    private func list(_ today: TodayClassesWidgetLogic.Today, height: Double) -> some View {
        let capacity = TodayClassesWidgetLogic.capacity(height, scale)
        // 오늘 수업이 다 끝났으면 마지막 한 줄은 다음 수업 안내에 쓴다.
        let window = TodayClassesWidgetLogic.window(today.rows, today.hasRemaining ? capacity : max(capacity - 1, 1))
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(window.rows.enumerated()), id: \.offset) { _, row in
                rowView(row)
            }
            if window.more > 0 {
                footer("+\(window.more)개 더")
            } else if !today.hasRemaining {
                footer(TodayClassesWidgetLogic.nextMessage(today) + (today.next.map { " \($0.row.course)" } ?? ""))
            }
        }
    }

    private func rowView(_ row: TodayClassesWidgetLogic.Row) -> some View {
        let r = row.entry.row
        let pair = WidgetPalette.coursePairs[WidgetText.colorIndex(r.courseId, r.course, WidgetPalette.coursePairs.count)]
        let ongoing = row.phase == .ongoing
        return HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 2)
                .fill(pair.0)
                .frame(width: 4, height: 16 * scale)
            Text(TodayClassesWidgetLogic.timeRange(r))
                .font(.system(size: 12 * scale, weight: ongoing ? .bold : .regular).monospacedDigit())
                .foregroundStyle(ongoing ? WidgetPalette.accent : WidgetPalette.secondary)
                .fixedSize()
            Text(WidgetText.blockTitle(row.entry))
                .font(.system(size: 13 * scale, weight: .bold))
                .foregroundStyle(WidgetPalette.text)
                .layoutPriority(1)
            Spacer(minLength: 4)
            if let room = WidgetText.room(r.room) {
                Text(room)
                    .font(.system(size: 11 * scale))
                    .foregroundStyle(WidgetPalette.secondary)
            }
        }
        .lineLimit(1)
        .padding(.horizontal, 6)
        .frame(height: 24 * scale)
        .background(RoundedRectangle(cornerRadius: 6).fill(ongoing ? WidgetPalette.accent.opacity(0.12) : Color.clear))
        .opacity(row.phase == .done ? 0.45 : 1)
    }

    private func footer(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11 * scale))
            .foregroundStyle(WidgetPalette.secondary)
            .lineLimit(1)
            .padding(.horizontal, 6)
            .frame(height: 22 * scale, alignment: .leading)
    }

    /// 오늘 수업이 없거나 사본이 없을 때.
    @ViewBuilder
    private var empty: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let today {
                Text(TodayClassesWidgetLogic.emptyMessage(today))
                    .font(.system(size: 12 * scale))
                    .foregroundStyle(WidgetPalette.secondary)
                Text(TodayClassesWidgetLogic.nextMessage(today))
                    .font(.system(size: 11 * scale, weight: .bold))
                    .foregroundStyle(WidgetPalette.accent)
                    .padding(.top, 4)
                if let next = today.next {
                    Text(next.row.course + (WidgetText.room(next.row.room).map { " · \($0)" } ?? ""))
                        .font(.system(size: 14 * scale, weight: .bold))
                        .foregroundStyle(WidgetPalette.text)
                }
            } else {
                Text("등록된 수업이 없어요\n눌러서 시간표 열기")
                    .font(.system(size: 11 * scale))
                    .foregroundStyle(WidgetPalette.secondary)
            }
        }
        .lineLimit(2)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var accessibilityText: String {
        guard let today else { return "등록된 수업이 없어요. 시간표 열기" }
        let head = "오늘 수업, \(TodayClassesWidgetLogic.dateLabel(today.date))"
        var parts = [head] + today.rows.map(TodayClassesWidgetLogic.accessibilityLine)
        if !today.hasRemaining {
            parts.append(TodayClassesWidgetLogic.emptyMessage(today))
            parts.append(TodayClassesWidgetLogic.nextMessage(today) + (today.next.map { " \($0.row.course)" } ?? ""))
        }
        return parts.joined(separator: ". ") + ". 시간표 열기"
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

#Preview(as: .systemMedium) {
    TimetableWidget()
} timeline: {
    SnapshotEntry(date: Date(), snapshot: WidgetSamples.snapshot(Date()))
}
