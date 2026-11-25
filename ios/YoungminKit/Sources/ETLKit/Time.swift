import Foundation

/// 요일(`java.time.DayOfWeek`). ISO 번호: 1 = 월 … 7 = 일.
public enum Weekday: Int, CaseIterable, Codable, Comparable, Hashable, Sendable, CustomStringConvertible {
    case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday

    /// ISO 번호(`DayOfWeek.value`).
    public var value: Int { rawValue }

    /// 한국어 짧은 이름: 월 화 수 목 금 토 일.
    public var label: String { Weekday.labels[rawValue - 1] }
    public static let labels = ["월", "화", "수", "목", "금", "토", "일"]

    /// ISO 번호(1…7)로. 범위를 벗어나면 7로 나눈 나머지를 쓴다.
    public static func of(_ isoNumber: Int) -> Weekday {
        Weekday(rawValue: ((isoNumber - 1) % 7 + 7) % 7 + 1)!
    }

    /// "월"…"일" → 요일.
    public static func fromLabel(_ s: String) -> Weekday? {
        labels.firstIndex(of: s).map { Weekday(rawValue: $0 + 1)! }
    }

    /// [days]일 뒤의 요일(`DayOfWeek.plus`).
    public func plus(_ days: Int) -> Weekday { Weekday.of(rawValue + days % 7 + 7) }
    public func minus(_ days: Int) -> Weekday { plus(-(days % 7)) }

    public static func < (a: Weekday, b: Weekday) -> Bool { a.rawValue < b.rawValue }
    public var description: String { label }
}

/// KST 기준 날짜(`java.time.LocalDate`). JSON/Codable 표기는 `yyyy-MM-dd`.
public struct DayDate: Comparable, Hashable, Codable, Sendable, CustomStringConvertible {
    public let year: Int
    /// 1…12 (`monthValue`).
    public let month: Int
    /// 1…31 (`dayOfMonth`).
    public let day: Int

