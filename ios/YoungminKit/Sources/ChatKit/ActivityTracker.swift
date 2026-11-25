import Foundation

/// 답변이 만들어지는 동안 "지금 무엇을 하는 중인지"를 한 줄로 알려 주는 상태 판정(Kotlin `ActivityTracker`).
///
/// 도구 호출을 대화 목록에 남기는 대신 진행 중인 일을 `조회하는 중…`, `다운로드하는 중…`처럼 묶어서 보여 주고
/// 끝나면 사라지게 한다. 각 메서드는 **지금 보여 줄 문구**(없으면 nil)를 돌려준다. 한 곳(엔진 또는 메인 액터)에서만 쓴다.
public final class ActivityTracker {

    private var running: [(id: String, tool: String)] = [] // 도구 호출 ID → 도구 이름(시작 순서 유지)
    private var streaming = false
    private var active = false

    public init() {}

    /// 질문을 보낸 직후.
    @discardableResult public func turnStarted() -> String? {
        active = true
        streaming = false
        running.removeAll()
        return current()
    }

    @discardableResult public func toolStarted(_ id: String, _ tool: String) -> String? {
        if !active { return nil }
        if let i = running.firstIndex(where: { $0.id == id }) { running[i].tool = tool } else { running.append((id, tool)) }
        streaming = false
        return current()
    }

    @discardableResult public func toolFinished(_ id: String) -> String? {
        running.removeAll { $0.id == id }
        return current()
    }

    /// 답변 글이 흐르기 시작/계속됨. 글이 보이는 동안에는 상태 줄을 숨긴다.
    @discardableResult public func textDelta() -> String? {
        streaming = true
        return current()
    }

    /// 답변 한 덩어리가 끝남. 모델이 다음 일을 고민하는 중이면 다시 "생각하는 중"이 된다.
    @discardableResult public func messageDone() -> String? {
        streaming = false
        return current()
    }

    @discardableResult public func turnEnded() -> String? {
        active = false
        streaming = false
        running.removeAll()
        return nil
    }

    public func current() -> String? {
        if !active { return nil }
        if let last = running.last { return Self.forTool(last.tool) }
        if streaming { return nil }
        return Self.THINKING
    }

    public static let THINKING = "생각하는 중…"

    /// 도구 이름을 사용자가 이해하는 큰 갈래로 묶는다.
    public static func forTool(_ tool: String) -> String {
        switch tool {
        case "etl_download": return "다운로드하는 중…"
        case "etl_read_file", "etl_render_pages": return "파일 읽는 중…"
        case "etl_digest", "etl_courses", "etl_assignment", "etl_search_files", "etl_verify_submission", "etl_get": return "조회하는 중…"
        default: return "처리하는 중…"
        }
    }
}
