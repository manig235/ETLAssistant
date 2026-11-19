package kr.snu.etlassistant.notify

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** 마감·공지 주기 확인. 실패하면 백오프로 최대 3번까지 다시 시도하고 그 뒤에는 다음 주기를 기다린다. */
class NotifyCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        // "지금 확인"(manual)은 알림이 모두 꺼져 있어도 한 번 실행한다.
        if (!manual && !NotifySettingsStore(applicationContext).load().needsPeriodicCheck) return Result.success()
        return when (NotifyRunner.runCheck(applicationContext)) {
            NotifyRunner.Outcome.RETRY -> if (runAttemptCount >= 3) Result.success() else Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_MANUAL = "manual"
    }
}
