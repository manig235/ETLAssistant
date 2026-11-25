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
            UserDefaults(suiteName: demoSuite)?.removePersistentDomain(forName: demoSuite)
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            try? FileManager.default.removeItem(at: base.appendingPathComponent("Youngmin/FakeETL"))
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? FileManager.default.removeItem(at: docs.appendingPathComponent("etl-fake"))
            prefs.removeObject(forKey: "studyData.resetDemo")
        }
        return changed
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
