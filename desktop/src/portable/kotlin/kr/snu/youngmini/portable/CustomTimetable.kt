// Ported from Android 0.3.8 (025ac84); platform storage is implemented in desktop/.
package kr.snu.youngmini.portable

import kr.snu.etlassistant.etl.*

import java.time.LocalTime

/** 직접 추가한 주간 수업은 음수 ID를 사용해 eTL 과목과 구분한다. */
object CustomTimetableLogic {
    fun validationError(course: TimetableLogic.CourseMeetings): String? = when {
        course.courseId >= 0 -> "직접 추가한 수업만 수정할 수 있어요"
        course.name.isBlank() -> "과목명을 입력해 주세요"
        course.name.trim().length > 80 -> "과목명은 80자까지 입력할 수 있어요"
        course.meetings.isEmpty() -> "요일을 선택해 주세요"
        course.meetings.any { it.day.length != 1 || it.day !in TimetableLogic.WEEKDAYS } -> "요일을 확인해 주세요"
        course.meetings.any {
            val start = runCatching { LocalTime.parse(it.start) }.getOrNull()
            val end = runCatching { LocalTime.parse(it.end) }.getOrNull()
            !Regex("\\d{2}:\\d{2}").matches(it.start) || !Regex("\\d{2}:\\d{2}").matches(it.end) ||
                start == null || end == null || !end.isAfter(start)
        } -> "종료 시간은 시작 시간보다 늦어야 해요"
        else -> null
    }

    fun normalize(course: TimetableLogic.CourseMeetings): TimetableLogic.CourseMeetings? {
        if (validationError(course) != null) return null
        return course.copy(
            name = course.name.trim(), professor = TimetableLogic.cleanProfessor(course.professor)?.take(80),
            meetings = course.meetings.map { it.copy(
                start = LocalTime.parse(it.start).toString(), end = LocalTime.parse(it.end).toString(),
                room = it.room?.trim()?.take(80)?.ifBlank { null },
            ) }.distinct().sortedWith(compareBy({ TimetableLogic.WEEKDAYS.indexOf(it.day) }, { it.start })),
        )
    }

    fun merge(catalog: TimetableLogic.Weekly?, custom: List<TimetableLogic.CourseMeetings>): TimetableLogic.Weekly? {
        val local = custom.mapNotNull(::normalize).distinctBy { it.courseId }
        if (catalog == null && local.isEmpty()) return null
        val base = catalog ?: TimetableLogic.Weekly("", 0, emptyList(), emptyList())
        return base.copy(courses = base.courses.filter { it.courseId >= 0 } + local)
    }
}
