import Foundation

/// LearningX Student 2.0이 받는 Canvas 딥링크. eTL 주소만 받고 쿼리·fragment를 보존한다.
/// iOS 앱은 `canvas-student://` 주소를 `UIApplication.open`으로 먼저 열어 보고, 열 수 없으면 [webUrl]을 Safari로 연다.
public enum LearningXLink {
    /// 안드로이드 패키지 이름(참고용). iOS에서는 `canvas-student` URL 스킴을 `LSApplicationQueriesSchemes`에 넣는다.
    public static let PACKAGE = "com.instructure.candroid.xinics2.production"
    public static let SCHEME = "canvas-student"
    private static let BOARD_REDIRECT = KRegexLite("^/learningx/redirect(/courses/[1-9][0-9]*/external_tools/[1-9][0-9]*)$")

    private struct Parts { let path: String; let query: String?; let fragment: String? }

    /// java.net.URI처럼 나눈다(잘못된 % 이스케이프·공백·제어문자는 거부).
    private static func parse(_ text: String) -> (scheme: String, host: String?, user: String?, port: Int?, parts: Parts)? {
        // URLComponents는 잘못된 %나 일부 금지 문자를 스스로 이스케이프하므로 원문에서 먼저 거른다.
        if !validEscapes(text) || text.contains(where: { "\"<>\\^`{|}".contains($0) }) { return nil }
        guard let c = URLComponents(string: text), let scheme = c.scheme else { return nil }
        // 경로·쿼리·조각의 % 이스케이프 확인(java.net.URI는 잘못된 이스케이프를 거부한다).
        for part in [c.percentEncodedPath, c.percentEncodedQuery ?? "", c.percentEncodedFragment ?? ""] where !validEscapes(part) { return nil }
        return (scheme, c.host, c.user, c.port, Parts(path: c.percentEncodedPath, query: c.percentEncodedQuery, fragment: c.percentEncodedFragment))
    }

    private static func validEscapes(_ s: String) -> Bool {
        let b = Array(s.utf8)
        var i = 0
        while i < b.count {
            if b[i] == UInt8(ascii: "%") {
                guard i + 2 < b.count, isHex(b[i + 1]), isHex(b[i + 2]) else { return false }
                i += 3
            } else { i += 1 }
        }
        return true
    }

    private static func isHex(_ c: UInt8) -> Bool { (0x30...0x39).contains(c) || (0x41...0x46).contains(c) || (0x61...0x66).contains(c) }

    /// eTL 링크만 HTTPS 원문 주소로 정규화한다. 사용자 정보·별도 서버 포트는 허용하지 않는다.
    public static func webUrl(_ url: String?) -> String? {
        guard let text = url?.kTrim, !text.isEmpty else { return nil }
        if text.unicodeScalars.contains(where: { $0.isISOControl || $0.isKotlinWhitespace }) { return nil }
        guard let u = parse(text) else { return nil }
        let scheme = u.scheme.lowercased()
        if scheme != "https" && scheme != "http" { return nil }
        guard let host = u.host, host.lowercased() == "myetl.snu.ac.kr", u.user == nil else { return nil }
        let defaultPort = scheme == "https" ? 443 : 80
        if let p = u.port, p != defaultPort { return nil }
        return "https://myetl.snu.ac.kr" + (u.parts.path.isEmpty ? "/" : u.parts.path) +
            (u.parts.query.map { "?\($0)" } ?? "") + (u.parts.fragment.map { "#\($0)" } ?? "")
    }

    /// 게시글용 웹 리다이렉트는 LearningX가 받는 과목의 외부 도구 경로로 바꾼다.
    public static func fromWebUrl(_ url: String?) -> String? {
        guard let web = webUrl(url), let u = parse(web) else { return nil }
        let path = BOARD_REDIRECT.firstGroup(u.parts.path) ?? u.parts.path
        return "\(SCHEME)://myetl.snu.ac.kr" + path + (u.parts.query.map { "?\($0)" } ?? "") + (u.parts.fragment.map { "#\($0)" } ?? "")
    }

    public static func opensBoardList(_ url: String) -> Bool {
        guard let web = webUrl(url), let u = parse(web) else { return false }
        return BOARD_REDIRECT.firstGroup(u.parts.path) != nil
    }
}

/// 전체 일치 + 첫 그룹만 쓰는 작은 정규식 도우미.
struct KRegexLite: @unchecked Sendable {
    let re: NSRegularExpression
    init(_ p: String) { re = try! NSRegularExpression(pattern: p) }
    func firstGroup(_ s: String) -> String? {
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)), m.range.length == (s as NSString).length,
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }
}
