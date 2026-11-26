package kr.snu.etlassistant.etl

enum class WriteScope(val label: String) {
    DEADLINES("내 마감·시험"), TIMETABLE("시간표 보강·휴강"), CALENDAR("캘린더 일정"), FILES("파일 만들기")
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
