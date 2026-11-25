import Foundation

// 새로 만듦: OpenAI Responses API(https://api.openai.com/v1/responses)를 앱이 직접 부를 때 쓰는 값 타입들.

/// 모델에게 알려 줄 함수 도구 하나. `parameters`는 JSON Schema.
public struct FunctionTool: Sendable, Hashable {
    public var name: String
    public var description: String
    public var parameters: ChatJSON
    /// true면 스키마를 엄격히 지키게 한다(`strict`). nil이면 보내지 않는다.
    public var strict: Bool?

    public init(name: String, description: String, parameters: ChatJSON, strict: Bool? = nil) {
        self.name = name
        self.description = description
        self.parameters = parameters
        self.strict = strict
    }

    /// `{"type":"function","name",…}`
    public var json: ChatJSON {
        var o: [String: ChatJSON] = ["type": "function", "name": .string(name), "description": .string(description), "parameters": parameters]
        if let strict { o["strict"] = .bool(strict) }
        return .object(o)
    }
}

/// 도구를 요청에 싣는 방식.
public enum ToolPackaging: Sendable, Hashable {
    /// API 키면 `plain`, ChatGPT 로그인이면 `namespace`(ChatGPT 플랜 사용은 함수 도구를 namespace로 묶어야 한다고 문서에 적혀 있다).
    case automatic
    /// 최상위 `tools`에 함수 도구를 그대로 넣는다.
    case plain
    /// `{"type":"namespace","name","description","tools":[…]}` 하나로 묶는다.
    case namespace(name: String, description: String)

    public static let defaultNamespace = ToolPackaging.namespace(name: "youngmin", description: "영민이 앱 도구: eTL 과목·과제·공지·파일 조회, 내 마감, 시간표")
}

/// 모델이 부른 함수 하나.
public struct ToolCall: Sendable, Hashable {
    public var callId: String
    public var name: String
    /// namespace로 묶은 도구면 그 이름.
    public var namespace: String?
    /// JSON 문자열.
    public var arguments: String
    /// 출력 항목 ID(`fc_…`).
    public var itemId: String?

    public init(callId: String, name: String, namespace: String? = nil, arguments: String, itemId: String? = nil) {
        self.callId = callId
        self.name = name
        self.namespace = namespace
        self.arguments = arguments
        self.itemId = itemId
    }

    /// `arguments`를 JSON으로(망가졌으면 nil).
    public var argumentsJSON: ChatJSON? { try? ChatJSON(parsing: arguments) }
}

/// 이미지 입력(`input_image`, data URL로 보낸다).
public struct InputImage: Sendable, Hashable {
    public var data: Data
    public var mime: String
    /// `auto`/`low`/`high`. nil이면 보내지 않는다(서버 기본 `auto`).
    public var detail: String?
    public init(data: Data, mime: String, detail: String? = nil) {
        self.data = data
        self.mime = mime
        self.detail = detail
    }
    public var dataURL: String { "data:\(mime);base64,\(data.base64EncodedString())" }
    public var json: ChatJSON {
        var o: [String: ChatJSON] = ["type": "input_image", "image_url": .string(dataURL)]
        if let detail { o["detail"] = .string(detail) }
        return .object(o)
    }
}

/// 파일 입력(`input_file`, `file_data`에 data URL).
public struct InputFile: Sendable, Hashable {
    public var filename: String
    public var data: Data
    public var mime: String
    public init(filename: String, data: Data, mime: String) {
        self.filename = filename
        self.data = data
        self.mime = mime
    }
    public var json: ChatJSON {
        ["type": "input_file", "filename": .string(filename), "file_data": .string("data:\(mime);base64,\(data.base64EncodedString())")]
    }
}

/// 도구 실행 결과. 글만 있으면 `output`에 문자열로, 이미지·파일이 있으면 내용 배열로 보낸다.
public struct ToolOutput: Sendable, Hashable {
    public var text: String
    public var images: [InputImage]
    public var files: [InputFile]
    public init(text: String, images: [InputImage] = [], files: [InputFile] = []) {
        self.text = text
        self.images = images
        self.files = files
    }

