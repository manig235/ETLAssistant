import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 공지 → "내 일정에 등록"/"시간표에 반영" 초안, 준비물 줄, 등록 안 한 공지 시트 대상.
@Suite struct ScheduleDraftTests {
    let posted = kst("2026-11-20T09:00")

    func ann(_ id: Int64, _ title: String, _ text: String, course: String = "2026-2 자료구조 (001)", at: Date? = nil) -> AnnouncementItem {
        AnnouncementItem(id, 10, course, title, at ?? posted, "https://myetl.snu.ac.kr/courses/10/discussion_topics/\(id)", text)
    }

    func one(_ s: String) -> (Date, Date?)? { ScheduleDrafts.single(s, posted) }

    @Test func findsSingleDateTimeInManyForms() {
        let a = one("시험은 11월 28일(금) 19:00에 봅니다")!
        #expect(a.0 == kst("2026-11-28T19:00") && a.1 == nil)
        #expect(one("11/28 19:00 301동")?.0 == kst("2026-11-28T19:00"))
        #expect(one("일시: 11.28.(금) 오후 7시")?.0 == kst("2026-11-28T19:00"))
        #expect(one("2026-11-28 19:00")?.0 == kst("2026-11-28T19:00"))
        #expect(one("11월 28일 오후 7시 반")?.0 == kst("2026-11-28T19:30"))
        // 시간 범위는 끝 시각까지
        let r = one("11월 28일(금) 19:00~21:00")!
        #expect(r.0 == kst("2026-11-28T19:00") && r.1 == kst("2026-11-28T21:00"))
        // 연도가 없으면 공지 날짜 기준 가장 가까운 미래(공지 11/20 → 1월은 다음 해)
        #expect(one("1/5 10:00 기말고사")?.0 == kst("2027-01-05T10:00"))
    }

    @Test func multipleOrNoneGivesNothing() {
        #expect(ScheduleDrafts.single("1차 11/28 19:00, 2차 12/5 19:00", posted) == nil)
        #expect(ScheduleDrafts.single("시험은 다음 주에 봅니다", posted) == nil)
        // 시각 없는 날짜는 세지 않는다(시험·마감)
        #expect(ScheduleDrafts.single("11월 28일 시험", posted) == nil)
        // 점수·소수는 날짜로 보지 않는다
        #expect(ScheduleDrafts.single("평균 3.5점, 만점 10.0", posted) == nil)
        // 같은 시각을 두 번 써도 하나
        #expect(ScheduleDrafts.single("11/28 19:00 (재공지: 11월 28일 19:00)", posted)?.0 == kst("2026-11-28T19:00"))
    }

    @Test func assignmentDetectionAvoidsResults() {
        #expect(Queries.isAssignmentAnnouncement("과제 3 안내", "다음 주까지"))
        #expect(Queries.isAssignmentAnnouncement("공지", "보고서는 금요일까지 제출하세요"))
        #expect(!Queries.isAssignmentAnnouncement("과제 2 성적 공개", ""))
        #expect(!Queries.isAssignmentAnnouncement("과제 정답 공개", ""))
        #expect(!Queries.isAssignmentAnnouncement("과제 해설", "제출 감사합니다"))
        #expect(!Queries.isAssignmentAnnouncement("수업 자료", "과제와 무관한 내용"))
        #expect(!Queries.isAssignmentAnnouncement("중간고사 안내", "과제 대신 시험 범위 안내, 일시 11/28"))
        #expect(Queries.announcementBadge("[자료구조] HW3 안내", "") == "과제 공지")
    }

