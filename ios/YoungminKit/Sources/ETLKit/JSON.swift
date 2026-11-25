import Foundation

// org.json(안드로이드판)의 JSONObject / JSONArray를 필요한 만큼 옮긴 것.
// Kotlin 코드를 1:1로 옮기려고 같은 이름·같은 느슨한 변환(optString, optLong …)을 쓴다.
// 키 순서는 넣은 순서(파싱한 순서)를 지킨다. 값은 String, Bool, Int64, Double, JSONObject, JSONArray, JSONNull 중 하나로 저장한다.

/// org.json의 `JSONException`.
public struct JSONException: Error, CustomStringConvertible, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// JSON null (org.json `JSONObject.NULL`).
public final class JSONNull: CustomStringConvertible, @unchecked Sendable {
    public static let shared = JSONNull()
    private init() {}
    public var description: String { "null" }
}

/// 값 정규화: Swift 값 → 저장 형태. 지원하지 않는 값은 문자열로 바꾼다(org.json은 toString 때 문자열로 쓴다).
func jsonNormalize(_ value: Any?) -> Any {
    guard let value else { return JSONNull.shared }
    switch value {
    case let v as JSONObject: return v
    case let v as JSONArray: return v
    case let v as JSONNull: return v
    case let v as String: return v
    case let v as Substring: return String(v)
    case let v as Bool: return v
    case let v as Int: return Int64(v)
    case let v as Int64: return v
    case let v as Int32: return Int64(v)
    case let v as Int16: return Int64(v)
    case let v as Int8: return Int64(v)
    case let v as UInt: return Int64(clamping: v)
    case let v as UInt32: return Int64(v)
    case let v as UInt64: return Int64(clamping: v)
    case let v as Double: return v
    case let v as Float: return Double(v)
    case let v as NSNumber:
        if CFGetTypeID(v) == CFBooleanGetTypeID() { return v.boolValue }
        let t = String(cString: v.objCType)
        return (t == "d" || t == "f") ? v.doubleValue : v.int64Value
    case let v as [Any?]: return JSONArray(v)
    case let v as [Any]: return JSONArray(v.map { Optional($0) })
    case let v as [String: Any]:
        let o = JSONObject()
        for k in v.keys.sorted() { o.put(k, v[k]) }
        return o
    default:
        // Optional이 Any 안에 싸여 온 경우
        let m = Mirror(reflecting: value)
        if m.displayStyle == .optional {
            if let inner = m.children.first?.value { return jsonNormalize(inner) }
            return JSONNull.shared
        }
        return String(describing: value)
    }
}

// MARK: - 변환(org.json JSON.java)

enum JSONCoerce {
    static func toBoolean(_ v: Any?) -> Bool? {
        switch v {
        case let b as Bool: return b
        case let s as String:
            if s.caseInsensitiveCompare("true") == .orderedSame { return true }
            if s.caseInsensitiveCompare("false") == .orderedSame { return false }
            return nil
        default: return nil
        }
    }

