import ChatKit
import Foundation
import NotifyKit

/// 설정 화면의 색 톤(상태 줄). Kotlin `StatusTone`.
enum StatusTone { case neutral, positive, caution, negative }

/// 계정 상태 줄: 문구, 톤, 진행 중(스피너) 여부. Kotlin `AccountStatus`.
struct AccountStatus: Equatable {
    let text: String
    let tone: StatusTone
    var loading = false
}

/// 플랫폼 API에 의존하지 않는 설정 화면 규칙(Kotlin `chat/SettingsLogic.kt`를 그대로 옮김).
enum SettingsLogic {
    /// 형식만 대충 본다(실제 유효성은 OpenAI가 판단). 공백, 한글 등은 거른다.
    static func isPlausibleApiKey(_ s: String) -> Bool {
        s.hasPrefix("sk-") && s.unicodeScalars.count >= 20 && s.unicodeScalars.allSatisfy { (0x21...0x7E).contains($0.value) }
    }

    /// `sk-…abcd`처럼 끝 4자리만 보인다.
    static func maskKey(_ s: String) -> String { s.count <= 8 ? "••••" : "sk-…" + String(s.suffix(4)) }

    /// eTL 토큰으로 쓸 수 있는 모양인가(`[\x21-\x7e]{8,}`). 실제 유효성은 eTL에 물어봐야 안다.
    static func isPlausibleEtlToken(_ s: String) -> Bool {
        s.unicodeScalars.count >= 8 && s.unicodeScalars.allSatisfy { (0x21...0x7E).contains($0.value) }
    }

    /// 계정 상태 줄. iOS에는 Codex 프로세스가 없어 `state`는 보통 `.ready`, 계정을 아직 읽지 않았으면 `account == nil`.
    static func accountStatus(_ state: SessionState, _ detail: String?, _ account: AccountInfo?) -> AccountStatus {
        if state == .failed { return AccountStatus(text: "비서를 시작하지 못했어요" + (detail.map { ": \($0)" } ?? ""), tone: .negative) }
        if state != .ready { return AccountStatus(text: "준비하는 중이에요…", tone: .neutral, loading: true) }
        guard let account else { return AccountStatus(text: "확인하는 중이에요…", tone: .neutral, loading: true) }
        switch account.kind {
        case .chatgpt:
            return AccountStatus(text: "로그인됨 · \(account.email ?? "ChatGPT")" + (account.plan.map { " · \($0)" } ?? ""), tone: .positive)
        case .apiKey: return AccountStatus(text: "API 키로 사용 중이에요", tone: .positive)
        case .unknown: return AccountStatus(text: "로그인됨", tone: .positive)
        case .none: return AccountStatus(text: "로그인되지 않았어요", tone: .neutral)
        }
    }

    // MARK: 브리핑 시각

    /// 브리핑 시각을 하나 바꾼다. 같은 시각이면 합치고, 정렬·개수 제한은 normalize가 맡는다.
    static func replaceBriefTime(_ times: [Int], _ index: Int, _ picked: Int) -> [Int] {
        var base = NotifyUiFormat.normalizeBriefTimes(times)
        guard base.indices.contains(index) else { return base }
        base[index] = picked
        return NotifyUiFormat.normalizeBriefTimes(base)
    }

    /// 브리핑 시각을 하나 더한다(최대 2개). 첫 시각의 12시간 뒤를 기본값으로 해서 같은 시각이 되지 않게 한다.
    static func addBriefTime(_ times: [Int]) -> [Int] {
        let base = NotifyUiFormat.normalizeBriefTimes(times)
        if base.count >= 2 { return base }
        return NotifyUiFormat.normalizeBriefTimes(base + [(base[0] + 12 * 60) % 1440])
    }

    /// 두 번째 브리핑 시각을 지운다(최소 1개는 남는다).
    static func removeSecondBriefTime(_ times: [Int]) -> [Int] {
        NotifyUiFormat.normalizeBriefTimes(Array(NotifyUiFormat.normalizeBriefTimes(times).prefix(1)))
    }

    // MARK: 마감 알림 시점(분)

