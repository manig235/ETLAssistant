package kr.snu.etlassistant.phase0

import android.app.Application

class Phase0App : Application() {
    override fun onCreate() {
        super.onCreate()
        CodexRunner.init(this)
        kr.snu.etlassistant.codex.CodexSession.init(this)
    }
}
