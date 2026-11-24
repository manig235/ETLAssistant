package kr.snu.etlassistant.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import java.time.Instant

/**
 * 캘린더 연동 "캘린더 → 영민이"의 기기 쪽: 설정 저장, 폰 캘린더 목록, 고른 캘린더의 일정 읽기(CalendarContract.Instances).
 * 네트워크는 쓰지 않는다. 권한이 없으면 빈 목록을 돌려준다(화면이 권한을 다시 묻는다).
 */
object CalendarReader {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("calendar_read", Context.MODE_PRIVATE)

    fun settings(context: Context): CalendarReadSettings = CalendarReadSettings.fromJson(prefs(context).getString("settings", null))

    @Synchronized
    fun saveSettings(context: Context, s: CalendarReadSettings) {
        prefs(context).edit().putString("settings", s.toJson()).apply()
        CalendarWatch.apply(context)
        CalendarExport.notifyChanged()
        runCatching { kr.snu.etlassistant.widget.HomeWidgets.dataChanged(context.applicationContext) }
    }

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context): Boolean = canRead(context) &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 폰의 캘린더 하나(목록·선택 화면용). [writable]은 기여자 이상. */
    data class Info(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean, val visible: Boolean)

    /** 보이는 캘린더 목록(앱이 만든 "영민이" 캘린더는 뺀다). 권한이 없으면 빈 목록. */
    fun calendars(context: Context): List<Info> {
        if (!canRead(context)) return emptyList()
        val own = CalendarExport.localCalendarId(context)
        val out = mutableListOf<Info>()
        runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id == own) continue
                    out += Info(id, c.getString(1).orEmpty().ifEmpty { "(이름 없음)" }, c.getString(2).orEmpty(), c.getInt(3),
                        c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR, c.getInt(5) != 0)
                }
            }
        }
        return out.sortedWith(compareBy({ !it.visible }, { it.account }, { it.name }))
    }

    /**
     * 고른 캘린더([ids], 기본은 설정의 선택)에서 [from, to)와 겹치는 일정. 영민이가 넣은 일정은 뺀다.
     * 권한이 없거나 고른 캘린더가 없으면 빈 목록.
     */
    fun events(context: Context, from: Instant, to: Instant, ids: Set<Long> = settings(context).calendarIds): List<CalEvent> {
        if (ids.isEmpty() || !canRead(context)) return emptyList()
        val (qFrom, qTo) = CalendarReadLogic.queryRange(from, to)
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, qFrom)
            ContentUris.appendId(it, qTo)
        }.build()
        val raw = mutableListOf<RawInstance>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.CALENDAR_DISPLAY_NAME, Instances.DISPLAY_COLOR, Instances.TITLE,
                    Instances.EVENT_LOCATION, Instances.DESCRIPTION, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.RRULE,
                    Instances.ORIGINAL_ID, Instances.CUSTOM_APP_URI, Instances.STATUS),
                "${Instances.CALENDAR_ID} IN (${ids.joinToString(",")})", null, "${Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && raw.size < 2000) {
                    raw += RawInstance(
                        eventId = c.getLong(0), calendarId = c.getLong(1), calendarName = c.getString(2).orEmpty(), color = c.getInt(3),
                        title = c.getString(4), location = c.getString(5), description = c.getString(6),
                        begin = c.getLong(7), end = c.getLong(8), allDay = c.getInt(9) != 0, rrule = c.getString(10),
                        originalId = if (c.isNull(11)) null else c.getLong(11), customAppUri = c.getString(12),
                        status = if (c.isNull(13)) null else c.getInt(13),
                    )
                }
            }
        }
        val (exported, own) = CalendarExport.ownEvents(context)
        return CalendarReadLogic.events(raw, ids, exported, own, from, to)
    }

    /** 내 일정·브리핑용: "내 캘린더 일정 보기"가 켜져 있을 때만 읽는다. */
    fun shownEvents(context: Context, from: Instant, to: Instant): List<CalEvent> {
        val s = settings(context)
        return if (s.showEnabled) events(context, from, to, s.calendarIds) else emptyList()
    }

    /** 브리핑 "오늘 일정" 줄(KST 오늘). 꺼져 있거나 권한이 없으면 빈 목록. */
    fun todayLines(context: Context, now: Instant): List<String> {
        val today = now.atZone(CalendarLogic.ZONE).toLocalDate()
        val from = today.atStartOfDay(CalendarLogic.ZONE).toInstant()
        return CalendarReadLogic.briefLines(shownEvents(context, from, from.plusSeconds(86400)), today)
    }

    /** 일정 하나를 캘린더 앱에서 여는 주소. */
    fun eventUri(eventId: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
}
