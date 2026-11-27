package kr.snu.etlassistant.etl

/**
 * [reviewed]가 거짓인 범위(파일 만들기)는 추가를 확인 창 없이 바로 한다(2026-11-28). PDF는 사용자가 요청한 결과물이고
 * 새 파일을 하나 더 만들 뿐 기존 일정·파일을 바꾸거나 지우지 않는다(이슈 #8 원안도 "끔이면 거절"만이었다).
 * 끔은 그대로 도구를 숨기고 거절한다. 일정을 바꾸는 범위(내 마감·보강·휴강·캘린더)는 계속 확인을 거친다.
 */
enum class WriteScope(val label: String, val reviewed: Boolean = true) {
    DEADLINES("내 마감·시험"), TIMETABLE("시간표 보강·휴강"), CALENDAR("캘린더 일정"), FILES("파일 만들기", reviewed = false), SCORES("성적 통계")
}

enum class WriteKind { ADD, UPDATE, DELETE }

enum class WriteMode(val label: String) {
    OFF("끔(읽기만)"), ASK("매번 확인"), AUTO_ADD("추가는 자동");

    companion object {
        fun parse(value: String?): WriteMode = entries.firstOrNull { it.name == value } ?: ASK
    }
}

object WritePolicy {
    enum class Decision { DENY, ASK, AUTO }
    const val MAX_AUTO_PER_TURN = 5

    /**
     * 확인 창·채팅 화면·자동 한도와 무관하게 바로 해도 되는 쓰기인가(끔이 아닌 [WriteScope.reviewed]=false 범위의 추가만).
     * 수정·삭제는 범위와 무관하게 항상 확인 대상이다.
     */
    fun direct(scope: WriteScope, kind: WriteKind, mode: WriteMode): Boolean =
        !scope.reviewed && kind == WriteKind.ADD && mode != WriteMode.OFF

    /** 설정 화면에서 고를 수 있는 모드. 확인을 거치지 않는 범위는 끔/켬 둘뿐이다(켬은 ASK로 저장). */
    fun choices(scope: WriteScope): List<WriteMode> =
        if (scope.reviewed) WriteMode.entries else listOf(WriteMode.OFF, WriteMode.ASK)

    /** 저장값을 설정 화면에 보일 모드로(확인을 거치지 않는 범위의 예전 AUTO_ADD는 켬으로 보인다). */
    fun shown(scope: WriteScope, mode: WriteMode): WriteMode =
        if (!scope.reviewed && mode != WriteMode.OFF) WriteMode.ASK else mode

    fun label(scope: WriteScope, mode: WriteMode): String =
        if (!scope.reviewed && mode != WriteMode.OFF) "켬(바로 만들기)" else mode.label

    fun decide(mode: WriteMode, kind: WriteKind, autoUsedThisTurn: Int): Decision = when (mode) {
        WriteMode.OFF -> Decision.DENY
        WriteMode.ASK -> Decision.ASK
        WriteMode.AUTO_ADD -> if (kind == WriteKind.ADD && autoUsedThisTurn in 0 until MAX_AUTO_PER_TURN) Decision.AUTO else Decision.ASK
    }

    data class Operation(val scope: WriteScope, val kind: WriteKind)

    fun operation(tool: String): Operation? = when (tool) {
        "my_deadline_add" -> Operation(WriteScope.DEADLINES, WriteKind.ADD)
        "my_deadline_update" -> Operation(WriteScope.DEADLINES, WriteKind.UPDATE)
        "my_deadline_delete" -> Operation(WriteScope.DEADLINES, WriteKind.DELETE)
        "timetable_change_add" -> Operation(WriteScope.TIMETABLE, WriteKind.ADD)
        "timetable_change_delete" -> Operation(WriteScope.TIMETABLE, WriteKind.DELETE)
        "calendar_add" -> Operation(WriteScope.CALENDAR, WriteKind.ADD)
        "calendar_update" -> Operation(WriteScope.CALENDAR, WriteKind.UPDATE)
        "calendar_delete" -> Operation(WriteScope.CALENDAR, WriteKind.DELETE)
        "make_pdf" -> Operation(WriteScope.FILES, WriteKind.ADD)
        "score_stats_save" -> Operation(WriteScope.SCORES, WriteKind.ADD)
        "score_stats_delete" -> Operation(WriteScope.SCORES, WriteKind.DELETE)
        else -> null
    }
}

/**
 * One shared budget across scopes per running turn; attachment changes and retries cannot replenish it.
 * Since 2026-11-27 several conversations can answer at once, so each turn is keyed by its Codex thread ([key]).
 * A call whose thread is unknown uses the key "" and, unless a turn began under that key, never gets automatic writes.
 */
internal class WriteTurnBudget {
    data class Permit(val decision: WritePolicy.Decision, val key: String, val turn: Long)
    private class Active(val turn: Long) { var used = 0 }
    private var seq = 0L
    private val active = HashMap<String, Active>()

    @Synchronized fun beginTurn(key: String = "") { active[key] = Active(++seq) }
    @Synchronized fun endTurn(key: String = "") { seq++; active.remove(key) }
    @Synchronized fun endAll() { seq++; active.clear() }

    @Synchronized fun claim(mode: WriteMode, kind: WriteKind, key: String = ""): Permit {
        val a = active[key]
        val decision = WritePolicy.decide(mode, kind, a?.used ?: WritePolicy.MAX_AUTO_PER_TURN)
        if (decision == WritePolicy.Decision.AUTO) a?.let { it.used++ }
        return Permit(decision, key, a?.turn ?: -1L)
    }

    @Synchronized fun isCurrent(permit: Permit): Boolean = permit.turn >= 0 && active[permit.key]?.turn == permit.turn
}

/** Parallel additions can be prepared in the same millisecond, before entering the approval gate. */
internal object AssistantWriteIds {
    private val last = java.util.concurrent.atomic.AtomicLong()
    fun next(): Long = last.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
}
