package kr.snu.etlassistant.etl

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WritePolicyTest {
    @Test fun parallelAdditionsGetDifferentIdsEvenInTheSameMillisecond() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..4).map { pool.submit<List<Long>> { List(100) { AssistantWriteIds.next() } } }
            val ids = jobs.flatMap { it.get(2, TimeUnit.SECONDS) }
            assertEquals(400, ids.toSet().size)
            assertTrue(ids.all { it > 0 })
        } finally { pool.shutdownNow() }
    }

    @Test fun offDeniesEveryKindAndAskNeverRunsAutomatically() {
        for (kind in WriteKind.entries) {
            assertEquals(WritePolicy.Decision.DENY, WritePolicy.decide(WriteMode.OFF, kind, 0))
            assertEquals(WritePolicy.Decision.ASK, WritePolicy.decide(WriteMode.ASK, kind, 0))
        }
    }

    @Test fun onlyAdditionsWithinTheSharedBudgetCanRunAutomatically() {
        for (kind in listOf(WriteKind.UPDATE, WriteKind.DELETE)) {
            assertEquals(WritePolicy.Decision.ASK, WritePolicy.decide(WriteMode.AUTO_ADD, kind, 0))
        }
        assertEquals(WritePolicy.Decision.AUTO, WritePolicy.decide(WriteMode.AUTO_ADD, WriteKind.ADD, 4))
        assertEquals(WritePolicy.Decision.ASK, WritePolicy.decide(WriteMode.AUTO_ADD, WriteKind.ADD, 5))
        assertEquals(WritePolicy.Decision.ASK, WritePolicy.decide(WriteMode.AUTO_ADD, WriteKind.ADD, -1))
    }

    @Test fun unsetOrUnknownSavedModesDefaultToConfirmation() {
        assertEquals(WriteMode.ASK, WriteMode.parse(null))
        assertEquals(WriteMode.ASK, WriteMode.parse("unknown"))
        WriteMode.entries.forEach { assertEquals(it, WriteMode.parse(it.name)) }
    }

    @Test fun writeToolMappingCoversEveryScopeAndLeavesReadToolsAvailable() {
        val names = mapOf(
            WriteScope.DEADLINES to listOf("my_deadline_add", "my_deadline_update", "my_deadline_delete"),
            WriteScope.TIMETABLE to listOf("timetable_change_add", "timetable_change_delete"),
            WriteScope.CALENDAR to listOf("calendar_add", "calendar_update", "calendar_delete"),
            WriteScope.FILES to listOf("make_pdf"),
        )
        names.forEach { (scope, tools) -> tools.forEach { assertEquals(scope, WritePolicy.operation(it)!!.scope) } }
        listOf("my_deadlines", "etl_timetable", "calendar_events", "etl_download").forEach { assertNull(WritePolicy.operation(it)) }
    }

    @Test fun concurrentClaimsCannotExceedFiveAutomaticWrites() {
        val budget = WriteTurnBudget()
        budget.beginTurn()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..30).map { pool.submit<WriteTurnBudget.Permit> { budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD) } }
            assertEquals(5, jobs.map { it.get(2, TimeUnit.SECONDS) }.count { it.decision == WritePolicy.Decision.AUTO })
        } finally { pool.shutdownNow() }
    }

    @Test fun turnCompletionInvalidatesOldPermitsAndOnlyNewTurnsReplenishBudget() {
        val budget = WriteTurnBudget()
        assertEquals(WritePolicy.Decision.ASK, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD).decision)
        budget.beginTurn()
        val permit = budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD)
        assertTrue(budget.isCurrent(permit))
        budget.endTurn()
        assertFalse(budget.isCurrent(permit))
        assertEquals(WritePolicy.Decision.ASK, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD).decision)
        budget.beginTurn()
        assertFalse(budget.isCurrent(permit))
        repeat(5) { assertEquals(WritePolicy.Decision.AUTO, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD).decision) }
        assertEquals(WritePolicy.Decision.ASK, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD).decision)
    }

    @Test fun confirmationAndDenialsDoNotConsumeAutomaticBudget() {
        val budget = WriteTurnBudget()
        budget.beginTurn()
        repeat(10) {
            budget.claim(WriteMode.OFF, WriteKind.ADD)
            budget.claim(WriteMode.ASK, WriteKind.ADD)
            budget.claim(WriteMode.AUTO_ADD, WriteKind.DELETE)
        }
        repeat(5) { assertEquals(WritePolicy.Decision.AUTO, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD).decision) }
    }

    @Test fun automaticWritesStillRequireAnAttachedForegroundHost() {
        val gate = WriteApprovalGate()
        try { gate.executeAutomatic { fail("No foreground host") }; fail() } catch (_: WriteApprovalGate.Denied) { }
        val host = gate.attachHost()
        assertEquals("done", gate.executeAutomatic { "done" })
        gate.detachHost(host)
        try { gate.executeAutomatic { fail("Host was detached") }; fail() } catch (_: WriteApprovalGate.Denied) { }
    }

    @Test fun budgetsAreSeparatePerConversationTurn() {
        val budget = WriteTurnBudget()
        budget.beginTurn("thread-a")
        budget.beginTurn("thread-b")
        repeat(5) { assertEquals(WritePolicy.Decision.AUTO, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD, "thread-a").decision) }
        assertEquals(WritePolicy.Decision.ASK, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD, "thread-a").decision)
        val b = budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD, "thread-b")
        assertEquals(WritePolicy.Decision.AUTO, b.decision)
        // 대화 A가 끝나도 B의 허가는 그대로.
        budget.endTurn("thread-a")
        assertTrue(budget.isCurrent(b))
        // 스레드를 모르는 호출은 자동으로 쓰지 않는다.
        assertEquals(WritePolicy.Decision.ASK, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD, "").decision)
        budget.endAll()
        assertFalse(budget.isCurrent(b))
    }
}
