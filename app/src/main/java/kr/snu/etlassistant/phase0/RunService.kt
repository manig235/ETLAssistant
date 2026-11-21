package kr.snu.etlassistant.phase0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Codex 프로세스가 도는 동안만 떠 있는 포그라운드 서비스.
 *
 * 로그인처럼 브라우저로 나가야 하는 단계에서는 앱이 백그라운드가 되는데, 그동안 이 앱 UID의 네트워크
 * (DNS 포함)가 막혀 프록시가 "No address associated with hostname"으로 실패했다(Galaxy, Android 17에서 확인).
 * 포그라운드 서비스를 띄워 프로세스를 포그라운드 서비스 상태로 유지한다.
 */
class RunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "비서 실행 중", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("미니 eTL 비서가 일하는 중")
            .setContentText("작업이 끝나면 자동으로 사라집니다")
            .setSmallIcon(R.drawable.ic_stat_etl)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "codex_run"
        private const val NOTIF_ID = 1

        private val holds = java.util.concurrent.atomic.AtomicInteger(0)

        /** 포그라운드 서비스를 붙잡는다. 처음 붙잡을 때만 서비스를 띄운다. */
        fun acquire(context: Context) {
            if (holds.getAndIncrement() == 0) {
                context.startForegroundService(Intent(context, RunService::class.java))
            }
        }

        /** 붙잡은 것을 놓는다. 모두 놓으면 서비스를 멈춘다. */
        fun release(context: Context) {
            if (holds.decrementAndGet() <= 0) {
                holds.set(0)
                context.stopService(Intent(context, RunService::class.java))
            }
        }
    }
}
