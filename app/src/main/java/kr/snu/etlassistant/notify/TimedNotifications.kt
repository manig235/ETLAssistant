package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.TimetableChangeStore
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.etl.TimetableStore
import java.time.Instant
import java.time.ZoneId

/** Alarm delivery uses only local data. Network refresh belongs to the separate worker. */
object TimedNotifications {
    data class Result(val plan: Plan, val nextAt: Instant?)

    fun plan(
        cached: CachedSnapshot?,
        mine: List<MyDeadline>,
        days: List<TimetableLogic.Day>,
        state: NotifyState,
        now: Instant,
        settings: NotifySettings,
        courses: CourseFilter = CourseFilter(),
        zone: ZoneId = ZoneId.systemDefault(),
        casual: Boolean = false,
    ): Result {
        val usable = cached?.takeIf { SnapshotFallback.usable(it, now) }
        val snapshot = MyDeadlines.merge(Snapshot(now, usable?.assignments.orEmpty(), emptyList()), mine, now)
        val deadlines = CourseMute.plan(snapshot, state, now, settings.copy(announcementAlerts = false), courses, zone)
        val local = now.atZone(zone)
        val quiet = Quiet.isQuiet(local.hour * 60 + local.minute, settings)
        val (examDone, examSilent) = MyDeadlines.examDoneNotices(mine, now, state.fired.keys, settings.examDoneAlerts, quiet, casual)
        val reminders = MyDeadlines.reminderNotices(mine, now, state.fired.keys, casual)
        val alertDays = days.map(courses::alertDay)
        val classes = if (settings.classAlerts && !quiet) alertDays.flatMap {
            ClassNotices.plan(it, now, zone, settings.classLeadMinutes, state.fired.keys)
        } else emptyList()
        val notices = deadlines.notices.map { n ->
            // Manual deadlines are current local data; only eTL data needs the cache label.
            if (usable != null && n.firedKeys.any { it.startsWith("deadline-m:") && !it.startsWith("deadline-m:${MyDeadlines.COURSE_ID}:") })
                n.copy(bigText = (n.bigText ?: n.text) + "\n\n※ ${SnapshotFallback.ageLabel(Instant.ofEpochMilli(usable.takenAt), now)} 받아 둔 eTL 정보예요")
            else n
        } + reminders + examDone + classes
        val plan = Plan(notices, deadlines.silentKeys + examSilent)
        val after = state.copy(fired = state.fired.toMutableMap()).also { s ->
            (plan.notices.flatMap { it.firedKeys } + plan.silentKeys).forEach { s.fired[it] = now.toEpochMilli() }
        }
        val next = listOfNotNull(
            NotificationPlanner.nextDeadlineCheckAt(courses.alertable(snapshot), after, now, settings),
            if (settings.classAlerts) ClassNotices.nextAt(alertDays, now, zone, settings.classLeadMinutes, after.fired.keys) else null,
            MyDeadlines.nextReminderAt(mine, now, after.fired.keys),
            // Quiet deadlines remain unfired: revisit when quiet hours end, before their due time.
            if (quiet && settings.deadlineAlerts) nextQuietEnd(now, settings, zone).takeIf { end ->
                courses.alertable(snapshot).assignments.any { !it.submitted && !it.missing && it.due?.isAfter(end) == true }
            } else null,
        ).minOrNull()
        return Result(plan, next)
    }

    /** Overdue explicit reminders are still deliverable until due; don't cancel them on process startup. */
    fun hasPersonalReminders(mine: List<MyDeadline>, now: Instant, fired: Set<String>): Boolean =
        MyDeadlines.nextReminderAt(mine, now, fired) != null || hasDuePersonalReminders(mine, now, fired)

    fun hasDuePersonalReminders(mine: List<MyDeadline>, now: Instant, fired: Set<String>): Boolean =
        MyDeadlines.reminderNotices(mine, now, fired).isNotEmpty() ||
            MyDeadlines.examDoneNotices(mine, now, fired, enabled = true, quiet = false, casual = false).first.isNotEmpty()

