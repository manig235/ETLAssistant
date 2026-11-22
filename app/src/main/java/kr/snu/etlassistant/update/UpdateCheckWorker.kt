package kr.snu.etlassistant.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 하루 한 번 새 버전 확인(알림 확인 워커와 별개). 실패하면 두 번까지 다시 시도하고 그 뒤에는 다음 날을 기다린다. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!UpdateStore(applicationContext).enabled) return Result.success()
        return when (UpdateChecker.check(applicationContext, notify = true)) {
            is UpdateChecker.Outcome.Failed -> if (runAttemptCount >= 2) Result.success() else Result.retry()
            else -> Result.success()
        }
    }
}

object UpdateScheduler {
    private const val NAME = "update-check"

    /** 설정에 맞춰 하루 한 번 확인을 예약하거나 취소한다(멱등: 이미 예약돼 있으면 그대로 둔다). */
    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!UpdateStore(app).enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            // 네트워크 제약을 걸지 않는다: 앱 UID 네트워크가 막혀 있으면(APP_BACKGROUND·삼성 얼림) CONNECTED 작업은
            // 아예 시작되지 않는데, 작업이 시작되면 그동안은 차단이 풀린다(docs/phase3-log.md 실측). 실패해도 무해하다.
            .setInitialDelay(1, TimeUnit.HOURS) // 앱 시작 확인과 겹치지 않게.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
