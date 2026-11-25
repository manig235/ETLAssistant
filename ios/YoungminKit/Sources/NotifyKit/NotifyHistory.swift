import Foundation
import ETLKit

/// 띄운 알림 기록. 실수로 지웠거나 사라진 알림을 다시 볼 수 있게 앱 안에만 [NotifyHistoryLogic.KEEP_DAYS]일,
/// 최대 [NotifyHistoryLogic.MAX_ITEMS]개 남긴다.
public struct HistoryEntry: Hashable, Sendable {
    /// epoch millis.
    public var at: Int64
    public var channel: String
    public var title: String
    public var text: String
    public var bigText: String?
    public var url: String?
    /// 시험·과제 공지의 "내 일정에 등록" 초안.
    public var draft: String?

    public init(_ at: Int64, _ channel: String, _ title: String, _ text: String, _ bigText: String?, _ url: String?, _ draft: String? = nil) {
        self.at = at
        self.channel = channel
        self.title = title
        self.text = text
        self.bigText = bigText
        self.url = url
        self.draft = draft
    }
}

public enum NotifyHistoryLogic {
    public static let KEEP_DAYS: Int64 = 90
    public static let MAX_ITEMS = 300

    public static func add(_ list: [HistoryEntry], _ e: HistoryEntry, _ nowMs: Int64) -> [HistoryEntry] {
        let cutoff = nowMs - KEEP_DAYS * 24 * 3600 * 1000
        return Array(stableSorted((list + [e]).filter { $0.at >= cutoff }) { $0.at < $1.at }.suffix(MAX_ITEMS))
    }

    public static func toJson(_ list: [HistoryEntry]) -> String {
        let arr = JSONArray()
        for e in list {
            let o = JSONObject().put("at", e.at).put("channel", e.channel).put("title", e.title).put("text", e.text)
                .put("big", e.bigText ?? JSONObject.NULL).put("url", e.url ?? JSONObject.NULL)
            if let d = e.draft { o.put("draft", d) }
            arr.put(o)
        }
        return arr.toString()
    }

    public static func fromJson(_ s: String?) -> [HistoryEntry] {
        guard let s, let arr = try? JSONArray(s) else { return [] }
        return (0..<arr.length()).compactMap { i in
            guard let o = arr.optJSONObject(i) else { return nil }
            return HistoryEntry(o.optLong("at"), o.optString("channel"), o.optString("title"), o.optString("text"),
                                o.isNull("big") ? nil : o.optString("big"), o.isNull("url") ? nil : o.optString("url"),
                                (!o.has("draft") || o.isNull("draft")) ? nil : o.optString("draft"))
        }
    }

    public static func channelLabel(_ id: String) -> String {
        switch id {
        case Channel.DEADLINE.id: return "마감"
        case Channel.ANNOUNCEMENT.id, NotificationPoster.LEGACY_ANNOUNCEMENT: return "공지"
        case Channel.BRIEF.id: return "브리핑"
        case Channel.CHANGE.id, NotificationPoster.LEGACY_CHANGE: return "과제·성적"
        case Channel.CLASS.id: return "수업"
        case Channel.UPDATE.id, NotificationPoster.LEGACY_UPDATE: return "업데이트"
        case Channel.SUBMIT.id, Channel.SUBMIT_OK.id: return "제출 확인"
        case Channel.NEW_FILES.id: return "강의자료"
        default: return "알림"
        }
    }
}

/// 알림 기록 저장소(Kotlin prefs `notify_history`의 `items`). 앱은 알림을 실제로 게시한 뒤 [record]를 부른다.
public final class NotifyHistory: @unchecked Sendable {
    private let prefs: KeyValueStore
    private let lock = NSLock()

    public init(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "notify_history")) { self.prefs = prefs }

    public func load() -> [HistoryEntry] { lock.withLock { NotifyHistoryLogic.fromJson(prefs.string("items")) } }

    public func record(_ n: Notice, nowMs: Int64 = Date().epochMilli) {
        lock.withLock {
            let e = HistoryEntry(nowMs, n.channel.id, n.title, n.text, n.bigText, n.url, n.draft)
            prefs.set(NotifyHistoryLogic.toJson(NotifyHistoryLogic.add(NotifyHistoryLogic.fromJson(prefs.string("items")), e, nowMs)), for: "items")
        }
    }

    public func clear() { lock.withLock { prefs.remove("items") } }
}
