import CryptoKit
import ETLKit
import Foundation

/*
 * 캘린더 연동의 "영민이 → 캘린더"(Kotlin `calendar/CalendarLogic.kt`). eTL 과제 마감과 내 마감(시험 포함)을 기기 캘린더(EventKit) 또는 .ics 파일로 보낸다.
 * 사용자가 캘린더에서 고친 내 마감 일정은 되가져오고(내 마감 시각·제목·시험 장소), eTL 과제 일정은 원래대로 되돌린다([edits]).
 * 네트워크는 쓰지 않는다: eTL 과제는 알림 확인 때 받아 둔 결과(SnapshotCache), 내 마감은 기기 안 저장값만 쓴다.
 * 이 파일은 순수 함수만 둔다(단위 테스트 대상). 맞추기 순서는 CalendarSyncExport.swift, EventKit은 CalendarSyncEventKit.swift.
 *
 * iOS: Kotlin `CalendarLogic`의 비서 도구 쪽(TZ_ID, APP_URI_PREFIX, cleanMultiline)은 AssistantKit의 `CalendarLogic`에 이미 있어
 * 이름이 겹치지 않게 이 묶음은 `CalendarSyncLogic`이라 부른다. 일정·캘린더 ID는 EventKit 식별자(문자열)다(안드로이드는 `Events._ID` 정수).
 */

/// 내보낼 곳. LOCAL: 앱이 만든 "영민이" 캘린더(이 기기, 로컬 소스가 없으면 iCloud). GOOGLE: 사용자가 고른 계정 캘린더(iCloud·구글 등).
/// 저장 키는 안드로이드와 같다("local", "google").
public enum CalendarTarget: String, CaseIterable, Hashable, Sendable {
    case LOCAL = "local", GOOGLE = "google"

    public var key: String { rawValue }
    public static func of(_ key: String?) -> CalendarTarget { CalendarTarget(rawValue: key ?? "") ?? .LOCAL }
}

public struct CalendarExportSettings: Hashable, Sendable {
    public var enabled: Bool
    public var target: CalendarTarget
    /// GOOGLE일 때 고른 캘린더의 EventKit 식별자와 보일 이름.
    public var googleCalendarId: String?
    public var googleCalendarName: String
    public var includeEtl: Bool
    public var includeMine: Bool
    /// 제출·완료한 항목도 보낸다(기본 끔: 제출·완료하면 캘린더에서 지운다).
    public var includeDone: Bool
    /// 캘린더에서 고친 내 마감 일정을 앱에 되가져온다(기본 켬). 끄면 고친 일정도 앱 내용으로 되돌린다.
    public var pullBack: Bool

    public init(enabled: Bool = false, target: CalendarTarget = .LOCAL, googleCalendarId: String? = nil, googleCalendarName: String = "",
                includeEtl: Bool = true, includeMine: Bool = true, includeDone: Bool = false, pullBack: Bool = true) {
        self.enabled = enabled
        self.target = target
        self.googleCalendarId = googleCalendarId
        self.googleCalendarName = googleCalendarName
        self.includeEtl = includeEtl
        self.includeMine = includeMine
        self.includeDone = includeDone
        self.pullBack = pullBack
    }

    public func toJson() -> String {
        JSONObject()
            .put("version", 1)
            .put("enabled", enabled)
            .put("target", target.key)
            .put("googleCalendarId", googleCalendarId ?? JSONObject.NULL)
            .put("googleCalendarName", googleCalendarName)
            .put("includeEtl", includeEtl)
            .put("includeMine", includeMine)
            .put("includeDone", includeDone)
            .put("pullBack", pullBack)
            .toString()
    }

    public static func fromJson(_ s: String?) -> CalendarExportSettings {
        guard let s, let o = try? JSONObject(s) else { return CalendarExportSettings() }
        let gid = o.isNull("googleCalendarId") ? nil : o.optString("googleCalendarId").nilIfEmpty
        return CalendarExportSettings(
            enabled: o.optBoolean("enabled", false),
            target: CalendarTarget.of(o.optString("target")),
            googleCalendarId: gid,
            googleCalendarName: o.optString("googleCalendarName", ""),
            includeEtl: o.optBoolean("includeEtl", true),
            includeMine: o.optBoolean("includeMine", true),
            includeDone: o.optBoolean("includeDone", false),
            pullBack: o.optBoolean("pullBack", true))
    }
}

/// 캘린더 일정 하나(마감 하나). 마감은 [start] == [end]인 0분짜리, 시험은 시작~끝(끝을 모르면 0분) 일정이다.
public struct CalItem: Hashable, Sendable {
    /// `etl:{courseId}:{assignmentId}`, `mine:{id}`, 반복 회차는 `mine:{id}:{epochDay}`.
    public var key: String
    public var title: String
    public var start: Date
    public var end: Date
    public var description: String
    public var url: String?
    /// 시험 장소.
    public var location: String
    /// 바쁨으로 표시(시험). 마감은 바쁨 아님.
    public var busy: Bool

    public init(_ key: String, _ title: String, _ start: Date, _ end: Date, _ description: String, url: String? = nil,
                location: String = "", busy: Bool = false) {
        self.key = key
        self.title = title
        self.start = start
        self.end = end
        self.description = description
        self.url = url
        self.location = location
        self.busy = busy
    }

    /// 바뀌었는지 비교하는 값(일정 내용만). 장소가 없으면 예전 값과 같다(이미 넣은 일정을 다시 쓰지 않게).
    public var hash: String {
        CalendarSyncLogic.hash([title, String(start.epochMilli), String(end.epochMilli), description, url ?? "", CalendarSyncLogic.TZ_ID] +
            (!location.isEmpty || busy ? [location, busy ? "true" : "false"] : []))
    }

