package kr.snu.etlassistant.calendar

import kr.snu.etlassistant.notify.AssignmentItem
import kr.snu.etlassistant.notify.CourseFilter
import kr.snu.etlassistant.notify.MyDeadline
import kr.snu.etlassistant.notify.MyKind
import kr.snu.etlassistant.notify.MyRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 캘린더에서 고친 것 되가져오기(D), 시험 일정 매핑(H), 반복 횟수 RRULE. */
class CalendarSyncEditsTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private val now = kst("2026-11-23T12:00")
    private val settings = CalendarExportSettings(enabled = true)

    private fun asg(cid: Long, aid: Long, due: String) =
        AssignmentItem(cid, "2026-2 자료구조 (001)", aid, "과제 1", kst(due), true, 10.0, "https://myetl.snu.ac.kr/courses/$cid/assignments/$aid", false, false)

    private fun mine(id: Long, due: String, title: String = "실험 보고서", repeat: MyRepeat? = null) = MyDeadline(id, title, kst(due), repeat = repeat)

    private fun exam(id: Long = 7) = MyDeadline(id, "중간고사", kst("2026-11-30T10:00"), kind = MyKind.EXAM, end = kst("2026-11-30T12:00"),
        location = "301동 118호", course = "자료구조", supplies = "공학용 계산기, 신분증")

    /** 지금 desired를 그대로 쓴 매핑(이벤트 ID = 100부터)과 그 모양의 캘린더 행. */
    private fun synced(desired: CalendarLogic.Desired): Pair<Map<String, CalendarLogic.Entry>, MutableMap<Long, CalRow>> {
        val entries = mutableMapOf<String, CalendarLogic.Entry>()
        val rows = mutableMapOf<Long, CalRow>()
        desired.items.forEachIndexed { i, it ->
            val id = 100L + i
            val row = CalRow(id, it.title, it.start.toEpochMilli(), it.end.toEpochMilli(), it.location)
            entries[it.key] = CalendarLogic.Entry(id, it.hash, it.start.toEpochMilli(), row.hash)
            rows[id] = row
        }
        return entries to rows
    }

    @Test fun unchangedRowsNeedNothingAndOldMappingsGetBaseline() {
        val list = listOf(mine(1, "2026-11-25T23:59"))
        val d = CalendarLogic.desired(settings, emptyList(), list, CourseFilter(), now)
        val (entries, rows) = synced(d)
        assertTrue(CalendarLogic.edits(d, entries, rows, list, pullBack = true).isEmpty())
        val old = entries.mapValues { it.value.copy(rowHash = "") }
        assertTrue(CalendarLogic.edits(d, old, rows, list, pullBack = true).single() is CalendarLogic.Edit.Baseline)
    }

    @Test fun movedMyDeadlineIsPulledBackWithNewIdAndShiftedReminders() {
        val d0 = mine(1, "2026-11-25T23:59").copy(reminders = listOf(kst("2026-11-25T21:00")))
        val d = CalendarLogic.desired(settings, emptyList(), listOf(d0), CourseFilter(), now)
        val (entries, rows) = synced(d)
        val id = entries.getValue("mine:1").eventId
        rows[id] = rows.getValue(id).copy(title = "[내 마감] 실험 보고서 최종", start = kst("2026-11-26T18:00").toEpochMilli(), end = kst("2026-11-26T18:00").toEpochMilli())
        val e = CalendarLogic.edits(d, entries, rows, listOf(d0), pullBack = true).single() as CalendarLogic.Edit.PullMine
        assertEquals("실험 보고서 최종", e.title)
        assertEquals(kst("2026-11-26T18:00"), e.start)
        val new = CalendarLogic.applyPull(d0, e, 999)!!
        assertEquals(999L, new.id) // 시각이 바뀌면 새 ID(알림 기록 규칙)
        assertEquals(listOf(kst("2026-11-26T15:01")), new.reminders) // 마감 2시간 59분 전 그대로
        // 제목만 바꾸면 ID는 그대로
        rows[id] = rows.getValue(id).copy(title = "[내 마감] 새 이름", start = d0.due.toEpochMilli(), end = d0.due.toEpochMilli())
        val e2 = CalendarLogic.edits(d, entries, rows, listOf(d0), pullBack = true).single() as CalendarLogic.Edit.PullMine
        assertNull(e2.start)
        assertEquals(1L, CalendarLogic.applyPull(d0, e2, 999)!!.id)
    }

    @Test fun etlAssignmentAndRepeatOccurrenceAreReverted() {
        val rep = mine(2, "2026-11-23T18:00", repeat = MyRepeat(MyRepeat.Freq.WEEKLY))
        val d = CalendarLogic.desired(settings, listOf(asg(5, 50, "2026-11-27T23:59")), listOf(rep), CourseFilter(), now)
        val (entries, rows) = synced(d)
        for (k in listOf("etl:5:50", d.items.first { it.key.startsWith("mine:2:") }.key)) {
            val id = entries.getValue(k).eventId
            rows[id] = rows.getValue(id).copy(start = rows.getValue(id).start + 3600_000)
        }
        val edits = CalendarLogic.edits(d, entries, rows, listOf(rep), pullBack = true)
        assertEquals(2, edits.size)
        assertTrue(edits.all { it is CalendarLogic.Edit.Revert })
        assertEquals(1, edits.count { (it as CalendarLogic.Edit.Revert).repeating })
        // 되돌리기는 plan에서 Update로(내용 해시가 같아도)
        val plan = CalendarLogic.plan(d, entries, rows.keys, now, edits.map { (it as CalendarLogic.Edit.Revert).key }.toSet())
        assertEquals(2, plan.updates)
    }

    @Test fun pullBackOffRevertsMineToo() {
        val d0 = mine(1, "2026-11-25T23:59")
        val d = CalendarLogic.desired(settings, emptyList(), listOf(d0), CourseFilter(), now)
        val (entries, rows) = synced(d)
        val id = entries.getValue("mine:1").eventId
        rows[id] = rows.getValue(id).copy(title = "바뀜")
        assertTrue(CalendarLogic.edits(d, entries, rows, listOf(d0), pullBack = false).single() is CalendarLogic.Edit.Revert)
    }

    @Test fun repeatedRevertsStopAfterLimitToAvoidLoop() {
        val d = CalendarLogic.desired(settings, listOf(asg(5, 50, "2026-11-27T23:59")), emptyList(), CourseFilter(), now)
        val (entries, rows) = synced(d)
        val k = "etl:5:50"
        val id = entries.getValue(k).eventId
        rows[id] = rows.getValue(id).copy(end = rows.getValue(id).end + 1)
        val limited = entries + (k to entries.getValue(k).copy(reverts = CalendarLogic.MAX_REVERTS))
        assertTrue(CalendarLogic.edits(d, limited, rows, emptyList(), pullBack = true).single() is CalendarLogic.Edit.Accept)
    }

    @Test fun appSideChangeWinsOverCalendarEdit() {
        val d0 = mine(1, "2026-11-25T23:59")
        val d = CalendarLogic.desired(settings, emptyList(), listOf(d0), CourseFilter(), now)
        val (entries, rows) = synced(d)
        val id = entries.getValue("mine:1").eventId
        rows[id] = rows.getValue(id).copy(title = "캘린더에서 바꿈")
        val changed = listOf(d0.copy(title = "앱에서 바꿈"))
        val d2 = CalendarLogic.desired(settings, emptyList(), changed, CourseFilter(), now)
        assertTrue(CalendarLogic.edits(d2, entries, rows, changed, pullBack = true).isEmpty())
        assertEquals(1, CalendarLogic.plan(d2, entries, rows.keys, now).updates)
    }

    @Test fun examPullBackKeepsLengthAndReadsLocation() {
        val x = exam()
        val d = CalendarLogic.desired(settings, emptyList(), listOf(x), CourseFilter(), now)
        val (entries, rows) = synced(d)
        val id = entries.getValue("mine:7").eventId
        rows[id] = rows.getValue(id).copy(start = kst("2026-11-30T13:00").toEpochMilli(), end = kst("2026-11-30T14:00").toEpochMilli() + 3600_000, location = "302동 101호")
        val e = CalendarLogic.edits(d, entries, rows, listOf(x), pullBack = true).single() as CalendarLogic.Edit.PullMine
        val n = CalendarLogic.applyPull(x, e, 1000)!!
        assertEquals(kst("2026-11-30T13:00"), n.due)
        assertEquals(kst("2026-11-30T15:00"), n.end)
        assertEquals("302동 101호", n.location)
        // 제목에서 [시험]·과목을 뗀다
        assertEquals("기말고사", CalendarLogic.titleFromCalendar("[시험] 자료구조 기말고사", x))
    }

    @Test fun mappingKeepsRowHashAndReverts() {
        val m = mapOf("mine:1" to CalendarLogic.Entry(5, "h", 10, "r", 2), "etl:1:2" to CalendarLogic.Entry(CalendarLogic.TOMBSTONE, "h2", 20))
        assertEquals(m, CalendarLogic.mappingFromJson(CalendarLogic.mappingToJson(3, m))!!.entries)
        // 예전 형식(3칸)도 읽는다
        val old = CalendarLogic.mappingFromJson("""{"version":1,"calendarId":3,"entries":{"mine:1":[5,"h",10]}}""")!!
        assertEquals(CalendarLogic.Entry(5, "h", 10, "", 0), old.entries["mine:1"])
    }

    // ---- 시험 일정 ----------------------------------------------------------------

    @Test fun examBecomesTimedEventWithLocationAndSupplies() {
        val item = CalendarLogic.mineItem(exam())
        assertEquals("[시험] 자료구조 중간고사", item.title)
        assertEquals(kst("2026-11-30T10:00"), item.start)
        assertEquals(kst("2026-11-30T12:00"), item.end)
        assertEquals("301동 118호", item.location)
        assertTrue(item.busy)
        assertTrue(item.description.startsWith("준비물: 공학용 계산기, 신분증"))
        // 마감 일정의 해시는 예전과 같다(장소 없음): 이미 넣은 일정을 다시 쓰지 않는다.
        val plain = CalendarLogic.mineItem(mine(1, "2026-11-25T23:59"))
        assertEquals(plain.hash, plain.copy().hash)
        assertFalse(plain.busy)
    }

    @Test fun icsHasLocationAndEndForExam() {
        val ics = CalendarLogic.ics(settings, emptyList(), listOf(exam()), CourseFilter(), now)
        assertTrue(ics.contains("DTSTART;TZID=Asia/Seoul:20261130T100000"))
        assertTrue(ics.contains("DTEND;TZID=Asia/Seoul:20261130T120000"))
        assertTrue(ics.contains("LOCATION:301동 118호"))
        assertTrue(ics.contains("SUMMARY:[시험] 자료구조 중간고사"))
        assertTrue(ics.contains("TRANSP:OPAQUE"))
        assertTrue(ics.replace("\r\n ", "").contains("준비물: 공학용 계산기\\, 신분증"))
    }

    // ---- 반복 횟수 ----------------------------------------------------------------

    @Test fun rruleUsesCountOrUntil() {
        val weekly = MyRepeat(MyRepeat.Freq.WEEKLY, setOf(DayOfWeek.MONDAY), count = 12)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=12", CalendarLogic.rrule(mine(1, "2026-11-23T18:00", repeat = weekly)))
        // 나중 회차에서 넣으면 COUNT가 어긋나므로 마지막 회차 시각을 UNTIL로
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20270208T090000Z", CalendarLogic.rrule(mine(1, "2026-11-23T18:00", repeat = weekly), fromFirst = false))
        // 매월 30일 + 횟수: 2월이 빠지는 캘린더 앱과 어긋나므로 UNTIL
        val m30 = mine(1, "2026-11-30T18:00", repeat = MyRepeat(MyRepeat.Freq.MONTHLY, count = 4))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=30;UNTIL=20270228T090000Z", CalendarLogic.rrule(m30))
        // 날짜까지는 예전처럼
        assertEquals("FREQ=DAILY;UNTIL=20261220T145959Z", CalendarLogic.rrule(mine(1, "2026-11-23T18:00", repeat = MyRepeat(MyRepeat.Freq.DAILY, until = LocalDate.parse("2026-12-20")))))
    }
}
