import ChatKit
import Foundation

/// 비서 도구와 지침을 채팅 화면에 넘기는 계약. AssistantKit(EtlTools·CodexConfig 이식)이 이것을 구현해 `AppState`에 끼운다.
///
/// - `instructions`: AGENTS.md(지침). `ChatController`가 끝에 말투 지침(`ChatSettings.toneInstructions()`)을 붙여 보낸다.
/// - `toolDefinitions`: Responses API 함수 도구 정의.
/// - `run(name:arguments:)`: 도구 하나 실행. 사용자가 멈추면 Task가 취소되므로 오래 걸리는 도구는 `Task.isCancelled`를 본다.
///   받은 파일·쪽 이미지는 `EtlEvents.fileSaved`/`pagesRendered`로 알리면 채팅에 카드로 붙는다(`ChatController`가 구독).
protocol AssistantToolbox: Sendable {
    var instructions: String { get }
    var toolDefinitions: [FunctionTool] { get }
    /// 도구를 싣는 방식(기본: ChatKit이 인증 방식에 맞게 고름).
    var toolPackaging: ToolPackaging { get }
    func run(name: String, arguments: String) async -> ToolOutput
}

extension AssistantToolbox {
    var toolPackaging: ToolPackaging { .automatic }
}

/// 도구 없는 기본값(AssistantKit을 끼우기 전). 지침도 비어 있어 모델이 일반 대화만 한다.
struct EmptyAssistantToolbox: AssistantToolbox {
    var instructions: String { "" }
    var toolDefinitions: [FunctionTool] { [] }
    func run(name: String, arguments: String) async -> ToolOutput {
        ToolOutput(text: "{\"error\":\"이 도구는 아직 앱에 연결되지 않았어요: \(name)\"}")
    }
}
