import ETLKit
import Foundation
import NotifyKit

/// 첫 실행 안내의 단계(Kotlin `Step`).
enum OnboardingStep: Int, CaseIterable, Hashable {
    case welcome, token, login, notify, done
}

/// 안내 흐름 규칙(Kotlin `OnboardingLogic`). 순수 로직이라 나중에 Kit으로 옮길 수 있다.
enum OnboardingLogic {
    static let steps = OnboardingStep.allCases

    /// 진행률(0..1). 첫 단계도 조금은 차 있게 (인덱스 + 1) / 전체.
    static func progress(_ step: OnboardingStep) -> Double { Double(step.rawValue + 1) / Double(steps.count) }

    static func next(_ step: OnboardingStep) -> OnboardingStep { OnboardingStep(rawValue: step.rawValue + 1) ?? .done }

    static func previous(_ step: OnboardingStep) -> OnboardingStep { OnboardingStep(rawValue: step.rawValue - 1) ?? .welcome }

    /// eTL 토큰으로 쓸 수 있는 모양인가(공백 없는 ASCII 8자 이상).
    static func isPlausibleEtlToken(_ raw: String) -> Bool {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.unicodeScalars.count >= 8 && t.unicodeScalars.allSatisfy { (0x21...0x7E).contains($0.value) }
    }

    /// 이미 끝냈거나 eTL 토큰을 저장한 기존 사용자면 건너뛴다.
    static func shouldSkip(completed: Bool, hasEtlToken: Bool) -> Bool { completed || hasEtlToken }

    static func canContinue(_ step: OnboardingStep, tokenSaved: Bool) -> Bool { step == .token ? tokenSaved : true }

