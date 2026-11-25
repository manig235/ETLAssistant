import CoreGraphics
import ETLKit
import EventKit
import Foundation

/// "영민이 → 캘린더"의 EventKit 구현(Kotlin `CalendarExport`의 CalendarContract 부분).
/// - 앱 캘린더: 기기의 로컬 소스("나의 iPhone")에 "영민이" 캘린더를 만든다. 로컬 소스가 없으면(iCloud 캘린더를 쓰는 기기) iCloud에 만든다.
/// - 일정: 반복 없이 한 회차씩, 시간대 Asia/Seoul, 알림 없음, 주소 `youngmin://item/{key}`(키의 `:`는 `-`, 캘린더 앱에서 누르면 영민이가 열린다).
/// 일반 앱으로 쓰므로 iCloud·구글 등은 그 계정의 동기화가 서버로 올린다. 앱이 직접 보내는 네트워크는 없다.
public final class CalendarSyncEventKit: CalendarSyncBackend, @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private var storeValue: EKEventStore?
    private var storeStatus: EKAuthorizationStatus = .notDetermined

    public init() {}

    /// 권한 상태가 바뀌면 새로 만든다(바뀐 뒤에는 새 저장소라야 캘린더가 보인다).
    public var eventStore: EKEventStore {
        lock.withLock {
            let status = EKEventStore.authorizationStatus(for: .event)
            if let s = storeValue, status == storeStatus { return s }
            let s = EKEventStore()
            storeValue = s
            storeStatus = status
            return s
        }
    }

    /// 전체 접근을 요청한다(iOS 17 `requestFullAccessToEvents`). 허용되면 true.
    public func requestAccess() async -> Bool {
        guard !StudyDataProfile.isDemo else { return false }
        let granted = (try? await eventStore.requestFullAccessToEvents()) ?? false
        lock.withLock { storeValue = nil }
        return granted
    }

    public var permitted: Bool { !StudyDataProfile.isDemo && EKEventStore.authorizationStatus(for: .event) == .fullAccess }

    private func requirePermission() throws {
        if !permitted { throw CalendarPermissionError() }
    }

    // MARK: 캘린더

    public func calendarExists(_ id: String) -> Bool {
        guard permitted else { return false }
        return lock.withLock { eventStore.calendar(withIdentifier: id) != nil }
    }

    private func isICloud(_ s: EKSource) -> Bool { s.sourceType == .calDAV && s.title.lowercased().contains("icloud") }

    /// 앱 캘린더를 만들 소스: 로컬(일정 캘린더가 있거나 iCloud가 없을 때) → iCloud → 기본 캘린더의 계정.
    private func creationSource() -> (EKSource, CalendarLocalPlacement)? {
        let store = eventStore
        let sources = store.sources
        let iCloud = sources.first(where: isICloud)
        if let local = sources.first(where: { $0.sourceType == .local }), iCloud == nil || !local.calendars(for: .event).isEmpty {
            return (local, .device)
        }
        if let iCloud { return (iCloud, .iCloud) }
        if let s = store.defaultCalendarForNewEvents?.source { return (s, .other(s.title)) }
        return nil
    }

    public func localPlacement(_ calendarId: String?) -> CalendarLocalPlacement {
        guard permitted else { return .unavailable }
        return lock.withLock {
            if let calendarId, let c = eventStore.calendar(withIdentifier: calendarId), let s = c.source {
                if s.sourceType == .local { return .device }
                if isICloud(s) { return .iCloud }
                return .other(s.title)
            }
            return creationSource()?.1 ?? .unavailable
        }
    }

    public func findLocalCalendar(_ known: String?) -> String? {
        guard permitted else { return nil }
        return lock.withLock {
            let store = eventStore
            if let known, store.calendar(withIdentifier: known) != nil { return known }
            // 다시 설치해 식별자를 잃었으면: 같은 이름(로컬·iCloud)이고 영민이가 넣은 일정만 있는 캘린더.
            let now = Date()
            for c in store.calendars(for: .event) where c.title == CalendarExport.DISPLAY_NAME && c.allowsContentModifications {
                guard let s = c.source, s.sourceType == .local || isICloud(s) else { continue }
                if events(in: c, around: now).allSatisfy(isOurs) { return c.calendarIdentifier }
            }
            return nil
        }
    }

    public func createLocalCalendar() throws -> String? {
        try requirePermission()
        return try lock.withLock {
            guard let (source, _) = creationSource() else { return nil }
            let c = EKCalendar(for: .event, eventStore: eventStore)
            c.title = CalendarExport.DISPLAY_NAME
            c.source = source
            c.cgColor = CGColor(srgbRed: 0x2F / 255.0, green: 0x6B / 255.0, blue: 1, alpha: 1)
            try eventStore.saveCalendar(c, commit: true)
            return c.calendarIdentifier
        }
    }

    public func accountCalendars(excluding: String?) -> [CalendarExportInfo] {
        guard permitted else { return [] }
        let list: [CalendarExportInfo] = lock.withLock {
            eventStore.calendars(for: .event)
                .filter { $0.allowsContentModifications && $0.calendarIdentifier != excluding && $0.type != .birthday && $0.type != .subscription }
                .map { CalendarExportInfo($0.calendarIdentifier, $0.title.isEmpty ? "(이름 없음)" : $0.title, $0.source?.title ?? "") }
        }
        return list.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            let xa = !x.name.contains(CalendarExport.DISPLAY_NAME), ya = !y.name.contains(CalendarExport.DISPLAY_NAME)
            if xa != ya { return !xa }
            if x.account != y.account { return x.account < y.account }
            if x.name != y.name { return x.name < y.name }
            return a.offset < b.offset
        }.map(\.element)
    }

    public func deleteCalendar(_ id: String) throws {
        try requirePermission()
        try lock.withLock {
            guard let c = eventStore.calendar(withIdentifier: id) else { return }
            try eventStore.removeCalendar(c, commit: true)
        }
    }

    // MARK: 일정

    /// 영민이가 넣은 일정인가(주소 또는 설명 끝 문구).
    private func isOurs(_ e: EKEvent) -> Bool {
        (e.url?.absoluteString.hasPrefix(CalendarSyncLogic.APP_URI_PREFIX) ?? false) || (e.notes ?? "").contains(CalendarSyncLogic.FOOTER)
    }

    private func key(of e: EKEvent) -> String? { CalendarSyncLogic.keyFromAppUri(e.url?.absoluteString) }

    /// [from, to)의 일정(EventKit 조회는 4년까지라 1년씩 나눈다).
    private func events(in c: EKCalendar, from: Date, to: Date) -> [EKEvent] {
        var out: [EKEvent] = []
        var t = from
        while t < to {
            let end = min(t.addingTimeInterval(365 * 86_400), to)
            out += eventStore.events(matching: eventStore.predicateForEvents(withStart: t, end: end, calendars: [c]))
            t = end
        }
        return out
    }

    private func events(in c: EKCalendar, around now: Date) -> [EKEvent] {
        events(in: c, from: now.addingTimeInterval(-2 * 365 * 86_400), to: now.addingTimeInterval(2 * 365 * 86_400))
    }

    public func wipeAppEvents(_ calendarId: String, now: Date) throws {
        try requirePermission()
        try lock.withLock {
            guard let c = eventStore.calendar(withIdentifier: calendarId) else { return }
            var seen = Set<String>()
            for e in events(in: c, around: now) where isOurs(e) {
                guard let id = e.eventIdentifier, seen.insert(id).inserted else { continue }
                try eventStore.remove(e, span: .thisEvent, commit: false)
            }
            try eventStore.commit()
        }
    }

    private func row(_ e: EKEvent, _ id: String) -> CalRow {
        let start = e.startDate ?? Date(timeIntervalSince1970: 0)
        return CalRow(id, e.title ?? "", start.epochMilli, max(e.endDate ?? start, start).epochMilli, e.location ?? "")
    }

    public func liveRows(_ calendarId: String, _ entries: [String: CalendarSyncLogic.Entry]) throws -> [String: CalRow] {
        try requirePermission()
        return lock.withLock {
            let store = eventStore
            // 오래 쓰는 저장소는 한 번 읽은 일정을 붙들고 있어서, 앱이 멈춰 있는 동안 캘린더 앱에서 고친 것이 옛 모양으로 읽힌다.
            // 맞추기마다 처음 읽기 전에 버려서 지금 데이터베이스 모양을 읽는다(붙들고 있는 EKObject는 없다).
            store.reset()
            guard let c = store.calendar(withIdentifier: calendarId) else { return [:] }
            var out: [String: CalRow] = [:]
            var missing: [String: CalendarSyncLogic.Entry] = [:]
            for (key, e) in entries where !e.tombstone {
                if let ev = store.event(withIdentifier: e.eventId), ev.calendar?.calendarIdentifier == calendarId {
                    out[key] = row(ev, e.eventId)
                } else {
                    missing[key] = e
                }
            }
            // 식별자로 못 찾은 일정은 주소의 키로 다시 찾는다(계정 동기화가 식별자를 바꾸는 경우).
            if let lo = missing.values.map(\.dueMillis).min(), let hi = missing.values.map(\.dueMillis).max() {
                let from = Date(epochMilli: lo).addingTimeInterval(-2 * 86_400)
                let to = Date(epochMilli: hi).addingTimeInterval(2 * 86_400)
                for ev in events(in: c, from: from, to: to) {
                    guard let k = key(of: ev), missing[k] != nil, out[k] == nil, let id = ev.eventIdentifier else { continue }
                    out[k] = row(ev, id)
                }
            }
            return out
        }
    }

    public func readRow(_ eventId: String) -> CalRow? {
        guard permitted else { return nil }
        return lock.withLock { eventStore.event(withIdentifier: eventId).map { row($0, eventId) } }
    }

    private func apply(_ item: CalItem, to e: EKEvent) {
        e.title = item.title
        e.notes = item.description
        e.isAllDay = false
        e.timeZone = EtlLogic.kst
        e.startDate = item.start
        e.endDate = item.end
        e.location = item.location.isEmpty ? nil : item.location
        e.alarms = nil
        if let c = e.calendar {
            let want: EKCalendarEventAvailabilityMask = item.busy ? .busy : .free
            if c.supportedEventAvailabilities.contains(want) { e.availability = item.busy ? .busy : .free }
        }
        e.url = URL(string: CalendarSyncLogic.appUri(item.key))
    }

    public func insert(_ calendarId: String, _ item: CalItem) throws -> String? {
        try requirePermission()
        return try lock.withLock {
            guard let c = eventStore.calendar(withIdentifier: calendarId) else { throw CalendarSyncError("Calendar not found") }
            let e = EKEvent(eventStore: eventStore)
            e.calendar = c
            apply(item, to: e)
            try eventStore.save(e, span: .thisEvent, commit: true)
            return e.eventIdentifier
        }
    }

    public func update(_ eventId: String, _ item: CalItem) throws -> Bool {
        try requirePermission()
        return try lock.withLock {
            guard let e = eventStore.event(withIdentifier: eventId) else { return false }
            apply(item, to: e)
            try eventStore.save(e, span: .thisEvent, commit: true)
            return true
        }
    }

    public func delete(_ eventId: String) throws {
        try requirePermission()
        try lock.withLock {
            guard let e = eventStore.event(withIdentifier: eventId) else { return }
            try eventStore.remove(e, span: .thisEvent, commit: true)
        }
    }

    public func deleteEvents(_ ids: [String]) throws {
        try requirePermission()
        try lock.withLock {
            for id in Set(ids) {
                guard let e = eventStore.event(withIdentifier: id) else { continue }
                try eventStore.remove(e, span: .thisEvent, commit: false)
            }
            try eventStore.commit()
        }
    }

    public func existing(_ calendarId: String, _ ids: [String]) throws -> Set<String> {
        try requirePermission()
        return lock.withLock {
            Set(ids.filter { eventStore.event(withIdentifier: $0)?.calendar?.calendarIdentifier == calendarId })
        }
    }

    // MARK: 캘린더 앱의 "일정 추가" 화면(EKEventEditViewController)

    /// [draft]를 채운 새 일정(저장하지 않는다). iOS 17부터 권한 없이 일정 추가 화면을 띄울 수 있다(사용자가 저장한다).
    public static func makeEvent(_ draft: CalendarExport.InsertDraft, store: EKEventStore) -> EKEvent {
        let e = EKEvent(eventStore: store)
        e.title = draft.title
        e.notes = draft.description
        e.isAllDay = false
        e.timeZone = EtlLogic.kst
        e.startDate = draft.start
        e.endDate = draft.end
        e.availability = draft.busy ? .busy : .free
        if !draft.location.isEmpty { e.location = draft.location }
        if let r = draft.recurrence { e.recurrenceRules = [rule(r)] }
        return e
    }

    /// [CalendarSyncLogic.Recurrence] → `EKRecurrenceRule`(RRULE과 같은 뜻. EventKit은 WKST를 정할 수 없어 기본 주 시작을 쓴다).
    public static func rule(_ r: CalendarSyncLogic.Recurrence) -> EKRecurrenceRule {
        let freq: EKRecurrenceFrequency
        switch r.freq {
        case .DAILY: freq = .daily
        case .WEEKLY, .BIWEEKLY: freq = .weekly
        case .MONTHLY: freq = .monthly
        }
        // EKWeekday: 일=1 … 토=7. ISO(월=1 … 일=7) → iso % 7 + 1.
        let days = r.weekdays.isEmpty ? nil : r.weekdays.compactMap { EKWeekday(rawValue: $0.rawValue % 7 + 1).map(EKRecurrenceDayOfWeek.init) }
        let monthDays = r.monthDay.map { [NSNumber(value: $0)] }
        let end: EKRecurrenceEnd?
        switch r.end {
        case .none: end = nil
        case .count(let n): end = EKRecurrenceEnd(occurrenceCount: n)
        case .until(let t): end = EKRecurrenceEnd(end: t)
        }
        return EKRecurrenceRule(recurrenceWith: freq, interval: r.interval, daysOfTheWeek: days, daysOfTheMonth: monthDays,
                                monthsOfTheYear: nil, weeksOfTheYear: nil, daysOfTheYear: nil, setPositions: nil, end: end)
    }
}
