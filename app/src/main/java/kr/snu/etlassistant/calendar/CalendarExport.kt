package kr.snu.etlassistant.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import kr.snu.etlassistant.notify.AssignmentItem
import kr.snu.etlassistant.notify.CourseFilterStore
import kr.snu.etlassistant.notify.MyDeadline
import kr.snu.etlassistant.notify.MyDeadlineStore
import kr.snu.etlassistant.notify.SnapshotCache
import kr.snu.etlassistant.phase0.CodexRunner
import java.time.Instant
import java.util.concurrent.Executors

/**
 * 캘린더 연동 "영민이 → 캘린더"의 기기 쪽: 설정 저장, CalendarContract 읽기·쓰기, 맞추기(내보내기 + 캘린더에서 고친 것 되가져오기).
 * - LOCAL: 앱이 만든 로컬 캘린더(ACCOUNT_TYPE_LOCAL, 계정 "영민이")에 sync adapter로 쓴다. 이 폰 밖으로 나가지 않는다.
 * - GOOGLE: 사용자가 고른 구글 계정 캘린더에 일반 앱으로 쓴다(DIRTY가 표시되어 구글 동기화가 서버로 올린다).
 * 네트워크는 쓰지 않는다. 권한이 없어지면(SecurityException) 내보내기를 끄고 이유를 남긴다.
 */
object CalendarExport {
    const val ACCOUNT_NAME = "영민이"
    const val LOCAL_CALENDAR_NAME = "youngmin_deadlines"
    const val DISPLAY_NAME = "영민이"
    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    private const val COLOR = 0xFF2F6BFF.toInt()

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar-export").apply { isDaemon = true } }

    fun hasPermission(context: Context): Boolean =
        PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    // ---- 설정·상태 -----------------------------------------------------------------

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("calendar_export", Context.MODE_PRIVATE)

    fun settings(context: Context): CalendarExportSettings = CalendarExportSettings.fromJson(prefs(context).getString("settings", null))

    @Synchronized
    fun saveSettings(context: Context, s: CalendarExportSettings) {
        prefs(context).edit().putString("settings", s.toJson()).apply()
        CalendarWatch.apply(context)
    }

    /** 마지막 결과 한 줄과 사용자에게 알릴 이유(권한이 꺼져 멈춤 등). */
    fun lastResult(context: Context): String? = prefs(context).getString("lastResult", null)
    fun note(context: Context): String? = prefs(context).getString("note", null)
    fun clearNote(context: Context) = prefs(context).edit().remove("note").apply()

    private fun setResult(context: Context, result: String?, note: String? = null) {
        prefs(context).edit().apply {
            if (result != null) putString("lastResult", result)
            if (note != null) putString("note", note) else remove("note")
        }.apply()
    }

    private fun loadMapping(context: Context): CalendarLogic.Mapping? = CalendarLogic.mappingFromJson(prefs(context).getString("mapping", null))
    private fun saveMapping(context: Context, calendarId: Long, entries: Map<String, CalendarLogic.Entry>) {
        prefs(context).edit().putString("mapping", CalendarLogic.mappingToJson(calendarId, entries)).apply()
    }
    private fun clearMapping(context: Context) = prefs(context).edit().remove("mapping").apply()

    fun summary(context: Context): String {
        val s = settings(context)
        val r = CalendarReader.settings(context)
        val parts = mutableListOf<String>()
        if (s.enabled) parts += if (s.target == CalendarTarget.LOCAL) "이 폰의 \"$DISPLAY_NAME\" 캘린더에 넣는 중" else "구글 캘린더 \"${s.googleCalendarName}\"에 넣는 중"
        if (r.showEnabled) parts += "내 캘린더 일정 보기"
        if (r.assistantEnabled) parts += "비서가 캘린더 사용"
        if (parts.isEmpty()) return note(context) ?: "꺼짐"
        return parts.joinToString(" · ")
    }

    /** 맞춘 뒤 내 마감이 바뀌었거나 캘린더가 바뀌었을 때 화면을 다시 읽게 하는 알림(앱이 떠 있을 때만). */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun notifyChanged() = listeners.forEach { runCatching { it() } }

