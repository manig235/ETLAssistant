import Foundation
import ETLKit

// Kotlin·java.time 동작을 옮기는 데 쓰는 작은 도구들(NotifyKit 내부 + 일부 공개).

// MARK: - 시각(LocalTime)

/// 하루 중 시각(`java.time.LocalTime`의 시·분). 편집 창 계산([DeadlineForm])에 쓴다.
public struct DayTime: Hashable, Comparable, Sendable, Codable, CustomStringConvertible {
    public var hour: Int
    public var minute: Int

    /// 범위(0..23, 0..59)를 벗어나면 nil(`LocalTime.of`는 예외).
    public init?(hour: Int, minute: Int) {
        guard (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        self.hour = hour
        self.minute = minute
    }

    /// `LocalTime.of(h, m)`. 올바르지 않으면 런타임 오류.
    public static func of(_ hour: Int, _ minute: Int) -> DayTime {
        guard let t = DayTime(hour: hour, minute: minute) else { preconditionFailure("잘못된 시각 \(hour):\(minute)") }
        return t
    }

    /// `LocalTime.parse("HH:mm")`(두 자리). 못 읽으면 nil.
    public static func parse(_ s: String) -> DayTime? {
        let b = Array(s.utf8)
        guard b.count == 5, b[2] == UInt8(ascii: ":"), b.enumerated().allSatisfy({ $0.offset == 2 || ($0.element >= 0x30 && $0.element <= 0x39) }) else { return nil }
        let h = Int(b[0] - 0x30) * 10 + Int(b[1] - 0x30)
        let m = Int(b[3] - 0x30) * 10 + Int(b[4] - 0x30)
        return DayTime(hour: h, minute: m)
    }

    public var minuteOfDay: Int { hour * 60 + minute }
    public var description: String { String(format: "%02d:%02d", hour, minute) }
    public static func < (a: DayTime, b: DayTime) -> Bool { a.minuteOfDay < b.minuteOfDay }
}

// MARK: - 시간대(ZoneId) 계산

/// `ZonedDateTime` 대신 쓰는 시간대 계산. KST가 아닌 기기 시간대(브리핑·방해 금지·내 일정 날짜 묶음)에 쓴다.
public enum ZoneClock {
    /// [t]의 [zone] 기준 날짜(`t.atZone(zone).toLocalDate()`).
    public static func localDate(_ t: Date, _ zone: TimeZone) -> DayDate {
        let local = (t.timeIntervalSince1970 + Double(zone.secondsFromGMT(for: t))).rounded(.down)
        return DayDate(epochDay: Int64((local / 86_400).rounded(.down)))
    }

    /// [t]의 [zone] 기준 하루 중 초(0..86399).
    public static func secondOfDay(_ t: Date, _ zone: TimeZone) -> Int {
        let local = (t.timeIntervalSince1970 + Double(zone.secondsFromGMT(for: t))).rounded(.down)
        let days = (local / 86_400).rounded(.down)
        return Int(local - days * 86_400)
    }

    public static func hour(_ t: Date, _ zone: TimeZone) -> Int { secondOfDay(t, zone) / 3600 }
    public static func minute(_ t: Date, _ zone: TimeZone) -> Int { (secondOfDay(t, zone) % 3600) / 60 }
    /// 하루 중 분(0..1439).
    public static func minuteOfDay(_ t: Date, _ zone: TimeZone) -> Int { secondOfDay(t, zone) / 60 }

    /// `ZonedDateTime.of(date, h:m:s, zone).toInstant()`: 없는 시각(서머타임 시작)은 그 간격만큼 뒤로,
    /// 두 번 있는 시각(서머타임 끝)은 이른 쪽.
    public static func instant(_ date: DayDate, _ hour: Int, _ minute: Int, _ second: Int = 0, _ zone: TimeZone) -> Date {
        let local = Double(date.epochDay * 86_400 + Int64(hour * 3600 + minute * 60 + second))
        let before = zone.secondsFromGMT(for: Date(timeIntervalSince1970: local - 86_400))
        let after = zone.secondsFromGMT(for: Date(timeIntervalSince1970: local + 86_400))
        var valid: [Date] = []
        for o in [before, after] {
            let t = Date(timeIntervalSince1970: local - Double(o))
            if zone.secondsFromGMT(for: t) == o { valid.append(t) }
        }
        if let earliest = valid.min() { return earliest }
        // 간격(gap): 전환 전 오프셋으로 계산하면 간격만큼 뒤로 밀린 시각이 된다.
        return Date(timeIntervalSince1970: local - Double(before))
    }

