import Foundation
import ETLKit
import NotifyKit

/// 캘린더 연동에서 비서 도구가 쓰는 상수·정리 함수(Kotlin `CalendarLogic`의 일부).
/// iOS에는 "영민이 → 캘린더" 내보내기가 아직 없어 그 부분은 옮기지 않았다.
public enum CalendarLogic {
    public static let TZ_ID = "Asia/Seoul"
    public static var ZONE: TimeZone { EtlLogic.kst }
    /// 영민이가 넣은 일정의 주소 접두어(안드로이드 `CUSTOM_APP_URI`, iOS는 `EKEvent.url`).
    public static let APP_URI_PREFIX = "youngmin://item/"

    /// 줄마다 [NoticeText.clean]하고 앞뒤를 다듬는다(Kotlin `cleanMultiline`).
    public static func cleanMultiline(_ s: String) -> String {
        // Kotlin `lines()`: \r\n, \n, \r 로 나눈다.
        let normalized = s.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        return normalized.components(separatedBy: "\n").map { NoticeText.clean($0) }.joined(separator: "\n").kt
    }

    /// 종일 일정의 날짜 ↔ UTC 자정 epoch millis(캘린더 규칙).
    static func utcMidnight(_ d: DayDate) -> Int64 { d.epochDay * 86_400_000 }
    static func utcDate(_ ms: Int64) -> DayDate {
        let day = ms >= 0 ? ms / 86_400_000 : -((-ms + 86_400_000 - 1) / 86_400_000)
        return DayDate(epochDay: day)
    }
}

/// 비서 캘린더 도구(calendar_events / calendar_add / calendar_update / calendar_delete)의 순수 로직(Kotlin `CalendarToolLogic`):
/// 인자 읽기·검사, 되돌리기용 백업, 채팅 알림 문구. 기기 쪽은 `CalendarStore`(EventKit)와 `Toolbox.callCalendar`.
public enum CalendarToolLogic {
    public static let TITLE_MAX = 100
    public static let LOCATION_MAX = 100
    public static let MEMO_MAX = 1000
    public static let MAX_RANGE_DAYS: Int64 = 62
    public static let MAX_EVENT_DAYS: Int64 = 31

    public struct ArgException: Error, LocalizedError, Sendable {
        public let message: String
        public init(_ message: String) { self.message = message }
        public var errorDescription: String? { message }
    }

    public struct Window: Hashable, Sendable {
        public let start: Date
        public let end: Date
        public init(start: Date, end: Date) {
            self.start = start
            self.end = end
        }
    }

    /// 조회 기간. range: today(기본) | this_week(KST 월~일) | next_week | days(지금부터 days일).
    /// start/end(YYYY-MM-DD, end 포함)를 주면 그 기간. 최대 [MAX_RANGE_DAYS]일.
    public static func window(_ range: String?, _ days: Int?, _ start: String?, _ end: String?, _ now: Date) throws -> Window {
        let today = DayDate(now)
        let startText = start.flatMap { $0.isBlankK ? nil : $0 }
        let endText = end.flatMap { $0.isBlankK ? nil : $0 }
        if startText != nil || endText != nil {
            let s = try startText.map(date) ?? today
            let e = try endText.map(date) ?? s
            if e < s { throw ArgException("end는 start보다 앞일 수 없습니다") }
            if Int64(s.daysUntil(e)) >= MAX_RANGE_DAYS { throw ArgException("한 번에 \(MAX_RANGE_DAYS)일까지 조회할 수 있습니다") }
            return Window(start: s.startOfDay, end: e.plusDays(1).startOfDay)
        }
        let monday = today.previousOrSame(.of(1))
        let id = (range ?? "").kt.lowercased()
        switch id.isEmpty ? "today" : id {
        case "today": return Window(start: today.startOfDay, end: today.plusDays(1).startOfDay)
        case "this_week": return Window(start: monday.startOfDay, end: monday.plusDays(7).startOfDay)
        case "next_week": return Window(start: monday.plusDays(7).startOfDay, end: monday.plusDays(14).startOfDay)
        case "days":
            let n = min(max(Int64(days ?? 7), 1), MAX_RANGE_DAYS)
            return Window(start: now, end: now.plusSeconds(n * 86_400))
        default: throw ArgException("range는 today, this_week, next_week, days 중 하나여야 합니다")
        }
    }

    private static func date(_ s: String) throws -> DayDate {
        guard let d = DayDate.parse(s.kt) else { throw ArgException("날짜는 YYYY-MM-DD 형식이어야 합니다: \(s)") }
        return d
    }

