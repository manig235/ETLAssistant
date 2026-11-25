import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 새 강의자료: 기준선, 새 파일 감지, 숨김·잠김 제외, 파일 탭 막힘 건너뛰기, 실패 시 기준선 유지, 묶음 알림.
@Suite struct NewFilesTests {
    let now: Int64 = 1_800_000_000_000
    let ds = CourseRef(1, "자료구조")
    let algo = CourseRef(2, "알고리즘")
    func f(_ id: Int64, _ name: String? = nil) -> NewFiles.Outcome { .files([FileRef(id, name ?? "file\(id).pdf")]) }
    func files(_ ids: Int64...) -> NewFiles.Outcome { .files(ids.map { FileRef($0, "file\($0).pdf") }) }

    @Test func parseSkipsHiddenAndLocked() {
        let items = [
            JSONObject().put("id", 1).put("display_name", "a.pdf"),
            JSONObject().put("id", 2).put("display_name", "b.pdf").put("hidden_for_user", true),
            JSONObject().put("id", 3).put("display_name", "c.pdf").put("locked_for_user", true),
            JSONObject().put("id", 4).put("filename", "d.pptx"),
        ]
        #expect(NewFiles.parse(items) == [FileRef(1, "a.pdf"), FileRef(4, "d.pptx")])
    }

    @Test func firstSeenCourseIsBaselineOnly() {
        let (s, n) = NewFiles.apply(FilesWatchState(), now, [ds], [1: files(1, 2, 3)])
        #expect(n.isEmpty)
        #expect(s.courses[1]!.seen == [1, 2, 3])
        #expect(s.lastRunAt == now)
        // 빈 목록으로 기준선을 잡은 과목도 다음부터는 새 파일을 알린다
        let (s2, _) = NewFiles.apply(FilesWatchState(), now, [ds], [1: files()])
        let (_, n2) = NewFiles.apply(s2, now + 1, [ds], [1: files(9)])
        #expect(n2.count == 1)
    }

    @Test func newFilesGroupedPerCourse() {
        let base = NewFiles.apply(FilesWatchState(), now, [ds, algo], [1: files(1), 2: files(5)]).0
        let (s, n) = NewFiles.apply(base, now + 1, [ds, algo], [
            1: .files([FileRef(3, "3주차.pdf"), FileRef(2, "2주차.pdf"), FileRef(1, "file1.pdf")]),
            2: files(5),
        ])
        #expect(n.count == 1)
        let notice = n[0]
        #expect(notice.channel == .NEW_FILES)
        #expect(notice.title == "새 강의자료 2개 · 자료구조")
        #expect(notice.text == "3주차.pdf, 2주차.pdf")
        #expect(notice.url == "https://myetl.snu.ac.kr/courses/1/files")
        #expect(notice.prefill == "자료구조 새 강의자료 3주차.pdf, 2주차.pdf 받아줘")
        #expect(notice.prefillAction == "자료 받아줘") // 본문은 LearningX, 자료 받기는 채팅 버튼
        #expect(s.courses[1]!.seen == [3, 2, 1])
        #expect(NewFiles.apply(s, now + 2, [ds, algo], [1: files(3, 2, 1)]).1.isEmpty)
    }

    @Test func prefillSummarisesManyFiles() {
        #expect(NewFiles.prefill(ds, (Int64(1)...5).map { FileRef($0, "f\($0).pdf") }) == "자료구조 새 강의자료 f1.pdf 외 4개 받아줘")
        // 줄바꿈 같은 제어문자는 정리한다
        #expect(NewFiles.prefill(ds, [FileRef(1, "a\nb.pdf")]) == "자료구조 새 강의자료 a b.pdf 받아줘")
    }

    @Test func mutedCourseUpdatesBaselineWithoutAlert() {
        let base = NewFiles.apply(FilesWatchState(), now, [ds], [1: files(1)]).0
        let (s, n) = NewFiles.apply(base, now + 1, [ds], [1: files(2, 1)], muted: { $0 == 1 })
        #expect(n.isEmpty)
        #expect(s.courses[1]!.seen.contains(2))
    }

    @Test func failureKeepsBaselineAndRetries() {
        let base = NewFiles.apply(FilesWatchState(), now, [ds], [1: files(1)]).0
        let (s, n) = NewFiles.apply(base, now + NewFiles.INTERVAL_MS, [ds], [1: .failed])
        #expect(n.isEmpty)
        #expect(s.courses == base.courses)
        #expect(s.lastRunAt == base.lastRunAt) // 모두 실패면 다음 확인에서 다시
        #expect(NewFiles.due(s, now + NewFiles.INTERVAL_MS))
    }

