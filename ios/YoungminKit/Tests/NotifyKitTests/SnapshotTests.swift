import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct SnapshotFallbackTests {
    let now = instant("2026-11-22T03:00:00Z")
    func h(_ x: Double) -> Date { now.h(x) }

    func asg(_ cid: Int64, _ aid: Int64, _ due: Date?, course: String? = nil, online: Bool = true, submitted: Bool = false) -> AssignmentItem {
        AssignmentItem(cid, course ?? "강의\(cid)", aid, "과제\(aid)", due, online, nil, nil, submitted, false)
    }

    func cache(_ hoursAgo: Double, _ a: AssignmentItem...) -> CachedSnapshot { CachedSnapshot(now.h(-hoursAgo).epochMilli, a) }
    func snap(_ a: [AssignmentItem] = [], errors: [String] = []) -> Snapshot { Snapshot(now, a, [], errors) }

    @Test func connectionErrorsAreDetected() {
        #expect(SnapshotFallback.hasConnectionErrors(["강의1: eTL 연결 실패: /api/v1/courses/1/assignments (UnknownHostException)"]))
        #expect(SnapshotFallback.hasConnectionErrors(["공지: eTL 연결 실패: /api/v1/announcements (SocketTimeoutException)"]))
        #expect(!SnapshotFallback.hasConnectionErrors(["강의1: 사용자에게 이 동작을 수행할 권한이 없음"]))
        #expect(!SnapshotFallback.hasConnectionErrors([]))
    }

    @Test func totalFailureUsesCache() {
        let r = SnapshotFallback.apply(snap(errors: ["eTL 연결 실패"]), cache(2, asg(1, 11, h(30)), asg(2, 21, h(5))), now, totalFailure: true)
        #expect(r.snapshot.assignments.map(\.assignmentId) == [21, 11]) // 마감순
        #expect(r.cachedAt == now.h(-2))
        #expect(r.snapshot.errors == ["eTL 연결 실패"]) // 오류는 그대로 남긴다
    }

    @Test func totalFailureWithoutCacheKeepsEmpty() {
        let r = SnapshotFallback.apply(snap(errors: ["x"]), nil, now, totalFailure: true)
        #expect(r.snapshot.assignments.isEmpty)
        #expect(r.cachedAt == nil)
    }

    @Test func tooOldCacheIsIgnored() {
        let r = SnapshotFallback.apply(snap(), cache(73, asg(1, 11, h(30))), now, totalFailure: true)
        #expect(r.snapshot.assignments.isEmpty)
        #expect(r.cachedAt == nil)
    }

    @Test func partialFailureFillsOnlyFailedCourses() {
        let fresh = snap([asg(1, 11, h(10))], errors: ["강의2: eTL 연결 실패: /api/v1/courses/2/assignments (SocketTimeoutException)", "공지: 실패"])
        let c = cache(1, asg(1, 99, h(20)), asg(2, 21, h(5)), asg(3, 31, h(6)))
        let r = SnapshotFallback.apply(fresh, c, now, totalFailure: false)
        // 강의1은 새로 받은 것만(옛 99는 버림), 강의2는 저장본, 강의3은 실패하지 않았으니 넣지 않는다
        #expect(r.snapshot.assignments.map(\.assignmentId) == [21, 11])
        #expect(r.cachedAt == now.h(-1))
    }

    @Test func noErrorsNoFallback() {
        let fresh = snap([asg(1, 11, h(10))])
        let r = SnapshotFallback.apply(fresh, cache(1, asg(2, 21, h(5))), now, totalFailure: false)
        #expect(r.snapshot == fresh)
        #expect(r.cachedAt == nil)
    }

    @Test func announcementOnlyFailureIsNotFilled() {
        #expect(SnapshotFallback.failedCourses(["공지: eTL 연결 실패", "eTL 토큰 없음"]).isEmpty)
    }

    @Test func passedDueBecomesMissingAndMyDeadlinesAreSkipped() {
        let c = cache(5, asg(1, 11, h(-1)), asg(1, 12, h(-1), online: false), asg(1, 13, h(-1), submitted: true), asg(MyDeadlines.COURSE_ID, 1, h(2)))
        let r = SnapshotFallback.apply(snap(), c, now, totalFailure: true)
        #expect(r.snapshot.assignments.map(\.assignmentId) == [11, 12, 13])
        #expect(r.snapshot.assignments.map(\.missing) == [true, false, false])
    }

    @Test func ageLabels() {
        #expect(SnapshotFallback.ageLabel(now, now) == "방금")
        #expect(SnapshotFallback.ageLabel(now.secs(-25 * 60), now) == "25분 전")
        #expect(SnapshotFallback.ageLabel(now.secs(-(3 * 3600 + 100)), now) == "3시간 전")
        #expect(SnapshotFallback.ageLabel(now.secs(-50 * 3600), now) == "2일 전")
    }

    @Test func snapshotCacheKeepsFailedCoursesAndSkipsMine() {
        let store = SnapshotCache(InMemoryStore())
        store.save(snap([asg(1, 11, h(10)), asg(2, 21, h(5)), asg(MyDeadlines.COURSE_ID, 1, h(2))]))
        #expect(store.load()!.assignments.map(\.assignmentId) == [11, 21])
        // 강의2가 실패: 그 과목은 이전 값을 남긴다
        store.save(snap([asg(1, 12, h(10))], errors: ["강의2: 시간 초과"]))
        #expect(store.load()!.assignments.map(\.assignmentId) == [12, 21])
        // 과제가 하나도 없고 오류만 있으면 덮어쓰지 않는다
        store.save(snap(errors: ["eTL 연결 실패"]))
        #expect(store.load()!.assignments.map(\.assignmentId) == [12, 21])
    }
}

