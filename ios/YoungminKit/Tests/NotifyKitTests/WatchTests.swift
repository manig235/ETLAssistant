import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 새 과제·마감 변경·성적 규칙과 수업 알림.
@Suite struct WatchTests {
    let now = instant("2026-11-19T03:00:00Z") // 11/19(목) 12:00 KST
    func h(_ x: Double) -> Date { now.h(x) }
    func item(_ id: Int64, _ due: Date?, score: Double? = nil, cid: Int64 = 1, name: String? = nil) -> AssignmentItem {
        AssignmentItem(cid, "자료구조", id, name ?? "과제\(id)", due, true, 10.0, "https://myetl.snu.ac.kr/courses/\(cid)/assignments/\(id)", score != nil, false, score)
    }
    func snap(_ a: AssignmentItem..., errors: [String] = []) -> Snapshot { Snapshot(now, a, [], errors) }
    func snap(list a: [AssignmentItem]) -> Snapshot { Snapshot(now, a, []) }

    @Test func firstRunIsSilentBaseline() {
        let (s, n) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5))), now)
        #expect(s.initialized)
        #expect(n.isEmpty)
        #expect(s.records.count == 1)
    }

    @Test func newDueChangeAndGrade() {
        let (s0, _) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5)), item(2, h(30))), now)
        let (s1, n) = AssignmentWatch.diff(s0, snap(item(1, h(29)), item(2, h(30), score: 9.5), item(3, h(48))), now)
        #expect(n.map(\.title) == ["마감 변경 · 자료구조", "새 과제 · 자료구조", "성적 · 자료구조"])
        #expect(n[2].text.contains("9.5 / 10"))
        #expect(n.allSatisfy { $0.channel == .CHANGE })
        // 같은 상태로 다시 확인하면 아무것도 없다
        #expect(AssignmentWatch.diff(s1, snap(item(1, h(29)), item(2, h(30), score: 9.5), item(3, h(48))), now).1.isEmpty)
    }

    @Test func gradeRestoresMissingBlankOrForeignUrlAndKeepsValidDeepLinks() {
        let (before, _) = AssignmentWatch.diff(WatchState(), snap(item(7, h(5))), now)
        for url: String? in [nil, "", "https://other.example/grades", "http://myetl.snu.ac.kr/x"] {
            var g = item(7, h(5), score: 9.0)
            g.url = url
            let ns = AssignmentWatch.diff(before, snap(g), now).1
            #expect(ns.count == 1 && ns[0].url == "https://myetl.snu.ac.kr/courses/1/assignments/7")
        }
        let original = "https://myetl.snu.ac.kr/courses/1/assignments/7?module_item_id=3"
        var g = item(7, h(5), score: 9.0)
        g.url = original
        #expect(AssignmentWatch.diff(before, snap(g), now).1.map(\.url) == [original])
    }

    @Test func gradeSummaryLinksToTheSharedCourseOrEtlHome() {
        let beforeItems = (Int64(1)...4).map { item($0, h(5)) }
        let (before, _) = AssignmentWatch.diff(WatchState(), snap(list: beforeItems), now)
        let grades = beforeItems.map { a -> AssignmentItem in var x = a; x.score = 8.0; x.url = nil; return x }
        let ns = AssignmentWatch.diff(before, snap(list: grades), now).1
        #expect(ns.count == 1 && ns[0].url == "https://myetl.snu.ac.kr/courses/1")
        let (_, across) = AssignmentWatch.diff(WatchState(true), snap(list: (Int64(1)...4).map { item($0, h(5), cid: $0) }), now)
        #expect(across.count == 1 && across[0].url == "https://myetl.snu.ac.kr/")
    }

    @Test func manyChangesAreSummarisedAndMyDeadlinesIgnored() {
        let (s0, _) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5))), now)
        let mine = item(99, h(3), cid: MyDeadlines.COURSE_ID)
        let (_, n) = AssignmentWatch.diff(s0, snap(item(1, h(5)), item(2, h(6)), item(3, h(7)), item(4, h(8)), item(5, h(9)), mine), now)
        #expect(n.count == 1)
        #expect(n[0].title == "eTL 변경 4건")
        #expect(n[0].bigText!.contains("[새 과제]"))
    }

    @Test func failedFetchKeepsState() {
        let (s0, _) = AssignmentWatch.diff(WatchState(), snap(item(1, h(5))), now)
        let (s1, n) = AssignmentWatch.diff(s0, snap(errors: ["자료구조: 실패"]), now)
        #expect(s0 == s1)
        #expect(n.isEmpty)
        // 일부 과목만 실패: 못 본 과제 기록은 남아 나중에 "새 과제"가 되지 않는다
        let (s2, _) = AssignmentWatch.diff(s0, snap(item(7, h(9), cid: 2)), now)
        #expect(s2.records["1:1"] != nil)
    }

    @Test func stateJsonRoundTrip() {
        let s = WatchState(true, ["1:1": WatchRecord("a", "b", 5, nil), "1:2": WatchRecord("c", "d", nil, 3.5)])
        #expect(WatchState.fromJson(s.toJson()) == s)
    }

    @Test func classNotices() {
        let day = TimetableLogic.Day(DayDate.of(2026, 11, 19), "목", [
            TimetableLogic.ClassRow("12:05", "13:20", "자료구조", 1, "301-118", "정규"),
            TimetableLogic.ClassRow("15:00", "16:15", "회로이론", 2, nil, "보강", "301동"),
        ], [])
        let n = ClassNotices.plan(day, now, seoul, 10, [])
        #expect(n.count == 1)
        #expect(n[0].title == "5분 뒤 수업 · 자료구조")
        #expect(n[0].text.contains("301-118"))
        #expect(ClassNotices.plan(day, now, seoul, 10, Set(n[0].firedKeys)).isEmpty)
        let next = ClassNotices.nextAt([day], now, seoul, 10, Set(n[0].firedKeys))
        #expect(next == instant("2026-11-19T05:50:00Z")) // 15:00 − 10분 KST
        #expect(ClassNotices.nextAt([day], h(10), seoul, 10, []) == nil)
    }

    @Test func historyKeepsRecent() {
        let day: Int64 = 24 * 3600 * 1000
        let old = HistoryEntry(0, "deadline", "a", "b", nil, nil)
        let list = NotifyHistoryLogic.add([old], HistoryEntry(100 * day, "class", "c", "d", nil, nil), 100 * day)
        #expect(list.map(\.title) == ["c"])
        #expect(NotifyHistoryLogic.fromJson(NotifyHistoryLogic.toJson(list)) == list)
    }
}
