import Foundation
import ETLKit
import NotifyKit
import EventKit
import CoreGraphics

/// EventKit 식별자(문자열) ↔ 도구가 쓰는 양의 정수 ID. 도구 스키마의 `event_id`가 정수라(안드로이드 `Events._ID`) 번호를 붙여 기억한다.
/// 저장: prefs `calendar_ids`, 키 `map` = {"next": N, "c": {캘린더 식별자: n}, "e": {일정 식별자: n}}. 일정 번호는 최근 [EVENT_KEEP]개만 둔다.
final class CalendarIdMap: @unchecked Sendable {
    static let EVENT_KEEP = 5000
    private let prefs: KeyValueStore
    private let lock = NSLock()
    private var loaded = false
    private var next: Int64 = 1
    private var cal: [String: Int64] = [:]
    private var ev: [String: Int64] = [:]
    private var dirty = false

    init(_ prefs: KeyValueStore) { self.prefs = prefs }

    private func loadLocked() {
        if loaded { return }
        loaded = true
        guard let s = prefs.string("map"), let o = try? JSONObject(s) else { return }
        next = max(1, o.optLong("next", 1))
        func read(_ key: String) -> [String: Int64] {
            guard let m = o.optJSONObject(key) else { return [:] }
            var out: [String: Int64] = [:]
            for k in m.keys() { let v = m.optLong(k); if v > 0 { out[k] = v } }
            return out
        }
        cal = read("c")
        ev = read("e")
    }

    private func idLocked(_ ident: String, calendar: Bool) -> Int64 {
        loadLocked()
        if let v = calendar ? cal[ident] : ev[ident] { return v }
        let v = next
        next += 1
        if calendar { cal[ident] = v } else { ev[ident] = v }
        dirty = true
        return v
    }

    func calendarId(_ ident: String) -> Int64 { lock.withLock { idLocked(ident, calendar: true) } }
    func eventId(_ ident: String) -> Int64 { lock.withLock { idLocked(ident, calendar: false) } }

    func calendarIdent(_ id: Int64) -> String? { lock.withLock { loadLocked(); return cal.first { $0.value == id }?.key } }
    func eventIdent(_ id: Int64) -> String? { lock.withLock { loadLocked(); return ev.first { $0.value == id }?.key } }

    func flush() {
        lock.withLock {
            guard dirty else { return }
            dirty = false
            if ev.count > Self.EVENT_KEEP {
                let keep = Set(ev.values.sorted(by: >).prefix(Self.EVENT_KEEP))
                ev = ev.filter { keep.contains($0.value) }
            }
            let c = JSONObject()
            for (k, v) in cal { c.put(k, v) }
            let e = JSONObject()
            for (k, v) in ev { e.put(k, v) }
            prefs.set(JSONObject().put("next", next).put("c", c).put("e", e).toString(), for: "map")
        }
    }
}