    /// 캘린더에 실제로 들어간 모양(제목·시각·장소)의 값. 사용자가 캘린더에서 고쳤는지 볼 때 쓴다.
    public var rowHash: String { CalendarSyncLogic.rowHash(title, start.epochMilli, end.epochMilli, location) }
}

/// 캘린더에서 읽은 내보낸 일정의 지금 모양([CalendarSyncLogic.edits]에 쓴다). [eventId]는 EventKit 일정 식별자.
public struct CalRow: Hashable, Sendable {
    public var eventId: String
    public var title: String
    public var start: Int64
    public var end: Int64
    public var location: String

    public init(_ eventId: String, _ title: String, _ start: Int64, _ end: Int64, _ location: String) {
        self.eventId = eventId
        self.title = title
        self.start = start
        self.end = end
        self.location = location
    }

    public var hash: String { CalendarSyncLogic.rowHash(title, start, end, location) }
}

public enum CalendarSyncLogic {
    public static let TZ_ID = "Asia/Seoul"
    public static var ZONE: TimeZone { EtlLogic.kst }
    public static let APP_URI_PREFIX = "youngmin://item/"

    /// 지난 마감도 이 기간까지는 캘린더에 남겨 둔다(그보다 오래된 일정은 더 건드리지 않는다).
    public static let KEEP_PAST: TimeInterval = 14 * 86_400
    /// 반복 내 마감은 앞으로 이만큼의 회차를 펼쳐 하나씩 넣는다.
    public static let REPEAT_AHEAD: TimeInterval = 60 * 86_400
    /// 이보다 오래된 매핑은 잊는다(일정은 캘린더에 그대로 둔다).
    public static let FORGET_AFTER: TimeInterval = 180 * 86_400

    public static func etlKey(_ courseId: Int64, _ assignmentId: Int64) -> String { "etl:\(courseId):\(assignmentId)" }
    public static func mineKey(_ d: MyDeadline) -> String { "mine:\(d.id)" + (d.occurrence.map { ":\($0.epochDay)" } ?? "") }
    public static func prefixOf(_ key: String) -> String { key.substringBefore(":") }

    /// 일정에 다는 주소 `youngmin://item/{key}`. iOS는 키의 `:`를 `-`로 쓴다(`mine-5-20000`): 위젯 링크(`youngmin://item/etl-1-2`)와 같은 모양이라
    /// 앱의 주소 처리 하나(HomeWidgetBridge)가 내 마감 항목·eTL 과제를 연다.
    public static func appUri(_ key: String) -> String { APP_URI_PREFIX + key.replacingOccurrences(of: ":", with: "-") }

    /// [appUri]의 키(`mine:5:20000`). 영민이 주소가 아니면 nil. 안드로이드 모양(`:`)도 읽는다.
    public static func keyFromAppUri(_ uri: String?) -> String? {
        guard let uri, uri.hasPrefix(APP_URI_PREFIX) else { return nil }
        let key = String(uri.dropFirst(APP_URI_PREFIX.count)).replacingOccurrences(of: "-", with: ":")
        return key.isEmpty ? nil : key
    }

    /// `youngmin://item/{key}`에서 내 마감 ID(없으면 nil). 캘린더 앱의 일정 링크(URL)로 앱을 열 때 쓴다.
    public static func mineIdFromUri(_ uri: String?) -> Int64? {
        guard let key = keyFromAppUri(uri), prefixOf(key) == "mine" else { return nil }
        let parts = key.split(separator: ":", omittingEmptySubsequences: false)
        return parts.count > 1 ? Int64(parts[1]) : nil
    }

