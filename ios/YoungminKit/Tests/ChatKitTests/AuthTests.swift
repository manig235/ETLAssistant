import Foundation
import Testing
@testable import ChatKit

private func query(_ url: URL) -> [String: String] {
    var out: [String: String] = [:]
    for q in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] { out[q.name] = q.value }
    return out
}

private func form(_ body: Data?) -> [String: String] {
    var out: [String: String] = [:]
    for (k, v) in FormEncoding.parse(String(decoding: body ?? Data(), as: UTF8.self)) ?? [] { out[k] = v }
    return out
}

@Suite struct SIWCTests {
    @Test func pkceMatchesRFC7636Vector() {
        #expect(PKCE.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let v = PKCE.random()
        #expect(v.count == 43)
        #expect(v.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" })
        #expect(PKCE.random() != v)
    }

    @Test func registrationAuthorizeURL() {
        let a = SIWCAttempt(clientId: SIWC.dynamicClientId, extAgentHostId: "urn:uuid:1b4e28ba-2fa1-11d2-883f-0016d3cca427",
                            redirectURI: SIWC.redirectURI(port: 1455), idTokenHint: "should-not-be-sent")
        let url = a.authorizeURL
        #expect(url.absoluteString.hasPrefix("https://auth.openai.com/api/accounts/authorize?client_id=dynamic_agent_client&agent_name_hint="))
        let q = query(url)
        #expect(q["client_id"] == "dynamic_agent_client")
        #expect(q["agent_name_hint"] == "영민이")
        #expect(q["ext_agent_host_id"] == "urn:uuid:1b4e28ba-2fa1-11d2-883f-0016d3cca427")
        #expect(q["response_type"] == "code")
        #expect(q["redirect_uri"] == "http://127.0.0.1:1455/auth/callback")
        #expect(q["scope"] == "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
        #expect(q["resource"] == "https://api.openai.com/v1")
        #expect(q["state"] == a.state && q["nonce"] == a.nonce)
        #expect(q["code_challenge_method"] == "S256")
        #expect(q["code_challenge"] == PKCE.challenge(a.codeVerifier))
        #expect(q["id_token_hint"] == nil)
        #expect(url.absoluteString.contains("scope=openid%20profile"))
        #expect(!url.absoluteString.contains("localhost"))
        // 시도마다 새 값
        let b = SIWCAttempt(clientId: SIWC.dynamicClientId, extAgentHostId: "h", redirectURI: SIWC.redirectURI(port: 1455))
        #expect(a.state != b.state && a.nonce != b.nonce && a.codeVerifier != b.codeVerifier)
    }

