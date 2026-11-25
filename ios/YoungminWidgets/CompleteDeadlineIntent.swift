import AppIntents
import ETLKit
import Foundation
import NotifyKit
import WidgetKit

/// 일정 위젯의 "○"(Kotlin `DeadlineWidgetProvider.onReceive(ACTION_COMPLETE)`). 앱을 열지 않는다.
/// 위젯 사본에서 바로 완료로 바꿔 다시 그리고, 완료 기록을 App Group에 남긴다. 앱은 다음에 활성화될 때(또는 알림 확인 뒤)
/// 그 기록을 내 마감 저장소에 반영하고 알림 예약을 다시 맞춘다. 항상 완료로 둔다(연속으로 눌러도 미완료로 돌아가지 않는다).
struct CompleteDeadlineIntent: AppIntent {
    static let title: LocalizedStringResource = "완료로 표시"
    static let isDiscoverable = false

    @Parameter(title: "내 마감 ID") var deadlineId: Int
    /// 반복 내 마감의 회차 날짜(`2026-11-23`). 한 번뿐인 마감이면 빈 글.
    @Parameter(title: "회차") var occurrence: String

    init() {}

    init(deadlineId: Int64, occurrence: DayDate?) {
        self.deadlineId = Int(deadlineId)
        self.occurrence = occurrence?.iso ?? ""
    }

    func perform() async throws -> some IntentResult {
        let now = Date()
        let id = Int64(deadlineId)
        let day = DayDate(iso: occurrence)
        if let s = WidgetFiles.readSnapshot() {
            WidgetFiles.writeSnapshot(s.markingDone(id, day, now))
        }
        WidgetFiles.addCompletion(WidgetCompletion(id: id, occurrence: day, at: now))
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKinds.deadlines)
        return .result()
    }
}