    /// SHA-256 앞 12바이트(16진수). 조각마다 UTF-8 + 0바이트.
    public static func hash(_ parts: [String]) -> String {
        var h = SHA256()
        for p in parts {
            h.update(data: Data(p.utf8))
            h.update(data: Data([0]))
        }
        return h.finalize().prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    // ---- 내보낼 항목 -------------------------------------------------------------

    /// 캘린더에 쓸 eTL 링크: eTL 주소이고 쿼리·조각이 없는 것만(서명 링크·토큰이 붙을 여지를 없앤다).
    public static func safeEtlUrl(_ url: String?) -> String? {
        guard let u = NoticeText.safeUrl(url), !u.contains("?"), !u.contains("#") else { return nil }
        return u
    }

    public static func etlTitle(_ a: AssignmentItem) -> String {
        "[\(NoticeText.clean(TimetableLogic.shortName(a.courseName)).nilIfEmpty ?? "eTL")] \(NoticeText.clean(a.name))" +
            (a.submitted ? " (제출함)" : "")
    }

    public static func etlDescription(_ a: AssignmentItem) -> String {
        [
            "eTL 과제 · \(NoticeText.clean(a.courseName))",
            a.submitted ? "제출함" : (!a.online ? "오프라인 제출" : nil),
            safeEtlUrl(a.url),
            FOOTER,
        ].compactMap { $0 }.joined(separator: "\n")
    }

    public static func mineTitle(_ d: MyDeadline) -> String {
        d.isExam ? MyDeadlines.examTitle(d) : "[내 마감] \(NoticeText.clean(d.title))" + (d.done ? " (완료)" : "")
    }

    public static func mineDescription(_ d: MyDeadline) -> String {
        let supplies = d.supplies.kTrim
        let memo = d.memo.kTrim
        return [
            d.isExam && !supplies.isEmpty ? "준비물: \(NoticeText.clean(supplies))" : nil,
            memo.isEmpty ? nil : cleanMultiline(memo),
            (d.isExam ? "시험" : "내 마감") + (d.repeat != nil ? " · \(MyDeadlines.repeatLabel(d))" : ""),
            FOOTER,
        ].compactMap { $0 }.joined(separator: "\n")
    }

    /// 캘린더 제목에서 앱이 붙인 앞뒤 말을 떼고 내 마감 제목만(되가져오기용). 시험은 과목도 뗀다. 비면 nil.
    public static func titleFromCalendar(_ calendarTitle: String, _ d: MyDeadline) -> String? {
        var t = NoticeText.clean(calendarTitle)
        if d.isExam {
            t = t.removingPrefix("[시험]").kTrim
            let course = NoticeText.clean(d.course)
            if !course.isEmpty && t.hasPrefix("\(course) ") && !NoticeText.clean(d.title).contains(course) {
                t = t.removingPrefix(course).kTrim
            }
        } else {
            t = t.removingPrefix("[내 마감]").kTrim
            if t.hasSuffix("(완료)") { t = String(t.dropLast("(완료)".count)) }
            t = t.kTrim
        }
        return t.kTake(MyDeadlines.TITLE_MAX).nilIfEmpty
    }

    public static let FOOTER = "영민이 앱이 넣은 일정이에요. 앱에서 바뀌면 여기도 바뀌어요."

    /// 줄바꿈은 남기고 줄마다 제어·보이지 않는 문자를 정리한다(Kotlin `lines()`: \r\n, \n, \r).
    public static func cleanMultiline(_ s: String) -> String {
        s.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { NoticeText.clean(String($0)) }
            .joined(separator: "\n").kTrim
    }

    public static func etlItem(_ a: AssignmentItem) -> CalItem? {
        guard let due = a.due else { return nil }
        return CalItem(etlKey(a.courseId, a.assignmentId), etlTitle(a), due, due, etlDescription(a), url: safeEtlUrl(a.url))
    }

    public static func mineItem(_ d: MyDeadline) -> CalItem {
        if d.isExam {
            return CalItem(mineKey(d), mineTitle(d), d.due, d.end ?? d.due, mineDescription(d), location: NoticeText.clean(d.location), busy: true)
        }
        return CalItem(mineKey(d), mineTitle(d), d.due, d.due, mineDescription(d))
    }

    public static func rowHash(_ title: String, _ start: Int64, _ end: Int64, _ location: String) -> String {
        hash([NoticeText.clean(title), String(start), String(end), NoticeText.clean(location)])
    }

    /// 캘린더에 있어야 할 일정. [authoritative]에 없는 종류는 이미 넣은 일정을 지우지 않는다.
    public struct Desired: Hashable, Sendable {
        public var items: [CalItem]
        public var authoritative: Set<String>
        public init(_ items: [CalItem], _ authoritative: Set<String>) {
            self.items = items
            self.authoritative = authoritative
        }
    }

    /// [assignments]가 nil이면(받아 둔 eTL 과제가 없음) eTL 쪽은 비우되 [authoritative]에서 빼서 이미 넣은 eTL 일정을 지우지 않는다.
    /// 숨긴 과목은 넣지 않는다. 마감이 [KEEP_PAST]보다 오래된 것은 넣지 않는다.
    /// 반복 내 마감은 [now]−[KEEP_PAST] ~ [now]+[REPEAT_AHEAD]의 회차를 하나씩 펼친다(반복 규칙을 쓰지 않는다).
    public static func desired(_ settings: CalendarExportSettings, _ assignments: [AssignmentItem]?, _ mine: [MyDeadline],
                               _ courses: CourseFilter, _ now: Date) -> Desired {
        let from = now.addingTimeInterval(-KEEP_PAST)
        var out: [CalItem] = []
        if settings.includeEtl, let assignments {
            for a in assignments where a.courseId > 0 && !courses.hidden(a.courseId) {
                guard let due = a.due, !(due < from) else { continue }
                if !settings.includeDone && a.submitted { continue }
                if let item = etlItem(a) { out.append(item) }
            }
        }
        if settings.includeMine {
            for d in mine {
                let list = d.repeat == nil ? (d.due < from ? [] : [d]) : MyDeadlines.occurrences(d, from, now.addingTimeInterval(REPEAT_AHEAD))
                for o in list where settings.includeDone || !o.done { out.append(mineItem(o)) }
            }
        }
        var authoritative: Set<String> = ["mine"]
        if !settings.includeEtl || assignments != nil { authoritative.insert("etl") }
        return Desired(distinctBy(out) { $0.key }, authoritative)
    }

    // ---- 맞추기(reconcile) -------------------------------------------------------

    /// 매핑 한 줄. [eventId]가 [TOMBSTONE]이면 사용자가 캘린더에서 지운 일정(다시 만들지 않는다).
    /// [rowHash]는 마지막으로 쓴 뒤 캘린더에서 다시 읽은 모양(제목·시각·장소). 비어 있으면 아직 모른다(예전 매핑).
    /// [reverts]는 같은 내용으로 되돌린 횟수(캘린더 쪽이 값을 바꿔 써서 되돌리기가 끝없이 반복되지 않게, [MAX_REVERTS]).
    public struct Entry: Hashable, Sendable {
        public var eventId: String
        public var hash: String
        public var dueMillis: Int64
        public var rowHash: String
        public var reverts: Int

        public init(_ eventId: String, _ hash: String, _ dueMillis: Int64, _ rowHash: String = "", _ reverts: Int = 0) {
            self.eventId = eventId
            self.hash = hash
            self.dueMillis = dueMillis
            self.rowHash = rowHash
            self.reverts = reverts
        }

        public var tombstone: Bool { eventId == TOMBSTONE }
    }

    public static let MAX_REVERTS = 3

    /// [Edit.PullMine]의 값: 반복 없는 내 마감의 바뀐 제목·시각(시험은 끝·장소도). nil은 그대로.
    public struct Pull: Hashable, Sendable {
        public var key: String
        public var id: Int64
        public var title: String?
        public var start: Date?
        public var end: Date?
        public var location: String?

        public init(_ key: String, _ id: Int64, _ title: String?, _ start: Date?, _ end: Date?, _ location: String?) {
            self.key = key
            self.id = id
            self.title = title
            self.start = start
            self.end = end
            self.location = location
        }
    }

    /// 캘린더에서 고친 것을 어떻게 할지.
    public enum Edit: Hashable, Sendable {
        /// 반복 없는 내 마감: 바뀐 제목·시각(시험은 끝·장소도)을 앱에 반영한다.
        case PullMine(Pull)
        /// eTL 과제·반복 회차·되가져오기 꺼짐: 앱 내용으로 되돌린다.
        case Revert(key: String, repeating: Bool)
        /// 같은 일정을 [MAX_REVERTS]번 되돌려도 다시 바뀌면 지금 모양을 받아들인다(되돌리지 않음).
        case Accept(key: String, rowHash: String)
        /// 예전 매핑이라 모양을 몰랐다: 지금 모양을 기준으로 삼는다.
        case Baseline(key: String, rowHash: String)

        public var key: String {
            switch self {
            case .PullMine(let p): return p.key
            case .Revert(let k, _), .Accept(let k, _), .Baseline(let k, _): return k
            }
        }
    }

    /// 사용자가 캘린더에서 고친 일정을 찾는다. [rows]는 매핑된 일정 중 아직 있는 것의 지금 모양(일정 ID로).
    /// - 앱에서도 바뀌었으면(desired 해시 ≠ 매핑 해시) 앱이 이긴다(plan의 Update가 덮는다): 여기서는 아무것도 하지 않는다.
    /// - 반복 없는 내 마감 + [pullBack]: 바뀐 제목·시각(시험은 끝·장소)을 되가져온다. 제목이 비면 제목은 그대로.
    /// - 반복 내 마감 회차: 반복 전체를 바꾸지 않고 되돌린다(앱에는 반영하지 않음).
    /// - eTL 과제(읽기 전용)·[pullBack] 꺼짐: 되돌린다.
    public static func edits(_ desired: Desired, _ mapping: [String: Entry], _ rows: [String: CalRow], _ mine: [MyDeadline],
                             pullBack: Bool) -> [Edit] {
        var out: [Edit] = []
        var wanted: [String: CalItem] = [:]
        for it in desired.items where wanted[it.key] == nil { wanted[it.key] = it }
        for key in mapping.keys.sorted() {
            let e = mapping[key]!
            if e.tombstone { continue }
            guard let row = rows[e.eventId], let item = wanted[key] else { continue }
            if item.hash != e.hash { continue } // 앱 쪽이 바뀜 → plan이 고친다
            if e.rowHash.isEmpty { out.append(.Baseline(key: key, rowHash: row.hash)); continue }
            if row.hash == e.rowHash { continue }
            let parts = key.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            let mineId = parts[0] == "mine" && parts.count > 1 ? Int64(parts[1]) : nil
            let repeating = parts[0] == "mine" && parts.count > 2
            let d = mineId.flatMap { id in mine.first { $0.id == id } }
            if pullBack, let d, !repeating, d.repeat == nil {
                let title = titleFromCalendar(row.title, d).flatMap { NoticeText.clean(row.title) != item.title && $0 != d.title ? $0 : nil }
                let start = row.start != item.start.epochMilli ? Date(epochMilli: row.start) : nil
                let end = d.isExam && row.end != item.end.epochMilli && row.end > row.start ? Date(epochMilli: row.end) : nil
                let location: String? = d.isExam ? NoticeText.clean(row.location).kTake(MyDeadlines.LOCATION_MAX) : nil
                let changedLocation = location.flatMap { $0 != item.location ? $0 : nil }
                if title != nil || start != nil || end != nil || changedLocation != nil {
                    out.append(.PullMine(Pull(key, d.id, title, start, end, changedLocation)))
                    continue
                }
                // 되가져올 것이 없는 변화(설명만 등): 지금 모양을 기준으로.
                out.append(.Accept(key: key, rowHash: row.hash))
                continue
            }
            out.append(e.reverts >= MAX_REVERTS ? .Accept(key: key, rowHash: row.hash) : .Revert(key: key, repeating: repeating))
        }
        return out
    }

    /// [Edit.PullMine]을 내 마감에 적용한 새 값. 시각이 바뀌면 새 ID(알림 기록 규칙, 화면과 같다)이고 알림 시각은 같은 만큼 옮긴다.
    /// 시험의 끝은 시작을 옮기면 길이를 유지한다(끝도 바뀌었으면 그 값). 검증에 걸리면 nil(되돌린다).
    public static func applyPull(_ d: MyDeadline, _ e: Pull, _ newId: Int64) -> MyDeadline? {
        let start = e.start ?? d.due
        let shift = start.timeIntervalSince(d.due)
        let end: Date? = !d.isExam ? nil : (e.end ?? d.end?.addingTimeInterval(shift))
        var new = d
        new.id = start != d.due ? newId : d.id
        new.title = e.title ?? d.title
        new.due = start
        new.end = end
        new.location = e.location ?? d.location
        new.reminders = d.reminders.map { $0.addingTimeInterval(shift) }.filter { $0 < start }
        if MyDeadlines.validate(new.title, new.memo, 0) != nil || MyDeadlines.validateExam(new) != nil { return nil }
        return new
    }

    /// 사용자가 지운 일정의 매핑 표시(EventKit 식별자는 비어 있지 않다).
    public static let TOMBSTONE = ""

    public enum Op: Hashable, Sendable {
        case Insert(CalItem)
        case Update(String, CalItem)
        case Delete(String, String)
    }

    public struct Plan: Hashable, Sendable {
        public var ops: [Op]
        /// 캘린더에서 없어진 일정 → 묘비로 바꿀 키.
        public var tombstone: [String]
        /// 매핑에서만 지울 키(일정은 건드리지 않음).
        public var forget: [String]

        public init(_ ops: [Op], _ tombstone: [String], _ forget: [String]) {
            self.ops = ops
            self.tombstone = tombstone
            self.forget = forget
        }

        public var inserts: Int { ops.filter { if case .Insert = $0 { return true }; return false }.count }
        public var updates: Int { ops.filter { if case .Update = $0 { return true }; return false }.count }
        public var deletes: Int { ops.filter { if case .Delete = $0 { return true }; return false }.count }
    }

    /// [mapping]과 [desired]를 비교해 할 일을 정한다. [live]는 매핑된 일정 중 캘린더에 아직 있는 일정 ID.
    /// - 새 항목은 넣고, 내용이 바뀐 항목은 고친다.
    /// - 사용자가 캘린더에서 지운 일정(매핑은 있는데 [live]에 없음)은 묘비로 남기고 다시 만들지 않는다.
    /// - 원본이 사라졌거나 제출·완료된 항목은 지운다. 단 [Desired.authoritative]에 없는 종류는 건드리지 않고,
    ///   마감이 [KEEP_PAST]보다 오래된 일정은 지난 기록으로 남겨 둔다([FORGET_AFTER]가 지나면 매핑만 잊는다).
    public static func plan(_ desired: Desired, _ mapping: [String: Entry], _ live: Set<String>, _ now: Date,
                            forceUpdate: Set<String> = []) -> Plan {
        var ops: [Op] = []
        var tomb: [String] = []
        var forget: [String] = []
        let wanted = Set(desired.items.map(\.key))
        for item in desired.items {
            guard let e = mapping[item.key] else { ops.append(.Insert(item)); continue }
            if e.tombstone { continue }
            if !live.contains(e.eventId) { tomb.append(item.key); continue }
            if e.hash != item.hash || forceUpdate.contains(item.key) { ops.append(.Update(e.eventId, item)) }
        }
        let keepFrom = now.addingTimeInterval(-KEEP_PAST).epochMilli
        let forgetBefore = now.addingTimeInterval(-FORGET_AFTER).epochMilli
        for key in mapping.keys.sorted() where !wanted.contains(key) {
            let e = mapping[key]!
            if !desired.authoritative.contains(prefixOf(key)) { continue }
            if e.tombstone || !live.contains(e.eventId) { forget.append(key) }
            else if e.dueMillis < forgetBefore { forget.append(key) }
            else if e.dueMillis < keepFrom { continue }
            else { ops.append(.Delete(key, e.eventId)) }
        }
        return Plan(ops, tomb, forget)
    }

    public struct Mapping: Hashable, Sendable {
        public var calendarId: String
        public var entries: [String: Entry]
        public init(_ calendarId: String, _ entries: [String: Entry]) {
            self.calendarId = calendarId
            self.entries = entries
        }
    }

    public static func mappingToJson(_ calendarId: String, _ entries: [String: Entry]) -> String {
        let o = JSONObject()
        for k in entries.keys.sorted() {
            let e = entries[k]!
            o.put(k, JSONArray().put(e.eventId).put(e.hash).put(e.dueMillis).put(e.rowHash).put(e.reverts))
        }
        return JSONObject().put("version", 1).put("calendarId", calendarId).put("entries", o).toString()
    }

    public static func mappingFromJson(_ s: String?) -> Mapping? {
        guard let s, let o = try? JSONObject(s) else { return nil }
        var entries: [String: Entry] = [:]
        if let e = o.optJSONObject("entries") {
            for k in e.keys() {
                guard let a = e.optJSONArray(k) else { continue }
                entries[k] = Entry(a.optString(0, TOMBSTONE), a.optString(1, ""), a.optLong(2, 0), a.optString(3, ""), a.optInt(4, 0))
            }
        }
        return Mapping(o.optString("calendarId", ""), entries)
    }

    // ---- 반복 규칙(RRULE) ---------------------------------------------------------

    private static let BYDAY = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]

