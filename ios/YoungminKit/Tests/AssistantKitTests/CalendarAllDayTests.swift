import Foundation
import Testing
import ETLKit
import NotifyKit
@testable import AssistantKit

// 종일 일정의 끝 날짜 변환(CalendarAllDay)과, EventKit 모양으로 저장하는 가짜 저장소로 돌리는 도구 왕복·되돌리기 테스트.
// 예전에는 쓰기에서 끝을 "마지막 날 0시"로 넣고 읽기에서 "끝 1초 전"의 날짜를 마지막 날로 봐서 11/26~28이 11/26~27로 줄었다.

private func zone(_ id: String) -> TimeZone { TimeZone(identifier: id)! }
private func cal(_ id: String) -> Calendar { CalendarAllDay.calendar(zone(id)) }
private func day(_ s: String) -> DayDate { DayDate.parse(s)! }
private func utcDay(_ s: String) -> Int64 { day(s).epochDay * 86_400_000 }
/// 튜플은 #expect에서 비교하기 번거로워 배열로 바꾼다.
private func pair<T>(_ t: (T, T)) -> [T] { [t.0, t.1] }

/// [id] 시간대의 벽시계 시각("yyyy-MM-ddTHH:mm:ss", 소수 초 [fraction]).
private func wall(_ id: String, _ s: String, fraction: TimeInterval = 0) -> Date {
    let p = s.split(whereSeparator: { "-T:".contains($0) }).map { Int($0)! }
    let d = cal(id).date(from: DateComponents(year: p[0], month: p[1], day: p[2], hour: p[3], minute: p[4], second: p[5]))!
    return d.addingTimeInterval(fraction)
}

private let SEOUL = "Asia/Seoul"
/// 서머타임이 있는 곳, 날짜 변경선 양쪽, 0시를 건너뛰던 곳(상파울루는 2019년까지 0시에 서머타임 시작)을 함께 본다.
private let ZONES = [SEOUL, "UTC", "America/New_York", "America/Los_Angeles", "Pacific/Auckland", "America/Sao_Paulo",
                     "Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Kolkata"]

/// EventKit이 저장한 뒤 돌려줄 수 있는 끝 모양. 쓴 끝을 마지막 날 안의 시각(포함)으로 받아들이고, 그날을
/// 23:59:59로 돌려주거나(일반적인 모양) 다음 날 0시(배타, CalDAV DTEND 모양)로 돌려준다.
private enum EndShape: CaseIterable { case asWritten, lastDayEndOfDay, nextDayMidnight }

private func normalized(_ t: EventKitTimes, _ shape: EndShape, _ c: Calendar) -> EventKitTimes {
    guard t.isAllDay, shape != .asWritten else { return t }
    var out = t
    let lastStart = c.startOfDay(for: t.end)
    let nextStart = c.startOfDay(for: c.date(byAdding: .hour, value: 36, to: lastStart)!)
    out.start = c.startOfDay(for: t.start)
    out.end = shape == .nextDayMidnight ? nextStart : nextStart.addingTimeInterval(-1)
    return out
}

private func allDay(_ first: String, _ last: String, id: Int64 = 1, calendarId: Int64 = 1, title: String = "MT") throws -> CalendarToolLogic.Backup {
    let w = try CalendarToolLogic.parseWhen(first, last, true)
    return CalendarToolLogic.Backup(id, calendarId, title, w.dtStart, w.dtEnd, true, w.timezone, "", "")
}

// MARK: - 순수 변환