    static func toDouble(_ v: Any?) -> Double? {
        switch v {
        case let d as Double: return d
        case let i as Int64: return Double(i)
        case let s as String: return Double(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    static func toLong(_ v: Any?) -> Int64? {
        switch v {
        case let i as Int64: return i
        case let d as Double: return doubleToLong(d)
        case let s as String:
            if let i = Int64(s.trimmingCharacters(in: .whitespaces)) { return i }
            if let d = Double(s.trimmingCharacters(in: .whitespaces)) { return doubleToLong(d) }
            return nil
        default: return nil
        }
    }

    /// Java `(long) d`: 소수점 아래를 버리고 범위를 넘으면 끝값, NaN은 0.
    static func doubleToLong(_ d: Double) -> Int64 {
        if d.isNaN { return 0 }
        if d >= 9.223372036854775807e18 { return Int64.max }
        if d <= -9.223372036854775808e18 { return Int64.min }
        return Int64(d.rounded(.towardZero))
    }

    static func toInt(_ v: Any?) -> Int? {
        toLong(v).map { Int(truncatingIfNeeded: $0) }
    }

    /// org.json `JSON.toString`: 문자열은 그대로, 그 밖은 JSON 표기, null(Kotlin null)은 nil.
    static func toString(_ v: Any?) -> String? {
        guard let v else { return nil }
        if let s = v as? String { return s }
        return JSONWriter.valueText(v)
    }
}

// MARK: - JSONObject

public final class JSONObject: CustomStringConvertible, @unchecked Sendable {
    /// org.json `JSONObject.NULL`.
    public static let NULL = JSONNull.shared

    private var order: [String] = []
    private var map: [String: Any] = [:]

    public init() {}

    /// JSON 문자열을 해석한다. 객체가 아니면 [JSONException].
    public convenience init(_ json: String) throws {
        self.init()
        let v = try JSONParser.parse(json)
        guard let o = v as? JSONObject else { throw JSONException("JSONObject가 아닙니다") }
        self.order = o.order
        self.map = o.map
    }

    /// [String: Any] 사전(키는 이름순)에서 만든다.
    public convenience init(_ dict: [String: Any?]) {
        self.init()
        for k in dict.keys.sorted() { put(k, dict[k] ?? nil) }
    }

    // ---- 쓰기 ----

    /// 값을 넣는다. nil이면 키를 지운다(org.json `put(name, null)`). 체이닝용으로 자신을 돌려준다.
    @discardableResult
    public func put(_ key: String, _ value: Any?) -> JSONObject {
        guard let value else { remove(key); return self }
        let v = jsonNormalize(value)
        if map[key] == nil { order.append(key) }
        map[key] = v
        return self
    }

    @discardableResult
    public func remove(_ key: String) -> Any? {
        guard let v = map.removeValue(forKey: key) else { return nil }
        order.removeAll { $0 == key }
        return v
    }

    // ---- 읽기 ----

    public func has(_ key: String) -> Bool { map[key] != nil }
    public func isNull(_ key: String) -> Bool { map[key] == nil || map[key] is JSONNull }
    public func length() -> Int { order.count }
    public var count: Int { order.count }
    public func keys() -> [String] { order }

    /// 값(JSON null이면 `JSONObject.NULL`), 없으면 nil.
    public func opt(_ key: String) -> Any? { map[key] }

    public func optString(_ key: String, _ fallback: String = "") -> String {
        JSONCoerce.toString(map[key]) ?? fallback
    }
    public func optBoolean(_ key: String, _ fallback: Bool = false) -> Bool { JSONCoerce.toBoolean(map[key]) ?? fallback }
    public func optLong(_ key: String, _ fallback: Int64 = 0) -> Int64 { JSONCoerce.toLong(map[key]) ?? fallback }
    public func optInt(_ key: String, _ fallback: Int = 0) -> Int { JSONCoerce.toInt(map[key]) ?? fallback }
    public func optDouble(_ key: String, _ fallback: Double = .nan) -> Double { JSONCoerce.toDouble(map[key]) ?? fallback }
    public func optJSONObject(_ key: String) -> JSONObject? { map[key] as? JSONObject }
    public func optJSONArray(_ key: String) -> JSONArray? { map[key] as? JSONArray }

    public func get(_ key: String) throws -> Any {
        guard let v = map[key] else { throw JSONException("No value for \(key)") }
        return v
    }
    public func getString(_ key: String) throws -> String {
        guard let s = JSONCoerce.toString(try get(key)) else { throw JSONException("Value at \(key) is not a string") }
        return s
    }
    public func getBoolean(_ key: String) throws -> Bool {
        guard let b = JSONCoerce.toBoolean(try get(key)) else { throw JSONException("Value at \(key) is not a boolean") }
        return b
    }
    public func getLong(_ key: String) throws -> Int64 {
        guard let n = JSONCoerce.toLong(try get(key)) else { throw JSONException("Value at \(key) is not a long") }
        return n
    }
    public func getInt(_ key: String) throws -> Int {
        guard let n = JSONCoerce.toInt(try get(key)) else { throw JSONException("Value at \(key) is not an int") }
        return n
    }
    public func getDouble(_ key: String) throws -> Double {
        guard let n = JSONCoerce.toDouble(try get(key)) else { throw JSONException("Value at \(key) is not a double") }
        return n
    }
    public func getJSONObject(_ key: String) throws -> JSONObject {
        guard let o = try get(key) as? JSONObject else { throw JSONException("Value at \(key) is not a JSONObject") }
        return o
    }
    public func getJSONArray(_ key: String) throws -> JSONArray {
        guard let a = try get(key) as? JSONArray else { throw JSONException("Value at \(key) is not a JSONArray") }
        return a
    }

    /// 압축된 JSON 문자열(org.json `toString()`).
    public func toString() -> String {
        var out = ""
        JSONWriter.write(self, into: &out)
        return out
    }
    public var description: String { toString() }

    /// 깊은 사본.
    public func copy() -> JSONObject {
        let o = JSONObject()
        for k in order { o.put(k, JSONWriter.deepCopy(map[k]!)) }
        return o
    }

    /// Foundation 값(JSONSerialization 호환: [String: Any], [Any], NSNull …)으로.
    public func toFoundation() -> [String: Any] {
        var d: [String: Any] = [:]
        for k in order { d[k] = JSONWriter.foundation(map[k]!) }
        return d
    }
}

// MARK: - JSONArray

public final class JSONArray: CustomStringConvertible, @unchecked Sendable {
    private var items: [Any] = []

    public init() {}

    /// 목록에서 만든다(org.json `JSONArray(Collection)`).
    public init(_ list: [Any?]) { items = list.map(jsonNormalize) }

    public convenience init(_ list: [String]) { self.init(list.map { Optional($0 as Any) }) }
    public convenience init(_ list: [JSONObject]) { self.init(list.map { Optional($0 as Any) }) }
    public convenience init(_ list: [Int64]) { self.init(list.map { Optional($0 as Any) }) }

    public convenience init(_ json: String) throws {
        self.init()
        let v = try JSONParser.parse(json)
        guard let a = v as? JSONArray else { throw JSONException("JSONArray가 아닙니다") }
        self.items = a.items
    }

    /// 끝에 붙인다. nil은 JSON null.
    @discardableResult
    public func put(_ value: Any?) -> JSONArray {
        items.append(jsonNormalize(value))
        return self
    }

    public func length() -> Int { items.count }
    public var count: Int { items.count }

    public func opt(_ i: Int) -> Any? { i >= 0 && i < items.count ? items[i] : nil }
    public func isNull(_ i: Int) -> Bool { let v = opt(i); return v == nil || v is JSONNull }
    public func optString(_ i: Int, _ fallback: String = "") -> String { JSONCoerce.toString(opt(i)) ?? fallback }
    public func optBoolean(_ i: Int, _ fallback: Bool = false) -> Bool { JSONCoerce.toBoolean(opt(i)) ?? fallback }
    public func optLong(_ i: Int, _ fallback: Int64 = 0) -> Int64 { JSONCoerce.toLong(opt(i)) ?? fallback }
    public func optInt(_ i: Int, _ fallback: Int = 0) -> Int { JSONCoerce.toInt(opt(i)) ?? fallback }
    public func optDouble(_ i: Int, _ fallback: Double = .nan) -> Double { JSONCoerce.toDouble(opt(i)) ?? fallback }
    public func optJSONObject(_ i: Int) -> JSONObject? { opt(i) as? JSONObject }
    public func optJSONArray(_ i: Int) -> JSONArray? { opt(i) as? JSONArray }

    public func get(_ i: Int) throws -> Any {
        guard let v = opt(i) else { throw JSONException("Index \(i) out of range [0..\(items.count))") }
        return v
    }
    public func getString(_ i: Int) throws -> String {
        guard let s = JSONCoerce.toString(try get(i)) else { throw JSONException("Value at \(i) is not a string") }
        return s
    }
    public func getLong(_ i: Int) throws -> Int64 {
        guard let n = JSONCoerce.toLong(try get(i)) else { throw JSONException("Value at \(i) is not a long") }
        return n
    }
    public func getInt(_ i: Int) throws -> Int {
        guard let n = JSONCoerce.toInt(try get(i)) else { throw JSONException("Value at \(i) is not an int") }
        return n
    }
    public func getDouble(_ i: Int) throws -> Double {
        guard let n = JSONCoerce.toDouble(try get(i)) else { throw JSONException("Value at \(i) is not a double") }
        return n
    }
    public func getBoolean(_ i: Int) throws -> Bool {
        guard let b = JSONCoerce.toBoolean(try get(i)) else { throw JSONException("Value at \(i) is not a boolean") }
        return b
    }
    public func getJSONObject(_ i: Int) throws -> JSONObject {
        guard let o = try get(i) as? JSONObject else { throw JSONException("Value at \(i) is not a JSONObject") }
        return o
    }
    public func getJSONArray(_ i: Int) throws -> JSONArray {
        guard let a = try get(i) as? JSONArray else { throw JSONException("Value at \(i) is not a JSONArray") }
        return a
    }

    /// 저장된 값들(정규화된 형태).
    public var values: [Any] { items }

    public func toString() -> String {
        var out = ""
        JSONWriter.write(self, into: &out)
        return out
    }
    public var description: String { toString() }

    public func copy() -> JSONArray {
        let a = JSONArray()
        for v in items { a.put(JSONWriter.deepCopy(v)) }
        return a
    }

    public func toFoundation() -> [Any] { items.map(JSONWriter.foundation) }
}

// MARK: - 쓰기

enum JSONWriter {
    static func valueText(_ v: Any) -> String {
        var out = ""
        write(v, into: &out)
        return out
    }

    static func numberText(_ d: Double) -> String {
        guard d.isFinite else { return "null" }
        if d == d.rounded(), abs(d) < 9.0e15 { return String(Int64(d)) }
        return String(d)
    }

    static func write(_ v: Any, into out: inout String) {
        switch v {
        case let o as JSONObject:
            out += "{"
            var first = true
            for k in o.keys() {
                if !first { out += "," }
                first = false
                writeString(k, into: &out)
                out += ":"
                write(o.opt(k)!, into: &out)
            }
            out += "}"
        case let a as JSONArray:
            out += "["
            for (i, x) in a.values.enumerated() {
                if i > 0 { out += "," }
                write(x, into: &out)
            }
            out += "]"
        case is JSONNull: out += "null"
        case let s as String: writeString(s, into: &out)
        case let b as Bool: out += b ? "true" : "false"
        case let i as Int64: out += String(i)
        case let d as Double: out += numberText(d)
        default: writeString(String(describing: v), into: &out)
        }
    }

    static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if u.value < 0x20 || u.value == 0x2028 || u.value == 0x2029 {
                    out += String(format: "\\u%04x", u.value)
                } else {
                    out.unicodeScalars.append(u)
                }
            }
        }
        out += "\""
    }

