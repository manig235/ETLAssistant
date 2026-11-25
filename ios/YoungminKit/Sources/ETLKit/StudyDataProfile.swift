import Foundation

/// A profile is fixed for the lifetime of the process. Switching takes effect
/// on the next launch, so an in-flight tool can never write into another profile.
public enum StudyDataProfile {
    public static let selectionKey = "studyData.mode"
    public static let demoSuite = "kr.snu.etlassistant.fake-etl.v1"
    public static let isDemo = UserDefaults.standard.string(forKey: selectionKey) == "fake"
    public static var hasSelection: Bool { UserDefaults.standard.string(forKey: selectionKey) != nil }
    public static var defaults: UserDefaults { isDemo ? UserDefaults(suiteName: demoSuite)! : .standard }

    /// Call before constructing any stores or the app state, once per launch.
    @discardableResult
    public static func prepareLaunch() -> Bool {
        let prefs = UserDefaults.standard
        var changed = false
        if let next = prefs.string(forKey: "studyData.nextMode") {
            changed = next != prefs.string(forKey: selectionKey)
            prefs.set(next, forKey: selectionKey)
            prefs.removeObject(forKey: "studyData.nextMode")
        }
        if prefs.bool(forKey: "studyData.resetDemo") {
            changed = true
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            // The account is shared, but the engine still owns these fake
            // thread records. Queue deletion before dropping their references.
            let threads = base.appendingPathComponent("Youngmin/FakeETL/threads")
            let pending = demoNativeThreadDeletes.union(nativeThreadIDs(in: threads))
            let fakePrefs = UserDefaults(suiteName: demoSuite)!
            if let raw = fakePrefs.string(forKey: "app_settings.pendingNativeThreadDeletes"),
               let data = raw.data(using: .utf8), let ids = try? JSONSerialization.jsonObject(with: data) as? [String] {
                demoNativeThreadDeletes = pending.union(ids.filter { UUID(uuidString: $0) != nil })
            } else { demoNativeThreadDeletes = pending }
            fakePrefs.removePersistentDomain(forName: demoSuite)
            try? FileManager.default.removeItem(at: base.appendingPathComponent("Youngmin/FakeETL"))
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? FileManager.default.removeItem(at: docs.appendingPathComponent("etl-fake"))
            prefs.removeObject(forKey: "studyData.resetDemo")
        }
        return changed
    }
    public static var demoNativeThreadDeletes: Set<String> {
        get { Set((UserDefaults.standard.stringArray(forKey: "studyData.deletedDemoThreads") ?? []).filter { UUID(uuidString: $0) != nil }) }
        set { UserDefaults.standard.set(newValue.filter { UUID(uuidString: $0) != nil }.sorted(), forKey: "studyData.deletedDemoThreads") }
    }
    /// Reads only persisted references from the fake profile's thread directory.
    public static func nativeThreadIDs(in directory: URL) -> Set<String> {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey])) ?? []
        var ids: Set<String> = []
        for file in files where file.pathExtension == "json" {
            guard let attrs = try? file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
                  attrs.isRegularFile == true, attrs.isSymbolicLink != true,
                  let bytes = try? Data(contentsOf: file), bytes.count <= 4 * 1024 * 1024,
                  let items = try? JSONSerialization.jsonObject(with: bytes) as? [[String: Any]] else { continue }
            for item in items where item["type"] as? String == "youngmin_codex_thread" {
                if let id = item["thread_id"] as? String, UUID(uuidString: id) != nil { ids.insert(id) }
            }
        }
        return ids
    }
    public static func selectInitial(demo: Bool) {
        precondition(!hasSelection)
        UserDefaults.standard.set(demo ? "fake" : "real", forKey: selectionKey)
    }
    public static func selectNextLaunch(demo: Bool, reset: Bool = false) {
        UserDefaults.standard.set(demo ? "fake" : "real", forKey: "studyData.nextMode")
        if reset { UserDefaults.standard.set(true, forKey: "studyData.resetDemo") }
    }
}
