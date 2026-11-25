import Foundation
import Security

/// SharedPreferences 대신 쓰는 키-값 저장소. 안드로이드의 prefs 이름은 `namespace`로 옮긴다.
public protocol KeyValueStore: AnyObject, Sendable {
    func string(_ key: String) -> String?
    func set(_ value: String?, for key: String)
    func remove(_ key: String)
    func keys() -> [String]
}

public extension KeyValueStore {
    func bool(_ key: String, default value: Bool) -> Bool {
        guard let s = string(key) else { return value }
        return s == "true"
    }
    func set(_ value: Bool, for key: String) { set(value ? "true" : "false", for: key) }
    func int(_ key: String, default value: Int) -> Int { string(key).flatMap(Int.init) ?? value }
    func set(_ value: Int, for key: String) { set(String(value), for: key) }
    func int64(_ key: String) -> Int64? { string(key).flatMap(Int64.init) }
    func set(_ value: Int64, for key: String) { set(String(value), for: key) }
}

/// UserDefaults 기반 저장소. 키 앞에 `namespace.`를 붙여 prefs 파일처럼 나눈다.
public final class UserDefaultsStore: KeyValueStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let prefix: String

    public init(namespace: String, defaults: UserDefaults? = nil) {
        self.defaults = defaults ?? StudyDataProfile.defaults
        self.prefix = namespace + "."
    }

    public func string(_ key: String) -> String? { defaults.string(forKey: prefix + key) }
    public func set(_ value: String?, for key: String) {
        if let value { defaults.set(value, forKey: prefix + key) } else { defaults.removeObject(forKey: prefix + key) }
    }
    public func remove(_ key: String) { defaults.removeObject(forKey: prefix + key) }
    public func keys() -> [String] {
        defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(prefix) }
            .map { String($0.dropFirst(prefix.count)) }
    }
}

/// 테스트·미리보기용 메모리 저장소.
public final class InMemoryStore: KeyValueStore, @unchecked Sendable {
    private var values: [String: String] = [:]
    private let lock = NSLock()
    public init(_ initial: [String: String] = [:]) { values = initial }
    public func string(_ key: String) -> String? { lock.withLock { values[key] } }
    public func set(_ value: String?, for key: String) { lock.withLock { values[key] = value } }
    public func remove(_ key: String) { lock.withLock { _ = values.removeValue(forKey: key) } }
    public func keys() -> [String] { lock.withLock { Array(values.keys) } }
}

/// 비밀값 저장소(안드로이드 Keystore AES-GCM 저장소에 해당). eTL 토큰, ChatGPT 토큰, API 키.
public protocol SecretStore: AnyObject, Sendable {
    func secret(_ key: String) -> String?
    func setSecret(_ value: String?, for key: String) throws
}

public enum SecretStoreError: Error { case keychain(OSStatus) }

/// Keychain(기기에만, 백업·이전 제외: `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
public final class KeychainSecretStore: SecretStore, @unchecked Sendable {
    private let service: String
    public init(service: String = "kr.snu.etlassistant.ios") { self.service = service }

    private func baseQuery(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    public func secret(_ key: String) -> String? {
        var q = baseQuery(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func setSecret(_ value: String?, for key: String) throws {
        let q = baseQuery(key)
        SecItemDelete(q as CFDictionary)
        guard let value else { return }
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw SecretStoreError.keychain(status) }
    }
}

/// 테스트용 비밀값 저장소.
public final class InMemorySecretStore: SecretStore, @unchecked Sendable {
    private var values: [String: String] = [:]
    private let lock = NSLock()
    public init() {}
    public func secret(_ key: String) -> String? { lock.withLock { values[key] } }
    public func setSecret(_ value: String?, for key: String) throws { lock.withLock { values[key] = value } }
}

/// 앱 전용 파일 위치(안드로이드 filesDir/cacheDir에 해당). 백업에서 제외한다.
public enum AppPaths {
    public static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var url = base.appendingPathComponent(StudyDataProfile.isDemo ? "Youngmin/FakeETL" : "Youngmin", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }

    /// The native engine's Keychain account is shared; course data and threads are not.
    public static var codexHome: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("Youngmin/codex", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var excluded = url; try? excluded.setResourceValues(values)
        return url
    }

    /// 하위 폴더(없으면 만든다). 예: `AppPaths.directory("etl-files")`
    public static func directory(_ name: String) -> URL {
        let url = root.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