@Suite struct CalendarAllDayTests {
    @Test func singleDayWritesWholeDayAndReadsBack() throws {
        let c = cal(SEOUL)
        let b = try allDay("2026-11-26", "2026-11-26")
        #expect(b.dtStart == utcDay("2026-11-26") && b.dtEnd == utcDay("2026-11-27"))
        let t = CalendarAllDay.eventKitTimes(b, c)
        #expect(t.isAllDay && t.timeZone == nil)
        #expect(t.start == wall(SEOUL, "2026-11-26T00:00:00"))
        #expect(t.end == wall(SEOUL, "2026-11-26T23:59:59"))
        let (s, e) = CalendarAllDay.backupTimes(t, c)
        #expect(s == b.dtStart && e == b.dtEnd)
        let ev = CalendarReadLogic.toEvent(RawInstance(1, 1, "개인", 0, "MT", nil, nil, s, e, true))
        #expect(ev.startDate == day("2026-11-26") && ev.endDate == day("2026-11-26"))
        #expect(CalendarReadLogic.timeLabel(ev) == "종일")
    }

    @Test func multiDayKeepsAllThreeDays() throws {
        let c = cal(SEOUL)
        let b = try allDay("2026-11-26", "2026-11-28")
        #expect(b.dtEnd == utcDay("2026-11-29"))
        let t = CalendarAllDay.eventKitTimes(b, c)
        #expect(t.start == wall(SEOUL, "2026-11-26T00:00:00"))
        // 마지막 날 0시(예전 값)가 아니라 마지막 날 23:59:59.
        #expect(t.end == wall(SEOUL, "2026-11-28T23:59:59"))
        #expect(t.end != wall(SEOUL, "2026-11-28T00:00:00"))
        let (s, e) = CalendarAllDay.backupTimes(t, c)
        #expect(s == utcDay("2026-11-26") && e == utcDay("2026-11-29"))
        let ev = CalendarReadLogic.toEvent(RawInstance(1, 1, "개인", 0, "MT", nil, nil, s, e, true))
        #expect(ev.endDate == day("2026-11-28"))
        #expect(CalendarReadLogic.eventJson(ev, writable: true).optString("end_date") == "2026-11-28")
        #expect(CalendarReadLogic.timeLabel(ev) == "11/26~11/28 종일")
        #expect(CalendarToolLogic.whenLabel(true, s, e) == "11/26(목)~11/28(토) 종일")
    }

    @Test func monthYearAndLeapBoundariesInEveryZone() {
        let spans = [("2026-11-30", "2026-12-01"), ("2026-12-31", "2027-01-01"), ("2026-12-30", "2027-01-02"),
                     ("2028-02-28", "2028-03-01"), ("2027-02-28", "2027-03-01"), ("2026-01-31", "2026-02-01"),
                     // 서머타임 바뀌는 날(뉴욕 3/8·11/1, 오클랜드 4/5·9/27)을 끼운 범위.
                     ("2026-03-07", "2026-03-08"), ("2026-10-31", "2026-11-01"), ("2026-11-01", "2026-11-01"),
                     ("2026-04-04", "2026-04-06"), ("2026-09-27", "2026-09-27")]
        for z in ZONES {
            let c = cal(z)
            for (f, l) in spans {
                let (s, e) = CalendarAllDay.eventKitDates(first: day(f), last: day(l), c)
                #expect(CalendarAllDay.day(s, c) == day(f), "\(z) \(f)")
                #expect(CalendarAllDay.day(e, c) == day(l), "\(z) \(l)")
                #expect(CalendarAllDay.day(e.addingTimeInterval(1), c) == day(l).plusDays(1), "\(z) \(l) 다음 날 0시")
                let got = CalendarAllDay.inclusiveDays(start: s, end: e, c)
                #expect(got.first == day(f) && got.last == day(l), "\(z) \(f)~\(l)")
            }
        }
    }

