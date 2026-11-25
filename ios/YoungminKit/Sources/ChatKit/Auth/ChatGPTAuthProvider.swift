import Foundation

/// "ChatGPT로 계속하기" 로그인 오류.
public enum SIWCError: Error, Sendable, Hashable {
    /// 사용자가 동의하지 않음.
    case denied
    /// 인가 서버가 오류를 돌려줌.
    case authorization(String)
    /// 콜백이 올바르지 않음(state 불일치 등).
    case invalidCallback
    /// 새 등록인데 발급 client_id가 없음, 또는 다시 로그인했는데 다른 client_id가 옴.
    case registrationIncomplete
    case clientMismatch
    /// 토큰 교환 실패(`invalid_grant`면 코드를 버리고 처음부터 다시).
    case tokenExchange(status: Int, code: String?)
    /// ID 토큰 검증 실패.
    case idToken(JWT.Failure)
    /// 저장된 계정과 다른 계정으로 로그인함.
    case accountMismatch
    case network

    public var userMessage: String {
        switch self {
        case .denied: return "로그인을 취소했어요."
        case .authorization: return "ChatGPT 로그인 중 오류가 났어요. 다시 시도해 주세요."
        case .invalidCallback: return "로그인 요청을 확인하지 못했어요. 다시 시도해 주세요."
        case .registrationIncomplete: return "ChatGPT 앱 등록을 마치지 못했어요. 다시 시도해 주세요."
        case .clientMismatch: return "로그인 응답이 이 앱의 등록과 맞지 않아요. 다시 시도해 주세요."
        case .tokenExchange(let status, _): return "ChatGPT 로그인 서버 요청에 실패했어요(HTTP \(status))"
        case .idToken: return "ChatGPT 로그인 응답을 확인하지 못했어요. 다시 로그인해 주세요."
        case .accountMismatch: return "저장된 계정과 다른 ChatGPT 계정이에요. 다른 계정을 쓰려면 먼저 로그아웃해 주세요."
        case .network: return "ChatGPT 로그인 서버에 연결하지 못했어요. 다시 시도해 주세요."
        }
    }
}

/// 로그아웃 결과. `revoked`가 false면 "철회를 확인하지 못했어요. ChatGPT 설정에서 연결을 끊을 수 있어요"라고 알린다.
public struct SIWCSignOutResult: Sendable, Hashable {
    public var revoked: Bool
}

