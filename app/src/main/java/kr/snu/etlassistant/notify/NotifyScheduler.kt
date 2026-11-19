package kr.snu.etlassistant.notify

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * WorkManager 예약.
 *
 * **정확한 시각을 보장하지 않는다.** Doze·앱 대기 버킷·배터리 최적화 때문에 주기 확인과 정기 브리핑은
 * 예정보다 늦게 실행될 수 있다. 브리핑은 4시간 넘게 늦으면 보내지 않는다([NotifySchedule.isStale]).
 * 모든 함수는 멱등이다.
 */
object NotifyScheduler {
    private const val CHECK_NAME = "notify-check"
    private const val CHECK_NOW_NAME = "notify-now"
    private const val BRIEF_NOW_NAME = "notify-brief-now"
    private const val MAX_BRIEF_SLOTS = 2

    private fun briefName(index: Int) = "notify-brief-$index"

    private val networkConstraints: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 저장된 설정에 맞춰 주기 확인과 정기 브리핑을 (재)예약하거나, 모두 꺼져 있으면 취소한다. 설정이 바뀔 때마다 부른다. */
    fun apply(context: Context) {
        val app = context.applicationContext
        NotificationPoster.ensureChannels(app)
        val settings = NotifySettingsStore(app).load()
        val wm = WorkManager.getInstance(app)

        // ① 주기 확인
        if (settings.needsPeriodicCheck) {
            val req = PeriodicWorkRequestBuilder<NotifyCheckWorker>(
                settings.checkIntervalHours.toLong(), TimeUnit.HOURS,
                NotifySchedule.flexMillis(settings.checkIntervalHours), TimeUnit.MILLISECONDS,
            )
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(CHECK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(CHECK_NAME)
        }

        // ② 정기 브리핑(슬롯마다 한 번짜리 작업을 예약하고, 실행 뒤 다시 apply로 다음 것을 예약)
        val slots = if (settings.briefEnabled) settings.briefMinutesOfDay.take(MAX_BRIEF_SLOTS) else emptyList()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val nowMs = now.toInstant().toEpochMilli()
        for (i in 0 until MAX_BRIEF_SLOTS) {
            val minute = slots.getOrNull(i)
            if (minute == null) {
                wm.cancelUniqueWork(briefName(i))
                continue
            }
            val delay = NotifySchedule.nextOccurrenceDelayMillis(now, minute)
            val req = OneTimeWorkRequestBuilder<BriefWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putLong(BriefWorker.KEY_SCHEDULED_AT, nowMs + delay).build())
                .build()
            wm.enqueueUniqueWork(briefName(i), ExistingWorkPolicy.REPLACE, req)
        }
    }

    /** 지금 한 번 확인한다(설정 화면의 "지금 확인" 버튼). 결과는 [NotifyState.lastResult]에 남는다. */
    fun checkNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<NotifyCheckWorker>()
            .setConstraints(networkConstraints)
            .setInputData(Data.Builder().putBoolean(NotifyCheckWorker.KEY_MANUAL, true).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(CHECK_NOW_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /** 지금 브리핑을 한 번 보낸다(미리 보기). `scheduledAt`이 없으므로 stale 검사와 재예약을 건너뛴다. */
    fun briefNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<BriefWorker>()
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(BRIEF_NOW_NAME, ExistingWorkPolicy.REPLACE, req)
    }
}
