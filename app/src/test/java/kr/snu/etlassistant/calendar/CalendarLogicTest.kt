package kr.snu.etlassistant.calendar

import kr.snu.etlassistant.notify.AssignmentItem
import kr.snu.etlassistant.notify.CourseFilter
import kr.snu.etlassistant.notify.CourseMode
import kr.snu.etlassistant.notify.CourseRef
import kr.snu.etlassistant.notify.MyDeadline
import kr.snu.etlassistant.notify.MyRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarLogicTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private val now = kst("2026-11-23T12:00") // 월요일

    private fun assignment(cid: Long, aid: Long, due: String?, submitted: Boolean = false, name: String = "과제 1", course: String = "2026-2 자료구조 (001)") =
        AssignmentItem(cid, course, aid, name, due?.let(::kst), true, 10.0, "https://myetl.snu.ac.kr/courses/$cid/assignments/$aid", submitted, false)

    private fun mine(id: Long, due: String, repeat: MyRepeat? = null, done: Boolean = false, doneDates: Set<LocalDate> = emptySet(), skipDates: Set<LocalDate> = emptySet(), memo: String = "") =
        MyDeadline(id, "실험 보고서", kst(due), memo = memo, done = done, repeat = repeat, doneDates = doneDates, skipDates = skipDates)

    // ---- ICS 기본 조각 ----------------------------------------------------------

    @Test fun escapesText() {
        assertEquals("a\\,b\\;c\\\\d\\ne\\nf", CalendarLogic.escapeText("a,b;c\\d\ne\r\nf"))
        assertEquals("탭없음", CalendarLogic.escapeText("탭\u0000없음"))
    }

    @Test fun foldsAt75OctetsWithoutSplittingCharacters() {
        val ascii = "DESCRIPTION:" + "x".repeat(200)
        val folded = CalendarLogic.fold(ascii)
        folded.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        assertEquals(ascii, folded.replace("\r\n ", ""))

        val korean = "SUMMARY:" + "가나다라마바사아자차카타파하".repeat(5)
        val f2 = CalendarLogic.fold(korean)
        val lines = f2.split("\r\n")
        assertTrue(lines.size > 1)
        lines.forEach { line ->
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75)
            // 각 줄이 온전한 UTF-8이다(글자를 가운데서 자르지 않음).
            assertEquals(line, String(line.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertTrue(lines.drop(1).all { it.startsWith(" ") })
        assertEquals(korean, f2.replace("\r\n ", ""))
        assertEquals("SHORT:1", CalendarLogic.fold("SHORT:1"))
    }

    @Test fun formatsTimes() {
        assertEquals("20261125T235900", CalendarLogic.local(kst("2026-11-25T23:59")))
        assertEquals("20261125T145900Z", CalendarLogic.utc(kst("2026-11-25T23:59")))
    }

    // ---- RRULE -----------------------------------------------------------------

    @Test fun mapsRepeatRules() {
        assertNull(CalendarLogic.rrule(mine(1, "2026-11-23T18:00")))
        assertEquals("FREQ=DAILY", CalendarLogic.rrule(mine(1, "2026-11-23T18:00", MyRepeat(MyRepeat.Freq.DAILY))))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE",
            CalendarLogic.rrule(mine(1, "2026-11-23T18:00", MyRepeat(MyRepeat.Freq.WEEKLY, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))))
        // 요일을 안 고르면 시작일 요일(월)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", CalendarLogic.rrule(mine(1, "2026-11-23T18:00", MyRepeat(MyRepeat.Freq.WEEKLY))))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=TU,FR",
            CalendarLogic.rrule(mine(1, "2026-11-23T18:00", MyRepeat(MyRepeat.Freq.BIWEEKLY, setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)))))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", CalendarLogic.rrule(mine(1, "2026-11-15T18:00", MyRepeat(MyRepeat.Freq.MONTHLY))))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", CalendarLogic.rrule(mine(1, "2026-10-31T18:00", MyRepeat(MyRepeat.Freq.MONTHLY))))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=30", CalendarLogic.rrule(mine(1, "2026-11-30T18:00", MyRepeat(MyRepeat.Freq.MONTHLY))))
        // 끝 날짜는 그날 KST 23:59:59를 UTC로
        assertEquals("FREQ=DAILY;UNTIL=20261220T145959Z",
            CalendarLogic.rrule(mine(1, "2026-11-23T18:00", MyRepeat(MyRepeat.Freq.DAILY, until = LocalDate.parse("2026-12-20")))))
    }

    @Test fun dtstartIsFirstOccurrenceMatchingTheRule() {
        // 수요일에 만들고 월요일만 고른 매주 반복 → 첫 회차는 다음 월요일
        val weekly = mine(1, "2026-11-25T18:00", MyRepeat(MyRepeat.Freq.WEEKLY, setOf(DayOfWeek.MONDAY)))
        assertEquals(kst("2026-11-30T18:00"), CalendarLogic.dtstartFor(weekly)!!.due)
        // 30일 매월 반복을 2월 말일 회차에서 넣으면 RRULE(BYMONTHDAY=30)에 맞는 3/30부터
        val monthly = mine(2, "2027-01-30T09:00", MyRepeat(MyRepeat.Freq.MONTHLY))
        val feb = MyDeadline(2, "x", kst("2027-02-28T09:00"), repeat = monthly.repeat, occurrence = LocalDate.parse("2027-02-28"))
        assertEquals(kst("2027-03-30T09:00"), CalendarLogic.dtstartFor(monthly, feb)!!.due)
        // 31일은 BYMONTHDAY=-1이라 2월 말일 그대로
        val m31 = mine(3, "2027-01-31T09:00", MyRepeat(MyRepeat.Freq.MONTHLY))
        val feb31 = MyDeadline(3, "x", kst("2027-02-28T09:00"), repeat = m31.repeat, occurrence = LocalDate.parse("2027-02-28"))
        assertEquals(kst("2027-02-28T09:00"), CalendarLogic.dtstartFor(m31, feb31)!!.due)
    }

    // ---- .ics 전체 ---------------------------------------------------------------

    @Test fun buildsIcsWithTimezoneRepeatAndExdates() {
        val filter = CourseFilter().with(CourseRef(77, "숨긴 과목"), CourseMode.HIDE)
        val weekly = mine(
            5, "2026-11-23T18:00", MyRepeat(MyRepeat.Freq.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), LocalDate.parse("2026-12-20")),
            doneDates = setOf(LocalDate.parse("2026-11-23")), skipDates = setOf(LocalDate.parse("2026-11-25")), memo = "실험실, 3층; 제출",
        )
        val ics = CalendarLogic.ics(
            CalendarExportSettings(),
            listOf(
                assignment(10, 100, "2026-11-25T23:59", name = "보고서, 1차; 최종"),
                assignment(10, 101, "2026-11-26T23:59", submitted = true),
                assignment(77, 200, "2026-11-26T23:59"),
                assignment(10, 102, null),
            ),
            listOf(weekly, mine(6, "2026-11-28T10:00", done = true)),
            filter, now,
        )
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertFalse(ics.replace("\r\n", "").contains("\n"))
        val unfolded = ics.replace("\r\n ", "")
        assertTrue(unfolded.contains("BEGIN:VTIMEZONE\r\nTZID:Asia/Seoul\r\n"))
        assertTrue(unfolded.contains("TZOFFSETTO:+0900"))
        assertTrue(unfolded.contains("SUMMARY:[자료구조] 보고서\\, 1차\\; 최종\r\n"))
        assertTrue(unfolded.contains("DTSTART;TZID=Asia/Seoul:20261125T235900\r\n"))
        assertTrue(unfolded.contains("URL:https://myetl.snu.ac.kr/courses/10/assignments/100\r\n"))
        assertTrue(unfolded.contains("UID:etl-10-100@youngmin.etl-android"))
        assertFalse("제출한 과제는 빠진다", unfolded.contains("etl-10-101@"))
        assertFalse("숨긴 과목은 빠진다", unfolded.contains("etl-77-200@"))
        assertFalse("마감 없는 과제는 빠진다", unfolded.contains("etl-10-102@"))
        assertFalse("완료한 내 마감은 빠진다", unfolded.contains("mine-6@"))
        assertFalse("0분 일정이라 DTEND가 없다", unfolded.contains("DTEND"))
        assertTrue(unfolded.contains("UID:mine-5@youngmin.etl-android"))
        assertTrue(unfolded.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261220T145959Z\r\n"))
        assertTrue(unfolded.contains("EXDATE;TZID=Asia/Seoul:20261123T180000,20261125T180000\r\n"))
        assertTrue(unfolded.contains("DESCRIPTION:실험실\\, 3층\\; 제출\\n내 마감 · 매주 월·수 · 12/20까지"))

        // 제출·완료 포함이면 제출한 과제와 완료한 마감이 들어가고, 완료 회차는 EXDATE에서 빠진다.
        val all = CalendarLogic.ics(CalendarExportSettings(includeDone = true), listOf(assignment(10, 101, "2026-11-26T23:59", submitted = true)),
            listOf(weekly, mine(6, "2026-11-28T10:00", done = true)), CourseFilter(), now).replace("\r\n ", "")
        assertTrue(all.contains("SUMMARY:[자료구조] 과제 1 (제출함)"))
        assertTrue(all.contains("UID:mine-6@"))
        assertTrue(all.contains("EXDATE;TZID=Asia/Seoul:20261125T180000\r\n"))

        // 항목 끄기
        val none = CalendarLogic.ics(CalendarExportSettings(includeEtl = false, includeMine = false), listOf(assignment(10, 100, "2026-11-25T23:59")),
            listOf(weekly), CourseFilter(), now)
        assertFalse(none.contains("BEGIN:VEVENT"))
    }

    @Test fun dropsSignedOrQueryUrls() {
        assertEquals("https://myetl.snu.ac.kr/courses/1/assignments/2", CalendarLogic.safeEtlUrl("https://myetl.snu.ac.kr/courses/1/assignments/2"))
        assertNull(CalendarLogic.safeEtlUrl("https://myetl.snu.ac.kr/files/1/download?verifier=abc"))
        assertNull(CalendarLogic.safeEtlUrl("https://evil.example/courses/1"))
        assertNull(CalendarLogic.safeEtlUrl(null))
    }

    // ---- 내보낼 항목 ---------------------------------------------------------------

    @Test fun desiredItemsFollowSettingsAndFilters() {
        val filter = CourseFilter().with(CourseRef(77, "숨긴 과목"), CourseMode.HIDE)
        val daily = mine(9, "2026-11-20T09:00", MyRepeat(MyRepeat.Freq.DAILY), doneDates = setOf(LocalDate.parse("2026-11-24")))
        val d = CalendarLogic.desired(
            CalendarExportSettings(),
            listOf(
                assignment(10, 100, "2026-11-25T23:59"),
                assignment(10, 101, "2026-11-26T23:59", submitted = true),
                assignment(77, 200, "2026-11-26T23:59"),
                assignment(10, 103, "2026-10-01T23:59"), // 14일보다 오래됨
            ),
            listOf(daily, mine(8, "2026-11-30T10:00")),
            filter, now,
        )
        val keys = d.items.map { it.key }
        assertTrue("etl:10:100" in keys)
        assertFalse("etl:10:101" in keys)
        assertFalse("etl:77:200" in keys)
        assertFalse("etl:10:103" in keys)
        assertTrue("mine:8" in keys)
        // 매일 반복: 시작(11/20)부터 앞으로 60일까지, 완료한 11/24 회차 제외
        val dailyKeys = keys.filter { it.startsWith("mine:9:") }
        assertTrue("mine:9:${LocalDate.parse("2026-11-20").toEpochDay()}" in dailyKeys)
        assertFalse("mine:9:${LocalDate.parse("2026-11-24").toEpochDay()}" in dailyKeys)
        assertTrue("mine:9:${LocalDate.parse("2027-01-21").toEpochDay()}" in dailyKeys)
        assertFalse("mine:9:${LocalDate.parse("2027-01-23").toEpochDay()}" in dailyKeys)
        assertEquals(setOf("etl", "mine"), d.authoritative)

        val item = d.items.first { it.key == "etl:10:100" }
        assertEquals("[자료구조] 과제 1", item.title)
        assertEquals(item.start, item.end)
        assertTrue(item.description.contains("https://myetl.snu.ac.kr/courses/10/assignments/100"))

        // 받아 둔 eTL 과제가 없으면 eTL 일정은 건드리지 않는다.
        assertEquals(setOf("mine"), CalendarLogic.desired(CalendarExportSettings(), null, emptyList(), CourseFilter(), now).authoritative)
        // eTL을 끄면 eTL 일정은 모두 지울 대상
        assertEquals(setOf("etl", "mine"), CalendarLogic.desired(CalendarExportSettings(includeEtl = false), null, emptyList(), CourseFilter(), now).authoritative)
    }

    // ---- 맞추기 ------------------------------------------------------------------

    private fun item(key: String, due: String, title: String = key) = CalItem(key, title, kst(due), kst(due), "d")
    private fun entry(id: Long, it: CalItem) = CalendarLogic.Entry(id, it.hash, it.start.toEpochMilli())

    @Test fun planInsertsUpdatesDeletesAndTombstones() {
        val a = item("etl:1:1", "2026-11-25T23:59")
        val b = item("etl:1:2", "2026-11-26T23:59")
        val bChanged = item("etl:1:2", "2026-11-27T23:59")
        val c = item("mine:5", "2026-11-28T10:00")
        val gone = item("etl:1:3", "2026-11-29T23:59")
        val userDeleted = item("mine:6", "2026-11-30T10:00")
        val tomb = item("mine:7", "2026-12-01T10:00")
        val oldPast = item("etl:1:9", "2026-10-01T23:59")
        val ancient = item("etl:1:8", "2026-03-01T23:59")
        val mapping = mapOf(
            b.key to entry(12, b),
            c.key to entry(13, c),
            gone.key to entry(14, gone),
            userDeleted.key to entry(15, userDeleted),
            tomb.key to CalendarLogic.Entry(CalendarLogic.TOMBSTONE, tomb.hash, tomb.start.toEpochMilli()),
            oldPast.key to entry(16, oldPast),
            ancient.key to entry(17, ancient),
        )
        val desired = CalendarLogic.Desired(listOf(a, bChanged, c, userDeleted, tomb), setOf("etl", "mine"))
        val p = CalendarLogic.plan(desired, mapping, live = setOf(12, 13, 14, 16, 17), now = now)
        assertEquals(listOf(CalendarLogic.Op.Insert(a)), p.ops.filterIsInstance<CalendarLogic.Op.Insert>())
        assertEquals(listOf(CalendarLogic.Op.Update(12, bChanged)), p.ops.filterIsInstance<CalendarLogic.Op.Update>())
        assertEquals(listOf(CalendarLogic.Op.Delete(gone.key, 14)), p.ops.filterIsInstance<CalendarLogic.Op.Delete>())
        assertEquals("사용자가 지운 일정은 묘비로", listOf(userDeleted.key), p.tombstone)
        assertEquals("오래된 매핑은 잊는다", listOf(ancient.key), p.forget)
        assertEquals(1, p.inserts); assertEquals(1, p.updates); assertEquals(1, p.deletes)
    }

    @Test fun planKeepsNonAuthoritativeKindsAndForgetsMissingRows() {
        val e = item("etl:1:1", "2026-11-25T23:59")
        val m = item("mine:1", "2026-11-25T23:59")
        val mapping = mapOf(e.key to entry(1, e), m.key to entry(2, m))
        // eTL 정보가 없을 때(authoritative에 etl 없음): eTL 일정은 그대로, 사라진 내 마감은 지운다.
        val p = CalendarLogic.plan(CalendarLogic.Desired(emptyList(), setOf("mine")), mapping, setOf(1, 2), now)
        assertEquals(listOf<CalendarLogic.Op>(CalendarLogic.Op.Delete(m.key, 2)), p.ops)
        // 원본도 없고 캘린더에서도 없어졌으면 매핑만 정리
        val p2 = CalendarLogic.plan(CalendarLogic.Desired(emptyList(), setOf("etl", "mine")), mapping, emptySet(), now)
        assertTrue(p2.ops.isEmpty())
        assertEquals(setOf(e.key, m.key), p2.forget.toSet())
        // 그대로면 할 일 없음
        val p3 = CalendarLogic.plan(CalendarLogic.Desired(listOf(e, m), setOf("etl", "mine")), mapping, setOf(1, 2), now)
        assertTrue(p3.ops.isEmpty() && p3.tombstone.isEmpty() && p3.forget.isEmpty())
    }

    @Test fun mappingAndSettingsRoundTrip() {
        val m = mapOf("etl:1:2" to CalendarLogic.Entry(5, "abc", 123L), "mine:3" to CalendarLogic.Entry(CalendarLogic.TOMBSTONE, "def", 456L))
        val back = CalendarLogic.mappingFromJson(CalendarLogic.mappingToJson(42, m))!!
        assertEquals(42, back.calendarId)
        assertEquals(m, back.entries)
        assertTrue(back.entries.getValue("mine:3").tombstone)
        assertNull(CalendarLogic.mappingFromJson("깨짐"))

        val s = CalendarExportSettings(true, CalendarTarget.GOOGLE, 7, "영민이", includeEtl = false, includeMine = true, includeDone = true)
        assertEquals(s, CalendarExportSettings.fromJson(s.toJson()))
        assertEquals(CalendarExportSettings(), CalendarExportSettings.fromJson(null))
        assertFalse(CalendarExportSettings().enabled)
        assertTrue(CalendarExportSettings().includeEtl && CalendarExportSettings().includeMine && !CalendarExportSettings().includeDone)
    }

    @Test fun keysAndAppUri() {
        val occ = MyDeadline(5, "x", kst("2026-11-25T18:00"), repeat = MyRepeat(MyRepeat.Freq.DAILY), occurrence = LocalDate.parse("2026-11-25"))
        assertEquals("mine:5:${LocalDate.parse("2026-11-25").toEpochDay()}", CalendarLogic.mineKey(occ))
        assertEquals("mine:5", CalendarLogic.mineKey(mine(5, "2026-11-25T18:00")))
        assertEquals("etl:3:4", CalendarLogic.etlKey(3, 4))
        assertEquals(5L, CalendarLogic.mineIdFromUri("youngmin://item/mine:5:20000"))
        assertNull(CalendarLogic.mineIdFromUri("youngmin://item/etl:3:4"))
        assertNull(CalendarLogic.mineIdFromUri("https://example.com/mine:5"))
        assertNotNull(CalendarLogic.hash(listOf("a")))
        assertFalse(CalendarLogic.hash(listOf("ab", "c")) == CalendarLogic.hash(listOf("a", "bc")))
    }
}
