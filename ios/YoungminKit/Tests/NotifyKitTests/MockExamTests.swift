import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 추천 질문 7번 "모의고사"의 시험 이름·과목 판정(Kotlin `MockExamTest`).
@Suite struct MockExamTests {
    func exam(_ title: String, _ start: String, _ end: String? = nil, course: String = "") -> MockExam.Exam {
        MockExam.Exam(title: title, course: course, start: kst(start), end: end.map { kst($0) })
    }

    @Test func semesterStartsOnMondayOfTheWeekWithMarch2OrSeptember1() {
        #expect(MockExam.semesterStart(2026, 1) == d("2026-03-02")) // 월요일
        #expect(MockExam.semesterStart(2026, 2) == d("2026-08-31")) // 9/1 화요일 → 그 주 월요일
        #expect(MockExam.semesterStart(2025, 1) == d("2025-03-03")) // 3/2 일요일 → 다음 월요일
        #expect(MockExam.semesterStart(2024, 2) == d("2024-09-02")) // 9/1 일요일 → 다음 월요일
        #expect(MockExam.semesterStart(2027, 2) == d("2027-08-30")) // 9/1 수요일
    }

    @Test func termByWeekBoundariesFirstSemester() {
        #expect(MockExam.termByWeek(d("2026-03-01")) == MockExam.generic)
        #expect(MockExam.termByWeek(d("2026-03-02")) == MockExam.midterm)
        #expect(MockExam.termByWeek(d("2026-04-26")) == MockExam.midterm)
        #expect(MockExam.termByWeek(d("2026-04-27")) == MockExam.finalExam)
        #expect(MockExam.termByWeek(d("2026-06-21")) == MockExam.finalExam)
        #expect(MockExam.termByWeek(d("2026-06-22")) == MockExam.generic)
        #expect(MockExam.termByWeek(d("2026-07-15")) == MockExam.generic)
    }

    @Test func termByWeekBoundariesSecondSemesterAndWinter() {
        #expect(MockExam.termByWeek(d("2026-08-30")) == MockExam.generic)
        #expect(MockExam.termByWeek(d("2026-08-31")) == MockExam.midterm)
        #expect(MockExam.termByWeek(d("2026-10-25")) == MockExam.midterm)
        #expect(MockExam.termByWeek(d("2026-10-26")) == MockExam.finalExam)
        #expect(MockExam.termByWeek(d("2026-11-27")) == MockExam.finalExam)
        #expect(MockExam.termByWeek(d("2026-12-20")) == MockExam.finalExam)
        #expect(MockExam.termByWeek(d("2026-12-21")) == MockExam.generic)
        #expect(MockExam.termByWeek(d("2027-01-20")) == MockExam.generic)
        #expect(MockExam.termByWeek(d("2027-02-28")) == MockExam.generic)
    }

    @Test func hintWithoutExamUsesTodayInKst() {
        #expect(MockExam.hint([], now: kst("2026-04-26T23:59")) == MockExam.Hint(MockExam.midterm))
        #expect(MockExam.hint([], now: kst("2026-04-27T00:00")) == MockExam.Hint(MockExam.finalExam))
        #expect(MockExam.hint([], now: kst("2026-07-01T12:00")) == MockExam.Hint(MockExam.generic))
    }

    @Test func nearestUpcomingExamWins() {
        let now = kst("2026-11-27T12:00")
        let exams = [
            exam("선형대수 기말고사", "2026-12-15T10:00", course: "2026-2 선형대수학 (001)"),
            exam("자료구조 중간 재시험", "2026-12-03T19:00", course: "자료구조"),
            exam("끝난 시험", "2026-11-27T09:00", "2026-11-27T11:00", course: "끝난 과목"),
            exam("6주 넘게 남은 시험", "2027-01-09T10:00", course: "먼 과목"),
        ]
        #expect(MockExam.hint(exams, now: now) == MockExam.Hint(MockExam.midterm, "자료구조", "자료구조 중간 재시험"))
        let ongoing = [exam("기말", "2026-11-27T11:00", "2026-11-27T13:00", course: "2026-2 회로이론 (002)")]
        #expect(MockExam.hint(ongoing, now: now) == MockExam.Hint(MockExam.finalExam, "회로이론", "기말"))
        #expect(MockExam.hint([exam("기말", "2026-11-27T11:00")], now: now) == MockExam.Hint(MockExam.finalExam))
        #expect(MockExam.hint([exam("시험", "2027-01-08T12:00")], now: now) == MockExam.Hint(MockExam.generic, nil, "시험"))
        #expect(MockExam.hint([exam("시험", "2027-01-08T12:01")], now: now) == MockExam.Hint(MockExam.finalExam))
    }

    @Test func examTitleWithoutTermUsesExamWeek() {
        let now = kst("2026-03-20T09:00")
        #expect(MockExam.hint([exam("물리 시험", "2026-04-21T10:00")], now: now).term == MockExam.midterm)
        #expect(MockExam.hint([exam("물리 퀴즈", "2026-03-24T10:00")], now: now).term == MockExam.generic)
        let late = kst("2026-06-01T09:00")
        #expect(MockExam.hint([exam("물리 시험", "2026-06-09T10:00")], now: late).term == MockExam.finalExam)
        #expect(MockExam.hint([exam("물리 시험", "2026-06-23T10:00")], now: late).term == MockExam.finalExam)
        #expect(MockExam.hint([exam("물리 시험", "2026-06-29T10:00")], now: late).term == MockExam.generic)
        let summer = kst("2026-07-01T09:00")
        #expect(MockExam.hint([exam("계절 시험", "2026-07-20T10:00")], now: summer).term == MockExam.generic)
        #expect(MockExam.hint([exam("계절 기말", "2026-07-20T10:00")], now: summer).term == MockExam.finalExam)
    }

    @Test func questionWordingAndCourseInTitle() {
        #expect(MockExam.question(MockExam.midterm, "자료구조") == "자료구조 중간고사 대비 모의고사 PDF로 만들어줘")
        #expect(MockExam.question(MockExam.finalExam, nil) == "기말고사 대비 모의고사 PDF로 만들어줘")
        #expect(MockExam.question(MockExam.generic, " ") == "시험 대비 모의고사 PDF로 만들어줘")
        #expect(MockExam.courseInTitle(nil, ["자료구조"]) == nil)
        #expect(MockExam.courseInTitle("선형대수학 중간", ["대수", "선형대수학", ""]) == "선형대수학")
    }
}
