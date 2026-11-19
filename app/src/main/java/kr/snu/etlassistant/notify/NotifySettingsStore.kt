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
            deadlineWindowsHours = readInts("deadlineWindowsHours", d.deadlineWindowsHours),
            briefEnabled = prefs.getBoolean("briefEnabled", d.briefEnabled),
            briefMinutesOfDay = readInts("briefMinutesOfDay", d.briefMinutesOfDay).map { it.coerceIn(0, 1439) }.distinct().take(2).ifEmpty { d.briefMinutesOfDay },
            checkIntervalHours = prefs.getInt("checkIntervalHours", d.checkIntervalHours).let { if (it in setOf(1, 3, 6, 12)) it else d.checkIntervalHours },
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
            .putString("deadlineWindowsHours", JSONArray(s.deadlineWindowsHours).toString())
            .putBoolean("briefEnabled", s.briefEnabled)
            .putString("briefMinutesOfDay", JSONArray(s.briefMinutesOfDay).toString())
            .putInt("checkIntervalHours", s.checkIntervalHours)
            .putBoolean("quietEnabled", s.quietEnabled)
            .putInt("quietStartMinutes", s.quietStartMinutes)
            .putInt("quietEndMinutes", s.quietEndMinutes)
            .putBoolean("hideContentOnLockScreen", s.hideContentOnLockScreen)
            .apply()
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
