import ETLKit
import Foundation

/*
 * 캘린더 연동 "영민이 → 캘린더"의 맞추기 순서와 설정 저장(Kotlin `calendar/CalendarExport.kt`).
 * 기기 캘린더 읽기·쓰기는 [CalendarSyncBackend](EventKit 구현은 CalendarSyncEventKit.swift, 테스트는 메모리 가짜)가 한다.
 * - LOCAL: 앱이 만든 "영민이" 캘린더(로컬 소스, 없으면 iCloud)에 쓴다. 끄거나 대상을 바꾸면 앱이 넣은 일정만 지우고,
 *   사용자가 그 캘린더에 직접 넣은 일정이 있으면 캘린더를 남긴다([CalendarAppCalendarCleanup]). 남긴 캘린더는 다음에 켤 때 다시 쓴다.
 * - GOOGLE: 사용자가 고른 계정 캘린더(iCloud·구글 등)에 쓴다(그 계정의 동기화가 서버로 올린다).
 * 네트워크는 쓰지 않는다. 권한이 없어지면 내보내기를 끄고 이유를 남긴다.
 */

/// 넣을 수 있는 계정 캘린더 하나(Kotlin `CalendarExport.CalendarInfo`).
public struct CalendarExportInfo: Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var account: String

    public init(_ id: String, _ name: String, _ account: String) {
        self.id = id
        self.name = name
        self.account = account
    }
}

/// 앱 캘린더("영민이")를 만들 곳. iOS에는 안드로이드의 로컬 계정이 없어 기기의 로컬 소스를 쓰고, 없으면 iCloud를 쓴다.
public enum CalendarLocalPlacement: Hashable, Sendable {
    /// 이 기기에만("나의 iPhone").
    case device
    /// 로컬 소스가 없어 iCloud에 만든다(다른 기기와 동기화된다).
    case iCloud
    /// 그 밖의 기본 캘린더 계정(이름).
    case other(String)
    /// 만들 곳이 없다(권한이 없거나 계정이 없음).
    case unavailable
}

/// 기기 캘린더 접근(EventKit). 일정·캘린더 ID는 EventKit 식별자.
public protocol CalendarSyncBackend: AnyObject, Sendable {
    /// 전체 접근 권한이 있는가(Kotlin READ + WRITE_CALENDAR).
    var permitted: Bool { get }
    func calendarExists(_ id: String) -> Bool
    /// 앱 캘린더. [known]이 아직 있으면 그것, 없으면 같은 이름이고 영민이가 넣은 일정만 있는 캘린더(다시 설치한 뒤). 없으면 nil.
    func findLocalCalendar(_ known: String?) -> String?
    /// 앱 캘린더가 있는 곳([calendarId]가 nil이면 새로 만들 곳).
    func localPlacement(_ calendarId: String?) -> CalendarLocalPlacement
    /// 다시 쓸 수 있는 "영민이" 캘린더(로컬·iCloud, 고칠 수 있음). 사용자 일정이 있어 남겨 둔 캘린더를 켤 때 다시 쓴다. 없으면 nil.
    func reusableLocalCalendar() -> String?
    /// 앱 캘린더를 만든다. 새 식별자.
    func createLocalCalendar() throws -> String?
    /// 일정을 넣을 수 있는 캘린더([excluding](앱 캘린더)는 뺀다). "영민이"라는 이름을 앞에.
    func accountCalendars(excluding: String?) -> [CalendarExportInfo]
    /// 캘린더를 지운다. 사용자 일정이 남아 있거나 다 읽지 못하면 지우지 않고 오류를 낸다(마지막 안전장치).
    func deleteCalendar(_ id: String) throws
    /// [calendarId]의 일정을 넓은 기간에 걸쳐 읽는다(앱 캘린더 정리용). 캘린더가 없으면 nil.
    func scanCalendar(_ calendarId: String, now: Date) throws -> CalendarScan?
    /// [calendarId]에서 영민이가 넣은 일정(주소 `youngmin://item/` 또는 설명 끝 문구)을 모두 지운다(매핑을 잃었을 때).
    func wipeAppEvents(_ calendarId: String, now: Date) throws
    /// 매핑된 일정 중 [calendarId]에 아직 있는 것의 지금 모양(키 → 일정). 식별자가 바뀌었으면 주소의 키로 다시 찾는다(그때 [CalRow.eventId]가 새 식별자).
    func liveRows(_ calendarId: String, _ entries: [String: CalendarSyncLogic.Entry]) throws -> [String: CalRow]
    func readRow(_ eventId: String) -> CalRow?
    /// 새 일정(주소 `youngmin://item/{key}`를 단다). 새 식별자.
    func insert(_ calendarId: String, _ item: CalItem) throws -> String?
    /// 일정 내용을 고친다(주소는 그대로). 없으면 false.
    func update(_ eventId: String, _ item: CalItem) throws -> Bool
    func delete(_ eventId: String) throws
    func deleteEvents(_ ids: [String]) throws
    /// [ids] 중 [calendarId]에 아직 있는 것(지운 뒤 확인용).
    func existing(_ calendarId: String, _ ids: [String]) throws -> Set<String>
}

