package kr.snu.etlassistant.files

import kr.snu.etlassistant.chat.AttachmentLogic
import kr.snu.etlassistant.files.DownloadCleanupLogic.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class DownloadCleanupLogicTest {
    private val now = 1_800_000_000_000L
    private val etl = "Download/etl/"

    @Test fun deletesOnlyAfterChosenPeriod() {
        val p = CleanupPeriod.SEVEN
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, now - 7 * DAY_MS + 1, now, p, generated = false))
        assertTrue(DownloadCleanupLogic.shouldDelete(etl, now - 7 * DAY_MS, now, p, generated = false)) // 정확히 7일
        assertTrue(DownloadCleanupLogic.shouldDelete(etl, now - 30 * DAY_MS, now, p, generated = false))
        assertTrue(DownloadCleanupLogic.shouldDelete(etl, now - 2 * DAY_MS, now, CleanupPeriod.TWO, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, now - 29 * DAY_MS, now, CleanupPeriod.THIRTY, generated = false))
    }

    @Test fun nothingIsDeletedBeforeChoosingOrWithNever() {
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, now - 400 * DAY_MS, now, null, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, now - 400 * DAY_MS, now, CleanupPeriod.NEVER, generated = false))
    }

    @Test fun excludesGeneratedOtherFoldersUnownedAndBadTimes() {
        val old = now - 100 * DAY_MS
        val p = CleanupPeriod.TWO
        assertFalse(DownloadCleanupLogic.shouldDelete("Download/etl/영민이가 만든 파일/", old, now, p, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, old, now, p, generated = true)) // 기록된 AI 파일
        assertFalse(DownloadCleanupLogic.shouldDelete("Download/", old, now, p, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete("Download/etlx/", old, now, p, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete("Pictures/etl/", old, now, p, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete(null, old, now, p, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, old, now, p, generated = false, owned = false))
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, now + DAY_MS, now, p, generated = false)) // 미래 시각
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, null, now, p, generated = false))
        assertFalse(DownloadCleanupLogic.shouldDelete(etl, 0, now, p, generated = false))
        assertTrue(DownloadCleanupLogic.shouldDelete("download/etl/sub/", old, now, p, generated = false))
    }

    @Test fun runsOncePerDayAndAsksAtMostTwice() {
        assertTrue(DownloadCleanupLogic.due(0, now))
        assertFalse(DownloadCleanupLogic.due(now - 3600_000, now))
        assertTrue(DownloadCleanupLogic.due(now - 20 * 3600_000L, now))
        assertTrue(DownloadCleanupLogic.due(now + 3600_000, now)) // 시계가 뒤로 감
        assertTrue(DownloadCleanupLogic.shouldAsk(null, 0))
        assertTrue(DownloadCleanupLogic.shouldAsk(null, 1))
        assertFalse(DownloadCleanupLogic.shouldAsk(null, 2))
        assertFalse(DownloadCleanupLogic.shouldAsk(CleanupPeriod.NEVER, 0))
    }

    @Test fun periodKeysAndLabels() {
        CleanupPeriod.values().forEach { assertEquals(it, CleanupPeriod.of(it.key)) }
        assertNull(CleanupPeriod.of(null))
        assertNull(CleanupPeriod.of("5"))
        assertEquals(CleanupPeriod.TWO, CleanupPeriod.DEFAULT)
        val at = java.time.ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        assertEquals("최근 정리: 11/24, 3개", DownloadCleanupLogic.lastRunLabel(at, 3))
        assertNull(DownloadCleanupLogic.lastRunLabel(0, 0))
    }

    @Test fun fileCardAvailability() {
        assertEquals(AttachmentLogic.Availability.OK, AttachmentLogic.availability(true, "content://x", phoneAlive = false, cleaned = true)) // 작업 폴더 사본으로 열림
        assertEquals(AttachmentLogic.Availability.OK, AttachmentLogic.availability(false, "content://x", phoneAlive = true, cleaned = false))
        assertEquals(AttachmentLogic.Availability.CLEANED, AttachmentLogic.availability(false, "content://x", phoneAlive = false, cleaned = true))
        assertEquals(AttachmentLogic.Availability.DELETED, AttachmentLogic.availability(false, "content://x", phoneAlive = false, cleaned = false))
        assertEquals(AttachmentLogic.Availability.DELETED, AttachmentLogic.availability(false, null, phoneAlive = true, cleaned = false))
        assertEquals("\"요약.pdf\" 다시 만들어줘", AttachmentLogic.redownloadPrompt("요약.pdf", generated = true))
        assertEquals("\"1주차.pdf\" 파일 다시 받아줘", AttachmentLogic.redownloadPrompt("1주차.pdf", generated = false))
    }
}