    /// "홍길동(2021-12345)" → "홍길동". 비면 nil.
    static func displayName(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let name = (raw.split(separator: "(", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? raw)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty || name == "null" ? nil : name
    }

    /// 브리핑 시각 후보(하루 중 분).
    static let briefTimeChoices = [7 * 60, 8 * 60, 9 * 60, 20 * 60]

    static let suggestions = [
        "이번 주 마감 과제 알려줘",
        "새 공지 중 중요한 내용만 정리해줘",
        "오늘 수업 시간과 강의실 알려줘",
        "최근 강의자료 핵심 내용 요약해줘",
        "최근 강의자료 파일 받아줘",
        "최근 과제가 제대로 제출됐는지 확인해줘",
    ]

    /// 확정한 여섯 질문 + 7번째 모의고사 질문(과목 없음, Kotlin `defaultSuggestions`). 과목을 하나도 모를 때와 처음 그릴 때 쓴다.
    static func defaultSuggestions(_ hint: MockExam.Hint = MockExam.Hint(MockExam.termByWeek(DayDate.today()))) -> [String] {
        suggestions + [MockExam.question(hint.term, hint.course)]
    }

    /// 추천 질문 후보 과목. 콘텐츠 값 nil = 미확인, false = 확인했는데 없음.
    struct CourseOption: Hashable {
        var course: TimetableLogic.CourseMeetings
        var hasMaterials: Bool?
        var hasRecentAssignment: Bool?
    }

    static func eligibleCourse(_ course: TimetableLogic.CourseMeetings) -> Bool {
        course.courseId > 0 && (course.credits.map { (3.0...4.0).contains($0) } ?? false)
    }

    /// 시간표를 먼저, 그다음 `extra`(ID, 이름)에서 없던 과목을 더한다. 직접 추가한 수업(ID ≤ 0), 이름 없는 과목, 숨긴 과목은 뺀다.
    static func knownCourses(_ timetable: [TimetableLogic.CourseMeetings], _ extra: [(Int64, String)],
                             hidden: (Int64) -> Bool = { _ in false }) -> [TimetableLogic.CourseMeetings] {
        var order: [Int64] = []
        var map: [Int64: TimetableLogic.CourseMeetings] = [:]
        for c in timetable where c.courseId > 0 && map[c.courseId] == nil { map[c.courseId] = c; order.append(c.courseId) }
        for (id, name) in extra where id > 0 && map[id] == nil {
            map[id] = TimetableLogic.CourseMeetings(id, name, [])
            order.append(id)
        }
        return order.compactMap { map[$0] }.filter { !hidden($0.courseId) && !cleanName($0.name).isEmpty }
    }

    static func cleanName(_ name: String) -> String {
        let short = TimetableLogic.shortName(name)
        let collapsed = short.replacingOccurrences(of: "[\\p{Cc}\\p{Cf}\\s]+", with: " ", options: .regularExpression)
        return collapsed.trimmingCharacters(in: .whitespaces)
    }

    /// 과목 질문 3개(자료 요약, 자료 받기, 과제 제출 확인)에 과목명을 붙인다(Kotlin `personalizedSuggestions`).
    /// 7번째 모의고사 질문은 `mockExam`의 시험 과목(비었으면 제목에 든 이번 학기 과목명)을, 없으면 자료 질문의 가장 좋은 순위 과목 중 앞의 세 질문과 겹치지 않는 과목을 쓴다.
    static func personalizedSuggestions<G: RandomNumberGenerator>(
        _ courses: [CourseOption], random: inout G,
        mockExam: MockExam.Hint = MockExam.Hint(MockExam.termByWeek(DayDate.today()))
    ) -> [String] {
        let named = courses.filter { $0.course.courseId > 0 }
            .map { (cleanName($0.course.name), $0) }
            .filter { !$0.0.isEmpty }
        func rank(_ option: CourseOption, _ has: Bool?) -> Int {
            let eligible = eligibleCourse(option.course)
            switch has {
            case true?: return eligible ? 0 : 1
            case nil: return eligible ? 2 : 4
            case false?: return eligible ? 3 : 5
            }
        }
        func candidates(_ content: (CourseOption) -> Bool?) -> [(String, Int)] {
            var best: [String: Int] = [:]
            for (name, option) in named {
                let r = rank(option, content(option))
                best[name] = min(best[name] ?? Int.max, r)
            }
            let confirmed = best.filter { $0.value <= 1 }
            let pool = confirmed.isEmpty ? best : confirmed
            let shuffled = pool.keys.sorted().shuffled(using: &random)
            return shuffled.enumerated()
                .sorted { a, b in pool[a.element]! != pool[b.element]! ? pool[a.element]! < pool[b.element]! : a.offset < b.offset }
                .map { ($0.element, pool[$0.element]!) }
        }
        func pick(_ list: [String], _ avoid: [String?]) -> String? {
            list.first { !avoid.contains($0) } ?? list.first { $0 != avoid.first ?? nil } ?? list.first
        }
        let materials = candidates { $0.hasMaterials }
        let assignments = candidates { $0.hasRecentAssignment }
        let materialConfirmed = Set(materials.filter { $0.1 <= 1 }.map(\.0))
        var assignment: String?
        if let (top, topRank) = assignments.first {
            assignment = assignments.first { $0.1 == topRank && !materialConfirmed.contains($0.0) }?.0 ?? top
        }
        let materialNames = materials.map(\.0)
        let summary = pick(materialNames, [assignment])
        let download = pick(materialNames, [summary, assignment])
        let picked = [summary, download, assignment]
        // 모의고사는 자료 질문의 가장 좋은 순위 안에서만 고른다. 겹침을 피하려고 1학점 과목으로 내려가지 않는다.
        let best = materials.filter { $0.1 == materials.first?.1 }.map(\.0)
        let mockCourse = mockExam.course
            ?? MockExam.courseInTitle(mockExam.title, named.map(\.0))
            ?? best.first { !picked.contains($0) } ?? best.first { $0 != summary } ?? best.first
        return suggestions.enumerated().map { index, question in
            let course = index >= 3 ? picked[index - 3] : nil
            return course.map { "\($0) \(question)" } ?? question
        } + [MockExam.question(mockExam.term, mockCourse)]
    }
}

/// 화면 수명 동안 같은 과목을 고르기 위한 시드 고정 난수(SplitMix64).
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// 첫 실행 완료와 빈 채팅이 같이 쓰는 추천 질문(Kotlin `rememberSuggestionQuestions`).
/// 폰에 받아 둔 시간표로 먼저 과목명을 붙이고, 토큰이 있으면 시간표를 새로 받아 다시 고른다(실패하면 10·30·60초 뒤 재시도).
/// iOS판은 과목별 콘텐츠 확인(SuggestionContentLookup)을 아직 하지 않는다 — 모든 과목을 "미확인"으로 둔다.
@MainActor
@Observable
final class SuggestionQuestions {
    static let shared = SuggestionQuestions()

