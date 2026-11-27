import Foundation
import ETLKit

/// 7번째 추천 질문 "{과목} {중간고사|기말고사|시험} 대비 모의고사 PDF로 만들어줘"의 시험 이름과 과목(Kotlin `onboarding/MockExam.kt`, 순수, KST).
///
/// 1) 내 일정에 넣은 시험이 `lookaheadDays`일 안에 있으면 가장 가까운 시험을 따른다: 과목은 그 시험의 과목,
///    이름은 제목의 "중간"/"기말" → 시험 날짜가 학기 중간·기말고사 기간(7~9주차 / 14~17주차)이면 그 이름 → 그 밖(퀴즈 등)은 "시험".
/// 2) 없으면 오늘의 학기 주차: 1~8주차 "중간고사", 9~16주차 "기말고사", 방학·계절학기 "시험".
/// 학기 시작일은 앱 데이터에 없어 서울대 학사력대로 1학기 3월 2일, 2학기 9월 1일이 든 주의 월요일을 1주차로 본다(그날이 주말이면 다음 주).
public enum MockExam {
    public static let midterm = "중간고사"
    public static let finalExam = "기말고사"
    public static let generic = "시험"

    /// 내 일정의 시험을 이만큼 앞까지만 본다(6주).
    public static let lookaheadDays = 42

    /// 내 일정의 시험 하나(`MyDeadline.kind == .EXAM`).
    public struct Exam: Hashable, Sendable {
        public var title: String
        public var course: String
        public var start: Date
        public var end: Date?
        public init(title: String, course: String, start: Date, end: Date? = nil) {
            self.title = title
            self.course = course
            self.start = start
            self.end = end
        }
    }

    /// `term`: 시험 이름. `course`: 시험에 적힌 과목(없으면 nil). `title`: 시험 제목(과목이 비었을 때 과목명을 찾는 데 쓴다).
    public struct Hint: Hashable, Sendable {
        public var term: String
        public var course: String?
        public var title: String?
        public init(_ term: String, _ course: String? = nil, _ title: String? = nil) {
            self.term = term
            self.course = course
            self.title = title
        }
    }

    /// 그 학기(`semester` 1 또는 2) 1주차 월요일.
    public static func semesterStart(_ year: Int, _ semester: Int) -> DayDate {
        let anchor = semester == 1 ? DayDate.of(year, 3, 2) : DayDate.of(year, 9, 1)
        let dow = anchor.dayOfWeek.rawValue // 1 = 월 … 7 = 일
        if dow >= 6 { return anchor.plusDays(8 - dow) } // 주말이면 다음 월요일
        return anchor.minusDays(dow - 1)
    }

    /// 정규 학기 주차(1부터). 학기 밖이면 nil. 1~16주차까지만 학기로 본다.
    public static func semesterWeek(_ date: DayDate) -> Int? { weekIn(date, 16) }

    private static func weekIn(_ date: DayDate, _ weeks: Int) -> Int? {
        for semester in 1...2 {
            let days = semesterStart(date.year, semester).daysUntil(date)
            if days >= 0 && days < weeks * 7 { return days / 7 + 1 }
        }
        return nil
    }

    /// 오늘 기준 시험 이름: 1~8주차 중간고사, 9~16주차 기말고사, 방학·계절학기는 "시험".
    public static func termByWeek(_ today: DayDate) -> String {
        guard let week = semesterWeek(today) else { return generic }
        return week <= 8 ? midterm : finalExam
    }

    /// 시험 날짜가 중간고사 기간(7~9주차)이나 기말고사 기간(14~17주차, 밀린 기말 포함)이면 그 이름, 아니면 "시험".
    public static func termOfExamDate(_ date: DayDate) -> String {
        switch weekIn(date, 17) {
        case .some(7...9): return midterm
        case .some(14...17): return finalExam
        default: return generic
        }
    }

    private static func termInTitle(_ title: String) -> String? {
        if title.contains("중간") { return midterm }
        if title.contains("기말") { return finalExam }
        return nil
    }

    /// 추천 질문에 쓸 시험 이름과 과목. 아직 끝나지 않았고 `lookaheadDays`일 안에 시작하는 가장 가까운 시험을 따르고, 없으면 `now`의 학기 주차로 정한다.
    public static func hint(_ exams: [Exam], now: Date) -> Hint {
        let limit = now.addingTimeInterval(TimeInterval(lookaheadDays) * 86_400)
        let upcoming = exams.filter { ($0.end ?? $0.start) > now && $0.start <= limit }
        guard let next = upcoming.min(by: { $0.start < $1.start }) else { return Hint(termByWeek(DayDate(now))) }
        let term = termInTitle(next.title) ?? termOfExamDate(DayDate(next.start))
        let course = clean(next.course)
        let title = next.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return Hint(term, course.isEmpty ? nil : course, title.isEmpty ? nil : title)
    }

    /// 시험 제목에 들어 있는 과목명(가장 긴 것). 과목 칸을 비워 둔 시험("자료구조 중간고사")용.
    public static func courseInTitle(_ title: String?, _ names: [String]) -> String? {
        guard let title else { return nil }
        var best: String?
        for name in names where !name.isEmpty && title.contains(name) && name.count > (best?.count ?? 0) { best = name }
        return best
    }

    public static func question(_ term: String, _ course: String?) -> String {
        let c = course?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return (c.isEmpty ? term : "\(c) \(term)") + " 대비 모의고사 PDF로 만들어줘"
    }

    private static func clean(_ name: String) -> String {
        TimetableLogic.shortName(name)
            .replacingOccurrences(of: "[\\p{Cc}\\p{Cf}\\s]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
}
