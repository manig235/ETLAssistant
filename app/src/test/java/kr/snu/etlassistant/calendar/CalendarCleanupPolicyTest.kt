package kr.snu.etlassistant.calendar

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CalendarCleanupPolicyTest {
    @Test fun permissionLossKeepsMappingSoReenableDoesNotDuplicateExistingEvent() {
        val now = Instant.parse("2026-11-24T00:00:00Z")
        val item = CalItem("mine:1", "마감", now.plusSeconds(3600), now.plusSeconds(3600), "")
        var mapping: CalendarLogic.Mapping? = CalendarLogic.Mapping(7, mapOf(item.key to CalendarLogic.Entry(42, item.hash, item.start.toEpochMilli())))
        var deletes = 0
        val error = CalendarCleanupPolicy.cleanup(false, { deletes++ }, { mapping = null })
        assertNotNull(error)
        assertEquals(0, deletes)
        assertNotNull(mapping)
        val plan = CalendarLogic.plan(CalendarLogic.Desired(listOf(item), setOf("mine")), mapping!!.entries, setOf(42L), now)
        assertTrue(plan.ops.isEmpty())
        assertEquals(42L, mapping!!.entries[item.key]!!.eventId)
    }

    @Test fun providerFailureAndPermissionRevocationKeepOwnershipAndPreventTargetSwitch() {
        for (failure in listOf(SecurityException(), IllegalStateException())) {
            var tracked = true
            var target = 7L
            val error = CalendarCleanupPolicy.switchTarget(
                cleanup = { CalendarCleanupPolicy.cleanup(true, { throw failure }, { tracked = false }) },
                activate = { target = 9; null },
            )
            assertNotNull(error)
            assertTrue(tracked)
            assertEquals(7L, target)
        }
    }

    @Test fun retryDeletesOldTargetBeforeForgettingAndActivatingNewTarget() {
        val operations = mutableListOf<String>()
        assertNull(CalendarCleanupPolicy.switchTarget(
            cleanup = { CalendarCleanupPolicy.cleanup(true, { operations += "delete old" }, { operations += "forget old" }) },
            activate = { operations += "activate new"; null },
        ))
        assertEquals(listOf("delete old", "forget old", "activate new"), operations)
    }
}
