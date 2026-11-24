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

/** 알림 본문을 눌렀을 때 갈 곳. */
enum class NoticeTap { CHAT_PREFILL, ETL, AGENDA, CHAT }

object NoticeRouting {
    /**
     * 채팅 입력창 채우기(버튼 없이 본문으로) > eTL 주소 > 내 일정(내 마감·시험) > 채팅. 브리핑처럼 아무것도 없으면 앱(채팅)을 연다.
     * eTL 과제와 내 마감이 섞인 묶음은 eTL 주소가 있어 그대로 eTL로 간다.
     */
    fun tap(n: Notice): NoticeTap = when {
        n.prefill != null && n.prefillAction == null -> NoticeTap.CHAT_PREFILL
        NoticeText.safeUrl(n.url) != null -> NoticeTap.ETL
        n.agenda -> NoticeTap.AGENDA
        else -> NoticeTap.CHAT
    }
}

/** 알림 채널 생성과 게시. 알림 내용은 신뢰할 수 없는 eTL 데이터를 그대로 보여 줄 뿐 지시로 해석하지 않는다. */
object NotificationPoster {
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
            nm.createNotificationChannel(NotificationChannel(Channel.UPDATE.id, "앱 업데이트", upgraded(nm, LEGACY_UPDATE)))
            nm.createNotificationChannel(NotificationChannel(Channel.SUBMIT.id, "제출 확인", NotificationManager.IMPORTANCE_HIGH))
            // 정상 제출 확인은 소리·팝업 없이 알림창에만(LOW: DEFAULT는 소리가 난다).
            nm.createNotificationChannel(NotificationChannel(Channel.SUBMIT_OK.id, "정상 제출 확인", NotificationManager.IMPORTANCE_LOW))
            // 새 강의자료는 급하지 않고 한 번에 여러 과목이 올 수 있어 팝업 없이 알림창에만 둔다.
            nm.createNotificationChannel(NotificationChannel(Channel.NEW_FILES.id, "새 강의자료", NotificationManager.IMPORTANCE_DEFAULT))
            nm.deleteNotificationChannel(LEGACY_ANNOUNCEMENT)
            nm.deleteNotificationChannel(LEGACY_CHANGE)
            nm.deleteNotificationChannel(LEGACY_UPDATE)
        }
    }

    private const val CHAT_REQUEST_SALT = 0x5f3c1d
    private const val DRAFT_REQUEST_SALT = 0x2a91e7
    private const val AGENDA_REQUEST_SALT = 0x6b2d48
    private const val AGENDA_ACTIVITY = "kr.snu.etlassistant.chat.MyDeadlinesActivity"
    /** HomeWidgets.EXTRA_DEADLINE_ID와 같은 값(내 일정이 그 항목의 창을 연다). */
    private const val EXTRA_DEADLINE_ID = "widget_deadline_id"

    /**
     * 내 일정을 채팅 위에 연다: 뒤로 가면 채팅. 채팅은 기존 알림처럼 NEW_TASK|CLEAR_TOP이라 이미 떠 있으면 그 화면을 쓰고(새로 만들지 않음),
     * 맨 위가 아니라서 내 일정에서 뒤로 갈 때 만들어진다.
     */
    private fun agendaOpen(context: Context, id: Int, mineItemId: Long?, flags: Int): PendingIntent {
        val agenda = Intent().setClassName(context, AGENDA_ACTIVITY)
            .setData(Uri.parse("youngmin-notice://agenda/$id"))
            .apply { if (mineItemId != null) putExtra(EXTRA_DEADLINE_ID, mineItemId) }
        return PendingIntent.getActivities(context, id xor AGENDA_REQUEST_SALT, arrayOf(chatOpen(context, null), agenda), flags)
    }

    private fun chatOpen(context: Context, prefill: String?): Intent =
        Intent().setClassName(context, CHAT_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (prefill != null) putExtra(EXTRA_PREFILL, prefill) }

    /** ChatActivity.EXTRA_PREFILL과 같은 값(알림 쪽은 채팅 화면 클래스에 의존하지 않는다). */
    private const val EXTRA_PREFILL = "prefill"

    /** 중요도를 DEFAULT로 만들었던 옛 채널 ID(팝업으로 올리며 바꿨다). */
    internal const val LEGACY_ANNOUNCEMENT = "announcement"
    internal const val LEGACY_CHANGE = "change"
    internal const val LEGACY_UPDATE = "update"

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
    fun post(context: Context, notice: Notice, settings: NotifySettings): Boolean =
        runCatching {
            if (!canPost(context)) return false
            val id = NotifySchedule.notificationId(notice.tag)

            // eTL 콘텐츠는 LearningX에서 같은 원문으로 이동한다(미설치면 웹).
            val url = NoticeText.safeUrl(notice.url)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val etlIntent = url?.let {
                PendingIntent.getActivity(context, id, LearningXOpenActivity.intent(context, it), flags)
            }
            // 채팅 입력창 채우기(보내지는 않는다). 요청 코드를 알림마다 달리 해 다른 알림의 문구로 덮이지 않게 한다.
            val chatIntent = PendingIntent.getActivity(context, id xor CHAT_REQUEST_SALT, chatOpen(context, notice.prefill), flags)
            val contentIntent = when (NoticeRouting.tap(notice)) {
                NoticeTap.CHAT_PREFILL, NoticeTap.CHAT -> chatIntent
                NoticeTap.ETL -> etlIntent ?: chatIntent
                NoticeTap.AGENDA -> agendaOpen(context, id, notice.mineItemId, flags)
            }

            val b = NotificationCompat.Builder(context, notice.channel.id)
                .setSmallIcon(R.drawable.ic_stat_etl)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(if (notice.channel == Channel.DEADLINE) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
                .setPriority(when (notice.channel) {
                    Channel.SUBMIT_OK -> NotificationCompat.PRIORITY_LOW
                    Channel.BRIEF, Channel.NEW_FILES -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_HIGH
                })

            notice.bigText?.takeIf { it.isNotBlank() }?.let {
                b.setStyle(NotificationCompat.BigTextStyle().bigText(it))
            }

            // 버튼 순서: 내 일정에 등록 / 일정 정리 부탁(비서) / eTL에서 열기(최대 3개).
            notice.draft?.let { draft ->
                val open = kr.snu.etlassistant.chat.DraftIntents.open(context, draft)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setData(Uri.parse("youngmin-draft://announcement/$id"))
                val label = ScheduleDraft.fromJson(draft)?.actionLabel ?: ScheduleDrafts.ACTION
                b.addAction(0, label, PendingIntent.getActivity(context, id xor DRAFT_REQUEST_SALT, open, flags))
            }
            if (notice.prefill != null && notice.prefillAction != null) {
                b.addAction(0, notice.prefillAction, chatIntent)
            }
            if (etlIntent != null) {
                b.addAction(0, "eTL에서 열기", etlIntent)
            }

            if (settings.hideContentOnLockScreen) {
                // 잠금화면에는 내용 없이 일반 문구만.
                val pub = NotificationCompat.Builder(context, notice.channel.id)
                    .setSmallIcon(R.drawable.ic_stat_etl)
                    .setContentTitle("영민이 알림")
                    .setContentText("새 알림이 있어요")
                    .build()
                b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(pub)
            } else {
                b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }

            NotificationManagerCompat.from(context).notify(id, b.build())
            runCatching { NotifyHistory(context).record(notice) }
            true
        }.getOrDefault(false)
}
