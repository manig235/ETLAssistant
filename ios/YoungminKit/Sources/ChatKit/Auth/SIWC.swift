import Foundation
import CryptoKit
import Security

/// OpenAI "Sign in with ChatGPT"(오픈소스·로컬 앱 경로, ChatGPT 플랜 사용)의 순수 부분(새로 만듦).
/// 기준 문서: https://developers.openai.com/siwc/token-sharing-open-source/sign-in 과 같은 폴더의 profiles-and-sessions,
/// token-reference, errors-and-recovery, preview-limitations.
public enum SIWC {
    public static let issuer = "https://auth.openai.com"
    public static let authorizeURL = URL(string: "https://auth.openai.com/api/accounts/authorize")!
    public static let tokenURL = URL(string: "https://auth.openai.com/api/accounts/oauth/token")!
    /// 철회 주소·JWKS 주소는 여기서 찾는다(문서: `revocation_endpoint`; JWKS는 표준 `jwks_uri`).
    public static let openIDConfigurationURL = URL(string: "https://auth.openai.com/.well-known/openid-configuration")!
    public static let resource = "https://api.openai.com/v1"
    public static let scopes = ["openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct"]
    public static var scope: String { scopes.joined(separator: " ") }
    /// ChatGPT 플랜으로 추론할 수 있게 하는 권한. 받은 권한에 없으면 추론하지 않는다.
    public static let directUsageScope = "chatgpt.tokens.use.direct"
    /// 처음 등록할 때의 `client_id`. 발급받은 ID로 저장하면 안 된다.
    public static let dynamicClientId = "dynamic_agent_client"
    /// 발급 `client_id`의 앞부분(문서: `oaiapp_`).
    public static let issuedClientIdPrefix = "oaiapp_"
    /// 앱 이름 힌트(설치마다 같게).
    public static let agentNameHint = "영민이"
    public static let callbackPath = "/auth/callback"
    /// 문서 예시의 포트. 쓸 수 없으면 다른 빈 포트를 쓴다(포트만 바뀔 수 있다).
    public static let preferredPort: UInt16 = 1455
    /// 새로 고침 토큰을 쓸 수 없다는 오류 코드(지우고 다시 로그인).
    public static let terminalRefreshErrors: Set<String> = [
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
    ]

    /// 루프백 리다이렉트 주소. 호스트는 항상 `127.0.0.1`(`localhost` 아님).
    public static func redirectURI(port: UInt16) -> String { "http://127.0.0.1:\(port)\(callbackPath)" }

    /// 받은 권한(공백 구분)에 ChatGPT 플랜 사용이 있는가.
    public static func grantsDirectUsage(_ scopes: [String]) -> Bool { scopes.contains(directUsageScope) }

    public static func splitScope(_ scope: String?) -> [String] {
        (scope ?? "").split(whereSeparator: { $0 == " " || $0 == "+" }).map(String.init)
    }
}

/// PKCE·state·nonce 값 만들기.
public enum PKCE {
    /// 안전한 난수 `byteCount`바이트를 base64url(패딩 없음)로. 32바이트면 43자.
    public static func random(byteCount: Int = 32) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        if status != errSecSuccess {
            var g = SystemRandomNumberGenerator()
            for i in bytes.indices { bytes[i] = UInt8.random(in: 0...255, using: &g) }
        }
        return Base64URL.encode(Data(bytes))
    }

    /// S256: base64url(SHA-256(verifier)), 패딩 없음.
    public static func challenge(_ verifier: String) -> String {
        Base64URL.encode(Data(SHA256.hash(data: Data(verifier.utf8))))
    }
}

public enum Base64URL {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
}

/// URL 쿼리·폼 인코딩(RFC 3986 비예약 문자만 그대로, 공백은 %20).
public enum FormEncoding {
    private static let unreserved: CharacterSet = {
        var s = CharacterSet()
        s.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return s
    }()

    public static func encode(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "" }

    public static func form(_ pairs: [(String, String)]) -> String {
        pairs.map { "\(encode($0.0))=\(encode($0.1))" }.joined(separator: "&")
    }

    /// `a=b&c=d`를 (이름, 값) 목록으로. `+`는 공백으로 본다(폼 규칙). 망가진 % 인코딩이면 nil.
    public static func parse(_ query: String) -> [(String, String)]? {
        var out: [(String, String)] = []
        for part in query.split(separator: "&", omittingEmptySubsequences: true) {
            let kv = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard let k = String(kv[0]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding else { return nil }
            let rawValue = kv.count > 1 ? String(kv[1]) : ""
            guard let v = rawValue.replacingOccurrences(of: "+", with: " ").removingPercentEncoding else { return nil }
            out.append((k, v))
        }
        return out
    }
}

/// 로그인 시도 하나. 시도마다 state·nonce·PKCE verifier를 새로 만든다.
public struct SIWCAttempt: Sendable, Hashable {
    /// `dynamic_agent_client`(처음 등록) 또는 발급받은 `oaiapp_…`.
    public let clientId: String
    public let extAgentHostId: String
    public let redirectURI: String
    public let state: String
    public let nonce: String
    public let codeVerifier: String
    public let idTokenHint: String?
    public let loginHint: String?

