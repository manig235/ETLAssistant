import Foundation

/// Responses 스트리밍 이벤트 하나를 해석한 값(새로 만듦, 순수). 이벤트 종류는 `data` JSON의 `type`(없으면 SSE `event:`)으로 정한다.
public enum ResponsesStreamEvent: Sendable, Hashable {
    /// `response.created` / `response.in_progress`
    case created(response: ChatJSON)
    /// `response.output_item.added`
    case outputItemAdded(item: ChatJSON, outputIndex: Int)
    /// `response.output_item.done`
    case outputItemDone(item: ChatJSON, outputIndex: Int)
    /// `response.output_text.delta` (거절 `response.refusal.delta`도 글로 본다)
    case textDelta(itemId: String, delta: String)
    /// `response.output_text.done`
    case textDone(itemId: String, text: String)
    /// `response.function_call_arguments.delta`
    case functionArgumentsDelta(itemId: String, delta: String)
    /// `response.function_call_arguments.done`
    case functionArgumentsDone(itemId: String, arguments: String)
    /// `response.reasoning_summary_text.delta` / `response.reasoning_text.delta`
    case reasoningDelta(itemId: String, delta: String)
    /// `response.completed`
    case completed(response: ChatJSON)
    /// `response.incomplete`
    case incomplete(response: ChatJSON)
    /// `response.failed`
    case failed(response: ChatJSON)
    /// `error`
    case error(code: String?, message: String, param: String?)
    /// 쓰지 않는 이벤트(이름만).
    case other(type: String)

    /// SSE 메시지 하나를 해석한다. 데이터가 JSON이 아니면(`[DONE]` 등) nil.
    public static func decode(_ m: SSEMessage) -> ResponsesStreamEvent? {
        guard let json = try? ChatJSON(parsing: m.data), json.object != nil else { return nil }
        let type = json["type"]?.string ?? m.event ?? ""
        func s(_ k: String) -> String { json[k]?.string ?? "" }
        let outputIndex = json["output_index"]?.int ?? 0
        switch type {
        case "response.created", "response.in_progress", "response.queued":
            return .created(response: json["response"] ?? .null)
        case "response.output_item.added":
            return .outputItemAdded(item: json["item"] ?? .null, outputIndex: outputIndex)
        case "response.output_item.done":
            return .outputItemDone(item: json["item"] ?? .null, outputIndex: outputIndex)
        case "response.output_text.delta", "response.refusal.delta":
            return .textDelta(itemId: s("item_id"), delta: s("delta"))
        case "response.output_text.done":
            return .textDone(itemId: s("item_id"), text: s("text"))
        case "response.refusal.done":
            return .textDone(itemId: s("item_id"), text: s("refusal"))
        case "response.function_call_arguments.delta":
            return .functionArgumentsDelta(itemId: s("item_id"), delta: s("delta"))
        case "response.function_call_arguments.done":
            return .functionArgumentsDone(itemId: s("item_id"), arguments: s("arguments"))
        case "response.reasoning_summary_text.delta", "response.reasoning_text.delta":
            return .reasoningDelta(itemId: s("item_id"), delta: s("delta"))
        case "response.completed":
            return .completed(response: json["response"] ?? .null)
        case "response.incomplete":
            return .incomplete(response: json["response"] ?? .null)
        case "response.failed":
            return .failed(response: json["response"] ?? .null)
        case "error":
            // 문서마다 최상위(code/message/param) 또는 `error` 객체 안에 둔다. 둘 다 받는다.
            let e = json["error"]?.object != nil ? json["error"]! : json
            return .error(code: e["code"]?.string, message: e["message"]?.string ?? "", param: e["param"]?.string)
        default:
            return .other(type: type)
        }
    }
}