    /// 날짜를 만든다. 존재하지 않는 날짜면 nil(`LocalDate.of`는 예외).
    public init?(year: Int, month: Int, day: Int) {
        guard (1...12).contains(month), day >= 1, day <= DayDate.lengthOfMonth(year: year, month: month) else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    /// 검사 없이(내부용, 값이 올바름이 보장될 때).
    init(unchecked year: Int, _ month: Int, _ day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// `LocalDate.of(y, m, d)`. 올바르지 않으면 런타임 오류.
    public static func of(_ year: Int, _ month: Int, _ day: Int) -> DayDate {
        guard let d = DayDate(year: year, month: month, day: day) else { preconditionFailure("잘못된 날짜 \(year)-\(month)-\(day)") }
        return d
    }

    /// 그 시각의 KST 날짜.
    public init(_ date: Date) {
        // KST는 UTC+9 고정.
        let days = ((date.timeIntervalSince1970 + 9 * 3600) / 86_400).rounded(.down)
        self.init(epochDay: Int64(days))
    }

    /// 오늘(KST). 테스트를 위해 [now]를 받을 수 있다.
    public static func today(_ now: Date = Date()) -> DayDate { DayDate(now) }

    /// `LocalDate.parse("yyyy-MM-dd")`. 형식이 다르거나 없는 날짜면 nil.
    public init?(iso: String) {
        let b = Array(iso.utf8)
        guard b.count == 10, b[4] == UInt8(ascii: "-"), b[7] == UInt8(ascii: "-") else { return nil }
        let digits = { (r: Range<Int>) -> Int? in
            var n = 0
            for i in r {
                guard b[i] >= 0x30, b[i] <= 0x39 else { return nil }
                n = n * 10 + Int(b[i] - 0x30)
            }
            return n
        }
        guard let y = digits(0..<4), let m = digits(5..<7), let d = digits(8..<10) else { return nil }
        self.init(year: y, month: m, day: d)
    }

    /// `LocalDate.parse` (예외 대신 nil).
    public static func parse(_ s: String) -> DayDate? { DayDate(iso: s) }

    /// `yyyy-MM-dd` (`LocalDate.toString`).
    public var description: String { String(format: "%04d-%02d-%02d", year, month, day) }
    public var iso: String { description }

    // ---- 계산 ----

    public static func isLeapYear(_ y: Int) -> Bool { (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 }
    public static func lengthOfMonth(year: Int, month: Int) -> Int {
        switch month {
        case 2: return isLeapYear(year) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }
    public var isLeapYear: Bool { DayDate.isLeapYear(year) }
    public var lengthOfMonth: Int { DayDate.lengthOfMonth(year: year, month: month) }
    public var lengthOfYear: Int { isLeapYear ? 366 : 365 }
    public var monthValue: Int { month }
    public var dayOfMonth: Int { day }
    public var dayOfYear: Int {
        var n = day
        for m in 1..<month { n += DayDate.lengthOfMonth(year: year, month: m) }
        return n
    }

    /// 1970-01-01부터의 날 수(`toEpochDay`).
    public var epochDay: Int64 {
        // java.time.LocalDate.toEpochDay
        let y = Int64(year)
        let m = Int64(month)
        var total: Int64 = 365 * y
        if y >= 0 {
            total += (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400
        } else {
            total -= y / -4 - y / -100 + y / -400
        }
        total += (367 * m - 362) / 12
        total += Int64(day) - 1
        if m > 2 {
            total -= 1
            if !isLeapYear { total -= 1 }
        }
        return total - 719_528
    }

    /// `LocalDate.ofEpochDay`.
    public init(epochDay: Int64) {
        // java.time.LocalDate.ofEpochDay
        var zeroDay = epochDay + 719_528 - 60
        var adjust: Int64 = 0
        if zeroDay < 0 {
            let adjustCycles = (zeroDay + 1) / 146_097 - 1
            adjust = adjustCycles * 400
            zeroDay += -adjustCycles * 146_097
        }
        var yearEst = (400 * zeroDay + 591) / 146_097
        var doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        if doyEst < 0 {
            yearEst -= 1
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        }
        yearEst += adjust
        let marchDoy0 = doyEst
        let marchMonth0 = (marchDoy0 * 5 + 2) / 153
        let month = (marchMonth0 + 2) % 12 + 1
        let dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1
        yearEst += marchMonth0 / 10
        self.init(unchecked: Int(yearEst), Int(month), Int(dom))
    }

    /// 요일(`dayOfWeek`).
    public var dayOfWeek: Weekday {
        let dow0 = Int(((epochDay + 3) % 7 + 7) % 7) // 0 = 월
        return Weekday(rawValue: dow0 + 1)!
    }

    public func plusDays(_ n: Int) -> DayDate { n == 0 ? self : DayDate(epochDay: epochDay + Int64(n)) }
    public func minusDays(_ n: Int) -> DayDate { plusDays(-n) }
    public func plusWeeks(_ n: Int) -> DayDate { plusDays(n * 7) }
    public func minusWeeks(_ n: Int) -> DayDate { plusDays(-n * 7) }

    /// `plusMonths`: 그 달에 없는 날이면 말일로.
    public func plusMonths(_ n: Int) -> DayDate {
        if n == 0 { return self }
        let total = year * 12 + (month - 1) + n
        let y = Int((Double(total) / 12).rounded(.down))
        let m = total - y * 12 + 1
        return DayDate(unchecked: y, m, min(day, DayDate.lengthOfMonth(year: y, month: m)))
    }
    public func minusMonths(_ n: Int) -> DayDate { plusMonths(-n) }
    public func plusYears(_ n: Int) -> DayDate { plusMonths(n * 12) }

    /// `withDayOfMonth`. 그 달에 없는 날이면 nil.
    public func withDayOfMonth(_ d: Int) -> DayDate? { DayDate(year: year, month: month, day: d) }

    /// 이 날 또는 그 전의 [w] 요일(`TemporalAdjusters.previousOrSame`).
    public func previousOrSame(_ w: Weekday) -> DayDate {
        minusDays(((dayOfWeek.rawValue - w.rawValue) % 7 + 7) % 7)
    }
    /// 이 날 또는 그 뒤의 [w] 요일(`nextOrSame`).
    public func nextOrSame(_ w: Weekday) -> DayDate {
        plusDays(((w.rawValue - dayOfWeek.rawValue) % 7 + 7) % 7)
    }
    /// 다음 [w] 요일(같은 요일이면 7일 뒤, `next`).
    public func next(_ w: Weekday) -> DayDate {
        let d = ((w.rawValue - dayOfWeek.rawValue) % 7 + 7) % 7
        return plusDays(d == 0 ? 7 : d)
    }
    /// 이전 [w] 요일(같은 요일이면 7일 전, `previous`).
    public func previous(_ w: Weekday) -> DayDate {
        let d = ((dayOfWeek.rawValue - w.rawValue) % 7 + 7) % 7
        return minusDays(d == 0 ? 7 : d)
    }

    public func isBefore(_ o: DayDate) -> Bool { self < o }
    public func isAfter(_ o: DayDate) -> Bool { self > o }
    public func isEqual(_ o: DayDate) -> Bool { self == o }

    /// [other]까지의 날 수(`ChronoUnit.DAYS.between(this, other)`).
    public func daysUntil(_ other: DayDate) -> Int { Int(other.epochDay - epochDay) }

    /// [self]부터 [end] 전날까지(`datesUntil`).
    public func datesUntil(_ end: DayDate) -> [DayDate] {
        let n = daysUntil(end)
        return n <= 0 ? [] : (0..<n).map { plusDays($0) }
    }

    // ---- Date 변환(KST) ----

    /// 그날 KST 00:00 (`atStartOfDay(KST)`).
    public var startOfDay: Date { atTime(0, 0) }

    /// 그날 KST [hour]:[minute]:[second] (`atTime(h, m).atZone(KST)`).
    public func atTime(_ hour: Int, _ minute: Int, _ second: Int = 0) -> Date {
        // KST는 서머타임이 없어 UTC+9 고정이다.
        let seconds = epochDay * 86_400 + Int64(hour * 3600 + minute * 60 + second) - 9 * 3600
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    public static func < (a: DayDate, b: DayDate) -> Bool {
        (a.year, a.month, a.day) < (b.year, b.month, b.day)
    }

    // ---- Codable: "yyyy-MM-dd" ----

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        let s = try c.decode(String.self)
        guard let d = DayDate(iso: s) else {
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "yyyy-MM-dd 날짜가 아닙니다: \(s)")
        }
        self = d
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(description)
    }
}

public extension Date {
    /// KST 날짜.
    var kstDay: DayDate { DayDate(self) }
    /// Kotlin `Instant.epochSecond`(내림).
    var epochSecond: Int64 { Int64((timeIntervalSince1970).rounded(.down)) }
    /// Kotlin `Instant.toEpochMilli()`(내림).
    var epochMilli: Int64 { Int64((timeIntervalSince1970 * 1000).rounded(.down)) }
    /// `Instant.ofEpochMilli`.
    init(epochMilli: Int64) { self.init(timeIntervalSince1970: TimeInterval(epochMilli) / 1000) }
    /// `Instant.plusSeconds`.
    func plusSeconds(_ s: Int64) -> Date { addingTimeInterval(TimeInterval(s)) }
}
