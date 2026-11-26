import AssistantKit
import ChatKit
import ETLKit
import NotifyKit
import SwiftUI
import UIKit
import UserNotifications

/// 설정 화면이 다른 모듈에 알리는 곳(Android `NotifyScheduler.apply`·`CodexSession.restart`·워커 1회 실행 자리).
/// 통합 담당이 앱 시작 때 채운다(INTEGRATION.md). 비어 있으면 아무 일도 하지 않고, "지금 확인" 버튼은 숨긴다.
@MainActor
enum SettingsHooks {
    /// 알림 설정을 저장한 직후(예약 다시 맞추기).
    static var notifySettingsChanged: (() -> Void)?
    /// eTL 토큰을 저장하거나 해제한 직후(조회·예약·위젯 갱신). `connected == false`면 해제.
    static var etlTokenChanged: ((_ connected: Bool) -> Void)?
    /// "지금 확인"(Android 워커 1회 실행 → iOS는 앱 안에서 바로 실행).
    static var checkNow: (() async -> Void)?
    /// "브리핑 미리 보내기".
    static var briefNow: (() async -> Void)?
}

/// 설정 화면 상태(Kotlin `SettingsActivity`의 `SettingsState` + 동작). 저장은 모듈 저장소(Android와 같은 키)로 한다.
@MainActor
@Observable
final class SettingsModel {
    let auth: AssistantAuth
    /// eTL 토큰 확인·저장은 첫 실행 안내와 같은 흐름(`GET /api/v1/users/self`로 확인 후 Keychain 저장)을 쓴다.
    let tokenFlow = OnboardingModel()

    @ObservationIgnored private let notifyStore = NotifySettingsStore(AppStores.notifySettings)
    @ObservationIgnored private let courseFilters = CourseFilterStore()
    @ObservationIgnored private let notifyState = NotifyStateStore()

    private(set) var notify: NotifySettings
    private(set) var hasEtlToken = false
    private(set) var notifyStatus: UNAuthorizationStatus = .authorized
    private(set) var hasApiKey = false
    private(set) var apiKeyMasked: String?
    private(set) var useApiKey = false
    private(set) var casualTone = false
    /// 공개 기출 자료 찾기(MIT OCW 등, 기본 켬).
    private(set) var studyLookup = true
    private(set) var courseAlertsSummary = ""
    private(set) var lastCheckText = ""
    private(set) var lastBriefText = ""
    /// 계정 정보를 한 번 읽었는가(읽기 전에는 "확인하는 중이에요…").
    private(set) var accountChecked = false
    /// 마지막 남은 시점을 ×했을 때 빨간 안내.
    var windowError: String?

    @ObservationIgnored private var statusRefresh: Task<Void, Never>?

    init(auth: AssistantAuth) {
        self.auth = auth
        notify = notifyStore.load()
        refreshSecrets()
        refreshLocal()
    }

    // MARK: 읽기

    var accountStatus: AccountStatus {
        SettingsLogic.accountStatus(.ready, nil, accountChecked ? auth.account : nil)
    }

    var notifyGranted: Bool { notifyStatus == .authorized || notifyStatus == .provisional || notifyStatus == .ephemeral }

    var etlChecking: Bool { tokenFlow.tokenState == .checking }

    /// 화면을 열 때·앱으로 돌아올 때.
    func refreshAll() async {
        refreshSecrets()
        refreshLocal()
        await refreshNotifyPermission()
        await auth.refreshAccount()
        accountChecked = true
    }

    /// 저장소에 있는 값(키체인 제외)을 다시 읽는다.
    func refreshLocal() {
        notify = notifyStore.load()
        courseAlertsSummary = CourseFilterLogic.summary(courseFilters.load())
        refreshNotifyStatusLines()
    }

    private func refreshSecrets() {
        let raw = AppStores.keychain.secret(APIKeyAuthProvider.SECRET_KEY)?.trimmingCharacters(in: .whitespacesAndNewlines)
        hasApiKey = !(raw ?? "").isEmpty
        apiKeyMasked = hasApiKey ? raw.map(SettingsLogic.maskKey) : nil
        useApiKey = auth.settings.authMode == ChatSettings.AUTH_API_KEY
        casualTone = auth.settings.casualTone
        studyLookup = StudySettings().enabled
        hasEtlToken = AppStores.tokens.hasToken()
    }

