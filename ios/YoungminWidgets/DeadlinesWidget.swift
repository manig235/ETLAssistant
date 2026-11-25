import AppIntents
import NotifyKit
import SwiftUI
import WidgetKit

/// 일정 위젯(안드로이드 2×2 `DeadlineWidgetProvider` → systemSmall/systemMedium, 2×1 `CompactDeadlineWidgetProvider` → 잠금 화면 accessoryRectangular).
struct DeadlinesWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.deadlines, provider: SnapshotProvider()) { entry in
            DeadlinesWidgetView(entry: entry)
        }
        .configurationDisplayName("일정")
        .description("가까운 과제와 내 마감을 보여줘요. 크기를 늘리면 더 많이 볼 수 있어요.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
        .contentMarginsDisabled()
    }
}

struct DeadlinesWidgetView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.dynamicTypeSize) private var dynamicType

    private var items: [DeadlineWidgetLogic.Item] { entry.snapshot?.deadlineItems(entry.date) ?? [] }
    private var emptyText: String {
        entry.snapshot?.assignmentsTakenAt == nil && items.isEmpty ? "저장된 과제 정보가 없어요\n눌러서 내 일정 확인" : "남은 마감이 없어요"
    }

    var body: some View {
        switch family {
        case .accessoryRectangular: rectangular
        default: home
        }
    }

    // MARK: 홈 화면 (2×2)

    private var home: some View {
        GeometryReader { geo in
            let scale = dynamicType.widgetFontScale
            // 안드로이드 행 수 공식(높이에 위아래 패딩 14 + 머리 22×배율 포함). iOS 모서리에 맞춰 위아래 4를 더 비운다.
            let height = Double(geo.size.height) - 4
            let capacity = DeadlineWidgetLogic.capacity(height, scale)
            let rowSpace = (height - (14 + 22 * max(scale, 1))) / Double(capacity)
            let titleSp = min(max(rowSpace / (4.2 * max(scale, 1)), 8), 15)
            let wide = geo.size.width >= 240
            VStack(alignment: .leading, spacing: 0) {
                header
                if items.isEmpty {
                    Text(emptyText)
                        .font(.system(size: 11 * scale))
                        .foregroundStyle(WidgetPalette.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ForEach(Array(items.prefix(capacity).enumerated()), id: \.element.key) { index, item in
                        if index > 0 { Rectangle().fill(WidgetPalette.separator).frame(height: 1) }
                        row(item, titleSize: titleSp * max(scale, 1), wide: wide)
                    }
                    Spacer(minLength: 0)
                }
            }
            .padding(EdgeInsets(top: 10, leading: 16, bottom: 8, trailing: 12))
        }
        .containerBackground(WidgetPalette.background, for: .widget)
        .widgetURL(WidgetLinks.url(.schedule))
    }

    @ViewBuilder private var header: some View {
        let title = Text("일정")
            .font(.system(size: 14 * dynamicType.widgetFontScale, weight: .bold))
            .foregroundStyle(WidgetPalette.accent)
            .frame(minHeight: 22, alignment: .leading)
            .accessibilityLabel("내 일정 열기")
        if family == .systemSmall { title } else { Link(destination: WidgetLinks.url(.schedule)) { title } }
    }

    @ViewBuilder
    private func row(_ item: DeadlineWidgetLogic.Item, titleSize: Double, wide: Bool) -> some View {
        let due = DeadlineWidgetLogic.dueLabel(item.due, entry.date)
        let remaining = DeadlineWidgetLogic.remaining(item.due, entry.date)
        let body = VStack(alignment: .leading, spacing: 0) {
            Text(item.title)
                .font(.system(size: titleSize, weight: .bold))
                .foregroundStyle(WidgetPalette.text)
                .lineLimit(1)
            Text(due)
                .font(.system(size: titleSize * 0.84))
                .foregroundStyle(WidgetPalette.secondary)
                .lineLimit(1)
            Text(remaining + (wide ? " · \(item.source)" : ""))
                .font(.system(size: titleSize * 0.84))
                .foregroundStyle(WidgetPalette.accent)
                .lineLimit(1)
        }
        .padding(.vertical, 1)
        .padding(.trailing, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(item.source), \(item.title), 마감 \(due), \(remaining)")
        HStack(spacing: 0) {
            // 작은 위젯은 iOS가 위젯 전체 한 곳만 누를 수 있게 한다 → 내 일정을 연다. 중간 크기부터는 항목별로 연다.
            if family == .systemSmall { body } else { Link(destination: WidgetLinks.url(.item(item.key))) { body } }
            if let id = item.mineId, !item.exam {
                Button(intent: CompleteDeadlineIntent(deadlineId: id, occurrence: item.mineDate)) {
                    Text("○")
                        .font(.system(size: 24))
                        .foregroundStyle(WidgetPalette.accent)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(item.title) 완료로 표시")
            }
        }
    }

    // MARK: 잠금 화면 (2×1에 해당: 가장 가까운 항목 하나)

    private var rectangular: some View {
        let first = items.first
        return VStack(alignment: .leading, spacing: 1) {
            if let first {
                Text(first.title)
                    .font(.headline)
                    .lineLimit(2)
                    .widgetAccentable()
                Text("\(DeadlineWidgetLogic.dueLabel(first.due, entry.date)) · \(DeadlineWidgetLogic.remaining(first.due, entry.date))")
                    .font(.caption)
                    .lineLimit(1)
            } else {
                Text("일정").font(.headline).widgetAccentable()
                Text(emptyText.replacingOccurrences(of: "\n", with: " ")).font(.caption).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(for: .widget) { Color.clear }
        .widgetURL(first.map { WidgetLinks.url(.item($0.key)) } ?? WidgetLinks.url(.schedule))
    }
}

#Preview(as: .systemSmall) {
    DeadlinesWidget()
} timeline: {
    SnapshotEntry(date: Date(), snapshot: WidgetSamples.snapshot(Date()))
}