    /// 일정 시각. 종일이면 [dtStart]·[dtEnd]가 UTC 자정(캘린더 규칙, 끝은 다음 날 0시), 아니면 실제 시각(epoch millis).
    public struct When: Hashable, Sendable {
        public let allDay: Bool
        public let dtStart: Int64
        public let dtEnd: Int64
        public init(_ allDay: Bool, _ dtStart: Int64, _ dtEnd: Int64) {
            self.allDay = allDay
            self.dtStart = dtStart
            self.dtEnd = dtEnd
        }
        public var timezone: String { allDay ? "UTC" : CalendarLogic.TZ_ID }
    }

    private static let DATE_TIME = KRegex("^(\\d{4}-\\d{2}-\\d{2})(?:[ T](\\d{1,2}):(\\d{2})(?::\\d{2})?)?$")
    private static let TIME = KRegex("^(\\d{1,2}):(\\d{2})$")

    private static func time(_ h: String, _ m: String) -> (Int, Int)? {
        guard let hh = Int(h), let mm = Int(m), (0...23).contains(hh), (0...59).contains(mm) else { return nil }
        return (hh, mm)
    }

    private static func parseDateTime(_ s: String, _ name: String) throws -> (DayDate, (Int, Int)?) {
        guard let m = DATE_TIME.find(s.kt) else { throw ArgException("\(name) 은(는) \"YYYY-MM-DD HH:MM\" 또는 \"YYYY-MM-DD\"(종일)이어야 합니다") }
        let g = m.groupValues
        let d = try date(g[1])
        if g[2].isEmpty { return (d, nil) }
        guard let t = time(g[2], g[3]) else { throw ArgException("\(name) 의 시각을 읽을 수 없습니다") }
        return (d, t)
    }

    /// start/end/all_day 인자를 시각으로. start에 시각이 없으면 종일(all_day=false면 오류).
    /// 시간 일정의 end는 "YYYY-MM-DD HH:MM" 또는 시작 날짜의 "HH:MM", 없으면 1시간. 종일의 end는 끝 날짜(포함), 없으면 그날.
    public static func parseWhen(_ start: String?, _ end: String?, _ allDay: Bool?) throws -> When {
        guard let start, !start.isBlankK else { throw ArgException("start가 필요합니다(\"YYYY-MM-DD HH:MM\", 종일이면 \"YYYY-MM-DD\")") }
        let (sd, st) = try parseDateTime(start, "start")
        let isAllDay = allDay ?? (st == nil)
        if !isAllDay && st == nil { throw ArgException("시간 일정은 start에 시각(HH:MM)이 필요합니다. 종일이면 all_day=true") }
        if isAllDay {
            let ed = try end.flatMap { $0.isBlankK ? nil : $0 }.map { try parseDateTime($0, "end").0 } ?? sd
            if ed < sd { throw ArgException("끝 날짜는 시작 날짜보다 앞일 수 없습니다") }
            if Int64(sd.daysUntil(ed)) >= MAX_EVENT_DAYS { throw ArgException("일정은 \(MAX_EVENT_DAYS)일까지 넣을 수 있습니다") }
            return When(true, CalendarLogic.utcMidnight(sd), CalendarLogic.utcMidnight(ed.plusDays(1)))
        }
        let s = sd.atTime(st!.0, st!.1)
        let e: Date
        if let end, !end.isBlankK {
            if let m = TIME.find(end.kt) {
                guard let t = time(m.groupValues[1], m.groupValues[2]) else { throw ArgException("end 시각을 읽을 수 없습니다") }
                e = sd.atTime(t.0, t.1)
            } else {
                let (d, t) = try parseDateTime(end, "end")
                guard let t else { throw ArgException("시간 일정의 end에는 시각이 필요합니다") }
                e = d.atTime(t.0, t.1)
            }
        } else {
            e = s.plusSeconds(3600)
        }
        if !(e > s) { throw ArgException("끝 시각은 시작 시각보다 뒤여야 합니다") }
        if Int64(e.timeIntervalSince(s) / 86_400) >= MAX_EVENT_DAYS { throw ArgException("일정은 \(MAX_EVENT_DAYS)일까지 넣을 수 있습니다") }
        return When(false, s.epochMilli, e.epochMilli)
    }

    /// 제목·장소·메모 정리와 검사.
    public static func cleanTitle(_ s: String?) throws -> String {
        let t = NoticeText.clean(s).kt
        if t.isEmpty { throw ArgException("title이 필요합니다") }
        if t.utf16.count > TITLE_MAX { throw ArgException("title은 \(TITLE_MAX)자까지입니다") }
        return t
    }

