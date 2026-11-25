import Foundation
import ETLKit

// (iOS 추가) 로컬 알림 게시·예약을 돕는 순수 부분: 알림 저장 형식, 버튼·본문 탭이 갈 곳, 예약해 둔 알림 장부, 예약 브리핑.

/// [Notice]를 알림 `userInfo`·예약 장부에 넣을 JSON으로.
public enum NoticeCodec {
    public static func toJson(_ n: Notice) -> String { toObject(n).toString() }

    public static func toObject(_ n: Notice) -> JSONObject {
        JSONObject().put("channel", n.channel.id).put("title", n.title).put("text", n.text).put("bigText", n.bigText).put("url", n.url)
            .put("tag", n.tag).put("keys", JSONArray(n.firedKeys)).put("prefill", n.prefill).put("prefillAction", n.prefillAction)
            .put("draft", n.draft).put("agenda", n.agenda ? true : nil).put("mineItemId", n.mineItemId)
    }

    public static func fromJson(_ s: String?) -> Notice? {
        guard let s, let o = try? JSONObject(s) else { return nil }
        return fromObject(o)
    }

    public static func fromObject(_ o: JSONObject) -> Notice? {
        guard let channel = Channel(rawValue: o.optString("channel")), o.has("tag") else { return nil }
        let keys = o.optJSONArray("keys").map { a in (0..<a.length()).map { a.optString($0) } } ?? []
        func str(_ k: String) -> String? { o.isNull(k) ? nil : o.optString(k) }
        return Notice(channel, o.optString("title"), o.optString("text"), bigText: str("bigText"), url: str("url"), tag: o.optString("tag"),
                      firedKeys: keys, prefill: str("prefill"), prefillAction: str("prefillAction"), draft: str("draft"),
                      agenda: o.optBoolean("agenda"), mineItemId: o.isNull("mineItemId") ? nil : o.optLong("mineItemId"))
    }
}

/// 알림 본문·버튼을 눌렀을 때 앱이 할 일(안드로이드 `NotificationPoster.post`의 PendingIntent들).
public enum NoticeOpen: Hashable, Sendable {
    /// "내 일정에 등록"/"시간표에 반영": 초안 JSON으로 미리 채운 창.
    case draft(String)
    /// 채팅 입력창 채우기(보내지 않음). nil이면 채팅만 연다.
    case chat(prefill: String?)
    /// eTL(LearningX 앱, 없으면 웹).
    case etl(String)
    /// 내 일정(항목 ID가 있으면 그 항목 창).
    case agenda(Int64?)

    /// 버튼 식별자(`UNNotificationAction.identifier`).
    public static let ACTION_DRAFT = "ym.draft"
    public static let ACTION_PREFILL = "ym.prefill"
    public static let ACTION_ETL = "ym.etl"

    public static func identifier(_ a: NotificationPoster.Action) -> String {
        switch a {
        case .draft: return ACTION_DRAFT
        case .prefill: return ACTION_PREFILL
        case .openEtl: return ACTION_ETL
        }
    }

    /// 버튼 이름들로 만든 알림 범주 ID. 같은 버튼 조합(이름까지)이면 같은 범주를 쓴다. 버튼이 없으면 nil.
    public static func categoryId(_ actions: [NotificationPoster.Action], hideOnLockScreen: Bool) -> String? {
        let acts = Array(actions.prefix(3))
        if acts.isEmpty && !hideOnLockScreen { return nil }
        return (hideOnLockScreen ? "ym.hidden:" : "ym:") + acts.map { identifier($0) + "=" + $0.label }.joined(separator: "|")
    }

    /// [action]: 누른 버튼 식별자(본문이면 그 밖의 값, 예: `UNNotificationDefaultActionIdentifier`).
    public static func resolve(_ n: Notice, action: String) -> NoticeOpen {
        let acts = NotificationPoster.actions(n)
        switch action {
        case ACTION_DRAFT:
            for a in acts { if case .draft(_, let json) = a { return .draft(json) } }
        case ACTION_PREFILL:
            for a in acts { if case .prefill(_, let text) = a { return .chat(prefill: text) } }
        case ACTION_ETL:
            for a in acts { if case .openEtl(let url) = a { return .etl(url) } }
        default:
            break
        }
        switch NoticeRouting.tap(n) {
        case .CHAT_PREFILL, .CHAT: return .chat(prefill: n.prefill)
        case .ETL: return NoticeText.safeUrl(n.url).map { .etl($0) } ?? .chat(prefill: n.prefill)
        case .AGENDA: return .agenda(n.mineItemId)
        }
    }
}

/// 예약해 둔 로컬 알림 장부. iOS는 예약 알림이 뜰 때 앱을 깨우지 않으므로, 다음에 앱이 돌 때 "시각이 지났고 대기 목록에 없는" 항목을
/// 뜬 것으로 보고 중복 방지 키·알림 기록·마지막 브리핑 시각을 남긴다(같은 알림을 바로 다시 보내지 않게).
public enum ScheduledNotices {
    /// 같은 항목의 여러 알림·반복 회차가 서로의 예약을 덮어쓰지 않게 시각을 포함한다.
    public static func timedEntry(_ notice: Notice, at: Date) -> Entry {
        Entry("timed:\(notice.tag):\(at.epochMilli)", at.epochMilli, notice)
    }