    /** 내보낸 일정 ID와 앱 캘린더 ID(캘린더 읽기에서 영민이가 넣은 일정을 뺄 때 쓴다). */
    fun ownEvents(context: Context): Pair<Set<Long>, Set<Long>> {
        val m = loadMapping(context.applicationContext)
        val ids = m?.entries?.values?.filter { !it.tombstone }?.map { it.eventId }?.toSet().orEmpty()
        val local = runCatching { findLocalCalendar(context.contentResolver) }.getOrNull()
        return ids to setOfNotNull(local)
    }

    /** 앱 캘린더(LOCAL "영민이") ID. 없으면 null. 권한이 없으면 null. */
    fun localCalendarId(context: Context): Long? = runCatching { findLocalCalendar(context.contentResolver) }.getOrNull()

    // ---- 동기화 ------------------------------------------------------------------

    /** 화면·저장소에서 부른다: 켜져 있으면 백그라운드 스레드에서 맞춘다. */
    fun requestSync(context: Context) {
        val app = context.applicationContext
        if (!settings(app).enabled) return
        executor.execute { syncIfEnabled(app) }
    }

    /** 알림 확인 작업 끝에서 부른다(같은 스레드에서 바로). 어떤 예외도 밖으로 내보내지 않는다. */
    @Synchronized
    fun syncIfEnabled(context: Context, now: Instant = Instant.now()): String? {
        val app = context.applicationContext
        val s = settings(app)
        if (!s.enabled) return null
        if (!hasPermission(app)) return disableForPermission(app)
        return try {
            sync(app, s, now)
        } catch (_: SecurityException) {
            disableForPermission(app)
        } catch (e: Exception) {
            val msg = "내보내지 못했어요(${e.javaClass.simpleName})"
            setResult(app, msg)
            log(msg)
            msg
        }
    }

    private fun disableForPermission(app: Context): String {
        // 권한이 없으니 지울 수도 없다. 매핑은 남겨 두어 다시 켜면 이어서 맞춘다.
        saveSettings(app, settings(app).copy(enabled = false))
        val msg = "캘린더 권한이 꺼져서 내보내기를 멈췄어요. 다시 켜려면 여기서 켜 주세요."
        setResult(app, null, msg)
        log("권한 없음 · 내보내기 멈춤")
        return msg
    }