    @Test func readsBothEventKitEndRepresentations() {
        let c = cal(SEOUL)
        let start = wall(SEOUL, "2026-11-26T00:00:00")
        func last(_ end: Date?) -> DayDate { let r = CalendarAllDay.inclusiveDays(start: start, end: end, c); #expect(r.first == day("2026-11-26")); return r.last }
        // 다음 날 0시(배타)
        #expect(last(wall(SEOUL, "2026-11-29T00:00:00")) == day("2026-11-28"))
        #expect(last(wall(SEOUL, "2026-11-29T00:00:00", fraction: 0.3)) == day("2026-11-28"))
        #expect(last(wall(SEOUL, "2026-11-28T23:59:59", fraction: 0.9999)) == day("2026-11-28"))
        // 마지막 날 23:59:59(EventKit이 종일 일정을 돌려주는 모양)
        #expect(last(wall(SEOUL, "2026-11-28T23:59:59")) == day("2026-11-28"))
        // 하루짜리: 끝 = 시작(마지막 날 0시), 끝 없음, 끝이 앞, 23:59:59, 다음 날 0시
        #expect(last(start) == day("2026-11-26"))
        #expect(last(nil) == day("2026-11-26"))
        #expect(last(start.addingTimeInterval(-3600)) == day("2026-11-26"))
        #expect(last(wall(SEOUL, "2026-11-26T23:59:59")) == day("2026-11-26"))
        #expect(last(wall(SEOUL, "2026-11-27T00:00:00")) == day("2026-11-26"))

        // 서머타임이 끝나는 날(뉴욕 2026-11-01, 25시간)을 끼워도 같다.
        let ny = cal("America/New_York")
        let nyStart = wall("America/New_York", "2026-10-31T00:00:00")
        for end in [wall("America/New_York", "2026-11-02T00:00:00"), wall("America/New_York", "2026-11-01T23:59:59")] {
            let r = CalendarAllDay.inclusiveDays(start: nyStart, end: end, ny)
            #expect(r.first == day("2026-10-31") && r.last == day("2026-11-01"))
        }
    }

    @Test func roundTripIsStableForEveryEndShapeAndZone() throws {
        let spans = [("2026-11-26", "2026-11-26"), ("2026-11-26", "2026-11-28"), ("2026-11-30", "2026-12-01"),
                     ("2026-12-31", "2027-01-01"), ("2028-02-28", "2028-03-01"), ("2026-10-31", "2026-11-02"),
                     ("2026-03-07", "2026-03-09"), ("2026-09-26", "2026-09-28"), ("2026-11-01", "2026-11-30")]
        for z in ZONES {
            let c = cal(z)
            for shape in EndShape.allCases {
                for (f, l) in spans {
                    let original = try allDay(f, l)
                    var b = original
                    // 쓰기 → (EventKit 저장) → 읽기 → 다시 쓰기를 여러 번 해도 날짜가 줄거나 늘지 않는다.
                    for round in 0..<4 {
                        let t = normalized(CalendarAllDay.eventKitTimes(b, c), shape, c)
                        let (s, e) = CalendarAllDay.backupTimes(t, c)
                        #expect(s == original.dtStart && e == original.dtEnd, "\(z) \(shape) \(f)~\(l) round \(round)")
                        b.dtStart = s
                        b.dtEnd = e
                    }
                }
            }
        }
    }

    @Test func utcConventionHelpers() {
        #expect(pair(CalendarAllDay.inclusiveDays(dtStart: utcDay("2026-11-26"), dtEnd: utcDay("2026-11-29"))) == [day("2026-11-26"), day("2026-11-28")])
        // 끝이 시작 이하인 이상한 값은 하루짜리.
        #expect(pair(CalendarAllDay.inclusiveDays(dtStart: utcDay("2026-11-26"), dtEnd: utcDay("2026-11-26"))) == [day("2026-11-26"), day("2026-11-26")])
        #expect(pair(CalendarAllDay.utcRange(first: day("2026-12-31"), last: day("2027-01-01"))) == [utcDay("2026-12-31"), utcDay("2027-01-02")])
        #expect(pair(CalendarAllDay.utcRange(first: day("2026-11-26"), last: day("2026-11-20"))) == [utcDay("2026-11-26"), utcDay("2026-11-27")])
    }

    @Test func timedEventsAreUnchanged() {
        let c = cal(SEOUL)
        let s = wall(SEOUL, "2026-11-28T14:00:00")
        let e = wall(SEOUL, "2026-11-29T00:00:00")
        let b = CalendarToolLogic.Backup(1, 1, "면담", s.epochMilli, e.epochMilli, false, "Asia/Seoul", "", "")
        let t = CalendarAllDay.eventKitTimes(b, c)
        #expect(!t.isAllDay && t.timeZone?.identifier == "Asia/Seoul" && t.start == s && t.end == e)
        #expect(pair(CalendarAllDay.backupTimes(t, c)) == [s.epochMilli, e.epochMilli])
        #expect(pair(CalendarAllDay.backupTimes(start: s, end: nil, isAllDay: false, c)) == [s.epochMilli, s.epochMilli])
    }
}

// MARK: - EventKit 모양 가짜 저장소로 도구 왕복

/// `CalendarEventKitStore`와 같은 변환(`CalendarAllDay`)으로 EKEvent 시각 칸을 저장한다. [shape]는 EventKit이 저장 뒤 돌려주는 끝 모양.
private final class EventKitShapedStore: CalendarStore, @unchecked Sendable {
    struct Ev {
        var calendarId: Int64
        var title: String
        var location: String
        var notes: String
        var t: EventKitTimes
    }

