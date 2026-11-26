package kr.snu.etlassistant.etl

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WriteApprovalGateTest {
    // Listeners invalidate state; concurrent invalidations may legitimately expose the same request.
    // Observe each opaque request once, as the dialog's request ID check does in production.
    private fun observeRequests(gate: WriteApprovalGate): LinkedBlockingQueue<WriteApprovalGate.Request> {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val requests = LinkedBlockingQueue<WriteApprovalGate.Request>()
        gate.listeners += {
            gate.pending().forEach { request -> if (seen.add(request.id)) requests.offer(request) }
        }
        return requests
    }

    @Test fun noForegroundHostCannotWrite() {
        val writes = AtomicInteger()
        val gate = WriteApprovalGate()
        assertThrows(WriteApprovalGate.Denied::class.java) { gate.execute("delete", "a") { writes.incrementAndGet() } }
        assertEquals(0, writes.get())
    }

    @Test fun approvalIsBoundToOneCallAndCannotBeReplayed() {
        val gate = WriteApprovalGate()
        val host = gate.attachHost()
        val requests = observeRequests(gate)
        val worker = Executors.newSingleThreadExecutor()
        val writes = AtomicInteger()
        try {
            val first = worker.submit<Int> { gate.execute("add", "A, 학교, 2026-11-25 09:00") { writes.incrementAndGet() } }
            val a = requests.poll(2, TimeUnit.SECONDS)!!
            assertEquals(0, writes.get())
            assertEquals("A, 학교, 2026-11-25 09:00", a.details)
            gate.listeners.forEach { it() } // Repeated state invalidation must not masquerade as a new call.
            assertFalse(gate.decide("wrong host", a.id, true))
            assertFalse(gate.decide(host, "made up token", true))
            assertTrue(gate.decide(host, a.id, true))
            assertEquals(1, first.get(2, TimeUnit.SECONDS))
            assertFalse(gate.decide(host, a.id, true))
            val second = worker.submit<Boolean> {
                try { gate.execute("delete", "B", true) { writes.incrementAndGet() }; false }
                catch (_: WriteApprovalGate.Denied) { true }
            }
            val b = requests.poll(2, TimeUnit.SECONDS)!!
            assertNotEquals(a.id, b.id)
            assertTrue(b.destructive)
            assertFalse(gate.decide(host, a.id, true))
            assertTrue(gate.decide(host, b.id, false))
            assertTrue(second.get(2, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
        } finally { gate.cancelAll(); worker.shutdownNow() }
    }

    @Test fun detachAndTurnCancellationReleaseWaitersWithoutWriting() {
        for (detach in listOf(false, true)) {
            val gate = WriteApprovalGate()
            val host = gate.attachHost()
            val requests = observeRequests(gate)
            val writes = AtomicInteger()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Boolean> {
                    try { gate.execute("update", "resolved write") { writes.incrementAndGet() }; false }
                    catch (_: WriteApprovalGate.Denied) { true }
                }
                val request = requests.poll(2, TimeUnit.SECONDS)!!
                if (detach) gate.detachHost(host) else gate.cancelAll()
                assertTrue(result.get(2, TimeUnit.SECONDS))
                assertFalse(gate.decide(host, request.id, true))
                assertEquals(0, writes.get())
                assertTrue(gate.pending().isEmpty())
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun replacementHostRejectsPendingRequestFromPreviousChat() {
        val gate = WriteApprovalGate()
        val oldHost = gate.attachHost()
        val requests = observeRequests(gate)
        val writes = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val oldCall = worker.submit<Boolean> {
                try { gate.execute("delete", "old chat") { writes.incrementAndGet() }; false }
                catch (_: WriteApprovalGate.Denied) { true }
            }
            val oldRequest = requests.poll(2, TimeUnit.SECONDS)!!
            val newHost = gate.attachHost()
            assertTrue(oldCall.get(2, TimeUnit.SECONDS))
            assertFalse(gate.decide(oldHost, oldRequest.id, true))
            assertFalse(gate.decide(newHost, oldRequest.id, true))
            gate.detachHost(oldHost)
            val newCall = worker.submit<Int> { gate.execute("add", "new chat") { writes.incrementAndGet() } }
            val newRequest = requests.poll(2, TimeUnit.SECONDS)!!
            assertNotEquals(oldRequest.id, newRequest.id)
            assertTrue(gate.decide(newHost, newRequest.id, true))
            assertEquals(1, newCall.get(2, TimeUnit.SECONDS))
        } finally { gate.cancelAll(); worker.shutdownNow() }
    }

    @Test fun timeoutDeniesAndNewHostInvalidatesOldDialog() {
        val gate = WriteApprovalGate(timeoutMillis = 40)
        val oldHost = gate.attachHost()
        val writes = AtomicInteger()
        assertThrows(WriteApprovalGate.Denied::class.java) { gate.execute("add", "never answered") { writes.incrementAndGet() } }
        assertEquals(0, writes.get())
        assertTrue(gate.pending().isEmpty())
        val activeGate = WriteApprovalGate()
        val newHost = activeGate.attachHost()
        activeGate.detachHost(oldHost) // An old Activity must not detach the new one.
        val requests = observeRequests(activeGate)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Int> { activeGate.execute("add", "new chat") { writes.incrementAndGet() } }
            val request = requests.poll(2, TimeUnit.SECONDS)!!
            assertFalse(activeGate.decide(oldHost, request.id, true))
            assertTrue(activeGate.decide(newHost, request.id, true))
            assertEquals(1, result.get(2, TimeUnit.SECONDS))
        } finally { activeGate.cancelAll(); worker.shutdownNow() }
    }

    @Test fun turnBoundaryCancelsOnlyThatConversationsReview() {
        val gate = WriteApprovalGate()
        val host = gate.attachHost()
        val requests = observeRequests(gate)
        val worker = Executors.newFixedThreadPool(2)
        val writes = AtomicInteger()
        try {
            val a = worker.submit<Boolean> {
                try { gate.execute("add", "A", origin = "thread-a") { writes.incrementAndGet() }; false }
                catch (_: WriteApprovalGate.Denied) { true }
            }
            val ra = requests.poll(2, TimeUnit.SECONDS)!!
            val b = worker.submit<Int> { gate.execute("add", "B", origin = "thread-b") { writes.incrementAndGet() } }
            val rb = requests.poll(2, TimeUnit.SECONDS)!!
            assertEquals("thread-a", ra.origin)
            assertEquals("thread-b", rb.origin)
            // 대화 A의 답변이 끝나거나 새로 시작하면 A의 요청만 거절된다.
            gate.cancelOrigin("thread-a")
            assertTrue(a.get(2, TimeUnit.SECONDS))
            assertEquals(listOf(rb.id), gate.pending().map { it.id })
            // B의 요청은 남아 있고, 그 요청의 버튼만 실행한다.
            assertFalse(gate.decide(host, ra.id, true))
            assertTrue(gate.decide(host, rb.id, true))
            assertEquals(1, b.get(2, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
        } finally { gate.cancelAll(); worker.shutdownNow() }
    }

    @Test fun unknownOriginIsCancelledByAnyTurnBoundary() {
        val gate = WriteApprovalGate()
        gate.attachHost()
        val requests = observeRequests(gate)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val denied = worker.submit<Boolean> {
                try { gate.execute("add", "?") { 1 }; false } catch (_: WriteApprovalGate.Denied) { true }
            }
            requests.poll(2, TimeUnit.SECONDS)!!
            gate.cancelOrigin("some-thread")
            assertTrue(denied.get(2, TimeUnit.SECONDS))
        } finally { gate.cancelAll(); worker.shutdownNow() }
    }
}
