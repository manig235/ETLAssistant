import Foundation
import ETLKit
import NotifyKit

/*
 * 캘린더 → 영민이(Kotlin `CalendarRead.kt`). 사용자가 고른 폰 캘린더의 일정을 읽어 비서 도구에 보여 준다.
 * 이 파일은 순수 함수만 둔다(단위 테스트 대상). EventKit으로 읽는 부분은 CalendarEventKitStore.swift.
 * 네트워크는 쓰지 않는다. 일정 내용(남이 쓴 공유 캘린더 글 포함)은 신뢰하지 않는 데이터다.
 */

/// 캘린더 연동의 "캘린더 → 영민이" 설정. 읽을 캘린더 선택은 내 일정 보기와 비서가 함께 쓴다.
/// 캘린더 ID는 `CalendarStore`가 정한 양의 정수다(EventKit 식별자 문자열은 `CalendarEventKitStore`가 정수로 바꿔 기억한다).
public struct CalendarReadSettings: Hashable, Sendable {
    /// 내 일정·브리핑에 고른 캘린더의 일정을 보인다(기본 꺼짐). iOS에서는 아직 쓰는 화면이 없다(설정 값만 보존).
    public var showEnabled: Bool
    /// 비서가 고른 캘린더를 읽고 일정을 넣고·고치고·지운다(기본 꺼짐).
    public var assistantEnabled: Bool
    /// 읽을 캘린더.
    public var calendarIds: Set<Int64>
    /// 비서가 새 일정을 넣을 캘린더(고른 캘린더 중 쓸 수 있는 것).
    public var writeCalendarId: Int64?
    public var writeCalendarName: String

    public init(showEnabled: Bool = false, assistantEnabled: Bool = false, calendarIds: Set<Int64> = [],
                writeCalendarId: Int64? = nil, writeCalendarName: String = "") {
        self.showEnabled = showEnabled
        self.assistantEnabled = assistantEnabled
        self.calendarIds = calendarIds
        self.writeCalendarId = writeCalendarId
        self.writeCalendarName = writeCalendarName
    }

    public func toJson() -> String {
        JSONObject()
            .put("version", 1)
            .put("showEnabled", showEnabled)
            .put("assistantEnabled", assistantEnabled)
            .put("calendarIds", JSONArray(calendarIds.sorted().map { $0 as Any? }))
            .put("writeCalendarId", writeCalendarId ?? JSONObject.NULL)
            .put("writeCalendarName", writeCalendarName)
            .toString()
    }

    public static func fromJson(_ s: String?) -> CalendarReadSettings {
        guard let s, let o = try? JSONObject(s) else { return CalendarReadSettings() }
        let ids = o.optJSONArray("calendarIds")
        let writeId = o.isNull("writeCalendarId") ? nil : o.optLong("writeCalendarId")
        return CalendarReadSettings(
            showEnabled: o.optBoolean("showEnabled", false),
            assistantEnabled: o.optBoolean("assistantEnabled", false),
            calendarIds: ids.map { a in Set((0..<a.length()).map { a.optLong($0) }.filter { $0 > 0 }) } ?? [],
            writeCalendarId: writeId.flatMap { $0 > 0 ? $0 : nil },
            writeCalendarName: o.optString("writeCalendarName", ""))
    }
}

/// 캘린더에서 읽은 회차 하나(Kotlin `RawInstance`). 반복 일정은 회차마다 한 줄이다.
public struct RawInstance: Hashable, Sendable {
    public var eventId: Int64
    public var calendarId: Int64
    public var calendarName: String
    public var color: Int
    public var title: String?
    public var location: String?
    public var description: String?
    /// epoch millis. 종일 일정은 UTC 자정 기준(날짜만 의미가 있다).
    public var begin: Int64
    public var end: Int64
    public var allDay: Bool
    public var rrule: String?
    public var originalId: Int64?
    public var customAppUri: String?
    /// 상태(2 = 취소).
    public var status: Int?

