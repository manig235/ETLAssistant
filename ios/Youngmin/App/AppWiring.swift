import AssistantKit
import ChatKit
import ETLKit
import Foundation
import NotifyKit
import Security
import UIKit
import UserNotifications

// MARK: - 비서 도구·쓰기 승인 (AssistantKit → 앱 계약)

extension Toolbox: AssistantToolbox {}

/// AssistantKit의 쓰기 승인 게이트를 채팅 화면의 승인 UI에 잇는다.
final class AssistantWriteApprovals: WriteApprovalSource, @unchecked Sendable {
    let gate: WriteApprovalGate
    init(_ gate: WriteApprovalGate = AiWriteApproval.gate) { self.gate = gate }
    func attachHost() -> String { gate.attachHost() }
    func detachHost(_ hostId: String) { gate.detachHost(hostId) }
    func pending() -> [WriteApprovalRequest] {
        gate.pending().map { WriteApprovalRequest(id: $0.id, operation: $0.operation, details: $0.details, destructive: $0.destructive) }
    }
    @discardableResult
    func decide(hostId: String, requestId: String, approved: Bool) -> Bool { gate.decide(hostId: hostId, requestId: requestId, approved: approved) }
    func cancelAll() { gate.cancelAll() }
    func setChangeHandler(_ handler: (@Sendable () -> Void)?) { gate.setChangeHandler(handler) }
}

enum AppWiring {
    /// iOS는 앱을 지워도 Keychain 항목을 남겨 두어, 다시 설치하면 eTL 토큰·ChatGPT 로그인·API 키가 되살아난다.
    /// 안드로이드처럼 앱 삭제 = 로그인 정보 삭제가 되도록, 설치 후 첫 실행(UserDefaults 표시가 없을 때) Keychain을 비운다.
    /// UserDefaults는 앱을 지우면 함께 지워지고 업데이트로는 유지되므로, 업데이트한 사용자는 영향이 없다.
    static func wipeKeychainAfterReinstall() {
        let marker = "app.installMarker"
        guard !UserDefaults.standard.bool(forKey: marker) else { return }
        SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
        SecItemDelete([kSecClass as String: kSecClassInternetPassword] as CFDictionary)
        UserDefaults.standard.set(true, forKey: marker)
    }

    /// 화면들이 쓰는 저장소와 같은 인스턴스(바뀌면 알림 예약을 다시 맞춘다).
    static let notifyStores = NotifyStores(settings: SchedStores.settings, mine: SchedStores.mine, courses: SchedStores.courses,
                                           snapshot: SchedStores.snapshot, history: SchedStores.history,
                                           timetable: SchedStores.timetable, timetableChanges: SchedStores.changes)

    @MainActor static func makeAppState() -> AppState {
        wipeKeychainAfterReinstall()
        let toolbox = Toolbox(tokens: { AppStores.tokens.load() },
                              myDeadlines: SchedStores.mine,
                              timetableStore: SchedStores.timetable,
                              timetableChanges: SchedStores.changes)
        return AppState(toolbox: toolbox, approvals: AssistantWriteApprovals())
    }

    /// 앱 시작 때 한 번: 알림 예약 갱신 고리, 백그라운드 확인, 알림 탭 처리.
    @MainActor
    static func start(app: AppState) {
        guard !started else { return }
        started = true
        SchedStores.mine.onChanged = { Task { await NotificationSync.replan() } }
        SchedStores.courses.onChanged = { Task { await NotificationSync.replan() } }
        SettingsHooks.notifySettingsChanged = { Task { await NotificationSync.replan() } }
        SettingsHooks.etlTokenChanged = { connected in
            Task {
                if !connected { await NotificationSync.clearEtl() }
                _ = await NotificationSync.check()
            }
        }
        SettingsHooks.checkNow = { Task { _ = await NotificationSync.check() } }
        dataChangedSubscription = EtlEvents.subscribe { event in
            if case .dataChanged = event { Task { await NotificationSync.replan() } }
        }
        BackgroundRefresh.nextBriefAt = { NotificationSync.nextBriefSlot() }
        LocalNotifications.shared.onOpen = { info, action in NotificationSync.open(info, action: action, app: app) }
        // 앱이 앞에 있을 때 뜬 예약 알림도 알림 기록에 남긴다.
        LocalNotifications.shared.onPresent = { Task { await NotificationSync.reconcile() } }
        SettingsHooks.briefNow = { await NotificationSync.briefNow() }
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            Task { await NotificationSync.onActivate() }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            BackgroundRefresh.schedule()
        }
        NotificationCenter.default.addObserver(forName: .NSSystemTimeZoneDidChange, object: nil, queue: .main) { _ in
            Task { await NotificationSync.replan() }
        }
        // 홈 화면 위젯 사본(App Group)·위젯 완료 반영·공유 받기(작업 B, YoungminShared/AppOnly/HomeWidgetBridge.swift).
        HomeWidgetBridge.start(app: app)
        // 캘린더 연동: 내 마감·과목 필터 변경·캘린더 변경(EKEventStoreChanged, 5초)·앞으로 올 때 맞춘다(작업 A, Services/CalendarSyncService.swift).
        CalendarSyncService.start()
        if StudyDataProfile.isDemo {
            Task {
                if let client = try? EtlClient(FakeEtl.token),
                   let snapshot = try? await SnapshotFetcher.fetch(client, Date()) {
                    SchedStores.snapshot.save(snapshot)
                    _ = try? await Timetable.weekly(client, SchedStores.timetable)
                    EtlEvents.dataChanged()
                }
            }
        }
    }

    private static var dataChangedSubscription: EtlEvents.Subscription?
    @MainActor private static var started = false
}