    public struct Entry: Hashable, Sendable {
        /// 알림 요청 식별자.
        public var id: String
        /// 울릴 시각(epoch millis).
        public var at: Int64
        public var notice: Notice
        public init(_ id: String, _ at: Int64, _ notice: Notice) {
            self.id = id
            self.at = at
            self.notice = notice
        }
    }

    public static func toJson(_ list: [Entry]) -> String {
        let a = JSONArray()
        for e in list { a.put(JSONObject().put("id", e.id).put("at", e.at).put("notice", NoticeCodec.toObject(e.notice))) }
        return a.toString()
    }

    public static func fromJson(_ s: String?) -> [Entry] {
        guard let a = try? JSONArray(s ?? "[]") else { return [] }
        return (0..<a.length()).compactMap { i in
            guard let o = a.optJSONObject(i), let n = o.optJSONObject("notice").flatMap(NoticeCodec.fromObject) else { return nil }
            return Entry(o.optString("id"), o.optLong("at"), n)
        }
    }

    /// 뜬 것([fired]: 시각이 지났고 대기 목록에 없음)과 남길 것([keep])으로 나눈다.
    public static func resolve(_ list: [Entry], pendingIds: Set<String>, nowMs: Int64) -> (fired: [Entry], keep: [Entry]) {
        var fired: [Entry] = []
        var keep: [Entry] = []
        for e in list {
            if e.at <= nowMs && !pendingIds.contains(e.id) { fired.append(e) } else { keep.append(e) }
        }
        return (fired.sorted { $0.at < $1.at }, keep)
    }

    /// 뜬 알림의 중복 방지 키를 그 시각으로 기록하고, 브리핑이면 마지막 브리핑 시각을 올린다.
    public static func apply(_ fired: [Entry], to state: inout NotifyState) {
        for e in fired {
            for k in e.notice.firedKeys where state.fired[k] == nil { state.fired[k] = e.at }
            if e.notice.channel == .BRIEF { state.lastBriefAt = max(state.lastBriefAt, e.at) }
        }
    }
}

/// 브리핑 예약(안드로이드 `BriefWorker`가 정각에 받아 보내던 것). iOS는 정각에 앱을 깨울 수 없어, 받아 둔 정보로 만든 브리핑을 미리 예약하고
/// 그 시각 가까이 앱·백그라운드 갱신이 돌면 새 정보로 바꾼다.
public enum BriefForecast {
    /// 앞으로 [horizonMillis] 안의 브리핑 예정 시각(오름차순, 겹침 없음). 꺼져 있으면 빈 목록.
    public static func upcomingSlots(_ settings: NotifySettings, _ now: Date, _ zone: TimeZone, horizonMillis: Int64 = 48 * 3600 * 1000) -> [Date] {
        if !settings.briefEnabled { return [] }
        let today = ZoneClock.localDate(now, zone)
        let nowMs = now.epochMilli
        var out = Set<Date>()
        for m in settings.briefMinutesOfDay {
            let mm = min(max(m, 0), 1439)
            for k in 0...3 {
                let t = ZoneClock.instant(today.plusDays(k), mm / 60, mm % 60, 0, zone)
                let delta = t.epochMilli - nowMs
                if delta > 0 && delta <= horizonMillis { out.insert(t) }
            }
        }
        return out.sorted()
    }

    /// 받아 둔 정보(eTL 과제 저장본·내 마감·시간표 저장본)만으로 [at] 시각의 브리핑을 만든다. 공지는 저장본이 없어 빠진다.
    /// eTL 토큰도 내 마감도 없으면 nil(안드로이드 `runBrief`가 건너뛰는 경우).
    public static func offline(_ stores: NotifyStores, at: Date, zone: TimeZone = .current, hasToken: Bool, todayEvents: [String] = []) -> Notice? {
        let settings = stores.settings.load()
        let mine = stores.mine.load()
        if !hasToken && mine.isEmpty { return nil }
        let courses = stores.courses.load()
        let r = SnapshotFallback.apply(Snapshot(at, [], []), stores.snapshot.load(), at, totalFailure: true)
        let snapshot = courses.visible(MyDeadlines.merge(r.snapshot, mine, at))
        var classes: [String] = []
        if hasToken, let w = stores.timetable.load() {
            let day = courses.timetableDay(TimetableLogic.day(w, ZoneClock.localDate(at, zone), stores.timetableChanges.load()))
            classes = TimetableLogic.briefLines(day.classes) + day.cancelled.map { "• \($0.start) \($0.course) 휴강" + ($0.note.map { n in " (\(n))" } ?? "") }
        }
        return BriefBuilder.build(snapshot, at, settings, zone, todayClasses: classes, staleSince: r.cachedAt,
                                  exams: MyDeadlines.examLines(mine, at), todayEvents: todayEvents)
    }
}
