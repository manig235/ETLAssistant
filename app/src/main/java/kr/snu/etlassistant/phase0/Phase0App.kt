package kr.snu.etlassistant.phase0

import android.app.Application

class Phase0App : Application() {
    override fun onCreate() {
        super.onCreate()
        kr.snu.etlassistant.chat.AppAppearance.apply(kr.snu.etlassistant.chat.AppAppearance.load(this))
        CodexRunner.init(this)
        Thread({
            try {
                kr.snu.etlassistant.codex.ChatGptAuthStorage.prepare(this)
            } catch (_: Exception) {
                // The session's synchronous prepare also fails closed and reports the error.
                CodexRunner.log.line("[auth] 로그인 정보 보호를 완료하지 못했습니다. 비서 시작을 보류합니다.")
            }
        }, "secure-auth-migration").apply { isDaemon = true }.start()
        kr.snu.etlassistant.codex.CodexSession.init(this)
        kr.snu.etlassistant.notify.NotifyScheduler.apply(this)
        kr.snu.etlassistant.files.WorkFileCleanup.schedule(this)
        Thread({ runCatching { kr.snu.etlassistant.files.WorkFileCleanup.run(this) } }, "private-file-cleanup").apply { isDaemon = true }.start()
        kr.snu.etlassistant.calendar.CalendarWatch.apply(this) // 캘린더가 바뀌면 곧바로 맞춘다(켜져 있을 때만)
    }
}
