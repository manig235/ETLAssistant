import Foundation
import ETLKit

// 캘린더 연동 "캘린더 → 영민이"의 기기 쪽 계약(Kotlin `CalendarReader` + `CalendarUndo`).
// 실제 구현은 EventKit(`CalendarEventKitStore`), 테스트는 메모리 가짜 저장소를 쓴다.

/// 폰의 캘린더 하나(목록·선택 화면용, Kotlin `CalendarReader.Info`). [writable]은 일정을 넣고 고칠 수 있는 캘린더.
public struct CalendarInfo: Hashable, Sendable, Identifiable {
    public var id: Int64
    public var name: String
    public var account: String
    /// 0xAARRGGBB.
    public var color: Int
    public var writable: Bool
    public var visible: Bool

    public init(id: Int64, name: String, account: String, color: Int = 0, writable: Bool, visible: Bool = true) {
        self.id = id
        self.name = name
        self.account = account
        self.color = color
        self.writable = writable
        self.visible = visible
    }
}

/// 일정 하나의 지금 모양과, 비서가 고쳐도 되는지 가리는 데 필요한 값.
public struct CalendarEventRow: Hashable, Sendable {
    public var backup: CalendarToolLogic.Backup
    /// 반복 일정이거나 반복의 한 회차를 따로 고친 것(Kotlin RRULE·RDATE·ORIGINAL_ID).
    public var recurring: Bool
    /// 영민이가 넣은 일정이면 [CalendarLogic.APP_URI_PREFIX]로 시작한다(EventKit은 `EKEvent.url`).
    public var customAppUri: String?
    /// 나 말고 다른 사람이 초대된(또는 남이 만든 초대) 일정.
    public var hasGuests: Bool

    public init(backup: CalendarToolLogic.Backup, recurring: Bool = false, customAppUri: String? = nil, hasGuests: Bool = false) {
        self.backup = backup
        self.recurring = recurring
        self.customAppUri = customAppUri
        self.hasGuests = hasGuests
    }
}

/// 캘린더 권한 상태.
public enum CalendarAccess: String, Sendable {
    case notDetermined, denied, restricted, writeOnly, fullAccess
}

/// 폰 캘린더(EventKit) 접근. 모든 ID는 양의 정수(EventKit 식별자는 구현이 정수로 바꿔 기억한다).
/// 일정 시각은 epoch millis, 종일 일정은 UTC 자정(안드로이드 CalendarContract와 같은 규칙)으로 주고받는다.
public protocol CalendarStore: AnyObject, Sendable {
    var access: CalendarAccess { get }
    /// 전체 접근을 요청한다(iOS 17 `requestFullAccessToEvents`). 허용되면 true.
    func requestAccess() async -> Bool
    /// 보이는 캘린더 목록(영민이가 만든 캘린더는 뺀다). 권한이 없으면 빈 목록.
    func calendars() -> [CalendarInfo]
    /// [calendarIds] 캘린더에서 [from, to)와 겹치는 회차(반복 일정은 회차마다). 권한이 없으면 빈 목록.
    func instances(from: Date, to: Date, calendarIds: Set<Int64>) -> [RawInstance]
    /// 영민이가 내보낸 일정 ID와 영민이 캘린더 ID(iOS에는 아직 내보내기가 없어 보통 비어 있다).
    func ownEvents() -> (exported: Set<Int64>, own: Set<Int64>)
    /// 일정 하나(없거나 지워졌으면 nil).
    func event(_ eventId: Int64) -> CalendarEventRow?
    /// [b.calendarId] 캘린더에 새 일정을 넣고 새 ID를 돌려준다. [b.eventId]는 쓰지 않는다.
    func insert(_ b: CalendarToolLogic.Backup) throws -> Int64
    /// 일정을 [b] 모양으로 고친다(캘린더는 옮기지 않는다). 없으면 false.
    func update(_ eventId: Int64, _ b: CalendarToolLogic.Backup) throws -> Bool
    /// 일정을 지운다. 없으면 false.
    func delete(_ eventId: Int64) throws -> Bool
}

public extension CalendarStore {
    func canRead() -> Bool { access == .fullAccess }
    /// 쓰기 전 일정을 다시 읽어 확인하므로 읽기 권한까지 있어야 쓴다(Kotlin `canWrite` = READ + WRITE).
    func canWrite() -> Bool { access == .fullAccess }
}

/// "캘린더 → 영민이" 설정 저장(Kotlin `CalendarReader.settings/saveSettings`, prefs `calendar_read`, 키 `settings`).
public final class CalendarReadSettingsStore: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSLock()
    /// 저장할 때 불린다(화면 갱신용).
    public var onChanged: (@Sendable () -> Void)?

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "calendar_read")) { self.prefs = prefs }

    public func load() -> CalendarReadSettings { lock.withLock { CalendarReadSettings.fromJson(prefs.string("settings")) } }

    public func save(_ s: CalendarReadSettings) {
        lock.withLock { prefs.set(s.toJson(), for: "settings") }
        EtlEvents.dataChanged()
        onChanged?()
    }
}