    private fun sync(app: Context, s: CalendarExportSettings, now: Instant): String {
        val cr = app.contentResolver
        val calendarId = when (s.target) {
            CalendarTarget.LOCAL -> {
                val id = loadMapping(app)?.calendarId?.takeIf { calendarExists(cr, it) } ?: findLocalCalendar(cr)
                if (id == null) {
                    // 앱 캘린더가 사라졌다(사용자가 캘린더 앱에서 지움 등). 다시 만들지 않고 멈춘다.
                    saveSettings(app, s.copy(enabled = false))
                    clearMapping(app)
                    val msg = "\"$DISPLAY_NAME\" 캘린더가 없어져서 내보내기를 껐어요. 다시 켜면 새로 만들어요."
                    setResult(app, null, msg)
                    return msg
                }
                id
            }
            CalendarTarget.GOOGLE -> {
                val id = s.googleCalendarId
                if (id == null || googleCalendars(cr).none { it.id == id }) {
                    saveSettings(app, s.copy(enabled = false))
                    val msg = "고른 구글 캘린더를 찾지 못해 내보내기를 껐어요. 캘린더를 다시 골라 주세요."
                    setResult(app, null, msg)
                    return msg
                }
                id
            }
        }
        val local = s.target == CalendarTarget.LOCAL
        val stored = loadMapping(app)
        // 다른 캘린더의 매핑이면 쓰지 않는다(대상을 바꿀 때 정리는 switchTarget/disable에서 한다).
        val entries = if (stored?.calendarId == calendarId) stored.entries.toMutableMap() else mutableMapOf()
        // 다시 설치 등으로 매핑을 잃었으면 앱 캘린더에서 앱이 넣은 일정을 지우고 새로 채운다(사용자가 직접 넣은 일정은 두고).
        if (local && stored?.calendarId != calendarId) wipeAppEvents(cr, calendarId)

        val assignments: List<AssignmentItem>? = runCatching { SnapshotCache(app).load()?.assignments }.getOrNull()
        val store = MyDeadlineStore(app)
        var mine = store.load()
        val courses = CourseFilterStore(app).load()
        var desired = CalendarLogic.desired(s, assignments, mine, courses, now)
        val liveRows = liveRows(cr, calendarId, entries.values.filter { !it.tombstone }.map { it.eventId })
        val live = liveRows.keys

        // 캘린더에서 고친 것: 내 마감은 되가져오고, eTL 과제·반복 회차는 되돌린다.
        val edits = CalendarLogic.edits(desired, entries, liveRows, mine, s.pullBack)
        val force = mutableSetOf<String>()
        var pulled = 0
        var reverted = 0
        var repeatingReverted = 0
        var nextId = System.currentTimeMillis()
        for (e in edits) {
            when (e) {
                is CalendarLogic.Edit.Baseline -> entries[e.key]?.let { entries[e.key] = it.copy(rowHash = e.rowHash, reverts = 0) }
                is CalendarLogic.Edit.Accept -> entries[e.key]?.let { entries[e.key] = it.copy(rowHash = e.rowHash) }
                is CalendarLogic.Edit.Revert -> {
                    force += e.key
                    reverted++
                    if (e.repeating) repeatingReverted++
                    entries[e.key]?.let { entries[e.key] = it.copy(reverts = it.reverts + 1) }
                }
                is CalendarLogic.Edit.PullMine -> {
                    val old = mine.firstOrNull { it.id == e.id }
                    val new = old?.let { CalendarLogic.applyPull(it, e, nextId++) }
                    if (old == null || new == null) { force += e.key; reverted++; continue }
                    if (new.id != old.id) store.delete(old.id)
                    store.upsert(new)
                    pulled++
                    // 같은 일정을 새 ID의 키로 옮긴다(지우고 다시 넣지 않게). 다음 맞추기에서 앱 모양(제목 앞말 등)으로 고쳐 쓴다.
                    entries.remove(e.key)?.let { entries[CalendarLogic.mineKey(new)] = it.copy(hash = "", reverts = 0) }
                    log("캘린더에서 고친 내 마감을 반영함")
                }
            }
        }
        if (pulled > 0) {
            mine = store.load()
            desired = CalendarLogic.desired(s, assignments, mine, courses, now)
            runCatching { kr.snu.etlassistant.notify.NotifyScheduler.apply(app) }
        }
        val plan = CalendarLogic.plan(desired, entries, live, now, force)

        plan.tombstone.forEach { k -> entries[k]?.let { entries[k] = it.copy(eventId = CalendarLogic.TOMBSTONE) } }
        plan.forget.forEach { entries.remove(it) }
        for (op in plan.ops) {
            when (op) {
                is CalendarLogic.Op.Insert -> insertEvent(app, cr, calendarId, local, op.item)?.let {
                    // 쓴 뒤 다시 읽은 모양을 기준으로 둔다(캘린더가 값을 바꿔 저장해도 사용자가 고친 것으로 보지 않게).
                    entries[op.item.key] = CalendarLogic.Entry(it, op.item.hash, op.item.start.toEpochMilli(), readRow(cr, it)?.hash ?: op.item.rowHash)
                }
                is CalendarLogic.Op.Update -> {
                    val n = cr.update(eventUri(op.eventId, local), eventValues(app, op.item, calendarId = null, withApp = false), null, null)
                    val reverts = entries[op.item.key]?.takeIf { op.item.key in force }?.reverts ?: 0
                    entries[op.item.key] = if (n > 0) CalendarLogic.Entry(op.eventId, op.item.hash, op.item.start.toEpochMilli(), readRow(cr, op.eventId)?.hash ?: op.item.rowHash, reverts)
                    else CalendarLogic.Entry(CalendarLogic.TOMBSTONE, op.item.hash, op.item.start.toEpochMilli())
                }
                is CalendarLogic.Op.Delete -> {
                    cr.delete(eventUri(op.eventId, local), null, null)
                    entries.remove(op.key)
                }
            }
            // 중간에 죽어도 넣은 일정이 매핑에서 빠지지 않게 조금씩 저장한다.
            saveMapping(app, calendarId, entries)
        }
        saveMapping(app, calendarId, entries)
        val msg = CalendarLogic.resultLabel(plan, now) + CalendarLogic.editsLabel(pulled, reverted, repeatingReverted)
        setResult(app, msg)
        if (plan.ops.isNotEmpty()) log(msg)
        if (pulled > 0) notifyChanged()
        return msg
    }

