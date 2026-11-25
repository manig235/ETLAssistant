import Foundation

/// ChatKit의 JSON 값(새로 만듦). Responses API 요청·응답 항목, 도구 스키마, 대화 기록 저장에 쓴다.
/// ETLKit의 `JSONObject`/`JSONArray`(org.json 흉내)와 이름이 겹치지 않게 따로 둔다.
public enum ChatJSON: Sendable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([ChatJSON])
    case object([String: ChatJSON])

    // MARK: 읽기

    public subscript(key: String) -> ChatJSON? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public subscript(index: Int) -> ChatJSON? {
        if case .array(let a) = self, index >= 0, index < a.count { return a[index] }
        return nil
    }

    public var string: String? { if case .string(let s) = self { return s }; return nil }
    public var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    public var double: Double? { if case .number(let d) = self { return d }; return nil }
    public var int: Int? {
        if case .number(let d) = self, d.isFinite, d == d.rounded(), abs(d) < 9.0e15 { return Int(d) }
        return nil
    }
    public var array: [ChatJSON]? { if case .array(let a) = self { return a }; return nil }
    public var object: [String: ChatJSON]? { if case .object(let o) = self { return o }; return nil }
    public var isNull: Bool { if case .null = self { return true }; return false }

    /// 객체에 키를 넣거나(값이 nil이면 지운) 새 값을 돌려준다.
    public func setting(_ key: String, _ value: ChatJSON?) -> ChatJSON {
        guard case .object(var o) = self else { return self }
        o[key] = value
        return .object(o)
    }

    // MARK: 변환

    public init(any: Any?) {
        switch any {
        case nil, is NSNull: self = .null
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { self = .bool(n.boolValue) } else { self = .number(n.doubleValue) }
        case let s as String: self = .string(s)
        case let a as [Any]: self = .array(a.map { ChatJSON(any: $0) })
        case let o as [String: Any]: self = .object(o.mapValues { ChatJSON(any: $0) })
        default: self = .null
        }
    }

    public var anyValue: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let b): return b
        case .number(let d):
            if d.isFinite, d == d.rounded(), abs(d) < 9.0e15 { return Int64(d) }
            return d
        case .string(let s): return s
        case .array(let a): return a.map(\.anyValue)
        case .object(let o): return o.mapValues(\.anyValue)
        }
    }

    public init(parsing data: Data) throws {
        let any = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        self = ChatJSON(any: any)
    }

    public init(parsing text: String) throws { try self.init(parsing: Data(text.utf8)) }

    /// 키 순서를 정렬해 직렬화한다(테스트에서 비교하기 쉽게).
    public func serialized() -> Data {
        (try? JSONSerialization.data(withJSONObject: anyValue, options: [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed])) ?? Data("null".utf8)
    }

    public var jsonString: String { String(decoding: serialized(), as: UTF8.self) }
}

extension ChatJSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral,
    ExpressibleByBooleanLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: ChatJSON...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, ChatJSON)...) {
        var o: [String: ChatJSON] = [:]
        for (k, v) in elements { o[k] = v }
        self = .object(o)
    }
    public init(nilLiteral: ()) { self = .null }
}

extension ChatJSON: Codable {
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let d = try? c.decode(Double.self) { self = .number(d) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([ChatJSON].self) { self = .array(a) }
        else { self = .object(try c.decode([String: ChatJSON].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let d): if let i = int { try c.encode(i) } else { try c.encode(d) }
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }
}

// MARK: - org.json의 opt* 규칙(저장 파일 읽기용, 내부)

/// `JSONSerialization` 결과(`[String: Any]`)를 org.json `optString`/`optLong`/`optBoolean`처럼 너그럽게 읽는다.
enum OrgJSON {
    static func isNull(_ o: [String: Any], _ key: String) -> Bool {
        guard let v = o[key] else { return true }
        return v is NSNull
    }

    static func optString(_ o: [String: Any], _ key: String, _ fallback: String = "") -> String {
        guard let v = o[key] else { return fallback }
        switch v {
        case let s as String: return s
        case is NSNull: return "null"
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            if n.doubleValue == n.doubleValue.rounded(), abs(n.doubleValue) < 9.0e15 { return String(n.int64Value) }
            return n.stringValue
        default: return fallback
        }
    }

    static func optLong(_ o: [String: Any], _ key: String, _ fallback: Int64 = 0) -> Int64 {
        switch o[key] {
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return fallback }
            return n.int64Value
        case let s as String:
            if let i = Int64(s) { return i }
            if let d = Double(s), d.isFinite { return Int64(d) }
            return fallback
        default: return fallback
        }
    }

    static func optBoolean(_ o: [String: Any], _ key: String, _ fallback: Bool) -> Bool {
        switch o[key] {
        case let n as NSNumber where CFGetTypeID(n) == CFBooleanGetTypeID(): return n.boolValue
        case let s as String:
            if s.lowercased() == "true" { return true }
            if s.lowercased() == "false" { return false }
            return fallback
        default: return fallback
        }
    }

    static func parseArray(_ data: Data) -> [Any]? {
        (try? JSONSerialization.jsonObject(with: data, options: [])) as? [Any]
    }

    static func write(_ value: Any) -> Data? {
        try? JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes])
    }
}