/// 고른 캘린더의 일정 읽기(Kotlin `CalendarReader.events`).
public enum CalendarReader {
    /// 고른 캘린더([ids])에서 [from, to)와 겹치는 일정. 영민이가 넣은 일정은 뺀다. 권한이 없거나 고른 캘린더가 없으면 빈 목록.
    public static func events(_ store: CalendarStore, _ from: Date, _ to: Date, _ ids: Set<Int64>) -> [CalEvent] {
        if ids.isEmpty || !store.canRead() { return [] }
        let (qFrom, qTo) = CalendarReadLogic.queryRange(from, to)
        let raw = Array(store.instances(from: qFrom, to: qTo, calendarIds: ids).prefix(2000))
        let (exported, own) = store.ownEvents()
        return CalendarReadLogic.events(raw, ids, exported, own, from, to)
    }
}

/// 비서가 바꾼 캘린더 일정의 기록과 되돌리기(Kotlin `CalendarUndo`, prefs `calendar_undo`, 키 `changes`, 14일·30개).
public final class CalendarUndo: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSRecursiveLock()
    public var onChanged: (@Sendable () -> Void)?

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "calendar_undo")) { self.prefs = prefs }

    public func list() -> [CalendarToolLogic.Change] { lock.withLock { CalendarToolLogic.changesFromJson(prefs.string("changes")) } }

    private func save(_ list: [CalendarToolLogic.Change]) {
        prefs.set(CalendarToolLogic.changesToJson(list), for: "changes")
    }

    public func record(_ action: CalendarToolLogic.Action, _ eventId: Int64, _ calendarName: String, _ summary: String,
                       _ before: CalendarToolLogic.Backup?, now: Date = Date()) {
        lock.withLock {
            let previous = list()
            let c = CalendarToolLogic.Change(CalendarToolLogic.nextChangeId(previous, now), now.epochMilli, action, eventId, calendarName, summary, before)
            save(CalendarToolLogic.addChange(previous, c, now))
        }
        onChanged?()
    }

    public func clear() {
        lock.withLock { prefs.remove("changes") }
        onChanged?()
    }

    /// 되돌린다: 넣은 일정은 지우고, 고친 일정은 전 모양으로, 지운 일정은 같은 캘린더에 다시 넣는다(알림·참석자는 되살리지 않는다). 결과 문구.
    @discardableResult
    public func undo(_ id: Int64, store: CalendarStore) -> String {
        let result: String = lock.withLock {
            let history = list()
            guard let c = history.first(where: { $0.id == id }) else { return "기록을 찾지 못했어요" }
            if c.undone { return "이미 되돌렸어요" }
            if !CalendarToolLogic.canUndo(history, c) { return "이 일정의 더 최근 변경부터 되돌려 주세요" }
            if !store.canWrite() { return "캘린더 권한이 필요해요" }
            var restoredEventId: Int64?
            let ok: Bool
            do {
                switch c.action {
                case .ADD: ok = try store.delete(c.eventId)
                case .UPDATE: ok = try c.before.map { try store.update(c.eventId, $0) } ?? false
                case .DELETE:
                    if let b = c.before, store.calendars().contains(where: { $0.id == b.calendarId }) {
                        restoredEventId = try store.insert(b)
                    }
                    ok = restoredEventId != nil
                }
            } catch {
                return "되돌리지 못했어요(\(String(describing: type(of: error))))"
            }
            if !ok { return "되돌리지 못했어요(일정이나 캘린더가 없어졌을 수 있어요)" }
            save(CalendarToolLogic.finishUndo(list(), id, restoredEventId))
            return "되돌렸어요"
        }
        if result == "되돌렸어요" {
            EtlEvents.dataChanged()
            onChanged?()
        }
        return result
    }
}

/// 비서 캘린더 도구가 쓰는 기기 쪽 묶음. 앱은 [shared](EventKit + UserDefaults)를 쓰고, 테스트는 가짜 저장소로 만든 것을
/// `Toolbox.useCalendar(_:)`로 그 도구 상자에만 붙인다.
public final class CalendarToolContext: @unchecked Sendable {
    public let store: CalendarStore
    public let settings: CalendarReadSettingsStore
    public let undo: CalendarUndo

    public init(store: CalendarStore, settings: CalendarReadSettingsStore = CalendarReadSettingsStore(), undo: CalendarUndo = CalendarUndo()) {
        self.store = store
        self.settings = settings
        self.undo = undo
    }

    /// 앱 전체에서 쓰는 것(EventKit 저장소는 처음 쓸 때 만든다).
    public static let shared = CalendarToolContext(store: CalendarEventKitStore())
}