    private let lock = NSLock()
    let shape: EndShape
    let cal: Calendar
    private var events: [Int64: Ev] = [:]
    private var next: Int64 = 100
    let cals = [CalendarInfo(id: 1, name: "개인", account: "me@icloud.com", writable: true)]

    init(shape: EndShape, zone: String = SEOUL) {
        self.shape = shape
        cal = CalendarAllDay.calendar(TimeZone(identifier: zone)!)
    }

    var access: CalendarAccess { .fullAccess }
    func requestAccess() async -> Bool { true }
    func calendars() -> [CalendarInfo] { cals }
    func ownEvents() -> (exported: Set<Int64>, own: Set<Int64>) { ([], []) }

    private func backup(_ id: Int64, _ ev: Ev) -> CalendarToolLogic.Backup {
        let (s, e) = CalendarAllDay.backupTimes(ev.t, cal)
        return CalendarToolLogic.Backup(id, ev.calendarId, ev.title, s, e, ev.t.isAllDay,
                                        ev.t.isAllDay ? "UTC" : (ev.t.timeZone?.identifier ?? CalendarLogic.TZ_ID), ev.location, ev.notes)
    }

    private func stored(_ b: CalendarToolLogic.Backup, calendarId: Int64) -> Ev {
        Ev(calendarId: calendarId, title: b.title, location: b.location, notes: b.description,
           t: normalized(CalendarAllDay.eventKitTimes(b, cal), shape, cal))
    }

    func seed(_ id: Int64, _ title: String, _ t: EventKitTimes) {
        lock.withLock { events[id] = Ev(calendarId: 1, title: title, location: "", notes: "", t: t) }
    }

    func times(_ id: Int64) -> EventKitTimes? { lock.withLock { events[id]?.t } }

    func instances(from: Date, to: Date, calendarIds: Set<Int64>) -> [RawInstance] {
        lock.withLock {
            events.sorted { $0.key < $1.key }.compactMap { id, ev in
                guard calendarIds.contains(ev.calendarId), ev.t.end >= from, ev.t.start < to else { return nil }
                let b = backup(id, ev)
                return RawInstance(id, ev.calendarId, "개인", 0, b.title, b.location, b.description, b.dtStart, b.dtEnd, b.allDay)
            }
        }
    }

    func event(_ eventId: Int64) -> CalendarEventRow? {
        lock.withLock { events[eventId].map { CalendarEventRow(backup: backup(eventId, $0)) } }
    }

