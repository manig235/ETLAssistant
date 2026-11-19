package kr.snu.etlassistant.chat

/**
 * 답변이 만들어지는 동안 "지금 무엇을 하는 중인지"를 한 줄로 알려 주는 상태 판정. 안드로이드 API에 의존하지 않는다.
 *
 * 도구 호출을 대화 목록에 칩으로 하나하나 남기는 대신, 진행 중인 일을 `조회하는 중…`, `다운로드하는 중…`처럼 묶어서 보여 주고
 * 끝나면 사라지게 한다. 각 메서드는 **지금 보여 줄 문구**(없으면 null)를 돌려준다.
 * 메인 스레드에서만 쓴다.
 */
class ActivityTracker {

    private val running = LinkedHashMap<String, String>() // 도구 호출 ID → 도구 이름(시작 순서 유지)
    private var streaming = false
    private var active = false

    /** 질문을 보낸 직후. */
    fun turnStarted(): String? {
        active = true
        streaming = false
        running.clear()
        return current()
    }

    fun toolStarted(id: String, tool: String): String? {
        if (!active) return null
        running[id] = tool
        streaming = false
        return current()
    }

    fun toolFinished(id: String): String? {
        running.remove(id)
        return current()
    }

    /** 답변 글이 흐르기 시작/계속됨. 글이 보이는 동안에는 상태 줄을 숨긴다. */
    fun textDelta(): String? {
        streaming = true
        return current()
    }

    /** 답변 한 덩어리가 끝남. 모델이 다음 일을 고민하는 중이면 다시 "생각하는 중"이 된다. */
    fun messageDone(): String? {
        streaming = false
        return current()
    }

    fun turnEnded(): String? {
        active = false
        streaming = false
        running.clear()
        return null
    }

    fun current(): String? = when {
        !active -> null
        running.isNotEmpty() -> forTool(running.values.last())
        streaming -> null
        else -> THINKING
    }

    companion object {
        const val THINKING = "생각하는 중…"

        /** 도구 이름을 사용자가 이해하는 큰 갈래로 묶는다. */
        fun forTool(tool: String): String = when (tool) {
            "etl_download" -> "다운로드하는 중…"
            "etl_read_file", "etl_render_pages" -> "파일 읽는 중…"
            "etl_digest", "etl_courses", "etl_assignment", "etl_search_files", "etl_verify_submission", "etl_get" -> "조회하는 중…"
            else -> "처리하는 중…"
        }
    }
}