/// 캘린더가 지워지지 않았을 때 등(Kotlin `IllegalStateException`).
public struct CalendarSyncError: Error, Sendable {
    public var message: String
    public init(_ message: String) { self.message = message }
}

/// 설정·상태 저장(prefs `calendar_export`: `settings`, `lastResult`, `note`, `mapping`; iOS 추가 `localCalendar` = 앱 캘린더 식별자).
public final class CalendarExportStore: @unchecked Sendable {
    public let prefs: KeyValueStore
    private let lock = NSRecursiveLock()

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "calendar_export")) { self.prefs = prefs }

    public func settings() -> CalendarExportSettings { lock.withLock { CalendarExportSettings.fromJson(prefs.string("settings")) } }
    public func saveSettings(_ s: CalendarExportSettings) { lock.withLock { prefs.set(s.toJson(), for: "settings") } }

    /// 마지막 결과 한 줄과 사용자에게 알릴 이유(권한이 꺼져 멈춤 등).
    public func lastResult() -> String? { prefs.string("lastResult") }
    public func note() -> String? { prefs.string("note") }
    public func clearNote() { prefs.remove("note") }

    public func setResult(_ result: String?, note: String? = nil) {
        lock.withLock {
            if let result { prefs.set(result, for: "lastResult") }
            if let note { prefs.set(note, for: "note") } else { prefs.remove("note") }
        }
    }

    public func loadMapping() -> CalendarSyncLogic.Mapping? { lock.withLock { CalendarSyncLogic.mappingFromJson(prefs.string("mapping")) } }
    public func saveMapping(_ calendarId: String, _ entries: [String: CalendarSyncLogic.Entry]) {
        lock.withLock { prefs.set(CalendarSyncLogic.mappingToJson(calendarId, entries), for: "mapping") }
    }
    public func clearMapping() { prefs.remove("mapping") }

    public var localCalendar: String? {
        get { prefs.string("localCalendar")?.nilIfEmpty }
        set { if let newValue { prefs.set(newValue, for: "localCalendar") } else { prefs.remove("localCalendar") } }
    }

    /// 내보낸 일정 식별자와 앱 캘린더 식별자(캘린더 읽기에서 영민이가 넣은 일정을 뺄 때 쓴다. EventKit을 건드리지 않는다).
    /// 앱 캘린더는 내보내기에 쓰는 동안만 넣는다. 끈 뒤 사용자 일정이 있어 남겨 둔 캘린더는 사용자 캘린더로 본다.
    public func ownIdentifiers() -> (events: Set<String>, calendars: Set<String>) {
        lock.withLock {
            let mapping = loadMapping()
            let ids = Set(mapping?.entries.values.filter { !$0.tombstone }.map(\.eventId) ?? [])
            let s = settings()
            let inUse = localCalendar.flatMap { id in mapping?.calendarId == id || (s.enabled && s.target == .LOCAL) ? id : nil }
            return (events: ids, calendars: Set([inUse].compactMap { $0 }))
        }
    }
}

