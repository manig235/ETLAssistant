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

    @Test fun oneAlarmTargetsTheReminderWithoutIntermediateWakes() {
        for (left in listOf(1_000L, min, 90 * min, 3 * 24 * hour)) {
            assertEquals(NotifySchedule.AlarmRequest(1_000L + left, true), NotifySchedule.nextAlarm(1_000L, 1_000L + left, true))
            assertEquals(NotifySchedule.AlarmRequest(1_000L + left, false), NotifySchedule.nextAlarm(1_000L, 1_000L + left, false))
        }
    }

    @Test fun overdueAlarmIsRequestedNowWithEitherAccessState() {
        assertEquals(NotifySchedule.AlarmRequest(2_000L, true), NotifySchedule.nextAlarm(2_000L, 1_000L, true))
        assertEquals(NotifySchedule.AlarmRequest(2_000L, false), NotifySchedule.nextAlarm(2_000L, 1_000L, false))
    }

    @Test fun rearmAfterRebootOrTimeChange() {
        assertEquals(NotifySchedule.Rearm.NONE, NotifySchedule.rearmAction(0L, 1_000L))
        assertEquals(NotifySchedule.Rearm.ARM, NotifySchedule.rearmAction(2_000L, 1_000L))
        // 꺼져 있던 사이 목표가 지났으면 바로 확인한다(내 마감 알림은 늦게라도 보낸다)
        assertEquals(NotifySchedule.Rearm.FIRE_NOW, NotifySchedule.rearmAction(1_000L, 1_000L))
        assertEquals(NotifySchedule.Rearm.FIRE_NOW, NotifySchedule.rearmAction(500L, 1_000L))
    }

    // ---- 브리핑 예약 유지(프로세스가 뜰 때마다 apply가 불려도 오늘 브리핑이 밀리지 않게) ----

    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    @Test fun keepBriefWhenBriefJobColdStartsProcess() {
        // 08:00 브리핑 작업이 프로세스를 띄운 직후(08:00:05) apply가 불려도 그대로 둔다.
        assertTrue(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(8, 0, 5)), seoul))
        // Doze로 늦어져 09:30에 프로세스가 떠도(4시간 이내) 그대로 둔다.
        assertTrue(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(9, 30)), seoul))
        // 아직 오지 않은 예약도 그대로 둔다.
        assertTrue(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(6, 0)), seoul))
    }

    @Test fun rescheduleBriefWhenMissingChangedOrTooOld() {
        assertFalse(NotifySchedule.keepBriefTarget(null, 8 * 60, ms(at(6, 0)), seoul))
        // 시각 설정이 바뀌었다(08:00 → 09:00).
        assertFalse(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 9 * 60, ms(at(6, 0)), seoul))
        // 4시간 넘게 지나 보낼 수 없는 예약은 다음 것으로 바꾼다.
        assertFalse(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(12, 0, 1)), seoul))
        // 이틀보다 먼 미래(시계가 뒤로 감)는 다시 계산한다.
        assertFalse(NotifySchedule.keepBriefTarget(ms(at(8, 0)) + 3 * 24 * hour, 8 * 60, ms(at(6, 0)), seoul))
    }

    @Test fun rescheduleBriefWhenZoneChanged() {
        // 서울 08:00으로 잡은 예약은 도쿄 시간대(같은 UTC+9)면 유지, UTC면 분이 달라 다시 계산한다.
        assertTrue(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(6, 0)), ZoneId.of("Asia/Tokyo")))
        assertFalse(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(6, 0)), ZoneId.of("UTC")))
    }
}