    static func deepCopy(_ v: Any) -> Any {
        if let o = v as? JSONObject { return o.copy() }
        if let a = v as? JSONArray { return a.copy() }
        return v
    }

    static func foundation(_ v: Any) -> Any {
        switch v {
        case let o as JSONObject: return o.toFoundation()
        case let a as JSONArray: return a.toFoundation()
        case is JSONNull: return NSNull()
        default: return v
        }
    }
}

// MARK: - 파싱

enum JSONParser {
    static func parse(_ text: String) throws -> Any {
        var p = Parser(Array(text.utf8))
        p.skipWhitespace()
        let v = try p.value(depth: 0)
        p.skipWhitespace()
        if p.i < p.b.count { throw JSONException("JSON 뒤에 남은 글자가 있습니다") }
        return v
    }

    struct Parser {
        let b: [UInt8]
        var i = 0
        init(_ b: [UInt8]) { self.b = b }

        mutating func skipWhitespace() {
            while i < b.count, b[i] == 0x20 || b[i] == 0x0A || b[i] == 0x0D || b[i] == 0x09 { i += 1 }
        }

        mutating func value(depth: Int) throws -> Any {
            guard depth < 512 else { throw JSONException("JSON이 너무 깊습니다") }
            skipWhitespace()
            guard i < b.count else { throw JSONException("JSON이 끝났습니다") }
            switch b[i] {
            case UInt8(ascii: "{"):
                i += 1
                let o = JSONObject()
                skipWhitespace()
                if i < b.count, b[i] == UInt8(ascii: "}") { i += 1; return o }
                while true {
                    skipWhitespace()
                    guard i < b.count, b[i] == UInt8(ascii: "\"") else { throw JSONException("키가 필요합니다") }
                    let k = try string()
                    skipWhitespace()
                    guard i < b.count, b[i] == UInt8(ascii: ":") else { throw JSONException(": 가 필요합니다") }
                    i += 1
                    let v = try value(depth: depth + 1)
                    o.put(k, v)
                    skipWhitespace()
                    guard i < b.count else { throw JSONException("객체가 끝나지 않았습니다") }
                    if b[i] == UInt8(ascii: ",") { i += 1; continue }
                    if b[i] == UInt8(ascii: "}") { i += 1; return o }
                    throw JSONException(", 나 } 가 필요합니다")
                }
            case UInt8(ascii: "["):
                i += 1
                let a = JSONArray()
                skipWhitespace()
                if i < b.count, b[i] == UInt8(ascii: "]") { i += 1; return a }
                while true {
                    a.put(try value(depth: depth + 1))
                    skipWhitespace()
                    guard i < b.count else { throw JSONException("배열이 끝나지 않았습니다") }
                    if b[i] == UInt8(ascii: ",") { i += 1; continue }
                    if b[i] == UInt8(ascii: "]") { i += 1; return a }
                    throw JSONException(", 나 ] 가 필요합니다")
                }
            case UInt8(ascii: "\""):
                return try string()
            case UInt8(ascii: "t"):
                try literal("true"); return true
            case UInt8(ascii: "f"):
                try literal("false"); return false
            case UInt8(ascii: "n"):
                try literal("null"); return JSONNull.shared
            default:
                return try number()
            }
        }

