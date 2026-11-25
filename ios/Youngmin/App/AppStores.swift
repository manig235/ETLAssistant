import ChatKit
import ETLKit
import UIKit

// ETLKit 저장소를 ChatKit 저장소 계약에 붙인다(요구 사항이 같다, ChatKit-API.md).
extension UserDefaultsStore: @retroactive ChatKeyValueStore {}
extension KeychainSecretStore: @retroactive ChatSecretStore {}

/// 앱 전체에서 같이 쓰는 저장소(안드로이드 SharedPreferences 이름 = namespace).
enum AppStores {
    static let keychain = KeychainSecretStore()
    /// Kotlin `AppSettings`(prefs `app_settings`).
    static let appSettings = UserDefaultsStore(namespace: ChatSettings.NAMESPACE)
    /// Kotlin `OnboardingPrefs`(prefs `onboarding`, 키 `completed`).
    static let onboarding = UserDefaultsStore(namespace: "onboarding")
    /// Kotlin `NotifySettingsStore`(prefs `notify_settings`). NotifyKit의 저장소가 생기면 같은 키를 읽는다.
    static let notifySettings = UserDefaultsStore(namespace: "notify_settings")
    /// Kotlin ChatActivity `uiPrefs()`(prefs `ui`): 알림 배너를 닫은 시각 등.
    static let ui = UserDefaultsStore(namespace: "ui")
    static let tokens = TokenStore(store: keychain)
}

/// 숨긴 내비게이션 바(원본 모양 상단바)에서도 왼쪽 가장자리 밀어서 뒤로 가기가 되게 한다.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
