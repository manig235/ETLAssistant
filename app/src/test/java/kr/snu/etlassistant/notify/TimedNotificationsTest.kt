package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.TimetableLogic
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TimedNotificationsTest {
    private val now = Instant.parse("2026-11-19T03:00:00Z") // Thu, noon in Seoul
    private val zone = ZoneId.of("Asia/Seoul")
    private val settings = NotifySettings(deadlineWindowsMinutes = listOf(60, 10))
    private fun assignment(cid: Long = 1, id: Long = 2) = AssignmentItem(cid, "과목$cid", id, "과제$id", now.plusSeconds(600), true, null, null, false, false)
    private fun plan(cached: CachedSnapshot? = null, mine: List<MyDeadline> = emptyList(), state: NotifyState = NotifyState(),
                     s: NotifySettings = settings, filter: CourseFilter = CourseFilter(), days: List<TimetableLogic.Day> = emptyList()) =
        TimedNotifications.plan(cached, mine, days, state, now, s, filter, zone)

    @Test fun offlineCachedDeadlineAndManualReminderNeedNoTokenOrNetwork() {
        val mine = MyDeadline(5, "서류", now.plusSeconds(7200), reminders = listOf(now.minusSeconds(60)))
        val result = plan(CachedSnapshot(now.minusSeconds(3600).toEpochMilli(), listOf(assignment())), listOf(mine))
        assertEquals(2, result.plan.notices.size)
        assertTrue(result.plan.notices.any { it.tag == "mine-remind:5" })
        assertTrue(result.plan.notices.first { it.tag == "deadline:1:2" }.bigText!!.contains("받아 둔 eTL 정보"))
        assertFalse(result.plan.markBaselineDone) // Offline delivery never initializes the announcement baseline.
        assertTrue(result.plan.notices.none { it.channel == Channel.ANNOUNCEMENT })
    }

    @Test fun deliveredKeysPreventOfflineAndFreshWorkerDuplicates() {
        val cached = CachedSnapshot(now.toEpochMilli(), listOf(assignment()))
        val first = plan(cached).plan
        val state = NotifyState(fired = first.notices.flatMap { it.firedKeys }.associateWith { now.toEpochMilli() }.toMutableMap())
        assertTrue(plan(cached, state = state).plan.notices.isEmpty())
        assertTrue(NotificationPlanner.plan(Snapshot(now, cached.assignments, emptyList()), state, now, settings, zone).notices.isEmpty())
    }

    @Test fun staleCacheIsExcludedButLocalRemindersStillDeliver() {
        val mine = MyDeadline(5, "서류", now.plusSeconds(7200), reminders = listOf(now))
        val stale = CachedSnapshot(now.toEpochMilli() - SnapshotFallback.MAX_AGE_MS - 1, listOf(assignment()))
        assertEquals(listOf("mine-remind:5"), plan(stale, listOf(mine), s = settings.copy(deadlineAlerts = false)).plan.notices.map { it.tag })
    }

    @Test fun courseMuteRecordsDeadlineSilentlyWithoutMutingPersonalReminder() {
        val filter = CourseFilter().with(CourseRef(1, "과목1"), CourseMode.MUTE)
        val mine = MyDeadline(5, "서류", now.plusSeconds(7200), reminders = listOf(now))
        val result = plan(CachedSnapshot(now.toEpochMilli(), listOf(assignment())), listOf(mine), filter = filter)
        assertEquals(listOf("mine-remind:5"), result.plan.notices.map { it.tag })
        assertTrue("deadline-m:1:2:10" in result.plan.silentKeys)
    }

    @Test fun quietHoursDelayCachedDeadlinesButExplicitReminderKeepsItsContract() {
        val quiet = settings.copy(quietEnabled = true, quietStartMinutes = 11 * 60, quietEndMinutes = 13 * 60)
        val cached = CachedSnapshot(now.toEpochMilli(), listOf(assignment().copy(due = now.plusSeconds(7200))))
        val mine = MyDeadline(5, "서류", now.plusSeconds(7200), reminders = listOf(now))
        val result = plan(cached, listOf(mine), s = quiet)
        assertEquals(listOf("mine-remind:5"), result.plan.notices.map { it.tag })
        assertEquals(now.plusSeconds(3600), result.nextAt)
    }

    @Test fun cachedClassReminderRespectsCourseFilterAndDedup() {
        val day = TimetableLogic.Day(now.atZone(zone).toLocalDate(), "목", listOf(
            TimetableLogic.ClassRow("12:05", "13:00", "과목1", 1, null, "정규"),
        ), emptyList())
        val s = settings.copy(deadlineAlerts = false, classAlerts = true)
        val first = plan(s = s, days = listOf(day)).plan.notices.single()
        assertEquals(Channel.CLASS, first.channel)
        val state = NotifyState(fired = first.firedKeys.associateWith { now.toEpochMilli() }.toMutableMap())
        assertTrue(plan(state = state, s = s, days = listOf(day)).plan.notices.isEmpty())
        assertTrue(plan(s = s, days = listOf(day), filter = CourseFilter().with(CourseRef(1, "과목1"), CourseMode.MUTE)).plan.notices.isEmpty())
    }

    @Test fun overduePersonalReminderSurvivesStartupUntilDueOrAlreadyDelivered() {
        val mine = MyDeadline(5, "서류", now.plusSeconds(7200), reminders = listOf(now.minusSeconds(60)))
        assertTrue(TimedNotifications.hasPersonalReminders(listOf(mine), now, emptySet()))
        assertFalse(TimedNotifications.hasPersonalReminders(listOf(mine), now,
            setOf(MyDeadlines.reminderKey(mine, mine.reminders.single()))))
        assertFalse(TimedNotifications.hasPersonalReminders(listOf(mine), now.plusSeconds(7200), emptySet()))
    }

    @Test fun deniedPostIsNotRecordedAndCanBeRetried() {
        var state = NotifyState()
        val notice = Notice(Channel.DEADLINE, "제목", "본문", tag = "n", firedKeys = listOf("key"))
        fun deliver(allowed: Boolean) = NoticeDelivery.deliver(now, { state }, { state = it }, { allowed }) { Plan(listOf(notice)) }
        assertTrue(deliver(false).notices.isEmpty())
        assertFalse("key" in state.fired)
        assertEquals(1, deliver(true).notices.size)
        assertTrue("key" in state.fired)
    }

    @Test fun notificationGrantRecoversDueReminderEvenWithoutStoredAlarmTarget() {
        val mine = listOf(MyDeadline(5, "서류", now.plusSeconds(7200), reminders = listOf(now.minusSeconds(60))))
        var state = NotifyState()
        fun deliver(allowed: Boolean) = NoticeDelivery.deliver(now, { state }, { state = it }, { allowed }) { current ->
            plan(mine = mine, state = current, s = settings.copy(deadlineAlerts = false)).plan
        }
        assertTrue(deliver(false).notices.isEmpty())
        val due = TimedNotifications.hasDuePersonalReminders(mine, now, state.fired.keys)
        assertTrue(due)
        // The failed alarm has been consumed; on permission grant/startup, no target must still recover it.
        assertEquals(NotifySchedule.Rearm.NONE, NotifySchedule.rearmAction(0L, now.toEpochMilli(), false))
        assertEquals(NotifySchedule.Rearm.FIRE_NOW, NotifySchedule.rearmAction(0L, now.toEpochMilli(), due))
        assertEquals(NotifySchedule.Rearm.FIRE_NOW, NotifySchedule.rearmAction(now.plusSeconds(3600).toEpochMilli(), now.toEpochMilli(), due))
        assertEquals(1, deliver(true).notices.size)
        assertFalse(TimedNotifications.hasDuePersonalReminders(mine, now, state.fired.keys))
    }

    @Test fun refreshCrossingTenMinuteBoundaryDeliversNewlyDueLocalNotice() {
        val cached = CachedSnapshot(now.minusSeconds(1).toEpochMilli(), listOf(assignment()))
        var state = NotifyState(fired = mutableMapOf("deadline-m:1:2:60" to now.minusSeconds(3600).toEpochMilli()))
        val beforeBoundary = now.minusSeconds(1)
        val afterRefresh = now.plusSeconds(2)
        // The network worker planned with its start time, before the next window opened.
        assertTrue(NotificationPlanner.plan(Snapshot(beforeBoundary, cached.assignments, emptyList()), state,
            beforeBoundary, settings, zone).notices.isEmpty())
        val posts = mutableListOf<Notice>()
        val delivered = NoticeDelivery.deliver(afterRefresh, { state }, { state = it }, { posts += it; true }) { current ->
            TimedNotifications.plan(cached, emptyList(), emptyList(), current, afterRefresh, settings, zone = zone).plan
        }
        assertEquals(1, delivered.notices.size)
        assertEquals(1, posts.size)
        assertTrue("deadline-m:1:2:10" in state.fired)
    }

    @Test fun broaderWindowKeyDoesNotSuppressNewTighterWindowAndFullReplayIsSkipped() {
        var state = NotifyState(fired = mutableMapOf("loose" to now.toEpochMilli()))
        val notice = Notice(Channel.DEADLINE, "제목", "본문", tag = "n", firedKeys = listOf("tight", "loose"))
        var posts = 0
        fun deliver() = NoticeDelivery.deliver(now, { state }, { state = it }, { posts++; true }) { Plan(listOf(notice)) }
        assertEquals(1, deliver().notices.size)
        assertTrue("tight" in state.fired)
        assertTrue(deliver().notices.isEmpty())
        assertEquals(1, posts)
    }

    @Test fun simultaneousAlarmAndWorkerPostOnlyOnce() {
        var state = NotifyState()
        val posts = AtomicInteger()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val notice = Notice(Channel.DEADLINE, "제목", "본문", tag = "n", firedKeys = listOf("key"))
        repeat(2) {
            Thread {
                try {
                    start.await()
                    NoticeDelivery.deliver(now, { state }, { state = it }, { posts.incrementAndGet(); true }) { current ->
                        if ("key" in current.fired) Plan(emptyList()) else Plan(listOf(notice))
                    }
                } finally { finished.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, posts.get())
        assertTrue("key" in state.fired)
    }
}
