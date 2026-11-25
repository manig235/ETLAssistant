import AssistantKit
import ChatKit
import ETLKit
import EventKit
import Foundation
import NotifyKit
import UIKit

/// 캘린더 연동의 앱 쪽(Kotlin `CalendarWatch` + `CalendarExport.requestSync` + `CalendarReader.shownEvents/todayLines`).
/// - "영민이 → 캘린더": 내 마감·과목 필터가 바뀌면, 알림 확인이 끝나면, 앱이 앞으로 올 때 뒤에서 맞춘다(켜져 있을 때만).
/// - 캘린더가 바뀌면(`EKEventStoreChanged`, 5초 모아서) 다시 맞추고 떠 있는 화면에 알린다. iOS는 앱이 떠 있을 때만 이 알림을 받는다
///   (꺼져 있는 동안 바뀐 것은 다음에 앱이 앞으로 올 때 맞춘다. 안드로이드는 WorkManager 트리거로 꺼져 있어도 맞춘다).
/// - "캘린더 → 영민이": 고른 캘린더의 일정을 내 일정("캘린더" 칩)과 브리핑("■ 오늘 일정")에 준다.
enum CalendarSyncService {
    static let backend = CalendarSyncEventKit()

    static let export: CalendarExport = {
        let e = CalendarExport(backend: backend, mine: SchedStores.mine, snapshot: SchedStores.snapshot, courses: SchedStores.courses)
        e.onChanged = { notifyChanged() }
        e.log = { msg in DiagLog.logger.notice("calendar: \(msg, privacy: .public)") }
        return e
    }()

    /// "캘린더 → 영민이" 설정·EventKit 읽기(비서 캘린더 도구와 같은 묶음).
    static var read: CalendarToolContext { .shared }

    /// 맞춘 뒤 내 마감이 바뀌었거나 캘린더가 바뀌었을 때(main에서). 내 일정 화면이 다시 읽는다.
    static let changed = Notification.Name("kr.snu.etlassistant.calendarChanged")

    static let DEBOUNCE: TimeInterval = 5