        mutating func literal(_ word: String) throws {
            let w = Array(word.utf8)
            guard i + w.count <= b.count, Array(b[i..<i + w.count]) == w else { throw JSONException("알 수 없는 값") }
            i += w.count
        }

        mutating func number() throws -> Any {
            let start = i
            var isDouble = false
            if i < b.count, b[i] == UInt8(ascii: "-") { i += 1 }
            while i < b.count {
                let c = b[i]
                if c >= 0x30 && c <= 0x39 { i += 1; continue }
                if c == UInt8(ascii: ".") || c == UInt8(ascii: "e") || c == UInt8(ascii: "E") || c == UInt8(ascii: "+") || c == UInt8(ascii: "-") {
                    isDouble = true; i += 1; continue
                }
                break
            }
            guard i > start, let s = String(bytes: b[start..<i], encoding: .ascii) else { throw JSONException("알 수 없는 값") }
            if !isDouble, let n = Int64(s) { return n }
            guard let d = Double(s) else { throw JSONException("숫자가 아닙니다: \(s)") }
            return d
        }

        mutating func hex4() throws -> UInt32 {
            guard i + 4 <= b.count, let s = String(bytes: b[i..<i + 4], encoding: .ascii), let v = UInt32(s, radix: 16) else {
                throw JSONException("잘못된 \\u 이스케이프")
            }
            i += 4
            return v
        }

