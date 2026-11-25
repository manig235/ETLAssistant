import Foundation

/// 인증 방식(Kotlin `AppSettings.AUTH_CHATGPT`/`AUTH_API_KEY`에 해당).
public enum AuthKind: String, Sendable, Hashable {
    case apiKey = "apikey"
    case chatGPT = "chatgpt"
}

/// 인증 오류(한국어 문구 포함).
public enum AuthError: Error, Sendable, Hashable {
    /// 로그인하지 않았거나 키가 없다.
    case notSignedIn
    /// 로그인은 됐지만 ChatGPT 플랜 사용(`chatgpt.tokens.use.direct`)을 허용하지 않았다.
    case planUsageNotGranted
    /// 새로 고침 토큰을 쓸 수 없어 다시 로그인해야 한다.
    case reauthRequired
    /// 일시적인 실패(네트워크 등). 저장된 로그인은 지우지 않는다.
    case temporarilyUnavailable

    public var userMessage: String {
        switch self {
        case .notSignedIn: return "먼저 로그인해야 해요"
        case .planUsageNotGranted: return "ChatGPT 플랜 사용을 허용하지 않았어요. 다시 로그인해서 허용하거나 OpenAI API 키를 써 주세요."
        case .reauthRequired: return "로그인이 만료됐어요. 설정에서 다시 로그인해 주세요."
        case .temporarilyUnavailable: return "로그인 서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요."
        }
    }
}

/// Responses 호출에 쓸 Bearer 토큰을 주는 쪽(새로 만듦).
public protocol AuthProvider: Sendable {
    var kind: AuthKind { get }
    /// `forceRefresh`면 저장된 토큰이 유효해 보여도 새로 받는다(401 뒤 한 번).
    func bearerToken(forceRefresh: Bool) async throws -> String
}

/// OpenAI API 키(예비 방식). 키는 비밀값 저장소(Keychain)에 둔다.
public final class APIKeyAuthProvider: AuthProvider, @unchecked Sendable {
    public static let SECRET_KEY = "openai_api_key"

    private let secrets: ChatSecretStore
    private let key: String

    public init(secrets: ChatSecretStore, key: String = APIKeyAuthProvider.SECRET_KEY) {
        self.secrets = secrets
        self.key = key
    }

    public var kind: AuthKind { .apiKey }

    public func bearerToken(forceRefresh: Bool) async throws -> String {
        guard let k = secrets.secret(key)?.trimmingCharacters(in: .whitespacesAndNewlines), !k.isEmpty else { throw AuthError.notSignedIn }
        return k
    }

    public var hasKey: Bool { !(secrets.secret(key) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    public func save(_ apiKey: String?) throws {
        let v = apiKey?.trimmingCharacters(in: .whitespacesAndNewlines)
        try secrets.setSecret((v?.isEmpty ?? true) ? nil : v, for: key)
    }
}

/// 모델 목록 불러오기(Kotlin `CodexSession.refreshModels`의 iOS 판, 새로 만듦). `GET /v1/models`.
public enum ModelListClient {
    public static let endpoint = URL(string: "https://api.openai.com/v1/models")!

    public static func fetch(auth: AuthProvider, transport: HTTPTransport) async throws -> [ModelInfo] {
        let token = try await auth.bearerToken(forceRefresh: false)
        var r = URLRequest(url: endpoint)
        r.httpMethod = "GET"
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.timeoutInterval = 30
        let (data, response) = try await transport.data(for: r)
        guard (200..<300).contains(response.statusCode) else {
            throw ResponsesRequest.httpError(status: response.statusCode, body: data, requestId: response.value(forHTTPHeaderField: "x-request-id"))
        }
        return ModelCatalog.parseOpenAIModels(try ChatJSON(parsing: data))
    }
}
