import Foundation

/// 키-값 저장소(새로 만듦). ETLKit `KeyValueStore`와 요구 사항이 같아서 앱에서
/// `extension UserDefaultsStore: ChatKeyValueStore {}` 한 줄로 붙일 수 있다.
public protocol ChatKeyValueStore: AnyObject, Sendable {
    func string(_ key: String) -> String?
    func set(_ value: String?, for key: String)
    func remove(_ key: String)
}

/// 비밀값 저장소(새로 만듦). ETLKit `SecretStore`와 요구 사항이 같아서 `extension KeychainSecretStore: ChatSecretStore {}`로 붙인다.
public protocol ChatSecretStore: AnyObject, Sendable {
    func secret(_ key: String) -> String?
    func setSecret(_ value: String?, for key: String) throws
}

/// 테스트·미리보기용 메모리 키-값 저장소.
public final class InMemoryChatKeyValueStore: ChatKeyValueStore, @unchecked Sendable {
    private var values: [String: String]
    private let lock = NSLock()
    public init(_ initial: [String: String] = [:]) { values = initial }
    public func string(_ key: String) -> String? { lock.withLock { values[key] } }
    public func set(_ value: String?, for key: String) { lock.withLock { values[key] = value } }
    public func remove(_ key: String) { lock.withLock { _ = values.removeValue(forKey: key) } }
}

/// 테스트용 메모리 비밀값 저장소.
public final class InMemoryChatSecretStore: ChatSecretStore, @unchecked Sendable {
    private var values: [String: String] = [:]
    private let lock = NSLock()
    public init() {}
    public func secret(_ key: String) -> String? { lock.withLock { values[key] } }
    public func setSecret(_ value: String?, for key: String) throws { lock.withLock { values[key] = value } }
}

/// 채팅 관련 앱 설정(Kotlin `codex.AppSettings`, prefs 이름 `app_settings`). 비밀값은 여기에 두지 않는다.
/// 키 이름과 기본값은 원본 그대로. 불리언은 `"true"`/`"false"`, 문자열 집합은 JSON 배열 문자열로 저장한다.
public final class ChatSettings: @unchecked Sendable {
    public static let AUTH_CHATGPT = "chatgpt"
    public static let AUTH_API_KEY = "apikey"
    /// 안드로이드 SharedPreferences 이름(앱이 `UserDefaultsStore(namespace:)`에 쓰면 된다).
    public static let NAMESPACE = "app_settings"

    private let store: ChatKeyValueStore

    public init(_ store: ChatKeyValueStore) { self.store = store }

    public var authMode: String {
        get { store.string("authMode") ?? Self.AUTH_CHATGPT }
        set { store.set(newValue, for: "authMode") }
    }

    /// 선택한 모델(nil = 자동).
    public var model: String? {
        get { store.string("model") }
        set { if let v = newValue { store.set(v, for: "model") } else { store.remove("model") } }
    }

    /// 선택한 모델의 표시 이름(iOS에서 더함, 키 `modelName`). 앱을 연 직후 모델 목록을 받기 전에도 칩에 슬러그 대신 이 이름을 보인다.
    public var modelName: String? {
        get { store.string("modelName") }
        set { if let v = newValue, !v.isEmpty { store.set(v, for: "modelName") } else { store.remove("modelName") } }
    }

    /// 선택한 추론 강도(nil = 모델 기본값).
    public var effort: String? {
        get { store.string("effort") }
        set { if let v = newValue { store.set(v, for: "effort") } else { store.remove("effort") } }
    }

    /// 예전(대화 하나) 형식의 스레드 ID. 대화 목록으로 옮길 때만 읽는다.
    public var legacyThreadId: String? {
        get { store.string("threadId") }
        set { if let v = newValue { store.set(v, for: "threadId") } else { store.remove("threadId") } }
    }

    /// 반말로 대화(기본 꺼짐 = 해요체). 턴마다 지침(`instructions`)에 실어 보낸다.
    public var casualTone: Bool {
        get { store.string("casualTone") == "true" }
        set { store.set(newValue ? "true" : "false", for: "casualTone") }
    }

    /// 마지막으로 열어 둔 대화. 없거나 지워졌으면 빈 새 대화로 시작한다.
    public var currentChatId: String? {
        get { store.string("currentChatId") }
        set { if let v = newValue { store.set(v, for: "currentChatId") } else { store.remove("currentChatId") } }
    }

    /// 지운 대화의 스레드 중 아직 지우지 못한 것. iOS에서는 로컬 파일만 지우므로 실패했을 때만 남는다.
    public var pendingThreadDeletes: Set<String> {
        get {
            guard let s = store.string("pendingThreadDeletes"), let a = try? ChatJSON(parsing: s).array else { return [] }
            return Set(a.compactMap(\.string))
        }
        set {
            if newValue.isEmpty { store.remove("pendingThreadDeletes") }
            else { store.set(ChatJSON.array(newValue.sorted().map(ChatJSON.string)).jsonString, for: "pendingThreadDeletes") }
        }
    }

    /// 지금 저장된 선택(Kotlin `CodexSession.modelChoice`).
    public var modelChoice: ModelChoice {
        get { ModelChoice(model, effort) }
        set { model = newValue.model; effort = newValue.effort }
    }

    /// 지금 설정에 맞는 말투 지침.
    public func toneInstructions() -> String { Self.toneInstructions(casual: casualTone) }

    /// 말투 지침(Kotlin `CodexSession.toneInstructions`). 기본 규칙은 AGENTS.md(지침)에 있고, 여기서는 "반말로 대화"에 따라
    /// 어느 쪽을 쓸지만 정한다. 턴마다 보내 기존 대화에도 다음 턴부터 반영되게 한다.
    public static func toneInstructions(casual: Bool) -> String {
        if casual {
            return "말투: 사용자가 설정에서 \"반말로 대화\"를 켰다. 지금부터 친구처럼 반말(\"~야\", \"~해\", \"~할까?\")로 답한다. " +
                "앞선 답이 해요체였어도 이번 답부터 반말로 쓴다. 짧고 담백하게 쓰고, 과한 이모지·감탄사·인사말은 넣지 않으며, 정확성을 먼저 지킨다. " +
                "예: \"이번 주 마감은 2개야. 회로이론 HW5가 내일 23:59까지라 제일 급해.\""
        }
        return "말투: 친근한 해요체(\"~예요\", \"~해요\")로 답한다. 앞선 답이 반말이었어도 이번 답부터 해요체로 쓴다. " +
            "\"~입니다/~습니다\"체는 쓰지 않는다. 짧고 담백하게 쓰고, 과한 이모지·감탄사·인사말은 넣지 않으며, 정확성을 먼저 지킨다."
    }
}
