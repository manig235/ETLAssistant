package kr.snu.etlassistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kr.snu.etlassistant.chat.MyDeadlinesActivity
import kr.snu.etlassistant.chat.TimetableActivity
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.etl.Timetable
import kr.snu.etlassistant.etl.TimetableChangeStore
import kr.snu.etlassistant.etl.TimetableStore
import kr.snu.etlassistant.notify.DeadlineAlarm
import kr.snu.etlassistant.notify.MyDeadlineStore
import kr.snu.etlassistant.notify.NotifyScheduler
import kr.snu.etlassistant.notify.SnapshotCache
import kr.snu.etlassistant.notify.SnapshotFetcher
import kr.snu.etlassistant.phase0.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Codex와 알림 권한 없이 앱과 같은 로컬 데이터를 읽는 홈 화면 위젯. */
object HomeWidgets {
    const val EXTRA_DEADLINE_ID = "widget_deadline_id"
    const val ACTION_COMPLETE = "kr.snu.etlassistant.widget.COMPLETE"
    private const val CLOCK_WORK = "home-widgets-clock"
    private const val SYNC_WORK = "home-widgets-sync"
    private const val WORK_TAG = "home-widgets"

    private fun ids(context: Context, provider: Class<out AppWidgetProvider>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider))

    fun hasWidgets(context: Context): Boolean =
        ids(context, DeadlineWidgetProvider::class.java).isNotEmpty() ||
            ids(context, TimetableWidgetProvider::class.java).isNotEmpty()

    internal fun hasDeadlines(context: Context) = ids(context, DeadlineWidgetProvider::class.java).isNotEmpty()
    internal fun hasTimetable(context: Context) = ids(context, TimetableWidgetProvider::class.java).isNotEmpty()

    /** 저장하는 모든 경로(UI·AI·알림 조회)에서 호출한다. 렌더링 중에는 데이터를 쓰지 않는다. */
    fun dataChanged(context: Context) {
        if (!hasWidgets(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork("home-widgets-render", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRenderWorker>().addTag(WORK_TAG).build())
    }

    fun schedule(context: Context, syncNow: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!hasWidgets(context)) {
            wm.cancelAllWorkByTag(WORK_TAG)
            return
        }
        // 남은 시간 표시만 다시 계산한다. 네트워크는 쓰지 않는다.
        wm.enqueueUniquePeriodicWork(CLOCK_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRenderWorker>(15, TimeUnit.MINUTES).addTag(WORK_TAG).build())
        // 알림을 모두 끄거나 알림 권한이 없어도 위젯의 자료는 갱신한다.
        wm.enqueueUniquePeriodicWork(SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetSyncWorker>(1, TimeUnit.HOURS).addTag(WORK_TAG).build())
        if (syncNow) wm.enqueueUniqueWork("home-widgets-sync-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetSyncWorker>().addTag(WORK_TAG).build())
    }

    @Synchronized
    fun render(context: Context) {
        if (!hasWidgets(context)) return
        val manager = AppWidgetManager.getInstance(context)
        val now = Instant.now()
        val snapshot = SnapshotCache(context).load()
        val items = DeadlineWidgetLogic.items(snapshot?.assignments.orEmpty(), MyDeadlineStore(context).load(), now)
        ids(context, DeadlineWidgetProvider::class.java).forEach { id ->
            val size = size(context, manager.getAppWidgetOptions(id), 110, 110)
            manager.updateAppWidget(id, deadlines(context, items, snapshot?.takenAt, size, now))
        }
        val week = TimetableWidgetLogic.week(TimetableStore(context).load(), TimetableChangeStore(context).load(),
            LocalDate.now(EtlLogic.KST))
        ids(context, TimetableWidgetProvider::class.java).forEach { id ->
            val size = size(context, manager.getAppWidgetOptions(id), 250, 250)
            manager.updateAppWidget(id, timetable(context, week, size, now))
        }
        // 다음 마감은 조회를 기다리지 않고 목록에서 빼고, 월요일/자정에는 주간 범위를 다시 계산한다.
        // 시각별 고유 작업이라 실행 중인 작업을 취소하지 않으며, 오래된 경계 작업도 최신 자료만 그린다.
        val boundary = DeadlineWidgetLogic.nextBoundary(items, now)
        WorkManager.getInstance(context).enqueueUniqueWork("home-widgets-boundary-${boundary.epochSecond}",
            ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WidgetRenderWorker>()
                .setInitialDelay(Duration.between(now, boundary).toMillis().coerceAtLeast(1000), TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG).build())
    }

    data class Size(val width: Int, val height: Int)

    private fun size(context: Context, options: Bundle, defaultWidth: Int, defaultHeight: Int): Size {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        return Size(options.getInt(widthKey, defaultWidth).coerceIn(80, 1200),
            options.getInt(heightKey, defaultHeight).coerceIn(80, 1200))
    }

    private fun activity(context: Context, intent: Intent): PendingIntent = PendingIntent.getActivity(context, 0,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun agenda(context: Context, mineId: Long? = null): PendingIntent = activity(context,
        Intent(context, MyDeadlinesActivity::class.java).apply {
            data = Uri.parse("youngmin-widget://deadline/${mineId ?: "all"}")
            if (mineId != null) putExtra(EXTRA_DEADLINE_ID, mineId)
        })

    private fun deadlines(context: Context, items: List<DeadlineWidgetLogic.Item>, takenAt: Long?, size: Size, now: Instant): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_deadlines)
        views.setOnClickPendingIntent(R.id.widget_header, agenda(context))
        views.setContentDescription(R.id.widget_header, "내 일정 열기, 남은 마감 ${items.size}개")
        views.setTextViewText(R.id.widget_header, "마감 · ${items.size}")
        views.removeAllViews(R.id.widget_items)
        views.setViewVisibility(R.id.widget_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_empty, if (takenAt == null) "저장된 과제 정보가 없어요\n눌러서 내 일정 확인" else "남은 마감이 없어요")
        views.setOnClickPendingIntent(R.id.widget_empty, agenda(context))
        val capacity = DeadlineWidgetLogic.capacity(size.height, context.resources.configuration.fontScale)
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val rowSpace = (size.height - 34f) / capacity
        val titleSp = (rowSpace / (3.8f * fontScale)).coerceIn(8f, 13f)
        for (item in items.take(capacity)) {
            val row = RemoteViews(context.packageName, R.layout.widget_deadline_row)
            val due = DeadlineWidgetLogic.dueLabel(item.due, now)
            val remaining = DeadlineWidgetLogic.remaining(item.due, now)
            row.setTextViewText(R.id.widget_item_title, item.title)
            row.setTextViewTextSize(R.id.widget_item_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
            row.setTextViewTextSize(R.id.widget_item_due, TypedValue.COMPLEX_UNIT_SP, titleSp * .82f)
            row.setTextViewTextSize(R.id.widget_item_remaining, TypedValue.COMPLEX_UNIT_SP, titleSp * .82f)
            row.setTextViewText(R.id.widget_item_due, due)
            row.setTextViewText(R.id.widget_item_remaining, remaining + if (size.width >= 240) " · ${item.source}" else "")
            row.setContentDescription(R.id.widget_item_body, "${item.source}, ${item.title}, 마감 $due, $remaining")
            val open = if (item.mineId != null) agenda(context, item.mineId) else
                activity(context, Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
            row.setOnClickPendingIntent(R.id.widget_item_body, open)
            if (item.mineId != null) {
                row.setViewVisibility(R.id.widget_item_complete, View.VISIBLE)
                row.setContentDescription(R.id.widget_item_complete, "${item.title} 완료로 표시")
                row.setOnClickPendingIntent(R.id.widget_item_complete, PendingIntent.getBroadcast(context, 0,
                    Intent(context, DeadlineWidgetProvider::class.java).setAction(ACTION_COMPLETE)
                        .setData(Uri.parse("youngmin-widget://complete/${item.mineId}"))
                        .putExtra(EXTRA_DEADLINE_ID, item.mineId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            views.addView(R.id.widget_items, row)
        }
        return views
    }

    private fun timetable(context: Context, week: TimetableWidgetLogic.Week, size: Size, now: Instant): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_timetable)
        val end = week.monday.plusDays(6)
        views.setTextViewText(R.id.widget_header, "시간표 · ${week.monday.monthValue}/${week.monday.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}")
        views.setOnClickPendingIntent(R.id.widget_root, activity(context, Intent(context, TimetableActivity::class.java)))
        views.setViewVisibility(R.id.widget_empty, if (week.entries.isEmpty()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_grid, if (week.entries.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.widget_empty, "등록된 수업이 없어요\n눌러서 시간표 열기")
        if (week.entries.isNotEmpty()) {
            // 한 번에 비트맵 하나만 전달하며 전체 RemoteViews를 교체한다(Android 17 메모리 한도).
            views.setImageViewBitmap(R.id.widget_grid, TimetableWidgetRenderer.draw(context, week,
                (size.width - 16).coerceAtLeast(64), (size.height - 42).coerceAtLeast(64), now))
            views.setContentDescription(R.id.widget_grid, week.entries.joinToString("\n") {
                "${it.date.monthValue}/${it.date.dayOfMonth} ${it.row.start}~${it.row.end} ${it.row.course}" +
                    (it.row.room?.let { room -> " $room" } ?: "") + if (it.cancelled) " 휴강" else " ${it.row.kind}"
            })
        }
        return views
    }
}

class DeadlineWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        HomeWidgets.dataChanged(context)
        HomeWidgets.schedule(context, syncNow = true)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        HomeWidgets.dataChanged(context)
    override fun onDisabled(context: Context) = HomeWidgets.schedule(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HomeWidgets.ACTION_COMPLETE) {
            val store = MyDeadlineStore(context)
            val id = intent.getLongExtra(HomeWidgets.EXTRA_DEADLINE_ID, Long.MIN_VALUE)
            val item = store.load().firstOrNull { it.id == id && !it.done } ?: return
            // 항상 완료로 설정한다. 빠르게 두 번 눌러도 다시 미완료가 되지 않는다.
            store.upsert(item.copy(done = true))
            NotifyScheduler.apply(context)
            DeadlineAlarm.rearm(context)
            return
        }
        super.onReceive(context, intent)
    }
}

class TimetableWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        HomeWidgets.dataChanged(context)
        HomeWidgets.schedule(context, syncNow = true)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        HomeWidgets.dataChanged(context)
    override fun onDisabled(context: Context) = HomeWidgets.schedule(context)
}

class WidgetRenderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        HomeWidgets.render(applicationContext)
        return Result.success()
    }
}

/** 위젯 전용 조회는 알림을 게시하거나 알림 기록/기준선을 바꾸지 않는다. */
class WidgetSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        if (!HomeWidgets.hasWidgets(app)) return Result.success()
        val now = Instant.now()
        val snapshot = SnapshotCache(app).load()
        val catalog = TimetableStore(app).loadCatalog()
        val needsAssignments = HomeWidgets.hasDeadlines(app) &&
            (runAttemptCount > 0 || snapshot == null || now.toEpochMilli() - snapshot.takenAt !in 0 until 3_600_000L)
        val needsTimetable = HomeWidgets.hasTimetable(app) &&
            (catalog == null || catalog.metadataVersion < 2 || now.toEpochMilli() - catalog.fetchedAt !in 0 until Timetable.CACHE_MS)
        var failed = false
        try {
            if (needsAssignments || needsTimetable) {
                val token = EtlService.tokens(app).load()
                if (!token.isNullOrEmpty()) {
                    val client = EtlClient(token)
                    if (needsAssignments) {
                        val fresh = SnapshotFetcher.fetch(client, now, announcementDays = 1)
                        SnapshotCache(app).save(fresh)
                        failed = fresh.errors.isNotEmpty()
                    }
                    if (needsTimetable) Timetable.weekly(client, TimetableStore(app), now.toEpochMilli())
                }
            }
        } catch (_: EtlAuthException) {
            // 재로그인은 기존 앱 화면에서 처리한다. 비밀값이나 응답을 로그에 남기지 않는다.
        } catch (_: Exception) {
            failed = true
        } finally {
            HomeWidgets.render(app)
        }
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }
}
