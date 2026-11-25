import Foundation
import ETLKit

/// [NotifySettings] 저장소(Kotlin prefs `notify_settings`). 비밀값은 여기에 두지 않는다.
public final class NotifySettingsStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSLock()
    public var onChanged: (@Sendable () -> Void)?

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "notify_settings"), onChanged: (@Sendable () -> Void)? = nil) {
        self.prefs = prefs
        self.onChanged = onChanged
    }

    public func load() -> NotifySettings {
        lock.withLock {
            let d = NotifySettings()
            let briefs = Array(distinct(readInts("briefMinutesOfDay", d.briefMinutesOfDay).map { min(max($0, 0), 1439) }).prefix(2))
            return NotifySettings(
                deadlineAlerts: prefs.bool("deadlineAlerts", default: d.deadlineAlerts),
                announcementAlerts: prefs.bool("announcementAlerts", default: d.announcementAlerts),
                deadlineWindowsMinutes: loadWindows(d.deadlineWindowsMinutes),
                briefEnabled: prefs.bool("briefEnabled", default: d.briefEnabled),
                briefMinutesOfDay: briefs.isEmpty ? d.briefMinutesOfDay : briefs,
                quietEnabled: prefs.bool("quietEnabled", default: d.quietEnabled),
                quietStartMinutes: min(max(prefs.int("quietStartMinutes", default: d.quietStartMinutes), 0), 1439),
                quietEndMinutes: min(max(prefs.int("quietEndMinutes", default: d.quietEndMinutes), 0), 1439),
                hideContentOnLockScreen: prefs.bool("hideContentOnLockScreen", default: d.hideContentOnLockScreen),
                changeAlerts: prefs.bool("changeAlerts", default: d.changeAlerts),
                classAlerts: prefs.bool("classAlerts", default: d.classAlerts),
                classLeadMinutes: min(max(prefs.int("classLeadMinutes", default: d.classLeadMinutes), 1), 120),
                submitAlerts: prefs.bool("submitAlerts", default: d.submitAlerts),
                submitOkAlerts: prefs.bool("submitOkAlerts", default: d.submitOkAlerts),
                newFileAlerts: prefs.bool("newFileAlerts", default: d.newFileAlerts),
                examDoneAlerts: prefs.bool("examDoneAlerts", default: d.examDoneAlerts),
                pendingSheet: prefs.bool("pendingSheet", default: d.pendingSheet)
            )
        }
    }

    public func save(_ s: NotifySettings) {
        lock.withLock {
            prefs.set(s.deadlineAlerts, for: "deadlineAlerts")
            prefs.set(s.announcementAlerts, for: "announcementAlerts")
            prefs.set(JSONArray(NotifySettings.normalizeWindows(s.deadlineWindowsMinutes).map { Optional($0 as Any) }).toString(), for: "deadlineWindowsMinutes")
            prefs.remove("deadlineWindowsHours")
            prefs.set(NotifySettingsStore.WINDOWS_VERSION, for: "deadlineWindowsVersion")
            prefs.set(s.briefEnabled, for: "briefEnabled")
            prefs.set(JSONArray(s.briefMinutesOfDay.map { Optional($0 as Any) }).toString(), for: "briefMinutesOfDay")
            prefs.remove("checkIntervalHours")
            prefs.set(s.quietEnabled, for: "quietEnabled")
            prefs.set(s.quietStartMinutes, for: "quietStartMinutes")
            prefs.set(s.quietEndMinutes, for: "quietEndMinutes")
            prefs.set(s.hideContentOnLockScreen, for: "hideContentOnLockScreen")
            prefs.set(s.changeAlerts, for: "changeAlerts")
            prefs.set(s.classAlerts, for: "classAlerts")
            prefs.set(s.classLeadMinutes, for: "classLeadMinutes")
            prefs.set(s.submitAlerts, for: "submitAlerts")
            prefs.set(s.submitOkAlerts, for: "submitOkAlerts")
            prefs.set(s.newFileAlerts, for: "newFileAlerts")
            prefs.set(s.examDoneAlerts, for: "examDoneAlerts")
            prefs.set(s.pendingSheet, for: "pendingSheet")
        }
        onChanged?()
    }

    private static let WINDOWS_VERSION = 3

    /// 마감 알림 시점(분). 저장 형식 버전: 1 = 옛 기본값 → 새 기본값, 2 = 시간 단위(`deadlineWindowsHours`) → ×60, 3 = 분 단위.
    private func loadWindows(_ def: [Int]) -> [Int] {
        switch prefs.int("deadlineWindowsVersion", default: 1) {
        case 1: return def
        case 2: return NotifySettings.normalizeWindows(readInts("deadlineWindowsHours", def.map { $0 / 60 }).map { $0 * 60 })
        default: return NotifySettings.normalizeWindows(readInts("deadlineWindowsMinutes", def))
        }
    }

    private func readInts(_ key: String, _ def: [Int]) -> [Int] {
        guard let raw = prefs.string(key), let a = try? JSONArray(raw) else { return def }
        let list = a.toIntList()
        return list.isEmpty ? def : list
    }
}

/// [NotifyState] 저장소(Kotlin prefs `notify_state`의 `state`). 계획·게시·기록은 [NoticeDelivery.lock] 하나로 묶는다.
public final class NotifyStateStore: @unchecked Sendable {
    private let prefs: KeyValueStore

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "notify_state")) { self.prefs = prefs }

    public func load() -> NotifyState { NoticeDelivery.lock.withLock { NotifyState.fromJson(prefs.string("state")) } }

    public func save(_ s: NotifyState) { NoticeDelivery.lock.withLock { prefs.set(s.toJson().toString(), for: "state") } }

    /// 읽고 바꾸고 저장한다(한 잠금 안에서).
    public func update(_ block: (inout NotifyState) -> Void) {
        NoticeDelivery.lock.withLock {
            var s = load()
            block(&s)
            save(s)
        }
    }
}