/// ChatGPT 로그인(SIWC) 토큰 관리자(새로 만듦, Kotlin `ChatGptAuthManager`·`ChatGptOAuth`의 iOS 판).
///
/// 흐름: `makeAttempt(port:)` → 앱이 `LoopbackCallbackServer`를 띄우고 `attempt.authorizeURL`을 브라우저로 연다 →
/// 콜백 대상을 받으면 `complete(attempt:callbackTarget:)` → 저장. 추론할 때는 `bearerToken(forceRefresh:)`가 만료 전에 새로 고친다.
/// 새로 고침은 액터 안에서 한 번에 하나만 돈다(회전하는 새로 고침 토큰을 두 번 쓰지 않게).
public actor ChatGPTAuthProvider: AuthProvider {
    public static let CREDENTIALS_KEY = "chatgpt_credentials"
    static let CLIENT_ID_KEY = "siwc.clientId"
    static let SUBJECT_KEY = "siwc.subject"
    static let EMAIL_KEY = "siwc.email"

    private let secrets: ChatSecretStore
    private let settings: ChatKeyValueStore
    private let transport: HTTPTransport
    private let now: @Sendable () -> Date
    private var refreshTask: Task<ChatGPTCredentials, Error>?
    private var discovery: ChatJSON?

    public init(secrets: ChatSecretStore, settings: ChatKeyValueStore, transport: HTTPTransport = URLSessionTransport(),
                now: @escaping @Sendable () -> Date = { Date() }) {
        self.secrets = secrets
        self.settings = settings
        self.transport = transport
        self.now = now
    }

    public nonisolated var kind: AuthKind { .chatGPT }

    // MARK: 상태

    public func credentials() -> ChatGPTCredentials? {
        secrets.secret(Self.CREDENTIALS_KEY).flatMap(ChatGPTCredentials.parse)
    }

    public func accountInfo() -> AccountInfo {
        credentials()?.accountInfo ?? AccountInfo(.none)
    }

    /// 이 설치에 저장된 발급 client_id(로그아웃해도 남긴다).
    public func issuedClientId() -> String? { settings.string(Self.CLIENT_ID_KEY) }

    /// 다른 계정으로 새로 등록하고 싶을 때: 저장된 등록(client_id·계정)을 잊는다. 호스트 ID는 그대로 둔다.
    public func forgetRegistration() {
        settings.remove(Self.CLIENT_ID_KEY)
        settings.remove(Self.SUBJECT_KEY)
        settings.remove(Self.EMAIL_KEY)
        try? secrets.setSecret(nil, for: Self.CREDENTIALS_KEY)
    }

    // MARK: 로그인

    /// 로그인 시도를 만든다(시도마다 state·nonce·verifier가 새로 생긴다). 루프백 서버를 먼저 띄우고 그 포트를 넘긴다.
    public func makeAttempt(port: UInt16) -> SIWCAttempt {
        let host = SIWCHostID.loadOrCreate(settings)
        let saved = issuedClientId()
        return SIWCAttempt(clientId: saved ?? SIWC.dynamicClientId, extAgentHostId: host, redirectURI: SIWC.redirectURI(port: port),
                           idTokenHint: saved == nil ? nil : credentials()?.idToken, loginHint: settings.string(Self.EMAIL_KEY))
    }

    /// 루프백 서버가 받은 요청 대상(`/auth/callback?…`)으로 로그인을 마친다.
    @discardableResult
    public func complete(attempt: SIWCAttempt, callbackTarget: String) async throws -> ChatGPTCredentials {
        guard let callback = SIWCCallback.parse(target: callbackTarget, expectedState: attempt.state) else { throw SIWCError.invalidCallback }
        let code: String
        let callbackClient: String?
        let callbackScope: String?
        switch callback {
        case .denied: throw SIWCError.denied
        case .error(let e): throw SIWCError.authorization(e)
        case .code(let c, let client, let scope): code = c; callbackClient = client; callbackScope = scope
        }
        let clientId: String
        if attempt.isRegistration {
            guard let issued = callbackClient, issued != SIWC.dynamicClientId, !issued.isEmpty else { throw SIWCError.registrationIncomplete }
            clientId = issued
        } else {
            if let c = callbackClient, c != attempt.clientId { throw SIWCError.clientMismatch }
            clientId = attempt.clientId
        }

        var request = URLRequest(url: SIWC.tokenURL)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Data(attempt.tokenRequestBody(code: code, clientId: clientId).utf8)
        request.timeoutInterval = 20
        let (data, response) = try await send(request)
        guard (200..<300).contains(response.statusCode) else {
            throw SIWCError.tokenExchange(status: response.statusCode, code: SIWCTokenResponse.errorCode(data))
        }
        let tokens: SIWCTokenResponse
        do { tokens = try SIWCTokenResponse.parse(data) } catch { throw SIWCError.idToken(.malformed) }
        guard let idToken = tokens.idToken else { throw SIWCError.idToken(.malformed) }
        let jwt = try await validateIDToken(idToken, clientId: clientId, nonce: attempt.nonce)
        let subject = jwt.subject ?? ""
        if !attempt.isRegistration, let saved = settings.string(Self.SUBJECT_KEY), saved != subject { throw SIWCError.accountMismatch }

        let creds = ChatGPTCredentials(
            email: jwt.email, subject: subject, clientId: clientId, extAgentHostId: attempt.extAgentHostId, idToken: idToken,
            accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, tokenType: tokens.tokenType, expiresIn: tokens.expiresIn,
            scopes: SIWC.splitScope(tokens.scope ?? callbackScope), savedAt: now(), earliestRefreshAt: tokens.earliestRefreshAt)
        try? secrets.setSecret(creds.json(), for: Self.CREDENTIALS_KEY)
        settings.set(clientId, for: Self.CLIENT_ID_KEY)
        settings.set(subject, for: Self.SUBJECT_KEY)
        if let e = jwt.email { settings.set(e, for: Self.EMAIL_KEY) }
        return creds
    }

    // MARK: 토큰

    public func bearerToken(forceRefresh: Bool) async throws -> String {
        guard var creds = credentials() else { throw AuthError.notSignedIn }
        guard creds.canUsePlan else { throw AuthError.planUsageNotGranted }
        if forceRefresh || creds.needsRefresh(now: now()) {
            do {
                creds = try await refresh()
            } catch AuthError.temporarilyUnavailable where !creds.isExpired(now: now()) && !forceRefresh {
                return creds.accessToken
            }
        }
        guard creds.canUsePlan else { throw AuthError.planUsageNotGranted }
        return creds.accessToken
    }

    /// 새로 고침(동시에 여러 번 불러도 한 번만 보낸다).
    @discardableResult
    public func refresh() async throws -> ChatGPTCredentials {
        if let t = refreshTask { return try await t.value }
        let task = Task { try await self.performRefresh() }
        refreshTask = task
        defer { refreshTask = nil }
        return try await task.value
    }

    private func performRefresh() async throws -> ChatGPTCredentials {
        guard let creds = credentials() else { throw AuthError.notSignedIn }
        guard let rt = creds.refreshToken else { throw AuthError.reauthRequired }
        var request = URLRequest(url: SIWC.tokenURL)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Data(FormEncoding.form([
            ("grant_type", "refresh_token"), ("client_id", creds.clientId), ("refresh_token", rt), ("resource", SIWC.resource),
        ]).utf8)
        request.timeoutInterval = 20
        let data: Data
        let response: HTTPURLResponse
        do { (data, response) = try await transport.data(for: request) } catch { throw AuthError.temporarilyUnavailable }
        guard (200..<300).contains(response.statusCode) else {
            let code = SIWCTokenResponse.errorCode(data)
            if let code, SIWC.terminalRefreshErrors.contains(code) {
                clearTokens()
                throw AuthError.reauthRequired
            }
            if response.statusCode == 400 || response.statusCode == 401 { if code == nil { clearTokens(); throw AuthError.reauthRequired } }
            throw AuthError.temporarilyUnavailable
        }
        guard let tokens = try? SIWCTokenResponse.parse(data) else { throw AuthError.temporarilyUnavailable }
        if let id = tokens.idToken {
            // 새 ID 토큰이 오면 같은 계정인지 확인한다(새로 고침 응답에는 nonce가 없을 수 있어 nonce는 보지 않는다).
            guard let jwt = try? await validateIDToken(id, clientId: creds.clientId, nonce: nil), jwt.subject == creds.subject else {
                throw AuthError.reauthRequired
            }
        }
        let updated = creds.refreshed(with: tokens, now: now())
        try? secrets.setSecret(updated.json(), for: Self.CREDENTIALS_KEY)
        return updated
    }

    // MARK: 로그아웃

    /// 새로 고침 토큰을 철회하고 토큰을 지운다. 등록(client_id)과 호스트 ID는 남긴다.
    public func signOut() async -> SIWCSignOutResult {
        guard let creds = credentials() else { return SIWCSignOutResult(revoked: true) }
        var revoked = false
        if let rt = creds.refreshToken, let endpoint = try? await discover()["revocation_endpoint"]?.string.flatMap(URL.init(string:)),
           endpoint.scheme == "https", endpoint.host == URL(string: SIWC.issuer)?.host {
            for attempt in 0..<2 {
                var request = URLRequest(url: endpoint)
                request.httpMethod = "POST"
                request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
                request.httpBody = Data(FormEncoding.form([("token", rt), ("token_type_hint", "refresh_token"), ("client_id", creds.clientId)]).utf8)
                request.timeoutInterval = 10
                if let (_, response) = try? await transport.data(for: request) {
                    if response.statusCode == 200 { revoked = true; break }
                    if response.statusCode < 500 { break }
                }
                if attempt == 0 { try? await Task.sleep(nanoseconds: 500_000_000) }
            }
        }
        clearTokens()
        return SIWCSignOutResult(revoked: revoked)
    }

    private func clearTokens() {
        try? secrets.setSecret(nil, for: Self.CREDENTIALS_KEY)
    }

    // MARK: ID 토큰

    /// OpenID 설정(`jwks_uri`, `revocation_endpoint`). 한 번 받으면 기억한다.
    private func discover() async throws -> ChatJSON {
        if let d = discovery { return d }
        var r = URLRequest(url: SIWC.openIDConfigurationURL)
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.timeoutInterval = 15
        let (data, response) = try await send(r)
        guard response.statusCode == 200, let json = try? ChatJSON(parsing: data), json.object != nil else { throw SIWCError.network }
        discovery = json
        return json
    }

    private func validateIDToken(_ token: String, clientId: String, nonce: String?) async throws -> JWT {
        let jwt: JWT
        do {
            jwt = try JWT.decode(token)
            try jwt.validateClaims(issuer: SIWC.issuer, audience: clientId, nonce: nonce, now: now())
        } catch let f as JWT.Failure { throw SIWCError.idToken(f) }
        guard let jwksURL = try await discover()["jwks_uri"]?.string.flatMap(URL.init(string:)), jwksURL.scheme == "https",
              let host = jwksURL.host, host == "openai.com" || host.hasSuffix(".openai.com") else {
            throw SIWCError.network
        }
        var r = URLRequest(url: jwksURL)
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.timeoutInterval = 15
        let (data, response) = try await send(r)
        guard response.statusCode == 200, let jwks = try? ChatJSON(parsing: data) else { throw SIWCError.network }
        do { try jwt.verifySignature(jwks: jwks) } catch let f as JWT.Failure { throw SIWCError.idToken(f) }
        return jwt
    }

    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do { return try await transport.data(for: request) } catch { throw SIWCError.network }
    }
}
