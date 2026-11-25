import BackgroundTasks
import Foundation

/// 안드로이드 WorkManager 주기 확인(1시간)·브리핑을 iOS BGAppRefreshTask로 옮긴 것.
/// iOS는 실행 시점을 보장하지 않는다(사용 패턴·배터리에 따라 늦거나 건너뜀, 강제 종료하면 돌지 않음).
/// 그래서 앱을 열 때마다 같은 확인을 먼저 돌리고, 여기서는 "가능하면 더 자주"를 맡는다.
enum BackgroundRefresh {
    static let checkTaskId = "kr.snu.etlassistant.ios.check"
    /// 다음 정기 브리핑 시각(앱 시작 때 채운다). 그 시각 [briefLead] 전쯤 확인을 요청해 예약 브리핑을 새 정보로 바꾼다.
    nonisolated(unsafe) static var nextBriefAt: (@Sendable () -> Date?)?
    static let briefLead: TimeInterval = 30 * 60

    /// 앱 시작 시 한 번 등록한다. `work`는 확인 한 번(eTL 조회 → 알림 계획 → 예약)을 돌리고 성공 여부를 돌려준다.
    static func register(work: @escaping @Sendable () async -> Bool) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: checkTaskId, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            schedule()
            let job = Task {
                let ok = await work()
                task.setTaskCompleted(success: ok)
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    /// 다음 확인을 요청한다(가장 이르게 1시간 뒤, 브리핑이 그보다 가까우면 브리핑 30분 전). 앱이 백그라운드로 갈 때도 부른다.
    static func schedule(after seconds: TimeInterval = 60 * 60) {
        let request = BGAppRefreshTaskRequest(identifier: checkTaskId)
        var earliest = Date(timeIntervalSinceNow: seconds)
        if let brief = nextBriefAt?() {
            let lead = brief.addingTimeInterval(-briefLead)
            if lead < earliest { earliest = max(lead, Date(timeIntervalSinceNow: 60)) }
        }
        request.earliestBeginDate = earliest
        try? BGTaskScheduler.shared.submit(request)
    }
}