/// EventKit 구현(iOS 17 / macOS 14 `requestFullAccessToEvents`). 일반 앱으로 쓰므로 iCloud·구글 등은 그 계정의 동기화가 서버로 올린다.
/// 앱이 직접 보내는 네트워크는 없다. 종일 일정의 날짜는 기기 달력(현재 시간대) 기준으로 읽고 쓴다(EventKit 종일 일정은 시간대가 없다).
public final class CalendarEventKitStore: CalendarStore, @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private var storeValue: EKEventStore?
    private let ids: CalendarIdMap
    /// "영민이 → 캘린더"(NotifyKit `CalendarExport`)가 넣은 일정·만든 캘린더(읽기에서 뺀다).
    private let export: CalendarExportStore

    public init(idPrefs: KeyValueStore = UserDefaultsStore(namespace: "calendar_ids"), export: CalendarExportStore = CalendarExportStore()) {
        ids = CalendarIdMap(idPrefs)
        self.export = export
    }

    private var store: EKEventStore {
        lock.withLock {
            if let s = storeValue { return s }
            let s = EKEventStore()
            storeValue = s
            return s
        }
    }

    private var localCalendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c
    }

    public var access: CalendarAccess {
        if StudyDataProfile.isDemo { return .denied }
        switch EKEventStore.authorizationStatus(for: .event) {
        case .notDetermined: return .notDetermined
        case .restricted: return .restricted
        case .denied: return .denied
        case .writeOnly: return .writeOnly
        case .fullAccess: return .fullAccess
        @unknown default: return .denied
        }
    }

    public func requestAccess() async -> Bool {
        guard !StudyDataProfile.isDemo else { return false }
        let granted = (try? await store.requestFullAccessToEvents()) ?? false
        // 권한이 바뀐 뒤에는 새로 읽어야 캘린더가 보인다.
        lock.withLock { storeValue = nil }
        return granted
    }

    // MARK: 변환

    private func colorInt(_ c: CGColor?) -> Int {
        guard let c, let rgb = c.converted(to: CGColorSpace(name: CGColorSpace.sRGB)!, intent: .defaultIntent, options: nil),
              let comps = rgb.components, comps.count >= 3 else { return 0 }
        func b(_ v: CGFloat) -> Int { Int((max(0, min(1, v)) * 255).rounded()) }
        let a = comps.count >= 4 ? b(comps[3]) : 255
        return (a << 24) | (b(comps[0]) << 16) | (b(comps[1]) << 8) | b(comps[2])
    }

    private func info(_ c: EKCalendar) -> CalendarInfo {
        CalendarInfo(id: ids.calendarId(c.calendarIdentifier), name: c.title.isEmpty ? "(이름 없음)" : c.title,
                     account: c.source?.title ?? "", color: colorInt(c.cgColor), writable: c.allowsContentModifications, visible: true)
    }

    private func day(_ d: Date) -> DayDate {
        let p = localCalendar.dateComponents([.year, .month, .day], from: d)
        return DayDate(year: p.year ?? 1970, month: p.month ?? 1, day: p.day ?? 1) ?? DayDate.of(1970, 1, 1)
    }

    private func localMidnight(_ d: DayDate) -> Date {
        localCalendar.date(from: DateComponents(year: d.year, month: d.month, day: d.day)) ?? d.startOfDay
    }

    /// EKEvent의 시각을 millis로(종일은 UTC 자정, 끝은 다음 날 0시).
    private func times(_ e: EKEvent) -> (Int64, Int64) {
        guard let start = e.startDate else { return (0, 0) }
        let end = e.endDate ?? start
        if e.isAllDay {
            let sd = day(start)
            let last = end > start ? day(end.addingTimeInterval(-1)) : sd
            return (CalendarLogic.utcMidnight(sd), CalendarLogic.utcMidnight(max(last, sd).plusDays(1)))
        }
        return (start.epochMilli, max(end, start).epochMilli)
    }

    private func backup(_ e: EKEvent, _ id: Int64) -> CalendarToolLogic.Backup {
        let (s, en) = times(e)
        let calendarId = e.calendar.map { ids.calendarId($0.calendarIdentifier) } ?? 0
        return CalendarToolLogic.Backup(id, calendarId, e.title ?? "", s, en, e.isAllDay,
                                        e.isAllDay ? "UTC" : (e.timeZone?.identifier ?? CalendarLogic.TZ_ID),
                                        e.location ?? "", e.notes ?? "")
    }

    private func apply(_ b: CalendarToolLogic.Backup, to e: EKEvent) {
        e.title = b.title
        e.location = b.location.isEmpty ? nil : b.location
        e.notes = b.description.isEmpty ? nil : b.description
        if b.allDay {
            let sd = CalendarLogic.utcDate(b.dtStart)
            let last = max(CalendarLogic.utcDate(b.dtEnd).minusDays(1), sd)
            e.isAllDay = true
            e.timeZone = nil
            e.startDate = localMidnight(sd)
            e.endDate = localMidnight(last)
        } else {
            e.isAllDay = false
            e.timeZone = TimeZone(identifier: b.timezone) ?? EtlLogic.kst
            e.startDate = Date(epochMilli: b.dtStart)
            e.endDate = Date(epochMilli: b.dtEnd)
        }
    }

    private func ekEvent(_ eventId: Int64) -> EKEvent? {
        guard let ident = ids.eventIdent(eventId) else { return nil }
        return store.event(withIdentifier: ident)
    }

    // MARK: CalendarStore

    public func calendars() -> [CalendarInfo] {
        guard canRead() else { return [] }
        let own = export.ownIdentifiers().calendars
        let list = lock.withLock { store.calendars(for: .event).filter { !own.contains($0.calendarIdentifier) }.map(info) }
        ids.flush()
        return stableSorted(list) { a, b in
            if a.visible != b.visible { return a.visible }
            if a.account != b.account { return a.account < b.account }
            return a.name < b.name
        }
    }

    public func instances(from: Date, to: Date, calendarIds: Set<Int64>) -> [RawInstance] {
        guard canRead(), !calendarIds.isEmpty, from < to else { return [] }
        let out: [RawInstance] = lock.withLock {
            let cals = store.calendars(for: .event).filter { calendarIds.contains(ids.calendarId($0.calendarIdentifier)) }
            if cals.isEmpty { return [] }
            let predicate = store.predicateForEvents(withStart: from, end: to, calendars: cals)
            var raw: [RawInstance] = []
            for e in store.events(matching: predicate) {
                guard let ident = e.eventIdentifier, let c = e.calendar else { continue }
                let (s, en) = times(e)
                raw.append(RawInstance(ids.eventId(ident), ids.calendarId(c.calendarIdentifier), c.title, colorInt(c.cgColor),
                                       e.title, e.location, e.notes, s, en, e.isAllDay,
                                       e.hasRecurrenceRules ? "RRULE" : nil, e.isDetached ? 1 : nil,
                                       e.url?.absoluteString, e.status == .canceled ? 2 : nil))
                if raw.count >= 2000 { break }
            }
            return raw
        }
        ids.flush()
        return out
    }

    public func ownEvents() -> (exported: Set<Int64>, own: Set<Int64>) {
        let (events, calendars) = export.ownIdentifiers()
        let out = (Set(events.map { ids.eventId($0) }), Set(calendars.map { ids.calendarId($0) }))
        ids.flush()
        return out
    }

    public func event(_ eventId: Int64) -> CalendarEventRow? {
        guard canRead() else { return nil }
        return lock.withLock {
            guard let e = ekEvent(eventId) else { return nil }
            let guests = (e.attendees ?? []).contains { !$0.isCurrentUser } || (e.organizer.map { !$0.isCurrentUser } ?? false)
            return CalendarEventRow(backup: backup(e, eventId), recurring: e.hasRecurrenceRules || e.isDetached,
                                    customAppUri: e.url?.absoluteString, hasGuests: guests)
        }
    }

    public func insert(_ b: CalendarToolLogic.Backup) throws -> Int64 {
        let id: Int64 = try lock.withLock {
            guard let ident = ids.calendarIdent(b.calendarId), let cal = store.calendar(withIdentifier: ident) else {
                throw EtlException("캘린더를 찾을 수 없어요")
            }
            let e = EKEvent(eventStore: store)
            e.calendar = cal
            apply(b, to: e)
            try store.save(e, span: .thisEvent, commit: true)
            guard let eid = e.eventIdentifier else { throw EtlException("일정을 넣지 못했어요") }
            return ids.eventId(eid)
        }
        ids.flush()
        return id
    }

    public func update(_ eventId: Int64, _ b: CalendarToolLogic.Backup) throws -> Bool {
        try lock.withLock {
            guard let e = ekEvent(eventId) else { return false }
            apply(b, to: e)
            try store.save(e, span: .thisEvent, commit: true)
            return true
        }
    }

    public func delete(_ eventId: Int64) throws -> Bool {
        try lock.withLock {
            guard let e = ekEvent(eventId) else { return false }
            try store.remove(e, span: .thisEvent, commit: true)
            return true
        }
    }
}