    public init(clientId: String, extAgentHostId: String, redirectURI: String, idTokenHint: String? = nil, loginHint: String? = nil,
                state: String = PKCE.random(), nonce: String = PKCE.random(), codeVerifier: String = PKCE.random()) {
        self.clientId = clientId
        self.extAgentHostId = extAgentHostId
        self.redirectURI = redirectURI
        self.state = state
        self.nonce = nonce
        self.codeVerifier = codeVerifier
        self.idTokenHint = idTokenHint
        self.loginHint = loginHint
    }

    /// 처음 등록(`dynamic_agent_client`)인가.
    public var isRegistration: Bool { clientId == SIWC.dynamicClientId }

    public var codeChallenge: String { PKCE.challenge(codeVerifier) }

    /// 브라우저(ASWebAuthenticationSession 등)로 열 주소. `id_token_hint`가 들어갈 수 있으므로 기록에 남기지 않는다.
    public var authorizeURL: URL {
        var pairs: [(String, String)] = [("client_id", clientId)]
        if isRegistration { pairs.append(("agent_name_hint", SIWC.agentNameHint)) }
        pairs.append(("ext_agent_host_id", extAgentHostId))
        if !isRegistration, let h = idTokenHint, !h.isEmpty { pairs.append(("id_token_hint", h)) }
        if let l = loginHint, !l.isEmpty { pairs.append(("login_hint", l)) }
        pairs += [
            ("response_type", "code"),
            ("redirect_uri", redirectURI),
            ("scope", SIWC.scope),
            ("resource", SIWC.resource),
            ("state", state),
            ("nonce", nonce),
            ("code_challenge_method", "S256"),
            ("code_challenge", codeChallenge),
        ]
        return URL(string: SIWC.authorizeURL.absoluteString + "?" + FormEncoding.form(pairs))!
    }

    /// 인가 코드 교환 본문(폼). 같은 `redirect_uri`와 `resource`, 클라이언트 비밀 없음.
    public func tokenRequestBody(code: String, clientId issued: String) -> String {
        FormEncoding.form([
            ("grant_type", "authorization_code"),
            ("client_id", issued),
            ("code", code),
            ("code_verifier", codeVerifier),
            ("redirect_uri", redirectURI),
            ("resource", SIWC.resource),
        ])
    }
}

/// 루프백으로 돌아온 콜백 해석 결과.
public enum SIWCCallback: Sendable, Hashable {
    /// 코드 받음. `clientId`는 새 등록이면 발급 ID, 다시 로그인이면 빠질 수 있다.
    case code(code: String, clientId: String?, scope: String?)
    /// 사용자가 거절함(`error=access_denied`).
    case denied
    /// 그 밖의 오류(`error=…`).
    case error(String)

    /// 요청 대상(`/auth/callback?…`)을 해석한다. 경로가 다르거나 state가 맞지 않거나 같은 이름이 두 번 오면 nil
    /// (이상한 로컬 요청이 진짜 로그인을 끝내지 못하게). state는 항상 먼저 확인한다.
    public static func parse(target: String, expectedState: String) -> SIWCCallback? {
        guard target.hasPrefix("/"), !target.hasPrefix("//"), !target.contains("#") else { return nil }
        let pathEnd = target.firstIndex(of: "?") ?? target.endIndex
        guard target[..<pathEnd] == SIWC.callbackPath else { return nil }
        let query = pathEnd < target.endIndex ? String(target[target.index(after: pathEnd)...]) : ""
        guard let pairs = FormEncoding.parse(query) else { return nil }
        var values: [String: [String]] = [:]
        for (k, v) in pairs { values[k, default: []].append(v) }
        func single(_ k: String) -> String?? {
            guard let a = values[k] else { return .some(nil) }
            return a.count == 1 ? .some(a[0]) : .none
        }
        guard case .some(let stateValue?) = single("state"), constantTimeEquals(stateValue, expectedState) else { return nil }
        guard case .some(let error) = single("error"), case .some(let code) = single("code"),
              case .some(let clientId) = single("client_id"), case .some(let scope) = single("scope") else { return nil }
        if let error, !error.isEmpty { return error == "access_denied" ? .denied : .error(error) }
        guard let code, !code.isEmpty, code.utf8.count <= 16 * 1024 else { return nil }
        return .code(code: code, clientId: (clientId?.isEmpty ?? true) ? nil : clientId, scope: scope)
    }

    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }
}

/// 이 설치(호스트)의 `ext_agent_host_id`. 처음 로그인 전에 정해 두고 계속 쓴다(`urn:uuid:` 형식, 개인정보 아님).
public enum SIWCHostID {
    public static let key = "siwc.extAgentHostId"

    public static func loadOrCreate(_ store: ChatKeyValueStore) -> String {
        if let v = store.string(key), isValid(v) { return v }
        let v = "urn:uuid:" + UUID().uuidString.lowercased()
        store.set(v, for: key)
        return v
    }

    public static func isValid(_ v: String) -> Bool {
        guard v.hasPrefix("urn:uuid:") else { return v.hasPrefix("urn:ietf:params:oauth:jwk-thumbprint:") || v.hasPrefix("did:key:") }
        return UUID(uuidString: String(v.dropFirst("urn:uuid:".count))) != nil
    }
}