    private func refreshNotifyStatusLines() {
        let st = notifyState.load()
        lastCheckText = NotifyUiFormat.describeLastCheck(st.lastCheckAt, st.lastResult)
        lastBriefText = NotifyUiFormat.describeLastBrief(st.lastBriefAt)
    }

    func refreshNotifyPermission() async {
        notifyStatus = await LocalNotifications.shared.authorizationStatus()
    }

    // MARK: ChatGPT 계정

    func loginBrowser() {
        auth.startChatGPTLogin(switchAccount: auth.account.loggedIn)
    }

    func logout() {
        Task {
            let r = await auth.signOut()
            refreshSecrets()
            if r?.revoked == false {
                ToastCenter.shared.show("철회를 확인하지 못했어요. ChatGPT 설정에서 연결을 끊을 수 있어요", long: true)
            }
        }
    }

    /// 로그인 진행이 끝났을 때(Android `LoginDone` 이벤트). 성공 토스트는 채팅 상태가 띄운다(`ChatController`).
    func loginFinished(app: AppState) {
        refreshSecrets()
        if let msg = auth.loginMessage {
            ToastCenter.shared.show("로그인하지 못했어요: \(msg)", long: true)
        } else if auth.account.loggedIn, app.chat == nil {
            ToastCenter.shared.show("로그인했어요")
        }
    }

    // MARK: eTL 토큰

    /// "저장": 형식 검사 → eTL에 확인 → Keychain 저장. 결과는 `etlTokenStateChanged`로 온다.
    func saveEtlToken() {
        let token = tokenFlow.tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard SettingsLogic.isPlausibleEtlToken(token) else {
            ToastCenter.shared.show("토큰 형식이 올바르지 않아요(공백 없는 ASCII 문자)", long: true)
            return
        }
        tokenFlow.tokenInput = token
        tokenFlow.checkAndSaveToken()
    }

    func etlTokenStateChanged(_ state: TokenState, app: AppState) {
        switch state {
        case .saved:
            ToastCenter.shared.show("eTL 토큰을 저장했어요")
            afterTokenChange(app: app, connected: true)
        case .error(let message):
            ToastCenter.shared.show(message, long: true)
        case .idle, .checking:
            break
        }
    }

    /// "eTL 연결 해제" 확인 뒤: 토큰과 eTL에서 받아 둔 캐시를 지운다(남기는 것은 `EtlDisconnect`).
    func disconnectEtl(app: AppState) {
        do {
            try EtlDisconnect.run(tokens: AppStores.tokens, stores: EtlDisconnect.Stores(extraCleanup: [{ BoardSession.clear() }]))
        } catch let e as EtlException {
            ToastCenter.shared.show(e.message, long: true)
            refreshSecrets()
            return
        } catch {
            ToastCenter.shared.show("저장하지 못했어요(\(type(of: error)))", long: true)
            refreshSecrets()
            return
        }
        tokenFlow.replaceToken()
        afterTokenChange(app: app, connected: false)
        ToastCenter.shared.show("eTL 연결을 해제했어요")
    }

    private func afterTokenChange(app: AppState, connected: Bool) {
        refreshSecrets()
        refreshLocal()
        app.refreshTokenState()
        SettingsHooks.etlTokenChanged?(connected)
        if let chat = app.chat { Task { await chat.refreshEnvironment() } }
    }

    // MARK: 알림

    /// 바꾸고 즉시 저장한 뒤 예약을 다시 맞춘다(Android `updateNotify`).
    func updateNotify(_ change: (inout NotifySettings) -> Void) {
        var s = notifyStore.load()
        change(&s)
        notifyStore.save(s)
        notify = notifyStore.load()
        if notify.needsPeriodicCheck { BackgroundRefresh.schedule(after: 15 * 60) }
        SettingsHooks.notifySettingsChanged?()
    }

    func removeWindow(_ minutes: Int) {
        let windows = NotifySettings.normalizeWindows(notify.deadlineWindowsMinutes)
        if let next = SettingsLogic.removeWindow(windows, minutes) {
            windowError = nil
            updateNotify { $0.deadlineWindowsMinutes = next }
        } else {
            windowError = SettingsLogic.LAST_WINDOW_MESSAGE
        }
    }

