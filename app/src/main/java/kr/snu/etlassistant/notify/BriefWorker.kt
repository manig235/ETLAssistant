package kr.snu.etlassistant.notify

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** 정기 브리핑. 한 번짜리 작업이라 끝난 뒤 다음 브리핑을 직접 예약한다. */
class BriefWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, -1L).takeIf { it >= 0 }
        val settings = NotifySettingsStore(applicationContext).load()
        if (scheduledAt != null && !settings.briefEnabled) return Result.success()

        val outcome = try {
            // 마지막 시도에서만 받아 둔 정보로 보낸다(그 전에는 연결이 돌아오길 기다려 다시 시도).
            NotifyRunner.runBrief(applicationContext, scheduledAtMillis = scheduledAt, allowFallback = runAttemptCount >= MAX_ATTEMPTS)
        } catch (_: Throwable) {
            NotifyRunner.Outcome.RETRY
        }

        // 일시적 실패(네트워크 등)는 다시 시도한다. 이때는 apply()를 부르지 않는다:
        // 같은 이름을 REPLACE하므로 재시도 대기 중인 이 작업 자신이 취소돼 버린다.
        // 너무 늦어지면 runBrief가 stale로 건너뛰므로 재시도가 무한히 쌓이지는 않는다.
        if (outcome == NotifyRunner.Outcome.RETRY && runAttemptCount < MAX_ATTEMPTS) return Result.retry()

        // 끝났다(성공, 건너뜀, 포기): 이 슬롯의 예약을 지우고 다음 브리핑을 예약한다(minLead 덕분에 방금 실행한 슬롯은 다시 잡히지 않는다).
        // 같은 이름을 REPLACE하므로 이 작업 자신이 취소 처리되지만, 이미 할 일을 마친 뒤다.
        if (scheduledAt != null) runCatching { NotifyScheduler.briefFinished(applicationContext, scheduledAt) }
        return Result.success()
    }

    companion object {
        const val KEY_SCHEDULED_AT = "scheduledAt"
        private const val MAX_ATTEMPTS = 3
    }
}
