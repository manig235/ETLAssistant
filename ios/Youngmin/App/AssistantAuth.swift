import ChatKit
import Foundation

/// AI 비서 인증 상태(Kotlin `CodexSession`의 계정·로그인 부분을 iOS 방식으로).
/// 기본은 "ChatGPT로 계속하기"(SIWC, 루프백 수신기 + 앱 안 브라우저 시트), 예비는 OpenAI API 키.
@MainActor
@Observable
final class AssistantAuth {
    let settings: ChatSettings
    let chatGPT: ChatGPTAuthProvider
    let apiKey: APIKeyAuthProvider

    /// 지금 계정(로그인 안 됐으면 `.none`).
    private(set) var account = AccountInfo(.none)
    /// 브라우저 로그인 진행 중(Android `loginPrompt != nil`).
    private(set) var loginInProgress = false
    /// 로그인 실패 문구(Android `loginMessage`). 성공하면 nil.
    var loginMessage: String?

    @ObservationIgnored private let presenter = WebAuthPresenter()
    @ObservationIgnored private var loginTask: Task<Void, Never>?
    @ObservationIgnored private var server: LoopbackCallbackServer?
    @ObservationIgnored private var attemptURL: URL?
    /// 지금 진행 중인 로그인 시도. 이전 시트가 늦게 닫혔다는 알림이 새 시도를 취소하지 않게 한다.
    @ObservationIgnored private var currentAttempt = UUID()
    /// 로그인을 마친 뒤 알려 줄 곳(채팅 화면 토스트 "로그인했어요").
    @ObservationIgnored var onLoginDone: ((Bool) -> Void)?

    init(secrets: ChatSecretStore = AppStores.keychain, store: ChatKeyValueStore = AppStores.appSettings) {
        settings = ChatSettings(store)
        chatGPT = ChatGPTAuthProvider(secrets: secrets, settings: store)
        apiKey = APIKeyAuthProvider(secrets: secrets)
        if settings.authMode == ChatSettings.AUTH_API_KEY, apiKey.hasKey { account = AccountInfo(.apiKey) }
        Task { await refreshAccount() }
    }

    /// 지금 인증 방식의 토큰 공급자.
    var provider: AuthProvider {
        settings.authMode == ChatSettings.AUTH_API_KEY ? apiKey : chatGPT
    }

    var isSignedIn: Bool { account.loggedIn }

    /// "로그인됐어요 · {누구}"의 누구.
    var accountLabel: String {
        switch account.kind {
        case .chatgpt: return account.email ?? "ChatGPT"
        case .apiKey: return "API 키"
        default: return "계정"
        }
    }

    func refreshAccount() async {
        if settings.authMode == ChatSettings.AUTH_API_KEY {
            account = apiKey.hasKey ? AccountInfo(.apiKey) : AccountInfo(.none)
        } else {
            account = await chatGPT.accountInfo()
        }
    }

    // MARK: ChatGPT로 계속하기

    /// 브라우저 로그인을 시작한다. `switchAccount`면 저장된 등록을 잊고 새로 고른다(Android "다른 계정으로 로그인").
    func startChatGPTLogin(switchAccount: Bool = false) {
        cancelLogin(silently: true)
        loginMessage = nil
        loginInProgress = true
        loginTask = Task { [weak self] in
            guard let self else { return }
            if switchAccount { await self.chatGPT.forgetRegistration() }
            await self.runLogin()
        }
    }

