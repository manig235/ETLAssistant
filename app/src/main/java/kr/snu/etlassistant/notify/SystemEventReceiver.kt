package kr.snu.etlassistant.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 재부팅, 앱 업데이트, 시각·시간대 변경 때 알림 예약을 되살린다.
 * 재부팅하면 AlarmManager 알람이 모두 사라져서, 이것이 없으면 다음 주기 확인(최대 약 1시간 뒤)까지
 * 내 마감 알림과 제시간 마감 알림이 울리지 않는다. WorkManager 작업은 WorkManager가 스스로 되살리지만
 * 브리핑은 남은 시간으로 예약하므로 시각이 바뀌면 [NotifyScheduler.apply]로 다시 계산한다.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // 시각·시간대가 바뀌면 브리핑까지 남은 시간을 새로 계산한다. 재부팅·업데이트 때는 아직 유효한 브리핑 예약을 그대로 둔다
        // (WorkManager가 작업을 스스로 되살리고, 다시 계산하면 지금 막 돌 브리핑이 내일로 밀릴 수 있다).
        val timeChanged = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        runCatching { NotifyScheduler.apply(context, forceBriefs = timeChanged) }
        runCatching { kr.snu.etlassistant.update.UpdateScheduler.apply(context) }
        runCatching { kr.snu.etlassistant.widget.HomeWidgets.schedule(context) }
        runCatching { kr.snu.etlassistant.widget.HomeWidgets.dataChanged(context) }
        runCatching { kr.snu.etlassistant.phase0.CodexRunner.log.line("[notify] 예약 되살림: ${intent.action?.substringAfterLast('.')}") }
    }

    companion object {
        val ACTIONS = setOf(
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