    @Test func categoriesAndPriority() {
        #expect(Queries.announcementCategory("중간고사 및 과제 안내", "") == .EXAM)
        #expect(Queries.announcementCategory("과제 제출 안내(보강 수업 포함)", "") == .ASSIGNMENT)
        #expect(Queries.announcementCategory("11/28 동영상 강의 안내", "12/1까지 시청하세요. 대면 수업은 없습니다") == .VIDEO)
        #expect(Queries.announcementCategory("보강 안내", "12월 3일 19:00~20:15 보강") == .MAKEUP)
        #expect(Queries.announcementCategory("휴강 안내", "11월 28일 수업은 휴강합니다") == .CANCEL)
        // 단순 자료 링크는 동영상 강의 공지가 아니다
        #expect(Queries.announcementCategory("강의 영상 링크", "참고 자료입니다") == nil)
        #expect(Queries.announcementBadge("녹화 강의 업로드", "출석 인정 기간: 12/1까지") == "동영상 강의")
    }

    @Test func examDraftCleansTitleAndCollectsSupplies() {
        let dr = ScheduleDrafts.fromAnnouncement(ann(1, "[자료구조] 중간고사 안내", "일시: 11월 28일(금) 19:00~21:00\n장소: 301동\n준비물: 공학용 계산기, 신분증"))!
        #expect(dr.kind == .EXAM)
        #expect(dr.title == "중간고사 안내")
        #expect(dr.course == "자료구조")
        #expect(dr.supplies == "준비물: 공학용 계산기, 신분증")
        #expect(dr.start == kst("2026-11-28T19:00"))
        #expect(dr.end == kst("2026-11-28T21:00"))
        #expect(dr.memo.contains("https://myetl.snu.ac.kr/courses/10/discussion_topics/1"))
        #expect(dr.prefill.hasSuffix("시험 일정(날짜·시간·장소·준비물) 내 일정에 넣어줘 (공지 ID 1)"))
        #expect(ScheduleDraft.fromJson(dr.toJson()) == dr)
        #expect(dr.actionLabel == "내 일정에 등록")
    }

    @Test func titleCleanup() {
        #expect(ScheduleDrafts.cleanTitle("[공지] [자료구조] 과제 3", "2026-2 자료구조 (001)", "자료구조") == "과제 3")
        #expect(ScheduleDrafts.cleanTitle("공지: 자료구조 - 기말 안내", "2026-2 자료구조 (001)", "자료구조") == "기말 안내")
    }

    @Test func suppliesLinesFromVariousWordings() {
        let text = "시험 범위: 1~5장\n- 준비물: 공학용 계산기\n※ 신분증 지참 필수\nA4 요약지 1장 허용합니다. 휴대폰은 끄세요.\n컴퓨터용 사인펜을 가져오세요"
        #expect(Queries.suppliesLines(text) == ["준비물: 공학용 계산기", "신분증 지참 필수"])
        #expect(Queries.suppliesLines(text, maxLines: 3).count == 3)
        #expect(Queries.suppliesLines("Exam on Friday. Calculators are not allowed. Good luck") == ["Calculators are not allowed."])
        let long = Queries.suppliesLines(String(repeating: "가", count: 100) + " 계산기", maxChars: 60)
        #expect(long.count == 1 && long[0].utf16.count <= 60)
    }

    /// 안드로이드 `TimetableDraftLogic.prefill`(채팅 화면의 보강·휴강 추가 창)은 iOS 화면 쪽이라 옮기지 않았다. 초안 값만 확인한다.
    @Test func timetableDrafts() {
        let mk = ScheduleDrafts.fromAnnouncement(ann(3, "보강 안내", "12월 3일(수) 19:00~20:15에 보강합니다. 강의실 302호"))!
        #expect(mk.timetable)
        #expect(mk.actionLabel == "시간표에 반영")
        #expect(mk.day == d("2026-12-03"))
        #expect(mk.start == kst("2026-12-03T19:00"))
        #expect(mk.end == kst("2026-12-03T20:15"))
        #expect(mk.course == "자료구조")
        #expect(mk.prefill.contains("보강을 시간표에 넣어줘 (공지 ID 3)"))
        let cancel = ScheduleDrafts.fromAnnouncement(ann(4, "휴강 공지", "11월 28일(금) 수업은 휴강합니다"))!
        #expect(cancel.category == .CANCEL)
        #expect(cancel.day == d("2026-11-28"))
        #expect(cancel.start == nil)
        // 날짜가 둘이면 비운다
        #expect(ScheduleDrafts.fromAnnouncement(ann(5, "휴강 공지", "11월 28일과 12월 5일 휴강"))!.day == nil)
    }

