import Foundation

/// Responses 요청 본문 만들기(새로 만듦, 순수).
public enum ResponsesRequest {
    public static let endpoint = URL(string: "https://api.openai.com/v1/responses")!

    /// 요청 본문. ChatGPT 플랜 사용에서 거절되는 필드(`max_output_tokens`, `temperature`, `metadata`, `user`,
    /// `previous_response_id`, `background`, `truncation` …)는 보내지 않는다. `store: false`, `stream: true` 고정.
    public static func body(config: TurnConfig, input: [ChatJSON], auth: AuthKind, forceAnswer: Bool) -> ChatJSON {
        var o: [String: ChatJSON] = [
            "model": .string(config.model),
            "input": .array(input),
            "store": false,
            "stream": true,
            "include": ["reasoning.encrypted_content"],
        ]
        if let i = config.instructions, !i.isEmpty { o["instructions"] = .string(i) }
        var reasoning: [String: ChatJSON] = [:]
        if let e = config.effort { reasoning["effort"] = .string(e) }
        if config.reasoningSummary { reasoning["summary"] = "auto" }
        if !reasoning.isEmpty { o["reasoning"] = .object(reasoning) }
        if !config.tools.isEmpty {
            o["tools"] = .array(tools(config.tools, packaging: config.toolPackaging, auth: auth))
            o["tool_choice"] = forceAnswer ? "none" : "auto"
            o["parallel_tool_calls"] = .bool(config.parallelToolCalls)
        }
        return .object(o)
    }

    public static func tools(_ tools: [FunctionTool], packaging: ToolPackaging, auth: AuthKind) -> [ChatJSON] {
        var p = packaging
        if case .automatic = p { p = auth == .chatGPT ? ToolPackaging.defaultNamespace : .plain }
        switch p {
        case .namespace(let name, let description):
            return [["type": "namespace", "name": .string(name), "description": .string(description), "tools": .array(tools.map(\.json))]]
        default:
            return tools.map(\.json)
        }
    }

    public static func urlRequest(body: ChatJSON, token: String, endpoint: URL = endpoint) -> URLRequest {
        var r = URLRequest(url: endpoint)
        r.httpMethod = "POST"
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        r.httpBody = body.serialized()
        r.timeoutInterval = 300
        return r
    }

    /// 스트림이 열리기 전 오류 본문을 해석한다. `{"error":{…}}` 또는 `{"detail":"…"}`.
    public static func httpError(status: Int, body: Data, requestId: String?) -> ResponsesError {
        let json = try? ChatJSON(parsing: body)
        if let e = json?["error"], e.object != nil {
            return ResponsesError(.http, status: status, code: e["code"]?.string ?? e["type"]?.string,
                                  message: e["message"]?.string ?? "", param: e["param"]?.string, requestId: requestId)
        }
        if let d = json?["detail"]?.string {
            return ResponsesError(.http, status: status, message: d, requestId: requestId)
        }
        return ResponsesError(.http, status: status, message: "HTTP \(status)", requestId: requestId)
    }
}

/// 대화 항목 목록 정리(새로 만듦, 순수). 저장·다음 요청 전에 쓴다.
public enum ConversationHistory {
    public static let interruptedOutput = "사용자가 답변을 멈춰서 실행하지 않았어요."

    /// 다음 요청에 그대로 보낼 수 있게 다듬는다.
    /// - 짝(`function_call_output`)이 없는 `function_call`에는 "실행하지 않음" 결과를 붙인다.
    /// - 뒤따르는 항목 없이 끝난 `reasoning` 항목은 뺀다(혼자 남으면 API가 거절한다).
    public static func seal(_ items: [ChatJSON]) -> [ChatJSON] {
        let answered = Set(items.compactMap { $0["type"]?.string == "function_call_output" ? $0["call_id"]?.string : nil })
        var out: [ChatJSON] = []
        for item in items {
            out.append(item)
            if item["type"]?.string == "function_call", let id = item["call_id"]?.string, !answered.contains(id) {
                out.append(ResponsesInput.functionCallOutput(callId: id, output: ToolOutput(text: interruptedOutput)))
            }
        }
        var result: [ChatJSON] = []
        for (i, item) in out.enumerated() {
            if item["type"]?.string == "reasoning" {
                let next = i + 1 < out.count ? out[i + 1]["type"]?.string : nil
                if next == nil || next == "reasoning" || next == "function_call_output" { continue }
                if next == "message" && out[i + 1]["role"]?.string == "user" { continue }
            }
            result.append(item)
        }
        return result
    }

    /// 사용자 메시지에서 시작하는 턴의 시작 위치들.
    static func turnStarts(_ items: [ChatJSON]) -> [Int] {
        items.indices.filter { items[$0]["role"]?.string == "user" && (items[$0]["type"]?.string ?? "message") == "message" }
    }

    /// 마지막 `keepLastTurns`턴보다 오래된 사진·파일(data URL)을 짧은 글로 바꾼다(다시 보낼 때 용량을 줄인다).
    public static func compactAttachments(_ items: [ChatJSON], keepLastTurns: Int = 2) -> [ChatJSON] {
        let starts = turnStarts(items)
        guard starts.count > keepLastTurns else { return items }
        let cutoff = starts[starts.count - keepLastTurns]
        return items.enumerated().map { i, item in i < cutoff ? stripAttachments(item) : item }
    }

    private static func stripAttachments(_ item: ChatJSON) -> ChatJSON {
        func strip(_ parts: [ChatJSON]) -> [ChatJSON] {
            parts.map { p in
                switch p["type"]?.string {
                case "input_image": return ["type": "input_text", "text": "[이전에 보낸 사진]"]
                case "input_file": return ["type": "input_text", "text": .string("[이전에 보낸 파일: \(p["filename"]?.string ?? "")]")]
                default: return p
                }
            }
        }
        if let content = item["content"]?.array { return item.setting("content", .array(strip(content))) }
        if item["type"]?.string == "function_call_output", let output = item["output"]?.array { return item.setting("output", .array(strip(output))) }
        return item
    }

    /// 직렬화 크기가 `maxBytes`를 넘으면 먼저 사진·쪽 이미지를 마지막 턴에서만 남기고 글로 바꾼다.
    /// 그래도 넘으면 오래된 턴부터 통째로 뺀다(마지막 턴은 남긴다).
    /// 이미지가 큰 턴(예: PDF 쪽 6장)을 턴째 빼면 그 턴의 답변 글까지 사라져 다음 질문에서 "앞의 요약이 안 보인다"가 되므로 순서가 중요하다.
    public static func trim(_ items: [ChatJSON], maxBytes: Int = 600_000) -> [ChatJSON] {
        var current = compactAttachments(items)
        if ChatJSON.array(current).serialized().count > maxBytes {
            current = compactAttachments(current, keepLastTurns: 1)
        }
        while ChatJSON.array(current).serialized().count > maxBytes {
            let starts = turnStarts(current)
            guard starts.count >= 2 else { break }
            current = Array(current[starts[1]...])
        }
        return current
    }
}