    public static func cleanLocation(_ s: String?) throws -> String {
        let t = NoticeText.clean(s).kt
        if t.utf16.count > LOCATION_MAX { throw ArgException("location은 \(LOCATION_MAX)자까지입니다") }
        return t
    }

    public static func cleanMemo(_ s: String?) throws -> String {
        let t = CalendarLogic.cleanMultiline(s ?? "")
        if t.utf16.count > MEMO_MAX { throw ArgException("memo는 \(MEMO_MAX)자까지입니다") }
        return t
    }

    /// 고치거나 지우기 전 일정의 모양(되돌리기용). 반복·참석자 일정은 다루지 않으므로 이 값으로 충분하다.
    public struct Backup: Hashable, Sendable {
        public var eventId: Int64
        public var calendarId: Int64
        public var title: String
        public var dtStart: Int64
        public var dtEnd: Int64
        public var allDay: Bool
        public var timezone: String
        public var location: String
        public var description: String

        public init(_ eventId: Int64, _ calendarId: Int64, _ title: String, _ dtStart: Int64, _ dtEnd: Int64, _ allDay: Bool,
                    _ timezone: String, _ location: String, _ description: String) {
            self.eventId = eventId
            self.calendarId = calendarId
            self.title = title
            self.dtStart = dtStart
            self.dtEnd = dtEnd
            self.allDay = allDay
            self.timezone = timezone
            self.location = location
            self.description = description
        }

        public func toJson() -> JSONObject {
            JSONObject().put("eventId", eventId).put("calendarId", calendarId).put("title", title)
                .put("dtStart", dtStart).put("dtEnd", dtEnd).put("allDay", allDay).put("tz", timezone).put("location", location).put("description", description)
        }

        public static func fromJson(_ o: JSONObject?) -> Backup? {
            guard let o else { return nil }
            return Backup(o.optLong("eventId"), o.optLong("calendarId"), o.optString("title"), o.optLong("dtStart"), o.optLong("dtEnd"),
                          o.optBoolean("allDay"), o.optString("tz", CalendarLogic.TZ_ID), o.optString("location"), o.optString("description"))
        }
    }

    public enum Action: String, CaseIterable, Sendable {
        case ADD = "add", UPDATE = "update", DELETE = "delete"

        public var id: String { rawValue }
        public var label: String {
            switch self {
            case .ADD: "넣음"
            case .UPDATE: "고침"
            case .DELETE: "지움"
            }
        }

        public static func of(_ s: String?) -> Action? { allCases.first { $0.id == s } }
    }

    /// 비서가 바꾼 일정 하나(되돌리기 목록). [before]는 고치거나 지우기 전, 넣은 일정은 nil. [eventId]는 지금의 일정 ID.
    public struct Change: Hashable, Sendable, Identifiable {
        public var id: Int64
        public var at: Int64
        public var action: Action
        public var eventId: Int64
        public var calendarName: String
        public var summary: String
        public var before: Backup?
        public var undone: Bool

        public init(_ id: Int64, _ at: Int64, _ action: Action, _ eventId: Int64, _ calendarName: String, _ summary: String,
                    _ before: Backup?, undone: Bool = false) {
            self.id = id
            self.at = at
            self.action = action
            self.eventId = eventId
            self.calendarName = calendarName
            self.summary = summary
            self.before = before
            self.undone = undone
        }
    }

    public static let UNDO_MAX = 30
    /// 14일.
    public static let UNDO_KEEP: TimeInterval = 14 * 86_400

    public static func changesToJson(_ list: [Change]) -> String {
        let a = JSONArray()
        for c in list {
            let o = JSONObject().put("id", c.id).put("at", c.at).put("action", c.action.id).put("eventId", c.eventId).put("calendar", c.calendarName)
                .put("summary", c.summary).put("undone", c.undone)
            if let b = c.before { o.put("before", b.toJson()) }
            a.put(o)
        }
        return a.toString()
    }

    public static func changesFromJson(_ s: String?) -> [Change] {
        guard let a = try? JSONArray(s ?? "[]") else { return [] }
        return (0..<a.length()).compactMap { i in
            guard let o = a.optJSONObject(i), let action = Action.of(o.optString("action")) else { return nil }
            return Change(o.optLong("id"), o.optLong("at"), action, o.optLong("eventId"), o.optString("calendar"), o.optString("summary"),
                          Backup.fromJson(o.optJSONObject("before")), undone: o.optBoolean("undone"))
        }
    }

    public static func nextChangeId(_ list: [Change], _ now: Date) -> Int64 { max(now.epochMilli, (list.map(\.id).max() ?? 0) + 1) }

