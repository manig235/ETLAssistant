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
            WriteScope.SCORES to listOf("score_stats_save", "score_stats_delete"),
        )
        names.forEach { (scope, tools) -> tools.forEach { assertEquals(scope, WritePolicy.operation(it)!!.scope) } }
        listOf("my_deadlines", "etl_timetable", "calendar_events", "etl_download", "score_stats", "etl_grades").forEach { assertNull(WritePolicy.operation(it)) }
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

    // 2026-11-28: make_pdf가 확인 창 없이(채팅 화면이 꺼졌거나 확인 창이 사라져) "변경을 승인하지 않아 실행하지 않았어요"로 거절되던 문제.
    @Test fun fileCreationRunsDirectlyWithoutHostDialogOrBudget() {
        val gate = WriteApprovalGate(timeoutMillis = 50)
        val budget = WriteTurnBudget()
        val runner = WriteRunner(gate, budget)
        for (mode in listOf(WriteMode.ASK, WriteMode.AUTO_ADD)) {
            // 채팅 화면이 붙어 있지 않아도, 턴을 몰라도, 확인 요청 없이 바로 만든다.
            assertEquals("pdf", runner.run(WriteScope.FILES, WriteKind.ADD, { mode }, null, "PDF 만들기", "") { "pdf" })
            assertEquals("pdf", runner.run(WriteScope.FILES, WriteKind.ADD, { mode }, "thread-a", "PDF 만들기", "") { "pdf" })
            assertTrue(gate.pending().isEmpty())
        }
        // PDF는 다른 범위의 자동 추가 한도를 쓰지 않는다.
        budget.beginTurn("thread-a")
        repeat(10) { runner.run(WriteScope.FILES, WriteKind.ADD, { WriteMode.AUTO_ADD }, "thread-a", "PDF 만들기", "") { } }
        repeat(5) { assertEquals(WritePolicy.Decision.AUTO, budget.claim(WriteMode.AUTO_ADD, WriteKind.ADD, "thread-a").decision) }
    }

    @Test fun fileCreationStillRefusedWhenOffAndSettingIsReadBeforeRunning() {
        val runner = WriteRunner(WriteApprovalGate(timeoutMillis = 50), WriteTurnBudget())
        var ran = false
        val off = try { runner.run(WriteScope.FILES, WriteKind.ADD, { WriteMode.OFF }, null, "PDF 만들기", "") { ran = true }; null } catch (e: EtlException) { e.message }
        assertEquals("설정에서 비서의 파일 만들기 변경이 꺼져 있어요", off)
        assertFalse(ran)
    }

    @Test fun scheduleWritesStillNeedTheForegroundConfirmation() {
        val gate = WriteApprovalGate(timeoutMillis = 50)
        val runner = WriteRunner(gate, WriteTurnBudget())
        for (scope in listOf(WriteScope.DEADLINES, WriteScope.TIMETABLE, WriteScope.CALENDAR, WriteScope.SCORES)) {
            for (mode in listOf(WriteMode.ASK, WriteMode.AUTO_ADD)) {
                var ran = false
                // 채팅 화면이 없으면 확인할 수 없으므로 거절(턴을 모르면 자동 추가도 없음).
                try { runner.run(scope, WriteKind.ADD, { mode }, null, "추가", "") { ran = true }; fail() } catch (_: EtlException) { }
                assertFalse(ran)
            }
        }
        // 화면이 있어도 아무도 승인하지 않으면 시간 초과로 거절.
        gate.attachHost()
        var ran = false
        try { runner.run(WriteScope.DEADLINES, WriteKind.ADD, { WriteMode.ASK }, null, "추가", "") { ran = true }; fail() } catch (_: EtlException) { }
        assertFalse(ran)
    }

    @Test fun onlyAdditionsInUnreviewedScopesAreDirect() {
        assertTrue(WritePolicy.direct(WriteScope.FILES, WriteKind.ADD, WriteMode.ASK))
        assertTrue(WritePolicy.direct(WriteScope.FILES, WriteKind.ADD, WriteMode.AUTO_ADD))
        assertFalse(WritePolicy.direct(WriteScope.FILES, WriteKind.ADD, WriteMode.OFF))
        assertFalse(WritePolicy.direct(WriteScope.FILES, WriteKind.UPDATE, WriteMode.ASK))
        assertFalse(WritePolicy.direct(WriteScope.FILES, WriteKind.DELETE, WriteMode.AUTO_ADD))
        for (scope in listOf(WriteScope.DEADLINES, WriteScope.TIMETABLE, WriteScope.CALENDAR, WriteScope.SCORES)) {
            assertTrue(scope.reviewed)
            for (kind in WriteKind.entries) for (mode in WriteMode.entries) assertFalse(WritePolicy.direct(scope, kind, mode))
        }
    }

    @Test fun settingsOfferOnOffForFilesAndAllModesElsewhere() {
        assertEquals(listOf(WriteMode.OFF, WriteMode.ASK), WritePolicy.choices(WriteScope.FILES))
        assertEquals("켬(바로 만들기)", WritePolicy.label(WriteScope.FILES, WriteMode.ASK))
        assertEquals(WriteMode.OFF.label, WritePolicy.label(WriteScope.FILES, WriteMode.OFF))
        // 예전에 "추가는 자동"으로 저장한 파일 만들기는 켬으로 보인다.
        assertEquals(WriteMode.ASK, WritePolicy.shown(WriteScope.FILES, WriteMode.AUTO_ADD))
        assertEquals(WriteMode.OFF, WritePolicy.shown(WriteScope.FILES, WriteMode.OFF))
        assertEquals(WriteMode.entries, WritePolicy.choices(WriteScope.CALENDAR))
        assertEquals(WriteMode.AUTO_ADD, WritePolicy.shown(WriteScope.DEADLINES, WriteMode.AUTO_ADD))
        assertEquals(WriteMode.ASK.label, WritePolicy.label(WriteScope.TIMETABLE, WriteMode.ASK))
    }
}
