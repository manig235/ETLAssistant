package kr.snu.etlassistant.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.snu.etlassistant.notify.Channel
import kr.snu.etlassistant.notify.Notice
import kr.snu.etlassistant.notify.NotificationPoster
import kr.snu.etlassistant.notify.NotifyHistory
import kr.snu.etlassistant.notify.NotifySchedule
import kr.snu.etlassistant.phase0.R
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 업데이트 확인 설정·상태(폰 안 SharedPreferences). */
class UpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("update_check", Context.MODE_PRIVATE)

    /** 업데이트 알림(기본 켬). 끄면 하루 한 번 확인과 앱 시작 확인을 하지 않는다. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** 마지막으로 확인을 시도한 시각(성공·실패 모두). */
    var lastCheckMs: Long
        get() = prefs.getLong("lastCheck", 0L)
        set(v) = prefs.edit().putLong("lastCheck", v).apply()

    /** 이미 알린 태그(버전마다 한 번만 알린다). */
    var notifiedTag: String?
        get() = prefs.getString("notifiedTag", null)
        set(v) = prefs.edit().putString("notifiedTag", v).apply()

    /** 마지막으로 확인한 최신 Release(지금 버전보다 새것일 때만 남긴다). */
    var latest: ReleaseInfo?
        get() {
            val tag = prefs.getString("latestTag", null) ?: return null
            val url = prefs.getString("latestUrl", null)?.takeIf { UpdateLogic.isAllowedUrl(it) } ?: return null
            return ReleaseInfo(
                tag = tag, version = tag.removePrefix("v").removePrefix("V"), htmlUrl = url, name = "", notes = "",
                apkName = null, apkSize = prefs.getLong("latestSize", -1L).takeIf { it > 0 },
            )
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("latestTag").remove("latestUrl").remove("latestSize")
            else e.putString("latestTag", v.tag).putString("latestUrl", v.htmlUrl).putLong("latestSize", v.apkSize ?: -1L)
            e.apply()
        }
}

/**
 * GitHub Release로 새 버전이 있는지 확인한다(사용자 결정 2026-11-22: Obtainium 없이 앱이 직접 알린다).
 *
 * 보내는 것은 `GET api.github.com/repos/manig235/ETLAssistant/releases/latest` 하나뿐이다. 토큰·계정·기기 정보는 보내지 않고
 * User-Agent도 일반 이름이다. 리다이렉트는 따라가지 않는다. 알림을 누르면 Release 페이지를 브라우저로 열 뿐 앱이 APK를 받지 않는다.
 */
object UpdateChecker {
    private const val USER_AGENT = "youngmin-android"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 512 * 1024

    sealed class Outcome {
        /** 새 버전이 있다. */
        data class Available(val info: ReleaseInfo) : Outcome()
        /** 지금 버전이 최신이다(또는 Release가 아직 없다). */
        data object UpToDate : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun currentVersion(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    /** 앱 시작(채팅 화면) 때: 켜져 있고 마지막 확인 후 6시간이 지났으면 뒤에서 확인하고 필요하면 알린다. */
    fun maybeCheckOnStart(context: Context) {
        val app = context.applicationContext
        val store = UpdateStore(app)
        if (!store.enabled || !UpdateLogic.startupCheckDue(store.lastCheckMs, System.currentTimeMillis())) return
        executor.execute { runCatching { check(app, notify = true) } }
    }

    /**
     * 확인하고 상태를 저장한다. [notify]면 새 버전을 (버전마다 한 번) 알린다. 블로킹이라 메인 스레드에서 부르지 않는다.
     * 동시에 두 번 돌면 뒤의 것은 확인하지 않고 저장된 결과를 돌려준다.
     */
    fun check(context: Context, notify: Boolean): Outcome {
        val app = context.applicationContext
        val store = UpdateStore(app)
        if (!running.compareAndSet(false, true)) {
            return store.latest?.takeIf { UpdateLogic.isNewer(it.tag, currentVersion(app)) }?.let { Outcome.Available(it) }
                ?: Outcome.Failed("이미 확인하는 중이에요")
        }
        try {
            store.lastCheckMs = System.currentTimeMillis()
            val outcome = fetch(currentVersion(app))
            when (outcome) {
                is Outcome.Available -> {
                    store.latest = outcome.info
                    if (notify && store.enabled && UpdateLogic.shouldNotify(outcome.info, currentVersion(app), store.notifiedTag)) {
                        if (post(app, outcome.info)) store.notifiedTag = outcome.info.tag
                    }
                }
                Outcome.UpToDate -> store.latest = null
                is Outcome.Failed -> {}
            }
            return outcome
        } finally {
            running.set(false)
        }
    }

    private fun fetch(current: String?): Outcome {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(UpdateLogic.LATEST_API).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            when (val code = conn.responseCode) {
                200 -> {
                    val body = readCapped(conn) ?: return Outcome.Failed("응답이 너무 커요")
                    val info = UpdateLogic.parseRelease(body) ?: return Outcome.UpToDate
                    if (UpdateLogic.isNewer(info.tag, current)) Outcome.Available(info) else Outcome.UpToDate
                }
                404 -> Outcome.UpToDate // 아직 Release가 없다.
                else -> Outcome.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readCapped(conn: HttpURLConnection): String? {
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    /** 업데이트 알림을 띄운다. 누르면 Release 페이지(허용된 주소만)를 연다. @return 띄웠으면 true. */
    @SuppressLint("MissingPermission")
    fun post(context: Context, info: ReleaseInfo): Boolean = runCatching {
        if (!UpdateLogic.isAllowedUrl(info.htmlUrl) || !NotificationPoster.canPost(context)) return false
        NotificationPoster.ensureChannels(context)
        val tag = "update:${info.tag}"
        val id = NotifySchedule.notificationId(tag)
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = UpdateLogic.notificationTitle(info)
        val text = UpdateLogic.notificationText(info)
        val n = NotificationCompat.Builder(context, Channel.UPDATE.id)
            .setSmallIcon(R.drawable.ic_stat_etl)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        NotifyHistory(context).record(Notice(Channel.UPDATE, title, text, url = info.htmlUrl, tag = tag))
        true
    }.getOrDefault(false)
}