    func insert(_ b: CalendarToolLogic.Backup) throws -> Int64 {
        lock.withLock {
            next += 1
            events[next] = stored(b, calendarId: b.calendarId)
            return next
        }
    }

    func update(_ eventId: Int64, _ b: CalendarToolLogic.Backup) throws -> Bool {
        lock.withLock {
            guard let old = events[eventId] else { return false }
            events[eventId] = stored(b, calendarId: old.calendarId)
            return true
        }
    }

    func delete(_ eventId: Int64) throws -> Bool { lock.withLock { events.removeValue(forKey: eventId) != nil } }
}

private struct ShapedFixture {
    let gate = WriteApprovalGate()
    let store: EventKitShapedStore
    let settings = CalendarReadSettingsStore(InMemoryStore())
    let undo = CalendarUndo(InMemoryStore())
    let box: Toolbox

    init(_ shape: EndShape) {
        store = EventKitShapedStore(shape: shape)
        box = makeToolbox(gate: gate)
        box.useCalendar(CalendarToolContext(store: store, settings: settings, undo: undo))
        settings.save(CalendarReadSettings(assistantEnabled: true, calendarIds: [1], writeCalendarId: 1, writeCalendarName: "개인"))
    }

    func run(_ name: String, _ args: String) async -> JSONObject { parsed(await box.run(name: name, argumentsJSON: args).text) }

    func runApproved(_ name: String, _ args: String) async throws -> (JSONObject, WriteApprovalGate.Request) {
        let host = gate.attachHost()
        let requests = RequestObserver(gate)
        let box = self.box
        let call = Task { await box.run(name: name, argumentsJSON: args).text }
        let req = try #require(await requests.next())
        #expect(gate.decide(hostId: host, requestId: req.id, approved: true))
        return (parsed(await call.value), req)
    }

    /// 지금 일정의 포함 날짜(도구가 보여 주는 값), [첫날, 마지막 날].
    func span(_ id: Int64) -> [DayDate] {
        store.event(id).map { pair(CalendarAllDay.inclusiveDays(dtStart: $0.backup.dtStart, dtEnd: $0.backup.dtEnd)) } ?? []
    }
}

@Suite struct CalendarAllDayToolTests {
    private static let span = "2026-11-26 ~ 2026-11-28 (종일, 끝 날짜 포함)"

    /// 캘린더 앱·동기화가 넣은 11/26~11/28 종일 일정(끝은 두 모양 중 하나).
    private func seeded(_ shape: EndShape, exclusiveEnd: Bool) -> ShapedFixture {
        let f = ShapedFixture(shape)
        let end = exclusiveEnd ? wall(SEOUL, "2026-11-29T00:00:00") : wall(SEOUL, "2026-11-28T23:59:59")
        f.store.seed(1, "MT", EventKitTimes(start: wall(SEOUL, "2026-11-26T00:00:00"), end: end, isAllDay: true, timeZone: nil))
        return f
    }

