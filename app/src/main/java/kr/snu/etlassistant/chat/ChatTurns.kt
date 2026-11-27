package kr.snu.etlassistant.chat

/**
 * 대화마다 따로 도는 답변(턴)의 상태(2026-11-27). 안드로이드 API에 의존하지 않는다.
 *
 * Codex app-server는 한 프로세스에서 여러 스레드를 동시에 돌리고, 알림마다 `threadId`가 붙는다. 그래서 답변 중 표시는
 * 앱 전체가 아니라 대화 하나에 묶는다. 대화 A가 답하는 동안 대화 B에서 보내도 되고, 동시에 도는 답변 수에는 제한이 없다.
 *
 * - [start]는 보내는 순간(스레드를 아직 모를 수 있다), [bindThread]는 `thread/start|resume` 뒤, [setTurnId]는 `turn/start` 응답 뒤.
 * - Codex 알림은 [chatForThread]로 대화를 찾는다. eTL 도구가 알리는 일(받은 파일, 바꾼 데이터)은 MCP 요청의 `_meta.threadId`로
 *   [routeTool]이 정한다.
 * - 여러 스레드(세션 스레드, 도구 스레드, 메인)에서 부르므로 모든 메서드를 잠근다.
 */
class TurnRegistry {

    class Turn(val chatId: String, val seq: Long, val modelAuto: Boolean) {
        @Volatile var threadId: String? = null
        @Volatile var turnId: String? = null
        /** `turn/start` 응답 전에 멈춤을 누름: 응답을 받는 대로 멈춘다. */
        @Volatile var interruptRequested = false
        /** 답변 중에 대화를 지움: 대화에 아무것도 쓰지 않고, 끝나면 스레드를 지운다. */
        @Volatile var deleted = false
    }

    /** 도구가 알린 일을 어느 대화에 붙일지. */
    sealed class ToolRoute {
        data class One(val chatId: String) : ToolRoute()
        /** 도구 요청에 스레드가 없는데 여러 대화가 답하는 중. [all]은 시작 순서, [latest]는 마지막에 시작한 대화. */
        data class Ambiguous(val all: List<String>, val latest: String) : ToolRoute()
        /** 답하는 대화가 없다(개발자 도구의 시험 실행 등). */
        object None : ToolRoute()
    }

    private val turns = LinkedHashMap<String, Turn>()
    private val threadToChat = HashMap<String, String>()
    private var seq = 0L

    /** 보낸다. 이미 이 대화가 답하는 중이면 null. */
    @Synchronized
    fun start(chatId: String, modelAuto: Boolean = true): Turn? {
        if (turns.containsKey(chatId)) return null
        return Turn(chatId, ++seq, modelAuto).also { turns[chatId] = it }
    }

    /** 이 대화가 쓰는 Codex 스레드. 대화가 답하는 중이 아니어도 기억한다(재개한 스레드의 늦은 알림을 찾을 때). */
    @Synchronized
    fun bindThread(chatId: String, threadId: String) {
        threadToChat.entries.removeAll { it.value == chatId && it.key != threadId }
        threadToChat[threadId] = chatId
        turns[chatId]?.threadId = threadId
    }

    /** `turn/start` 응답. 그사이 답변이 끝났거나(빠른 실패) 지워졌으면 null. */
    @Synchronized
    fun setTurnId(chatId: String, turnId: String?): Turn? {
        val t = turns[chatId] ?: return null
        t.turnId = turnId
        return t
    }

    /** 같은 사용자 질문의 모델 재시도. 멈춤·삭제·새 질문으로 교체된 뒤에는 시작하지 않는다. */
    @Synchronized
    fun retry(chatId: String, expected: Turn): Boolean {
        if (turns[chatId] !== expected || expected.interruptRequested || expected.deleted) return false
        expected.turnId = null
        return true
    }

    @Synchronized
    fun get(chatId: String): Turn? = turns[chatId]

    /**
     * 멈출 대상(스레드, 턴). `turn/start` 응답 전이면 멈춤을 기록하고 null(응답을 받는 쪽이 [Turn.interruptRequested]를 보고 멈춘다).
     * [setTurnId]와 같은 잠금 안에서 판단하므로 둘 중 한쪽은 반드시 멈춤을 본다.
     */
    @Synchronized
    fun interruptTarget(chatId: String): Pair<String, String>? {
        val t = turns[chatId] ?: return null
        val thread = t.threadId
        val turn = t.turnId
        if (thread == null || turn == null) {
            t.interruptRequested = true
            return null
        }
        return thread to turn
    }

    @Synchronized
    fun isBusy(chatId: String): Boolean = turns.containsKey(chatId)

    /** 답하는 대화(시작 순서). */
    @Synchronized
    fun running(): Set<String> = LinkedHashSet(turns.keys)

    @Synchronized
    fun isEmpty(): Boolean = turns.isEmpty()

    /** 답하는 대화의 스레드(지우기를 미룰 때). */
    @Synchronized
    fun runningThreads(): Set<String> = turns.values.mapNotNull { it.threadId }.toSet()

    @Synchronized
    fun chatForThread(threadId: String?): String? = threadId?.let { threadToChat[it] }