    public init(_ eventId: Int64, _ calendarId: Int64, _ calendarName: String, _ color: Int, _ title: String?, _ location: String?,
                _ description: String?, _ begin: Int64, _ end: Int64, _ allDay: Bool, _ rrule: String? = nil, _ originalId: Int64? = nil,
                _ customAppUri: String? = nil, _ status: Int? = nil) {
        self.eventId = eventId
        self.calendarId = calendarId
        self.calendarName = calendarName
        self.color = color
        self.title = title
        self.location = location
        self.description = description
        self.begin = begin
        self.end = end
        self.allDay = allDay
        self.rrule = rrule
        self.originalId = originalId
        self.customAppUri = customAppUri
        self.status = status
    }
}

/// 화면·브리핑·도구에 쓰는 일정 하나. 종일 일정은 [start]·[end]가 KST 자정이고 [startDate]~[endDate](포함)가 날짜다.
public struct CalEvent: Hashable, Sendable {
    public var eventId: Int64
    public var calendarId: Int64
    public var calendarName: String
    public var color: Int
    public var title: String
    public var location: String
    public var description: String
    public var start: Date
    public var end: Date
    public var allDay: Bool
    public var startDate: DayDate
    public var endDate: DayDate
    public var recurring: Bool

    public var key: String { "cal-\(eventId)-\(start.epochMilli)" }
}

public enum CalendarReadLogic {
    public static let TITLE_MAX = 80
    public static let TEXT_MAX = 500
    /// 화면·도구에서 한 번에 보이는 최대 일정 수.
    public static let MAX_EVENTS = 300

    /// 영민이가 넣은 일정인가(내보내기한 일정: 우리 URI, 매핑된 ID, 앱 캘린더). 두 번 보이지 않게 뺀다.
    public static func isOurs(_ r: RawInstance, _ exportedEventIds: Set<Int64>, _ ownCalendarIds: Set<Int64>) -> Bool {
        ownCalendarIds.contains(r.calendarId) || exportedEventIds.contains(r.eventId) || (r.customAppUri?.hasPrefix(CalendarLogic.APP_URI_PREFIX) ?? false)
    }

    /// 읽은 회차를 일정으로 바꾼다: [selected] 캘린더만, 영민이가 넣은 일정·취소된 일정은 빼고, [from, to)와 겹치는 것만(KST), 시작순.
    /// 종일 일정의 날짜는 UTC 기준으로 저장되므로 UTC 날짜를 그대로 KST 날짜로 쓴다(끝은 다음 날 0시라 하루 빼서 포함 날짜로).
    public static func events(_ raw: [RawInstance], _ selected: Set<Int64>, _ exportedEventIds: Set<Int64>, _ ownCalendarIds: Set<Int64>,
                              _ from: Date, _ to: Date) -> [CalEvent] {
        var seen = Set<String>()
        let list = raw
            .filter { selected.contains($0.calendarId) && !isOurs($0, exportedEventIds, ownCalendarIds) && $0.status != 2 }
            .map(toEvent)
            .filter { ($0.end > from && $0.start < to) || ($0.start == $0.end && !($0.start < from) && $0.start < to) }
            .filter { seen.insert($0.key).inserted }
        let sorted = stableSorted(list) { a, b in
            if a.start != b.start { return a.start < b.start }
            if a.allDay != b.allDay { return a.allDay } // !allDay 오름차순: 종일 먼저
            if a.title != b.title { return a.title < b.title }
            return a.eventId < b.eventId
        }
        return Array(sorted.prefix(MAX_EVENTS))
    }

    public static func toEvent(_ r: RawInstance) -> CalEvent {
        let title = NoticeText.clean(r.title).take16(TITLE_MAX).nilIfEmpty ?? "(제목 없음)"
        let location = NoticeText.clean(r.location).take16(TEXT_MAX)
        let description = CalendarLogic.cleanMultiline(r.description ?? "").take16(TEXT_MAX)
        if r.allDay {
            let sd = CalendarLogic.utcDate(r.begin)
            let endExclusive = CalendarLogic.utcDate(r.end)
            let ed = endExclusive > sd ? endExclusive.minusDays(1) : sd
            return CalEvent(eventId: r.eventId, calendarId: r.calendarId, calendarName: r.calendarName, color: r.color, title: title,
                            location: location, description: description, start: sd.startOfDay, end: ed.plusDays(1).startOfDay,
                            allDay: true, startDate: sd, endDate: ed, recurring: recurring(r))
        }
        let s = Date(epochMilli: r.begin)
        let e = Date(epochMilli: max(r.end, r.begin))
        let lastInstant = max(e.addingTimeInterval(-0.001), s)
        return CalEvent(eventId: r.eventId, calendarId: r.calendarId, calendarName: r.calendarName, color: r.color, title: title,
                        location: location, description: description, start: s, end: e, allDay: false,
                        startDate: DayDate(s), endDate: DayDate(lastInstant), recurring: recurring(r))
    }

