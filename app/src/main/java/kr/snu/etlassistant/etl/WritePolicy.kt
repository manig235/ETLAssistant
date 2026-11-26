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

/** One shared budget across scopes; attachment changes and retries cannot replenish it. */
internal class WriteTurnBudget {
    data class Permit(val decision: WritePolicy.Decision, val turn: Long)
    private var turn = 0L
    private var active = false
    private var used = 0

    @Synchronized fun beginTurn() { turn++; active = true; used = 0 }
    @Synchronized fun endTurn() { turn++; active = false }

    @Synchronized fun claim(mode: WriteMode, kind: WriteKind): Permit {
        val decision = WritePolicy.decide(mode, kind, if (active) used else WritePolicy.MAX_AUTO_PER_TURN)
        if (decision == WritePolicy.Decision.AUTO) used++
        return Permit(decision, turn)
    }

    @Synchronized fun isCurrent(permit: Permit): Boolean = active && turn == permit.turn
}

/** Parallel additions can be prepared in the same millisecond, before entering the approval gate. */
internal object AssistantWriteIds {
    private val last = java.util.concurrent.atomic.AtomicLong()
    fun next(): Long = last.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
}
