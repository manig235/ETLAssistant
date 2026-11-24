package kr.snu.etlassistant.phase0

import android.app.Application

class Phase0App : Application() {
    override fun onCreate() {
        super.onCreate()
        kr.snu.etlassistant.chat.AppAppearance.apply(kr.snu.etlassistant.chat.AppAppearance.load(this))
        CodexRunner.init(this)
        kr.snu.etlassistant.codex.CodexSession.init(this)
        kr.snu.etlassistant.notify.NotifyScheduler.apply(this)
        kr.snu.etlassistant.calendar.CalendarWatch.apply(this) // 캘린더가 바뀌면 곧바로 맞춘다(켜져 있을 때만)
    }
}