@Suite struct SnapshotFetcherTests {
    let now = instant("2026-11-19T03:00:00Z")

    func map(_ json: String) -> [AssignmentItem] { SnapshotFetcher.mapAssignments(7, "알고리즘", jsonList(json), now) }

    // ---- 과제 ----------------------------------------------------------------

    @Test func 미게시_과제는_제외하고_published_필드가_없으면_포함() {
        let r = map(#"[{"id":1,"name":"공개","published":true},{"id":2,"name":"미게시","published":false},{"id":3,"name":"필드없음"}]"#)
        #expect(r.map(\.assignmentId) == [1, 3])
        #expect(r[0].courseName == "알고리즘")
        #expect(r[0].courseId == 7)
    }

    @Test func due가_null이거나_비정상_문자열이면_null() {
        let r = map(#"[{"id":1,"name":"a","due_at":null},{"id":2,"name":"b","due_at":"내일"},{"id":3,"name":"c"},{"id":4,"name":"d","due_at":"2026-11-20T14:59:00Z"},{"id":5,"name":"e","due_at":"2026-11-20T23:59:00+09:00"}]"#)
        #expect(r[0].due == nil)
        #expect(r[1].due == nil)
        #expect(r[2].due == nil)
        #expect(r[3].due == instant("2026-11-20T14:59:00Z"))
        #expect(r[4].due == instant("2026-11-20T14:59:00Z"))
    }

    @Test func 오프라인과_온라인_구분() {
        let r = map(#"[{"id":1,"name":"종이","submission_types":["on_paper"]},{"id":2,"name":"없음","submission_types":["none"]},{"id":3,"name":"업로드","submission_types":["online_upload"]},{"id":4,"name":"혼합","submission_types":["on_paper","online_text_entry"]},{"id":5,"name":"필드없음"}]"#)
        #expect(r.map(\.online) == [false, false, true, true, false])
    }

    @Test func 제출_판정은_workflow_state나_submitted_at() {
        let r = map(#"[{"id":1,"name":"a","submission":{"workflow_state":"submitted"}},{"id":2,"name":"b","submission":{"workflow_state":"graded"}},{"id":3,"name":"c","submission":{"workflow_state":"pending_review"}},{"id":4,"name":"d","submission":{"workflow_state":"unsubmitted","submitted_at":"2026-11-18T01:00:00Z"}},{"id":5,"name":"e","submission":{"workflow_state":"unsubmitted","submitted_at":null}},{"id":6,"name":"f"}]"#)
        #expect(r.map(\.submitted) == [true, true, true, true, false, false])
    }

    @Test func missing_플래그와_마감_지난_온라인_과제() {
        let r = map(#"[{"id":1,"name":"플래그","submission_types":["online_upload"],"due_at":"2026-11-25T00:00:00Z","submission":{"missing":true}},{"id":2,"name":"지난온라인","submission_types":["online_upload"],"due_at":"2026-11-10T00:00:00Z"},{"id":3,"name":"지난오프라인","submission_types":["on_paper"],"due_at":"2026-11-10T00:00:00Z"},{"id":4,"name":"아직온라인","submission_types":["online_upload"],"due_at":"2026-11-25T00:00:00Z"},{"id":5,"name":"제출됨","submission_types":["online_upload"],"due_at":"2026-11-10T00:00:00Z","submission":{"workflow_state":"submitted","missing":true}}]"#)
        #expect(r.map(\.missing) == [true, true, false, false, false])
    }

    @Test func points가_없거나_null이면_null() {
        let r = map(#"[{"id":1,"name":"a","points_possible":10},{"id":2,"name":"b","points_possible":2.5},{"id":3,"name":"c","points_possible":null},{"id":4,"name":"d"}]"#)
        #expect(r[0].points == 10.0)
        #expect(r[1].points == 2.5)
        #expect(r[2].points == nil)
        #expect(r[3].points == nil)
    }

    @Test func html_url이_없으면_null() {
        let r = map(#"[{"id":1,"name":"a","html_url":"https://myetl.snu.ac.kr/courses/7/assignments/1"},{"id":2,"name":"b"},{"id":3,"name":"c","html_url":null}]"#)
        #expect(r[0].url == "https://myetl.snu.ac.kr/courses/7/assignments/1")
        #expect(r[1].url == nil)
        #expect(r[2].url == nil)
    }

    @Test func 이름이_없거나_비면_제목_없음() {
        let r = map(#"[{"id":1},{"id":2,"name":null},{"id":3,"name":""},{"id":4,"name":"   "}]"#)
        #expect(r.allSatisfy { $0.name == "(제목 없음)" })
    }

    @Test func 정렬은_마감_오름차순이고_마감_없음은_마지막이며_같은_마감은_강의_순서_유지() {
        func item(_ course: Int64, _ id: Int64, _ due: String?) -> AssignmentItem {
            AssignmentItem(course, "c\(course)", id, "n\(id)", due.map(instant), true, nil, nil, false, false)
        }
        let r = SnapshotFetcher.sortAssignments([
            item(1, 1, nil),
            item(1, 2, "2026-11-22T00:00:00Z"),
            item(2, 3, "2026-11-20T00:00:00Z"),
            item(2, 4, nil),
            item(3, 5, "2026-11-22T00:00:00Z"),
        ])
        #expect(r.map(\.assignmentId) == [3, 2, 5, 1, 4])
    }

    // ---- 공지 ----------------------------------------------------------------

    let names: [Int64: String] = [7: "알고리즘", 8: "운영체제"]

    func ann(_ json: String, _ days: Int = 2) -> [AnnouncementItem] { SnapshotFetcher.mapAnnouncements(jsonList(json), names, now, days) }

    @Test func context_code로_강의명을_찾고_모르는_ID는_숫자_문자열() {
        let r = ann(#"[{"id":1,"title":"a","context_code":"course_7","posted_at":"2026-11-19T02:00:00Z","html_url":"https://myetl.snu.ac.kr/courses/7/discussion_topics/1"},{"id":2,"title":"b","context_code":"course_999","posted_at":"2026-11-19T01:00:00Z"}]"#)
        #expect(r[0].courseId == 7)
        #expect(r[0].courseName == "알고리즘")
        #expect(r[0].url == "https://myetl.snu.ac.kr/courses/7/discussion_topics/1")
        #expect(r[1].courseId == 999)
        #expect(r[1].courseName == "999")
        #expect(r[1].url == nil)
    }

    @Test func url이_없으면_url_필드로_대체() {
        let r = ann(#"[{"id":1,"title":"a","context_code":"course_7","posted_at":"2026-11-19T02:00:00Z","url":"https://myetl.snu.ac.kr/x"}]"#)
        #expect(r[0].url == "https://myetl.snu.ac.kr/x")
    }

    @Test func posted_at_경계는_포함하고_그_이전은_제외() {
        // now - 2일 = 2026-11-17T03:00:00Z
        let r = ann(#"[{"id":1,"title":"경계","context_code":"course_7","posted_at":"2026-11-17T03:00:00Z"},{"id":2,"title":"1초전","context_code":"course_7","posted_at":"2026-11-17T02:59:59Z"},{"id":3,"title":"최근","context_code":"course_7","posted_at":"2026-11-19T00:00:00Z"}]"#)
        #expect(r.map(\.id) == [3, 1])
    }

    @Test func posted_at이_없으면_now로_간주하고_최신순_정렬() {
        let r = ann(#"[{"id":1,"title":"옛","context_code":"course_7","posted_at":"2026-11-18T00:00:00Z"},{"id":2,"title":"없음","context_code":"course_7"},{"id":3,"title":"깨짐","context_code":"course_8","posted_at":"어제"}]"#)
        #expect(r.count == 3)
        #expect(r.last!.id == 1)
        #expect(r.first { $0.id == 2 }!.postedAt == now)
        #expect(r.first { $0.id == 3 }!.postedAt == now)
    }

    @Test func 제목이_없으면_제목_없음_context_code가_없으면_ID_0() {
        let r = ann(#"[{"id":5,"posted_at":"2026-11-19T00:00:00Z"}]"#)
        #expect(r[0].title == "(제목 없음)")
        #expect(r[0].courseId == 0)
        #expect(r[0].courseName == "0")
    }

    @Test func html_본문은_텍스트로_바꾸고_600자로_제한() {
        let long = String(repeating: "가", count: 700)
        let r = ann(#"[{"id":1,"title":"a","context_code":"course_7","posted_at":"2026-11-19T00:00:00Z","message":"<p>안녕&nbsp;하세요 &amp; <b>반갑습니다</b></p><script>alert(1)</script><p>둘째 줄</p>"},{"id":2,"title":"b","context_code":"course_7","posted_at":"2026-11-18T00:00:00Z","message":"\#(long)"},{"id":3,"title":"c","context_code":"course_7","posted_at":"2026-11-17T12:00:00Z","message":null}]"#)
        #expect(r[0].text == "안녕 하세요 & 반갑습니다\n둘째 줄")
        #expect(r[1].text.utf16.count == 601)
        #expect(r[1].text.hasSuffix("…"))
        #expect(r[2].text == "")
        #expect(!r[0].text.contains("alert"))
    }

    // ---- KST 날짜 범위 -----------------------------------------------------------

    @Test func KST_날짜_범위_UTC_자정_근처() {
        // UTC 11-19 15:30 = KST 11-20 00:30
        let (s, e) = SnapshotFetcher.kstDateRange(instant("2026-11-19T15:30:00Z"), 2)
        #expect(s == "2026-11-18")
        #expect(e == "2026-11-21")
    }

    @Test func KST_날짜_범위_UTC_자정_직전은_같은_KST_날() {
        // UTC 11-19 14:59 = KST 11-19 23:59
        let (s, e) = SnapshotFetcher.kstDateRange(instant("2026-11-19T14:59:00Z"), 1)
        #expect(s == "2026-11-18")
        #expect(e == "2026-11-20")
    }

    @Test func KST_날짜_범위_월_경계() {
        let (s, e) = SnapshotFetcher.kstDateRange(instant("2026-12-01T00:00:00Z"), 2)
        #expect(s == "2026-11-29")
        #expect(e == "2026-12-02")
    }
}