    public static func byDay(_ days: Set<Weekday>) -> String { days.sorted().map { BYDAY[$0.rawValue - 1] }.joined(separator: ",") }

    /// 반복 규칙의 모양([rrule] 문자열과 EventKit `EKRecurrenceRule`이 함께 쓴다).
    public struct Recurrence: Hashable, Sendable {
        public enum End: Hashable, Sendable {
            case none
            case count(Int)
            case until(Date)
        }
        public var freq: MyRepeat.Freq
        /// 1(매일·매주·매월) 또는 2(격주).
        public var interval: Int
        /// 매주·격주의 요일(월요일부터).
        public var weekdays: [Weekday]
        /// 매월의 날짜(31일이면 −1 = 말일).
        public var monthDay: Int?
        public var end: End
    }

    /// 내 마감 반복 규칙의 모양. 반복이 없으면 nil. 규칙은 [rrule] 설명과 같다.
    public static func recurrence(_ d: MyDeadline, fromFirst: Bool = true) -> Recurrence? {
        guard let r = d.repeat else { return nil }
        let start = DayDate(d.due)
        let weekdays = MyDeadlines.weekdaysOf(d).sorted()
        let rule: (Int, [Weekday], Int?)
        switch r.freq {
        case .DAILY: rule = (1, [], nil)
        case .WEEKLY: rule = (1, weekdays, nil)
        case .BIWEEKLY: rule = (2, weekdays, nil)
        case .MONTHLY: rule = (1, [], start.dayOfMonth == 31 ? -1 : start.dayOfMonth)
        }
        // 끝: 횟수면 COUNT. 단 시작 회차가 첫 회차가 아니거나(나중 회차에서 추가), 날짜도 함께 있거나, 매월 29·30일(캘린더 앱이 2월을 빼서 횟수가 어긋남)이면
        // 실제 마지막 회차 시각을 UNTIL로 쓴다(RFC 5545는 COUNT와 UNTIL을 함께 못 쓴다).
        let monthlyGap = r.freq == .MONTHLY && (29...30).contains(start.dayOfMonth)
        let end: Recurrence.End
        if let count = r.count, r.until == nil, fromFirst, !monthlyGap {
            end = .count(count)
        } else if let count = r.count {
            end = MyDeadlines.lastOccurrence(d).map { .until($0.due) } ?? .count(count)
        } else if let until = r.until {
            end = .until(until.atTime(23, 59, 59))
        } else {
            end = .none
        }
        return Recurrence(freq: r.freq, interval: rule.0, weekdays: rule.1, monthDay: rule.2, end: end)
    }