    private(set) var questions: [String] = OnboardingLogic.defaultSuggestions()
    @ObservationIgnored private let seed = UInt64.random(in: 1...UInt64.max)
    @ObservationIgnored private var settled = false
    @ObservationIgnored private var running = false

    func refresh() async {
        if settled || running { return }
        running = true
        defer { running = false }
        if let offline = await Self.options(online: false) { apply(offline.options, offline.mockExam) }
        let delays: [UInt64] = [10, 30, 60]
        for attempt in 0...delays.count {
            let result = await Self.options(online: true)
            if let result, !result.options.isEmpty { apply(result.options, result.mockExam) }
            if result?.complete == true { settled = true; return }
            guard attempt < delays.count else { return }
            try? await Task.sleep(nanoseconds: delays[attempt] * 1_000_000_000)
            if Task.isCancelled { return }
        }
    }

    private func apply(_ options: [OnboardingLogic.CourseOption], _ mockExam: MockExam.Hint) {
        var g = SeededGenerator(seed: seed)
        questions = OnboardingLogic.personalizedSuggestions(options, random: &g, mockExam: mockExam)
    }

    /// `mockExam`: 모의고사 질문의 시험 이름·과목(내 일정의 시험 → 학기 주차).
    private struct Result { let options: [OnboardingLogic.CourseOption]; let complete: Bool; let mockExam: MockExam.Hint }

    /// 내 일정의 시험(완료 표시하지 않은 것)으로 모의고사 질문의 시험 이름·과목을 정한다. 네트워크 없음.
    nonisolated private static func mockExamHint(now: Date) -> MockExam.Hint {
        let exams = SchedStores.mine.load()
            .filter { $0.kind == .EXAM && !$0.done }
            .map { MockExam.Exam(title: $0.title, course: $0.course, start: $0.due, end: $0.end) }
        return MockExam.hint(exams, now: now)
    }

    nonisolated private static let STALE_MS: Int64 = 60 * 24 * 3600 * 1000

    nonisolated private static func options(online: Bool) async -> Result? {
        let store = TimetableStore()
        let cached = store.loadCatalog()
        let now = Timetable.nowMs()
        let age = cached.map { now - $0.fetchedAt }
        var timetable: [TimetableLogic.CourseMeetings] = []
        if let age, age >= 0, age < STALE_MS { timetable = (cached?.courses ?? []).filter { $0.courseId > 0 } }
        var listed = false
        if online, let token = AppStores.tokens.load(), !token.isEmpty, let client = try? EtlClient(token) {
            if let w = try? await Timetable.weekly(client, store, nowMs: now) {
                timetable = w.courses.filter { $0.courseId > 0 }
                listed = true
            }
        }
        let mockExam = mockExamHint(now: Date())
        let courses = OnboardingLogic.knownCourses(timetable, [])
        if courses.isEmpty { return Result(options: [], complete: false, mockExam: mockExam) }
        return Result(options: courses.map { OnboardingLogic.CourseOption(course: $0) }, complete: listed, mockExam: mockExam)
    }
}