// MARK: - 알림 예약 (안드로이드 DeadlineAlarm + WorkManager + BriefWorker 대신)

/// NotifyKit이 계획한 알림을 iOS 로컬 알림으로 게시·예약한다.
/// iOS는 정해진 시각에 앱을 깨울 수 없어서 앞으로 7일의 제시간 알림과 이틀 치 브리핑을 미리 예약한다(대기 알림 64개 한도).
/// 예약한 알림은 장부(`ScheduledNotices`)에 적어 두고, 다음에 앱이 돌 때 이미 뜬 것을 알림 기록·중복 방지 키에 옮긴다.
enum NotificationSync {
    static let timedPrefix = "timed:"
    static let briefPrefix = "brief:"
    /// 브리핑 시각이 이만큼 안으로 다가왔을 때 앱·백그라운드 갱신이 돌면 eTL을 새로 받아 예약 브리핑을 바꾼다.
    static let briefRefreshLead: TimeInterval = 60 * 60

    /// 예약 장부(`ledger`)와 미리 받아 둔 브리핑(`freshBrief`).
    private static let prefs = UserDefaultsStore(namespace: "ios_notify_schedule")
    private static let serial = SerialRunner()

    static var casual: Bool { ChatSettings(AppStores.appSettings).casualTone }
    private static var stores: NotifyStores { AppWiring.notifyStores }