        mutating func string() throws -> String {
            i += 1 // "
            var bytes: [UInt8] = []
            while true {
                guard i < b.count else { throw JSONException("문자열이 끝나지 않았습니다") }
                let c = b[i]
                if c == UInt8(ascii: "\"") { i += 1; break }
                if c == UInt8(ascii: "\\") {
                    i += 1
                    guard i < b.count else { throw JSONException("문자열이 끝나지 않았습니다") }
                    let e = b[i]
                    i += 1
                    switch e {
                    case UInt8(ascii: "\""): bytes.append(0x22)
                    case UInt8(ascii: "\\"): bytes.append(0x5C)
                    case UInt8(ascii: "/"): bytes.append(0x2F)
                    case UInt8(ascii: "b"): bytes.append(0x08)
                    case UInt8(ascii: "f"): bytes.append(0x0C)
                    case UInt8(ascii: "n"): bytes.append(0x0A)
                    case UInt8(ascii: "r"): bytes.append(0x0D)
                    case UInt8(ascii: "t"): bytes.append(0x09)
                    case UInt8(ascii: "u"):
                        var cp = try hex4()
                        if (0xD800...0xDBFF).contains(cp), i + 6 <= b.count, b[i] == UInt8(ascii: "\\"), b[i + 1] == UInt8(ascii: "u") {
                            let save = i
                            i += 2
                            let lo = try hex4()
                            if (0xDC00...0xDFFF).contains(lo) {
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00)
                            } else {
                                i = save
                            }
                        }
                        let scalar = Unicode.Scalar(cp) ?? "\u{FFFD}"
                        bytes.append(contentsOf: Array(String(Character(scalar)).utf8))
                    default:
                        throw JSONException("잘못된 이스케이프")
                    }
                    continue
                }
                bytes.append(c)
                i += 1
            }
            return String(decoding: bytes, as: UTF8.self)
        }
    }
}

// MARK: - Kotlin `x ?: JSONObject.NULL`

/// `값 ?? JSONObject.NULL` (Kotlin `?: JSONObject.NULL`): 값이 없으면 JSON null.
public func ?? <T>(lhs: T?, rhs: @autoclosure () -> JSONNull) -> Any {
    if let v = lhs { return v }
    return rhs()
}

/// 조건이 참이면 JSON null, 아니면 [value] (Kotlin `if (cond) JSONObject.NULL else value`).
public func jsonNullIf(_ cond: Bool, _ value: @autoclosure () -> Any?) -> Any {
    if cond { return JSONNull.shared }
    if let v = value() { return v }
    return JSONNull.shared
}