    @Test func deniedCourseIsSkippedForSevenDays() {
        let (s, _) = NewFiles.apply(FilesWatchState(), now, [ds, algo], [1: .denied, 2: files(1)])
        #expect(s.courses[1]!.skipUntil == now + NewFiles.SKIP_MS)
        #expect(NewFiles.toCheck(s, [ds, algo], now + 1) == [algo])
        #expect(NewFiles.toCheck(s, [ds, algo], now + NewFiles.SKIP_MS) == [ds, algo])
        // 권한 문구 없는 401도 다른 과목이 성공했으면 파일 탭 막힘으로 본다
        let (s2, _) = NewFiles.apply(FilesWatchState(), now, [ds, algo], [1: .authRejected, 2: files(1)])
        #expect(s2.courses[1]!.skipUntil == now + NewFiles.SKIP_MS)
    }

    @Test func allAuthRejectedLooksLikeTokenProblem() {
        let (s, _) = NewFiles.apply(FilesWatchState(), now, [ds, algo], [1: .authRejected, 2: .authRejected])
        #expect(s.courses.isEmpty)
        #expect(s.lastRunAt == 0)
    }

    @Test func skippedCourseBecomesNewFilesAfterSkipWithoutLosingBaseline() {
        let base = NewFiles.apply(FilesWatchState(), now, [ds], [1: files(1)]).0
        let denied = NewFiles.apply(base, now + 1, [ds], [1: .denied]).0
        #expect(denied.courses[1]!.seen == [1])
        let (s, n) = NewFiles.apply(denied, now + NewFiles.SKIP_MS + 2, [ds], [1: files(2, 1)])
        #expect(n.count == 1)
        #expect(s.courses[1]!.skipUntil == 0)
    }

    @Test func seenIsCapped() {
        let prev = CourseFiles(Array(Int64(1)...Int64(NewFiles.SEEN_MAX)))
        let (rec, fresh) = NewFiles.diffCourse(prev, [FileRef(9999, "new.pdf")])
        #expect(fresh == [FileRef(9999, "new.pdf")])
        #expect(rec.seen.count == NewFiles.SEEN_MAX)
        #expect(rec.seen.first == 9999)
    }

    @Test func dueEveryFourHours() {
        #expect(NewFiles.due(FilesWatchState(), now))
        let s = FilesWatchState(lastRunAt: now)
        #expect(!NewFiles.due(s, now + 3600_000))
        #expect(!NewFiles.due(s, now + 3 * 3600_000))
        #expect(NewFiles.due(s, now + 4 * 3600_000 - 5 * 60_000)) // 주기 작업이 조금 일찍 돌아도
        #expect(NewFiles.due(s, now - 2 * 3600_000)) // 시계가 뒤로 갔을 때
    }

    @Test func droppedCoursesArePrunedAndJsonRoundTrips() {
        let base = NewFiles.apply(FilesWatchState(), now, [ds, algo], [1: files(1), 2: files(2)]).0
        let (s, _) = NewFiles.apply(base, now + 1, [ds], [1: files(1)])
        #expect(Set(s.courses.keys) == [1])
        #expect(FilesWatchState.fromJson(s.toJson()) == s)
        #expect(FilesWatchState.fromJson("깨진 값") == FilesWatchState())
    }

    @Test func singleFileNoticeHasNoBigText() {
        let base = NewFiles.apply(FilesWatchState(), now, [ds], [1: files()]).0
        let ns = NewFiles.apply(base, now + 1, [ds], [1: f(7, "강의노트.pdf")]).1
        #expect(ns.count == 1)
        #expect(ns[0].title == "새 강의자료 1개 · 자료구조")
        #expect(ns[0].bigText == nil)
        #expect(ns[0].prefill == "자료구조 새 강의자료 강의노트.pdf 받아줘")
    }

    // ---- 조회 루프(네트워크 없이 GET을 바꿔 끼운다) ----

    @Test func fetchLoopMapsErrorsAndStopsAfterNetworkFailures() async throws {
        let courses = [CourseRef(1, "a"), CourseRef(2, "b"), CourseRef(3, "c"), CourseRef(4, "d"), CourseRef(5, "e")]
        let r = try await NewFilesCheck.fetch({ path -> Any in
            if path.contains("/courses/1/") { return JSONArray([JSONObject().put("id", 9).put("display_name", "x.pdf")]) }
            if path.contains("/courses/2/") { throw EtlException("막힘", kind: "http-404") }
            if path.contains("/courses/3/") { throw EtlException("끊김", kind: "network") }
            if path.contains("/courses/4/") { throw EtlException("끊김", kind: "timeout") }
            Issue.record("연결 실패가 이어지면 더 요청하지 않는다")
            return JSONArray()
        }, courses)
        #expect(r[1] == .files([FileRef(9, "x.pdf")]))
        #expect(r[2] == .denied)
        #expect(r[3] == .failed)
        #expect(r[4] == .failed)
        #expect(r[5] == .failed)
        let auth = try await NewFilesCheck.fetch({ _ in throw EtlAuthException("거부") }, [CourseRef(1, "a")])
        #expect(auth[1] == .authRejected)
    }
}
