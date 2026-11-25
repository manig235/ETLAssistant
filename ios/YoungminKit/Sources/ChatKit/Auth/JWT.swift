import Foundation
import Security

/// JWT 해석·검증(새로 만듦). 서명 확인은 RS256(JWKS의 RSA 공개 키)만 받는다.
public struct JWT: Sendable, Hashable {
    public let header: ChatJSON
    public let claims: ChatJSON
    public let signingInput: Data
    public let signature: Data

    public enum Failure: Error, Sendable, Hashable {
        case malformed
        case unsupportedAlgorithm(String)
        case unknownKey
        case badSignature
        case wrongIssuer
        case wrongAudience
        case expired
        case wrongNonce
        case missingSubject
    }

    public static func decode(_ token: String) throws -> JWT {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[1].count <= 64 * 1024,
              let h = Base64URL.decode(String(parts[0])), let c = Base64URL.decode(String(parts[1])),
              let sig = Base64URL.decode(String(parts[2])),
              let header = try? ChatJSON(parsing: h), header.object != nil,
              let claims = try? ChatJSON(parsing: c), claims.object != nil else { throw Failure.malformed }
        return JWT(header: header, claims: claims, signingInput: Data("\(parts[0]).\(parts[1])".utf8), signature: sig)
    }

    public var subject: String? { claims["sub"]?.string }
    public var email: String? { claims["email"]?.string }
    public var expiresAt: Date? { claims["exp"]?.double.map { Date(timeIntervalSince1970: $0) } }

    /// `aud`가 문자열이거나 배열이다.
    public var audiences: [String] {
        if let s = claims["aud"]?.string { return [s] }
        return claims["aud"]?.array?.compactMap(\.string) ?? []
    }

    /// ID 토큰 주장 확인: 발급자, 대상(발급 client_id), 만료, nonce(주어졌을 때), sub.
    public func validateClaims(issuer: String, audience: String, nonce: String?, now: Date, leeway: TimeInterval = 60) throws {
        guard claims["iss"]?.string == issuer else { throw Failure.wrongIssuer }
        guard audiences.contains(audience) else { throw Failure.wrongAudience }
        guard let exp = expiresAt, exp.addingTimeInterval(leeway) > now else { throw Failure.expired }
        if let nonce { guard let n = claims["nonce"]?.string, SIWCCallback.constantTimeEquals(n, nonce) else { throw Failure.wrongNonce } }
        guard let sub = subject, !sub.isEmpty else { throw Failure.missingSubject }
    }

    /// JWKS(`{"keys":[…]}`)로 RS256 서명을 확인한다.
    public func verifySignature(jwks: ChatJSON) throws {
        guard header["alg"]?.string == "RS256" else { throw Failure.unsupportedAlgorithm(header["alg"]?.string ?? "none") }
        let kid = header["kid"]?.string
        let keys = (jwks["keys"]?.array ?? []).filter { k in
            k["kty"]?.string == "RSA" && (kid == nil || k["kid"]?.string == kid)
                && (k["use"] == nil || k["use"]?.string == "sig") && (k["alg"] == nil || k["alg"]?.string == "RS256")
        }
        guard !keys.isEmpty else { throw Failure.unknownKey }
        for k in keys {
            guard let n = k["n"]?.string.flatMap(Base64URL.decode), let e = k["e"]?.string.flatMap(Base64URL.decode),
                  let key = RSAPublicKey.make(modulus: n, exponent: e) else { continue }
            var err: Unmanaged<CFError>?
            if SecKeyVerifySignature(key, .rsaSignatureMessagePKCS1v15SHA256, signingInput as CFData, signature as CFData, &err) { return }
        }
        throw Failure.badSignature
    }
}

/// JWK(n, e) → `SecKey`. PKCS#1 `RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }` DER을 만든다.
public enum RSAPublicKey {
    public static func make(modulus: Data, exponent: Data) -> SecKey? {
        let der = pkcs1DER(modulus: modulus, exponent: exponent)
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
        ]
        return SecKeyCreateWithData(der as CFData, attrs as CFDictionary, nil)
    }

    public static func pkcs1DER(modulus: Data, exponent: Data) -> Data {
        func integer(_ d: Data) -> Data {
            var bytes = [UInt8](d.drop(while: { $0 == 0 }))
            if bytes.isEmpty { bytes = [0] }
            if bytes[0] & 0x80 != 0 { bytes.insert(0, at: 0) }
            return Data([0x02]) + length(bytes.count) + Data(bytes)
        }
        let body = integer(modulus) + integer(exponent)
        return Data([0x30]) + length(body.count) + body
    }

    static func length(_ n: Int) -> Data {
        if n < 0x80 { return Data([UInt8(n)]) }
        var v = n
        var bytes: [UInt8] = []
        while v > 0 { bytes.insert(UInt8(v & 0xFF), at: 0); v >>= 8 }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }
}