    private fun nextQuietEnd(now: Instant, settings: NotifySettings, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        var end = local.toLocalDate().atTime(settings.quietEndMinutes / 60, settings.quietEndMinutes % 60).atZone(zone)
        if (!end.toInstant().isAfter(now)) end = end.plusDays(1)
        return end.toInstant()
    }

    private data class Inputs(
        val cached: CachedSnapshot?, val mine: List<MyDeadline>, val days: List<TimetableLogic.Day>,
        val settings: NotifySettings, val courses: CourseFilter, val zone: ZoneId, val casual: Boolean,
    ) {
        fun plan(state: NotifyState, now: Instant) =
            TimedNotifications.plan(cached, mine, days, state, now, settings, courses, zone, casual)
    }

    private fun readInputs(app: Context, now: Instant): Inputs {
        val settings = NotifySettingsStore(app).load()
        val mine = MyDeadlineStore(app).load()
        val courses = CourseFilterStore(app).load()
        val cached = SnapshotCache(app).load()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val days = if (settings.classAlerts) TimetableStore(app).load()?.let { weekly ->
            val changes = TimetableChangeStore(app).load()
            listOf(TimetableLogic.day(weekly, today, changes), TimetableLogic.day(weekly, today.plusDays(1), changes))
        }.orEmpty() else emptyList()
        val casual = runCatching { kr.snu.etlassistant.codex.AppSettings.casualTone }.getOrDefault(false)
        return Inputs(cached, mine, days, settings, courses, zone, casual)
    }

    /** Short local reads + notification posts only; never fetch, export calendars, or update widgets here. */
    fun deliver(context: Context, now: Instant = Instant.now()) = synchronized(NoticeDelivery.lock) {
        val app = context.applicationContext
        val inputs = readInputs(app, now)
        NoticeDelivery.deliver(app, inputs.settings, now) { state -> inputs.plan(state, now).plan }
        if (!Thread.currentThread().isInterrupted) {
            DeadlineAlarm.schedule(app, inputs.plan(NotifyStateStore(app).load(), now).nextAt)
        }
    }

    /** A refresh can cross a reminder boundary: deliver newly due local notices before selecting the next alarm. */
    fun reschedule(context: Context, now: Instant = Instant.now()) = deliver(context, now)

}

/** Planning, posting and fired keys share one process lock across alarms and network workers. */
internal object NoticeDelivery {
    val lock = Any()

    fun deliver(context: Context, settings: NotifySettings, now: Instant, planner: (NotifyState) -> Plan): Plan {
        NotificationPoster.ensureChannels(context)
        val store = NotifyStateStore(context)
        return deliver(now, store::load, store::save, { NotificationPoster.post(context, it, settings) }, planner)
    }

    /** Injectable local effects let tests cover alarm/worker races and denied notification posts. */
    internal fun deliver(
        now: Instant,
        load: () -> NotifyState,
        save: (NotifyState) -> Unit,
        post: (Notice) -> Boolean,
        planner: (NotifyState) -> Plan,
    ): Plan = synchronized(lock) {
        if (Thread.currentThread().isInterrupted) return@synchronized Plan(emptyList())
        val state = load()
        val planned = planner(state)
        val posted = planned.notices.filter { n ->
            // A tighter deadline also records broader windows; an already-fired broader key
            // must not suppress its newly due tighter key. Suppress only fully delivered notices.
            val hasUnfiredKey = n.firedKeys.isEmpty() || n.firedKeys.any { it !in state.fired }
            !Thread.currentThread().isInterrupted && hasUnfiredKey && post(n)
        }
        (posted.flatMap { it.firedKeys } + planned.silentKeys).forEach { state.fired[it] = now.toEpochMilli() }
        if (planned.markBaselineDone) state.announcementBaselineDone = true
        state.prune(now.toEpochMilli())
        save(state)
        planned.copy(notices = posted)
    }
}
