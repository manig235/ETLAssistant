import Foundation
import Security
@testable import ChatKit

/// 네트워크 대신 쓰는 HTTP 계층. 스트리밍 응답은 차례로 꺼내 쓰고, 한 번에 받는 요청은 URL로 고른다.
final class StubTransport: HTTPTransport, @unchecked Sendable {
    struct StreamReply {
        var status: Int = 200
        var headers: [String: String] = ["x-request-id": "req_test"]
        var body: String
        /// 본문을 다 보낸 뒤 끝내지 않고 기다린다(중단 시험용).
        var hang = false
    }

    private let lock = NSLock()
    private var streams: [StreamReply]
    private var dataHandler: @Sendable (URLRequest) -> (Int, Data)
    private(set) var streamRequests: [URLRequest] = []
    private(set) var dataRequests: [URLRequest] = []

    init(streams: [StreamReply] = [], data: @escaping @Sendable (URLRequest) -> (Int, Data) = { _ in (404, Data()) }) {
        self.streams = streams
        self.dataHandler = data
    }

    func setData(_ h: @escaping @Sendable (URLRequest) -> (Int, Data)) { lock.withLock { dataHandler = h } }

    var streamBodies: [ChatJSON] {
        lock.withLock { streamRequests.compactMap { $0.httpBody.flatMap { try? ChatJSON(parsing: $0) } } }
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let h: @Sendable (URLRequest) -> (Int, Data) = lock.withLock { dataRequests.append(request); return dataHandler }
        let (status, data) = h(request)
        return (data, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])!)
    }

    func lines(for request: URLRequest) async throws -> HTTPLineResponse {
        let reply: StreamReply? = lock.withLock {
            streamRequests.append(request)
            return streams.isEmpty ? nil : streams.removeFirst()
        }
        guard let reply else { throw URLError(.notConnectedToInternet) }
        var splitter = SSELineSplitter()
        var lines = splitter.push(Data(reply.body.utf8))
        if let rest = splitter.finish() { lines.append(rest) }
        let hang = reply.hang
        let stream = AsyncThrowingStream<String, Error> { c in
            let task = Task {
                for l in lines { c.yield(l) }
                if hang { try? await Task.sleep(nanoseconds: 10_000_000_000) }
                c.finish()
            }
            c.onTermination = { _ in task.cancel() }
        }
        return HTTPLineResponse(status: reply.status, headers: reply.headers, lines: stream)
    }
}

/// 고정 토큰을 주는 인증. 강제 새로 고침 횟수를 센다.
final class StubAuth: AuthProvider, @unchecked Sendable {
    let kind: AuthKind
    private let lock = NSLock()
    private var _forced = 0
    var forced: Int { lock.withLock { _forced } }
    init(_ kind: AuthKind = .apiKey) { self.kind = kind }
    func bearerToken(forceRefresh: Bool) async throws -> String {
        lock.withLock {
            if forceRefresh { _forced += 1 }
            return forceRefresh ? "tok-refreshed" : "tok-1"
        }
    }
}

/// SSE 녹화 만들기: (이벤트 이름, data JSON) 목록 → 글.
func sse(_ events: [(String, String)]) -> String {
    events.map { "event: \($0.0)\ndata: \($0.1)\n\n" }.joined()
}

enum Fixtures {
    static let usage = #"{"input_tokens":120,"input_tokens_details":{"cached_tokens":64},"output_tokens":30,"output_tokens_details":{"reasoning_tokens":12},"total_tokens":150}"#
    static let reasoningItem = #"{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"gAAAA-enc"}"#

    static func messageItem(_ id: String, _ text: String) -> String {
        #"{"id":"\#(id)","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"\#(text)","annotations":[]}]}"#
    }

