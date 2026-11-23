package kr.snu.etlassistant.chat

import kr.snu.etlassistant.etl.TimetableLogic

/** 시간·요일·겹치는 수업의 배치. 화면 크기와 독립적이라 단위 테스트로 검증한다. */
object TimetableGridLogic {
    data class Block(
        val course: TimetableLogic.CourseMeetings,
        val meeting: TimetableLogic.Meeting,
        val startMinute: Int,
        val endMinute: Int,
        val lane: Int = 0,
        val lanes: Int = 1,
    ) {
        val duration: Int get() = endMinute - startMinute
    }

    data class Grid(val days: List<String>, val startHour: Int, val endHour: Int, val blocks: List<Block>)

    private fun minutes(time: String): Int = time.take(2).toInt() * 60 + time.takeLast(2).toInt()

    fun build(courses: List<TimetableLogic.CourseMeetings>): Grid {
        val valid = courses.flatMap { course ->
            course.meetings.mapNotNull { meeting ->
                if (meeting.day.length != 1 || meeting.day[0] !in TimetableLogic.WEEKDAYS) return@mapNotNull null
                val start = TimetableLogic.normTime(meeting.start) ?: return@mapNotNull null
                val end = TimetableLogic.normTime(meeting.end) ?: return@mapNotNull null
                if (start >= end) return@mapNotNull null
                Block(course, meeting.copy(start = start, end = end), minutes(start), minutes(end))
            }
        }.distinctBy { listOf(it.course.courseId, it.meeting.day, it.startMinute, it.endMinute) }
        val dayCount = maxOf(5, (valid.maxOfOrNull { TimetableLogic.WEEKDAYS.indexOf(it.meeting.day) } ?: 4) + 1)
        val days = TimetableLogic.WEEKDAYS.take(dayCount).map { it.toString() }
        val positioned = mutableListOf<Block>()
        days.forEach { day ->
            val group = mutableListOf<Block>()
            var groupEnd = -1
            fun flush() {
                val ends = mutableListOf<Int>()
                val assigned = group.map { block ->
                    val free = ends.indexOfFirst { it <= block.startMinute }
                    val lane = if (free >= 0) free else ends.size.also { ends += 0 }
                    ends[lane] = block.endMinute
                    block.copy(lane = lane)
                }
                positioned += assigned.map { it.copy(lanes = ends.size) }
                group.clear()
            }
            valid.filter { it.meeting.day == day }
                .sortedWith(compareBy({ it.startMinute }, { it.endMinute }, { it.course.courseId }))
                .forEach { block ->
                    if (group.isNotEmpty() && block.startMinute >= groupEnd) flush()
                    if (group.isEmpty()) groupEnd = block.endMinute else groupEnd = maxOf(groupEnd, block.endMinute)
                    group += block
                }
            if (group.isNotEmpty()) flush()
        }
        return Grid(
            days,
            minOf(9, valid.minOfOrNull { it.startMinute / 60 } ?: 9),
            maxOf(18, valid.maxOfOrNull { (it.endMinute + 59) / 60 } ?: 18).coerceAtMost(24),
            positioned,
        )
    }

    fun roomLabel(raw: String?): String {
        val room = TimetableLogic.cleanRoom(raw) ?: return "등록된 강의실 정보가 없어요"
        val numbered = Regex("^(\\d+(?:-\\d+)?)-(\\d+)$").matchEntire(room)
        return numbered?.let { "${it.groupValues[1]}동 ${it.groupValues[2]}호" } ?: room
    }
}
