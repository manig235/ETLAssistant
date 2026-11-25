package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 편집 창의 시험 알림 칩(2026-11-25): 기본 알림을 지울 수 있는 칩으로 보이고, 보이는 그대로 저장한다. */
class ExamReminderFormTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private val now = kst("2026-11-24T12:00")
    private val start = kst("2026-11-26T10:00")
    private val eve = 14 * 60L // 전날 20:00 = 10:00 시작의 14시간 전
    private val twoHours = 120L

    @Test fun untouchedNewExamShowsDefaults() {
        val r = DeadlineForm.initialReminders(null)
        assertFalse(r.touched)
        assertEquals(listOf(twoHours, eve), DeadlineForm.shownReminders(r, exam = true, due = start, now = now))
        assertEquals(listOf(kst("2026-11-25T20:00"), kst("2026-11-26T08:00")), DeadlineForm.reminderInstants(r, true, start, now))
        assertEquals("전날 20:00", DeadlineForm.reminderLabel(eve, start))
        assertEquals("08:00 · 2시간 전", DeadlineForm.reminderLabel(twoHours, start))
        // 시작 시각을 아직 정할 수 없으면 칩 없음
        assertTrue(DeadlineForm.shownReminders(r, true, null, now).isEmpty())
    }

    @Test fun untouchedRecomputesWhenDateOrTimeChanges() {
        val r = DeadlineForm.initialReminders(null)
        val later = kst("2026-11-30T14:00")
        assertEquals(listOf(kst("2026-11-29T20:00"), kst("2026-11-30T12:00")), DeadlineForm.reminderInstants(r, true, later, now))
        assertEquals(listOf(120L, 18 * 60L), DeadlineForm.shownReminders(r, true, later, now))
    }

    @Test fun removingAllSavesEmpty() {
        var r = DeadlineForm.initialReminders(null)
        r = DeadlineForm.removeReminder(r, true, start, now, eve)
        assertTrue(r.touched)
        assertEquals(listOf(twoHours), DeadlineForm.shownReminders(r, true, start, now))
        r = DeadlineForm.removeReminder(r, true, start, now, twoHours)
        assertTrue(DeadlineForm.shownReminders(r, true, start, now).isEmpty())
        assertTrue(DeadlineForm.reminderInstants(r, true, start, now).isEmpty())
        // 굳힌 뒤에는 날짜를 바꿔도 기본값이 돌아오지 않는다
        assertTrue(DeadlineForm.reminderInstants(r, true, kst("2026-12-01T10:00"), now).isEmpty())
    }

    @Test fun addingFreezesShownListPlusNew() {
        val r = DeadlineForm.addReminder(DeadlineForm.initialReminders(null), true, start, now, 10)
        assertTrue(r.touched)
        assertEquals(listOf(10L, twoHours, eve), r.offsets)
        // 굳힌 뒤 시작 시각을 옮기면 "몇 분 전"이 그대로 따라간다(기본값 다시 계산 안 함)
        assertEquals(listOf(10L, twoHours, eve), DeadlineForm.shownReminders(r, true, kst("2026-11-27T15:00"), now))
        // 중복·0분은 무시
        assertEquals(r.offsets, DeadlineForm.addReminder(r, true, start, now, 10).offsets)
        assertEquals(r.offsets, DeadlineForm.addReminder(r, true, start, now, 0).offsets)
    }

    @Test fun pastDefaultsDropped() {
        val r = DeadlineForm.initialReminders(null)
        // 내일 아침 9시 시험을 오늘 21시에 넣으면 전날 20:00은 지났다
        val n = kst("2026-11-24T21:00")
        assertEquals(listOf(kst("2026-11-25T07:00")), DeadlineForm.reminderInstants(r, true, kst("2026-11-25T09:00"), n))
        // 1시간 뒤 시험이면 둘 다 지나 칩 없음 = 알림 없음
        assertTrue(DeadlineForm.reminderInstants(r, true, kst("2026-11-24T22:00"), n).isEmpty())
    }

    @Test fun kindSwitch() {
        val r = DeadlineForm.initialReminders(null)
        val due = kst("2026-11-26T23:59")
        // 마감 → 시험: 손대지 않았으면 시험 기본값이 보인다
        assertTrue(DeadlineForm.shownReminders(r, exam = false, due = due, now = now).isEmpty())
        assertEquals(2, DeadlineForm.shownReminders(r, exam = true, due = due, now = now).size)
        // 시험 → 마감: 손대지 않았으면 비운다(마감에는 기본 직접 알림이 없다)
        assertTrue(DeadlineForm.reminderInstants(r, false, due, now).isEmpty())
        // 손댄 목록은 종류를 바꿔도 그대로
        val t = DeadlineForm.removeReminder(r, true, due, now, 120)
        assertEquals(t.offsets, DeadlineForm.shownReminders(t, false, due, now))
    }

    @Test fun existingExamUnchanged() {
        val saved = MyDeadline(9, "중간고사", start, kind = MyKind.EXAM, reminders = listOf(kst("2026-11-26T09:30")))
        val r = DeadlineForm.initialReminders(saved)
        assertTrue(r.touched)
        assertEquals(listOf(30L), DeadlineForm.shownReminders(r, true, start, now))
        assertEquals(saved.reminders, DeadlineForm.reminderInstants(r, true, start, now))
        // 알림 없이 저장된 시험을 고쳐도 기본값을 넣지 않는다
        val none = DeadlineForm.initialReminders(saved.copy(reminders = emptyList()))
        assertTrue(DeadlineForm.reminderInstants(none, true, start, now).isEmpty())
    }

    @Test fun labels() {
        assertEquals("30분 전", DeadlineForm.reminderLabel(30, null))
        val due = kst("2026-11-26T23:59")
        assertEquals("23:49 · 10분 전", DeadlineForm.reminderLabel(10, due))
        assertEquals("전날 23:59", DeadlineForm.reminderLabel(1440, due))
        assertEquals("11/23 23:59 · 3일 전", DeadlineForm.reminderLabel(3 * 1440, due))
    }
}
