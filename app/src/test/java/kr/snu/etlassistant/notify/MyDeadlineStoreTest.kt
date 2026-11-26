package kr.snu.etlassistant.notify

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class MyDeadlineStoreTest {
    private val items = AtomicReference<String?>(null)
    private val writes = AtomicInteger()
    private fun store() = MyDeadlineStore({ items.get() }, { writes.incrementAndGet(); items.set(it) })
    private fun item(id: Long = 1) = MyDeadline(id, "서류", Instant.parse("2026-11-26T09:00:00Z"))

    @Test fun concurrentStoresKeepEveryAddition() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0..3).map { worker -> pool.submit {
                val store = store()
                check(start.await(2, TimeUnit.SECONDS))
                repeat(20) { store.upsert(item((worker * 20 + it + 1).toLong())) }
            } }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals((1L..80L).toSet(), store().load().map { it.id }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun idReplacementPersistsOnceAndNeverLeavesTheItemMissing() {
        val store = store()
        val old = item()
        store.upsert(old)
        val before = writes.get()
        val replacement = old.copy(id = 2, due = old.due.plusSeconds(3600))
        assertTrue(store.replace(old.id, replacement))
        assertEquals(before + 1, writes.get())
        assertEquals(listOf(replacement), store.load())
    }

    @Test fun replacementCannotOverwriteAnotherItemsId() {
        val store = store()
        val first = item(1)
        val second = item(2)
        store.upsert(first)
        store.upsert(second)
        assertFalse(store.compareAndSet(first, first.copy(id = second.id)))
        assertFalse(store.replace(first.id, first.copy(id = second.id)))
        assertEquals(setOf(first, second), store.load().toSet())
    }

    @Test fun staleCalendarOrApprovalSnapshotCannotOverwriteEditsOrResurrectDeletedItem() {
        val first = store()
        val second = store()
        val old = item()
        first.upsert(old)
        second.update(old.id) { it.copy(done = true) }
        assertFalse(first.compareAndSet(old, old.copy(due = old.due.plusSeconds(3600))))
        assertTrue(first.load().single().done)
        assertFalse(first.compareAndSet(old, null))
        second.delete(old.id)
        assertFalse(first.compareAndSet(old, old.copy(title = "stale")))
        assertFalse(first.replace(old.id, old))
        assertTrue(first.load().isEmpty())
    }

    @Test fun updateTransformsTheLatestValueAndDoesNotWriteForMissingItems() {
        val first = store()
        first.upsert(item())
        store().update(1) { it.copy(memo = "새 메모") }
        assertTrue(first.update(1) { it.copy(done = true) })
        assertEquals("새 메모", first.load().single().memo)
        val before = writes.get()
        assertFalse(first.update(2) { fail("Missing item reached transform"); it })
        assertEquals(before, writes.get())
    }

    @Test fun changeCallbacksRunAfterReleasingTheStoreLock() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writer = MyDeadlineStore({ items.get() }, { items.set(it) }) {
                assertEquals(1, pool.submit<Int> { store().load().size }.get(2, TimeUnit.SECONDS))
            }
            writer.upsert(item())
        } finally { pool.shutdownNow() }
    }
}
