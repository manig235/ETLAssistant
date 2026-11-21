package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class BriefBuilderTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val utc = ZoneId.of("UTC")
    /** 2026-11-19(목) 12:00 KST */
    private val now: Instant = Instant.parse("2026-11-19T03:00:00Z")
    private val settings = NotifySettings()

    private fun h(x: Long) = now.plus(Duration.ofHours(x))

    private fun asg(
        id: Long,
        due: Instant?,
        course: String = "강의$id",
        name: String = "과제$id",
        online: Boolean = true,
        submitted: Boolean = false,
        missing: Boolean = false,
    ) = AssignmentItem(1, course, id, name, due, online, null, null, submitted, missing)

    private fun ann(id: Long, hoursAgo: Long = 1, course: String = "강의", title: String = "제목$id") =
        AnnouncementItem(id, 1, course, title, now.minus(Duration.ofHours(hoursAgo)), null, "본문")

    private fun snap(a: List<AssignmentItem> = emptyList(), n: List<AnnouncementItem> = emptyList(), errors: List<String> = emptyList()) =
        Snapshot(now, a, n, errors)

    private fun build(s: Snapshot, zone: ZoneId = seoul, look: Int = 24, at: Instant = now) =
        BriefBuilder.build(s, at, settings, zone, look)

    @Test fun fixedFields() {
        val b = build(snap())
        assertEquals(Channel.BRIEF, b.channel)
        assertEquals("brief", b.tag)
        assertTrue(b.firedKeys.isEmpty())
        assertNull(b.url)
        assertEquals("eTL 브리핑 · 11/19(목)", b.title)
    }

    @Test fun allEmpty() {
        val b = build(snap())
        assertEquals("급한 마감, 누락, 새 공지가 없어요", b.text)
        assertEquals("급한 마감, 누락, 새 공지가 없어요", b.bigText)
    }

    @Test fun allEmptyWithErrors() {
        val b = build(snap(errors = listOf("강의 3 조회 실패")))
        assertEquals("급한 마감, 누락, 새 공지가 없어요", b.text)
        assertEquals("급한 마감, 누락, 새 공지가 없어요\n\n※ 일부 항목을 조회하지 못했어요", b.bigText)
    }

    @Test fun countsAndSections() {
        val s = snap(
            listOf(
                asg(1, h(80), course = "자료구조", name = "HW1"), // 3일 8시간 → 7일 이내
                asg(2, h(1), submitted = true),
                asg(3, h(-30), missing = true, course = "알고리즘", name = "PS2"),
            ),
            listOf(ann(1, 2, "운영체제", "휴강 안내")),
        )
        val b = build(s)
        assertEquals("마감 임박 1건 · 누락 1건 · 새 공지 1건", b.text)
        val sections = b.bigText!!.split("\n\n")
        assertEquals(3, sections.size)
        assertEquals("■ 마감 예정 (7일 이내, 미제출)\n• 11/22(일) 20:00 · 자료구조 — HW1 (3일 8시간 남음)", sections[0])
        assertEquals("■ 누락 (마감 지남, 미제출)\n• 11/18(수) 06:00 · 알고리즘 — PS2", sections[1])
        assertEquals("■ 새 공지 (최근 24시간)\n• [운영체제] 휴강 안내", sections[2])
    }

    @Test fun emptySectionsOmitted() {
        val b = build(snap(n = listOf(ann(1))))
        assertEquals("마감 임박 0건 · 누락 0건 · 새 공지 1건", b.text)
        assertFalse(b.bigText!!.contains("마감 예정"))
        assertFalse(b.bigText!!.contains("누락"))
        assertTrue(b.bigText!!.startsWith("■ 새 공지"))
    }

    @Test fun upcomingBoundaries() {
        val list = listOf(
            asg(1, now),                                   // 정확히 now: 포함
            asg(2, now.minusSeconds(1)),                   // 지남: 제외
            asg(3, now.plus(Duration.ofDays(7))),          // 정확히 +7일: 포함
            asg(4, now.plus(Duration.ofDays(7)).plusSeconds(1)), // 제외
            asg(5, null),                                  // 마감 없음: 제외
        )
        val b = build(snap(list))
        assertTrue(b.text.startsWith("마감 임박 2건"))
        val lines = b.bigText!!.lines().filter { it.startsWith("•") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("과제1"))
        assertTrue(lines[1].contains("과제3"))
    }

    @Test fun upcomingSortedAndLimited8WithRemainder() {
        val list = (1L..11L).map { asg(it, h(100 - it)) } // id 클수록 마감이 빠름
        val b = build(snap(list))
        assertTrue(b.text.startsWith("마감 임박 11건"))
        val lines = b.bigText!!.lines()
        assertEquals(1 + 8 + 1, lines.size)
        assertEquals("외 3건", lines.last())
        assertTrue(lines[1].contains("과제11"))
        assertTrue(lines[8].contains("과제4"))
    }

    @Test fun todayMarkerUsesZoneDate() {
        // KST 11/19 18:00 = 오늘, 11/20 09:00 = 내일
        val list = listOf(asg(1, h(6)), asg(2, h(21)))
        val lines = build(snap(list)).bigText!!.lines().filter { it.startsWith("•") }
        assertTrue(lines[0].startsWith("• [오늘] 11/19(목) 18:00"))
        assertTrue(lines[1].startsWith("• 11/20(금) 09:00"))
        assertFalse(lines[1].contains("[오늘]"))
    }

    @Test fun todayMarkerDiffersBetweenSeoulAndUtc() {
        // now(UTC 11/19 03:00, KST 11/19 12:00). due = UTC 11/19 16:00 = KST 11/20 01:00
        val due = Instant.parse("2026-11-19T16:00:00Z")
        val list = listOf(asg(1, due))
        val inSeoul = build(snap(list), seoul).bigText!!
        val inUtc = build(snap(list), utc).bigText!!
        assertFalse(inSeoul.contains("[오늘]"))
        assertTrue(inUtc.contains("[오늘]"))
        // 표시 시각은 두 경우 모두 KST 고정
        assertTrue(inSeoul.contains("11/20(금) 01:00"))
        assertTrue(inUtc.contains("11/20(금) 01:00"))
    }

    @Test fun titleDateFollowsZone() {
        val lateUtc = Instant.parse("2026-11-19T20:00:00Z") // KST 11/20(금) 05:00
        assertEquals("eTL 브리핑 · 11/20(금)", build(snap(), seoul, at = lateUtc).title)
        assertEquals("eTL 브리핑 · 11/19(목)", build(snap(), utc, at = lateUtc).title)
    }

    @Test fun offlineMarkedInBrief() {
        val b = build(snap(listOf(asg(1, h(5), online = false))))
        assertTrue(b.bigText!!.lines()[1].endsWith("(오프라인)"))
    }

    @Test fun missingLimited5NewestFirstWithRemainder() {
        val list = (1L..7L).map { asg(it, h(-it), missing = true) } // id 작을수록 최근
        val b = build(snap(list))
        assertEquals("마감 임박 0건 · 누락 7건 · 새 공지 0건", b.text)
        val lines = b.bigText!!.lines()
        assertEquals(1 + 5 + 1, lines.size)
        assertTrue(lines[1].contains("과제1"))
        assertTrue(lines[5].contains("과제5"))
        assertEquals("외 2건", lines.last())
    }

    @Test fun missingWithoutDueShowsLabel() {
        val b = build(snap(listOf(asg(1, null, missing = true))))
        assertTrue(b.bigText!!.contains("• 마감일 없음 · 강의1 — 과제1"))
    }

    @Test fun missingNotDoubleCountedAsUpcoming() {
        val b = build(snap(listOf(asg(1, h(5), missing = true))))
        assertTrue(b.text.startsWith("마감 임박 0건 · 누락 1건"))
    }

    @Test fun submittedMissingFlagIgnored() {
        val b = build(snap(listOf(asg(1, h(-5), missing = true, submitted = true))))
        assertEquals("급한 마감, 누락, 새 공지가 없어요", b.text)
    }

    @Test fun announcementsLookbackBoundaryAndOrderAndLimit() {
        val list = listOf(
            ann(1, hoursAgo = 24),  // 경계 포함
            ann(2, hoursAgo = 25),  // 제외
            ann(3, hoursAgo = 2),
            ann(4, hoursAgo = 3), ann(5, hoursAgo = 4), ann(6, hoursAgo = 5), ann(7, hoursAgo = 6), ann(8, hoursAgo = 7),
        )
        val b = build(snap(n = list))
        assertEquals("마감 임박 0건 · 누락 0건 · 새 공지 7건", b.text)
        val lines = b.bigText!!.lines()
        assertEquals("■ 새 공지 (최근 24시간)", lines[0])
        assertEquals(1 + 5 + 1, lines.size)
        assertEquals("• [강의] 제목3", lines[1])
        assertEquals("외 2건", lines.last())
    }

    @Test fun lookbackHoursUsedInHeader() {
        val b = build(snap(n = listOf(ann(1, hoursAgo = 30))), look = 48)
        assertTrue(b.bigText!!.startsWith("■ 새 공지 (최근 48시간)"))
        assertTrue(b.text.endsWith("새 공지 1건"))
    }

    @Test fun errorsLineLast() {
        val b = build(snap(listOf(asg(1, h(5))), errors = listOf("x")))
        assertTrue(b.bigText!!.endsWith("\n\n※ 일부 항목을 조회하지 못했어요"))
        assertFalse(b.bigText!!.contains("x 조회"))
    }

    @Test fun sanitizedAndTruncated() {
        val evil = "A\nB\u0000C"
        val long = "나".repeat(300)
        val b = build(snap(listOf(asg(1, h(5), course = evil, name = long)), n = listOf(ann(1, course = evil, title = "t\r\nu"))))
        val big = b.bigText!!
        assertTrue(big.contains("A B C"))
        assertTrue(big.contains("[A B C] t u"))
        assertFalse(big.contains('\u0000'))
        assertFalse(big.contains('\r'))
        big.lines().forEach { assertTrue(it.length <= 140) }
        assertTrue(big.contains("…"))
        // 줄바꿈은 구조의 \n 뿐: 섹션 2 + 줄 2 -> 빈 줄 하나 포함 총 5줄
        assertEquals(5, big.lines().size)
    }

    @Test fun emptyInputsDoNotThrow() {
        build(Snapshot(now, emptyList(), emptyList()), look = 0)
        build(snap(listOf(asg(1, now), asg(2, now))))
    }
}