    public var outputJSON: ChatJSON {
        if images.isEmpty && files.isEmpty { return .string(text) }
        var parts: [ChatJSON] = []
        if !text.isEmpty { parts.append(["type": "input_text", "text": .string(text)]) }
        parts += images.map(\.json)
        parts += files.map(\.json)
        return .array(parts)
    }
}

/// 입력 항목 만들기.
public enum ResponsesInput {
    /// 사용자 메시지: 글 + 사진 + 파일. 글 파일(.txt 등)은 `texts`로 따로 붙일 수 있다(`input_text`).
    public static func userMessage(_ text: String, images: [InputImage] = [], files: [InputFile] = [], texts: [String] = []) -> ChatJSON {
        var content: [ChatJSON] = [["type": "input_text", "text": .string(text)]]
        content += texts.map { ["type": "input_text", "text": .string($0)] }
        content += images.map(\.json)
        content += files.map(\.json)
        return ["type": "message", "role": "user", "content": .array(content)]
    }

    /// 개발자 메시지(대화 중간에 넣는 지침). 시스템 역할 항목은 ChatGPT 플랜 사용에서 거절되므로 쓰지 않는다.
    public static func developerMessage(_ text: String) -> ChatJSON {
        ["type": "message", "role": "developer", "content": [["type": "input_text", "text": .string(text)]]]
    }

    public static func functionCallOutput(callId: String, output: ToolOutput) -> ChatJSON {
        ["type": "function_call_output", "call_id": .string(callId), "output": output.outputJSON]
    }
}

/// 토큰 사용량(`usage`).
public struct ResponsesUsage: Sendable, Hashable {
    public var inputTokens: Int
    public var outputTokens: Int
    public var totalTokens: Int
    public var cachedTokens: Int
    public var reasoningTokens: Int

    public init(inputTokens: Int = 0, outputTokens: Int = 0, totalTokens: Int = 0, cachedTokens: Int = 0, reasoningTokens: Int = 0) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.totalTokens = totalTokens
        self.cachedTokens = cachedTokens
        self.reasoningTokens = reasoningTokens
    }

    public init?(_ json: ChatJSON?) {
        guard let u = json, u.object != nil else { return nil }
        self.init(inputTokens: u["input_tokens"]?.int ?? 0, outputTokens: u["output_tokens"]?.int ?? 0,
                  totalTokens: u["total_tokens"]?.int ?? 0,
                  cachedTokens: u["input_tokens_details"]?["cached_tokens"]?.int ?? 0,
                  reasoningTokens: u["output_tokens_details"]?["reasoning_tokens"]?.int ?? 0)
    }

    public static func + (a: ResponsesUsage, b: ResponsesUsage) -> ResponsesUsage {
        ResponsesUsage(inputTokens: a.inputTokens + b.inputTokens, outputTokens: a.outputTokens + b.outputTokens,
                       totalTokens: a.totalTokens + b.totalTokens, cachedTokens: a.cachedTokens + b.cachedTokens,
                       reasoningTokens: a.reasoningTokens + b.reasoningTokens)
    }
}

/// Responses 호출 실패. `status`·`code`·`requestId`는 원문 그대로 보존한다(문서의 진단 규칙).
public struct ResponsesError: Error, Sendable, Hashable, CustomStringConvertible {
    public enum Kind: String, Sendable, Hashable {
        /// 스트림이 열리기 전 HTTP 오류.
        case http
        /// 스트림 안의 `error`/`response.failed`.
        case stream
        /// 연결 실패·끊김.
        case transport
        /// 토큰을 얻지 못함(로그인 필요 등).
        case auth
        /// 도구를 너무 많이 불러 멈춤.
        case tooManyRounds
        /// 응답을 해석하지 못함.
        case invalidResponse
    }