    /// 내 마감 반복 규칙 → RFC 5545 RRULE(앞의 `RRULE:` 없이). 반복이 없으면 nil.
    /// - 매월: 시작일이 28일 이하면 `BYMONTHDAY=d`, 31일이면 `BYMONTHDAY=-1`(앱처럼 그달 말일).
    ///   29·30일이면 `BYMONTHDAY=d`를 쓰므로 그날이 없는 2월 회차는 캘린더 앱에서 빠진다(앱은 2월 말일로 맞춘다. 알려진 차이).
    /// - 격주: `INTERVAL=2;WKST=MO`. 시작 시각([dtstartFor])이 규칙에 맞는 회차여야 앱과 같은 주에 맞춰진다.
    /// - 끝 날짜: 그날(KST) 23:59:59를 UTC로 바꾼 `UNTIL`(그날 회차 포함).
    public static func rrule(_ d: MyDeadline, fromFirst: Bool = true) -> String? {
        guard let rec = recurrence(d, fromFirst: fromFirst) else { return nil }
        let base: String
        switch rec.freq {
        case .DAILY: base = "FREQ=DAILY"
        case .WEEKLY: base = "FREQ=WEEKLY;BYDAY=\(byDay(Set(rec.weekdays)))"
        case .BIWEEKLY: base = "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=\(byDay(Set(rec.weekdays)))"
        case .MONTHLY: base = "FREQ=MONTHLY;BYMONTHDAY=\(rec.monthDay ?? 1)"
        }
        switch rec.end {
        case .none: return base
        case .count(let n): return base + ";COUNT=\(n)"
        case .until(let t): return base + ";UNTIL=" + utc(t)
        }
    }

