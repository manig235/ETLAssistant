package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 시험 종류(H): 저장 호환, 알림 기본값, 응원 문구, 브리핑·내 일정·도구 결과. */
class ExamTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private val now = kst("2026-11-24T12:00") // 화요일

    private fun exam(id: Long = 77, start: String = "2026-11-26T10:00", end: String? = "2026-11-26T12:00", location: String = "301동 118호",
                     course: String = "자료구조", supplies: String = "") =
        MyDeadline(id, "중간고사", kst(start), kind = MyKind.EXAM, end = end?.let(::kst), location = location, course = course, supplies = supplies,
            reminders = MyDeadlines.defaultExamReminders(kst(start), now))

    @Test fun oldDataReadsAsDeadlineAndExamRoundTrips() {
        val old = """[{"id":5,"title":"옛 항목","due":0,"memo":"","done":false,"reminders":[]}]"""
        val d = MyDeadlines.fromJson(old).single()
        assertEquals(MyKind.DEADLINE, d.kind)
        assertNull(d.end)
        assertNull(d.sourceAnnouncementId)
        val e = exam(supplies = "공학용 계산기, 신분증").copy(sourceAnnouncementId = 42)
        val back = MyDeadlines.fromJson(MyDeadlines.toJson(listOf(e))).single()
        assertEquals(e, back)
        // 마감은 시험 칸을 쓰지 않는다(예전 형식 그대로)
        assertFalse(MyDeadlines.toJson(listOf(d)).contains("kind"))
        // 시험에 반복이 들어 있어도 읽을 때 뺀다
        val bad = """[{"id":6,"title":"시험","due":1,"kind":"exam","repeat":{"freq":"daily"}}]"""
        assertNull(MyDeadlines.fromJson(bad).single().repeat)
    }

    @Test fun defaultRemindersAreEveAndTwoHoursBefore() {
        assertEquals(listOf(kst("2026-11-25T20:00"), kst("2026-11-26T08:00")), MyDeadlines.defaultExamReminders(kst("2026-11-26T10:00"), now))
        // 이미 지난 시각은 뺀다(1시간 뒤 시험이면 둘 다 지남)
        assertTrue(MyDeadlines.defaultExamReminders(kst("2026-11-24T13:00"), now).isEmpty())
    }

    @Test fun validation() {
        assertNull(MyDeadlines.validateExam(exam()))
        assertEquals("끝 시각은 시작 시각보다 뒤여야 해요", MyDeadlines.validateExam(exam(end = "2026-11-26T09:00")))
        assertTrue(MyDeadlines.validateExam(exam().copy(repeat = MyRepeat(MyRepeat.Freq.DAILY)))!!.contains("반복"))
        assertEquals("공학용 계산기, 신분증, A4 요약지 1장", MyDeadlines.normalizeSupplies("공학용 계산기\n신분증 · A4 요약지 1장, 신분증"))
    }

    @Test fun examsAreNotDeadlineAlertItemsButShowInBriefAndWidgetList() {
        val list = listOf(exam(), MyDeadline(1, "서류", kst("2026-11-25T18:00")))
        assertEquals(listOf("서류"), MyDeadlines.toItems(list, now).map { it.name })
        val lines = MyDeadlines.examLines(list, now)
        assertEquals(listOf("• 11/26(목) 10:00~12:00 · [시험] 자료구조 중간고사 · 301동 118호"), lines)
        val tomorrow = MyDeadlines.examLines(listOf(exam(start = "2026-11-25T10:00", end = null, supplies = "계산기")), now).single()
        assertTrue(tomorrow.startsWith("• [내일] "))
        assertTrue(tomorrow.endsWith("준비물: 계산기"))
        // 끝난 시험은 빠진다
        assertTrue(MyDeadlines.examLines(list, kst("2026-11-26T12:01")).isEmpty())
    }

    @Test fun reminderPutsInfoFirstThenCheer() {
        val e = exam(supplies = "공학용 계산기")
        val n = MyDeadlines.reminderNotices(listOf(e), kst("2026-11-25T20:01"), emptySet()).single()
        assertEquals("시험 · 중간고사 · 자료구조", n.title)
        val big = n.bigText!!.lines()
        assertTrue(big[0].startsWith("11/26(목) 10:00~12:00"))
        assertTrue(big[0].contains("· 301동 118호 · 준비물: 공학용 계산기"))
        assertTrue(big[1] in ExamCheer.EVE.map { it.replace("{s}", "자료구조 시험") })
        assertEquals(listOf(MyDeadlines.reminderKey(e, kst("2026-11-25T20:00"))), n.firedKeys)
    }

    @Test fun cheerIsDeterministicAndRespectsToneLocationAndSupplies() {
        val at = kst("2026-11-26T08:00")
        val start = kst("2026-11-26T10:00")
        val a = ExamCheer.message(ExamCheer.Phase.DAY, 77, at, "자료구조", start, "301동 118호", "", casual = false)
        assertEquals(a, ExamCheer.message(ExamCheer.Phase.DAY, 77, at, "자료구조", start, "301동 118호", "", casual = false))
        // 같은 번호의 반말 문구
        val i = ExamCheer.pick(77, at, ExamCheer.Phase.DAY, ExamCheer.DAY.size)
        assertEquals(ExamCheer.DAY_CASUAL[i].replace("{s}", "자료구조 시험").replace("{w}", "10:00 301동 118호에서"),
            ExamCheer.message(ExamCheer.Phase.DAY, 77, at, "자료구조", start, "301동 118호", "", casual = true))
        // 장소가 없으면 "10:00에"
        val noPlace = ExamCheer.message(ExamCheer.Phase.DAY, 77, at, "", start, "", "", casual = false)
        assertFalse(noPlace.contains("에서"))
        assertFalse(noPlace.contains("{"))
        // 준비물이 있으면 "신분증·필기구 챙기셨죠?" 같은 일반 확인 문구를 쓰지 않는다
        for (id in 1L..30L) {
            val m = ExamCheer.message(ExamCheer.Phase.DAY, id, at, "물리", start, "", "계산기", casual = false)
            assertFalse(m.contains("필기구"))
            assertTrue(m.contains("준비물"))
        }
        // 회차마다 고르게: 서로 다른 시험이면 다른 문구가 나올 수 있다
        assertTrue((1L..30L).map { ExamCheer.pick(it, at, ExamCheer.Phase.EVE, ExamCheer.EVE.size) }.distinct().size > 1)
        assertTrue(listOf(ExamCheer.EVE, ExamCheer.EVE_CASUAL, ExamCheer.DAY, ExamCheer.DAY_CASUAL, ExamCheer.AFTER, ExamCheer.AFTER_CASUAL, ExamCheer.DAY_SUPPLIES).all { it.size >= 5 })
        assertEquals(ExamCheer.Phase.EVE, ExamCheer.phaseOf(kst("2026-11-25T20:00"), start))
        assertEquals(ExamCheer.Phase.DAY, ExamCheer.phaseOf(kst("2026-11-26T08:00"), start))
    }

    @Test fun doneAlertAfterEndUnlessQuietOrOff() {
        val e = exam()
        val after = kst("2026-11-26T12:10")
        val (sent, silent) = MyDeadlines.examDoneNotices(listOf(e), after, emptySet(), enabled = true, quiet = false, casual = false)
        assertEquals("수고했어요 · 자료구조 중간고사", sent.single().title)
        assertTrue(silent.isEmpty())
        val (none, keys) = MyDeadlines.examDoneNotices(listOf(e), after, emptySet(), enabled = true, quiet = true, casual = false)
        assertTrue(none.isEmpty())
        assertEquals(listOf(MyDeadlines.examDoneKey(e)), keys)
        assertTrue(MyDeadlines.examDoneNotices(listOf(e), after, setOf(MyDeadlines.examDoneKey(e)), true, false, false).first.isEmpty())
        // 끝 시각 없는 시험·너무 늦은 확인은 없음
        assertTrue(MyDeadlines.examDoneNotices(listOf(exam(end = null)), after, emptySet(), true, false, false).first.isEmpty())
        assertTrue(MyDeadlines.examDoneNotices(listOf(e), kst("2026-11-26T16:00"), emptySet(), true, false, false).first.isEmpty())
        assertEquals("수고했어 · 자료구조 중간고사", MyDeadlines.examDoneNotices(listOf(e), after, emptySet(), true, false, casual = true).first.single().title)
        // 끝 시각에 제시간 알람
        assertEquals(kst("2026-11-26T12:00"), MyDeadlines.nextReminderAt(listOf(e), kst("2026-11-26T09:00"), setOf(MyDeadlines.reminderKey(e, kst("2026-11-26T08:00")))))
    }

    @Test fun agendaShowsExamRowsAndFilter() {
        val list = listOf(exam(supplies = "계산기"), MyDeadline(1, "서류", kst("2026-11-25T18:00")))
        val all = Agenda.build(emptyList(), list, now, seoul)
        val row = all.upcoming.flatMap { it.rows }.single { it.kind == Agenda.Kind.EXAM }
        assertEquals("자료구조 중간고사", row.title)
        assertTrue(row.subtitle.startsWith("시험 · 301동 118호 · 준비물: 계산기"))
        assertTrue(row.timeText!!.startsWith("11/26(목) 10:00~12:00"))
        val examsOnly = Agenda.build(emptyList(), list, now, seoul, Agenda.Filter.EXAM)
        assertEquals(listOf(Agenda.Kind.EXAM), examsOnly.upcoming.flatMap { it.rows }.map { it.kind })
        val mineOnly = Agenda.build(emptyList(), list, now, seoul, Agenda.Filter.MINE)
        assertEquals(listOf("서류"), mineOnly.upcoming.flatMap { it.rows }.map { it.title })
        // 끝난 시험은 지난 항목에도 남지 않는다
        assertTrue(Agenda.build(emptyList(), list, kst("2026-11-26T13:00"), seoul).overdue.none { it.kind == Agenda.Kind.EXAM })
    }

    @Test fun toolResultIncludesExamFields() {
        val o = MyDeadlines.toolResult(listOf(exam(supplies = "계산기")), now, null, false).getJSONArray("items").getJSONObject(0)
        assertEquals("exam", o.getString("kind"))
        assertEquals("301동 118호", o.getString("location"))
        assertEquals("계산기", o.getString("supplies"))
        assertTrue(o.has("end_kst"))
        assertFalse(o.getBoolean("overdue"))
        assertNotEquals("", o.getString("start_kst"))
    }
}