    /// 같은 일정의 변경은 최근 것부터 되돌린다(예전 변경을 되돌려 더 새 변경을 덮어쓰지 않게).
    public static func canUndo(_ list: [Change], _ change: Change) -> Bool {
        !change.undone && list.first(where: { !$0.undone && $0.eventId == change.eventId })?.id == change.id
    }

    /// 지운 일정을 다시 넣으면 ID가 바뀐다. 그 일정의 예전 기록도 새 ID에 붙여 둔다.
    public static func finishUndo(_ list: [Change], _ id: Int64, _ restoredEventId: Int64? = nil) -> [Change] {
        guard let selected = list.first(where: { $0.id == id }) else { return list }
        return list.map { c in
            var linked = c
            if let restoredEventId, c.eventId == selected.eventId {
                linked.eventId = restoredEventId
                linked.before?.eventId = restoredEventId
            }
            if linked.id == id { linked.undone = true }
            return linked
        }
    }

    /// 새 기록을 앞에 두고 오래된 것([UNDO_KEEP])과 [UNDO_MAX]개 넘는 것을 버린다.
    public static func addChange(_ list: [Change], _ c: Change, _ now: Date) -> [Change] {
        let cutoff = now.addingTimeInterval(-UNDO_KEEP).epochMilli
        return Array(([c] + list).filter { $0.at >= cutoff }.prefix(UNDO_MAX))
    }

    /// `11/25(수) 14:00~15:00`, 종일이면 `11/25(수) 종일`(여러 날이면 `~11/26(목)`).
    public static func whenLabel(_ allDay: Bool, _ dtStart: Int64, _ dtEnd: Int64) -> String {
        if allDay {
            let sd = CalendarLogic.utcDate(dtStart)
            var ed = CalendarLogic.utcDate(dtEnd).minusDays(1)
            if ed < sd { ed = sd }
            return Agenda.dateLabel(sd) + (ed != sd ? "~" + Agenda.dateLabel(ed) : "") + " 종일"
        }
        let s = Date(epochMilli: dtStart)
        let e = Date(epochMilli: dtEnd)
        let ep = EtlLogic.kstParts(e)
        let end = ep.day == EtlLogic.kstParts(s).day ? String(format: "%02d:%02d", ep.hour, ep.minute) : NoticeText.dateLabel(e)
        return NoticeText.dateLabel(s) + "~" + end
    }

    public static func describe(_ title: String, _ allDay: Bool, _ dtStart: Int64, _ dtEnd: Int64, _ location: String) -> String {
        "\(NoticeText.cut(NoticeText.clean(title), 60)) · \(whenLabel(allDay, dtStart, dtEnd))" + (location.isBlankK ? "" : " · \(NoticeText.cut(location, 40))")
    }

    public static func describe(_ b: Backup) -> String { describe(b.title, b.allDay, b.dtStart, b.dtEnd, b.location) }

    /// 승인 창 본문: 짧은 알림에서 빠지는 연도·계정·내용까지 쓰기 전부를 보인다.
    public static func reviewDetails(_ calendarName: String, _ account: String, _ b: Backup) -> String {
        let time: String
        if b.allDay {
            let start = CalendarLogic.utcDate(b.dtStart)
            let end = CalendarLogic.utcDate(b.dtEnd).minusDays(1)
            time = "\(start) ~ \(end) (종일, 끝 날짜 포함)"
        } else {
            func fmt(_ ms: Int64) -> String {
                let p = EtlLogic.kstParts(Date(epochMilli: ms))
                return "\(p.day) " + String(format: "%02d:%02d", p.hour, p.minute)
            }
            time = "\(fmt(b.dtStart)) ~ \(fmt(b.dtEnd)) (한국 시각)"
        }
        return "캘린더: \(calendarName) (\(account))\n제목: \(b.title)\n시각: \(time)\n장소: \(b.location.isEmpty ? "없음" : b.location)\n메모: \(b.description.isEmpty ? "없음" : b.description)"
    }

    /// 채팅 알림 한 줄(바뀌기 전 → 뒤).
    public static func notice(_ action: Action, _ calendarName: String, _ before: Backup?, _ after: String?) -> String {
        let place = "\"\(NoticeText.cut(calendarName, 30))\" 캘린더"
        let body: String
        switch action {
        case .ADD: body = "\(place) 에 넣음: \(after ?? "null")"
        case .UPDATE: body = "\(place) 일정 고침: \(before.map(describe) ?? "null") → \(after ?? "null")"
        case .DELETE: body = "\(place) 에서 지움: \(before.map(describe) ?? "null")"
        }
        return "🗓 \(body) · 설정 → 캘린더 연동에서 되돌릴 수 있어요"
    }
}
