package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kr.snu.etlassistant.etl.AssignmentQuery
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class MyDeadlinesTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    /** 2026-11-19(목) 12:00 KST */
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z")
    private fun hours(h: Long) = now.plus(Duration.ofHours(h))
    private fun mine(id: Long, due: Instant, title: String = "내 일$id", done: Boolean = false) = MyDeadline(id, title, due, "", done)

    @Test fun jsonRoundTrip() {
        val list = listOf(MyDeadline(1, "장학금 서류", hours(5), "행정실", false), MyDeadline(2, "회의", hours(-3), "", true))
        assertEquals(list, MyDeadlines.fromJson(MyDeadlines.toJson(list)))
    }

    @Test fun fromJsonSkipsBrokenEntries() {
        val raw = """[{"id":1,"title":"ok","due":0},{"id":0,"title":"no id","due":0},{"id":3,"title":"  ","due":0},{"id":4,"title":"no due"},5]"""
        assertEquals(listOf(1L), MyDeadlines.fromJson(raw).map { it.id })
        assertTrue(MyDeadlines.fromJson("not json").isEmpty())
        assertTrue(MyDeadlines.fromJson(null).isEmpty())
    }

    @Test fun toItemsMapsDoneAndOverdue() {
        val items = MyDeadlines.toItems(listOf(mine(1, hours(2)), mine(2, hours(-1)), mine(3, hours(-1), done = true)), now)
        assertTrue(items.all { it.courseId == MyDeadlines.COURSE_ID && it.courseName == "내 마감" && it.url == null })
        assertEquals(listOf(false, true, false), items.map { it.missing })
        assertEquals(listOf(false, false, true), items.map { it.submitted })
    }

    @Test fun deadlineAlertFiresForMyDeadline() {
        val etl = AssignmentItem(10, "자료구조", 7, "HW3", hours(50), true, null, null, false, false)
        val snap = MyDeadlines.merge(Snapshot(now, listOf(etl), emptyList()), listOf(mine(1, hours(2), "장학금 서류")), now)
        assertEquals(listOf(1L, 7L), snap.assignments.map { it.assignmentId }) // 마감순
        val plan = NotificationPlanner.plan(snap, NotifyState(announcementBaselineDone = true), now, NotifySettings(), seoul)
        val n = plan.notices.single { it.tag == "deadline:-1:1" }
        assertEquals("마감 임박 · 내 마감", n.title)
        assertTrue(n.text.startsWith("장학금 서류"))
        assertFalse(n.text.contains("오프라인"))
        assertNull(n.url)
        assertTrue("deadline-m:-1:1:180" in n.firedKeys)
    }

    @Test fun doneDeadlineIsNotAlerted() {
        val snap = MyDeadlines.merge(Snapshot(now, emptyList(), emptyList()), listOf(mine(1, hours(2), done = true)), now)
        assertTrue(NotificationPlanner.plan(snap, NotifyState(announcementBaselineDone = true), now, NotifySettings(), seoul).notices.isEmpty())
    }

    @Test fun briefIncludesMyDeadline() {
        val snap = MyDeadlines.merge(Snapshot(now, emptyList(), emptyList()), listOf(mine(1, hours(30), "동아리 회의록")), now)
        val brief = BriefBuilder.build(snap, now, NotifySettings(), seoul)
        assertNotNull(brief.bigText)
        assertTrue(brief.bigText!!.contains("내 마감 — 동아리 회의록"))
    }

    @Test fun validateAndSort() {
        assertEquals("제목을 입력하세요", MyDeadlines.validate("  ", "", 0))
        assertNotNull(MyDeadlines.validate("a".repeat(61), "", 0))
        assertNotNull(MyDeadlines.validate("ok", "", MyDeadlines.MAX_ITEMS))
        assertNull(MyDeadlines.validate("ok", "memo", 0))
        val sorted = MyDeadlines.sorted(listOf(mine(1, hours(9), done = true), mine(2, hours(5)), mine(3, hours(1)), mine(4, hours(20), done = true)))
        assertEquals(listOf(3L, 2L, 4L, 1L), sorted.map { it.id })
    }

    @Test fun toolResultFiltersByWindowAndDone() {
        val list = listOf(
            MyDeadline(1, "장학금 서류", hours(30), "행정실", false), // 11/20(금) 이번 주
            mine(2, hours(24 * 6)),                                    // 11/25 다음 주
            mine(3, hours(-5)),                                        // 지남, 미완료 → 항상 포함
            mine(4, hours(10), done = true),                           // 완료
        )
        val week = AssignmentQuery.window(AssignmentQuery.Range.THIS_WEEK, now)
        val r = MyDeadlines.toolResult(list, now, week, includeDone = false)
        val titles = (0 until r.getJSONArray("items").length()).map { r.getJSONArray("items").getJSONObject(it).getString("title") }
        assertEquals(listOf("내 일3", "장학금 서류"), titles)
        val first = r.getJSONArray("items").getJSONObject(0)
        assertTrue(first.getBoolean("overdue"))
        assertEquals("행정실", r.getJSONArray("items").getJSONObject(1).getString("memo"))
        assertEquals(4, MyDeadlines.toolResult(list, now, null, includeDone = true).getInt("count"))
        assertEquals(3, MyDeadlines.toolResult(list, now, null, includeDone = false).getInt("count"))
    }

    @Test fun parseDueKst() {
        assertEquals(Instant.parse("2026-11-23T14:59:00Z"), MyDeadlines.parseDue("2026-11-23 23:59"))
        assertEquals(Instant.parse("2026-11-23T00:30:00Z"), MyDeadlines.parseDue("2026-11-23T9:30"))
        assertEquals(Instant.parse("2026-11-23T14:59:00Z"), MyDeadlines.parseDue("2026-11-23"))
        assertEquals(Instant.parse("2026-11-23T09:00:00Z"), MyDeadlines.parseDue("2026-11-23 18:00:00"))
        assertNull(MyDeadlines.parseDue("내일 6시"))
        assertNull(MyDeadlines.parseDue("2026-13-01 10:00"))
        assertTrue(MyDeadlines.toolResult(listOf(mine(7, hours(5))), now, null, false).getJSONArray("items").getJSONObject(0).has("id"))
    }

    @Test fun customReminders() {
        // 오늘(11/19) 22:00 마감, 21:00·21:50 알림
        val due = Instant.parse("2026-11-19T13:00:00Z")
        val r1 = MyDeadlines.parseReminder("21:00", due)!!
        val r2 = MyDeadlines.parseReminder("2026-11-19 21:50", due)!!
        assertEquals(Instant.parse("2026-11-19T12:00:00Z"), r1)
        assertEquals(Instant.parse("2026-11-19T12:50:00Z"), r2)
        assertEquals(Instant.parse("2026-11-18T14:30:00Z"), MyDeadlines.parseReminder("23:30", due)) // 마감보다 늦은 시각이면 전날
        assertNull(MyDeadlines.parseReminder("저녁", due))
        assertNotNull(MyDeadlines.validateReminders(listOf(due), due))
        val d = MyDeadline(5, "보고서", due, reminders = listOf(r1, r2))
        assertEquals(d, MyDeadlines.fromJson(MyDeadlines.toJson(listOf(d))).single())
        assertEquals("21:00, 21:50", MyDeadlines.reminderLabel(d))

        val at2105 = Instant.parse("2026-11-19T12:05:00Z")
        val n = MyDeadlines.reminderNotices(listOf(d), at2105, emptySet())
        assertEquals(1, n.size)
        assertEquals("알림 · 보고서", n[0].title)
        assertTrue(n[0].text.contains("55분"))
        val fired = n[0].firedKeys.toSet()
        assertTrue(MyDeadlines.reminderNotices(listOf(d), at2105, fired).isEmpty())
        assertEquals(r2, MyDeadlines.nextReminderAt(listOf(d), at2105, fired))
        // 늦게 깨어나도 마감 전이면 두 개를 한 알림으로
        assertEquals(2, MyDeadlines.reminderNotices(listOf(d), Instant.parse("2026-11-19T12:55:00Z"), emptySet()).single().firedKeys.size)
        // 마감이 지났거나 완료면 울리지 않는다
        assertTrue(MyDeadlines.reminderNotices(listOf(d), due.plusSeconds(60), emptySet()).isEmpty())
        assertTrue(MyDeadlines.reminderNotices(listOf(d.copy(done = true)), at2105, emptySet()).isEmpty())
        assertNull(MyDeadlines.nextReminderAt(listOf(d), due, emptySet()))
    }
}
