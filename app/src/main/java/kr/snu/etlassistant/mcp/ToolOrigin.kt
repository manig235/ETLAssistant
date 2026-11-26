package kr.snu.etlassistant.mcp

import org.json.JSONObject

/**
 * 지금 도구 스레드가 처리하는 MCP `tools/call`을 낸 Codex 스레드(2026-11-27, 대화마다 따로 답하기).
 *
 * Codex(rust-v0.170.0)는 `tools/call`의 `params._meta.threadId`에 그 호출을 낸 스레드 ID를 넣는다(`mcp_tool_call.rs`
 * `with_mcp_tool_call_ids_meta`, code mode 호출도 같은 경로). [McpServer]가 도구를 실행하는 동안 이 값을 스레드 지역으로 두면
 * 도구가 알리는 일([kr.snu.etlassistant.etl.EtlEvents])과 쓰기 승인 요청이 어느 대화의 것인지 알 수 있다.
 * 값은 라우팅에만 쓰고 권한 판단에는 쓰지 않는다(MCP 요청 자체는 실행마다 바뀌는 비밀값으로 이미 인증된다).
 */
object ToolOrigin {
    private val current = ThreadLocal<String?>()
    private val SAFE = Regex("[A-Za-z0-9_-]{1,80}")

    fun current(): String? = current.get()

    fun <T> with(threadId: String?, block: () -> T): T {
        val prev = current.get()
        current.set(threadId)
        try {
            return block()
        } finally {
            current.set(prev)
        }
    }

    /** `params._meta`에서 스레드 ID를 꺼낸다. 모양이 이상하면 null. */
    fun fromMeta(meta: JSONObject?): String? {
        val v = meta?.opt("threadId") as? String ?: return null
        return v.takeIf { SAFE.matches(it) }
    }
}