/// 맞추기(내보내기 + 캘린더에서 고친 것 되가져오기)와 켜기·끄기·대상 바꾸기(Kotlin `object CalendarExport`).
/// 메서드는 한 번에 하나씩 돈다(잠금, Kotlin `@Synchronized`). 어떤 예외도 밖으로 내보내지 않는다.
public final class CalendarExport: @unchecked Sendable {
    public static let ACCOUNT_NAME = "영민이"
    public static let DISPLAY_NAME = "영민이"
    /// 앱 캘린더 색(0xAARRGGBB).
    public static let COLOR = 0xFF2F6BFF

    public let store: CalendarExportStore
    public let backend: CalendarSyncBackend
    private let mine: MyDeadlineStore
    private let snapshot: SnapshotCache
    private let courses: CourseFilterStore
    private let lock = NSRecursiveLock()

    /// 맞춘 뒤 내 마감이 바뀌었을 때(화면을 다시 읽게). 맞춘 스레드에서 불린다.
    public var onChanged: (@Sendable () -> Void)?
    /// 진단 기록(Kotlin `CodexRunner.log`).
    public var log: (@Sendable (String) -> Void)?
    /// 마지막 끄기·대상 바꾸기에서 사용자 일정이 있어 앱 캘린더를 남겼는가(화면 안내용).
    public private(set) var lastCleanupKeptCalendar = false

    public init(store: CalendarExportStore = CalendarExportStore(), backend: CalendarSyncBackend, mine: MyDeadlineStore,
                snapshot: SnapshotCache, courses: CourseFilterStore) {
        self.store = store
        self.backend = backend
        self.mine = mine
        self.snapshot = snapshot
        self.courses = courses
    }

    public var hasPermission: Bool { backend.permitted }
    public func settings() -> CalendarExportSettings { store.settings() }
    public func saveSettings(_ s: CalendarExportSettings) { store.saveSettings(s) }

    /// 설정 화면 "캘린더 연동" 줄의 요약. [showEnabled]·[assistantEnabled]는 "캘린더 → 영민이" 설정(AssistantKit).
    public func summary(showEnabled: Bool, assistantEnabled: Bool) -> String {
        let s = store.settings()
        var parts: [String] = []
        if s.enabled {
            parts.append(s.target == .LOCAL ? "\"\(Self.DISPLAY_NAME)\" 캘린더에 넣는 중" : "\"\(s.googleCalendarName)\" 캘린더에 넣는 중")
        }
        if showEnabled { parts.append("내 캘린더 일정 보기") }
        if assistantEnabled { parts.append("비서가 캘린더 사용") }
        if parts.isEmpty { return store.note() ?? "꺼짐" }
        return parts.joined(separator: " · ")
    }

    /// 앱 캘린더. 찾으면 식별자를 기억한다.
    public func localCalendarId() -> String? {
        lock.withLock {
            guard backend.permitted else { return nil }
            let id = backend.findLocalCalendar(store.localCalendar)
            if let id, id != store.localCalendar { store.localCalendar = id }
            return id
        }
    }

    /// 앱 캘린더가 있는(없으면 만들) 곳. 화면 안내 문구에 쓴다.
    public func localPlacement() -> CalendarLocalPlacement {
        backend.permitted ? backend.localPlacement(localCalendarId()) : .unavailable
    }

    public func accountCalendars() -> [CalendarExportInfo] {
        guard backend.permitted else { return [] }
        return backend.accountCalendars(excluding: localCalendarId())
    }

    private func notifyChanged() { onChanged?() }

    // ---- 동기화 ------------------------------------------------------------------

    /// 켜져 있으면 맞춘다(알림 확인 끝·캘린더 변경·내 마감 변경 때). 결과 문구(꺼져 있으면 nil).
    @discardableResult
    public func syncIfEnabled(_ now: Date = Date()) -> String? {
        lock.withLock {
            let s = store.settings()
            if !s.enabled { return nil }
            if !backend.permitted { return disableForPermission() }
            do {
                return try sync(s, now)
            } catch is CalendarPermissionError {
                return disableForPermission()
            } catch {
                let msg = "내보내지 못했어요(\(String(describing: type(of: error))))"
                store.setResult(msg)
                log?(msg)
                return msg
            }
        }
    }