    public static func recurring(_ r: RawInstance) -> Bool { !(r.rrule ?? "").isBlankK || (r.originalId ?? 0) > 0 }

    /// 종일 일정은 범위를 넓혀 읽은 뒤 [events]에서 KST 날짜로 거른다(UTC 저장이라 9시간 어긋날 수 있다).
    public static func queryRange(_ from: Date, _ to: Date) -> (Date, Date) {
        (from.addingTimeInterval(-86_400), to.addingTimeInterval(86_400))
    }

    /// `10:00~11:30`, 종일이면 `종일`, 여러 날 종일이면 `11/24~11/26 종일`, 날을 넘기면 `10:00~11/25 02:00`.
    public static func timeLabel(_ e: CalEvent) -> String {
        if e.allDay {
            return e.startDate == e.endDate ? "종일" : "\(e.startDate.monthValue)/\(e.startDate.dayOfMonth)~\(e.endDate.monthValue)/\(e.endDate.dayOfMonth) 종일"
        }
        let s = EtlLogic.kstParts(e.start)
        let en = EtlLogic.kstParts(e.end)
        let hs = String(format: "%02d:%02d", s.hour, s.minute)
        if e.end == e.start { return hs }
        let he = String(format: "%02d:%02d", en.hour, en.minute)
        return (en.day == s.day || (en.hour == 0 && en.minute == 0 && en.day == s.day.plusDays(1))) ? "\(hs)~\(he)"
            : "\(hs)~\(en.day.monthValue)/\(en.day.dayOfMonth) \(he)"
    }

    /// 브리핑 "오늘 일정" 줄: 오늘(KST)과 겹치는 일정, 종일 먼저. `• 종일 · 동아리 MT`, `• 14:00~15:00 · 면담 · 301동`.
    public static func briefLines(_ events: [CalEvent], _ today: DayDate) -> [String] {
        let start = today.startOfDay
        let end = today.plusDays(1).startOfDay
        let today = events.filter { ($0.end > start && $0.start < end) || ($0.start == $0.end && !($0.start < start) && $0.start < end) }
        return stableSorted(today) { a, b in
            if a.allDay != b.allDay { return a.allDay }
            if a.start != b.start { return a.start < b.start }
            return a.title < b.title
        }.map { e in
            "• \(timeLabel(e)) · \(NoticeText.cut(e.title, 50))" + (e.location.isEmpty ? "" : " · \(NoticeText.cut(e.location, 30))")
        }
    }

    /// 비서 도구 `calendar_events` 결과의 한 줄. 설명은 [TEXT_MAX]자로 자른다.
    public static func eventJson(_ e: CalEvent, writable: Bool) -> JSONObject {
        let o = JSONObject()
            .put("event_id", e.eventId)
            .put("calendar", e.calendarName)
            .put("title", e.title)
            .put("all_day", e.allDay)
        if e.allDay {
            o.put("start_date", e.startDate.description)
            o.put("end_date", e.endDate.description)
        } else {
            o.put("start_kst", fmt(e.start))
            o.put("end_kst", fmt(e.end))
        }
        if !e.location.isEmpty { o.put("location", e.location) }
        if !e.description.isEmpty { o.put("memo", e.description) }
        o.put("recurring", e.recurring)
        o.put("can_edit", writable && !e.recurring)
        return o
    }

    public static func fmt(_ t: Date) -> String { EtlLogic.fmtKst(t) }

    /// 캘린더 선택 요약: `캘린더 2개(개인, 학교)`.
    public static func selectionLabel(_ names: [String]) -> String {
        if names.isEmpty { return "고른 캘린더가 없어요" }
        if names.count <= 3 { return "캘린더 \(names.count)개(\(names.joined(separator: ", ")))" }
        return "캘린더 \(names.count)개(\(names.prefix(2).joined(separator: ", ")) 외 \(names.count - 2)개)"
    }
}
