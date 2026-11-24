package kr.snu.etlassistant.notify

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** One alarm per next known reminder. Delivery reads local data; refresh is separate deferrable work. */
object DeadlineAlarm {
    private const val REQUEST_CODE = 41
    private const val WORK_NAME = "notify-deadline-alarm"
    private const val PREFS = "deadline_alarm"
    private const val KEY_TARGET = "target"

    fun canScheduleExact(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** User initiated special-access settings only; denial keeps an inexact alarm. */
    fun openExactAlarmSettings(context: Context): Boolean = runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    @Synchronized
    fun schedule(context: Context, at: Instant?) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (at == null) {
            prefs.edit().remove(KEY_TARGET).commit()
            app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
            return
        }
        prefs.edit().putLong(KEY_TARGET, at.toEpochMilli()).commit()
        arm(app)
    }

    fun cancel(context: Context) = schedule(context, null)

    @Synchronized
    fun scheduleEarlier(context: Context, at: Instant?) {
        if (at == null) return
        val app = context.applicationContext
        val cur = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TARGET, 0L)
        if (cur <= System.currentTimeMillis() || at.toEpochMilli() < cur) schedule(app, at)
    }

    /** Rebuild after reboot/time change/access grant. Overdue delivery still uses the bounded receiver. */
    fun rearm(context: Context) {
        val app = context.applicationContext
        val now = Instant.now()
        val fired = NotifyStateStore(app).load().fired.keys
        val mine = MyDeadlineStore(app).load()
        val duePersonal = NotificationPoster.canPost(app) && TimedNotifications.hasDuePersonalReminders(mine, now, fired)
        val target = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TARGET, 0L)
        when (NotifySchedule.rearmAction(target, now.toEpochMilli(), duePersonal)) {
            NotifySchedule.Rearm.ARM -> arm(app)
            NotifySchedule.Rearm.FIRE_NOW -> app.sendBroadcast(Intent(app, DeadlineAlarmReceiver::class.java))
            NotifySchedule.Rearm.NONE -> Unit
        }
        scheduleEarlier(app, MyDeadlines.nextReminderAt(mine, now, fired))
    }

    @SuppressLint("ScheduleExactAlarm")
    @Synchronized
    private fun arm(app: Context) {
        val target = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TARGET, 0L)
        if (target <= 0L) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val request = NotifySchedule.nextAlarm(System.currentTimeMillis(), target, canScheduleExact(app))
        if (request.exact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.atMillis, pendingIntent(app))
                return
            } catch (_: SecurityException) {
                // Special access can be revoked between the check and the call.
            }
        }
        // Android may batch/delay this in Doze. No assumed maximum delay and no intermediate wakeups.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.atMillis, pendingIntent(app))
    }

    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, REQUEST_CODE, Intent(app, DeadlineAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun onFired(context: Context) {
        val app = context.applicationContext
        try {
            TimedNotifications.deliver(app)
        } finally {
            // WorkManager can wait for Doze maintenance/network: cached/manual delivery already ran.
            if (!Thread.currentThread().isInterrupted) {
                val req = OneTimeWorkRequestBuilder<NotifyCheckWorker>()
                    .setInputData(androidx.work.Data.Builder().putBoolean(NotifyCheckWorker.KEY_MANUAL, true).build())
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
            }
        }
    }
}

class DeadlineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val done = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        // No network or other unbounded task in this receiver. A hard deadline releases broadcast + CPU.
        val wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "youngmin:reminder")
        runCatching { wakeLock?.acquire(9_000L) }
        fun finish() {
            if (done.compareAndSet(false, true)) {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                pending.finish()
            }
        }
        lateinit var timeout: Runnable
        val thread = Thread({
            try {
                runCatching { DeadlineAlarm.onFired(context.applicationContext) }
            } finally {
                handler.removeCallbacks(timeout)
                finish()
            }
        }, "reminder-delivery")
        timeout = Runnable { thread.interrupt(); finish() }
        handler.postDelayed(timeout, 8_000L)
        thread.start()
    }
}
