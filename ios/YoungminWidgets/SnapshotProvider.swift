import ETLKit
import Foundation
import NotifyKit
import WidgetKit

/// 한 시점의 위젯 자료. [snapshot]이 nil이면 앱이 아직 사본을 쓰지 않았다(저장된 정보 없음).
struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?
}

/// 세 위젯이 함께 쓰는 타임라인. 앱이 쓴 App Group 사본만 읽고 네트워크는 쓰지 않는다.
/// 15분마다, 다음 마감·다음 수업 전환·KST 자정에 다시 그리고(안드로이드 `HomeWidgets.render`), 6시간 뒤 다시 읽는다.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: Date(), snapshot: WidgetSamples.snapshot(Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let now = Date()
        if context.isPreview {
            completion(SnapshotEntry(date: now, snapshot: WidgetFiles.readSnapshot() ?? WidgetSamples.snapshot(now)))
        } else {
            completion(SnapshotEntry(date: now, snapshot: WidgetFiles.readSnapshot()))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        AppGroup.logContainer("widget")
        let now = Date()
        let snapshot = WidgetFiles.readSnapshot()
        let entries = WidgetTimelineLogic.entryDates(snapshot, now).map { SnapshotEntry(date: $0, snapshot: snapshot) }
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}

/// 위젯 갤러리 미리보기용 합성 자료(개인 정보 없음).
enum WidgetSamples {
    static func snapshot(_ now: Date) -> WidgetSnapshot {
        let today = EtlLogic.kstParts(now).day
        let assignments = [
            AssignmentItem(1, "2026-2 자료구조 (001)", 1, "과제 제출", today.atTime(23, 59) > now ? today.atTime(23, 59) : now.addingTimeInterval(3 * 3600),
                           true, nil, nil, false, false),
            AssignmentItem(2, "2026-2 확률과 통계 (002)", 2, "3주차 퀴즈", now.addingTimeInterval(2 * 86_400), true, nil, nil, false, false),
        ]
        let mine = [MyDeadline(1, "보고서 초안", now.addingTimeInterval(86_400 + 3600))]
        let weekly = TimetableLogic.Weekly("sample", 0, [
            TimetableLogic.CourseMeetings(1, "자료구조", [TimetableLogic.Meeting("월", "09:30", "10:45", "301-101"),
                                                       TimetableLogic.Meeting("수", "09:30", "10:45", "301-101")]),
            TimetableLogic.CourseMeetings(2, "확률과 통계", [TimetableLogic.Meeting("화", "13:00", "14:15", "26-B101"),
                                                         TimetableLogic.Meeting("목", "13:00", "14:15", "26-B101")]),
            TimetableLogic.CourseMeetings(3, "글쓰기의 기초", [TimetableLogic.Meeting("금", "11:00", "12:50", "1-201")]),
        ], [])
        return WidgetSnapshot.make(snapshot: CachedSnapshot(now.epochMilli, assignments), mine: mine, weekly: weekly, changes: [],
                                   filter: CourseFilter(), now: now)
    }
}
