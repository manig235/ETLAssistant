package kr.snu.etlassistant.calendar

/** Keep ownership until deletion succeeded; switching must never orphan an export. */
internal object CalendarCleanupPolicy {
    fun cleanup(permitted: Boolean, delete: () -> Unit, forget: () -> Unit): String? {
        if (!permitted) return "캘린더 권한이 없어 넣은 일정을 지우지 못했어요"
        return try {
            delete()
            forget()
            null
        } catch (_: SecurityException) {
            "캘린더 권한이 없어 넣은 일정을 지우지 못했어요"
        } catch (e: Exception) {
            "넣은 일정을 다 지우지 못했어요(${e.javaClass.simpleName})"
        }
    }

    fun switchTarget(cleanup: () -> String?, activate: () -> String?): String? = cleanup() ?: activate()
}
