package kr.snu.etlassistant.phase0

import android.app.Application

class Phase0App : Application() {
    override fun onCreate() {
        super.onCreate()
        kr.snu.etlassistant.chat.AppAppearance.apply(kr.snu.etlassistant.chat.AppAppearance.load(this))
        CodexRunner.init(this)
        kr.snu.etlassistant.codex.CodexSession.init(this)
        kr.snu.etlassistant.notify.NotifyScheduler.apply(this)
    }
}
