package kr.snu.etlassistant.calendar

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * 여러 날 종일 일정의 저장 → 읽기 → 저장 왕복 회귀 테스트(2026-11-26).
 * iOS 이식본에서 끝 날짜를 "마지막 날 0시"로 쓰고 "끝 − 1초가 든 날"로 읽어 왕복마다 하루씩 줄던 버그가 있었다.
 * 안드로이드는 종일 일정을 시작 날짜 0시(UTC) ~ (끝 날짜 + 1) 0시(UTC, 배타)로 쓰고, 읽을 때 배타 끝에서 하루를 뺀다.
 * 기기 시간대와 무관해야 한다.
 */
class CalendarAllDayRoundTripTest {
    private val day = 86_400_000L
    private lateinit var savedZone: TimeZone

    private val zones = listOf(
        "Asia/Seoul", "UTC", "America/Los_Angeles", "Pacific/Kiritimati", "Pacific/Pago_Pago",
        "Europe/London", "Asia/Kolkata", "Australia/Adelaide", "America/St_Johns",
    )

    @Before fun saveZone() { savedZone = TimeZone.getDefault() }

    @After fun restoreZone() { TimeZone.setDefault(savedZone) }

    private fun utcMidnight(d: String): Long = LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun isUtcMidnight(ms: Long) = ms % day == 0L

    /** CalendarReader가 Instances에서 만드는 한 줄(종일 일정은 begin/end = DTSTART/DTEND). */
    private fun raw(w: CalendarToolLogic.When, id: Long = 1) =
        RawInstance(id, 7, "학교", 0, "MT", null, null, w.dtStart, w.dtEnd, w.allDay)

    private fun backup(w: CalendarToolLogic.When) =
        CalendarToolLogic.Backup(1, 7, "MT", w.dtStart, w.dtEnd, w.allDay, w.timezone, "", "")

    /** 쓰기 → 읽기(calendar_events가 비서에게 주는 start_date/end_date) → 그대로 다시 쓰기. */
    private fun roundTrip(w: CalendarToolLogic.When): Triple<LocalDate, LocalDate, CalendarToolLogic.When> {
        val e = CalendarReadLogic.toEvent(raw(w))
        val json = CalendarReadLogic.eventJson(e, writable = true)
        assertEquals(e.startDate.toString(), json.getString("start_date"))
        assertEquals(e.endDate.toString(), json.getString("end_date"))
        return Triple(e.startDate, e.endDate, CalendarToolLogic.parseWhen(json.getString("start_date"), json.getString("end_date"), true))
    }