    /** 내보내기를 켠다(권한은 화면이 미리 받는다). LOCAL이면 앱 캘린더를 찾거나 만든다. 실패하면 이유. */
    @Synchronized
    fun enable(context: Context, s: CalendarExportSettings): String? {
        val app = context.applicationContext
        if (!hasPermission(app)) return "캘린더 권한이 필요해요"
        return try {
            val cr = app.contentResolver
            val targetId = when (s.target) {
                CalendarTarget.LOCAL -> findLocalCalendar(cr) ?: createLocalCalendar(cr) ?: return "\"$DISPLAY_NAME\" 캘린더를 만들지 못했어요"
                CalendarTarget.GOOGLE -> {
                    val id = s.googleCalendarId ?: return "내보낼 구글 캘린더를 골라 주세요"
                    if (googleCalendars(cr).none { it.id == id }) return "고른 구글 캘린더를 찾지 못했어요"
                    id
                }
            }
            // A disabled export can still own events after permission loss or failed cleanup.
            // Never discard that mapping to start a different export.
            if (loadMapping(app)?.calendarId?.let { it != targetId } == true) {
                cleanup(app, settings(app))?.let { return it }
            }
            saveSettings(app, s.copy(enabled = true))
            clearNote(app)
            syncIfEnabled(app)
            null
        } catch (_: SecurityException) {
            "캘린더 권한이 필요해요"
        } catch (e: Exception) {
            "내보내기를 켜지 못했어요(${e.javaClass.simpleName})"
        }
    }

    /**
     * 내보내기를 끈다. LOCAL이면 앱 캘린더를 통째로 지우고, GOOGLE이면 앱이 넣은 일정만 지운다.
     * 권한이 없으면 지우지 못하고 설정만 끈다(이유를 돌려준다).
     */
    @Synchronized
    fun disable(context: Context): String? {
        val app = context.applicationContext
        val s = settings(app)
        saveSettings(app, s.copy(enabled = false))
        clearNote(app)
        val r = cleanup(app, s)
        setResult(app, r ?: "꺼짐 · 넣은 일정을 지웠어요")
        return r
    }

    /** 대상(LOCAL/GOOGLE, 다른 구글 캘린더)을 바꾼다: 이전 대상을 정리하고 새 대상으로 켠다. */
    @Synchronized
    fun switchTarget(context: Context, next: CalendarExportSettings): String? {
        val app = context.applicationContext
        val cur = settings(app)
        return CalendarCleanupPolicy.switchTarget(
            cleanup = { if (cur.enabled || loadMapping(app) != null) cleanup(app, cur) else null },
            activate = {
                saveSettings(app, next.copy(enabled = false))
                if (cur.enabled || next.enabled) enable(app, next) else null
            },
        )
    }

    private fun cleanup(app: Context, s: CalendarExportSettings): String? = CalendarCleanupPolicy.cleanup(
        permitted = hasPermission(app),
        delete = {
            val cr = app.contentResolver
            val mapping = loadMapping(app)
            val localId = findLocalCalendar(cr)
            // The mapping, including when export is off, owns the actual old target.
            // Looking at only today's selection could abandon events after a failed disable.
            val id = mapping?.calendarId ?: localId?.takeIf { s.target == CalendarTarget.LOCAL }
            if (id != null && id == localId) {
                cr.delete(syncAdapter(Calendars.CONTENT_URI), "${Calendars._ID}=?", arrayOf(id.toString()))
                check(!calendarExists(cr, id, requireResult = true)) { "Calendar was not deleted" }
            } else {
                val ids = mapping?.entries?.values?.filter { !it.tombstone }?.map { it.eventId }.orEmpty()
                if (ids.isNotEmpty()) {
                    val ops = ArrayList(ids.map { ContentProviderOperation.newDelete(eventUri(it, local = false)).build() })
                    cr.applyBatch(CalendarContract.AUTHORITY, ops)
                    check(liveRows(cr, mapping!!.calendarId, ids, requireResult = true).isEmpty()) { "Events were not deleted" }
                }
            }
        },
        forget = { clearMapping(app) },
    )

