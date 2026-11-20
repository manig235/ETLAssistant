package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class NotificationPlannerTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    /** 2026-11-19(목) 12:00 KST */
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z")
    private val settings = NotifySettings()

    private fun asg(
        id: Long,
        due: Instant?,
        course: String = "강의$id",
        name: String = "과제$id",
        cid: Long = 1,
        online: Boolean = true,
        submitted: Boolean = false,
        missing: Boolean = false,
        url: String? = "https://myetl.snu.ac.kr/courses/$cid/assignments/$id",
    ) = AssignmentItem(cid, course, id, name, due, online, null, url, submitted, missing)

    private fun ann(id: Long, hoursAgo: Long = 1, course: String = "강의", title: String = "제목$id", text: String = "본문$id", url: String? = "https://myetl.snu.ac.kr/a/$id") =
        AnnouncementItem(id, 1, course, title, now.minus(Duration.ofHours(hoursAgo)), url, text)

    private fun snap(a: List<AssignmentItem> = emptyList(), n: List<AnnouncementItem> = emptyList()) = Snapshot(now, a, n)
    private fun state(done: Boolean = true, vararg keys: String) =
        NotifyState(fired = keys.associateWith { 0L }.toMutableMap(), announcementBaselineDone = done)

    private fun plan(s: Snapshot, st: NotifyState = state(), set: NotifySettings = settings, at: Instant = now) =
        NotificationPlanner.plan(s, st, at, set, seoul)

    private fun hours(h: Long) = now.plus(Duration.ofHours(h))

    // ---- 마감: 경계 ----------------------------------------------------------

    @Test fun exactlyWindowBoundaryFires() {
        val p = plan(snap(listOf(asg(1, hours(24)))))
        assertEquals(1, p.notices.size)
        assertEquals("deadline:1:1:24", p.notices[0].firedKeys.first())
    }

    @Test fun oneSecondBeyondWindowFiresNextWindow() {
        val p = plan(snap(listOf(asg(1, hours(24).plusSeconds(1)))))
        assertEquals(1, p.notices.size)
        assertEquals(listOf("deadline:1:1:72"), p.notices[0].firedKeys)
    }

    @Test fun beyondMaxWindowDoesNothing() {
        assertTrue(plan(snap(listOf(asg(1, hours(72).plusSeconds(1))))).notices.isEmpty())
        assertEquals(1, plan(snap(listOf(asg(1, hours(72))))).notices.size)
    }

    @Test fun pastDueNowAndNullDueAreIgnored() {
        val list = listOf(asg(1, now), asg(2, now.minusSeconds(1)), asg(3, null))
        assertTrue(plan(snap(list)).notices.isEmpty())
    }

    @Test fun submittedAndMissingAreIgnored() {
        val list = listOf(asg(1, hours(5), submitted = true), asg(2, hours(5), missing = true))
        assertTrue(plan(snap(list)).notices.isEmpty())
    }

    @Test fun oneSecondFromNowFires() {
        assertEquals(1, plan(snap(listOf(asg(1, now.plusSeconds(1))))).notices.size)
    }

    // ---- 마감: 키 --------------------------------------------------------------

    @Test fun firstCheckInside24hRecordsLooserKeysToo() {
        val n = plan(snap(listOf(asg(7, hours(10), cid = 3)))).notices
        assertEquals(1, n.size)
        assertEquals(setOf("deadline:3:7:24", "deadline:3:7:72"), n[0].firedKeys.toSet())
    }

    @Test fun firedKeyIsSkipped() {
        val st = state(true, "deadline:1:1:24")
        assertTrue(plan(snap(listOf(asg(1, hours(10)))), st).notices.isEmpty())
    }

    @Test fun after72hFiredThen24hStillFires() {
        val st = state(true, "deadline:1:1:72")
        val n = plan(snap(listOf(asg(1, hours(10)))), st).notices
        assertEquals(1, n.size)
        assertEquals("deadline:1:1:24", n[0].firedKeys.first())
    }

    @Test fun duplicateAssignmentsInSnapshotCountOnce() {
        val a = asg(1, hours(10))
        val n = plan(snap(listOf(a, a))).notices
        assertEquals(1, n.size)
        assertEquals("deadline:1:1", n[0].tag)
    }

    @Test fun planDoesNotMutateState() {
        val st = state(false, "x")
        plan(snap(listOf(asg(1, hours(5))), listOf(ann(1))), st)
        assertEquals(setOf("x"), st.fired.keys)
        assertFalse(st.announcementBaselineDone)
    }

    @Test fun deadlineAlertsOffAndEmptyWindows() {
        val list = listOf(asg(1, hours(5)))
        assertTrue(plan(snap(list), set = settings.copy(deadlineAlerts = false)).notices.isEmpty())
        assertTrue(plan(snap(list), set = settings.copy(deadlineWindowsHours = emptyList())).notices.isEmpty())
    }

    @Test fun customWindowsUnsortedWork() {
        val set = settings.copy(deadlineWindowsHours = listOf(6, 48, 12))
        val n = plan(snap(listOf(asg(1, hours(10)))), set = set).notices
        assertEquals(setOf("deadline:1:1:12", "deadline:1:1:48"), n[0].firedKeys.toSet())
    }

    // ---- 마감: 묶음/단일/정렬 -----------------------------------------------------

    @Test fun singleHasSpecificTitleTagAndUrl() {
        val n = plan(snap(listOf(asg(5, hours(30), course = "자료구조", name = "HW1")))).notices.single()
        assertEquals(Channel.DEADLINE, n.channel)
        assertEquals("마감 임박 · 자료구조", n.title)
        assertEquals("deadline:1:5", n.tag)
        assertEquals("https://myetl.snu.ac.kr/courses/1/assignments/5", n.url)
        assertTrue(n.text.contains("HW1"))
        assertTrue(n.bigText!!.startsWith("• 11/20(금) 18:00 · 자료구조 — HW1 (1일 6시간 남음)"))
    }

    @Test fun singleWithForeignUrlHasNoUrl() {
        val n = plan(snap(listOf(asg(5, hours(30), url = "https://evil.example.com/x")))).notices.single()
        assertNull(n.url)
        assertNull(plan(snap(listOf(asg(5, hours(30), url = "http://myetl.snu.ac.kr/x")))).notices.single().url)
        assertNull(plan(snap(listOf(asg(5, hours(30), url = null)))).notices.single().url)
    }

    @Test fun sameWindowGroupedInto1Notice() {
        val n = plan(snap(listOf(asg(1, hours(20)), asg(2, hours(10)), asg(3, hours(15))))).notices
        assertEquals(1, n.size)
        val g = n[0]
        assertEquals("마감 임박 3건 · 24시간 이내", g.title)
        assertEquals("deadline:w24", g.tag)
        assertNull(g.url)
        val lines = g.bigText!!.lines()
        assertEquals(3, lines.size)
        // 마감 오름차순: 10h(2), 15h(3), 20h(1)
        assertTrue(lines[0].contains("과제2"))
        assertTrue(lines[1].contains("과제3"))
        assertTrue(lines[2].contains("과제1"))
        assertTrue(g.text.contains("과제2") && g.text.endsWith("외 2건"))
        assertEquals(3, g.firedKeys.count { it.endsWith(":24") })
        assertEquals(3, g.firedKeys.count { it.endsWith(":72") })
    }

    @Test fun window72LabelIs3Days() {
        val n = plan(snap(listOf(asg(1, hours(50)), asg(2, hours(60))))).notices.single()
        assertEquals("마감 임박 2건 · 3일 이내", n.title)
        assertEquals("deadline:w72", n.tag)
    }

    @Test fun differentWindowsGiveSeparateNoticesTightestFirst() {
        val n = plan(snap(listOf(asg(1, hours(60)), asg(2, hours(5)), asg(3, hours(70)), asg(4, hours(6))))).notices
        assertEquals(2, n.size)
        assertEquals("deadline:w24", n[0].tag)
        assertEquals("deadline:w72", n[1].tag)
        assertEquals("마감 임박 2건 · 24시간 이내", n[0].title)
    }

    @Test fun bigTextLimitedTo6LinesWithRemainder() {
        val list = (1L..9L).map { asg(it, hours(it)) }
        // 묶음을 보려는 시험이라 모두 같은 창(24시간)에 들어가게 창을 고정한다.
        val g = plan(snap(list), set = settings.copy(deadlineWindowsHours = listOf(72, 24))).notices.single()
        val lines = g.bigText!!.lines()
        assertEquals(7, lines.size)
        assertEquals("외 3건", lines.last())
        assertEquals(6, lines.count { it.startsWith("• ") })
        assertEquals(9, g.title.substringAfter("임박 ").substringBefore("건").toInt())
    }

    @Test fun exactlySixLinesHasNoRemainder() {
        val list = (1L..6L).map { asg(it, hours(it)) }
        val lines = plan(snap(list), set = settings.copy(deadlineWindowsHours = listOf(72, 24))).notices.single().bigText!!.lines()
        assertEquals(6, lines.size)
        assertFalse(lines.any { it.startsWith("외") })
    }

    @Test fun offlineMarked() {
        val n = plan(snap(listOf(asg(1, hours(5), online = false)))).notices.single()
        assertTrue(n.bigText!!.endsWith("(오프라인)"))
        assertTrue(n.text.endsWith("(오프라인)"))
        val online = plan(snap(listOf(asg(1, hours(5))))).notices.single()
        assertFalse(online.bigText!!.contains("오프라인"))
    }

    @Test fun sameDueTimeIsStableAndDoesNotThrow() {
        val list = listOf(asg(2, hours(5), course = "B"), asg(1, hours(5), course = "A"))
        val g = plan(snap(list)).notices.single()
        assertTrue(g.bigText!!.lines()[0].contains("A —"))
    }

    @Test fun emptySnapshotIsEmptyPlan() {
        val p = plan(snap())
        assertTrue(p.notices.isEmpty())
        assertTrue(p.silentKeys.isEmpty())
    }

    // ---- 방해 금지 ----------------------------------------------------------------

    @Test fun quietHoursProduceNothingAndNoKeys() {
        val quiet = Instant.parse("2026-11-19T14:30:00Z") // 23:30 KST
        val p = plan(snap(listOf(asg(1, hours(5))), listOf(ann(1))), state(true), at = quiet)
        assertTrue(p.notices.isEmpty())
        assertTrue(p.silentKeys.isEmpty())
        assertFalse(p.markBaselineDone)
    }

    @Test fun quietAfterMidnightAndBoundaries() {
        val s = snap(listOf(asg(1, hours(30))))
        // 03:00 KST = 18:00Z: 조용
        assertTrue(plan(s, at = Instant.parse("2026-11-18T18:00:00Z")).notices.isEmpty())
        // 06:59 KST 조용 / 07:00 KST 해제
        assertTrue(plan(s, at = Instant.parse("2026-11-18T21:59:00Z")).notices.isEmpty())
        assertEquals(1, plan(s, at = Instant.parse("2026-11-18T22:00:00Z")).notices.size)
        // 22:59 KST 해제 / 23:00 KST 조용
        assertEquals(1, plan(s, at = Instant.parse("2026-11-19T13:59:00Z")).notices.size)
        assertTrue(plan(s, at = Instant.parse("2026-11-19T14:00:00Z")).notices.isEmpty())
    }

    @Test fun quietUsesGivenZoneNotUtc() {
        val s = snap(listOf(asg(1, hours(30))))
        val at = Instant.parse("2026-11-19T03:00:00Z") // KST 12:00(낮), UTC 03:00(조용)
        assertEquals(1, NotificationPlanner.plan(s, state(), at, settings, seoul).notices.size)
        assertTrue(NotificationPlanner.plan(s, state(), at, settings, ZoneId.of("UTC")).notices.isEmpty())
    }

    @Test fun quietDisabledAllowsAlerts() {
        val quiet = Instant.parse("2026-11-19T14:30:00Z")
        val s = snap(listOf(asg(1, quiet.plusSeconds(3600))))
        assertEquals(1, plan(s, set = settings.copy(quietEnabled = false), at = quiet).notices.size)
    }

    @Test fun baselineRecordedEvenInQuietHours() {
        val quiet = Instant.parse("2026-11-19T14:30:00Z")
        val p = plan(snap(n = listOf(ann(1), ann(2))), state(false), at = quiet)
        assertTrue(p.notices.isEmpty())
        assertEquals(listOf("ann:1", "ann:2"), p.silentKeys)
        assertTrue(p.markBaselineDone)
    }

    // ---- 공지 --------------------------------------------------------------------

    @Test fun baselineFirstRunIsSilent() {
        val p = plan(snap(listOf(asg(1, hours(5))), listOf(ann(1), ann(2), ann(3), ann(4, hoursAgo = 100))), state(false))
        assertEquals(setOf("ann:1", "ann:2", "ann:3", "ann:4"), p.silentKeys.toSet())
        assertTrue(p.markBaselineDone)
        // 마감 알림은 기준선과 무관하게 나온다
        assertEquals(1, p.notices.size)
        assertEquals(Channel.DEADLINE, p.notices[0].channel)
    }

    @Test fun baselineAlreadyDoneNotMarkedAgain() {
        val p = plan(snap(n = listOf(ann(1))), state(true))
        assertFalse(p.markBaselineDone)
        assertTrue(p.silentKeys.isEmpty())
        assertEquals(1, p.notices.size)
    }

    @Test fun announcementAlertsOffMeansNoBaseline() {
        val p = plan(snap(n = listOf(ann(1))), state(false), set = settings.copy(announcementAlerts = false))
        assertTrue(p.notices.isEmpty())
        assertTrue(p.silentKeys.isEmpty())
        assertFalse(p.markBaselineDone)
    }

    @Test fun firedAnnouncementsExcluded() {
        val p = plan(snap(n = listOf(ann(1), ann(2))), state(true, "ann:1"))
        assertEquals(1, p.notices.size)
        assertEquals("ann:2", p.notices[0].tag)
    }

    @Test fun threeAnnouncementsAreIndividualNewestFirst() {
        val n = plan(snap(n = listOf(ann(1, hoursAgo = 5), ann(2, hoursAgo = 1), ann(3, hoursAgo = 3)))).notices
        assertEquals(3, n.size)
        assertEquals(listOf("ann:2", "ann:3", "ann:1"), n.map { it.tag })
        n.forEach {
            assertEquals(Channel.ANNOUNCEMENT, it.channel)
            assertEquals(listOf(it.tag), it.firedKeys)
        }
        assertEquals("강의 · 새 공지", n[0].title)
        assertEquals("제목2", n[0].text)
        assertEquals("본문2", n[0].bigText)
        assertEquals("https://myetl.snu.ac.kr/a/2", n[0].url)
    }

    @Test fun fourAnnouncementsBecomeOneSummary() {
        val list = (1L..4L).map { ann(it, hoursAgo = it, course = "C$it") }
        val n = plan(snap(n = list)).notices.single()
        assertEquals("새 공지 4건", n.title)
        assertEquals("ann:summary", n.tag)
        assertNull(n.url)
        assertEquals(setOf("ann:1", "ann:2", "ann:3", "ann:4"), n.firedKeys.toSet())
        assertEquals(4, n.bigText!!.lines().size)
        assertEquals("• [C1] 제목1", n.bigText!!.lines()[0])
    }

    @Test fun summaryLimitedTo8Lines() {
        val list = (1L..11L).map { ann(it, hoursAgo = it) }
        val n = plan(snap(n = list)).notices.single()
        val lines = n.bigText!!.lines()
        assertEquals(9, lines.size)
        assertEquals("외 3건", lines.last())
        assertEquals(11, n.firedKeys.size)
    }

    @Test fun announcementWithoutBodyHasNullBigText() {
        val n = plan(snap(n = listOf(ann(1, text = "  \n "))), state()).notices.single()
        assertNull(n.bigText)
    }

    @Test fun announcementBodyCutTo300() {
        val n = plan(snap(n = listOf(ann(1, text = "가".repeat(700))))).notices.single()
        assertEquals(300, n.bigText!!.length)
        assertTrue(n.bigText!!.endsWith("…"))
    }

    @Test fun announcementForeignUrlRejected() {
        val n = plan(snap(n = listOf(ann(1, url = "https://myetl.snu.ac.kr.evil.com/x")))).notices.single()
        assertNull(n.url)
    }

    @Test fun duplicateAnnouncementIdsCountOnce() {
        val n = plan(snap(n = listOf(ann(1), ann(1)))).notices
        assertEquals(1, n.size)
    }

    // ---- 안전 --------------------------------------------------------------------

    @Test fun controlCharsAndNewlinesAreSanitized() {
        val evil = "A\nB\r\nC\u0000D\u0007E\t\tF‮evil G"
        val n = plan(snap(listOf(asg(1, hours(5), course = evil, name = evil)))).notices.single()
        assertEquals("마감 임박 · A B C D E Fevil G", n.title)
        assertFalse(n.text.contains('\n'))
        assertEquals(1, n.bigText!!.lines().size)
        assertFalse(n.bigText!!.any { it == '\u0000' || it == '‮' || it == '\r' })
    }

    @Test fun longStringsAreTruncated() {
        val long = "가".repeat(500)
        val n = plan(snap(listOf(asg(1, hours(5), course = long, name = long)))).notices.single()
        assertTrue(n.title.length <= 80)
        assertTrue(n.title.endsWith("…"))
        assertTrue(n.text.length <= 140)
        assertTrue(n.bigText!!.length <= 140)
        val a = plan(snap(n = listOf(ann(1, course = long, title = long)))).notices.single()
        assertTrue(a.title.length <= 80)
        assertTrue(a.text.length <= 140)
        assertTrue(a.text.endsWith("…"))
    }

    @Test fun truncationDoesNotSplitSurrogatePairs() {
        val emoji = "😀".repeat(100)
        val n = plan(snap(n = listOf(ann(1, title = emoji)))).notices.single()
        assertTrue(n.text.length <= 140)
        assertFalse(Character.isHighSurrogate(n.text[n.text.length - 2]))
    }

    @Test fun groupLinesStayWithinLimit() {
        val long = "x".repeat(400)
        val g = plan(snap(listOf(asg(1, hours(5), course = long, name = long), asg(2, hours(6), online = false, course = long, name = long)))).notices.single()
        g.bigText!!.lines().forEach { assertTrue(it.length <= 140) }
        assertTrue(g.text.length <= 140)
    }

    @Test fun summaryTitlesSanitized() {
        val list = (1L..5L).map { ann(it, title = "a\nb$it") }
        val n = plan(snap(n = list)).notices.single()
        assertEquals(5, n.bigText!!.lines().size)
        assertFalse(n.text.contains('\n'))
    }

    // ---- 다음 확인 시각(정확한 시각 알람) ----------------------------------------

    private fun next(s: Snapshot, st: NotifyState = state(), set: NotifySettings = settings) =
        NotificationPlanner.nextDeadlineCheckAt(s, st, now, set)

    @Test fun defaultWindowsAre3d24h3h1h() {
        assertEquals(listOf(72, 24, 3, 1), NotifySettings().deadlineWindowsHours)
    }

    @Test fun nextCheckIsEarliestUnfiredWindow() {
        // 마감 5시간 뒤: 3시간 전(2시간 뒤)이 가장 이르다. 72·24시간 창은 이미 지났다.
        assertEquals(hours(2), next(snap(listOf(asg(1, hours(5))))))
        // 3시간 창을 띄웠으면 1시간 전(4시간 뒤)
        assertEquals(hours(4), next(snap(listOf(asg(1, hours(5)))), state(true, "deadline:1:1:3")))
    }

    @Test fun nextCheckAcrossAssignments() {
        val s = snap(listOf(asg(1, hours(30)), asg(2, hours(2))))
        // 과제2의 1시간 전(1시간 뒤)이 과제1의 24시간 전(6시간 뒤)보다 이르다.
        assertEquals(hours(1), next(s))
    }

    @Test fun nextCheckIgnoresSubmittedPastAndAllFired() {
        assertNull(next(snap(listOf(asg(1, hours(5), submitted = true)))))
        assertNull(next(snap(listOf(asg(1, hours(-1))))))
        assertNull(next(snap(listOf(asg(1, hours(5)))), state(true, "deadline:1:1:3", "deadline:1:1:1")))
        assertNull(next(snap(listOf(asg(1, hours(5)))), set = settings.copy(deadlineAlerts = false)))
    }

    @Test fun oneHourWindowFiresAtBoundary() {
        val p = plan(snap(listOf(asg(1, hours(1)))))
        assertEquals(1, p.notices.size)
        assertEquals("deadline:1:1:1", p.notices[0].firedKeys.first())
        assertTrue(p.notices[0].firedKeys.containsAll(listOf("deadline:1:1:3", "deadline:1:1:24", "deadline:1:1:72")))
    }

    @Test fun normalizeWindowsKeepsChoicesOnly() {
        assertEquals(listOf(72, 3), NotifySettings.normalizeWindows(listOf(3, 72, 5, 3)))
        assertEquals(NotifySettings.DEFAULT_DEADLINE_WINDOWS, NotifySettings.normalizeWindows(listOf(5)))
    }
}
