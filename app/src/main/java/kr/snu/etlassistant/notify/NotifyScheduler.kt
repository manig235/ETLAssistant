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

    /** 사용자가 화면에서 누른 "지금 확인"·"지금 브리핑"용: 앱이 앞에 있으니 네트워크가 될 때 돈다. */
    private val networkConstraints: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * 주기 확인·정기 브리핑은 네트워크 제약을 걸지 않는다(2026-11-22 실측). 절전 모드·삼성 앱 얼림·Android 15+ 백그라운드 차단으로
     * 앱 UID의 네트워크가 막히면 CONNECTED 작업은 "미충족"으로 **아예 시작되지 않고**, 사용자가 앱을 열 때 밀린 것이 한꺼번에 돌았다.
     * 제약 없이 제시간에 시작하면 조회가 막혀도 받아 둔 과제로 마감 알림·브리핑을 보내고(SnapshotFallback),
     * 재시도는 지수 백오프로 최대 3번이라 배터리를 크게 쓰지 않는다. 절전 모드가 아니면 작업 실행 중에는 차단이 풀려 새로 조회된다.
     */
    private val backgroundConstraints: Constraints
        get() = Constraints.NONE

    /** 저장된 설정에 맞춰 주기 확인과 정기 브리핑을 (재)예약하거나, 모두 꺼져 있으면 취소한다. 설정이 바뀔 때마다 부른다. */
    fun apply(context: Context) {
        val app = context.applicationContext
        NotificationPoster.ensureChannels(app)
        val settings = NotifySettingsStore(app).load()
        val wm = WorkManager.getInstance(app)

        // ① 주기 확인
        if (settings.needsPeriodicCheck) {
            val req = PeriodicWorkRequestBuilder<NotifyCheckWorker>(
                NotifySettings.CHECK_INTERVAL_HOURS.toLong(), TimeUnit.HOURS,
                NotifySchedule.flexMillis(NotifySettings.CHECK_INTERVAL_HOURS), TimeUnit.MILLISECONDS,
            )
                .setConstraints(backgroundConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(CHECK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(CHECK_NAME)
        }
        // 마감·수업 알림을 모두 끄면 제시간 확인 알람도 거둔다(켜면 다음 확인이 다시 건다).
        // 단, 내 마감에 직접 정한 알림 시각이 남아 있으면 그대로 둔다.
        val hasReminders = MyDeadlines.nextReminderAt(MyDeadlineStore(app).load(), java.time.Instant.now(), emptySet()) != null
        if (!settings.needsTimedCheck && !hasReminders) DeadlineAlarm.cancel(app)

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
                .setConstraints(backgroundConstraints)
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
