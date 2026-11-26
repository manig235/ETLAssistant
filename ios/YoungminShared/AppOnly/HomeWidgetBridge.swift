import ChatKit
import ETLKit
import Foundation
import NotifyKit
import UIKit
import WidgetKit

// 앱 타깃 전용(위젯·공유 확장 타깃에서는 제외). 안드로이드 `HomeWidgets.dataChanged` + `ShareReceiverActivity`의 앱 쪽.

/// 앱 데이터가 바뀔 때마다 위젯 사본을 App Group에 쓰고 위젯을 다시 그리게 한다. 위젯에서 누른 "완료"를 내 마감에 반영한다.
@MainActor
enum HomeWidgetBridge {
    private static var subscription: EtlEvents.Subscription?
    private static var pending: Task<Void, Never>?

    /// 앱 시작 때 한 번(`AppWiring.start`).
    static func start(app: AppState) {
        guard !StudyDataProfile.isDemo else { return }
        AppGroup.logContainer("app")
        subscription = EtlEvents.subscribe { event in
            if case .dataChanged = event { Task { @MainActor in HomeWidgetBridge.publishSoon() } }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                publish()
                ShareInbox.pickUp(app: app)
            }
        }
        NotificationCenter.default.addObserver(forName: .NSCalendarDayChanged, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { publish(force: true) }
        }
        publish(force: true)
        ShareInbox.pickUp(app: app)
    }

    /// 여러 저장이 한꺼번에 일어나면 한 번만 쓴다.
    static func publishSoon() {
        pending?.cancel()
        pending = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            publish()
        }
    }

    /// 위젯 완료 기록을 반영하고, 위젯 사본을 쓰고, 바뀌었으면 위젯을 다시 그린다.
    static func publish(now: Date = Date(), force: Bool = false) {
        guard !StudyDataProfile.isDemo else { return }
        applyCompletions(now: now)
        let snapshot = WidgetSnapshot.make(snapshot: SchedStores.snapshot.load(), mine: SchedStores.mine.load(),
                                           weekly: SchedStores.timetable.load(), changes: SchedStores.changes.load(),
                                           filter: SchedStores.courses.load(), now: now)
        if WidgetFiles.writeSnapshot(snapshot) || force {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    /// 위젯의 "완료"(그 회차만, 항상 완료로). 저장소 `onChanged`가 알림 예약을 다시 맞춘다.
    static func applyCompletions(now: Date = Date()) {
        let completions = WidgetFiles.takeCompletions()
        guard !completions.isEmpty else { return }
        let changed = WidgetCompletion.apply(completions, to: SchedStores.mine.load())
        for d in changed { SchedStores.mine.upsert(d) }
        DiagLog.logger.notice("widget: applied completions=\(completions.count) changed=\(changed.count)")
    }

    /// `youngmin://…`(위젯·공유 확장). 처리했으면 true.
    @discardableResult
    static func handle(_ url: URL, app: AppState) -> Bool {
        guard !StudyDataProfile.isDemo else { return false }
        guard let target = WidgetLinks.parse(url) else { return false }
        DiagLog.logger.notice("widget: open \(url.host ?? "-", privacy: .public)")
        switch target {
        case .schedule:
            app.openFromNotification(prefill: nil, route: .mySchedule)
        case .timetable:
            app.openFromNotification(prefill: nil, route: .timetable)
        case .item(let key):
            if let id = WidgetLinks.mineId(key) {
                app.openFromNotification(prefill: nil, route: .myScheduleItem(id))
            } else if let (courseId, assignmentId) = WidgetLinks.assignment(key) {
                // 과제는 알림과 같은 연결 코드로 LearningX(없으면 eTL 웹)에서 연다.
                let found = SchedStores.snapshot.load()?.assignments.first { $0.courseId == courseId && $0.assignmentId == assignmentId }
                    ?? AssignmentItem(courseId, "", assignmentId, "", nil, true, nil, nil, false, false)
                SchedLinks.openEtl(DeadlineWidgetLogic.assignmentUrl(found))
            } else {
                app.openFromNotification(prefill: nil, route: .mySchedule)
            }
        case .share:
            ShareInbox.pickUp(app: app)
        }
        return true
    }
}

/// 공유 확장이 남긴 내용을 새 대화의 입력창·첨부로 옮긴다(Kotlin `ChatActivity.applyShare`). 자동으로 보내지 않는다.
@MainActor
enum ShareInbox {
    static func pickUp(app: AppState) {
        let shares = PendingShare.pending()
        guard !shares.isEmpty else { return }
        // 첫 설정 전이면 버리고 안내한다(Android: 안내 화면으로 보낸다).
        guard OnboardingLogic.shouldSkip(completed: app.onboardingCompleted, hasEtlToken: AppStores.tokens.hasToken()) else {
            shares.forEach { PendingShare.remove($0.id) }
            ToastCenter.shared.show("먼저 첫 설정을 마쳐 주세요. 공유한 내용은 가져오지 않았어요", long: true)
            return
        }
        for share in shares { apply(share, app: app) }
    }

    private static func apply(_ s: PendingShare, app: AppState) {
        defer { PendingShare.remove(s.id) }
        let chat = app.chatController()
        if s.files.isEmpty && s.text == nil {
            s.failures.uniqued().forEach { ToastCenter.shared.show($0) }
            if s.failures.isEmpty { ToastCenter.shared.show("가져올 수 있는 내용이 없어요") }
            return
        }
        // 답하는 대화가 있어도 새 대화로 연다(그 답변은 뒤에서 계속된다).
        chat.newConversation()
        var failures = s.failures
        var attachments: [Attachment] = []
        for f in s.files {
            guard let url = s.fileURL(f), let data = try? Data(contentsOf: url) else {
                failures.append("파일을 열 수 없어요")
                continue
            }
            if !f.mime.hasPrefix("image/") && Int64(data.count) > AttachmentLogic.MAX_FILE_BYTES {
                failures.append("50MB보다 큰 파일은 첨부할 수 없어요")
                continue
            }
            if let a = ChatController.saveUpload(data: data, name: f.name, mime: f.mime) { attachments.append(a) } else {
                failures.append("첨부하지 못했어요")
            }
        }
        failures.uniqued().forEach { ToastCenter.shared.show($0) }
        let room = max(0, AttachmentLogic.MAX_PENDING - chat.pending.count - chat.importing)
        chat.pending += attachments.prefix(room)
        for extra in attachments.dropFirst(room) { if let p = extra.path { try? FileManager.default.removeItem(atPath: p) } }
        if s.dropped > 0 || attachments.count > room { ToastCenter.shared.show("한 번에 \(AttachmentLogic.MAX_PENDING)개까지 첨부할 수 있어요") }
        DiagLog.logger.notice("share: applied files=\(min(attachments.count, room)) text=\(s.text != nil) failures=\(failures.count) dropped=\(s.dropped)")
        // 입력창을 채우고 채팅 뿌리로(보내지 않음).
        app.openFromNotification(prefill: s.text, route: nil)
    }
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