    private func disableForPermission() -> String {
        // 권한이 없으니 지울 수도 없다. 매핑은 남겨 두어 다시 켜면 이어서 맞춘다.
        var s = store.settings()
        s.enabled = false
        store.saveSettings(s)
        let msg = "캘린더 권한이 꺼져서 내보내기를 멈췄어요. 다시 켜려면 여기서 켜 주세요."
        store.setResult(nil, note: msg)
        log?("권한 없음 · 내보내기 멈춤")
        return msg
    }

    private func sync(_ s: CalendarExportSettings, _ now: Date) throws -> String {
        let calendarId: String
        switch s.target {
        case .LOCAL:
            let stored = store.loadMapping()?.calendarId.nilIfEmpty
            guard let id = stored.flatMap({ backend.calendarExists($0) ? $0 : nil }) ?? localCalendarId() else {
                // 앱 캘린더가 사라졌다(사용자가 캘린더 앱에서 지움 등). 다시 만들지 않고 멈춘다.
                var off = s
                off.enabled = false
                store.saveSettings(off)
                store.clearMapping()
                let msg = "\"\(Self.DISPLAY_NAME)\" 캘린더가 없어져서 내보내기를 껐어요. 다시 켜면 새로 만들어요."
                store.setResult(nil, note: msg)
                return msg
            }
            calendarId = id
        case .GOOGLE:
            guard let id = s.googleCalendarId, backend.accountCalendars(excluding: nil).contains(where: { $0.id == id }) else {
                var off = s
                off.enabled = false
                store.saveSettings(off)
                let msg = "고른 캘린더를 찾지 못해 내보내기를 껐어요. 캘린더를 다시 골라 주세요."
                store.setResult(nil, note: msg)
                return msg
            }
            calendarId = id
        }
        let local = s.target == .LOCAL
        let stored = store.loadMapping()
        // 다른 캘린더의 매핑이면 쓰지 않는다(대상을 바꿀 때 정리는 switchTarget/disable에서 한다).
        var entries = stored?.calendarId == calendarId ? stored!.entries : [:]
        // 다시 설치 등으로 매핑을 잃었으면 앱 캘린더에서 앱이 넣은 일정을 지우고 새로 채운다(사용자가 직접 넣은 일정은 두고).
        if local && stored?.calendarId != calendarId { try backend.wipeAppEvents(calendarId, now: now) }

        let assignments = snapshot.load()?.assignments
        var list = mine.load()
        let filter = courses.load()
        var desired = CalendarSyncLogic.desired(s, assignments, list, filter, now)
        let byKey = try backend.liveRows(calendarId, entries.filter { !$0.value.tombstone })
        // 식별자가 바뀐 일정(계정 동기화 등)은 새 식별자로 잇는다.
        for (key, row) in byKey where entries[key] != nil && entries[key]!.eventId != row.eventId { entries[key]!.eventId = row.eventId }
        var rows: [String: CalRow] = [:]
        for row in byKey.values { rows[row.eventId] = row }
        let live = Set(rows.keys)

        // 캘린더에서 고친 것: 내 마감은 되가져오고, eTL 과제·반복 회차는 되돌린다.
        let edits = CalendarSyncLogic.edits(desired, entries, rows, list, pullBack: s.pullBack)
        var force = Set<String>()
        var pulled = 0
        var reverted = 0
        var repeatingReverted = 0
        var nextId = Date().epochMilli
        for e in edits {
            switch e {
            case let .Baseline(key, rowHash):
                if entries[key] != nil { entries[key]!.rowHash = rowHash; entries[key]!.reverts = 0 }
            case let .Accept(key, rowHash):
                if entries[key] != nil { entries[key]!.rowHash = rowHash }
            case let .Revert(key, repeating):
                force.insert(key)
                reverted += 1
                if repeating { repeatingReverted += 1 }
                if entries[key] != nil { entries[key]!.reverts += 1 }
            case let .PullMine(p):
                let old = list.first { $0.id == p.id }
                let new = old.flatMap { CalendarSyncLogic.applyPull($0, p, nextId) }
                nextId += 1
                guard let old, let new else { force.insert(p.key); reverted += 1; continue }
                if new.id != old.id { mine.delete(old.id) }
                mine.upsert(new)
                pulled += 1
                // 같은 일정을 새 ID의 키로 옮긴다(지우고 다시 넣지 않게). 다음 맞추기에서 앱 모양(제목 앞말 등)으로 고쳐 쓴다.
                if var moved = entries.removeValue(forKey: p.key) {
                    moved.hash = ""
                    moved.reverts = 0
                    entries[CalendarSyncLogic.mineKey(new)] = moved
                }
                log?("캘린더에서 고친 내 마감을 반영함")
            }
        }
        if pulled > 0 {
            list = mine.load()
            desired = CalendarSyncLogic.desired(s, assignments, list, filter, now)
        }
        let plan = CalendarSyncLogic.plan(desired, entries, live, now, forceUpdate: force)

        for k in plan.tombstone where entries[k] != nil { entries[k]!.eventId = CalendarSyncLogic.TOMBSTONE }
        for k in plan.forget { entries.removeValue(forKey: k) }
        for op in plan.ops {
            switch op {
            case let .Insert(item):
                if let id = try backend.insert(calendarId, item) {
                    // 쓴 뒤 다시 읽은 모양을 기준으로 둔다(캘린더가 값을 바꿔 저장해도 사용자가 고친 것으로 보지 않게).
                    entries[item.key] = CalendarSyncLogic.Entry(id, item.hash, item.start.epochMilli, backend.readRow(id)?.hash ?? item.rowHash)
                }
            case let .Update(eventId, item):
                let ok = try backend.update(eventId, item)
                let reverts = force.contains(item.key) ? (entries[item.key]?.reverts ?? 0) : 0
                entries[item.key] = ok
                    ? CalendarSyncLogic.Entry(eventId, item.hash, item.start.epochMilli, backend.readRow(eventId)?.hash ?? item.rowHash, reverts)
                    : CalendarSyncLogic.Entry(CalendarSyncLogic.TOMBSTONE, item.hash, item.start.epochMilli)
            case let .Delete(key, eventId):
                try backend.delete(eventId)
                entries.removeValue(forKey: key)
            }
            // 중간에 죽어도 넣은 일정이 매핑에서 빠지지 않게 조금씩 저장한다.
            store.saveMapping(calendarId, entries)
        }
        store.saveMapping(calendarId, entries)
        let msg = CalendarSyncLogic.resultLabel(plan, now) + CalendarSyncLogic.editsLabel(pulled, reverted, repeatingReverted)
        store.setResult(msg)
        if !plan.ops.isEmpty { log?(msg) }
        if pulled > 0 { notifyChanged() }
        return msg
    }

