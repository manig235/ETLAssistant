package kr.snu.etlassistant.chat

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "시스템 설정"), LIGHT("light", "라이트"), DARK("dark", "다크");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
