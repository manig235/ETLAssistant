import Foundation

/// Server-Sent Events 메시지 하나(새로 만듦).
public struct SSEMessage: Sendable, Hashable {
    public var event: String?
    public var data: String
    public var id: String?
    public init(event: String? = nil, data: String, id: String? = nil) {
        self.event = event
        self.data = data
        self.id = id
    }
}

/// 줄 단위 SSE 해석기(새로 만듦, 순수). WHATWG 규칙: `data:` 여러 줄은 `\n`으로 잇고, 빈 줄에서 내보내고,
/// `:`로 시작하는 줄은 주석, 콜론 뒤 공백 하나는 뗀다. 끝에 빈 줄 없이 끝나면 `finish()`가 남은 것을 내보낸다.
public struct SSEParser: Sendable {
    private var event: String?
    private var data: [String] = []
    private var id: String?
    private var hasData = false

    public init() {}

    /// 한 줄(개행 문자 제외)을 넣는다. 메시지가 끝났으면 돌려준다.
    public mutating func push(line: String) -> SSEMessage? {
        if line.isEmpty { return dispatch() }
        if line.hasPrefix(":") { return nil }
        let field: Substring
        var value: Substring
        if let colon = line.firstIndex(of: ":") {
            field = line[..<colon]
            value = line[line.index(after: colon)...]
            if value.hasPrefix(" ") { value = value.dropFirst() }
        } else {
            field = line[...]
            value = ""
        }
        switch field {
        case "event": event = String(value)
        case "data": data.append(String(value)); hasData = true
        case "id": if !value.contains("\0") { id = String(value) }
        default: break // retry 등은 쓰지 않는다
        }
        return nil
    }

    /// 스트림이 끝났을 때 남은 메시지.
    public mutating func finish() -> SSEMessage? { dispatch() }

    private mutating func dispatch() -> SSEMessage? {
        defer { event = nil; data = []; hasData = false }
        guard hasData else { return nil }
        return SSEMessage(event: event, data: data.joined(separator: "\n"), id: id)
    }

    /// 글 전체를 한 번에 해석한다(테스트·녹화 재생용).
    public static func parse(_ text: String) -> [SSEMessage] {
        var p = SSEParser()
        var out: [SSEMessage] = []
        var splitter = SSELineSplitter()
        for line in splitter.push(Data(text.utf8)) { if let m = p.push(line: line) { out.append(m) } }
        if let rest = splitter.finish(), let m = p.push(line: rest) { out.append(m) }
        if let m = p.finish() { out.append(m) }
        return out
    }
}

/// 바이트를 줄로 나눈다(`\n`, `\r\n`, `\r`). 빈 줄도 그대로 내보낸다(SSE 메시지 경계라서
/// `URLSession.AsyncBytes.lines`처럼 빈 줄을 버리는 방식은 쓰지 않는다).
public struct SSELineSplitter: Sendable {
    private var buffer: [UInt8] = []
    private var lastWasCR = false

    public init() {}

    public mutating func push(_ byte: UInt8) -> String? {
        if byte == 0x0A {
            if lastWasCR { lastWasCR = false; return nil }
            return flush()
        }
        lastWasCR = false
        if byte == 0x0D {
            lastWasCR = true
            return flush()
        }
        buffer.append(byte)
        return nil
    }

    public mutating func push(_ data: Data) -> [String] {
        var out: [String] = []
        for b in data { if let l = push(b) { out.append(l) } }
        return out
    }

    /// 끝에 개행 없이 남은 줄.
    public mutating func finish() -> String? { buffer.isEmpty ? nil : flush() }

    private mutating func flush() -> String {
        defer { buffer.removeAll(keepingCapacity: true) }
        return String(decoding: buffer, as: UTF8.self)
    }
}
