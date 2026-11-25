import ETLKit
import SwiftUI
import UserNotifications

/// eTL 토큰 확인 상태(Kotlin `TokenState`).
enum TokenState: Equatable {
    case idle
    case checking
    /// 저장됨. `name`은 방금 확인한 이름(이미 저장돼 있던 토큰이면 nil).
    case saved(name: String?)
    case error(String)
}

/// 첫 실행 안내의 화면 상태(Kotlin `OnboardingViewModel`). 네트워크 확인과 설정 저장도 여기서 한다.
@MainActor
@Observable
final class OnboardingModel {
    var step: OnboardingStep = .welcome

    // eTL 토큰
    var tokenInput = ""
    var tokenState: TokenState

    // 알림
    var notifyStatus: UNAuthorizationStatus = .notDetermined
    var deadlineAlerts = true
    var announcementAlerts = true
    var briefEnabled = true
    var briefMinutes = OnboardingLogic.briefTimeChoices[1]
    /// 방해 금지: 기본 꺼짐. 켜면 23:00~07:00(바꿀 수 있음).
    var quietEnabled = false
    var quietStart = 23 * 60
    var quietEnd = 7 * 60
    /// 마감 알림 시점(안내에서는 보여 주기만 한다).
    let deadlineWindows: [Int]

    private let tokens = AppStores.tokens
    private let notifyStore = AppStores.notifySettings

    init() {
        tokenState = AppStores.tokens.hasToken() ? .saved(name: nil) : .idle
        deadlineWindows = NotifyPrefs.deadlineWindows(AppStores.notifySettings)
        // 기존 사용자가 안내를 다시 볼 때는 저장된 알림 설정을 그대로 보여 준다.
        if AppStores.tokens.hasToken() {
            let s = notifyStore
            deadlineAlerts = s.bool("deadlineAlerts", default: true)
            announcementAlerts = s.bool("announcementAlerts", default: true)
            briefEnabled = s.bool("briefEnabled", default: false)
            briefMinutes = NotifyPrefs.ints(s, "briefMinutesOfDay").first ?? briefMinutes
            quietEnabled = s.bool("quietEnabled", default: false)
            quietStart = min(max(s.int("quietStartMinutes", default: quietStart), 0), 1439)
            quietEnd = min(max(s.int("quietEndMinutes", default: quietEnd), 0), 1439)
        }
    }

    var tokenSaved: Bool { if case .saved = tokenState { return true } else { return false } }
    var hasStoredToken: Bool { tokens.hasToken() }

    func goNext() { withAnimation(.easeOut(duration: 0.22)) { step = OnboardingLogic.next(step) } }
    func goBack() { withAnimation(.easeOut(duration: 0.22)) { step = OnboardingLogic.previous(step) } }

    // MARK: eTL 토큰

    /// 입력한 토큰으로 eTL에 실제로 접속해 확인하고(`GET /api/v1/users/self`), 맞으면 Keychain에 저장한다.
    func checkAndSaveToken() {
        let token = tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard OnboardingLogic.isPlausibleEtlToken(token) else {
            tokenState = .error("토큰 모양이 아니에요. 공백 없이 그대로 붙여넣어 주세요")
            return
        }
        tokenState = .checking
        Task {
            let result: TokenState
            do {
                let me = try await EtlClient(token).getObject("/api/v1/users/self")
                let name = OnboardingLogic.displayName(me.str("name") ?? me.str("short_name"))
                do {
                    try tokens.save(token)
                    result = .saved(name: name)
                } catch let e as EtlException {
                    result = .error(e.message)
                }
            } catch is EtlAuthException {
                result = .error("eTL이 이 토큰을 받아주지 않았어요. 다시 만들어서 붙여넣어 주세요")
            } catch is EtlException {
                result = .error("eTL에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요")
            } catch {
                result = .error("확인하지 못했어요(\(type(of: error)))")
            }
            tokenState = result
            if case .saved = result { tokenInput = "" }
        }
    }

    func replaceToken() {
        tokenState = .idle
        tokenInput = ""
    }

    func keepExistingToken() {
        if hasStoredToken {
            tokenState = .saved(name: nil)
            tokenInput = ""
        }
    }

    var tokenErrorMessage: String? {
        if case .error(let m) = tokenState { return m }
        return nil
    }

