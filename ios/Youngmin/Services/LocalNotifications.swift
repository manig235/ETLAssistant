import Foundation
import NotifyKit
import UserNotifications

/// 안드로이드 알림 채널을 iOS 알림 설정으로 옮긴 것.
/// 채널 중요도 HIGH(헤드업) → `.timeSensitive`, DEFAULT → `.active`, LOW(소리·팝업 없음) → `.passive`.
enum NotifyChannel: String, CaseIterable, Sendable {
    case deadline, announcement = "announcement_v2", change = "change_v2", classStart = "class", brief
    case submitCheck = "submit_check", submitOk = "submit_ok", newFiles = "new_files", exam, mine

    var interruption: UNNotificationInterruptionLevel {
        switch self {
        case .deadline, .announcement, .change, .classStart, .submitCheck, .exam, .mine: .timeSensitive
        case .brief, .newFiles: .active
        case .submitOk: .passive
        }
    }
    var playsSound: Bool { self != .submitOk }
}

/// 알림 하나(안드로이드 `Notice`를 iOS 알림 요청으로 바꿀 때 쓰는 중간 모양).
struct LocalNotice: Sendable {
    var tag: String                 // 같은 tag는 교체된다(안드로이드 tag와 같음)
    var channel: NotifyChannel
    var title: String
    var body: String
    var fireAt: Date?               // nil이면 바로
    var url: String?                // 눌렀을 때 여는 eTL 주소
    var prefill: String?            // 채팅 입력창에 채울 문구
    var agendaItemId: String?       // 내 일정에서 열 항목
    var actions: [String] = []      // 카테고리 동작 이름(예: "내 일정에 등록")
}

/// UNUserNotificationCenter 래퍼. iOS는 대기 알림을 앱당 64개까지만 유지하므로 가까운 것부터 예약한다.
@MainActor
final class LocalNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = LocalNotifications()
    nonisolated static let pendingLimit = 64

    private let center = UNUserNotificationCenter.current()
    /// 알림을 눌렀을 때 앱이 처리할 내용(채팅 미리 채우기, 내 일정 열기, eTL 열기).
    /// 앱이 꺼져 있을 때 누른 알림은 앱 시작 연결(`AppWiring.start`)이 이 값을 채운 뒤 처리한다.
    var onOpen: ((_ userInfo: [AnyHashable: Any], _ actionIdentifier: String) -> Void)? {
        didSet {
            if let onOpen, let waiting = waitingOpen {
                waitingOpen = nil
                onOpen(waiting.info, waiting.action)
            }
        }
    }
    private var waitingOpen: (info: [AnyHashable: Any], action: String)?
    /// 앱이 앞에 있을 때 알림이 떴을 때(예약해 둔 알림을 알림 기록에 남긴다).
    var onPresent: (() -> Void)?

    func activate() {
        center.delegate = self
    }

    func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge, .timeSensitive])) ?? false
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    /// 예약해 둔 것 중 이 접두어로 시작하는 알림을 새 목록으로 바꾼다(가까운 시각부터 한도 안에서).
    func replaceScheduled(prefix: String, with notices: [LocalNotice], reserve: Int = 8) async {
        let pending = await center.pendingNotificationRequests()
        let stale = pending.map(\.identifier).filter { $0.hasPrefix(prefix) }
        center.removePendingNotificationRequests(withIdentifiers: stale)
        let others = pending.count - stale.count
        let room = max(0, Self.pendingLimit - reserve - others)
        let upcoming = notices
            .filter { ($0.fireAt ?? .distantFuture) > Date() }
            .sorted { ($0.fireAt ?? .distantFuture) < ($1.fireAt ?? .distantFuture) }
            .prefix(room)
        for n in upcoming { await post(n) }
    }

    func post(_ n: LocalNotice) async {
        let content = UNMutableNotificationContent()
        content.title = n.title
        content.body = n.body
        content.threadIdentifier = n.channel.rawValue
        content.interruptionLevel = n.channel.interruption
        if n.channel.playsSound { content.sound = .default }
        var info: [String: String] = ["channel": n.channel.rawValue, "tag": n.tag]
        if let u = n.url { info["url"] = u }
        if let p = n.prefill { info["prefill"] = p }
        if let a = n.agendaItemId { info["agenda"] = a }
        content.userInfo = info
        if !n.actions.isEmpty {
            let categoryId = "cat." + n.actions.joined(separator: "|")
            await registerCategory(categoryId, actions: n.actions)
            content.categoryIdentifier = categoryId
        }
        var trigger: UNNotificationTrigger?
        if let at = n.fireAt, at.timeIntervalSinceNow > 1 {
            let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: at)
            trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        }
        let request = UNNotificationRequest(identifier: n.tag, content: content, trigger: trigger)
        try? await center.add(request)
    }

    private var categories: [String: UNNotificationCategory] = [:]
    private func registerCategory(_ id: String, actions: [String]) async {
        guard categories[id] == nil else { return }
        let acts = actions.enumerated().map { i, title in
            UNNotificationAction(identifier: "action.\(i)", title: title, options: [.foreground])
        }
        categories[id] = UNNotificationCategory(identifier: id, actions: acts, intentIdentifiers: [], options: [.hiddenPreviewsShowTitle])
        center.setNotificationCategories(Set(categories.values))
    }

    /// NotifyKit 알림 버튼(`NotificationPoster.actions`, 최대 3개)으로 범주를 만들어 등록하고 그 ID를 돌려준다. 버튼이 없고 숨김도 아니면 nil.
    /// 잠금화면 숨김(`hideContentOnLockScreen`)이면 미리 보기를 숨길 때 제목도 숨기고 본문 자리에 "영민이 알림"을 쓴다
    /// (iOS는 앱이 잠금화면 표시를 직접 고를 수 없어, 설정 → 알림 → 미리 보기가 "잠금 해제 시"일 때 적용된다).
    func category(for actions: [NotificationPoster.Action], hideOnLockScreen: Bool) async -> String? {
        guard let id = NoticeOpen.categoryId(actions, hideOnLockScreen: hideOnLockScreen) else { return nil }
        if categories[id] != nil { return id }
        if !loadedSystemCategories {
            // 이전 실행에서 등록한 범주를 이어받는다(대기·표시 중인 알림의 버튼이 사라지지 않게).
            loadedSystemCategories = true
            for c in await center.notificationCategories() where categories[c.identifier] == nil { categories[c.identifier] = c }
            if categories[id] != nil { return id }
        }
        let acts = actions.prefix(3).map { a in
            UNNotificationAction(identifier: NoticeOpen.identifier(a), title: a.label, options: [.foreground])
        }
        categories[id] = hideOnLockScreen
            ? UNNotificationCategory(identifier: id, actions: Array(acts), intentIdentifiers: [],
                                     hiddenPreviewsBodyPlaceholder: NotificationPoster.LOCKSCREEN_TITLE, options: [])
            : UNNotificationCategory(identifier: id, actions: Array(acts), intentIdentifiers: [], options: [.hiddenPreviewsShowTitle])
        center.setNotificationCategories(Set(categories.values))
        return id
    }
    private var loadedSystemCategories = false

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        await MainActor.run { self.onPresent?() }
        return [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        await MainActor.run {
            if let onOpen = self.onOpen { onOpen(info, action) } else { self.waitingOpen = (info, action) }
        }
    }
}