    // ---- CalendarContract -------------------------------------------------------

    data class CalendarInfo(val id: Long, val name: String, val account: String)

    private fun syncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** 앱 캘린더(LOCAL)는 sync adapter로 쓴다(지우면 바로 지워짐). 구글 캘린더는 일반 앱으로 써야 구글 동기화가 바뀐 것을 올린다. */
    private fun eventUri(id: Long, local: Boolean): Uri {
        val u = android.content.ContentUris.withAppendedId(Events.CONTENT_URI, id)
        return if (local) syncAdapter(u) else u
    }

    /** 쓸 수 있는(기여자 이상) 구글 계정 캘린더. "영민이"라는 이름을 앞에. */
    fun googleCalendars(cr: ContentResolver): List<CalendarInfo> {
        val out = mutableListOf<CalendarInfo>()
        cr.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            "${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(GOOGLE_ACCOUNT_TYPE, Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
        }
        return out.sortedWith(compareBy({ !it.name.contains(DISPLAY_NAME) }, { it.account }, { it.name }))
    }

    private fun calendarExists(cr: ContentResolver, id: Long, requireResult: Boolean = false): Boolean {
        val cursor = cr.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), "${Calendars._ID}=?", arrayOf(id.toString()), null)
        if (requireResult) checkNotNull(cursor) { "Calendar query failed" }
        return cursor?.use { it.count > 0 } == true
    }

    private fun findLocalCalendar(cr: ContentResolver): Long? = cr.query(
        Calendars.CONTENT_URI,
        arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.ACCOUNT_NAME}=? AND ${Calendars.NAME}=?",
        arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, ACCOUNT_NAME, LOCAL_CALENDAR_NAME),
        null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun createLocalCalendar(cr: ContentResolver): Long? {
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, LOCAL_CALENDAR_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, DISPLAY_NAME)
            put(Calendars.CALENDAR_COLOR, COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, CalendarLogic.TZ_ID)
        }
        return cr.insert(syncAdapter(Calendars.CONTENT_URI), v)?.lastPathSegment?.toLongOrNull()
    }

    private fun wipeAppEvents(cr: ContentResolver, calendarId: Long) {
        cr.delete(
            syncAdapter(Events.CONTENT_URI),
            "${Events.CALENDAR_ID}=? AND (${Events.CUSTOM_APP_URI} LIKE ? OR ${Events.DESCRIPTION} LIKE ?)",
            arrayOf(calendarId.toString(), CalendarLogic.APP_URI_PREFIX + "%", "%" + CalendarLogic.FOOTER + "%"),
        )
    }

    private val ROW_PROJECTION = arrayOf(Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_LOCATION)

    private fun row(c: android.database.Cursor): CalRow {
        val start = c.getLong(2)
        return CalRow(c.getLong(0), c.getString(1).orEmpty(), start, if (c.isNull(3)) start else c.getLong(3), c.getString(4).orEmpty())
    }

    /** [ids] 중 [calendarId]에 아직 있는(DELETED=0) 일정과 그 지금 모양(제목·시각·장소). */
    private fun liveRows(cr: ContentResolver, calendarId: Long, ids: List<Long>, requireResult: Boolean = false): Map<Long, CalRow> {
        val out = mutableMapOf<Long, CalRow>()
        ids.distinct().chunked(400).forEach { chunk ->
            val cursor = cr.query(
                Events.CONTENT_URI,
                ROW_PROJECTION,
                "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0 AND ${Events._ID} IN (${chunk.joinToString(",")})",
                arrayOf(calendarId.toString()),
                null,
            )
            if (requireResult) checkNotNull(cursor) { "Event query failed" }
            cursor?.use { c -> while (c.moveToNext()) row(c).let { out[it.eventId] = it } }
        }
        return out
    }

    private fun readRow(cr: ContentResolver, id: Long): CalRow? = runCatching {
        cr.query(Events.CONTENT_URI, ROW_PROJECTION, "${Events._ID}=?", arrayOf(id.toString()), null)?.use { c -> if (c.moveToFirst()) row(c) else null }
    }.getOrNull()

    private fun eventValues(app: Context, item: CalItem, calendarId: Long?, withApp: Boolean) = ContentValues().apply {
        if (calendarId != null) put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, item.title)
        put(Events.DESCRIPTION, item.description)
        put(Events.DTSTART, item.start.toEpochMilli())
        put(Events.DTEND, item.end.toEpochMilli())
        put(Events.EVENT_TIMEZONE, CalendarLogic.TZ_ID)
        put(Events.EVENT_END_TIMEZONE, CalendarLogic.TZ_ID)
        put(Events.ALL_DAY, 0)
        put(Events.HAS_ALARM, 0)
        put(Events.AVAILABILITY, if (item.busy) Events.AVAILABILITY_BUSY else Events.AVAILABILITY_FREE)
        put(Events.EVENT_LOCATION, item.location)
        if (withApp) {
            put(Events.CUSTOM_APP_PACKAGE, app.packageName)
            put(Events.CUSTOM_APP_URI, CalendarLogic.APP_URI_PREFIX + item.key)
        }
    }

    private fun insertEvent(app: Context, cr: ContentResolver, calendarId: Long, local: Boolean, item: CalItem): Long? {
        val uri = if (local) syncAdapter(Events.CONTENT_URI) else Events.CONTENT_URI
        val inserted = try {
            cr.insert(uri, eventValues(app, item, calendarId, withApp = true))
        } catch (e: SecurityException) {
            throw e
        } catch (_: IllegalArgumentException) {
            // 앱 연결 열(CUSTOM_APP_*)을 받지 않는 제공자면 빼고 다시 넣는다.
            cr.insert(uri, eventValues(app, item, calendarId, withApp = false))
        }
        return inserted?.lastPathSegment?.toLongOrNull()
    }

    // ---- 권한 없이: 캘린더 앱에 "일정 추가" 화면 열기 -------------------------------------

    /** 한 항목을 캘린더 앱의 일정 추가 화면으로(권한 필요 없음, 사용자가 저장한다). 반복 내 마감은 RRULE을 넣는다. */
    fun insertIntent(title: String, start: Instant, description: String, rrule: String? = null, end: Instant = start, location: String = ""): Intent =
        Intent(Intent.ACTION_INSERT, Events.CONTENT_URI).apply {
            putExtra(Events.TITLE, title)
            putExtra(Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            putExtra(Events.EVENT_TIMEZONE, CalendarLogic.TZ_ID)
            putExtra(Events.AVAILABILITY, if (end.isAfter(start)) Events.AVAILABILITY_BUSY else Events.AVAILABILITY_FREE)
            if (location.isNotEmpty()) putExtra(Events.EVENT_LOCATION, location)
            if (rrule != null) putExtra(Events.RRULE, rrule)
        }

    fun insertIntentFor(a: AssignmentItem): Intent? {
        val item = CalendarLogic.etlItem(a) ?: return null
        return insertIntent(item.title, item.start, item.description)
    }

    /** [series]는 저장된 원래 항목, [occurrence]는 누른 회차(반복이 아니면 null). */
    fun insertIntentFor(series: MyDeadline, occurrence: MyDeadline?): Intent? {
        if (series.repeat == null) {
            val item = CalendarLogic.mineItem(series.copy(done = false))
            return insertIntent(item.title, item.start, item.description, end = item.end, location = item.location)
        }
        val first = CalendarLogic.dtstartFor(series, occurrence) ?: return null
        val fromFirst = first.occurrence == kr.snu.etlassistant.notify.MyDeadlines.firstOccurrence(series)?.occurrence
        return insertIntent(CalendarLogic.mineTitle(series.copy(done = false)), first.due, CalendarLogic.mineDescription(series), CalendarLogic.rrule(series, fromFirst))
    }

    // ---- .ics -------------------------------------------------------------------

    /** 설정의 항목 선택(eTL·내 마감·완료 포함)을 따른 .ics 내용. 받아 둔 정보만 쓴다. */
    fun icsContent(context: Context, now: Instant = Instant.now()): String {
        val app = context.applicationContext
        return CalendarLogic.ics(
            settings(app),
            runCatching { SnapshotCache(app).load()?.assignments }.getOrNull().orEmpty(),
            MyDeadlineStore(app).load(),
            CourseFilterStore(app).load(),
            now,
        )
    }

    private fun log(msg: String) {
        runCatching { CodexRunner.log.line("[calendar] $msg") }
    }
}
