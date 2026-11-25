import Foundation

/// 비밀값 하나(Kotlin `SecretStore` 클래스). 안드로이드는 Keystore AES-GCM으로 암호화해 prefs에 두었고,
/// iOS는 [SecretStore](기본 Keychain, 이 기기에만)에 둔다. 원문은 로그, 오류 메시지에 남기지 않는다.
/// **기존 값의 저장 키는 바꾸지 말 것**(이미 저장된 값을 읽지 못하게 된다).
open class SecretSlot: @unchecked Sendable {
    public let store: SecretStore
    public let blobKey: String

    public init(store: SecretStore, blobKey: String) {
        self.store = store
        self.blobKey = blobKey
    }

    public func has() -> Bool { store.secret(blobKey) != nil }

    /// 저장한다. 실패하면 "암호화한 로그인 정보를 저장하지 못했어요".
    public func save(_ value: String) throws {
        do {
            try store.setSecret(value, for: blobKey)
        } catch {
            throw EtlException("암호화한 로그인 정보를 저장하지 못했어요")
        }
    }

    /// 저장된 값. 없거나 읽지 못하면 nil.
    public func load() -> String? { store.secret(blobKey) }

    /// 지운다. 실패하면 "로그인 정보를 지우지 못했어요".
    public func clear() throws {
        do {
            try store.setSecret(nil, for: blobKey)
        } catch {
            throw EtlException("로그인 정보를 지우지 못했어요")
        }
    }
}

/// eTL 액세스 토큰.
public final class TokenStore: SecretSlot, @unchecked Sendable {
    public init(store: SecretStore = KeychainSecretStore()) {
        if StudyDataProfile.isDemo {
            let local = InMemorySecretStore()
            try? local.setSecret(FakeEtl.token, for: "token_blob")
            super.init(store: local, blobKey: "token_blob")
        } else { super.init(store: store, blobKey: "token_blob") }
    }
    public func hasToken() -> Bool { has() }
}

/// 사용자가 직접 넣는 OpenAI API 키(고급 옵션).
public final class ApiKeyStore: SecretSlot, @unchecked Sendable {
    public init(store: SecretStore = KeychainSecretStore()) { super.init(store: store, blobKey: "apikey_blob") }
}

/// 앱이 관리하는 ChatGPT OAuth grant.
public final class ChatGptSecretStore: SecretSlot, @unchecked Sendable {
    public init(store: SecretStore = KeychainSecretStore()) { super.init(store: store, blobKey: "chatgpt_oauth_blob") }
}
