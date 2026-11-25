import Foundation

/// 토큰 끝점(`/api/accounts/oauth/token`) 응답(새로 만듦).
/// 필드: `access_token`, `refresh_token`(offline_access일 때), `id_token`, `token_type`, `expires_in`, `scope`, `earliest_refresh_at`.
public struct SIWCTokenResponse: Sendable, Hashable {
    public var accessToken: String
    public var refreshToken: String?
    public var idToken: String?
    public var tokenType: String
    public var expiresIn: Int
    public var scope: String?
    /// 형식이 문서에 없다. 숫자(epoch 초) 또는 ISO 8601 문자열을 받는다.
    public var earliestRefreshAt: Date?

    public init(accessToken: String, refreshToken: String?, idToken: String?, tokenType: String = "Bearer", expiresIn: Int,
                scope: String?, earliestRefreshAt: Date? = nil) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.idToken = idToken
        self.tokenType = tokenType
        self.expiresIn = expiresIn
        self.scope = scope
        self.earliestRefreshAt = earliestRefreshAt
    }

    public enum Failure: Error, Sendable, Hashable { case malformed }

    public static func parse(_ data: Data) throws -> SIWCTokenResponse {
        guard let j = try? ChatJSON(parsing: data), let access = j["access_token"]?.string, !access.isEmpty else { throw Failure.malformed }
        func nonEmpty(_ k: String) -> String? { j[k]?.string.flatMap { $0.isEmpty ? nil : $0 } }
        var expires = j["expires_in"]?.int ?? Int(j["expires_in"]?.string ?? "") ?? 0
        if expires <= 0, let exp = (try? JWT.decode(access))?.expiresAt { expires = max(0, Int(exp.timeIntervalSinceNow)) }
        if expires <= 0 { expires = 3600 }
        var earliest: Date?
        if let d = j["earliest_refresh_at"]?.double { earliest = Date(timeIntervalSince1970: d > 1e12 ? d / 1000 : d) }
        else if let s = j["earliest_refresh_at"]?.string { earliest = ISO8601DateFormatter().date(from: s) }
        return SIWCTokenResponse(accessToken: access, refreshToken: nonEmpty("refresh_token"), idToken: nonEmpty("id_token"),
                                 tokenType: nonEmpty("token_type") ?? "Bearer", expiresIn: expires, scope: nonEmpty("scope"),
                                 earliestRefreshAt: earliest)
    }

    /// 오류 응답의 `error` 코드(`invalid_grant` 등).
    public static func errorCode(_ data: Data) -> String? {
        guard let j = try? ChatJSON(parsing: data) else { return nil }
        return j["error"]?.string ?? j["error"]?["code"]?.string ?? j["code"]?.string
    }
}

/// 저장하는 ChatGPT 로그인 기록(새로 만듦, 문서의 자격 증명 기록 예시와 같은 필드 이름).
/// 토큰 값은 `description`·오류·URL·로그에 넣지 않는다. 비밀값 저장소(Keychain)에만 둔다.
public struct ChatGPTCredentials: Codable, Sendable, Hashable, CustomStringConvertible {
    public var email: String?
    public var issuer: String
    /// 검증한 ID 토큰의 `sub`.
    public var subject: String
    /// 발급 client_id(`oaiapp_…`).
    public var clientId: String
    public var extAgentHostId: String
    public var idToken: String
    public var accessToken: String
    public var refreshToken: String?
    public var tokenType: String
    public var expiresIn: Int
    public var scopes: [String]
    public var savedAt: Date
    public var earliestRefreshAt: Date?

    enum CodingKeys: String, CodingKey {
        case email, issuer, subject
        case clientId = "client_id"
        case extAgentHostId = "ext_agent_host_id"
        case idToken = "id_token"
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case tokenType = "token_type"
        case expiresIn = "expires_in"
        case scopes
        case savedAt = "saved_at"
        case earliestRefreshAt = "earliest_refresh_at"
    }

    public init(email: String?, issuer: String = SIWC.issuer, subject: String, clientId: String, extAgentHostId: String, idToken: String,
                accessToken: String, refreshToken: String?, tokenType: String = "Bearer", expiresIn: Int, scopes: [String], savedAt: Date,
                earliestRefreshAt: Date? = nil) {
        self.email = email
        self.issuer = issuer
        self.subject = subject
        self.clientId = clientId
        self.extAgentHostId = extAgentHostId
        self.idToken = idToken
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.tokenType = tokenType
        self.expiresIn = expiresIn
        self.scopes = scopes
        self.savedAt = savedAt
        self.earliestRefreshAt = earliestRefreshAt
    }

    public var description: String { "ChatGPTCredentials(<redacted>)" }

    public var expiresAt: Date { savedAt.addingTimeInterval(TimeInterval(expiresIn)) }

    /// ChatGPT 플랜으로 추론해도 되는가.
    public var canUsePlan: Bool { SIWC.grantsDirectUsage(scopes) }

    public func isExpired(now: Date) -> Bool { now >= expiresAt }

    /// 만료 `skew`초 전부터 새로 고친다. `earliest_refresh_at` 전에는(만료되지 않았다면) 미룬다.
    public func needsRefresh(now: Date, skew: TimeInterval = 300) -> Bool {
        if isExpired(now: now) { return true }
        if let e = earliestRefreshAt, now < e { return false }
        return now >= expiresAt.addingTimeInterval(-skew)
    }

    /// 새로 고침 결과를 반영한다(접근 토큰·만료·권한·회전된 새로 고침 토큰을 함께 바꾼다).
    public func refreshed(with r: SIWCTokenResponse, now: Date) -> ChatGPTCredentials {
        var c = self
        c.accessToken = r.accessToken
        c.expiresIn = r.expiresIn
        c.savedAt = now
        c.tokenType = r.tokenType
        if let s = r.scope { c.scopes = SIWC.splitScope(s) }
        if let rt = r.refreshToken { c.refreshToken = rt }
        if let id = r.idToken { c.idToken = id }
        c.earliestRefreshAt = r.earliestRefreshAt
        return c
    }

    private static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.sortedKeys]
        return e
    }

    private static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }

    public func json() -> String { String(decoding: (try? Self.encoder().encode(self)) ?? Data(), as: UTF8.self) }

    public static func parse(_ text: String) -> ChatGPTCredentials? { try? decoder().decode(ChatGPTCredentials.self, from: Data(text.utf8)) }

    /// 화면에 보일 계정 정보.
    public var accountInfo: AccountInfo { AccountInfo(.chatgpt, email: email, plan: nil) }
}
