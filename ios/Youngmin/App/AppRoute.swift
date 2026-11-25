import NotifyKit
import SwiftUI

/// 채팅 화면(NavigationStack 뿌리) 위로 여는 화면들. 다른 화면이 만들어지면 `destination`의 자리표시만 바꾸면 된다.
enum AppRoute: Hashable {
    case mySchedule
    case timetable
    case notifyHistory
    case settings
    case privacyPolicy
    case courseAlerts
    /// 알림·캘린더에서 내 마감 항목 하나를 열 때(반복 회차 ID도 된다).
    case myScheduleItem(Int64)
    /// 공지 초안(`ScheduleDraft.toJson`)으로 미리 채운 내 일정 추가 창(알림 버튼·등록할 공지 시트).
    case scheduleDraft(String)
    /// 보강·휴강 공지 초안으로 미리 채운 시간표 보강·휴강 창.
    case timetableDraft(String)

    /// 초안 JSON이 갈 화면: 보강·휴강이면 시간표, 그 밖은 내 일정.
    static func draft(_ json: String) -> AppRoute {
        ScheduleDraft.fromJson(json)?.timetable == true ? .timetableDraft(json) : .scheduleDraft(json)
    }

    @MainActor @ViewBuilder
    var destination: some View {
        switch self {
        case .mySchedule: MyScheduleView()
        case .timetable: TimetableView()
        case .notifyHistory: NotifyHistoryView()
        case .settings: SettingsView(courseAlerts: { AnyView(CourseAlertsView()) })
        case .privacyPolicy: PrivacyPolicyView()
        case .courseAlerts: CourseAlertsView()
        case .myScheduleItem(let id): MyScheduleView(openDeadlineId: id)
        case .scheduleDraft(let json): MyScheduleView(draft: ScheduleDraft.fromJson(json))
        case .timetableDraft(let json): TimetableView(draft: ScheduleDraft.fromJson(json))
        }
    }
}
