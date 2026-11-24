package kr.snu.etlassistant.calendar

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.util.concurrent.Executors

/**
 * 캘린더가 바뀌면 곧바로 맞춘다(2026-11-24). 사용자가 캘린더 앱에서 내 마감 일정을 옮기면 몇 초~1분 안에 앱에 반영된다.
 * - 앱이 꺼져 있어도: WorkManager 일회성 작업 + 콘텐츠 URI 트리거(`Events.CONTENT_URI`, 5초 모아서, 최대 60초). 돌고 나면 다시 건다.
 * - 앱이 떠 있는 동안: [ContentObserver](5초 모아서).
 * - 내 일정 화면을 열 때: 화면이 직접 맞춘다.
 * "영민이 일정을 캘린더에 넣기"+"캘린더에서 고친 것 가져오기"나 "내 캘린더 일정 보기"가 켜져 있을 때만 건다.
 * 앱이 쓴 변경도 트리거를 부르지만, 맞추기는 내용 해시와 다시 읽은 모양을 비교하므로 바뀐 것이 없으면 아무것도 쓰지 않는다(되풀이되지 않는다).
 */
object CalendarWatch {
    private const val WORK = "calendar-change"
    private val DEBOUNCE: Duration = Duration.ofSeconds(5)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar-watch").apply { isDaemon = true } }
    @Volatile private var observer: ContentObserver? = null

    fun needed(context: Context): Boolean {
        val e = CalendarExport.settings(context)
        val r = CalendarReader.settings(context)
        return ((e.enabled && e.pullBack) || r.showEnabled) && CalendarReader.canRead(context)
    }

    /** 앱 시작·설정 변경 때 부른다: 필요하면 작업을 걸고 관찰자를 등록, 아니면 둘 다 끈다. */
    fun apply(context: Context) {
        val app = context.applicationContext
        runCatching {
            if (needed(app)) {
                enqueue(app, ExistingWorkPolicy.KEEP)
                register(app)
            } else {
                WorkManager.getInstance(app).cancelUniqueWork(WORK)
                unregister(app)
            }
        }
    }

    internal fun enqueue(app: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(DEBOUNCE)
            .setTriggerContentMaxDelay(Duration.ofSeconds(60))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, policy, OneTimeWorkRequestBuilder<CalendarChangeWorker>().setConstraints(constraints).build())
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var appRef: Context? = null
    private val run = Runnable { appRef?.let { a -> executor.execute { onChange(a) } } }

    @Synchronized
    private fun register(app: Context) {
        if (observer != null) return
        appRef = app
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, DEBOUNCE.toMillis())
            }
        }
        runCatching { app.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, o) }
            .onSuccess { observer = o }
    }

    @Synchronized
    private fun unregister(app: Context) {
        observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        observer = null
        handler.removeCallbacks(run)
    }

    /** 캘린더가 바뀐 뒤: 맞추고(켜져 있을 때만), 떠 있는 화면에 알린다. */
    internal fun onChange(app: Context) {
        CalendarExport.syncIfEnabled(app)
        CalendarExport.notifyChanged()
    }
}

/** 캘린더 콘텐츠 URI 트리거로 도는 작업. 맞춘 뒤 다시 건다(트리거는 한 번 쓰면 끝나므로). */
class CalendarChangeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        runCatching { CalendarWatch.onChange(app) }
        // 지금 돌고 있는 작업 뒤에 이어 건다(REPLACE는 지금 작업을 취소한다).
        if (CalendarWatch.needed(app)) runCatching { CalendarWatch.enqueue(app, ExistingWorkPolicy.APPEND_OR_REPLACE) }
        return Result.success()
    }
}
