package kr.snu.etlassistant.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant

/**
 * 마감 알림을 제시간에 보내기 위한 알람. 주기 확인([NotifyScheduler])은 몇 시간 간격이라 "1시간 전" 알림이
 * 그만큼 늦을 수 있다. 확인할 때마다 다음 알림 시점([NotificationPlanner.nextDeadlineCheckAt])에 알람을 하나 걸고,
 * 알람이 울리면 eTL을 다시 조회해서(그사이 제출했으면 알리지 않는다) 알린다.
 *
 * 정확한 알람 권한(SCHEDULE_EXACT_ALARM)은 쓰지 않는다. 권한 없는 `setAndAllowWhileIdle`은 남은 시간에 비례해
 * 최대 1시간까지 늦게 울린다(기기에서 `window=+1h` 확인). 그래서 목표가 멀면 중간 알람으로 다가가고
 * ([NotifySchedule.nextAlarm], 중간에는 네트워크 없이 다시 걸기만 한다) 마지막 몇 분 안에서만 목표에 맞춘다. 지연은 최대 약 6분.
 * 재부팅하면 알람이 사라지지만 다음 주기 확인이 다시 건다.
 */
object DeadlineAlarm {
    private const val REQUEST_CODE = 41
    private const val WORK_NAME = "notify-deadline-alarm"
    private const val PREFS = "deadline_alarm"
    private const val KEY_TARGET = "target"
    /** 창 경계 바로 뒤에 확인하도록 조금 늦춘다(경계와 같은 시각이면 아직 창 밖으로 계산될 수 있다). */
    private const val SLACK_MS = 20_000L

    /** 다음 마감 알림 시점을 [at]으로 정한다. null이면 알람을 거둔다. */
    fun schedule(context: Context, at: Instant?) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (at == null) {
            prefs.edit().remove(KEY_TARGET).apply()
            app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
            return
        }
        prefs.edit().putLong(KEY_TARGET, at.toEpochMilli() + SLACK_MS).apply()
        arm(app, System.currentTimeMillis())
    }

    fun cancel(context: Context) = schedule(context, null)

    /** 저장된 목표를 향해 다음 알람(중간 또는 마지막)을 건다. */
    private fun arm(app: Context, nowMs: Long) {
        val target = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TARGET, 0L)
        if (target <= 0L) return
        val (at, final) = NotifySchedule.nextAlarm(nowMs, target)
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(app, final))
    }

    private fun pendingIntent(app: Context, final: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            app, REQUEST_CODE, Intent(app, DeadlineAlarmReceiver::class.java).putExtra(EXTRA_FINAL, final),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal const val EXTRA_FINAL = "final"

    /** 알람이 울렸을 때: 중간 알람이면 다시 걸고, 마지막 알람이면 네트워크가 되는 대로 한 번 확인한다(결과에 따라 다음 목표를 다시 정한다). */
    internal fun onFired(context: Context, final: Boolean) {
        val app = context.applicationContext
        if (!final) {
            arm(app, System.currentTimeMillis())
            return
        }
        val req = OneTimeWorkRequestBuilder<NotifyCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }
}

class DeadlineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        DeadlineAlarm.onFired(context, intent.getBooleanExtra(DeadlineAlarm.EXTRA_FINAL, true))
}