    // MARK: 알림

    func refreshNotifyStatus() async {
        notifyStatus = await LocalNotifications.shared.authorizationStatus()
    }

    var notifyGranted: Bool {
        notifyStatus == .authorized || notifyStatus == .provisional || notifyStatus == .ephemeral
    }

    /// "알림 허용하기": 처음이면 시스템 권한 창, 거절한 뒤면 설정 앱의 알림 화면.
    func requestNotifications(openURL: OpenURLAction) async {
        if notifyStatus == .notDetermined {
            _ = await LocalNotifications.shared.requestAuthorization()
            await refreshNotifyStatus()
        } else if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            openURL(url)
        }
    }

    /// 알림 선택(방해 금지 포함)을 저장한다. 다른 알림 설정은 기존 값을 유지한다(Kotlin `applyNotificationChoices`).
    func applyNotificationChoices() {
        let s = notifyStore
        s.set(deadlineAlerts, for: "deadlineAlerts")
        s.set(announcementAlerts, for: "announcementAlerts")
        s.set(briefEnabled, for: "briefEnabled")
        s.set("[\(briefMinutes)]", for: "briefMinutesOfDay")
        s.set(quietEnabled, for: "quietEnabled")
        s.set(quietStart, for: "quietStartMinutes")
        s.set(quietEnd, for: "quietEndMinutes")
        // 다음 확인을 예약한다(첫 확인은 NotifyKit이 연결되면 앱을 열 때 돈다).
        if deadlineAlerts || announcementAlerts { BackgroundRefresh.schedule(after: 15 * 60) }
    }

    /// "{창 요약} (설정에서 바꿀 수 있어요)" — 기본이면 "3일, 24시간, 3시간, 10분 전에 알려줘요 (설정에서 바꿀 수 있어요)".
    var deadlineSummary: String {
        deadlineWindows.map(NotifyPrefs.windowLabel).joined(separator: ", ") + " 전에 알려줘요 (설정에서 바꿀 수 있어요)"
    }
}

/// `notify_settings` 저장 형식 도우미(Kotlin `NotifySettingsStore`의 키와 같다). NotifyKit 저장소가 생기면 그것으로 바꾼다.
enum NotifyPrefs {
    static let defaultWindows = [3 * 24 * 60, 24 * 60, 3 * 60, 10]

    static func ints(_ s: KeyValueStore, _ key: String) -> [Int] {
        guard let raw = s.string(key), let data = raw.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return [] }
        return arr.compactMap { ($0 as? NSNumber)?.intValue }
    }

    static func deadlineWindows(_ s: KeyValueStore) -> [Int] {
        let w = ints(s, "deadlineWindowsMinutes").filter { (10...14 * 24 * 60).contains($0) }
        return w.isEmpty ? defaultWindows : Array(Set(w)).sorted(by: >)
    }

    /// Kotlin `NotifySettings.windowLabel`.
    static func windowLabel(_ minutes: Int) -> String {
        if minutes < 60 { return "\(minutes)분" }
        if minutes % 60 != 0 { return "\(minutes / 60)시간 \(minutes % 60)분" }
        if minutes >= 2 * 24 * 60 && minutes % (24 * 60) == 0 { return "\(minutes / (24 * 60))일" }
        return "\(minutes / 60)시간"
    }

    /// 켠 알림이 쓰는 채널(배너 판정용): 마감 → deadline, 공지 → announcement_v2, 브리핑 → brief.
    static func usedChannels(_ s: KeyValueStore) -> [String] {
        var out: [String] = []
        if s.bool("deadlineAlerts", default: true) { out.append("deadline") }
        if s.bool("announcementAlerts", default: true) { out.append("announcement_v2") }
        if s.bool("briefEnabled", default: false) { out.append("brief") }
        return out
    }

    /// "오전 7:00" / "오후 8:00"(12시간제, Kotlin `NotifyUiFormat.time`의 12시간 분기).
    static func timeLabel(_ minutes: Int) -> String {
        let h = (minutes / 60) % 24, m = minutes % 60
        let ampm = h < 12 ? "오전" : "오후"
        let h12 = h % 12 == 0 ? 12 : h % 12
        return String(format: "%@ %d:%02d", ampm, h12, m)
    }
}