    /** 이 스레드의 알림이 지금 답하는 대화에 쓸 것인가. 지운 대화는 아니다. */
    @Synchronized
    fun liveTurnForThread(threadId: String?): Turn? {
        val chat = chatForThread(threadId) ?: return null
        return turns[chat]?.takeIf { !it.deleted }
    }

    /** 답변이 끝났다. 이미 끝났으면 null(같은 끝을 두 번 처리하지 않는다). */
    @Synchronized
    fun finish(chatId: String): Turn? = turns.remove(chatId)

    /** 세션이 멈춤: 모든 답변이 끝난다. */
    @Synchronized
    fun clear(): List<Turn> = turns.values.toList().also { turns.clear() }

    /** 대화를 지움: 스레드 기억도 지운다. */
    @Synchronized
    fun forget(chatId: String) {
        threadToChat.entries.removeAll { it.value == chatId }
    }

    /**
     * 도구 호출을 낸 대화. Codex는 MCP `tools/call`의 `_meta.threadId`에 스레드를 넣는다(rust-v0.170.0 `mcp_tool_call.rs`).
     * - 스레드가 있으면 그 스레드의 대화가 답하는 중일 때만 그 대화(지운 대화는 None).
     * - 스레드가 없으면(예전 판, 다른 클라이언트) 답하는 대화가 하나일 때 그 대화, 여럿이면 [ToolRoute.Ambiguous].
     */
    @Synchronized
    fun routeTool(threadId: String?): ToolRoute {
        if (threadId != null) {
            val chat = threadToChat[threadId] ?: return ToolRoute.None
            val t = turns[chat] ?: return ToolRoute.None
            return if (t.deleted) ToolRoute.None else ToolRoute.One(chat)
        }
        val live = turns.values.filter { !it.deleted }
        return when (live.size) {
            0 -> ToolRoute.None
            1 -> ToolRoute.One(live[0].chatId)
            else -> ToolRoute.Ambiguous(live.map { it.chatId }, live.maxBy { it.seq }.chatId)
        }
    }
}

/** 답변이 끝났을 때 사용자에게 어떻게 알릴지. */
enum class FinishNotice {
    /** 알리지 않는다(지금 보는 대화, 직접 멈춤, 지운 대화). */
    NONE,
    /** 채팅 화면 아래 막대 "‘…’ 답변이 끝났어요 · 보기". */
    BAR,
    /** 채팅 화면이 보이지 않음: 기기 알림(누르면 그 대화). */
    NOTIFICATION,
}

/** 턴이 끝난 모양. */
enum class TurnEnd { COMPLETED, FAILED, INTERRUPTED }

object TurnLogic {

    /**
     * - 직접 멈췄거나(INTERRUPTED) 지운 대화면 알리지 않는다.
     * - 채팅 화면이 보이지 않으면(다른 앱, 설정 화면 등) 보던 대화였어도 기기 알림으로 알린다.
     * - 채팅 화면이 보이면 지금 보는 대화는 알리지 않고, 다른 대화면 아래 막대로 알린다.
     */
    fun finishNotice(chatId: String, visibleChatId: String, chatScreenVisible: Boolean, end: TurnEnd, deleted: Boolean): FinishNotice = when {
        deleted || end == TurnEnd.INTERRUPTED -> FinishNotice.NONE
        !chatScreenVisible -> FinishNotice.NOTIFICATION
        chatId == visibleChatId -> FinishNotice.NONE
        else -> FinishNotice.BAR
    }

    fun finishText(title: String?, ok: Boolean): String {
        val name = title?.takeIf { it.isNotBlank() } ?: "새 대화"
        return if (ok) "‘$name’ 답변이 끝났어요" else "‘$name’ 답변을 마치지 못했어요"
    }

    /** 승인 요청을 이 화면에서 어떻게 보일지. */
    sealed class ApprovalUi {
        object None : ApprovalUi()
        /** 지금 대화의 요청: 확인 창을 띄운다. */
        data class Dialog(val requestId: String) : ApprovalUi()
        /** 다른 대화의 요청만 있다: 위쪽 배너 "다른 대화에서 확인을 기다려요"(누르면 그 대화로). */
        data class Elsewhere(val chatId: String) : ApprovalUi()
    }

    const val APPROVAL_ELSEWHERE = "다른 대화에서 확인을 기다려요"

    /**
     * [pending]은 (요청 ID, 그 요청을 낸 대화) 들어온 순서. 대화를 모르는 요청(null)은 예전처럼 지금 대화에서 묻는다.
     * 지금 대화의 요청이 먼저다. 다른 대화로 옮겨 간다고 요청이 취소되지는 않는다(취소는 화면을 떠날 때, 턴이 끝날 때, 90초).
     */
    fun approvalUi(pending: List<Pair<String, String?>>, visibleChatId: String): ApprovalUi {
        pending.firstOrNull { it.second == null || it.second == visibleChatId }?.let { return ApprovalUi.Dialog(it.first) }
        val other = pending.firstOrNull { it.second != null } ?: return ApprovalUi.None
        return ApprovalUi.Elsewhere(other.second!!)
    }
}
