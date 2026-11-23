package kr.snu.etlassistant.etl

import kr.snu.etlassistant.chat.TimetableGridLogic
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CustomTimetableTest {
    private val meeting = TimetableLogic.Meeting("월", "09:00", "10:15", " 301-101 ")
    private val custom = TimetableLogic.CourseMeetings(-42, " 개인 수업 ", listOf(meeting), " 김교수 ")
    private val catalog = TimetableLogic.Weekly("42", 1234, listOf(custom.copy(courseId = 42, name = "수강편람 과목")), listOf("일부 정보 누락"))

    @Test fun validatesRequiredFieldsAndLocalIdentity() {
        assertNull(CustomTimetableLogic.validationError(custom))
        assertNotNull(CustomTimetableLogic.validationError(custom.copy(courseId = 42)))
        assertNotNull(CustomTimetableLogic.validationError(custom.copy(name = " ")))
        assertNotNull(CustomTimetableLogic.validationError(custom.copy(meetings = emptyList())))
        assertNotNull(CustomTimetableLogic.validationError(custom.copy(meetings = listOf(meeting.copy(day = "월화")))))
    }

    @Test fun rejectsInvalidOrOvernightTimes() {
        listOf("oops" to "10:00", "09:00" to "09:00", "10:00" to "09:00", "09:00" to "24:00", "09:00:30" to "10:00").forEach { (start, end) ->
            assertNull(CustomTimetableLogic.normalize(custom.copy(meetings = listOf(meeting.copy(start = start, end = end)))))
        }
    }

    @Test fun normalizesMultipleDaysAndOptionalFields() {
        val result = CustomTimetableLogic.normalize(custom.copy(meetings = listOf(meeting.copy(day = "수"), meeting, meeting)))!!
        assertEquals("개인 수업", result.name)
        assertEquals("김교수", result.professor)
        assertEquals(listOf("월", "수"), result.meetings.map { it.day })
        assertTrue(result.meetings.all { it.room == "301-101" })
        val optional = CustomTimetableLogic.normalize(custom.copy(professor = " ", meetings = listOf(meeting.copy(room = " "))))!!
        assertNull(optional.professor)
        assertNull(optional.meetings.single().room)
    }

    @Test fun localOnlyDoesNotPretendToBeFreshCatalogCache() {
        assertNull(CustomTimetableLogic.merge(null, emptyList()))
        val result = CustomTimetableLogic.merge(null, listOf(custom))!!
        assertEquals(0, result.fetchedAt)
        assertEquals("", result.ids)
        assertEquals(listOf(-42L), result.courses.map { it.courseId })
    }

    @Test fun catalogRefreshKeepsCustomAndMetadataSeparate() {
        val result = CustomTimetableLogic.merge(catalog, listOf(custom))!!
        assertEquals(catalog.ids, result.ids)
        assertEquals(catalog.fetchedAt, result.fetchedAt)
        assertEquals(catalog.errors, result.errors)
        assertEquals(listOf(42L, -42L), result.courses.map { it.courseId })
        val refreshed = catalog.copy(courses = listOf(catalog.courses.single().copy(name = "갱신한 과목")))
        val after = CustomTimetableLogic.merge(refreshed, result.courses.filter { it.courseId < 0 })!!
        assertEquals(listOf("갱신한 과목", "개인 수업"), after.courses.map { it.name })
    }

    @Test fun deletedOrEditedCustomDoesNotReappearFromOldMergedData() {
        val old = CustomTimetableLogic.merge(catalog, listOf(custom))!!
        assertEquals(listOf(42L), CustomTimetableLogic.merge(old, emptyList())!!.courses.map { it.courseId })
        val edited = custom.copy(name = "수정한 수업", meetings = listOf(meeting.copy(day = "토")))
        val result = CustomTimetableLogic.merge(old, listOf(edited))!!
        assertEquals(2, result.courses.size)
        assertEquals("수정한 수업", result.courses.last().name)
        assertEquals("토", result.courses.last().meetings.single().day)
    }

    @Test fun persistenceGridAndDailyAgendaKeepCustomIdentity() {
        val merged = CustomTimetableLogic.merge(catalog, listOf(custom))!!
        val restored = TimetableLogic.fromJson(TimetableLogic.toJson(merged))!!
        val local = restored.courses.single { it.courseId == -42L }
        assertEquals("김교수", local.professor)
        assertEquals("301-101", local.meetings.single().room)
        val blocks = TimetableGridLogic.build(restored.courses).blocks
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.lanes == 2 })
        assertTrue(TimetableLogic.day(restored, LocalDate.of(2026, 11, 23)).classes.any { it.courseId == -42L })
    }

    @Test fun customSundayAddsSaturdayColumnAndRemovingItRestoresWeekdays() {
        val sunday = custom.copy(meetings = listOf(meeting.copy(day = "일")))
        val weekly = CustomTimetableLogic.merge(catalog, listOf(sunday))!!
        val restored = TimetableLogic.fromJson(TimetableLogic.toJson(weekly))!!
        val grid = TimetableGridLogic.build(restored.courses)
        assertEquals(listOf("월", "화", "수", "목", "금", "토", "일"), grid.days)
        assertTrue(grid.blocks.none { it.meeting.day == "토" })
        assertEquals(-42L, grid.blocks.single { it.meeting.day == "일" }.course.courseId)
        val afterDeletion = CustomTimetableLogic.merge(restored, emptyList())!!
        assertEquals(listOf("월", "화", "수", "목", "금"), TimetableGridLogic.build(afterDeletion.courses).days)
    }
}