    /// 내보내기를 켠다(권한은 화면이 미리 받는다). LOCAL이면 앱 캘린더를 찾거나 만든다. 실패하면 이유.
    public func enable(_ s: CalendarExportSettings, now: Date = Date()) -> String? {
        lock.withLock {
            if !backend.permitted { return "캘린더 권한이 필요해요" }
            do {
                let targetId: String
                switch s.target {
                case .LOCAL:
                    if let id = localCalendarId() {
                        targetId = id
                    } else if let id = backend.reusableLocalCalendar() {
                        // 전에 끌 때 사용자 일정이 있어 남겨 둔 캘린더가 있으면 새로 만들지 않고 다시 쓴다(같은 이름 캘린더가 둘 생기지 않게).
                        store.localCalendar = id
                        targetId = id
                    } else if let id = try backend.createLocalCalendar() {
                        store.localCalendar = id
                        targetId = id
                    } else {
                        return "\"\(Self.DISPLAY_NAME)\" 캘린더를 만들지 못했어요"
                    }
                case .GOOGLE:
                    guard let id = s.googleCalendarId else { return "내보낼 캘린더를 골라 주세요" }
                    if !backend.accountCalendars(excluding: nil).contains(where: { $0.id == id }) { return "고른 캘린더를 찾지 못했어요" }
                    targetId = id
                }
                // 꺼진 내보내기도 권한을 잃었거나 정리에 실패해 일정을 가지고 있을 수 있다. 그 매핑을 버리고 다른 곳으로 시작하지 않는다.
                if let m = store.loadMapping(), m.calendarId != targetId {
                    if let err = cleanup(store.settings()) { return err }
                }
                var on = s
                on.enabled = true
                store.saveSettings(on)
                store.clearNote()
                syncIfEnabled(now)
                return nil
            } catch is CalendarPermissionError {
                return "캘린더 권한이 필요해요"
            } catch {
                return "내보내기를 켜지 못했어요(\(String(describing: type(of: error))))"
            }
        }
    }