    /// 맞추기는 한 줄로(Kotlin 단일 스레드 executor).
    private static let queue = DispatchQueue(label: "calendar-export", qos: .utility)
    private static let state = State()

    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var pending: DispatchWorkItem?
        var observers: [NSObjectProtocol] = []
        var started = false
    }

    /// 앱 시작 때 한 번(AppWiring.start). 저장소의 onChanged를 이어 붙이고 캘린더 변경을 듣는다.
    @MainActor
    static func start() {
        let first: Bool = state.lock.withLock {
            if state.started { return false }
            state.started = true
            return true
        }
        guard first else { return }
        let mine = SchedStores.mine.onChanged
        SchedStores.mine.onChanged = { mine?(); requestSync() } // 캘린더로 내보내기가 켜져 있으면 뒤에서 맞춘다
        let courses = SchedStores.courses.onChanged
        SchedStores.courses.onChanged = { courses?(); requestSync() } // 숨긴 과목은 캘린더에서도 뺀다
        let center = NotificationCenter.default
        let o1 = center.addObserver(forName: .EKEventStoreChanged, object: nil, queue: nil) { _ in storeChanged() }
        let o2 = center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            requestSync()
            notifyChanged()
        }
        state.lock.withLock { state.observers = [o1, o2] }
        requestSync()
    }

    /// 켜져 있으면 뒤에서 맞춘다(Kotlin `requestSync`).
    static func requestSync() {
        guard export.settings().enabled else { return }
        queue.async { export.syncIfEnabled() }
    }

    /// 알림 확인 끝에서(Kotlin `NotifyRunner` → `syncIfEnabled`). 끝날 때까지 기다린다.
    static func syncAfterCheck() async {
        guard export.settings().enabled else { return }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            queue.async {
                export.syncIfEnabled()
                c.resume()
            }
        }
    }

    /// 화면에서 결과를 기다리는 일(켜기·끄기·옮기기·지금 맞추기). 같은 줄에서 돌린다.
    static func run<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { (c: CheckedContinuation<T, Never>) in
            queue.async { c.resume(returning: work()) }
        }
    }

    /// 캘린더를 지켜볼 필요가 있는가(Kotlin `CalendarWatch.needed`).
    static func needed() -> Bool {
        let e = export.settings()
        let r = read.settings.load()
        return ((e.enabled && e.pullBack) || r.showEnabled) && read.store.canRead()
    }

    /// 캘린더가 바뀐 뒤: 5초 모아서 맞추고(켜져 있을 때만), 떠 있는 화면에 알린다.
    private static func storeChanged() {
        guard needed() else { return }
        let item = DispatchWorkItem {
            export.syncIfEnabled()
            notifyChanged()
        }
        state.lock.withLock {
            state.pending?.cancel()
            state.pending = item
        }
        queue.asyncAfter(deadline: .now() + DEBOUNCE, execute: item)
    }

    static func notifyChanged() {
        DispatchQueue.main.async { NotificationCenter.default.post(name: changed, object: nil) }
    }

    // MARK: 캘린더 → 영민이

    /// "내 캘린더 일정 보기"가 켜져 있는가.
    static var showEnabled: Bool { read.settings.load().showEnabled }

    /// 내 일정·브리핑용: "내 캘린더 일정 보기"가 켜져 있을 때만 읽는다(Kotlin `shownEvents`). 권한이 없으면 빈 목록.
    static func shownEvents(_ from: Date, _ to: Date) -> [NotifyKit.CalEvent] {
        let s = read.settings.load()
        guard s.showEnabled else { return [] }
        return CalendarReader.events(read.store, from, to, s.calendarIds).map(toAgenda)
    }

    /// 내 일정 화면: 오늘(KST)부터 30일.
    static func agendaEvents(_ now: Date = Date()) -> [NotifyKit.CalEvent] {
        let from = DayDate(now).startOfDay
        return shownEvents(from, from.addingTimeInterval(30 * 86_400))
    }

    /// 브리핑 "오늘 일정" 줄(KST 오늘). 꺼져 있거나 권한이 없으면 빈 목록(Kotlin `todayLines`).
    static func todayLines(_ now: Date = Date()) -> [String] {
        let s = read.settings.load()
        guard s.showEnabled else { return [] }
        let today = DayDate(now)
        let from = today.startOfDay
        let events = CalendarReader.events(read.store, from, from.addingTimeInterval(86_400), s.calendarIds)
        return CalendarReadLogic.briefLines(events, today)
    }

    private static func toAgenda(_ e: AssistantKit.CalEvent) -> NotifyKit.CalEvent {
        NotifyKit.CalEvent(eventId: e.eventId, calendarId: e.calendarId, calendarName: e.calendarName, color: e.color, title: e.title,
                           location: e.location, description: e.description, start: e.start, end: e.end, allDay: e.allDay,
                           startDate: e.startDate, endDate: e.endDate, recurring: e.recurring)
    }

    /// 설정 화면 "캘린더 연동" 줄의 요약(Kotlin `CalendarExport.summary`).
    static func summary() -> String {
        let r = read.settings.load()
        return export.summary(showEnabled: r.showEnabled, assistantEnabled: r.assistantEnabled)
    }

    // MARK: 링크
    // 일정 주소 `youngmin://item/{key}`(키의 `:`는 `-`)는 위젯 링크와 같은 모양이라 앱의 `.onOpenURL`(HomeWidgetBridge, 작업 B)이
    // 내 마감 항목(`.myScheduleItem`)·eTL 과제를 연다(Kotlin `CalendarOpenActivity`).

    /// 캘린더 일정 하나를 캘린더 앱에서 연다(iOS는 일정 ID로 열 수 없어 그날을 연다).
    @MainActor
    static func openInCalendarApp(_ e: NotifyKit.CalEvent) {
        guard let url = URL(string: "calshow:\(Int(e.start.timeIntervalSinceReferenceDate))") else { return }
        UIApplication.shared.open(url) { ok in
            if !ok { Task { @MainActor in ToastCenter.shared.show("캘린더 앱을 열 수 없어요") } }
        }
    }
}