    @Test func returningSignInURLAndTokenBody() {
        let a = SIWCAttempt(clientId: "oaiapp_abc", extAgentHostId: "urn:uuid:x", redirectURI: SIWC.redirectURI(port: 50123),
                            idTokenHint: "id.tok.en", loginHint: "user@example.com")
        let q = query(a.authorizeURL)
        #expect(q["client_id"] == "oaiapp_abc")
        #expect(q["agent_name_hint"] == nil)
        #expect(q["id_token_hint"] == "id.tok.en")
        #expect(q["login_hint"] == "user@example.com")
        #expect(a.authorizeURL.absoluteString.contains("login_hint=user%40example.com"))
        let f = form(Data(a.tokenRequestBody(code: "c0de", clientId: "oaiapp_abc").utf8))
        #expect(f == ["grant_type": "authorization_code", "client_id": "oaiapp_abc", "code": "c0de", "code_verifier": a.codeVerifier,
                      "redirect_uri": "http://127.0.0.1:50123/auth/callback", "resource": "https://api.openai.com/v1"])
    }

    @Test func callbackParsing() {
        let s = "st4te"
        #expect(SIWCCallback.parse(target: "/auth/callback?code=abc&state=st4te&client_id=oaiapp_1&scope=openid+chatgpt.tokens.use.direct", expectedState: s)
                == .code(code: "abc", clientId: "oaiapp_1", scope: "openid chatgpt.tokens.use.direct"))
        #expect(SIWCCallback.parse(target: "/auth/callback?code=abc&state=st4te", expectedState: s) == .code(code: "abc", clientId: nil, scope: nil))
        #expect(SIWCCallback.parse(target: "/auth/callback?code=abc&state=other", expectedState: s) == nil)
        #expect(SIWCCallback.parse(target: "/auth/callback?code=abc", expectedState: s) == nil)
        #expect(SIWCCallback.parse(target: "/other?code=abc&state=st4te", expectedState: s) == nil)
        #expect(SIWCCallback.parse(target: "http://evil/auth/callback?code=abc&state=st4te", expectedState: s) == nil)
        #expect(SIWCCallback.parse(target: "/auth/callback?code=a&code=b&state=st4te", expectedState: s) == nil)
        #expect(SIWCCallback.parse(target: "/auth/callback?state=st4te&state=st4te&code=a", expectedState: s) == nil)
        #expect(SIWCCallback.parse(target: "/auth/callback?error=access_denied&state=st4te", expectedState: s) == .denied)
        #expect(SIWCCallback.parse(target: "/auth/callback?error=access_denied&state=bad", expectedState: s) == nil)
        #expect(SIWCCallback.parse(target: "/auth/callback?error=server_error&state=st4te", expectedState: s) == .error("server_error"))
        #expect(SIWCCallback.parse(target: "/auth/callback?state=st4te", expectedState: s) == nil)
    }

    @Test func hostIdIsStableUrnUuid() {
        let store = InMemoryChatKeyValueStore()
        let a = SIWCHostID.loadOrCreate(store)
        #expect(a.hasPrefix("urn:uuid:"))
        #expect(SIWCHostID.isValid(a))
        #expect(SIWCHostID.loadOrCreate(store) == a)
        store.set("garbage", for: SIWCHostID.key)
        #expect(SIWCHostID.loadOrCreate(store) != "garbage")
    }

    @Test func tokenResponseParsing() throws {
        let r = try SIWCTokenResponse.parse(Data(#"{"access_token":"at","refresh_token":"rt","id_token":"it","token_type":"Bearer","expires_in":3600,"scope":"openid chatgpt.tokens.use.direct","earliest_refresh_at":1900000000}"#.utf8))
        #expect(r.accessToken == "at" && r.refreshToken == "rt" && r.idToken == "it" && r.expiresIn == 3600)
        #expect(SIWC.splitScope(r.scope) == ["openid", "chatgpt.tokens.use.direct"])
        #expect(r.earliestRefreshAt == Date(timeIntervalSince1970: 1_900_000_000))
        let noRefresh = try SIWCTokenResponse.parse(Data(#"{"access_token":"at","expires_in":60}"#.utf8))
        #expect(noRefresh.refreshToken == nil && noRefresh.tokenType == "Bearer")
        #expect(throws: SIWCTokenResponse.Failure.malformed) { try SIWCTokenResponse.parse(Data(#"{"refresh_token":"x"}"#.utf8)) }
        #expect(SIWCTokenResponse.errorCode(Data(#"{"error":"invalid_grant","error_description":"x"}"#.utf8)) == "invalid_grant")
    }

    @Test func credentialsExpiryAndRefreshRules() {
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        var c = ChatGPTCredentials(email: "a@b.c", subject: "user-1", clientId: "oaiapp_1", extAgentHostId: "urn:uuid:x", idToken: "id",
                                   accessToken: "at", refreshToken: "rt", expiresIn: 3600,
                                   scopes: ["openid", "chatgpt.tokens.use.direct"], savedAt: t0)
        #expect(c.canUsePlan)
        #expect(!c.needsRefresh(now: t0.addingTimeInterval(3000)))
        #expect(c.needsRefresh(now: t0.addingTimeInterval(3400)))
        #expect(c.isExpired(now: t0.addingTimeInterval(3600)))
        c.earliestRefreshAt = t0.addingTimeInterval(3500)
        #expect(!c.needsRefresh(now: t0.addingTimeInterval(3400)))
        #expect(c.needsRefresh(now: t0.addingTimeInterval(3600)))
        let r = c.refreshed(with: SIWCTokenResponse(accessToken: "at2", refreshToken: "rt2", idToken: nil, expiresIn: 3600, scope: nil), now: t0.addingTimeInterval(3500))
        #expect(r.accessToken == "at2" && r.refreshToken == "rt2" && r.idToken == "id" && r.scopes == c.scopes)
        #expect(r.expiresAt == t0.addingTimeInterval(7100))
        let back = ChatGPTCredentials.parse(r.json())
        #expect(back?.accessToken == "at2" && back?.clientId == "oaiapp_1" && back?.subject == "user-1")
        #expect(r.json().contains("\"ext_agent_host_id\""))
        #expect(!"\(r)".contains("at2"))
        var noScope = c
        noScope.scopes = ["openid"]
        #expect(!noScope.canUsePlan)
    }
}

@Suite struct JWTTests {
    let signer = TestSigner()
    let now = Date(timeIntervalSince1970: 2_000_000_000)

    private func claims(aud: ChatJSON = "oaiapp_1", nonce: String = "n0nce", exp: Double = 2_000_000_600, sub: String? = "user-1") -> ChatJSON {
        var o: [String: ChatJSON] = ["iss": "https://auth.openai.com", "aud": aud, "nonce": .string(nonce), "exp": .number(exp), "email": "a@b.c"]
        if let sub { o["sub"] = .string(sub) }
        return .object(o)
    }

    @Test func derEncodingMatchesSecurityExport() {
        let (n, e) = signer.modulusAndExponent
        #expect(RSAPublicKey.pkcs1DER(modulus: n, exponent: e) == signer.publicDER)
    }

    @Test func validTokenPasses() throws {
        let jwt = try JWT.decode(signer.sign(claims()))
        try jwt.validateClaims(issuer: SIWC.issuer, audience: "oaiapp_1", nonce: "n0nce", now: now)
        try jwt.verifySignature(jwks: signer.jwks)
        #expect(jwt.subject == "user-1" && jwt.email == "a@b.c")
        let multi = try JWT.decode(signer.sign(claims(aud: ["x", "oaiapp_1"])))
        try multi.validateClaims(issuer: SIWC.issuer, audience: "oaiapp_1", nonce: nil, now: now)
    }

    @Test func claimFailures() throws {
        func check(_ c: ChatJSON, _ expected: JWT.Failure, nonce: String? = "n0nce") throws {
            let jwt = try JWT.decode(signer.sign(c))
            #expect(throws: expected) { try jwt.validateClaims(issuer: SIWC.issuer, audience: "oaiapp_1", nonce: nonce, now: now) }
        }
        try check(claims(aud: "oaiapp_other"), .wrongAudience)
        try check(claims(nonce: "replayed"), .wrongNonce)
        try check(claims(exp: 1_999_990_000), .expired)
        try check(claims(sub: nil), .missingSubject)
        try check(claims().setting("iss", "https://evil.example"), .wrongIssuer)
    }

    @Test func signatureFailures() throws {
        let other = TestSigner()
        let forged = try JWT.decode(other.sign(claims()))
        #expect(throws: JWT.Failure.badSignature) { try forged.verifySignature(jwks: signer.jwks) }
        let unknown = try JWT.decode(signer.sign(claims(), kid: "nope"))
        #expect(throws: JWT.Failure.unknownKey) { try unknown.verifySignature(jwks: signer.jwks) }
        let none = try JWT.decode(unsignedJWT(claims()))
        #expect(throws: JWT.Failure.unsupportedAlgorithm("none")) { try none.verifySignature(jwks: signer.jwks) }
        #expect(throws: JWT.Failure.malformed) { try JWT.decode("a.b") }
        // 내용을 바꾸면 서명이 맞지 않는다
        let parts = signer.sign(claims()).split(separator: ".")
        let tampered = try JWT.decode("\(parts[0]).\(Base64URL.encode(claims(sub: "admin").serialized())).\(parts[2])")
        #expect(throws: JWT.Failure.badSignature) { try tampered.verifySignature(jwks: signer.jwks) }
    }
}

@Suite struct ChatGPTAuthProviderTests {
    let signer = TestSigner()

    final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var t = Date(timeIntervalSince1970: 2_000_000_000)
        var now: Date { lock.withLock { t } }
        func advance(_ s: TimeInterval) { lock.withLock { t = t.addingTimeInterval(s) } }
    }

    final class TokenServer: @unchecked Sendable {
        let lock = NSLock()
        var nonce = ""
        var scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        var refreshError: String?
        var counter = 0
    }

    private func make() -> (ChatGPTAuthProvider, StubTransport, TokenServer, Clock, InMemoryChatKeyValueStore, InMemoryChatSecretStore) {
        let server = TokenServer()
        let clock = Clock()
        let signer = self.signer
        let jwks = signer.jwks.serialized()
        let t = StubTransport(data: { r in
            switch r.url!.absoluteString {
            case SIWC.openIDConfigurationURL.absoluteString:
                return (200, Data(#"{"issuer":"https://auth.openai.com","jwks_uri":"https://auth.openai.com/.well-known/jwks.json","revocation_endpoint":"https://auth.openai.com/oauth/revoke"}"#.utf8))
            case "https://auth.openai.com/.well-known/jwks.json":
                return (200, jwks)
            case SIWC.tokenURL.absoluteString:
                return server.lock.withLock {
                    let f = form(r.httpBody)
                    if f["grant_type"] == "refresh_token", let e = server.refreshError { return (400, Data(#"{"error":"\#(e)"}"#.utf8)) }
                    server.counter += 1
                    var resp: [String: ChatJSON] = [
                        "access_token": .string("at-\(server.counter)"), "refresh_token": .string("rt-\(server.counter)"),
                        "token_type": "Bearer", "expires_in": 3600, "scope": .string(server.scope),
                    ]
                    if f["grant_type"] == "authorization_code" {
                        resp["id_token"] = .string(signer.sign(["iss": "https://auth.openai.com", "aud": .string(f["client_id"] ?? ""),
                                                               "sub": "user-1", "email": "a@b.c", "nonce": .string(server.nonce),
                                                               "exp": .number(clock.now.timeIntervalSince1970 + 600)]))
                    }
                    return (200, ChatJSON.object(resp).serialized())
                }
            case "https://auth.openai.com/oauth/revoke":
                return (200, Data())
            default:
                return (404, Data())
            }
        })
        let settings = InMemoryChatKeyValueStore()
        let secrets = InMemoryChatSecretStore()
        let p = ChatGPTAuthProvider(secrets: secrets, settings: settings, transport: t, now: { clock.now })
        return (p, t, server, clock, settings, secrets)
    }

    private func signIn(_ p: ChatGPTAuthProvider, _ server: TokenServer, clientId: String = "oaiapp_test") async throws -> (SIWCAttempt, ChatGPTCredentials) {
        let a = await p.makeAttempt(port: 1455)
        server.lock.withLock { server.nonce = a.nonce }
        let issued = a.isRegistration ? "&client_id=\(clientId)" : ""
        let creds = try await p.complete(attempt: a, callbackTarget: "/auth/callback?code=abc&state=\(a.state)\(issued)")
        return (a, creds)
    }

    @Test func fullSignInStoresRegistrationAndTokens() async throws {
        let (p, t, server, _, settings, _) = make()
        let (a, creds) = try await signIn(p, server)
        #expect(a.isRegistration)
        #expect(creds.clientId == "oaiapp_test")
        #expect(creds.subject == "user-1" && creds.email == "a@b.c")
        #expect(creds.canUsePlan)
        #expect(creds.extAgentHostId == settings.string(SIWCHostID.key))
        let tokenReq = t.dataRequests.first { $0.url == SIWC.tokenURL }!
        #expect(tokenReq.value(forHTTPHeaderField: "Content-Type") == "application/x-www-form-urlencoded")
        let f = form(tokenReq.httpBody)
        #expect(f["grant_type"] == "authorization_code" && f["client_id"] == "oaiapp_test" && f["code"] == "abc")
        #expect(f["code_verifier"] == a.codeVerifier && f["redirect_uri"] == "http://127.0.0.1:1455/auth/callback")
        #expect(f["resource"] == "https://api.openai.com/v1")
        #expect(try await p.bearerToken(forceRefresh: false) == "at-1")
        // 다음 로그인은 발급 ID로, 이름 힌트 없이
        let next = await p.makeAttempt(port: 50000)
        #expect(next.clientId == "oaiapp_test" && !next.isRegistration)
        #expect(next.loginHint == "a@b.c")
        #expect(next.idTokenHint == creds.idToken)
        #expect(next.extAgentHostId == a.extAgentHostId)
        #expect(await p.accountInfo() == AccountInfo(.chatgpt, email: "a@b.c"))
    }

    @Test func registrationWithoutIssuedClientIsIncomplete() async throws {
        let (p, _, server, _, _, _) = make()
        let a = await p.makeAttempt(port: 1455)
        server.lock.withLock { server.nonce = a.nonce }
        await #expect(throws: SIWCError.registrationIncomplete) {
            try await p.complete(attempt: a, callbackTarget: "/auth/callback?code=abc&state=\(a.state)")
        }
        await #expect(throws: SIWCError.registrationIncomplete) {
            try await p.complete(attempt: a, callbackTarget: "/auth/callback?code=abc&state=\(a.state)&client_id=dynamic_agent_client")
        }
        await #expect(throws: SIWCError.denied) {
            try await p.complete(attempt: a, callbackTarget: "/auth/callback?error=access_denied&state=\(a.state)")
        }
        await #expect(throws: SIWCError.invalidCallback) {
            try await p.complete(attempt: a, callbackTarget: "/auth/callback?code=abc&state=wrong&client_id=oaiapp_x")
        }
    }

    @Test func reauthRejectsDifferentClientAndWrongNonce() async throws {
        let (p, _, server, _, _, _) = make()
        _ = try await signIn(p, server)
        let a = await p.makeAttempt(port: 1455)
        server.lock.withLock { server.nonce = a.nonce }
        await #expect(throws: SIWCError.clientMismatch) {
            try await p.complete(attempt: a, callbackTarget: "/auth/callback?code=abc&state=\(a.state)&client_id=oaiapp_other")
        }
        server.lock.withLock { server.nonce = "stale" }
        await #expect(throws: SIWCError.idToken(.wrongNonce)) {
            try await p.complete(attempt: a, callbackTarget: "/auth/callback?code=abc&state=\(a.state)")
        }
    }

    @Test func missingPlanScopeBlocksInference() async throws {
        let (p, _, server, _, _, _) = make()
        server.lock.withLock { server.scope = "openid profile email offline_access" }
        let (_, creds) = try await signIn(p, server)
        #expect(!creds.canUsePlan)
        await #expect(throws: AuthError.planUsageNotGranted) { try await p.bearerToken(forceRefresh: false) }
    }

    @Test func refreshNearExpiryRotatesTokens() async throws {
        let (p, t, server, clock, _, _) = make()
        _ = try await signIn(p, server)
        clock.advance(3500)
        #expect(try await p.bearerToken(forceRefresh: false) == "at-2")
        let refreshReq = t.dataRequests.last { $0.url == SIWC.tokenURL }!
        let f = form(refreshReq.httpBody)
        #expect(f == ["grant_type": "refresh_token", "client_id": "oaiapp_test", "refresh_token": "rt-1", "resource": "https://api.openai.com/v1"])
        #expect(await p.credentials()?.refreshToken == "rt-2")
        #expect(try await p.bearerToken(forceRefresh: false) == "at-2") // 다시 새로 고치지 않는다
        #expect(try await p.bearerToken(forceRefresh: true) == "at-3")
    }

    @Test func terminalRefreshErrorClearsTokensButKeepsRegistration() async throws {
        let (p, _, server, clock, settings, _) = make()
        _ = try await signIn(p, server)
        server.lock.withLock { server.refreshError = "refresh_token_reused" }
        clock.advance(4000)
        await #expect(throws: AuthError.reauthRequired) { try await p.bearerToken(forceRefresh: false) }
        #expect(await p.credentials() == nil)
        #expect(await p.issuedClientId() == "oaiapp_test")
        #expect(settings.string(SIWCHostID.key) != nil)
        await #expect(throws: AuthError.notSignedIn) { try await p.bearerToken(forceRefresh: false) }
    }

    @Test func signOutRevokesRefreshToken() async throws {
        let (p, t, server, _, _, _) = make()
        _ = try await signIn(p, server)
        let result = await p.signOut()
        #expect(result.revoked)
        let rev = t.dataRequests.last { $0.url?.absoluteString == "https://auth.openai.com/oauth/revoke" }!
        #expect(form(rev.httpBody) == ["token": "rt-1", "token_type_hint": "refresh_token", "client_id": "oaiapp_test"])
        #expect(await p.credentials() == nil)
        #expect(await p.issuedClientId() == "oaiapp_test")
    }

    @Test func apiKeyProvider() async throws {
        let s = InMemoryChatSecretStore()
        let p = APIKeyAuthProvider(secrets: s)
        #expect(!p.hasKey)
        await #expect(throws: AuthError.notSignedIn) { try await p.bearerToken(forceRefresh: false) }
        try p.save("  sk-test  ")
        #expect(try await p.bearerToken(forceRefresh: false) == "sk-test")
        try p.save("")
        #expect(!p.hasKey)
    }
}

@Suite struct LoopbackTests {
    @Test func requestHeadParsing() {
        let d = Data("GET /auth/callback?code=a&state=b HTTP/1.1\r\nHost: 127.0.0.1:1455\r\n\r\n".utf8)
        #expect(LoopbackHTTP.headerComplete(d))
        #expect(LoopbackHTTP.parseRequestHead(d) == LoopbackHTTP.RequestHead(method: "GET", target: "/auth/callback?code=a&state=b"))
        #expect(!LoopbackHTTP.headerComplete(Data("GET / HTTP/1.1\r\nHost: x\r\n".utf8)))
        #expect(LoopbackHTTP.headerComplete(Data("GET / HTTP/1.1\n\n".utf8)))
        #expect(LoopbackHTTP.parseRequestHead(Data("garbage\r\n\r\n".utf8)) == nil)
        #expect(LoopbackHTTP.parseRequestHead(Data(repeating: 0x41, count: LoopbackHTTP.maxHeaderBytes + 1)) == nil)
    }

    @Test func responsePages() {
        let ok = String(decoding: LoopbackHTTP.response(.success), as: UTF8.self)
        #expect(ok.hasPrefix("HTTP/1.1 200 OK\r\n"))
        #expect(ok.contains("영민이 앱으로 돌아가 주세요"))
        #expect(ok.contains("Cache-Control: no-store"))
        let bad = String(decoding: LoopbackHTTP.response(.invalid), as: UTF8.self)
        #expect(bad.hasPrefix("HTTP/1.1 400 Bad Request"))
        let body = LoopbackHTTP.response(.success)
        let headerEnd = body.range(of: Data("\r\n\r\n".utf8))!.upperBound
        #expect(ok.contains("Content-Length: \(body.count - headerEnd)"))
    }

    @Test func siwcDecision() {
        let d = LoopbackCallbackServer.siwcDecision(expectedState: "s")
        #expect(d("/auth/callback?code=x&state=s") == .finish(.success))
        #expect(d("/auth/callback?error=access_denied&state=s") == .finish(.cancelled))
        #expect(d("/auth/callback?code=x&state=t") == .ignore)
        #expect(d("/favicon.ico") == .ignore)
    }

    /// 실제 127.0.0.1 루프백으로 한 번 주고받는다(바깥 네트워크는 쓰지 않는다).
    @Test func listenerCapturesCallbackOnce() async throws {
        let server = LoopbackCallbackServer(port: 0)
        let port = try await server.start()
        #expect(port > 0)
        let session = URLSession(configuration: .ephemeral)
        let waiter = Task { try await server.waitForCallback(timeout: 10, decide: LoopbackCallbackServer.siwcDecision(expectedState: "good")) }
        // state가 틀린 요청은 400을 받고, 기다리기는 계속된다
        let (_, wrong) = try await session.data(from: URL(string: "http://127.0.0.1:\(port)/auth/callback?code=x&state=nope")!)
        #expect((wrong as? HTTPURLResponse)?.statusCode == 400)
        let (data, ok) = try await session.data(from: URL(string: "http://127.0.0.1:\(port)/auth/callback?code=x&state=good")!)
        #expect(try await waiter.value == "/auth/callback?code=x&state=good")
        #expect((ok as? HTTPURLResponse)?.statusCode == 200)
        #expect(String(decoding: data, as: UTF8.self).contains("로그인을 확인했어요"))
        server.stop()
    }

    /// 브라우저가 앱이 기다리기 시작하기 전에 돌아와도 놓치지 않는다.
    @Test func callbackArrivingBeforeWaitIsKept() async throws {
        let server = LoopbackCallbackServer(port: 0)
        let port = try await server.start()
        let request = Task { try await URLSession(configuration: .ephemeral).data(from: URL(string: "http://127.0.0.1:\(port)/auth/callback?code=y&state=s")!) }
        try await Task.sleep(nanoseconds: 200_000_000)
        let target = try await server.waitForCallback(timeout: 10, decide: LoopbackCallbackServer.siwcDecision(expectedState: "s"))
        #expect(target == "/auth/callback?code=y&state=s")
        #expect((try await request.value.1 as? HTTPURLResponse)?.statusCode == 200)
    }

    @Test func stopCancelsWaiting() async throws {
        let server = LoopbackCallbackServer(port: 0)
        _ = try await server.start()
        let waiting = Task { try await server.waitForCallback(timeout: 10) { _ in .ignore } }
        try await Task.sleep(nanoseconds: 50_000_000)
        server.stop()
        await #expect(throws: LoopbackError.cancelled) { _ = try await waiting.value }
    }
}

@Suite struct ChatSettingsAndThreadTests {
    @Test func settingsKeysAndDefaults() {
        let store = InMemoryChatKeyValueStore()
        let s = ChatSettings(store)
        #expect(s.authMode == ChatSettings.AUTH_CHATGPT)
        #expect(s.model == nil && s.effort == nil && !s.casualTone)
        #expect(s.modelChoice.isAuto)
        s.modelChoice = ModelChoice("gpt-x", "high")
        s.casualTone = true
        s.currentChatId = "c1"
        s.pendingThreadDeletes = ["b", "a"]
        #expect(store.string("model") == "gpt-x" && store.string("effort") == "high")
        #expect(store.string("casualTone") == "true")
        #expect(store.string("currentChatId") == "c1")
        #expect(s.pendingThreadDeletes == ["a", "b"])
        s.model = nil
        s.pendingThreadDeletes = []
        #expect(store.string("model") == nil && store.string("pendingThreadDeletes") == nil)
        #expect(s.toneInstructions().contains("반말"))
        #expect(ChatSettings.toneInstructions(casual: false).hasPrefix("말투: 친근한 해요체"))
    }

    @Test func threadStoreLifecycle() {
        let dir = tempDir("threads")
        defer { try? FileManager.default.removeItem(at: dir) }
        let s = ConversationThreadStore(dir)
        let id = s.newThreadId()
        #expect(ThreadPurge.isThreadId(id))
        #expect(!ThreadPurge.isThreadId("../x"))
        #expect(s.load(id).isEmpty)
        let items: [ChatJSON] = [ResponsesInput.userMessage("q"), ["type": "message", "role": "assistant", "content": [["type": "output_text", "text": "a"]]]]
        s.save(id, items)
        #expect(s.load(id) == items)
        let other = s.newThreadId()
        s.save(other, items)
        #expect(s.orphans(keep: [id]) == [other])
        #expect(s.purgePending([other, "not-a-thread"]).isEmpty)
        #expect(s.load(other).isEmpty)
        #expect(s.delete(id))
        #expect(s.load(id).isEmpty)
    }

    @Test func chatJSONRoundTrip() throws {
        let j: ChatJSON = ["a": 1, "b": [true, nil, "x", 1.5], "c": ["d": "e/f"]]
        #expect(try ChatJSON(parsing: j.serialized()) == j)
        #expect(j.jsonString == #"{"a":1,"b":[true,null,"x",1.5],"c":{"d":"e/f"}}"#)
        let decoded = try JSONDecoder().decode(ChatJSON.self, from: JSONEncoder().encode(j))
        #expect(decoded == j)
    }
}
