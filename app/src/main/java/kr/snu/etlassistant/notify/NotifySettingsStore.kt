package kr.snu.etlassistant.notify

import android.content.Context
import org.json.JSONArray

/** [NotifySettings]와 [NotifyState]를 앱 내부 SharedPreferences에 저장한다. 비밀값은 여기에 두지 않는다. */
class NotifySettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notify_settings", Context.MODE_PRIVATE)

    fun load(): NotifySettings {
        val d = NotifySettings()
        return NotifySettings(
            deadlineAlerts = prefs.getBoolean("deadlineAlerts", d.deadlineAlerts),
            announcementAlerts = prefs.getBoolean("announcementAlerts", d.announcementAlerts),
            deadlineWindowsMinutes = loadWindows(d.deadlineWindowsMinutes),
            briefEnabled = prefs.getBoolean("briefEnabled", d.briefEnabled),
            briefMinutesOfDay = readInts("briefMinutesOfDay", d.briefMinutesOfDay).map { it.coerceIn(0, 1439) }.distinct().take(2).ifEmpty { d.briefMinutesOfDay },
            quietEnabled = prefs.getBoolean("quietEnabled", d.quietEnabled),
            quietStartMinutes = prefs.getInt("quietStartMinutes", d.quietStartMinutes).coerceIn(0, 1439),
            quietEndMinutes = prefs.getInt("quietEndMinutes", d.quietEndMinutes).coerceIn(0, 1439),
            hideContentOnLockScreen = prefs.getBoolean("hideContentOnLockScreen", d.hideContentOnLockScreen),
        )
    }

    fun save(s: NotifySettings) {
        prefs.edit()
            .putBoolean("deadlineAlerts", s.deadlineAlerts)
            .putBoolean("announcementAlerts", s.announcementAlerts)
            .putString("deadlineWindowsMinutes", JSONArray(NotifySettings.normalizeWindows(s.deadlineWindowsMinutes)).toString())
            .remove("deadlineWindowsHours")
            .putInt("deadlineWindowsVersion", WINDOWS_VERSION)
            .putBoolean("briefEnabled", s.briefEnabled)
            .putString("briefMinutesOfDay", JSONArray(s.briefMinutesOfDay).toString())
            .remove("checkIntervalHours") // 예전 버전의 확인 주기 선택(이제 1시간 고정)
            .putBoolean("quietEnabled", s.quietEnabled)
            .putInt("quietStartMinutes", s.quietStartMinutes)
            .putInt("quietEndMinutes", s.quietEndMinutes)
            .putBoolean("hideContentOnLockScreen", s.hideContentOnLockScreen)
            .apply()
    }

    /**
     * 마감 알림 시점(분). 저장 형식 버전:
     * 1 = 시점을 고르는 화면이 없던 시절(저장값은 옛 기본값 72, 24시간) → 새 기본값,
     * 2 = 시간 단위(`deadlineWindowsHours`) → ×60, 3 = 분 단위(`deadlineWindowsMinutes`).
     */
    private fun loadWindows(default: List<Int>): List<Int> = when (prefs.getInt("deadlineWindowsVersion", 1)) {
        1 -> default
        2 -> NotifySettings.normalizeWindows(readInts("deadlineWindowsHours", default.map { it / 60 }).map { it * 60 })
        else -> NotifySettings.normalizeWindows(readInts("deadlineWindowsMinutes", default))
    }

    private companion object {
        const val WINDOWS_VERSION = 3
    }

    private fun readInts(key: String, def: List<Int>): List<Int> {
        val raw = prefs.getString(key, null) ?: return def
        return try {
            JSONArray(raw).toIntList().ifEmpty { def }
        } catch (_: Exception) {
            def
        }
    }
}

class NotifyStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notify_state", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): NotifyState = NotifyState.fromJson(prefs.getString("state", null))

    @Synchronized
    fun save(s: NotifyState) {
        prefs.edit().putString("state", s.toJson().toString()).apply()
    }

    /** 읽고 고쳐서 저장한다(동시 실행되는 작업끼리 덮어쓰지 않도록 잠금 안에서). */
    @Synchronized
    fun update(block: (NotifyState) -> Unit) {
        val s = load()
        block(s)
        save(s)
    }
}
