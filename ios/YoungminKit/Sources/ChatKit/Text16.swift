import Foundation

/// Kotlin `String`은 UTF-16 단위로 위치를 센다. 마크다운·수식 스팬의 위치를 원본과 똑같이 맞추려고
/// 여기서는 글을 UTF-16 배열로 다룬다(화면 쪽 `NSAttributedString`/`NSRange`도 UTF-16 위치를 쓴다).
enum T16 {
    typealias Units = [UInt16]

    static func units(_ s: String) -> Units { Array(s.utf16) }
    static func string(_ a: Units) -> String { String(decoding: a, as: UTF16.self) }
    static func string(_ a: ArraySlice<UInt16>) -> String { String(decoding: a, as: UTF16.self) }

    /// `s.indexOf(needle, from)`; 없으면 -1.
    static func indexOf(_ s: Units, _ needle: Units, from: Int) -> Int {
        let start = max(0, from)
        guard !needle.isEmpty else { return start <= s.count ? start : -1 }
        guard s.count >= needle.count, start <= s.count - needle.count else { return -1 }
        var i = start
        let last = s.count - needle.count
        while i <= last {
            if s[i] == needle[0] {
                var ok = true
                var k = 1
                while k < needle.count { if s[i + k] != needle[k] { ok = false; break }; k += 1 }
                if ok { return i }
            }
            i += 1
        }
        return -1
    }

    static func indexOf(_ s: Units, _ c: UInt16, from: Int) -> Int {
        var i = max(0, from)
        while i < s.count { if s[i] == c { return i }; i += 1 }
        return -1
    }

    static func startsWith(_ s: Units, _ needle: Units, at i: Int = 0) -> Bool {
        guard i >= 0, i + needle.count <= s.count else { return false }
        for k in 0..<needle.count where s[i + k] != needle[k] { return false }
        return true
    }

    static func contains(_ s: Units, _ c: UInt16) -> Bool { s.contains(c) }

    private static func scalar(_ c: UInt16) -> Unicode.Scalar? { Unicode.Scalar(UInt32(c)) }

    /// Kotlin `Char.isWhitespace()`: Java `Character.isWhitespace` 또는 `isSpaceChar`(Zs, Zl, Zp).
    static func isWhitespace(_ c: UInt16) -> Bool {
        switch c {
        case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F: return true
        default: break
        }
        guard let u = scalar(c) else { return false }
        switch u.properties.generalCategory {
        case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
        default: return false
        }
    }

    /// Kotlin `Char.isDigit()`(Unicode Nd).
    static func isDigit(_ c: UInt16) -> Bool {
        guard let u = scalar(c) else { return false }
        return u.properties.generalCategory == .decimalNumber
    }

    /// Kotlin `Char.isLetter()`(Lu, Ll, Lt, Lm, Lo).
    static func isLetter(_ c: UInt16) -> Bool {
        guard let u = scalar(c) else { return false }
        switch u.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    static func isHighSurrogate(_ c: UInt16) -> Bool { c >= 0xD800 && c <= 0xDBFF }

    static func isBlank<C: Collection>(_ s: C) -> Bool where C.Element == UInt16 { s.allSatisfy(isWhitespace) }

    /// Kotlin `trim()`.
    static func trim(_ s: ArraySlice<UInt16>) -> ArraySlice<UInt16> {
        var a = s.startIndex
        var b = s.endIndex
        while a < b && isWhitespace(s[a]) { a += 1 }
        while b > a && isWhitespace(s[b - 1]) { b -= 1 }
        return s[a..<b]
    }

    static func trim(_ s: Units) -> Units { Array(trim(s[...])) }

    static func trimEnd(_ s: Units) -> Units {
        var b = s.count
        while b > 0 && isWhitespace(s[b - 1]) { b -= 1 }
        return Array(s[0..<b])
    }

    /// Kotlin `trim(ch)`.
    static func trim(_ s: Units, char: UInt16) -> Units {
        var a = 0
        var b = s.count
        while a < b && s[a] == char { a += 1 }
        while b > a && s[b - 1] == char { b -= 1 }
        return Array(s[a..<b])
    }

    /// Kotlin `split(ch)`(빈 조각 유지).
    static func split(_ s: Units, _ sep: UInt16) -> [Units] {
        var out: [Units] = []
        var cur: Units = []
        for c in s {
            if c == sep { out.append(cur); cur = [] } else { cur.append(c) }
        }
        out.append(cur)
        return out
    }

    static func lit(_ s: String) -> Units { Array(s.utf16) }
    static func ch(_ s: Unicode.Scalar) -> UInt16 { UInt16(s.value) }
}

extension String {
    /// UTF-16 길이(Kotlin `length`).
    var length16: Int { utf16.count }

    /// UTF-16 위치로 자른다(Kotlin `substring`). 범위는 잘라서 맞춘다.
    func substring16(_ from: Int, _ to: Int) -> String {
        let u = Array(utf16)
        let a = min(max(0, from), u.count)
        let b = min(max(a, to), u.count)
        return String(decoding: u[a..<b], as: UTF16.self)
    }
}

/// Java 정규식의 `\s`(ASCII 공백)로 이어진 구간을 한 칸 공백으로 바꾼다.
func collapseJavaWhitespace(_ s: String) -> String {
    var out = String.UnicodeScalarView()
    var inSpace = false
    for u in s.unicodeScalars {
        switch u.value {
        case 0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D:
            if !inSpace { out.append(" ") }
            inSpace = true
        default:
            out.append(u)
            inSpace = false
        }
    }
    return String(out)
}

/// Kotlin `trim()`과 같은 공백 기준으로 자른다.
func kotlinTrim(_ s: String) -> String { T16.string(T16.trim(T16.units(s))) }

/// Kotlin `sortedByDescending`처럼 안정 정렬한다.
func stableSorted<T>(_ list: [T], by less: (T, T) -> Bool) -> [T] {
    list.enumerated().sorted { a, b in
        if less(a.element, b.element) { return true }
        if less(b.element, a.element) { return false }
        return a.offset < b.offset
    }.map(\.element)
}
