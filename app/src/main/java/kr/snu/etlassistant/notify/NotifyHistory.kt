package kr.snu.etlassistant.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 띄운 알림 기록(ttokttok-pet `src/etl/history.js`의 etl-history.log). 실수로 지웠거나 사라진 알림을 다시 볼 수 있게
 * 앱 안에만 [KEEP_DAYS]일, 최대 [MAX_ITEMS]개 남긴다. 실패해도 알림에는 영향이 없다.
 */
data class HistoryEntry(val at: Long, val channel: String, val title: String, val text: String, val bigText: String?, val url: String?)

object NotifyHistoryLogic {
    const val KEEP_DAYS = 90L
    const val MAX_ITEMS = 300

    fun add(list: List<HistoryEntry>, e: HistoryEntry, nowMs: Long): List<HistoryEntry> {
        val cutoff = nowMs - KEEP_DAYS * 24 * 3600 * 1000
        return (list + e).filter { it.at >= cutoff }.sortedBy { it.at }.takeLast(MAX_ITEMS)
    }

    fun toJson(list: List<HistoryEntry>): String = JSONArray().also { arr ->
        list.forEach { e ->
            arr.put(JSONObject().put("at", e.at).put("channel", e.channel).put("title", e.title).put("text", e.text)
                .put("big", e.bigText ?: JSONObject.NULL).put("url", e.url ?: JSONObject.NULL))
        }
    }.toString()

    fun fromJson(s: String?): List<HistoryEntry> {
        val arr = try { JSONArray(s ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(
                o.optLong("at"), o.optString("channel"), o.optString("title"), o.optString("text"),
                if (o.isNull("big")) null else o.optString("big"), if (o.isNull("url")) null else o.optString("url"),
            )
        }
    }

    fun channelLabel(id: String): String = when (id) {
        Channel.DEADLINE.id -> "마감"
        Channel.ANNOUNCEMENT.id, NotificationPoster.LEGACY_ANNOUNCEMENT -> "공지"
        Channel.BRIEF.id -> "브리핑"
        Channel.CHANGE.id, NotificationPoster.LEGACY_CHANGE -> "과제·성적"
        Channel.CLASS.id -> "수업"
        Channel.UPDATE.id, NotificationPoster.LEGACY_UPDATE -> "업데이트"
        else -> "알림"
    }
}

class NotifyHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notify_history", Context.MODE_PRIVATE)

    @Synchronized fun load(): List<HistoryEntry> = NotifyHistoryLogic.fromJson(prefs.getString("items", null))

    @Synchronized fun record(n: Notice, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            val e = HistoryEntry(nowMs, n.channel.id, n.title, n.text, n.bigText, n.url)
            prefs.edit().putString("items", NotifyHistoryLogic.toJson(NotifyHistoryLogic.add(load(), e, nowMs))).apply()
        }
    }

    @Synchronized fun clear() { prefs.edit().remove("items").apply() }
}