    static func isGranted() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .authorized
    }

    /// 다음 브리핑 예정 시각(백그라운드 갱신을 그 전에 요청한다).
    static func nextBriefSlot(now: Date = Date()) -> Date? {
        BriefForecast.upcomingSlots(stores.settings.load(), now, .current).first
    }

    // MARK: 예약해 둔 알림 정리

    /// 예약해 둔 알림 중 이미 뜬 것을 알림 기록에 남기고 중복 방지 키·마지막 브리핑 시각을 기록한다.
    static func reconcile(now: Date = Date()) async {
        await serial.run { await reconcileNow(now) }
    }

    private static func reconcileNow(_ now: Date) async {
        let ledger = ScheduledNotices.fromJson(prefs.string("ledger"))
        if ledger.isEmpty { return }
        let pendingIds = Set(await UNUserNotificationCenter.current().pendingNotificationRequests().map(\.identifier))
        let r = ScheduledNotices.resolve(ledger, pendingIds: pendingIds, nowMs: now.epochMilli)
        guard !r.fired.isEmpty else { return }
        stores.state.update { ScheduledNotices.apply(r.fired, to: &$0) }
        for e in r.fired { stores.history.record(e.notice, nowMs: e.at) }
        prefs.set(ScheduledNotices.toJson(r.keep), for: "ledger")
        DiagLog.logger.notice("notify: reconciled delivered=\(r.fired.count)")
    }

    // MARK: 다시 계획

    /// 지금 보낼 제시간 알림을 보내고, 앞으로 7일 것과 이틀 치 브리핑을 다시 예약한다.
    static func replan(now: Date = Date()) async {
        await serial.run { await replanNow(now) }
    }

    private static func replanNow(_ now: Date) async {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        guard status == .authorized else { DiagLog.logger.notice("notify: replan skipped, authorization=\(status.rawValue)"); return }
        await reconcileNow(now)
        var due: [Notice] = []
        _ = TimedNotifications.deliver(stores, now, casual: casual) { notice in due.append(notice); return true }
        for n in due { await post(n, at: nil) }
        let until = now.addingTimeInterval(7 * 86_400)
        let inputs = TimedNotifications.Inputs.read(stores, now, casual: casual, through: until)
        let upcoming = TimedNotifications.forecast(inputs, stores.state.load(), from: now, until: until)
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        let ours = pending.map(\.identifier).filter { $0.hasPrefix(timedPrefix) || $0.hasPrefix(briefPrefix) }
        center.removePendingNotificationRequests(withIdentifiers: ours)
        var room = max(0, LocalNotifications.pendingLimit - 8 - (pending.count - ours.count))
        var ledger: [ScheduledNotices.Entry] = []
        // 브리핑(많아야 하루 몇 개 × 이틀)을 먼저, 남은 자리에 제시간 알림을 가까운 것부터.
        for (at, notice) in briefPlan(now).prefix(room) {
            let id = briefPrefix + String(at.epochMilli)
            if await post(notice, at: at, id: id) { ledger.append(.init(id, at.epochMilli, notice)) }
        }
        room = max(0, room - ledger.count)
        let scheduled = Array(upcoming.sorted(by: { $0.at < $1.at }).prefix(room))
        for item in scheduled {
            let entry = ScheduledNotices.timedEntry(item.notice, at: item.at)
            if await post(entry.notice, at: item.at, id: entry.id) { ledger.append(entry) }
        }
        prefs.set(ScheduledNotices.toJson(ledger), for: "ledger")
        let f = DateFormatter(); f.dateFormat = "MM-dd HH:mm:ss"
        DiagLog.logger.notice("notify: replan posted-now=\(due.count) scheduled=\(ledger.count) first=\(scheduled.first.map { f.string(from: $0.at) + " " + $0.notice.channel.id } ?? "-", privacy: .public)")
    }

    /// 앞으로 이틀의 브리핑: 그 시각을 위해 새로 받아 둔 브리핑이 있으면 그것, 없으면 받아 둔 정보로 만든 것.
    private static func briefPlan(_ now: Date) -> [(Date, Notice)] {
        let slots = BriefForecast.upcomingSlots(stores.settings.load(), now, .current)
        if slots.isEmpty { return [] }
        let hasToken = AppStores.tokens.hasToken()
        let fresh = freshBrief()
        return slots.compactMap { t in
            if let fresh, fresh.at == t.epochMilli { return (t, fresh.notice) }
            return BriefForecast.offline(stores, at: t, hasToken: hasToken).map { (t, $0) }
        }
    }

    private static func freshBrief() -> (at: Int64, fetchedAt: Int64, notice: Notice)? {
        guard let s = prefs.string("freshBrief"), let o = try? JSONObject(s),
              let n = o.optJSONObject("notice").flatMap(NoticeCodec.fromObject) else { return nil }
        return (o.optLong("at"), o.optLong("fetchedAt"), n)
    }

    /// 다음 브리핑이 [briefRefreshLead] 안이면 eTL을 새로 받아 그 시각의 예약 브리핑 내용을 만든다(지금 보내지 않음).
    /// 실제 상태(마지막 브리핑 시각 등)는 바꾸지 않도록 메모리 상태 저장소로 `runBrief`를 돌린다.
    private static func prefetchBrief(now: Date, token: String?) async {
        guard let token, !token.isEmpty, let slot = nextBriefSlot(now: now),
              slot.timeIntervalSince(now) <= briefRefreshLead else { return }
        if let f = freshBrief(), f.at == slot.epochMilli, now.epochMilli - f.fetchedAt < 15 * 60 * 1000 { return }
        var temp = stores
        temp.state = NotifyStateStore(InMemoryStore())
        let env = NotifyRunner.Environment(stores: temp, token: token, casual: casual, canPost: true,
                                           todayEvents: { CalendarSyncService.todayLines(slot) })
        let r = await NotifyRunner.runBrief(env, now: now)
        guard r.outcome == .SUCCESS, let brief = r.notices.first(where: { $0.channel == .BRIEF }) else { return }
        prefs.set(JSONObject().put("at", slot.epochMilli).put("fetchedAt", now.epochMilli).put("notice", NoticeCodec.toObject(brief)).toString(),
                  for: "freshBrief")
        DiagLog.logger.notice("notify: brief prefetched for slot in \(Int(slot.timeIntervalSince(now) / 60))m")
    }

    // MARK: 확인·브리핑

    /// eTL을 조회해 새 공지·과제·성적·마감 알림을 보낸다(앱 열 때·백그라운드 갱신·"지금 확인").
    /// 공지 중 시험·과제·보강/휴강은 `NotifyRunner`가 "등록 안 한 공지"(`PendingNoticeStore`)로 남긴다(안드로이드와 같다).
    @discardableResult
    static func check(now: Date = Date()) async -> Bool {
        // 예약 브리핑이 이미 떴으면 마지막 브리핑 시각에 먼저 반영한다(같은 브리핑을 또 보내지 않게).
        await reconcile(now: now)
        let granted = await isGranted()
        let settings = stores.settings.load()
        let brief = NotifySchedule.dueBriefSlot(settings, lastBriefAtMillis: stores.state.load().lastBriefAt, now, .current)
        let token = AppStores.tokens.load()
        let env = NotifyRunner.Environment(stores: stores, token: token, casual: casual, canPost: granted,
                                           todayEvents: { CalendarSyncService.todayLines() })
        let result = await NotifyRunner.runCheck(env, now: now, briefDueAt: brief)
        FilesCleanupService.runIfDue() // 받은 파일·작업 폴더 정리(하루 한 번, 네트워크 없음)
        for n in result.notices { await post(n, at: nil) }
        // 캘린더로 내보내기(켜져 있을 때만, 네트워크 없음, 작업 A).
        await CalendarSyncService.syncAfterCheck()
        // 등록 안 한 공지가 새로 남았을 수 있다(채팅 화면 칩·시트 다시 읽기).
        await MainActor.run { NotificationCenter.default.post(name: .pendingNoticesChanged, object: nil) }
        if granted { await prefetchBrief(now: Date(), token: token) }
        await replan(now: Date())
        // 위젯 사본을 바로 쓴다(백그라운드 갱신이 끝나기 전에).
        await MainActor.run { HomeWidgetBridge.publish() }
        return result.outcome != .RETRY
    }

    /// 앱이 앞으로 올 때: 마지막 확인이 1시간(안드로이드 주기)보다 오래됐으면 확인(백그라운드 갱신은 iOS가 미루거나 건너뛸 수 있다),
    /// 아니면 브리핑이 가까울 때 새 브리핑을 받아 두고 예약을 다시 맞춘다.
    static func onActivate(now: Date = Date()) async {
        let settings = stores.settings.load()
        let token = AppStores.tokens.load()
        let stale = now.epochMilli - stores.state.load().lastCheckAt >= Int64(NotifySettings.CHECK_INTERVAL_HOURS) * 3_600_000
        if settings.needsPeriodicCheck, let token, !token.isEmpty, stale {
            await check(now: now)
            return
        }
        if await isGranted() { await prefetchBrief(now: now, token: token) }
        await replan(now: now)
    }

    /// 설정의 "브리핑 미리 보내기"(안드로이드 `BriefWorker` 1회 실행).
    static func briefNow() async {
        let env = NotifyRunner.Environment(stores: stores, token: AppStores.tokens.load(), casual: casual, canPost: await isGranted(),
                                           todayEvents: { CalendarSyncService.todayLines() })
        let result = await NotifyRunner.runBrief(env)
        for n in result.notices { await post(n, at: nil) }
        DiagLog.logger.notice("notify: brief now outcome=\(String(describing: result.outcome), privacy: .public)")
    }

    static func clearEtl() async {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        let removed = pending.map(\.identifier).filter { !$0.contains("mine") }
        center.removePendingNotificationRequests(withIdentifiers: removed)
        await serial.run {
            let gone = Set(removed)
            prefs.set(ScheduledNotices.toJson(ScheduledNotices.fromJson(prefs.string("ledger")).filter { !gone.contains($0.id) }), for: "ledger")
            prefs.remove("freshBrief")
        }
    }

    // MARK: 게시

    /// 알림 하나를 게시(at이 nil이거나 1초 안이면 바로, 아니면 그 시각에)한다. 바로 보낸 것은 알림 기록에 남긴다.
    /// - Returns: 예약했으면 true(장부에 적을 것).
    @discardableResult
    static func post(_ n: Notice, at: Date?, id: String? = nil) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = n.title
        content.body = n.bigText ?? n.text
        content.threadIdentifier = n.channel.id
        switch NotificationPoster.importance(n.channel) {
        case .high: content.interruptionLevel = .timeSensitive; content.sound = .default
        case .normal: content.interruptionLevel = .active; content.sound = .default
        case .low: content.interruptionLevel = .passive
        }
        var info: [String: Any] = ["channel": n.channel.id, "tag": n.tag, "notice": NoticeCodec.toJson(n)]
        if let u = n.url { info["url"] = u }
        if let p = n.prefill { info["prefill"] = p }
        if let a = n.prefillAction { info["prefillAction"] = a }
        if let d = n.draft { info["draft"] = d }
        if n.agenda { info["agenda"] = true }
        if let m = n.mineItemId { info["mineItemId"] = m }
        content.userInfo = info
        let hide = stores.settings.load().hideContentOnLockScreen
        if let category = await LocalNotifications.shared.category(for: NotificationPoster.actions(n), hideOnLockScreen: hide) {
            content.categoryIdentifier = category
        }
        var trigger: UNNotificationTrigger?
        if let at, at.timeIntervalSinceNow > 1 {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: at.timeIntervalSinceNow, repeats: false)
        }
        let center = UNUserNotificationCenter.current()
        if trigger == nil && n.channel == .BRIEF {
            // 새 브리핑은 이미 떠 있는 예약 브리핑을 대신한다(안드로이드는 같은 tag로 교체).
            let delivered = await center.deliveredNotifications().map(\.request.identifier).filter { $0.hasPrefix(briefPrefix) }
            center.removeDeliveredNotifications(withIdentifiers: delivered)
        }
        do {
            try await center.add(UNNotificationRequest(identifier: id ?? n.tag, content: content, trigger: trigger))
        } catch {
            DiagLog.logger.error("notify: add failed \(error.localizedDescription, privacy: .public)")
            return false
        }
        if trigger == nil { stores.history.record(n) }
        return trigger != nil
    }

    // MARK: 알림 탭

    /// 알림 본문·버튼(안드로이드 PendingIntent들): 초안 창, 채팅 입력창 채우기, eTL(LearningX/웹), 내 일정.
    @MainActor
    static func open(_ info: [AnyHashable: Any], action: String, app: AppState) {
        let notice = NoticeCodec.fromJson(info["notice"] as? String) ?? legacyNotice(info)
        let target = NoticeOpen.resolve(notice, action: action)
        DiagLog.logger.notice("notify: opened channel=\(notice.channel.id, privacy: .public) action=\(action, privacy: .public) target=\(String(describing: target).prefix(12), privacy: .public)")
        switch target {
        case .draft(let json):
            app.openFromNotification(prefill: nil, route: AppRoute.draft(json))
        case .chat(let prefill):
            app.openFromNotification(prefill: prefill, route: nil)
        case .agenda(let id):
            app.openFromNotification(prefill: nil, route: id.map { .myScheduleItem($0) } ?? .mySchedule)
        case .etl(let urlString):
            guard let url = URL(string: urlString) else { return }
            UIApplication.shared.open(LearningXLink.fromWebUrl(urlString).flatMap(URL.init(string:)) ?? url) { ok in
                if !ok { UIApplication.shared.open(url) }
            }
        }
    }

    /// 이 버전 전에 게시한 알림(`notice` 키 없음).
    private static func legacyNotice(_ info: [AnyHashable: Any]) -> Notice {
        Notice(Channel(rawValue: info["channel"] as? String ?? "") ?? .DEADLINE, "", "", url: info["url"] as? String, tag: info["tag"] as? String ?? "",
               prefill: info["prefill"] as? String, prefillAction: info["prefillAction"] as? String, draft: info["draft"] as? String,
               agenda: (info["agenda"] as? Bool) == true, mineItemId: (info["mineItemId"] as? NSNumber)?.int64Value)
    }
}

extension Notification.Name {
    /// 알림 확인이 끝나 등록 안 한 공지 목록이 바뀌었을 수 있을 때.
    static let pendingNoticesChanged = Notification.Name("ym.pendingNoticesChanged")
}

/// 비동기 작업을 하나씩 차례로 돌린다(예약 장부를 여러 곳이 동시에 고치지 않게).
actor SerialRunner {
    private var tail: Task<Void, Never>?

    func run(_ op: @escaping @Sendable () async -> Void) async {
        let previous = tail
        let task = Task { await previous?.value; await op() }
        tail = task
        await task.value
    }
}
