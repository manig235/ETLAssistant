package kr.snu.etlassistant.chat

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** 시스템 설정을 바꾸지 않고 모든 앱 화면·기본 대화상자에 선택한 모드를 적용한다. */
object AppAppearance {
    fun load(context: Context): ThemeMode = ThemeMode.fromKey(
        context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("mode", null)
    )

    fun save(context: Context, mode: ThemeMode) {
        context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit().putString("mode", mode.key).apply()
        apply(mode)
    }

    fun apply(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        })
    }
}
