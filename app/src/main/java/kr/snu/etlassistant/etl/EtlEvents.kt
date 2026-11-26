package kr.snu.etlassistant.etl

import java.io.File

/**
 * eTL 도구가 앱(채팅 화면)에 알리는 일. 도구는 앱 프로세스 안의 MCP 서버에서 돌기 때문에, 받은 파일을
 * Codex 프로토콜을 거치지 않고 여기로 바로 알린다. 리스너는 도구 스레드에서 불리므로 화면 쪽에서 메인 스레드로 넘긴다.
 */
object EtlEvents {
    data class SavedFile(
        val name: String,
        val mime: String,
        val size: Long,
        /** 앱 작업 폴더 안의 파일. */
        val file: File,
        /** 폰 Download/etl/ 사본의 content URI. 없으면 null. */
        val phoneUri: String?,
        /** 폰 사본 위치 표시. */
        val shownPath: String?,
        /** 내용을 확인하기 위한 자료면 채팅에서 접어 둔다. */
        val referenceOnly: Boolean = false,
    )

    /**
     * [origin]은 이 일을 한 도구 호출을 낸 Codex 스레드([kr.snu.etlassistant.mcp.ToolOrigin], 모르면 null).
     * 여러 대화가 동시에 답할 수 있어 받은 파일과 바뀐 데이터 알림을 그 대화에 붙이는 데 쓴다.
     */
    interface Listener {
        fun onFilesSaved(files: List<SavedFile>, pagePreview: Boolean, origin: String?)
        /** 비서가 폰 안의 데이터(내 마감, 보강·휴강)를 바꿨다. 채팅에 한 줄로 보여 사용자가 바로 알게 한다. */
        fun onNotice(text: String, origin: String?) {}
    }

    @Volatile var listener: Listener? = null

    fun fileSaved(f: SavedFile) {
        runCatching { listener?.onFilesSaved(listOf(f), pagePreview = false, origin = kr.snu.etlassistant.mcp.ToolOrigin.current()) }
    }

    fun notice(text: String) {
        runCatching { listener?.onNotice(text, kr.snu.etlassistant.mcp.ToolOrigin.current()) }
    }

    fun pagesRendered(files: List<SavedFile>) {
        if (files.isNotEmpty()) runCatching { listener?.onFilesSaved(files, pagePreview = true, origin = kr.snu.etlassistant.mcp.ToolOrigin.current()) }
    }
}