    /// 내보내기를 끈다. 앱이 넣은 일정만 지운다. LOCAL이면 그 뒤 "영민이" 캘린더에 사용자가 넣은 일정이 없을 때만 캘린더도 지운다.
    /// 권한이 없으면 지우지 못하고 설정만 끈다(이유를 돌려준다).
    @discardableResult
    public func disable() -> String? {
        lock.withLock {
            lastCleanupKeptCalendar = false
            let s = store.settings()
            var off = s
            off.enabled = false
            store.saveSettings(off)
            store.clearNote()
            let r = cleanup(s)
            store.setResult(r ?? (lastCleanupKeptCalendar ? "꺼짐 · 넣은 일정을 지웠어요(직접 넣은 일정이 있어 캘린더는 남겼어요)" : "꺼짐 · 넣은 일정을 지웠어요"))
            return r
        }
    }

    /// 끈 뒤 화면에 보일 문구.
    public static func offMessage(keptCalendar: Bool) -> String {
        keptCalendar
            ? "껐어요. 영민이가 넣은 일정만 지우고, 직접 넣은 일정이 있어 \"\(DISPLAY_NAME)\" 캘린더는 남겨 뒀어요"
            : "껐어요. 넣은 일정을 지웠어요"
    }

    /// 대상을 옮긴 뒤 화면에 보일 문구.
    public static func switchedMessage(keptCalendar: Bool) -> String {
        keptCalendar ? "옮겼어요. 직접 넣은 일정이 있어 \"\(DISPLAY_NAME)\" 캘린더는 남겨 뒀어요" : "옮겼어요"
    }

    /// 대상(LOCAL/GOOGLE, 다른 계정 캘린더)을 바꾼다: 이전 대상을 정리하고 새 대상으로 켠다.
    public func switchTarget(_ next: CalendarExportSettings) -> String? {
        lock.withLock {
            lastCleanupKeptCalendar = false
            let cur = store.settings()
            return CalendarCleanupPolicy.switchTarget(
                cleanup: { cur.enabled || store.loadMapping() != nil ? cleanup(cur) : nil },
                activate: {
                    var off = next
                    off.enabled = false
                    store.saveSettings(off)
                    return cur.enabled || next.enabled ? enable(next) : nil
                })
        }
    }

    /// 이전 대상에서 앱이 넣은 일정을 지운다(끄기·대상 바꾸기·켤 때 남은 매핑 정리). 사용자가 넣은 일정은 지우지 않는다.
    private func cleanup(_ s: CalendarExportSettings, now: Date = Date()) -> String? {
        CalendarCleanupPolicy.cleanup(
            permitted: backend.permitted,
            delete: {
                let mapping = store.loadMapping()
                let localId = localCalendarId()
                // 꺼져 있을 때도 매핑이 실제 이전 대상을 가진다. 지금 고른 것만 보면 실패한 끄기 뒤의 일정을 버려 둘 수 있다.
                let id = mapping?.calendarId.nilIfEmpty ?? (s.target == .LOCAL ? localId : nil)
                if let id, id == localId {
                    try cleanupAppCalendar(id, mapping?.calendarId == id ? mapping!.entries : [:], now)
                } else if let mapping {
                    let ids = mapping.entries.values.filter { !$0.tombstone }.map(\.eventId)
                    if !ids.isEmpty {
                        try backend.deleteEvents(ids)
                        if !(try backend.existing(mapping.calendarId, ids)).isEmpty { throw CalendarSyncError("Events were not deleted") }
                    }
                }
            },
            forget: { store.clearMapping() })
    }

