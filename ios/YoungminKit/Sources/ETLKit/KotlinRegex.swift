import Foundation

/// Kotlin `Regex`와 같은 모양의 작은 NSRegularExpression 래퍼(ICU 문법이라 안드로이드 정규식과 같은 엔진이다).
public struct KRegex: @unchecked Sendable {
    public let regex: NSRegularExpression
    /// 전체 일치용(`\A(?:패턴)\z`, Kotlin `matches`).
    private let whole: NSRegularExpression

    public struct Options: OptionSet, Sendable {
        public let rawValue: Int
        public init(rawValue: Int) { self.rawValue = rawValue }
        public static let ignoreCase = Options(rawValue: 1)
        public static let dotMatchesAll = Options(rawValue: 2)
        public static let multiline = Options(rawValue: 4)
    }

    public init(_ pattern: String, _ options: Options = []) {
        var o: NSRegularExpression.Options = []
        if options.contains(.ignoreCase) { o.insert(.caseInsensitive) }
        if options.contains(.dotMatchesAll) { o.insert(.dotMatchesLineSeparators) }
        if options.contains(.multiline) { o.insert(.anchorsMatchLines) }
        // 패턴은 코드에 고정된 것만 쓴다.
        regex = try! NSRegularExpression(pattern: pattern, options: o)
        whole = try! NSRegularExpression(pattern: "\\A(?:" + pattern + ")\\z", options: o)
    }

    /// 정규식 특수문자를 이스케이프한다(Kotlin `Regex.escape`).
    public static func escape(_ literal: String) -> String { NSRegularExpression.escapedPattern(for: literal) }

    public struct Match {
        /// 0번은 전체 일치. 참여하지 않은 그룹은 nil.
        public let groups: [String?]
        public let range: Range<String.Index>
        public var value: String { groups[0] ?? "" }
        /// Kotlin `groupValues`: 참여하지 않은 그룹은 "".
        public var groupValues: [String] { groups.map { $0 ?? "" } }
    }

    private func match(from r: NSTextCheckingResult, in s: String) -> Match {
        var groups: [String?] = []
        for i in 0..<r.numberOfRanges {
            let nr = r.range(at: i)
            if nr.location == NSNotFound { groups.append(nil); continue }
            groups.append(Range(nr, in: s).map { String(s[$0]) })
        }
        return Match(groups: groups, range: Range(r.range, in: s)!)
    }

    public func containsMatchIn(_ s: String) -> Bool {
        regex.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    public func find(_ s: String) -> Match? {
        guard let r = regex.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return match(from: r, in: s)
    }

    public func findAll(_ s: String) -> [Match] {
        regex.matches(in: s, range: NSRange(s.startIndex..., in: s)).map { match(from: $0, in: s) }
    }

    /// 전체가 일치하는가(Kotlin `matches`).
    public func matches(_ s: String) -> Bool {
        whole.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    /// 일치를 글자 그대로의 [replacement]로 바꾼다.
    public func replace(_ s: String, _ replacement: String) -> String {
        regex.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s),
                                       withTemplate: NSRegularExpression.escapedTemplate(for: replacement))
    }

    /// 일치마다 [transform] 결과로 바꾼다.
    public func replace(_ s: String, _ transform: (Match) -> String) -> String {
        let ms = regex.matches(in: s, range: NSRange(s.startIndex..., in: s))
        if ms.isEmpty { return s }
        var out = ""
        var last = s.startIndex
        for r in ms {
            let m = match(from: r, in: s)
            out += s[last..<m.range.lowerBound]
            out += transform(m)
            last = m.range.upperBound
        }
        out += s[last...]
        return out
    }

    /// Kotlin `CharSequence.split(Regex)`: 빈 조각도 남긴다.
    public func split(_ s: String) -> [String] {
        let ms = regex.matches(in: s, range: NSRange(s.startIndex..., in: s))
        if ms.isEmpty { return [s] }
        var out: [String] = []
        var last = s.startIndex
        for r in ms {
            guard let range = Range(r.range, in: s) else { continue }
            if range.isEmpty && range.lowerBound == s.startIndex { continue }
            out.append(String(s[last..<range.lowerBound]))
            last = range.upperBound
        }
        out.append(String(s[last...]))
        return out
    }
}

// MARK: - Kotlin 문자열 보조(내부)

extension String {
    /// Kotlin `substringAfterLast(c)`: 없으면 전체(또는 [missing]).
    func substringAfterLast(_ c: Character, _ missing: String? = nil) -> String {
        guard let i = lastIndex(of: c) else { return missing ?? self }
        return String(self[index(after: i)...])
    }
    /// Kotlin `substringBefore(c)`.
    func substringBefore(_ c: Character) -> String {
        guard let i = firstIndex(of: c) else { return self }
        return String(self[..<i])
    }
    /// Kotlin `substringAfter(c)`.
    func substringAfter(_ c: Character) -> String {
        guard let i = firstIndex(of: c) else { return self }
        return String(self[index(after: i)...])
    }
    /// Kotlin `trim()`.
    var kTrim: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    /// Kotlin `isBlank()`.
    var kIsBlank: Bool { kTrim.isEmpty }
    /// Kotlin `ifEmpty { nil }`.
    var nilIfEmpty: String? { isEmpty ? nil : self }
    /// Kotlin `ifBlank { nil }`.
    var nilIfBlank: String? { kIsBlank ? nil : self }
    /// Kotlin `length`(UTF-16 단위).
    var kLength: Int { utf16.count }
    /// Kotlin `take(n)` / `substring(0, n)`(UTF-16 단위, 대리쌍을 자르지 않는다).
    func kTake(_ n: Int) -> String {
        if n <= 0 { return "" }
        if utf16.count <= n { return self }
        var k = n
        while k > 0 {
            if let s = String(utf16.prefix(k)) { return s }
            k -= 1
        }
        return ""
    }
    /// Kotlin `trimStart(vararg chars)`.
    func kTrimStart(_ chars: Set<Character>) -> String {
        String(drop(while: { chars.contains($0) }))
    }
}

/// Kotlin `sortedWith`처럼 안정 정렬.
func stableSorted<T>(_ xs: [T], by less: (T, T) -> Bool) -> [T] {
    xs.enumerated().sorted { a, b in
        if less(a.element, b.element) { return true }
        if less(b.element, a.element) { return false }
        return a.offset < b.offset
    }.map(\.element)
}