    public var kind: Kind
    public var status: Int?
    public var code: String?
    public var message: String
    public var param: String?
    public var requestId: String?

    public init(_ kind: Kind, status: Int? = nil, code: String? = nil, message: String, param: String? = nil, requestId: String? = nil) {
        self.kind = kind
        self.status = status
        self.code = code
        self.message = message
        self.param = param
        self.requestId = requestId
    }

    public var description: String {
        "ResponsesError(\(kind.rawValue), status: \(status.map(String.init) ?? "-"), code: \(code ?? "-"), requestId: \(requestId ?? "-"))"
    }

    /// 잠시 뒤 다시 하면 될 수 있는 오류인가(문서: 503 계열은 제한된 재시도).
    public var isRetryable: Bool {
        if kind == .transport { return true }
        switch code {
        case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable", "server_error", "rate_limit_exceeded": return true
        default: break
        }
        if let s = status { return s == 500 || s == 502 || s == 503 || s == 504 }
        return false
    }

    /// 다시 로그인해야 할 수 있는 오류인가.
    public var needsSignIn: Bool { kind == .auth || status == 401 || code == "subscription_sharing_invalid_user" }

    /// 사용자에게 보여 줄 한국어 문구.
    public var userMessage: String {
        switch code {
        case "subscription_sharing_user_not_eligible":
            return "이 ChatGPT 계정으로는 영민이에서 ChatGPT 플랜을 쓸 수 없어요. 설정에서 OpenAI API 키를 쓰도록 바꿀 수 있어요."
        case "subscription_sharing_usage_limit_exceeded":
            return "ChatGPT 플랜 사용 한도에 도달했어요. ChatGPT 설정 → 사용량에서 확인해 주세요."
        case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable":
            return "ChatGPT 플랜 사용을 지금 확인할 수 없어요. 잠시 뒤 다시 시도해 주세요."
        case "subscription_sharing_unsupported_capability":
            return "ChatGPT 플랜에서 지원하지 않는 기능이 요청에 들어 있어요\(param.map { "(\($0))" } ?? ""). 첨부나 모델 설정을 바꿔 보세요."
        case "subscription_sharing_route_not_supported":
            return "ChatGPT 플랜으로는 이 요청을 보낼 수 없어요."
        case "subscription_sharing_invalid_user":
            return "ChatGPT 로그인을 확인하지 못했어요. 계속되면 설정에서 다시 로그인해 주세요."
        case "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context":
            return "ChatGPT 플랜 사용 권한이 없어요. 설정에서 다시 로그인하고 플랜 사용을 허용해 주세요."
        case "invalid_api_key":
            return "OpenAI API 키가 올바르지 않아요. 설정에서 다시 입력해 주세요."
        case "insufficient_quota":
            return "OpenAI API 사용 한도나 잔액이 부족해요."
        case "context_length_exceeded":
            return "대화가 너무 길어요. 새 대화에서 다시 물어봐 주세요."
        default: break
        }
        switch kind {
        case .auth: return message.isEmpty ? "먼저 로그인해야 해요" : message
        case .tooManyRounds: return "도구를 너무 많이 불러서 멈췄어요. 질문을 나눠서 다시 물어봐 주세요."
        case .transport: return "네트워크에 연결하지 못했어요. 연결을 확인하고 다시 시도해 주세요."
        case .invalidResponse: return "답변을 해석하지 못했어요"
        case .http, .stream: break
        }
        switch status {
        case 401: return "로그인이 만료됐거나 권한이 없어요. 설정에서 다시 로그인해 주세요."
        case 403: return "이 요청은 허용되지 않아요" + (message.isEmpty ? "" : ": \(message)")
        case 429: return "요청이 너무 많아요. 잠시 뒤 다시 시도해 주세요."
        case 503: return "서비스를 잠시 쓸 수 없어요. 잠시 뒤 다시 시도해 주세요."
        default: return message.isEmpty ? "답변을 만들지 못했어요" : message
        }
    }
}

