package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NotifyScheduleTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int, s: Int = 0, zone: ZoneId = seoul) = ZonedDateTime.of(2026, 3, 10, h, m, s, 0, zone)
    private val min = 60_000L
    private val hour = 3_600_000L

    @Test fun todayWhenNotYetPassed() {
        assertEquals(2 * hour, NotifySchedule.nextOccurrenceDelayMillis(at(6, 0), 8 * 60))
    }

    @Test fun tomorrowWhenAlreadyPassed() {
        assertEquals(22 * hour, NotifySchedule.nextOccurrenceDelayMillis(at(10, 0), 8 * 60))
    }

    @Test fun exactSameMinuteGoesToTomorrow() {
        assertEquals(24 * hour, NotifySchedule.nextOccurrenceDelayMillis(at(8, 0), 8 * 60))
    }

    @Test fun minLeadBoundary() {
        // 정확히 minLead만큼 남으면 오늘, 1초라도 모자라면 내일
        assertEquals(min, NotifySchedule.nextOccurrenceDelayMillis(at(7, 59), 8 * 60))
        assertEquals(24 * hour + 59_000, NotifySchedule.nextOccurrenceDelayMillis(at(7, 59, 1), 8 * 60))
    }

    @Test fun justAfterRunDoesNotRepickSameSlot() {
        // 08:00:30에 실행된 브리핑 다음 예약은 내일 08:00
        assertEquals(24 * hour - 30_000, NotifySchedule.nextOccurrenceDelayMillis(at(8, 0, 30), 8 * 60))
    }

    @Test fun nearMidnight() {
        assertEquals(1 * hour, NotifySchedule.nextOccurrenceDelayMillis(at(23, 0), 0))
        // 23:59 기준 00:00은 1분 뒤(minLead와 같음)
        assertEquals(min, NotifySchedule.nextOccurrenceDelayMillis(at(23, 59), 0))
    }

    @Test fun twoSlotsPickIndependently() {
        val now = at(12, 0)
        assertEquals(20 * hour, NotifySchedule.nextOccurrenceDelayMillis(now, 8 * 60))
        assertEquals(8 * hour, NotifySchedule.nextOccurrenceDelayMillis(now, 20 * 60))
    }

    @Test fun springForwardNonexistentTime() {
        // America/New_York 2026-03-08 02:00 -> 03:00. 02:30은 존재하지 않아 03:30(EDT)이 된다.
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny)
        val delay = NotifySchedule.nextOccurrenceDelayMillis(now, 2 * 60 + 30)
        val target = now.toInstant().plusMillis(delay).atZone(ny)
        assertEquals(3, target.hour)
        assertEquals(30, target.minute)
        assertEquals(2 * hour + 30 * min, delay) // 실제 경과는 2시간 30분
    }

    @Test fun springForwardDayIsShort() {
        val ny = ZoneId.of("America/New_York")
        // 전환 전날 09:00(EST) -> 전환일 09:00(EDT)은 23시간 뒤
        assertEquals(23 * hour, NotifySchedule.nextOccurrenceDelayMillis(ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, ny), 9 * 60))
        val now = ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, ny)
        // 전환일 09:00(EDT) -> 다음 날 09:00은 24시간 뒤(전환은 이미 지났다)
        assertEquals(24 * hour, NotifySchedule.nextOccurrenceDelayMillis(now, 9 * 60))
    }

    @Test fun fallBackDayIsLong() {
        val ny = ZoneId.of("America/New_York")
        // 2026-11-01 01:00~02:00가 두 번. 00:00 기준 다음 08:00은 9시간 뒤(25시간짜리 하루 안)
        val now = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ny)
        assertEquals(9 * hour, NotifySchedule.nextOccurrenceDelayMillis(now, 8 * 60))
    }

    @Test fun isStaleBoundary() {
        val sched = 1_000_000L
        assertFalse(NotifySchedule.isStale(sched, sched))
        assertFalse(NotifySchedule.isStale(sched, sched + NotifySchedule.MAX_LATE_MILLIS))
        assertTrue(NotifySchedule.isStale(sched, sched + NotifySchedule.MAX_LATE_MILLIS + 1))
        assertFalse(NotifySchedule.isStale(sched, sched - 5000)) // 일찍 실행돼도 stale 아님
    }

    @Test fun flexMillis() {
        assertEquals(15 * min, NotifySchedule.flexMillis(1))
        assertEquals(45 * min, NotifySchedule.flexMillis(3))
        assertEquals(3 * hour, NotifySchedule.flexMillis(12))
    }

    @Test fun notificationIdNonNegativeAndStable() {
        for (t in listOf("auth", "brief", "deadline:1:2:24", "polygenelubricants", "")) {
            assertTrue(NotifySchedule.notificationId(t) >= 0)
            assertEquals(NotifySchedule.notificationId(t), NotifySchedule.notificationId(t))
        }
        assertEquals(Int.MAX_VALUE and "Aa".hashCode(), NotifySchedule.notificationId("Aa"))
    }

    @Test fun authFailureThrottle() {
        val now = 100L * hour
        assertTrue(NotifySchedule.authFailureDue(null, now))
        assertFalse(NotifySchedule.authFailureDue(now - 23 * hour, now))
        assertTrue(NotifySchedule.authFailureDue(now - 24 * hour, now))
    }

    // ---- 마감 알람 다가가기 ------------------------------------------------------

    private val aMin = 60_000L
    private val aHour = 60 * aMin

    /** 시스템 최대 지연(남은 시간 × 0.75, 최대 1시간)을 넣어 끝까지 따라가 본다. 목표를 넘지 않고, 마지막 지연은 6분 이하. */
    private fun worstCase(startLeft: Long): Pair<Long, Int> {
        val target = 1_000_000_000L
        var now = target - startLeft
        var wakes = 0
        while (true) {
            val (at, final) = NotifySchedule.nextAlarm(now, target)
            val fire = at + minOf(((at - now) * 0.75).toLong(), aHour)
            wakes++
            if (final) return (fire - target) to wakes
            assertTrue("중간 알람이 목표를 넘었다", fire < target)
            now = fire
            assertTrue(wakes < 30)
        }
    }

    @Test fun farTargetApproachesAndEndsWithin6Minutes() {
        for (left in listOf(3 * 24 * aHour, 5 * aHour, 2 * aHour + 1, 90 * aMin, 30 * aMin, 9 * aMin)) {
            val (late, wakes) = worstCase(left)
            assertTrue("left=$left late=$late", late <= 6 * aMin)
            assertTrue("left=$left wakes=$wakes", wakes <= 8)
        }
    }

    @Test fun nearTargetIsFinal() {
        assertEquals(1_000L to true, NotifySchedule.nextAlarm(0, 1_000))
        assertEquals((8 * aMin) to true, NotifySchedule.nextAlarm(0, 8 * aMin))
        assertEquals((9 * aHour - 5 * aMin) to false, NotifySchedule.nextAlarm(0, 10 * aHour))
        assertEquals((45 * aMin) to false, NotifySchedule.nextAlarm(0, 90 * aMin))
    }
}
