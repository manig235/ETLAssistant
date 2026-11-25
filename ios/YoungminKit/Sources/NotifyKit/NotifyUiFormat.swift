import Foundation
import ETLKit

/// 알림 설정 화면용 순수 포맷 헬퍼(Kotlin `chat/NotifyUiFormat.kt`).
public enum NotifyUiFormat {
    /// 하루 중 분(0..1439)을 `오전 8:00` 또는 24시간제 `08:00`으로. 범위 밖 값은 보정한다.
    public static func formatTime(_ minuteOfDay: Int, _ is24h: Bool) -> String {
        let m = min(max(minuteOfDay, 0), 1439)
        let h = m / 60
        let mi = m % 60
        if is24h { return String(format: "%02d:%02d", h, mi) }
        let ampm = h < 12 ? "오전" : "오후"
        let h12 = h % 12 == 0 ? 12 : h % 12
        return "\(ampm) \(h12):" + String(format: "%02d", mi)
    }

    /// 브리핑 시각 목록 정리: 0..1439로 보정, 중복 제거, 오름차순, 최대 2개, 비면 오전 8시.
    public static func normalizeBriefTimes(_ times: [Int]) -> [Int] {
        let r = Array(distinct(times.map { min(max($0, 0), 1439) }).sorted().prefix(2))
        return r.isEmpty ? [8 * 60] : r
    }

    /// 마감 알림 시점 이름(분 단위 값).
    public static func windowLabel(_ minutes: Int) -> String { NotifySettings.windowLabel(minutes) }

    /// "3일, 24시간, 3시간, 10분 전에 알려줘요"
    public static func windowsSummary(_ minutes: [Int]) -> String {
        NotifySettings.normalizeWindows(minutes).map(windowLabel).joined(separator: ", ") + " 전에 알려줘요"
    }

    public static func describeLastCheck(_ lastCheckAt: Int64, _ lastResult: String) -> String {
        if lastCheckAt <= 0 { return "아직 확인한 적이 없어요" }
        let time = EtlLogic.fmtKst(Date(epochMilli: lastCheckAt))
        let result = lastResult.kTrim
        return result.isEmpty ? "마지막 확인: \(time)" : "마지막 확인: \(time) · \(result)"
    }

    public static func describeLastBrief(_ lastBriefAt: Int64) -> String {
        if lastBriefAt <= 0 { return "아직 브리핑을 보낸 적이 없어요" }
        return "마지막 브리핑: " + EtlLogic.fmtKst(Date(epochMilli: lastBriefAt))
    }
}