    /// 이 날짜가 [rrule]이 만드는 회차인가(29·30일 매월 반복의 2월 말일처럼 앱과 다른 날을 거른다).
    private static func rruleHas(_ d: MyDeadline, _ date: DayDate) -> Bool {
        guard let r = d.repeat, r.freq == .MONTHLY else { return true }
        let day = DayDate(d.due).dayOfMonth
        return day == 31 || date.dayOfMonth == day
    }

    /// 반복 마감을 반복 규칙으로 넣을 때 시작 회차: [from] 회차(없으면 첫 회차)부터 규칙에 맞는 첫 회차. [d]는 저장된 원래 항목. 없으면 nil.
    public static func dtstartFor(_ d: MyDeadline, _ from: MyDeadline? = nil) -> MyDeadline? {
        if d.repeat == nil { return d }
        let begin = (from?.due ?? d.due).addingTimeInterval(-1)
        return MyDeadlines.occurrences(d, begin, begin.addingTimeInterval(400 * 86_400))
            .first { o in o.occurrence.map { rruleHas(d, $0) } ?? false }
    }

    /// `yyyyMMdd'T'HHmmss'Z'`(UTC).
    public static func utc(_ t: Date) -> String {
        let s = t.epochSecond
        let day = DayDate(epochDay: s >= 0 ? s / 86_400 : -((-s + 86_399) / 86_400))
        let sod = Int(s - day.epochDay * 86_400)
        return String(format: "%04d%02d%02dT%02d%02d%02dZ", day.year, day.month, day.day, sod / 3600, sod / 60 % 60, sod % 60)
    }

    /// `yyyyMMdd'T'HHmmss`(KST).
    public static func local(_ t: Date) -> String {
        let p = EtlLogic.kstParts(t)
        return String(format: "%04d%02d%02dT%02d%02d%02d", p.day.year, p.day.month, p.day.day, p.hour, p.minute, p.second)
    }

    // ---- .ics(RFC 5545) ---------------------------------------------------------

    /// TEXT 값 이스케이프: `\` `;` `,` 줄바꿈. 그 밖의 제어문자는 지운다.
    public static func escapeText(_ s: String) -> String {
        var out = ""
        let u = Array(s.unicodeScalars)
        var i = 0
        while i < u.count {
            let ch = u[i]
            switch ch {
            case "\\": out += "\\\\"
            case ";": out += "\\;"
            case ",": out += "\\,"
            case "\r":
                out += "\\n"
                if i + 1 < u.count && u[i + 1] == "\n" { i += 1 }
            case "\n": out += "\\n"
            default:
                if !ch.isISOControl { out.unicodeScalars.append(ch) }
            }
            i += 1
        }
        return out
    }