/// 한 턴(사용자 메시지 하나 → 최종 답변)의 설정.
public struct TurnConfig: Sendable {
    public var model: String
    /// nil이면 모델 기본값(`reasoning`을 보내지 않는다).
    public var effort: String?
    /// 지침(AGENTS.md + 말투). `instructions`로 보낸다.
    public var instructions: String?
    public var tools: [FunctionTool]
    public var toolPackaging: ToolPackaging
    /// 모델 호출 횟수 상한(도구 → 다시 모델). 마지막 회차에는 `tool_choice: "none"`으로 답을 받는다.
    public var maxRounds: Int
    /// 추론 요약(`reasoning.summary: "auto"`)을 받을지. API 키는 조직 인증이 필요할 수 있어 기본 꺼짐.
    public var reasoningSummary: Bool
    public var parallelToolCalls: Bool
    /// 한 회차에 여러 도구를 동시에 실행할지(기본: 차례로).
    public var parallelToolExecution: Bool

    public init(model: String, effort: String? = nil, instructions: String? = nil, tools: [FunctionTool] = [],
                toolPackaging: ToolPackaging = .automatic, maxRounds: Int = 12, reasoningSummary: Bool = false,
                parallelToolCalls: Bool = true, parallelToolExecution: Bool = false) {
        self.model = model
        self.effort = effort
        self.instructions = instructions
        self.tools = tools
        self.toolPackaging = toolPackaging
        self.maxRounds = max(1, maxRounds)
        self.reasoningSummary = reasoningSummary
        self.parallelToolCalls = parallelToolCalls
        self.parallelToolExecution = parallelToolExecution
    }
}

/// 턴이 끝난 모양.
public enum TurnStatus: Sendable, Hashable {
    case completed
    /// `response.incomplete`(예: `max_output_tokens`).
    case incomplete(reason: String?)
    /// 사용자가 멈춤.
    case interrupted
    case failed(ResponsesError)
}

public struct TurnResult: Sendable {
    public var status: TurnStatus
    /// 마지막 응답 ID.
    public var responseId: String?
    /// 이번 턴의 모든 회차 사용량 합.
    public var usage: ResponsesUsage?
    /// 다음 턴에 보낼 전체 항목 목록(이전 기록 + 이번 입력 + 모델 출력 + 도구 결과). `ConversationThreadStore`에 저장한다.
    public var history: [ChatJSON]
    /// 마지막 회차 답변 글.
    public var finalText: String

    public init(status: TurnStatus, responseId: String?, usage: ResponsesUsage?, history: [ChatJSON], finalText: String) {
        self.status = status
        self.responseId = responseId
        self.usage = usage
        self.history = history
        self.finalText = finalText
    }
}

/// 엔진이 화면에 알리는 일(턴마다 `.turnEnded`가 꼭 한 번, 마지막에 온다).
public enum ResponsesEvent: Sendable {
    /// 상태 한 줄(`생각하는 중…`, `조회하는 중…`), nil이면 숨김. 바뀔 때만 온다.
    case activity(String?)
    /// 답변 글 조각. `ChatModel.appendAssistant(itemId, delta)`.
    case textDelta(itemId: String, delta: String)
    /// 답변 한 덩어리 확정. `ChatModel.finishAssistant(itemId, text)`.
    case textDone(itemId: String, text: String)
    /// 추론 요약 조각(`reasoningSummary`를 켰을 때).
    case reasoningDelta(itemId: String, delta: String)
    /// 도구 실행 시작(`label`은 `ActivityTracker.forTool` 문구).
    case toolStarted(ToolCall, label: String)
    case toolFinished(ToolCall)
    /// 모델 호출 한 번이 끝남.
    case roundCompleted(responseId: String?, usage: ResponsesUsage?)
    case turnEnded(TurnResult)
}