    /// 칩으로 보일 시점(오름차순: 가까운 것부터).
    static func windowChips(_ current: [Int]) -> [Int] { NotifySettings.normalizeWindows(current).sorted() }

    /// 추가 대화상자의 빠른 버튼.
    static var quickWindows: [Int] { NotifySettings.DEADLINE_WINDOW_PRESETS }

    /// 시점 하나를 지운다. 지우면 하나도 남지 않으면 지우지 않고 nil(알림을 끄는 것은 스위치로).
    static func removeWindow(_ current: [Int], _ minutes: Int) -> [Int]? {
        let base = NotifySettings.normalizeWindows(current)
        guard base.contains(minutes) else { return base }
        let next = base.filter { $0 != minutes }
        return next.isEmpty ? nil : next
    }

    static let LAST_WINDOW_MESSAGE = "알림 시점이 하나는 있어야 해요. 알림을 끄려면 위 스위치를 쓰세요"

    static func canAddWindow(_ current: [Int]) -> Bool { NotifySettings.normalizeWindows(current).count < NotifySettings.MAX_WINDOWS }

    static func isDefaultWindows(_ current: [Int]) -> Bool {
        NotifySettings.normalizeWindows(current) == NotifySettings.normalizeWindows(NotifySettings.DEFAULT_DEADLINE_WINDOWS)
    }

    static func defaultWindows() -> [Int] { NotifySettings.normalizeWindows(NotifySettings.DEFAULT_DEADLINE_WINDOWS) }

    enum WindowUnit: CaseIterable {
        case minute, hour, day
        var label: String {
            switch self {
            case .minute: "분"
            case .hour: "시간"
            case .day: "일"
            }
        }
        var minutes: Int {
            switch self {
            case .minute: 1
            case .hour: 60
            case .day: 24 * 60
            }
        }
    }

    enum CustomWindow: Equatable {
        case ok(Int)
        case error(String)
    }

    /// 분 값을 추가할 수 있는지 본다(범위, 이미 있음, 개수).
    static func checkWindow(_ minutes: Int64, _ current: [Int]) -> CustomWindow {
        if minutes < Int64(NotifySettings.MIN_WINDOW_MINUTES) { return .error("\(NotifySettings.MIN_WINDOW_MINUTES)분 이상으로 정해 주세요") }
        if minutes > Int64(NotifySettings.MAX_WINDOW_MINUTES) { return .error("최대 14일 전까지 정할 수 있어요") }
        let base = NotifySettings.normalizeWindows(current)
        if base.contains(Int(minutes)) { return .error("이미 있는 시점이에요") }
        if base.count >= NotifySettings.MAX_WINDOWS {
            return .error("알림 시점은 \(NotifySettings.MAX_WINDOWS)개까지예요. 다른 시점을 먼저 지워 주세요")
        }
        return .ok(Int(minutes))
    }

    /// 직접 입력: 숫자와 단위를 분으로 바꿔 검사한다.
    static func parseCustomWindow(_ amountText: String, _ unit: WindowUnit, _ current: [Int]) -> CustomWindow {
        guard let amount = Int(amountText.trimmingCharacters(in: .whitespaces)) else { return .error("숫자를 입력해 주세요") }
        if amount <= 0 { return .error("1 이상으로 입력해 주세요") }
        return checkWindow(Int64(amount) * Int64(unit.minutes), current)
    }

    /// 시점을 더한다(이미 있으면 그대로, 개수 제한 안에서).
    static func addWindow(_ current: [Int], _ minutes: Int) -> [Int] {
        let base = NotifySettings.normalizeWindows(current)
        if base.contains(minutes) || base.count >= NotifySettings.MAX_WINDOWS { return base }
        return NotifySettings.normalizeWindows(base + [minutes])
    }

    // MARK: iOS

    /// 기기가 24시간제인가(Android `DateFormat.is24HourFormat`).
    static var is24Hour: Bool {
        !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }

    /// 건의사항·오류 제보용 카카오톡 오픈채팅.
    static let FEEDBACK_URL = "https://open.kakao.com/o/sR3ebBYi"
    static let ETL_TOKEN_URL = "https://myetl.snu.ac.kr/profile/settings"
}