    /// `date.atStartOfDay(zone).toInstant()`.
    public static func startOfDay(_ date: DayDate, _ zone: TimeZone) -> Date { instant(date, 0, 0, 0, zone) }
}

// MARK: - Kotlin 문자열 보조

extension Unicode.Scalar {
    /// Java `Character.isISOControl`.
    var isISOControl: Bool { value <= 0x1F || (0x7F...0x9F).contains(value) }
    /// Kotlin `Char.isWhitespace()` (= Java `isWhitespace || isSpaceChar`).
    var isKotlinWhitespace: Bool {
        switch value {
        case 0x09...0x0D, 0x1C...0x1F: return true
        default:
            switch properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }
}

extension String {
    /// Kotlin `length`(UTF-16 단위).
    var kLength: Int { utf16.count }

    /// Kotlin `trim()`.
    var kTrim: String { kTrimEnd.kTrimStartWS }
    var kTrimEnd: String {
        let scalars = unicodeScalars
        guard let i = scalars.lastIndex(where: { !$0.isKotlinWhitespace }) else { return "" }
        return String(scalars[...i])
    }
    var kTrimStartWS: String {
        let scalars = unicodeScalars
        guard let i = scalars.firstIndex(where: { !$0.isKotlinWhitespace }) else { return "" }
        return String(scalars[i...])
    }
    /// Kotlin `isBlank()`.
    var kIsBlank: Bool { unicodeScalars.allSatisfy { $0.isKotlinWhitespace } }

    /// Kotlin `take(n)`(UTF-16 단위, 대리쌍을 가운데서 자르지 않는다).
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
    /// Kotlin `substringAfterLast(c)`.
    func substringAfterLast(_ c: Character) -> String {
        guard let i = lastIndex(of: c) else { return self }
        return String(self[index(after: i)...])
    }
    /// Kotlin `removePrefix`.
    func removingPrefix(_ p: String) -> String { hasPrefix(p) ? String(dropFirst(p.count)) : self }
    /// Kotlin `replaceFirst(old, new)`(글자 그대로).
    func replacingFirst(_ old: String, _ new: String) -> String {
        guard let r = range(of: old) else { return self }
        return replacingCharacters(in: r, with: new)
    }
    /// Kotlin `String.compareTo`(UTF-16 사전순).
    func kLess(_ o: String) -> Bool { utf16.lexicographicallyPrecedes(o.utf16) }
    /// Kotlin `ifEmpty { nil }`.
    var nilIfEmpty: String? { isEmpty ? nil : self }
    /// Kotlin `takeIf { it.isNotBlank() }`.
    var nilIfBlank: String? { kIsBlank ? nil : self }
}

/// Kotlin `sortedWith`처럼 안정 정렬.
func stableSorted<T>(_ xs: [T], by less: (T, T) -> Bool) -> [T] {
    xs.enumerated().sorted { a, b in
        if less(a.element, b.element) { return true }
        if less(b.element, a.element) { return false }
        return a.offset < b.offset
    }.map(\.element)
}

/// Kotlin `distinctBy`(처음 것을 남긴다).
func distinctBy<T, K: Hashable>(_ xs: [T], _ key: (T) -> K) -> [T] {
    var seen = Set<K>()
    return xs.filter { seen.insert(key($0)).inserted }
}

/// Kotlin `distinct()`(순서 유지).
func distinct<T: Hashable>(_ xs: [T]) -> [T] { distinctBy(xs) { $0 } }

/// 비교 도우미: Optional 날짜(Kotlin `compareBy`는 null을 먼저 둔다).
func lessNullsFirst<T: Comparable>(_ a: T?, _ b: T?) -> Bool {
    switch (a, b) {
    case (nil, nil): return false
    case (nil, _): return true
    case (_, nil): return false
    case let (x?, y?): return x < y
    }
}

extension Date {
    /// `Duration`을 더한다(초).
    func plus(seconds s: Double) -> Date { addingTimeInterval(s) }
}

/// Kotlin `Math.floorMod(Long, Long)`.
func floorMod(_ a: Int64, _ n: Int64) -> Int64 { ((a % n) + n) % n }
