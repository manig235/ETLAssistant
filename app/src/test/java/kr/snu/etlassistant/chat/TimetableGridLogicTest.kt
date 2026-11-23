package kr.snu.etlassistant.chat

import kr.snu.etlassistant.etl.TimetableLogic.CourseMeetings
import kr.snu.etlassistant.etl.TimetableLogic.Meeting
import org.junit.Assert.*
import org.junit.Test

class TimetableGridLogicTest {
    private fun course(id: Long, day: String = "월", start: String = "09:00", end: String = "10:00") =
        CourseMeetings(id, "과목 $id", listOf(Meeting(day, start, end, "301-302")))

    @Test fun emptyWeekHasWeekdaysAndDaytimeAxis() {
        val grid = TimetableGridLogic.build(emptyList())
        assertEquals(listOf("월","화","수","목","금"), grid.days)
        assertEquals(9, grid.startHour)
        assertEquals(18, grid.endHour)
        assertTrue(grid.blocks.isEmpty())
    }

    @Test fun minutePrecisionAndLateClasses() {
        val grid = TimetableGridLogic.build(listOf(course(1, start = "9:30", end = "10:45"), course(2, "금", "18:00", "21:50")))
        assertEquals(570, grid.blocks[0].startMinute)
        assertEquals(75, grid.blocks[0].duration)
        assertEquals("09:30", grid.blocks[0].meeting.start)
        assertEquals(22, grid.endHour)
        assertEquals(230, grid.blocks[1].duration)
    }

    @Test fun weekendsAndOutOfDaytimeRangeAreIncluded() {
        val grid = TimetableGridLogic.build(listOf(course(1, "일", "07:00", "08:00"), course(2,"토","23:00","23:59")))
        assertEquals(7, grid.days.size)
        assertEquals(7, grid.startHour)
        assertEquals(24, grid.endHour)
    }

    @Test fun invalidMeetingsAreSkippedAndDuplicatesCollapsed() {
        val grid = TimetableGridLogic.build(listOf(course(1), course(1), course(2,"x"), course(3,start="bad"),
            course(4,start="11:00",end="10:00"),course(5,start="10:00",end="10:00")))
        assertEquals(1, grid.blocks.size)
    }

    @Test fun overlappingClassesArePlacedInSeparateLanes() {
        val grid = TimetableGridLogic.build(listOf(course(1,start="09:00",end="12:00"),course(2,start="09:30",end="10:00"),
            course(3,start="10:00",end="11:00"),course(4,start="12:00",end="13:00")))
        val blocks = grid.blocks.associateBy { it.course.courseId }
        assertEquals(2, blocks.getValue(1).lanes)
        assertNotEquals(blocks.getValue(1).lane, blocks.getValue(2).lane)
        assertEquals(blocks.getValue(2).lane, blocks.getValue(3).lane)
        assertEquals(1, blocks.getValue(4).lanes)
        assertEquals(0, blocks.getValue(4).lane)
    }

    @Test fun differentDaysAndAdjacentClassesDoNotOverlap() {
        val grid = TimetableGridLogic.build(listOf(course(1), course(2,"화"),course(3,start="10:00",end="11:00")))
        assertTrue(grid.blocks.all { it.lane == 0 && it.lanes == 1 })
    }

    @Test fun roomNamesAreReadableWithoutLosingBuildingSuffix() {
        assertEquals("301동 302호", TimetableGridLogic.roomLabel("301-302(무선랜제공)"))
        assertEquals("43-1동 101호", TimetableGridLogic.roomLabel("43-1-101"))
        assertEquals("테니스장", TimetableGridLogic.roomLabel("테니스장"))
        assertEquals("등록된 강의실 정보가 없어요", TimetableGridLogic.roomLabel(null))
    }
}