    /// 한 줄을 75옥텟(UTF-8)씩 접는다. 이어지는 줄은 공백 하나로 시작한다. 글자(코드 포인트)를 가운데서 자르지 않는다.
    public static func fold(_ line: String) -> String {
        var out = String.UnicodeScalarView()
        var bytes = 0
        for cp in line.unicodeScalars {
            let b = cp.value < 0x80 ? 1 : cp.value < 0x800 ? 2 : cp.value < 0x10000 ? 3 : 4
            if bytes + b > 75 {
                out.append(contentsOf: "\r\n ".unicodeScalars)
                bytes = 1
            }
            out.append(cp)
            bytes += b
        }
        return String(out)
    }

    /// eTL 과제와 내 마감을 VCALENDAR로. 반복 내 마감은 펼치지 않고 RRULE 하나로 넣고, 건너뛴 회차(와 [CalendarExportSettings.includeDone]이
    /// 꺼져 있으면 완료한 회차)는 EXDATE로 뺀다. 0분짜리 일정이라 DTEND는 넣지 않는다(RFC 5545: DTEND는 DTSTART보다 뒤여야 함).
    public static func ics(_ settings: CalendarExportSettings, _ assignments: [AssignmentItem], _ mine: [MyDeadline],
                           _ courses: CourseFilter, _ now: Date) -> String {
        let from = now.addingTimeInterval(-KEEP_PAST)
        var lines = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//etl-android//Youngmin//KO",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:\(escapeText("영민이"))",
            "X-WR-TIMEZONE:\(TZ_ID)",
            "BEGIN:VTIMEZONE",
            "TZID:\(TZ_ID)",
            "BEGIN:STANDARD",
            "DTSTART:19700101T000000",
            "TZOFFSETFROM:+0900",
            "TZOFFSETTO:+0900",
            "TZNAME:KST",
            "END:STANDARD",
            "END:VTIMEZONE",
        ]
        let stamp = utc(now)
        func event(_ uidKey: String, _ title: String, _ start: Date, _ desc: String, _ url: String?, extra: [String] = [],
                   end: Date? = nil, location: String = "") {
            let timed = end.map { $0 > start } ?? false
            lines.append("BEGIN:VEVENT")
            lines.append("UID:\(uidKey.replacingOccurrences(of: ":", with: "-"))@youngmin.etl-android")
            lines.append("DTSTAMP:\(stamp)")
            lines.append("DTSTART;TZID=\(TZ_ID):\(local(start))")
            if timed, let end { lines.append("DTEND;TZID=\(TZ_ID):\(local(end))") }
            lines.append(contentsOf: extra)
            lines.append("SUMMARY:\(escapeText(title))")
            if !location.isEmpty { lines.append("LOCATION:\(escapeText(location))") }
            lines.append("DESCRIPTION:\(escapeText(desc))")
            if let url { lines.append("URL:\(url)") }
            lines.append(timed ? "TRANSP:OPAQUE" : "TRANSP:TRANSPARENT")
            lines.append("END:VEVENT")
        }
        if settings.includeEtl {
            let list = assignments.filter { a in
                guard a.courseId > 0, !courses.hidden(a.courseId), let due = a.due, !(due < from) else { return false }
                return settings.includeDone || !a.submitted
            }
            for a in stableSorted(list, by: { $0.due! < $1.due! }) {
                if let it = etlItem(a) { event(it.key, it.title, it.start, it.description, it.url) }
            }
        }
        if settings.includeMine {
            for d in stableSorted(mine, by: { $0.due < $1.due }) {
                guard d.repeat != nil else {
                    if d.due < from || (!settings.includeDone && d.done) { continue }
                    let item = mineItem(d)
                    event(mineKey(d), item.title, d.due, item.description, nil, end: item.end, location: item.location)
                    continue
                }
                if let lastDate = MyDeadlines.lastDate(d), lastDate < DayDate(from) { continue }
                guard let first = dtstartFor(d) else { continue }
                let fromFirst = first.occurrence == MyDeadlines.firstOccurrence(d)?.occurrence
                let t = EtlLogic.kstParts(d.due)
                let ex = d.skipDates.union(settings.includeDone ? [] : d.doneDates)
                    .filter { !($0 < (first.occurrence ?? $0)) }.sorted()
                    .map { local($0.atTime(t.hour, t.minute, t.second)) }
                var extra = ["RRULE:\(rrule(d, fromFirst: fromFirst) ?? "")"]
                if !ex.isEmpty { extra.append("EXDATE;TZID=\(TZ_ID):\(ex.joined(separator: ","))") }
                var plain = d
                plain.done = false
                event("mine:\(d.id)", mineTitle(plain), first.due, mineDescription(d), nil, extra: extra)
            }
        }
        lines.append("END:VCALENDAR")
        return lines.map { fold($0) + "\r\n" }.joined()
    }

    public static func icsFileName(_ now: Date) -> String {
        let p = EtlLogic.kstParts(now).day
        return String(format: "youngmin-%04d%02d%02d.ics", p.year, p.month, p.day)
    }

    // ---- 상태 문구 ---------------------------------------------------------------

    public static func resultLabel(_ plan: Plan, _ at: Date) -> String {
        let z = EtlLogic.kstParts(at)
        let parts = [
            plan.inserts > 0 ? "추가 \(plan.inserts)" : nil,
            plan.updates > 0 ? "바뀜 \(plan.updates)" : nil,
            plan.deletes > 0 ? "지움 \(plan.deletes)" : nil,
        ].compactMap { $0 }
        return String(format: "%d/%d %02d:%02d 맞춤 · ", z.day.month, z.day.day, z.hour, z.minute) +
            (parts.isEmpty ? "바뀐 것 없음" : parts.joined(separator: " · "))
    }

    /// 되가져오기 결과 문구: ` · 캘린더에서 고친 내 마감 1개 반영 · 되돌림 2`.
    public static func editsLabel(_ pulled: Int, _ reverted: Int, _ repeatingReverted: Int) -> String {
        [
            pulled > 0 ? "캘린더에서 고친 내 마감 \(pulled)개 반영" : nil,
            reverted - repeatingReverted > 0 ? "eTL 과제 일정 되돌림 \(reverted - repeatingReverted)" : nil,
            repeatingReverted > 0 ? "반복 회차 되돌림 \(repeatingReverted)(반복 마감은 앱에서 고쳐 주세요)" : nil,
        ].compactMap { $0 }.map { " · \($0)" }.joined()
    }
}

