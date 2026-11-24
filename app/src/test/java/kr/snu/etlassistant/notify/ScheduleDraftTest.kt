package kr.snu.etlassistant.notify

import kr.snu.etlassistant.chat.TimetableDraftLogic
import kr.snu.etlassistant.etl.Queries
import kr.snu.etlassistant.etl.Queries.AnnCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 공지 → "내 일정에 등록"/"시간표에 반영" 초안(I), 준비물 줄, 등록 안 한 공지 시트 대상. */
class ScheduleDraftTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(seoul).toInstant()
    private val posted = kst("2026-11-20T09:00")

    private fun ann(id: Long, title: String, text: String, course: String = "2026-2 자료구조 (001)", at: Instant = posted) =
        AnnouncementItem(id, 10, course, title, at, "https://myetl.snu.ac.kr/courses/10/discussion_topics/$id", text)

    @Test fun findsSingleDateTimeInManyForms() {
        fun one(s: String) = ScheduleDrafts.single(s, posted)
        assertEquals(kst("2026-11-28T19:00") to null, one("시험은 11월 28일(금) 19:00에 봅니다"))
        assertEquals(kst("2026-11-28T19:00"), one("11/28 19:00 301동")!!.first)
        assertEquals(kst("2026-11-28T19:00"), one("일시: 11.28.(금) 오후 7시")!!.first)
        assertEquals(kst("2026-11-28T19:00"), one("2026-11-28 19:00")!!.first)
        assertEquals(kst("2026-11-28T19:30"), one("11월 28일 오후 7시 반")!!.first)
        // 시간 범위는 끝 시각까지
        assertEquals(kst("2026-11-28T19:00") to kst("2026-11-28T21:00"), one("11월 28일(금) 19:00~21:00"))
        // 연도가 없으면 공지 날짜 기준 가장 가까운 미래(공지 11/20 → 1월은 다음 해)
        assertEquals(kst("2027-01-05T10:00"), one("1/5 10:00 기말고사")!!.first)
    }

    @Test fun multipleOrNoneGivesNothing() {
        assertNull(ScheduleDrafts.single("1차 11/28 19:00, 2차 12/5 19:00", posted))
        assertNull(ScheduleDrafts.single("시험은 다음 주에 봅니다", posted))
        // 시각 없는 날짜는 세지 않는다(시험·마감)
        assertNull(ScheduleDrafts.single("11월 28일 시험", posted))
        // 점수·소수는 날짜로 보지 않는다
        assertNull(ScheduleDrafts.single("평균 3.5점, 만점 10.0", posted))
        // 같은 시각을 두 번 써도 하나
        assertEquals(kst("2026-11-28T19:00"), ScheduleDrafts.single("11/28 19:00 (재공지: 11월 28일 19:00)", posted)!!.first)
    }

    @Test fun assignmentDetectionAvoidsResults() {
        assertTrue(Queries.isAssignmentAnnouncement("과제 3 안내", "다음 주까지"))
        assertTrue(Queries.isAssignmentAnnouncement("공지", "보고서는 금요일까지 제출하세요"))
        assertFalse(Queries.isAssignmentAnnouncement("과제 2 성적 공개", ""))
        assertFalse(Queries.isAssignmentAnnouncement("과제 정답 공개", ""))
        assertFalse(Queries.isAssignmentAnnouncement("과제 해설", "제출 감사합니다"))
        assertFalse(Queries.isAssignmentAnnouncement("수업 자료", "과제와 무관한 내용"))
        assertFalse(Queries.isAssignmentAnnouncement("중간고사 안내", "과제 대신 시험 범위 안내, 일시 11/28"))
        assertEquals("과제 공지", Queries.announcementBadge("[자료구조] HW3 안내", ""))
    }

    @Test fun categoriesAndPriority() {
        assertEquals(AnnCategory.EXAM, Queries.announcementCategory("중간고사 및 과제 안내", ""))
        assertEquals(AnnCategory.ASSIGNMENT, Queries.announcementCategory("과제 제출 안내(보강 수업 포함)", ""))
        assertEquals(AnnCategory.VIDEO, Queries.announcementCategory("11/28 동영상 강의 안내", "12/1까지 시청하세요. 대면 수업은 없습니다"))
        assertEquals(AnnCategory.MAKEUP, Queries.announcementCategory("보강 안내", "12월 3일 19:00~20:15 보강"))
        assertEquals(AnnCategory.CANCEL, Queries.announcementCategory("휴강 안내", "11월 28일 수업은 휴강합니다"))
        // 단순 자료 링크는 동영상 강의 공지가 아니다
        assertNull(Queries.announcementCategory("강의 영상 링크", "참고 자료입니다"))
        assertEquals("동영상 강의", Queries.announcementBadge("녹화 강의 업로드", "출석 인정 기간: 12/1까지"))
    }

    @Test fun examDraftCleansTitleAndCollectsSupplies() {
        val d = ScheduleDrafts.fromAnnouncement(ann(1, "[자료구조] 중간고사 안내", "일시: 11월 28일(금) 19:00~21:00\n장소: 301동\n준비물: 공학용 계산기, 신분증"))!!
        assertEquals(MyKind.EXAM, d.kind)
        assertEquals("중간고사 안내", d.title)
        assertEquals("자료구조", d.course)
        assertEquals("준비물: 공학용 계산기, 신분증", d.supplies)
        assertEquals(kst("2026-11-28T19:00"), d.start)
        assertEquals(kst("2026-11-28T21:00"), d.end)
        assertTrue(d.memo.contains("https://myetl.snu.ac.kr/courses/10/discussion_topics/1"))
        assertTrue(d.prefill.endsWith("시험 일정(날짜·시간·장소·준비물) 내 일정에 넣어줘 (공지 ID 1)"))
        assertEquals(d, ScheduleDraft.fromJson(d.toJson()))
        assertEquals("내 일정에 등록", d.actionLabel)
    }

    @Test fun titleCleanup() {
        assertEquals("과제 3", ScheduleDrafts.cleanTitle("[공지] [자료구조] 과제 3", "2026-2 자료구조 (001)", "자료구조"))
        assertEquals("기말 안내", ScheduleDrafts.cleanTitle("공지: 자료구조 - 기말 안내", "2026-2 자료구조 (001)", "자료구조"))
    }

    @Test fun suppliesLinesFromVariousWordings() {
        val text = "시험 범위: 1~5장\n- 준비물: 공학용 계산기\n※ 신분증 지참 필수\nA4 요약지 1장 허용합니다. 휴대폰은 끄세요.\n컴퓨터용 사인펜을 가져오세요"
        assertEquals(listOf("준비물: 공학용 계산기", "신분증 지참 필수"), Queries.suppliesLines(text))
        assertEquals(3, Queries.suppliesLines(text, maxLines = 3).size)
        assertEquals(listOf("Calculators are not allowed."), Queries.suppliesLines("Exam on Friday. Calculators are not allowed. Good luck"))
        assertTrue(Queries.suppliesLines("가".repeat(100) + " 계산기", maxChars = 60).single().length <= 60)
    }

    @Test fun timetableDrafts() {
        val mk = ScheduleDrafts.fromAnnouncement(ann(3, "보강 안내", "12월 3일(수) 19:00~20:15에 보강합니다. 강의실 302호"))!!
        assertTrue(mk.timetable)
        assertEquals("시간표에 반영", mk.actionLabel)
        assertEquals(LocalDate.parse("2026-12-03"), mk.day)
        val pre = TimetableDraftLogic.prefill(mk, emptyList())
        assertEquals("보강", pre.kind)
        assertEquals(LocalTime.of(19, 0), pre.start)
        assertEquals(LocalTime.of(20, 15), pre.end)
        assertEquals("자료구조", pre.courseText)
        assertTrue(mk.prefill.contains("보강을 시간표에 넣어줘 (공지 ID 3)"))
        val cancel = ScheduleDrafts.fromAnnouncement(ann(4, "휴강 공지", "11월 28일(금) 수업은 휴강합니다"))!!
        assertEquals(AnnCategory.CANCEL, cancel.category)
        assertEquals(LocalDate.parse("2026-11-28"), cancel.day)
        assertNull(cancel.start)
        assertEquals("휴강", TimetableDraftLogic.prefill(cancel, emptyList()).kind)
        // 날짜가 둘이면 비운다
        assertNull(ScheduleDrafts.fromAnnouncement(ann(5, "휴강 공지", "11월 28일과 12월 5일 휴강"))!!.day)
    }

    @Test fun videoDraftAndClassReplacement() {
        val v = ScheduleDrafts.fromAnnouncement(ann(6, "11/28(금) 동영상 강의 안내", "11월 28일 대면 수업은 없습니다. 12월 1일(월) 23:59까지 시청하세요"))!!
        assertEquals(AnnCategory.VIDEO, v.category)
        assertEquals(MyKind.DEADLINE, v.kind)
        assertEquals("[동영상] 자료구조 11/28(금) 동영상 강의 안내", v.title)
        assertEquals(kst("2026-12-01T23:59"), v.start)
        assertEquals(LocalDate.parse("2026-11-28"), v.day)
        assertTrue(v.replacesClass)
        assertTrue(v.prefill.contains("동영상 강의 시청 기한을 내 일정에 넣고"))
        val noClue = ScheduleDrafts.fromAnnouncement(ann(7, "녹화 강의 업로드", "12월 1일(월) 23:59까지 시청"))!!
        assertFalse(noClue.replacesClass)
    }

    @Test fun pendingTargetsAndAutoShow() {
        val now = kst("2026-11-24T12:00")
        val anns = listOf(
            ann(1, "중간고사 안내", "11/28 19:00"),
            ann(2, "과제 3 안내", "금요일까지 제출"),
            ann(3, "수업 자료", "참고"),
            ann(4, "휴강 공지", "11월 28일 휴강", at = kst("2026-11-01T09:00")), // 14일 넘음
        )
        val list = PendingNoticesLogic.add(emptyList(), anns, now)
        assertEquals(listOf(1L, 2L), list.map { it.id }.sorted())
        assertEquals(listOf(2L), PendingNoticesLogic.targets(list, registered = setOf(1L), ignored = emptySet(), excludedCourse = { false }, now = now).map { it.id })
        assertTrue(PendingNoticesLogic.targets(list, emptySet(), setOf(1L, 2L), { false }, now).isEmpty())
        assertTrue(PendingNoticesLogic.targets(list, emptySet(), emptySet(), { it == 10L }, now).isEmpty()) // 숨긴·알림 끈 과목
        val t = PendingNoticesLogic.targets(list, emptySet(), emptySet(), { false }, now)
        assertTrue(PendingNoticesLogic.shouldAutoShow(t, seen = emptySet(), enabled = true, updatePromptShowing = false))
        assertFalse(PendingNoticesLogic.shouldAutoShow(t, seen = setOf(1L, 2L), enabled = true, updatePromptShowing = false))
        assertFalse(PendingNoticesLogic.shouldAutoShow(t, seen = emptySet(), enabled = true, updatePromptShowing = true))
        assertFalse(PendingNoticesLogic.shouldAutoShow(t, seen = emptySet(), enabled = false, updatePromptShowing = false))
        assertTrue(PendingNoticesLogic.shouldAutoShow(t, seen = setOf(1L), enabled = true, updatePromptShowing = false)) // 새 공지가 하나 있으면
        assertEquals(list, PendingNoticesLogic.fromJson(PendingNoticesLogic.toJson(list)))
    }
}