    private func runLogin() async {
        var finished = false
        let attemptId = UUID()
        currentAttempt = attemptId
        do {
            let (server, port) = try await LoopbackCallbackServer.startPreferring()
            self.server = server
            DiagLog.logger.notice("login: loopback listening on port \(port)")
            let attempt = await chatGPT.makeAttempt(port: port)
            DiagLog.logger.notice("login: attempt registration=\(attempt.isRegistration) loginHint=\(attempt.loginHint != nil)")
            attemptURL = attempt.authorizeURL
            presenter.start(url: attempt.authorizeURL) { [weak self] in
                // 사용자가 시트를 닫았다(콜백을 받은 뒤 우리가 닫은 것은 무시).
                guard let self, !finished, self.loginInProgress, self.currentAttempt == attemptId else { return }
                DiagLog.logger.notice("login: browser sheet closed by user")
                self.cancelLogin()
            }
            let target = try await server.waitForCallback(decide: LoopbackCallbackServer.siwcDecision(expectedState: attempt.state))
            finished = true
            DiagLog.logger.notice("login: callback received")
            presenter.finish()
            let creds = try await chatGPT.complete(attempt: attempt, callbackTarget: target)
            server.stop()
            self.server = nil
            settings.authMode = ChatSettings.AUTH_CHATGPT
            await refreshAccount()
            loginInProgress = false
            if !creds.canUsePlan {
                loginMessage = AuthError.planUsageNotGranted.userMessage
                onLoginDone?(false)
            } else {
                onLoginDone?(true)
            }
        } catch {
            finished = true
            DiagLog.logger.notice("login: failed \(String(describing: error), privacy: .public)")
            guard currentAttempt == attemptId else { return }
            // 저장된 등록으로 다른 계정에 로그인하면 코드 교환이 invalid_grant로 거절된다. 등록을 잊어 다음 시도는 새 등록으로.
            if case SIWCError.tokenExchange(_, let code)? = error as? SIWCError, code == "invalid_grant" {
                await chatGPT.forgetRegistration()
            }
            if case SIWCError.accountMismatch? = error as? SIWCError {
                await chatGPT.forgetRegistration()
            }
            presenter.finish()
            server?.stop()
            server = nil
            guard !Task.isCancelled, loginInProgress else { return }
            loginInProgress = false
            loginMessage = Self.message(error)
            onLoginDone?(false)
        }
    }

    /// "브라우저 다시 열기": 같은 시도를 새로 시작한다(state가 바뀌므로 처음부터).
    func reopenBrowser() {
        startChatGPTLogin()
    }

    /// "취소".
    func cancelLogin(silently: Bool = false) {
        loginTask?.cancel()
        loginTask = nil
        server?.stop()
        server = nil
        presenter.finish()
        loginInProgress = false
        if !silently { loginMessage = nil }
    }

    // MARK: API 키

    /// 키로 모델 목록을 한 번 불러와 확인한 뒤 Keychain에 저장한다. 실패하면 이유 문구.
    func connectAPIKey(_ raw: String) async -> String? {
        let key = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, key.allSatisfy({ $0.isASCII && !$0.isWhitespace }) else {
            return "키 모양이 아니에요. 공백 없이 그대로 붙여넣어 주세요"
        }
        let probe = APIKeyAuthProvider(secrets: InMemoryChatSecretStore())
        try? probe.save(key)
        do {
            _ = try await ModelListClient.fetch(auth: probe, transport: URLSessionTransport())
        } catch let e as ResponsesError {
            if e.status == 401 { return "OpenAI가 이 키를 받아주지 않았어요. 키를 확인해 주세요" }
            return e.userMessage
        } catch {
            return "OpenAI에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요"
        }
        do {
            try apiKey.save(key)
        } catch {
            return "암호화한 로그인 정보를 저장하지 못했어요"
        }
        cancelLogin(silently: true)
        settings.authMode = ChatSettings.AUTH_API_KEY
        loginMessage = nil
        await refreshAccount()
        onLoginDone?(true)
        return nil
    }

    // MARK: 로그아웃

    func signOut() async -> SIWCSignOutResult? {
        if settings.authMode == ChatSettings.AUTH_API_KEY {
            try? apiKey.save(nil)
            settings.authMode = ChatSettings.AUTH_CHATGPT
            await refreshAccount()
            return nil
        }
        let r = await chatGPT.signOut()
        // 발급 client_id는 그 계정으로 등록한 것이라 다른 계정으로 다시 로그인하면 invalid_grant가 난다.
        // 로그아웃하면 등록도 잊어 다음 로그인을 새 등록으로 시작한다.
        await chatGPT.forgetRegistration()
        await refreshAccount()
        return r
    }

    static func message(_ error: Error) -> String {
        if let e = error as? SIWCError { return e.userMessage }
        if let e = error as? AuthError { return e.userMessage }
        if let e = error as? ResponsesError { return e.userMessage }
        if error is LoopbackError { return "로그인하지 못했어요" }
        return "로그인하지 못했어요"
    }
}