    @Test func videoDraftAndClassReplacement() {
        let v = ScheduleDrafts.fromAnnouncement(ann(6, "11/28(금) 동영상 강의 안내", "11월 28일 대면 수업은 없습니다. 12월 1일(월) 23:59까지 시청하세요"))!
        #expect(v.category == .VIDEO)
        #expect(v.kind == .DEADLINE)
        #expect(v.title == "[동영상] 자료구조 11/28(금) 동영상 강의 안내")
        #expect(v.start == kst("2026-12-01T23:59"))
        #expect(v.day == d("2026-11-28"))
        #expect(v.replacesClass)
        #expect(v.prefill.contains("동영상 강의 시청 기한을 내 일정에 넣고"))
        let noClue = ScheduleDrafts.fromAnnouncement(ann(7, "녹화 강의 업로드", "12월 1일(월) 23:59까지 시청"))!
        #expect(!noClue.replacesClass)
    }

    @Test func pendingTargetsAndAutoShow() {
        let now = kst("2026-11-24T12:00")
        let anns = [
            ann(1, "중간고사 안내", "11/28 19:00"),
            ann(2, "과제 3 안내", "금요일까지 제출"),
            ann(3, "수업 자료", "참고"),
            ann(4, "휴강 공지", "11월 28일 휴강", at: kst("2026-11-01T09:00")), // 14일 넘음
        ]
        let list = PendingNoticesLogic.add([], anns, now)
        #expect(list.map(\.id).sorted() == [1, 2])
        #expect(PendingNoticesLogic.targets(list, registered: [1], ignored: [], excludedCourse: { _ in false }, now: now).map(\.id) == [2])
        #expect(PendingNoticesLogic.targets(list, registered: [], ignored: [1, 2], excludedCourse: { _ in false }, now: now).isEmpty)
        #expect(PendingNoticesLogic.targets(list, registered: [], ignored: [], excludedCourse: { $0 == 10 }, now: now).isEmpty) // 숨긴·알림 끈 과목
        let t = PendingNoticesLogic.targets(list, registered: [], ignored: [], excludedCourse: { _ in false }, now: now)
        #expect(PendingNoticesLogic.shouldAutoShow(t, seen: [], enabled: true, updatePromptShowing: false))
        #expect(!PendingNoticesLogic.shouldAutoShow(t, seen: [1, 2], enabled: true, updatePromptShowing: false))
        #expect(!PendingNoticesLogic.shouldAutoShow(t, seen: [], enabled: true, updatePromptShowing: true))
        #expect(!PendingNoticesLogic.shouldAutoShow(t, seen: [], enabled: false, updatePromptShowing: false))
        #expect(PendingNoticesLogic.shouldAutoShow(t, seen: [1], enabled: true, updatePromptShowing: false)) // 새 공지가 하나 있으면
        #expect(PendingNoticesLogic.fromJson(PendingNoticesLogic.toJson(list)) == list)
    }

    @Test func pendingStoreIgnoreSeenAndRegistered() {
        let store = PendingNoticeStore(InMemoryStore())
        let now = kst("2026-11-24T12:00")
        store.record([ann(1, "중간고사 안내", "11/28 19:00"), ann(2, "과제 3 안내", "금요일까지 제출")], now)
        #expect(store.load().count == 2)
        store.ignore(1)
        store.markSeen([2])
        #expect(store.ignored() == [1])
        #expect(store.seen() == [2])
        #expect(store.targets(courses: CourseFilter(), registered: [], now: now).map(\.id) == [2])
        let mine = [MyDeadline(9, "x", now, sourceAnnouncementId: 2)]
        #expect(PendingNoticeStore.registeredIds(mine: mine, changes: []) == [2])
    }
}
