package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class NotifyUiFormatTest {

    @Test
    fun formatTime12h() {
        assertEquals("오전 12:00", NotifyUiFormat.formatTime(0, false))
        assertEquals("오전 8:00", NotifyUiFormat.formatTime(480, false))
        assertEquals("오전 11:59", NotifyUiFormat.formatTime(719, false))
        assertEquals("오후 12:00", NotifyUiFormat.formatTime(720, false))
        assertEquals("오후 1:05", NotifyUiFormat.formatTime(13 * 60 + 5, false))
        assertEquals("오후 11:59", NotifyUiFormat.formatTime(1439, false))
    }

    @Test
    fun formatTime24h() {
        assertEquals("00:00", NotifyUiFormat.formatTime(0, true))
        assertEquals("08:00", NotifyUiFormat.formatTime(480, true))
        assertEquals("11:59", NotifyUiFormat.formatTime(719, true))
        assertEquals("12:00", NotifyUiFormat.formatTime(720, true))
        assertEquals("23:59", NotifyUiFormat.formatTime(1439, true))
    }

    @Test
    fun formatTimeClampsOutOfRange() {
        assertEquals("00:00", NotifyUiFormat.formatTime(-5, true))
        assertEquals("23:59", NotifyUiFormat.formatTime(5000, true))
    }

    @Test
    fun normalizeBriefTimes() {
        assertEquals(listOf(480), NotifyUiFormat.normalizeBriefTimes(emptyList()))
        assertEquals(listOf(480), NotifyUiFormat.normalizeBriefTimes(listOf(480, 480)))
        assertEquals(listOf(480, 1200), NotifyUiFormat.normalizeBriefTimes(listOf(1200, 480)))
        assertEquals(listOf(0, 1439), NotifyUiFormat.normalizeBriefTimes(listOf(-10, 9999)))
        assertEquals(listOf(100, 200), NotifyUiFormat.normalizeBriefTimes(listOf(300, 200, 100)))
        // 보정 뒤 같아지면 하나로 합친다
        assertEquals(listOf(1439), NotifyUiFormat.normalizeBriefTimes(listOf(1439, 2000)))
    }

    @Test
    fun describeLastCheckNever() {
        assertEquals("아직 확인한 적이 없어요", NotifyUiFormat.describeLastCheck(0L, "성공"))
    }

    @Test
    fun describeLastCheckWithResult() {
        // 2026-11-19 14:10 UTC = 23:10 KST(목)
        val ms = ZonedDateTime.of(2026, 11, 19, 14, 10, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val s = NotifyUiFormat.describeLastCheck(ms, "성공 · 마감 알림 1건, 공지 알림 0건")
        assertTrue(s, s.startsWith("마지막 확인: "))
        assertTrue(s, s.contains("23:10"))
        assertTrue(s, s.endsWith(" · 성공 · 마감 알림 1건, 공지 알림 0건"))
        assertEquals("마지막 확인: 11-19(목) 23:10", NotifyUiFormat.describeLastCheck(ms, "  "))
    }

    @Test
    fun describeLastBrief() {
        assertEquals("아직 브리핑을 보낸 적이 없어요", NotifyUiFormat.describeLastBrief(0L))
        val ms = ZonedDateTime.of(2026, 11, 19, 14, 10, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("마지막 브리핑: 11-19(목) 23:10", NotifyUiFormat.describeLastBrief(ms))
    }
}