    /// 글 답변 한 회차(추론 항목 + 메시지).
    static func textStream(id: String = "resp_1", msg: String = "msg_1", parts: [String] = ["이번 주 ", "마감은 2개예요."]) -> String {
        let full = parts.joined()
        var ev: [(String, String)] = [
            ("response.created", #"{"type":"response.created","sequence_number":0,"response":{"id":"\#(id)","object":"response","status":"in_progress","output":[]}}"#),
            ("response.in_progress", #"{"type":"response.in_progress","sequence_number":1,"response":{"id":"\#(id)","status":"in_progress"}}"#),
            ("response.output_item.added", #"{"type":"response.output_item.added","sequence_number":2,"output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[]}}"#),
            ("response.output_item.done", #"{"type":"response.output_item.done","sequence_number":3,"output_index":0,"item":\#(reasoningItem)}"#),
            ("response.output_item.added", #"{"type":"response.output_item.added","sequence_number":4,"output_index":1,"item":{"id":"\#(msg)","type":"message","status":"in_progress","role":"assistant","content":[]}}"#),
            ("response.content_part.added", #"{"type":"response.content_part.added","sequence_number":5,"item_id":"\#(msg)","output_index":1,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}"#),
        ]
        for (i, p) in parts.enumerated() {
            ev.append(("response.output_text.delta", #"{"type":"response.output_text.delta","sequence_number":\#(6 + i),"item_id":"\#(msg)","output_index":1,"content_index":0,"delta":"\#(p)"}"#))
        }
        ev += [
            ("response.output_text.done", #"{"type":"response.output_text.done","sequence_number":20,"item_id":"\#(msg)","output_index":1,"content_index":0,"text":"\#(full)"}"#),
            ("response.output_item.done", #"{"type":"response.output_item.done","sequence_number":21,"output_index":1,"item":\#(messageItem(msg, full))}"#),
            ("response.completed", #"{"type":"response.completed","sequence_number":22,"response":{"id":"\#(id)","status":"completed","output":[\#(reasoningItem),\#(messageItem(msg, full))],"usage":\#(usage)}}"#),
        ]
        return sse(ev)
    }

    static func callItem(_ call: String, _ name: String, _ args: String, namespace: String? = nil) -> String {
        let ns = namespace.map { #","namespace":"\#($0)""# } ?? ""
        let escaped = args.replacingOccurrences(of: "\"", with: "\\\"")
        return #"{"id":"fc_\#(call)","type":"function_call","status":"completed","call_id":"\#(call)","name":"\#(name)"\#(ns),"arguments":"\#(escaped)"}"#
    }

    /// 함수 호출 한 회차. 인자는 조각으로 흘러온다.
    static func toolStream(id: String = "resp_t", calls: [(String, String, String)], namespace: String? = nil) -> String {
        var ev: [(String, String)] = [
            ("response.created", #"{"type":"response.created","sequence_number":0,"response":{"id":"\#(id)","status":"in_progress","output":[]}}"#),
        ]
        var items: [String] = []
        for (i, c) in calls.enumerated() {
            let (call, name, args) = c
            let ns = namespace.map { #","namespace":"\#($0)""# } ?? ""
            ev.append(("response.output_item.added", #"{"type":"response.output_item.added","output_index":\#(i),"item":{"id":"fc_\#(call)","type":"function_call","status":"in_progress","call_id":"\#(call)","name":"\#(name)"\#(ns),"arguments":""}}"#))
            let half = args.count / 2
            for piece in [String(args.prefix(half)), String(args.dropFirst(half))] {
                let e = piece.replacingOccurrences(of: "\"", with: "\\\"")
                ev.append(("response.function_call_arguments.delta", #"{"type":"response.function_call_arguments.delta","item_id":"fc_\#(call)","output_index":\#(i),"delta":"\#(e)"}"#))
            }
            let e = args.replacingOccurrences(of: "\"", with: "\\\"")
            ev.append(("response.function_call_arguments.done", #"{"type":"response.function_call_arguments.done","item_id":"fc_\#(call)","output_index":\#(i),"arguments":"\#(e)"}"#))
            let item = callItem(call, name, args, namespace: namespace)
            ev.append(("response.output_item.done", #"{"type":"response.output_item.done","output_index":\#(i),"item":\#(item)}"#))
            items.append(item)
        }
        ev.append(("response.completed", #"{"type":"response.completed","response":{"id":"\#(id)","status":"completed","output":[\#(items.joined(separator: ","))],"usage":\#(usage)}}"#))
        return sse(ev)
    }

    static func failedStream(code: String, message: String) -> String {
        sse([
            ("response.created", #"{"type":"response.created","response":{"id":"resp_f","status":"in_progress","output":[]}}"#),
            ("response.failed", #"{"type":"response.failed","response":{"id":"resp_f","status":"failed","error":{"code":"\#(code)","message":"\#(message)"},"output":[]}}"#),
        ])
    }
}

/// 시험용 RSA 키로 RS256 JWT를 만든다.
struct TestSigner {
    let privateKey: SecKey
    let kid = "test-key-1"

    init() {
        let attrs: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 2048]
        privateKey = SecKeyCreateRandomKey(attrs as CFDictionary, nil)!
    }

    var publicDER: Data { SecKeyCopyExternalRepresentation(SecKeyCopyPublicKey(privateKey)!, nil)! as Data }

    /// PKCS#1 DER에서 (n, e)를 꺼낸다.
    var modulusAndExponent: (Data, Data) {
        let b = [UInt8](publicDER)
        var i = 0
        func readLen() -> Int {
            let first = Int(b[i]); i += 1
            if first < 0x80 { return first }
            var n = 0
            for _ in 0..<(first & 0x7F) { n = (n << 8) | Int(b[i]); i += 1 }
            return n
        }
        precondition(b[i] == 0x30); i += 1; _ = readLen()
        precondition(b[i] == 0x02); i += 1; let nl = readLen(); let n = Data(b[i..<(i + nl)]); i += nl
        precondition(b[i] == 0x02); i += 1; let el = readLen(); let e = Data(b[i..<(i + el)])
        return (n, e)
    }

    var jwks: ChatJSON {
        let (n, e) = modulusAndExponent
        return ["keys": [["kty": "RSA", "kid": .string(kid), "use": "sig", "alg": "RS256",
                          "n": .string(Base64URL.encode(n)), "e": .string(Base64URL.encode(e))]]]
    }

    func sign(_ claims: ChatJSON, alg: String = "RS256", kid: String? = nil) -> String {
        let header: ChatJSON = ["alg": .string(alg), "typ": "JWT", "kid": .string(kid ?? self.kid)]
        let input = Base64URL.encode(header.serialized()) + "." + Base64URL.encode(claims.serialized())
        let sig = SecKeyCreateSignature(privateKey, .rsaSignatureMessagePKCS1v15SHA256, Data(input.utf8) as CFData, nil)! as Data
        return input + "." + Base64URL.encode(sig)
    }
}

/// 서명 없는 JWT(접근 토큰 흉내).
func unsignedJWT(_ claims: ChatJSON) -> String {
    let header: ChatJSON = ["alg": "none"]
    return Base64URL.encode(header.serialized()) + "." + Base64URL.encode(claims.serialized()) + ".sig"
}

/// 이벤트 스트림을 끝까지 모은다.
func collect(_ s: AsyncStream<ResponsesEvent>) async -> [ResponsesEvent] {
    var out: [ResponsesEvent] = []
    for await e in s { out.append(e) }
    return out
}

func turnResult(_ events: [ResponsesEvent]) -> TurnResult? {
    for e in events { if case .turnEnded(let r) = e { return r } }
    return nil
}