/// 권한이 없거나 캘린더가 거부했을 때(Kotlin `SecurityException`).
public struct CalendarPermissionError: Error, Sendable {
    public init() {}
}

/// 지우기에 성공할 때까지 넣은 일정의 소유(매핑)를 놓지 않는다. 대상을 바꿀 때 이전 일정을 버려 두지 않는다(Kotlin `CalendarCleanupPolicy`).
/// 무엇을 지우는지는 [CalendarAppCalendarCleanup](앱 캘린더)과 매핑(계정 캘린더)이 정한다. 사용자가 넣은 일정은 지우지 않는다.
public enum CalendarCleanupPolicy {
    public static let PERMISSION_MESSAGE = "캘린더 권한이 없어 넣은 일정을 지우지 못했어요"

    public static func cleanup(permitted: Bool, delete: () throws -> Void, forget: () -> Void) -> String? {
        if !permitted { return PERMISSION_MESSAGE }
        do {
            try delete()
            forget()
            return nil
        } catch is CalendarPermissionError {
            return PERMISSION_MESSAGE
        } catch {
            return "넣은 일정을 다 지우지 못했어요(\(String(describing: type(of: error))))"
        }
    }

    public static func switchTarget(cleanup: () -> String?, activate: () -> String?) -> String? { cleanup() ?? activate() }
}

/// 앱 캘린더("영민이")를 정리할 때 읽은 일정 하나. 반복 일정은 회차마다 따로 올 수 있다(같은 [id]).
public struct CalendarScannedEvent: Hashable, Sendable {
    public var id: String
    public var url: String?
    public var notes: String?

    public init(_ id: String, url: String? = nil, notes: String? = nil) {
        self.id = id
        self.url = url
        self.notes = notes
    }
}

/// 캘린더 하나를 넓은 기간에 걸쳐 읽은 결과. [complete]가 false면(캘린더를 못 찾았거나 기간 일부를 못 읽음) 비어 있다고 믿지 않는다.
public struct CalendarScan: Hashable, Sendable {
    public var events: [CalendarScannedEvent]
    public var complete: Bool

    public init(_ events: [CalendarScannedEvent], complete: Bool = true) {
        self.events = events
        self.complete = complete
    }
}

/// 앱 캘린더 정리 규칙(끄기·대상 바꾸기). 사용자가 앱 캘린더에 직접 넣은 일정(iCloud에 있는 "영민이" 캘린더 포함)은 절대 지우지 않는다.
/// 1. 앱이 넣은 일정만 지운다: 매핑에 있는 일정 + 영민이 표시(주소 `youngmin://item/` 또는 설명 끝 문구)가 있는 일정.
/// 2. 지운 뒤 다시 읽어, 넓은 기간 전체를 읽었고 앱이 넣지 않은 일정이 하나도 없을 때만 캘린더를 지운다. 확신할 수 없으면 캘린더를 남긴다
///    (남긴 캘린더는 식별자를 기억해 다음에 켤 때 그대로 다시 쓴다).
public enum CalendarAppCalendarCleanup {
    /// 정리할 때 읽는 기간(지금 앞뒤로). EventKit 조회는 4년까지라 1년씩 나눠 읽는다.
    public static let SCAN_YEARS_BACK = 30
    public static let SCAN_YEARS_AHEAD = 30

    /// 영민이가 넣은 일정인가(주소 또는 설명 끝 문구).
    public static func isAppEvent(url: String?, notes: String?) -> Bool {
        (url?.hasPrefix(CalendarSyncLogic.APP_URI_PREFIX) ?? false) || (notes ?? "").contains(CalendarSyncLogic.FOOTER)
    }

    public static func isAppEvent(_ e: CalendarScannedEvent) -> Bool { isAppEvent(url: e.url, notes: e.notes) }

    /// 지울 일정: 매핑에 있는 일정(묘비 빼고) + 읽은 일정 중 앱이 넣은 것. 겹치지 않게, 순서는 일정하게.
    public static func eventsToDelete(mapped: [String: CalendarSyncLogic.Entry], scan: CalendarScan?) -> [String] {
        var seen = Set<String>()
        let fromMapping = mapped.sorted { $0.key < $1.key }.map(\.value).filter { !$0.tombstone }.map(\.eventId)
        let fromScan = (scan?.events ?? []).filter { isAppEvent($0) }.map(\.id)
        return (fromMapping + fromScan).filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    /// 앱이 넣지 않은 일정 수(반복 회차는 하나로 센다).
    public static func userEventCount(_ scan: CalendarScan?) -> Int {
        Set((scan?.events ?? []).filter { !isAppEvent($0) }.map(\.id)).count
    }

    /// 앱 일정을 지운 뒤 다시 읽은 결과로 캘린더 자체를 지워도 되는가. 기간 전체를 읽었고 사용자 일정이 없을 때만.
    public static func canRemoveCalendar(_ afterDelete: CalendarScan?) -> Bool {
        guard let afterDelete, afterDelete.complete else { return false }
        return userEventCount(afterDelete) == 0
    }
}