    /// 앱 캘린더: 앱이 넣은 일정(매핑 + 영민이 표시)만 지우고, 다시 읽어 사용자 일정이 없다고 확인될 때만 캘린더를 지운다.
    /// 남긴 캘린더는 식별자를 기억해 다음에 켤 때 다시 쓴다(내보내기에 쓰지 않는 동안은 캘린더 읽기에서 사용자 캘린더로 보인다).
    private func cleanupAppCalendar(_ id: String, _ entries: [String: CalendarSyncLogic.Entry], _ now: Date) throws {
        let before = try backend.scanCalendar(id, now: now)
        let ids = CalendarAppCalendarCleanup.eventsToDelete(mapped: entries, scan: before)
        if !ids.isEmpty {
            try backend.deleteEvents(ids)
            if !(try backend.existing(id, ids)).isEmpty { throw CalendarSyncError("Events were not deleted") }
        }
        let after = try backend.scanCalendar(id, now: now)
        if CalendarAppCalendarCleanup.canRemoveCalendar(after) {
            try backend.deleteCalendar(id)
            if backend.calendarExists(id) { throw CalendarSyncError("Calendar was not deleted") }
            store.localCalendar = nil
        } else if backend.calendarExists(id) {
            lastCleanupKeptCalendar = true
            log?("앱 캘린더에 사용자 일정 \(CalendarAppCalendarCleanup.userEventCount(after))개가 있어 캘린더는 남김")
        } else {
            store.localCalendar = nil
        }
    }

    // ---- .ics ---------------------------------------------------------------------

    /// 설정의 항목 선택(eTL·내 마감·완료 포함)을 따른 .ics 내용. 받아 둔 정보만 쓴다.
    public func icsContent(_ now: Date = Date()) -> String {
        CalendarSyncLogic.ics(store.settings(), snapshot.load()?.assignments ?? [], mine.load(), courses.load(), now)
    }

    // ---- 권한 없이: 캘린더 앱의 "일정 추가" 화면 ------------------------------------

    /// 캘린더 앱의 일정 추가 화면(iOS `EKEventEditViewController`)에 채울 값(Kotlin `insertIntent`).
    public struct InsertDraft: Hashable, Sendable {
        public var title: String
        public var start: Date
        public var end: Date
        public var description: String
        public var location: String
        public var recurrence: CalendarSyncLogic.Recurrence?
        /// 바쁨(시간이 있는 일정).
        public var busy: Bool { end > start }
    }

    public static func insertDraft(_ a: AssignmentItem) -> InsertDraft? {
        guard let item = CalendarSyncLogic.etlItem(a) else { return nil }
        return InsertDraft(title: item.title, start: item.start, end: item.start, description: item.description, location: "", recurrence: nil)
    }

    /// [series]는 저장된 원래 항목, [occurrence]는 누른 회차(반복이 아니면 nil). 반복 내 마감은 반복 규칙을 넣는다.
    public static func insertDraft(series: MyDeadline, occurrence: MyDeadline?) -> InsertDraft? {
        var plain = series
        plain.done = false
        if series.repeat == nil {
            let item = CalendarSyncLogic.mineItem(plain)
            return InsertDraft(title: item.title, start: item.start, end: item.end, description: item.description, location: item.location, recurrence: nil)
        }
        guard let first = CalendarSyncLogic.dtstartFor(series, occurrence) else { return nil }
        let fromFirst = first.occurrence == MyDeadlines.firstOccurrence(series)?.occurrence
        return InsertDraft(title: CalendarSyncLogic.mineTitle(plain), start: first.due, end: first.due,
                           description: CalendarSyncLogic.mineDescription(series), location: "",
                           recurrence: CalendarSyncLogic.recurrence(series, fromFirst: fromFirst))
    }
}