/// 모델 호출 한 번(회차)의 스트림을 모으는 상태(새로 만듦, 순수). 엔진이 이벤트를 하나씩 넣고, 끝나면 결과를 읽는다.
public struct ResponsesRoundAccumulator: Sendable {
    public private(set) var responseId: String?
    /// `output_item.done`으로 받은 항목(출력 순서).
    public private(set) var doneItems: [Int: ChatJSON] = [:]
    /// 진행 중인 함수 호출(`output_item.added`) → 인자 조각을 모은다. 키: 항목 ID.
    public private(set) var pendingCalls: [String: (name: String, arguments: String)] = [:]
    /// 메시지 항목 ID → 지금까지 받은 글.
    public private(set) var texts: [String: String] = [:]
    public private(set) var finalResponse: ChatJSON?
    public private(set) var terminal: Terminal?

    public enum Terminal: Sendable, Hashable {
        case completed
        case incomplete(reason: String?)
        case failed(code: String?, message: String, param: String?)
    }

    public init() {}

    public var isFinished: Bool { terminal != nil }

    public mutating func apply(_ e: ResponsesStreamEvent) {
        switch e {
        case .created(let r):
            if let id = r["id"]?.string { responseId = id }
        case .outputItemAdded(let item, _):
            if item["type"]?.string == "function_call", let id = item["id"]?.string {
                pendingCalls[id] = (item["name"]?.string ?? "", item["arguments"]?.string ?? "")
            }
        case .outputItemDone(let item, let index):
            doneItems[index] = item
            if let id = item["id"]?.string { pendingCalls[id] = nil }
        case .textDelta(let id, let d):
            texts[id, default: ""] += d
        case .textDone(let id, let t):
            texts[id] = t
        case .functionArgumentsDelta(let id, let d):
            if pendingCalls[id] != nil { pendingCalls[id]!.arguments += d }
        case .functionArgumentsDone(let id, let a):
            if pendingCalls[id] != nil { pendingCalls[id]!.arguments = a }
        case .reasoningDelta:
            break
        case .completed(let r):
            take(r)
            terminal = .completed
        case .incomplete(let r):
            take(r)
            terminal = .incomplete(reason: r["incomplete_details"]?["reason"]?.string)
        case .failed(let r):
            take(r)
            let err = r["error"]
            terminal = .failed(code: err?["code"]?.string ?? "unknown_error", message: err?["message"]?.string ?? "", param: err?["param"]?.string)
        case .error(let code, let message, let param):
            if terminal == nil { terminal = .failed(code: code, message: message, param: param) }
        case .other:
            break
        }
    }

    private mutating func take(_ r: ChatJSON) {
        if let id = r["id"]?.string { responseId = id }
        if r.object != nil { finalResponse = r }
    }

    public var usage: ResponsesUsage? { ResponsesUsage(finalResponse?["usage"]) }

    /// 이번 회차의 출력 항목. 최종 응답의 `output`이 있으면 그것, 없으면 `output_item.done`으로 받은 것.
    public var outputItems: [ChatJSON] {
        if let out = finalResponse?["output"]?.array, !out.isEmpty { return out }
        return doneItems.keys.sorted().compactMap { doneItems[$0] }
    }

    /// 출력 항목 중 함수 호출.
    public static func functionCalls(in items: [ChatJSON]) -> [ToolCall] {
        items.compactMap { item in
            guard item["type"]?.string == "function_call", let callId = item["call_id"]?.string, let name = item["name"]?.string else { return nil }
            return ToolCall(callId: callId, name: name, namespace: item["namespace"]?.string,
                            arguments: item["arguments"]?.string ?? "", itemId: item["id"]?.string)
        }
    }

    /// 메시지 항목의 글(`output_text`와 `refusal`을 잇는다).
    public static func messageText(_ item: ChatJSON) -> String {
        guard item["type"]?.string == "message" else { return "" }
        return (item["content"]?.array ?? []).compactMap { part -> String? in
            switch part["type"]?.string {
            case "output_text": return part["text"]?.string
            case "refusal": return part["refusal"]?.string
            default: return nil
            }
        }.joined()
    }
}
