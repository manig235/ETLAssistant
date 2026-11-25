import Foundation
import NotifyKit
import os

// 앱 · 위젯 확장(YoungminWidgets) · 공유 확장(YoungminShare)이 함께 쓰는 App Group 폴더.
// 이 폴더(YoungminShared)는 세 타깃에 모두 들어간다. `AppOnly/` 아래 파일만 앱 타깃 전용이다.

enum AppGroup {
    static let id = "group.kr.snu.etlassistant.ios"
    static let logger = Logger(subsystem: "kr.snu.etlassistant.ios", category: "appgroup")

    /// App Group 컨테이너. 서명에 `com.apple.security.application-groups`가 없으면 nil.
    static var container: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) }

    /// 컨테이너 안 하위 폴더(없으면 만든다).
    static func directory(_ path: String) -> URL? {
        guard let root = container else { return nil }
        let url = root.appendingPathComponent(path, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// DEBUG 빌드에서 컨테이너를 얻었는지 한 줄 남긴다(통합 확인용: `log stream --predicate 'category == "appgroup"'`).
    static func logContainer(_ who: String) {
        #if DEBUG
        let url = container
        logger.notice("appgroup: \(who, privacy: .public) container=\(url != nil ? "ok" : "nil", privacy: .public) path=\(url?.path ?? "-", privacy: .public)")
        #endif
    }
}

/// 위젯 종류(`WidgetConfiguration.kind`).
enum WidgetKinds {
    static let deadlines = "kr.snu.etlassistant.widget.deadlines"
    static let timetable = "kr.snu.etlassistant.widget.timetable"
    static let nextClass = "kr.snu.etlassistant.widget.nextClass"
}

/// 위젯 자료 사본과 위젯에서 누른 "완료" 기록.
enum WidgetFiles {
    private static let dir = "widget"
    private static let completionsDir = "widget/completions"

    static var snapshotURL: URL? { AppGroup.directory(dir)?.appendingPathComponent("snapshot.json") }

    static func readSnapshot() -> WidgetSnapshot? {
        guard let url = snapshotURL else { return nil }
        return WidgetSnapshot.decode(try? Data(contentsOf: url))
    }

    /// 바뀌었으면 쓰고 true. 쓴 시각만 다르면 쓰지 않는다(위젯 다시 그리기 예산을 아낀다).
    @discardableResult
    static func writeSnapshot(_ s: WidgetSnapshot) -> Bool {
        guard let url = snapshotURL else { return false }
        if var old = WidgetSnapshot.decode(try? Data(contentsOf: url)) {
            old.writtenAt = s.writtenAt
            if old == s { return false }
        }
        do {
            try s.encoded().write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            AppGroup.logger.error("appgroup: snapshot write failed")
            return false
        }
    }

    /// 위젯의 완료 버튼: 기록을 하나씩 파일로 남긴다(앱과 동시에 써도 서로 덮지 않게).
    static func addCompletion(_ c: WidgetCompletion) {
        guard let d = AppGroup.directory(completionsDir) else { return }
        try? c.encoded().write(to: d.appendingPathComponent("\(Int64(c.at.timeIntervalSince1970 * 1000))-\(UUID().uuidString).json"),
                               options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// 앱이 반영할 완료 기록. 읽은 파일은 지운다.
    static func takeCompletions() -> [WidgetCompletion] {
        guard let d = AppGroup.directory(completionsDir),
              let files = try? FileManager.default.contentsOfDirectory(at: d, includingPropertiesForKeys: nil) else { return [] }
        var out: [WidgetCompletion] = []
        for f in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) where f.pathExtension == "json" {
            if let c = WidgetCompletion.decode(try? Data(contentsOf: f)) { out.append(c) }
            try? FileManager.default.removeItem(at: f)
        }
        return out
    }
}

/// 위젯·공유 확장이 앱을 여는 주소(`youngmin://…`). 앱의 `.onOpenURL`이 받는다.
enum WidgetLinks {
    static let scheme = "youngmin"

    enum Target: Equatable {
        /// 내 일정 전체.
        case schedule
        /// 시간표.
        case timetable
        /// 위젯 항목(`etl-{과목}-{과제}`, `mine-{id}[-{회차}]`, `exam-{id}`).
        case item(String)
        /// 공유 확장이 남긴 받은 내용.
        case share(String)
    }

    static func url(_ t: Target) -> URL {
        switch t {
        case .schedule: return URL(string: "\(scheme)://schedule")!
        case .timetable: return URL(string: "\(scheme)://timetable")!
        case .item(let key): return URL(string: "\(scheme)://item/\(safe(key))")!
        case .share(let id): return URL(string: "\(scheme)://share/\(safe(id))")!
        }
    }

    static func parse(_ url: URL) -> Target? {
        guard url.scheme?.lowercased() == scheme, let host = url.host?.lowercased() else { return nil }
        let arg: String? = url.pathComponents.count > 1 ? url.pathComponents[1] : nil
        switch host {
        case "schedule": return .schedule
        case "timetable": return .timetable
        case "item":
            guard let arg, !arg.isEmpty, safe(arg) == arg else { return nil }
            return .item(arg)
        case "share":
            guard let arg, !arg.isEmpty, safe(arg) == arg else { return nil }
            return .share(arg)
        default: return nil
        }
    }

    /// 항목 키에서 내 마감 ID(`mine-12`, `mine-12-2026-11-23`, `exam-12`).
    static func mineId(_ key: String) -> Int64? {
        let parts = key.split(separator: "-")
        guard parts.count >= 2, parts[0] == "mine" || parts[0] == "exam" else { return nil }
        return Int64(parts[1])
    }

    /// 항목 키에서 eTL 과제(`etl-{과목}-{과제}`).
    static func assignment(_ key: String) -> (courseId: Int64, assignmentId: Int64)? {
        let parts = key.split(separator: "-")
        guard parts.count == 3, parts[0] == "etl", let c = Int64(parts[1]), let a = Int64(parts[2]) else { return nil }
        return (c, a)
    }

    /// 주소 경로에 넣을 수 있는 글자만(영숫자·`-`·`_`).
    private static func safe(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.unicodeScalars where (u.isASCII && CharacterSet.alphanumerics.contains(u)) || u == "-" || u == "_" { out.append(u) }
        return String(out)
    }
}
