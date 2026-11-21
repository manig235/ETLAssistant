package kr.snu.etlassistant.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.snu.etlassistant.phase0.R

/** 알림 채널 생성과 게시. 알림 내용은 신뢰할 수 없는 eTL 데이터를 그대로 보여 줄 뿐 지시로 해석하지 않는다. */
object NotificationPoster {
    private const val ETL_ORIGIN = "https://myetl.snu.ac.kr/"
    private const val CHAT_ACTIVITY = "kr.snu.etlassistant.chat.ChatActivity"

    /** 채널들을 만든다(멱등: 이미 있으면 이름만 갱신되고 사용자가 바꾼 설정은 유지된다). */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // HIGH = 화면 위 팝업(헤드업). 브리핑은 긴 요약이라 팝업 없이 알림창에만 둔다.
            nm.createNotificationChannel(NotificationChannel(Channel.DEADLINE.id, "마감 알림", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(Channel.ANNOUNCEMENT.id, "공지 알림", upgraded(nm, LEGACY_ANNOUNCEMENT)))
            nm.createNotificationChannel(NotificationChannel(Channel.BRIEF.id, "정기 브리핑", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(Channel.CHANGE.id, "새 과제·마감 변경·성적", upgraded(nm, LEGACY_CHANGE)))
            nm.createNotificationChannel(NotificationChannel(Channel.CLASS.id, "수업 알림", NotificationManager.IMPORTANCE_HIGH))
            nm.deleteNotificationChannel(LEGACY_ANNOUNCEMENT)
            nm.deleteNotificationChannel(LEGACY_CHANGE)
        }
    }

    /** 중요도를 DEFAULT로 만들었던 옛 채널 ID(팝업으로 올리며 바꿨다). */
    internal const val LEGACY_ANNOUNCEMENT = "announcement"
    internal const val LEGACY_CHANGE = "change"

    /** 새 채널의 중요도: 사용자가 옛 채널을 꺼 두었으면 계속 꺼 두고, 아니면 HIGH(팝업). */
    private fun upgraded(nm: NotificationManager, legacyId: String): Int =
        if (nm.getNotificationChannel(legacyId)?.importance == NotificationManager.IMPORTANCE_NONE) NotificationManager.IMPORTANCE_NONE
        else NotificationManager.IMPORTANCE_HIGH

    /** 알림 권한(Android 13+)이 있고 앱 알림이 꺼져 있지 않은가. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 알림을 띄운다. 예외를 던지지 않으며, 권한이 없으면 아무것도 하지 않는다. */
    @SuppressLint("MissingPermission")
    fun post(context: Context, notice: Notice, settings: NotifySettings) {
        runCatching {
            if (!canPost(context)) return
            val id = NotifySchedule.notificationId(notice.tag)

            // 탭하면 채팅 화면(클래스 직접 참조는 순환 의존이라 이름으로 지정).
            val open = Intent().setClassName(context, CHAT_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val b = NotificationCompat.Builder(context, notice.channel.id)
                .setSmallIcon(R.drawable.ic_stat_etl)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(if (notice.channel == Channel.DEADLINE) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
                .setPriority(if (notice.channel == Channel.BRIEF) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)

            notice.bigText?.takeIf { it.isNotBlank() }?.let {
                b.setStyle(NotificationCompat.BigTextStyle().bigText(it))
            }

            // eTL 주소만 허용(그 외 URL은 무시).
            notice.url?.takeIf { it.startsWith(ETL_ORIGIN) }?.let { url ->
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val viewPi = PendingIntent.getActivity(context, id xor 0x40000000, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(0, "eTL에서 열기", viewPi)
            }

            if (settings.hideContentOnLockScreen) {
                // 잠금화면에는 내용 없이 일반 문구만.
                val pub = NotificationCompat.Builder(context, notice.channel.id)
                    .setSmallIcon(R.drawable.ic_stat_etl)
                    .setContentTitle("미니 eTL 알림")
                    .setContentText("새 알림이 있어요")
                    .build()
                b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(pub)
            } else {
                b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }

            NotificationManagerCompat.from(context).notify(id, b.build())
            NotifyHistory(context).record(notice)
        }
    }
}
