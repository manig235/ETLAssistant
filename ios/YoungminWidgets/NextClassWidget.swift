import NotifyKit
import SwiftUI
import WidgetKit

/// 다음 수업 위젯(안드로이드 2×1 `NextClassWidgetProvider` → systemSmall + 잠금 화면 accessoryRectangular).
/// 시작 30분 뒤(더 짧은 수업은 끝날 때) 다음 수업으로 넘어간다. 누르면 앱의 시간표.
struct NextClassWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.nextClass, provider: SnapshotProvider()) { entry in
            NextClassWidgetView(entry: entry)
        }
        .configurationDisplayName("다음 수업")
        .description("다음 수업의 과목·시각·강의실을 보여줘요. 시작 30분 뒤 다음 수업으로 넘어가요.")
        .supportedFamilies([.systemSmall, .accessoryRectangular])
    }
}

struct NextClassWidgetView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.dynamicTypeSize) private var dynamicType

    var body: some View {
        let next = entry.snapshot?.nextClass(entry.date)
        let title = next?.entry.row.course ?? "다음 수업이 없어요"
        let detail = next.map { WidgetText.nextClassDetail($0, entry.date) } ?? "눌러서 시간표 열기"
        let label = next.map { "\($0.entry.row.course), \(detail), \($0.entry.row.end) 종료. 시간표 열기" } ?? "다음 수업이 없어요. 시간표 열기"
        Group {
            if family == .accessoryRectangular {
                VStack(alignment: .leading, spacing: 1) {
                    Text("다음 수업").font(.caption2).widgetAccentable()
                    Text(title).font(.headline).lineLimit(1)
                    Text(detail).font(.caption).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .containerBackground(for: .widget) { Color.clear }
            } else {
                let scale = max(dynamicType.widgetFontScale, 1)
                VStack(spacing: 2) {
                    Text(title)
                        .font(.system(size: 15 * scale, weight: .bold))
                        .foregroundStyle(WidgetPalette.text)
                        .lineLimit(2)
                    Text(detail)
                        .font(.system(size: 15 * 0.84 * scale))
                        .foregroundStyle(WidgetPalette.accent)
                        .lineLimit(2)
                }
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .containerBackground(WidgetPalette.background, for: .widget)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .widgetURL(WidgetLinks.url(.timetable))
    }
}

#Preview(as: .systemSmall) {
    NextClassWidget()
} timeline: {
    SnapshotEntry(date: Date(), snapshot: WidgetSamples.snapshot(Date()))
}