    @Test fun threeDayAllDayIsStoredAsUtcMidnightsWithExclusiveEnd() {
        val w = CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true)
        assertTrue(w.allDay)
        assertEquals("UTC", w.timezone)
        assertEquals(3 * day, w.dtEnd - w.dtStart)
        assertTrue(isUtcMidnight(w.dtStart))
        assertTrue(isUtcMidnight(w.dtEnd))
        assertEquals(utcMidnight("2026-11-26"), w.dtStart)
        assertEquals(utcMidnight("2026-11-29"), w.dtEnd)
    }

    @Test fun threeDayAllDaySurvivesRepeatedRoundTrips() {
        val first = CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true)
        var w = first
        repeat(4) { i ->
            val (sd, ed, next) = roundTrip(w)
            assertEquals("왕복 ${i + 1}번째 시작", LocalDate.parse("2026-11-26"), sd)
            assertEquals("왕복 ${i + 1}번째 끝", LocalDate.parse("2026-11-28"), ed)
            assertEquals("왕복 ${i + 1}번째", first, next)
            w = next
        }
    }

    @Test fun singleDayAllDayRoundTripsToSameDay() {
        val first = CalendarToolLogic.parseWhen("2026-11-26", null, null)
        assertTrue(first.allDay)
        assertEquals(day, first.dtEnd - first.dtStart)
        var w = first
        repeat(4) {
            val (sd, ed, next) = roundTrip(w)
            assertEquals(LocalDate.parse("2026-11-26"), sd)
            assertEquals(LocalDate.parse("2026-11-26"), ed)
            assertEquals(first, next)
            w = next
        }
        assertEquals("11/26(목) 종일", CalendarToolLogic.whenLabel(true, first.dtStart, first.dtEnd))
    }

    @Test fun zeroLengthAllDayReadsAsOneDay() {
        // 일부 앱은 종일 일정을 DTEND == DTSTART로 저장한다.
        val start = utcMidnight("2026-11-26")
        val zero = CalendarToolLogic.When(true, start, start)
        val e = CalendarReadLogic.toEvent(raw(zero))
        assertEquals(LocalDate.parse("2026-11-26"), e.startDate)
        assertEquals(LocalDate.parse("2026-11-26"), e.endDate)
        assertEquals("종일", CalendarReadLogic.timeLabel(e))
        assertEquals("11/26(목) 종일", CalendarToolLogic.whenLabel(true, zero.dtStart, zero.dtEnd))
        // 다시 쓰면 정상적인 하루짜리(끝 = 다음 날 0시)가 된다.
        val (_, _, rewritten) = roundTrip(zero)
        assertEquals(CalendarToolLogic.parseWhen("2026-11-26", "2026-11-26", true), rewritten)
    }

    @Test fun whenLabelShowsInclusiveLastDay() {
        val w = CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true)
        assertEquals("11/26(목)~11/28(토) 종일", CalendarToolLogic.whenLabel(true, w.dtStart, w.dtEnd))
        assertEquals("11/26~11/28 종일", CalendarReadLogic.timeLabel(CalendarReadLogic.toEvent(raw(w))))
        assertTrue(CalendarToolLogic.reviewDetails("학교", "acc", backup(w)).contains("2026-11-26 ~ 2026-11-28 (종일, 끝 날짜 포함)"))
    }

    @Test fun yearEndAndLeapDayBoundariesRoundTrip() {
        data class Case(val start: String, val end: String, val days: Long, val label: String)
        val cases = listOf(
            Case("2026-12-30", "2027-01-02", 4, "12/30(수)~1/2(토) 종일"),
            Case("2028-02-28", "2028-03-01", 3, "2/28(월)~3/1(수) 종일"),
            Case("2028-02-29", "2028-02-29", 1, "2/29(화) 종일"),
            Case("2027-02-28", "2027-03-01", 2, "2/28(일)~3/1(월) 종일"),
            Case("2026-11-30", "2026-12-01", 2, "11/30(월)~12/1(화) 종일"),
        )
        for (c in cases) {
            val first = CalendarToolLogic.parseWhen(c.start, c.end, true)
            assertEquals(c.start, c.days * day, first.dtEnd - first.dtStart)
            assertEquals(c.start, c.label, CalendarToolLogic.whenLabel(true, first.dtStart, first.dtEnd))
            var w = first
            repeat(4) {
                val (sd, ed, next) = roundTrip(w)
                assertEquals(c.start, LocalDate.parse(c.start), sd)
                assertEquals(c.start, LocalDate.parse(c.end), ed)
                assertEquals(c.start, first, next)
                w = next
            }
        }
    }

    @Test fun moveStartKeepsAllDayLength() {
        val three = CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true)
        val one = CalendarToolLogic.parseWhen("2026-11-26", null, true)
        val zero = CalendarToolLogic.When(true, three.dtStart, three.dtStart)
        val dec1 = LocalDate.parse("2026-12-01")
        assertEquals(LocalDate.parse("2026-12-03"), CalendarToolLogic.allDayEndKeepingLength(three.dtStart, three.dtEnd, dec1))
        assertEquals(dec1, CalendarToolLogic.allDayEndKeepingLength(one.dtStart, one.dtEnd, dec1))
        assertEquals(dec1, CalendarToolLogic.allDayEndKeepingLength(zero.dtStart, zero.dtEnd, dec1))
        // 연말을 넘겨 옮겨도 길이 그대로.
        assertEquals(LocalDate.parse("2027-01-01"), CalendarToolLogic.allDayEndKeepingLength(three.dtStart, three.dtEnd, LocalDate.parse("2026-12-30")))

        // calendar_update(start만) 경로 전체.
        val moved = CalendarToolLogic.updatedWhen(backup(three), "2026-12-01", null, null)
        assertEquals(CalendarToolLogic.parseWhen("2026-12-01", "2026-12-03", true), moved)
        assertEquals("12/1(화)~12/3(목) 종일", CalendarToolLogic.whenLabel(true, moved.dtStart, moved.dtEnd))
        assertEquals(CalendarToolLogic.parseWhen("2026-12-01", null, true), CalendarToolLogic.updatedWhen(backup(one), "2026-12-01", null, null))
        assertEquals(CalendarToolLogic.parseWhen("2026-12-01", null, true), CalendarToolLogic.updatedWhen(backup(zero), "2026-12-01", null, null))

        // 여러 번 옮겨도 하루씩 줄지 않는다.
        var w = three
        listOf("2026-12-01", "2026-12-08", "2026-12-30", "2027-01-05").forEach { s ->
            w = CalendarToolLogic.updatedWhen(backup(w), s, null, null)
            assertEquals(s, 3 * day, w.dtEnd - w.dtStart)
            val e = CalendarReadLogic.toEvent(raw(w))
            assertEquals(LocalDate.parse(s), e.startDate)
            assertEquals(LocalDate.parse(s).plusDays(2), e.endDate)
        }
    }

    @Test fun endOnlyUpdateKeepsStartDate() {
        val three = CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true)
        assertEquals(CalendarToolLogic.parseWhen("2026-11-26", "2026-11-29", true), CalendarToolLogic.updatedWhen(backup(three), null, "2026-11-29", null))
        assertEquals("2026-11-26", CalendarToolLogic.startText(backup(three), true))
    }

    @Test fun titleOnlyUpdateKeepsRawStartAndEnd() {
        val three = CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true)
        var w = three
        repeat(4) {
            w = CalendarToolLogic.updatedWhen(backup(w), null, null, null)
            assertEquals(three, w)
        }
        // 다른 앱이 쓴 특이한 값(0분짜리, 자정이 아닌 값)도 손대지 않는다.
        val odd = CalendarToolLogic.Backup(1, 7, "x", utcMidnight("2026-11-26"), utcMidnight("2026-11-26"), true, "UTC", "", "")
        assertEquals(CalendarToolLogic.When(true, odd.dtStart, odd.dtEnd), CalendarToolLogic.updatedWhen(odd, null, null, null))
        val offMidnight = odd.copy(dtStart = odd.dtStart + 9 * 3_600_000L, dtEnd = odd.dtStart + 2 * day + 9 * 3_600_000L)
        assertEquals(CalendarToolLogic.When(true, offMidnight.dtStart, offMidnight.dtEnd), CalendarToolLogic.updatedWhen(offMidnight, null, null, null))
    }

    @Test fun undoBackupPreservesMultiDayAllDay() {
        val w = CalendarToolLogic.parseWhen("2026-12-30", "2027-01-02", true)
        val b = CalendarToolLogic.Backup(42, 7, "MT", w.dtStart, w.dtEnd, true, w.timezone, "가평", "메모")
        val back = CalendarToolLogic.Backup.fromJson(JSONObject(b.toJson().toString()))
        assertEquals(b, back)
        assertEquals("UTC", back!!.timezone)

        val change = CalendarToolLogic.Change(10, 10, CalendarToolLogic.Action.UPDATE, 42, "학교", "고침", b)
        val restored = CalendarToolLogic.changesFromJson(CalendarToolLogic.changesToJson(listOf(change))).single().before!!
        assertEquals(b, restored)
        val e = CalendarReadLogic.toEvent(RawInstance(42, 7, "학교", 0, restored.title, null, null, restored.dtStart, restored.dtEnd, restored.allDay))
        assertEquals(LocalDate.parse("2026-12-30"), e.startDate)
        assertEquals(LocalDate.parse("2027-01-02"), e.endDate)
    }

    @Test fun resultsDoNotDependOnDeviceTimeZone() {
        fun snapshot(): List<String> {
            val out = mutableListOf<String>()
            listOf("2026-11-26" to "2026-11-28", "2026-11-26" to "2026-11-26", "2026-12-30" to "2027-01-02", "2028-02-28" to "2028-03-01").forEach { (s, e) ->
                val w = CalendarToolLogic.parseWhen(s, e, true)
                val ev = CalendarReadLogic.toEvent(raw(w))
                val (_, _, again) = roundTrip(w)
                val moved = CalendarToolLogic.updatedWhen(backup(w), "2026-12-01", null, null)
                out += listOf(
                    "$s~$e", w.dtStart.toString(), w.dtEnd.toString(),
                    ev.startDate.toString(), ev.endDate.toString(), ev.start.toString(), ev.end.toString(),
                    CalendarReadLogic.timeLabel(ev), CalendarReadLogic.eventJson(ev, true).toString(),
                    CalendarToolLogic.whenLabel(true, w.dtStart, w.dtEnd), CalendarToolLogic.reviewDetails("학교", "acc", backup(w)),
                    again.toString(), moved.toString(), CalendarToolLogic.startText(backup(w), true),
                )
            }
            val start = utcMidnight("2026-11-26")
            val zero = CalendarReadLogic.toEvent(raw(CalendarToolLogic.When(true, start, start)))
            out += listOf(zero.startDate.toString(), zero.endDate.toString(), CalendarToolLogic.whenLabel(true, start, start))
            // 내 일정 화면 범위 필터(KST): 11/28 하루 창에 3일짜리가 들어오고 11/29 창에는 안 들어온다.
            val w = CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true)
            val kst = CalendarLogic.ZONE
            fun inDay(d: String) = CalendarReadLogic.events(listOf(raw(w)), setOf(7), emptySet(), emptySet(),
                LocalDate.parse(d).atStartOfDay(kst).toInstant(), LocalDate.parse(d).plusDays(1).atStartOfDay(kst).toInstant()).size
            out += listOf("25=${inDay("2026-11-25")}", "26=${inDay("2026-11-26")}", "28=${inDay("2026-11-28")}", "29=${inDay("2026-11-29")}")
            return out
        }

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        val baseline = snapshot()
        assertTrue(baseline.contains("11/26(목)~11/28(토) 종일"))
        assertTrue(baseline.containsAll(listOf("25=0", "26=1", "28=1", "29=0")))
        for (z in zones) {
            val tz = TimeZone.getTimeZone(z)
            assertEquals(z, tz.id)
            TimeZone.setDefault(tz)
            assertEquals("기기 시간대 $z", baseline, snapshot())
        }
        // 시각과 무관한 Instant 기준 확인: 시작 날짜 KST 0시.
        assertEquals(Instant.parse("2026-11-25T15:00:00Z"), CalendarReadLogic.toEvent(raw(CalendarToolLogic.parseWhen("2026-11-26", "2026-11-28", true))).start)
    }
}
