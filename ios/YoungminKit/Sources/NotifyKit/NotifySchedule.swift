import Foundation
import ETLKit

/// 알림 예약에 쓰는 순수 계산.
public enum NotifySchedule {
    /// 브리핑이 예정 시각보다 이만큼 넘게 늦으면 보내지 않는다(4시간).
    public static let MAX_LATE_MILLIS: Int64 = 4 * 3600 * 1000

    /// 안드로이드 WorkManager 주기 작업의 최소 flex 간격(5분).
    public static let MIN_FLEX_MILLIS: Int64 = 5 * 60 * 1000

    private static let HOUR_MILLIS: Int64 = 3600 * 1000

    /// [now]보다 **최소 [minLeadMillis] 뒤**인 가장 가까운 [minuteOfDay](0..1439)시각까지의 지연(ms), [zone] 기준.
    /// 서머타임: 없는 시각은 전환 뒤로 밀리고, 두 번 있는 시각은 이른 쪽을 쓴다.
    public static func nextOccurrenceDelayMillis(_ now: Date, _ zone: TimeZone, _ minuteOfDay: Int, minLeadMillis: Int64 = 60_000) -> Int64 {
        let m = min(max(minuteOfDay, 0), 1439)
        let nowMs = now.epochMilli
        var date = ZoneClock.localDate(now, zone)
        for _ in 0..<4 {
            let candidate = ZoneClock.instant(date, m / 60, m % 60, 0, zone)
            let delay = candidate.epochMilli - nowMs
            if delay >= minLeadMillis { return delay }
            date = date.plusDays(1)
        }
        return 24 * HOUR_MILLIS // 도달하지 않는다(안전장치)
    }

    /// 브리핑이 예정 시각보다 [maxLateMillis]를 넘게 늦게 실행됐는가(그러면 보내지 않는다).
    public static func isStale(_ scheduledAtMillis: Int64, _ nowMillis: Int64, maxLateMillis: Int64 = MAX_LATE_MILLIS) -> Bool {
        nowMillis - scheduledAtMillis > maxLateMillis
    }

    /// 이미 예약해 둔 브리핑([storedTargetMillis])을 그대로 둘지. 같은 시각(분) 설정의 예약이 아직 유효하면 건드리지 않는다.
    public static func keepBriefTarget(_ storedTargetMillis: Int64?, _ minuteOfDay: Int, _ nowMillis: Int64, _ zone: TimeZone,
                                       maxLateMillis: Int64 = MAX_LATE_MILLIS) -> Bool {
        guard let t = storedTargetMillis else { return false }
        if nowMillis - t > maxLateMillis { return false }
        if t - nowMillis > 2 * 24 * HOUR_MILLIS { return false }
        return ZoneClock.minuteOfDay(Date(epochMilli: t), zone) == min(max(minuteOfDay, 0), 1439)
    }

    /// 주기 확인의 flex 간격: 주기의 약 1/4, 최소 [MIN_FLEX_MILLIS], 최대 주기 자체.
    public static func flexMillis(_ intervalHours: Int) -> Int64 {
        let interval = Int64(max(intervalHours, 1)) * HOUR_MILLIS
        return min(max(interval / 4, MIN_FLEX_MILLIS), interval)
    }

    /// Java `String.hashCode()`.
    public static func javaHash(_ s: String) -> Int32 {
        var h: Int32 = 0
        for u in s.utf16 { h = h &* 31 &+ Int32(u) }
        return h
    }

    /// 같은 [tag]는 같은 정수 알림 ID가 되도록 하는 해시. 항상 0 이상.
    public static func notificationId(_ tag: String) -> Int { Int(javaHash(tag) & Int32.max) }

    /// 토큰 오류 알림은 마지막으로 띄운 뒤 [intervalMillis](기본 24시간)가 지나야 다시 띄운다.
    public static func authFailureDue(_ lastFiredMillis: Int64?, _ nowMillis: Int64, intervalMillis: Int64 = 24 * 3600 * 1000) -> Bool {
        guard let last = lastFiredMillis else { return true }
        return nowMillis - last >= intervalMillis
    }

    /// 목표 시각에 한 번 깨우는 요청.
    public struct AlarmRequest: Hashable, Sendable {
        public var atMillis: Int64
        public var exact: Bool
        public init(_ atMillis: Int64, _ exact: Bool) {
            self.atMillis = atMillis
            self.exact = exact
        }
    }

    public static func nextAlarm(_ nowMillis: Int64, _ targetMillis: Int64, _ exactAllowed: Bool) -> AlarmRequest {
        AlarmRequest(max(nowMillis, targetMillis), exactAllowed)
    }

    /// 재부팅·시간 변경 뒤 저장된 알람 목표를 어떻게 다시 걸지.
    public enum Rearm: Hashable, Sendable { case NONE, ARM, FIRE_NOW }

    /// 목표가 없으면 NONE, 아직 오지 않았으면 ARM, 꺼져 있던 사이 지났으면 FIRE_NOW.
    public static func rearmAction(_ targetMillis: Int64, _ nowMillis: Int64, _ deliverPersonalNow: Bool = false) -> Rearm {
        if deliverPersonalNow { return .FIRE_NOW }
        if targetMillis <= 0 { return .NONE }
        if targetMillis > nowMillis { return .ARM }
        return .FIRE_NOW
    }

    /// (iOS 추가) 지금 보내야 할 브리핑 예정 시각: 설정된 시각 중 [now] 이전 [MAX_LATE_MILLIS] 안에 있고
    /// 마지막 브리핑([lastBriefAtMillis]) 뒤인 가장 최근 시각. 없으면 nil. 백그라운드 새로고침이 브리핑 시각을 놓쳤을 때 쓴다.
    public static func dueBriefSlot(_ settings: NotifySettings, lastBriefAtMillis: Int64, _ now: Date, _ zone: TimeZone) -> Date? {
        if !settings.briefEnabled { return nil }
        let today = ZoneClock.localDate(now, zone)
        var best: Date?
        for m in settings.briefMinutesOfDay {
            let mm = min(max(m, 0), 1439)
            for d in [today.minusDays(1), today] {
                let t = ZoneClock.instant(d, mm / 60, mm % 60, 0, zone)
                if t > now || isStale(t.epochMilli, now.epochMilli) || t.epochMilli <= lastBriefAtMillis { continue }
                if best == nil || t > best! { best = t }
            }
        }
        return best
    }
}
