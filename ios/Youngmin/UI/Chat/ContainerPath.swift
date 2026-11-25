import Foundation

/// 대화 기록에 저장된 파일 경로를 지금 앱 폴더 기준으로 다시 맞춘다.
/// iOS는 앱을 다시 설치하거나(개발 중) 업데이트·복원할 때 앱 데이터 폴더의 절대 경로(UUID)가 바뀔 수 있어서,
/// 저장해 둔 절대 경로가 그대로면 파일이 있어도 "지워진 파일"로 보인다.
enum ContainerPath {
    private static let markers = ["/Documents/", "/Library/", "/tmp/"]

    /// 있는 그대로 있으면 그 경로, 아니면 `…/Documents/…`·`…/Library/…` 이후를 지금 홈 폴더에 붙인 경로(있을 때만).
    static func resolve(_ path: String) -> String? {
        let fm = FileManager.default
        if fm.fileExists(atPath: path) { return path }
        let home = NSHomeDirectory()
        for m in markers {
            guard let r = path.range(of: m) else { continue }
            let rebased = home + m + path[r.upperBound...]
            if fm.fileExists(atPath: rebased) { return rebased }
        }
        return nil
    }

    /// `file://` URL 문자열이면 경로를 다시 맞춘 URL, 아니면 nil.
    static func resolveFileURL(_ string: String?) -> URL? {
        guard let s = string, let url = URL(string: s), url.isFileURL else { return nil }
        return resolve(url.path).map { URL(fileURLWithPath: $0) }
    }
}