    @Test func eventsShowsInclusiveEndForBothRepresentations() async {
        for exclusive in [true, false] {
            let f = seeded(.asWritten, exclusiveEnd: exclusive)
            let r = await f.run("calendar_events", #"{"start":"2026-11-26","end":"2026-11-30"}"#)
            let e = r.optJSONArray("events")!.objects()
            #expect(e.count == 1)
            #expect(e.first?.optString("start_date") == "2026-11-26" && e.first?.optString("end_date") == "2026-11-28", "exclusive=\(exclusive)")
            // 마지막 날만 보는 조회에도 걸린다.
            #expect(await f.run("calendar_events", #"{"start":"2026-11-28"}"#).optInt("count") == 1)
            #expect(await f.run("calendar_events", #"{"start":"2026-11-29"}"#).optInt("count") == 0)
        }
    }

    @Test func updateOtherFieldsKeepsDatesAndUndoRestoresExactSpan() async throws {
        for shape in EndShape.allCases {
            for exclusive in [true, false] {
                let f = seeded(shape, exclusiveEnd: exclusive)
                let original = try #require(f.store.event(1)?.backup)
                #expect(f.span(1) == [day("2026-11-26"), day("2026-11-28")])

                // 제목만, 이어서 장소만 고쳐도 날짜는 그대로(여러 번 저장해도 줄지 않는다).
                let (out, req) = try await f.runApproved("calendar_update", #"{"event_id":1,"title":"MT(수정)"}"#)
                #expect(out.optBoolean("ok"), "\(shape) \(exclusive) \(out)")
                #expect(req.details.components(separatedBy: Self.span).count == 3, "변경 전·후 모두 같은 날짜")
                #expect(out.optString("after") == "MT(수정) · 11/26(목)~11/28(토) 종일")
                #expect(f.span(1) == [day("2026-11-26"), day("2026-11-28")], "\(shape) \(exclusive)")
                _ = try await f.runApproved("calendar_update", #"{"event_id":1,"location":"가평"}"#)
                _ = try await f.runApproved("calendar_update", #"{"event_id":1,"memo":"회비"}"#)
                let edited = try #require(f.store.event(1)?.backup)
                #expect(edited.dtStart == original.dtStart && edited.dtEnd == original.dtEnd && edited.title == "MT(수정)" && edited.location == "가평")
                // EventKit에는 마지막 날 0시가 아니라 마지막 날 안(23:59:59) 또는 다음 날 0시로 들어간다.
                let t = try #require(f.store.times(1))
                #expect(t.end != wall(SEOUL, "2026-11-28T00:00:00"))

                // 시작만 옮기면 길이 유지(달 경계), 되돌리면 원래 사흘 그대로.
                let (moved, _) = try await f.runApproved("calendar_update", #"{"event_id":1,"start":"2026-11-30"}"#)
                #expect(moved.optString("after").contains("11/30(월)~12/2(수) 종일"), "\(moved)")
                #expect(f.span(1) == [day("2026-11-30"), day("2026-12-02")])

                let log = f.undo.list()
                #expect(log.count == 4)
                for c in log { #expect(f.undo.undo(c.id, store: f.store) == "되돌렸어요") }
                let restored = try #require(f.store.event(1)?.backup)
                #expect(restored == original, "\(shape) \(exclusive)")
                #expect(f.span(1) == [day("2026-11-26"), day("2026-11-28")])
            }
        }
    }

    @Test func addThenReadAndDeleteUndoKeepThreeDays() async throws {
        for shape in EndShape.allCases {
            let f = ShapedFixture(shape)
            let (out, req) = try await f.runApproved("calendar_add", #"{"title":"학회","start":"2026-11-26","end":"2026-11-28"}"#)
            #expect(req.details.contains(Self.span))
            let id = out.optLong("event_id")
            let t = try #require(f.store.times(id))
            #expect(CalendarAllDay.day(t.start, f.store.cal) == day("2026-11-26"))
            #expect(CalendarAllDay.day(t.end.addingTimeInterval(-0.5), f.store.cal) == day("2026-11-28"))
            let r = await f.run("calendar_events", #"{"start":"2026-11-26","end":"2026-11-30"}"#)
            #expect(r.optJSONArray("events")!.objects().first?.optString("end_date") == "2026-11-28", "\(shape)")

            let before = try #require(f.store.event(id)?.backup)
            let (deleted, _) = try await f.runApproved("calendar_delete", #"{"event_id":\#(id)}"#)
            #expect(deleted.optString("deleted") == "학회 · 11/26(목)~11/28(토) 종일")
            #expect(f.undo.undo(f.undo.list()[0].id, store: f.store) == "되돌렸어요")
            let newId = try #require(f.undo.list().first?.eventId)
            let again = try #require(f.store.event(newId)?.backup)
            #expect(again.dtStart == before.dtStart && again.dtEnd == before.dtEnd && again.allDay, "\(shape)")
        }
    }
}