    func addWindow(_ minutes: Int) {
        updateNotify { $0.deadlineWindowsMinutes = SettingsLogic.addWindow($0.deadlineWindowsMinutes, minutes) }
    }

    func resetWindows() {
        windowError = nil
        updateNotify { $0.deadlineWindowsMinutes = SettingsLogic.defaultWindows() }
    }

    /// "권한 요청": 처음이면 시스템 권한 창, 거절한 뒤면 설정 앱의 이 앱 알림 화면.
    func requestNotifyPermission(openURL: OpenURLAction) async {
        if notifyStatus == .notDetermined {
            _ = await LocalNotifications.shared.requestAuthorization()
            await refreshNotifyPermission()
            if notifyGranted { SettingsHooks.notifySettingsChanged?() }
        } else if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            openURL(url) { accepted in
                if !accepted { ToastCenter.shared.show("알림 설정을 열지 못했어요. 시스템 설정에서 직접 허용해 주세요.", long: true) }
            }
        }
    }

    /// 지금 확인/브리핑 미리 보내기. 권한이 없어도 실행은 하되 먼저 안내한다. 5초·15초 뒤 상태 줄을 다시 읽는다.
    func manualRun(_ run: @escaping () async -> Void) {
        if !notifyGranted {
            ToastCenter.shared.show("알림 권한이 꺼져 있어 알림이 뜨지 않아요. 위에서 권한을 허용하세요.", long: true)
        }
        Task { await run(); refreshNotifyStatusLines() }
        ToastCenter.shared.show("요청했어요. 잠시 뒤 결과가 표시돼요")
        statusRefresh?.cancel()
        statusRefresh = Task { [weak self] in
            for delay: UInt64 in [5, 10] {
                try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
                if Task.isCancelled { return }
                self?.refreshNotifyStatusLines()
            }
        }
    }

    // MARK: 대화

    func setCasualTone(_ on: Bool) {
        auth.settings.casualTone = on
        casualTone = auth.settings.casualTone
    }

    func setStudyLookup(_ on: Bool) {
        let s = StudySettings()
        s.enabled = on
        studyLookup = s.enabled
    }

    // MARK: API 키

    /// @return 저장했으면 true(입력칸을 비운다). 키 값은 메시지에 넣지 않는다.
    func saveApiKey(_ raw: String) async -> Bool {
        let key = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard SettingsLogic.isPlausibleApiKey(key) else {
            ToastCenter.shared.show("API 키 형식이 아니에요(sk-로 시작, 공백 없음)", long: true)
            return false
        }
        var saved = false
        do {
            try auth.apiKey.save(key)
            saved = true
            ToastCenter.shared.show("API 키를 저장했어요")
            if auth.settings.authMode == ChatSettings.AUTH_API_KEY { await auth.refreshAccount() }
        } catch {
            ToastCenter.shared.show("저장하지 못했어요(\(type(of: error)))", long: true)
        }
        refreshSecrets()
        return saved
    }

    func clearApiKey(app: AppState) async {
        try? auth.apiKey.save(nil)
        if auth.settings.authMode == ChatSettings.AUTH_API_KEY {
            auth.settings.authMode = ChatSettings.AUTH_CHATGPT
            await authModeChanged(app: app)
        }
        refreshSecrets()
    }

    func setUseApiKey(_ checked: Bool, app: AppState) async {
        if checked && !auth.apiKey.hasKey {
            ToastCenter.shared.show("먼저 API 키를 저장하세요")
            refreshSecrets() // 스위치는 저장된 상태를 따라가므로 꺼진 채로 남는다
            return
        }
        auth.settings.authMode = checked ? ChatSettings.AUTH_API_KEY : ChatSettings.AUTH_CHATGPT
        refreshSecrets()
        await authModeChanged(app: app)
    }

    /// Android `CodexSession.restart()` 대신: 계정을 다시 읽고 모델 목록을 새 인증으로 다시 받는다.
    private func authModeChanged(app: AppState) async {
        await auth.refreshAccount()
        if let chat = app.chat { await chat.loadModels() }
    }
}
