import Foundation
import ETLKit

/// 정기 브리핑 본문 생성(순수 함수, AI 미사용).
public enum BriefBuilder {
    private static let UPCOMING_DAYS = 7
    private static let UPCOMING_LINES = 8
    private static let MISSING_LINES = 5
    private static let ANN_LINES = 5
    private static let EMPTY = "급한 마감, 누락, 새 공지가 없어요"

    /// - todayClasses: 오늘 수업 줄(시간표). 비면 섹션을 넣지 않는다.
    /// - staleSince: eTL 조회가 실패해 받아 둔 과제 정보를 썼으면 그 저장 시각. 맨 끝에 "N시간 전 정보" 줄을 붙인다.
    /// - exams: 7일 안의 시험 줄([MyDeadlines.examLines]). 비면 섹션을 넣지 않는다.
    /// - todayEvents: 오늘 캘린더 일정 줄([CalendarReadLogic.briefLines]).
    public static func build(_ snapshot: Snapshot, _ now: Date, _ settings: NotifySettings, _ zone: TimeZone = .current, _ lookbackHours: Int = 24,
                             todayClasses: [String] = [], staleSince: Date? = nil, exams: [String] = [], todayEvents: [String] = []) -> Notice {
        let today = ZoneClock.localDate(now, zone)

        // 마감 예정: now <= due <= now+7일, 미제출(누락 표시된 과제는 아래 누락 섹션에서만 센다).
        let limit = now.addingTimeInterval(Double(UPCOMING_DAYS) * 86_400)
        let upcoming = stableSorted(snapshot.assignments.filter { a in
            guard let d = a.due else { return false }
            return !(d < now) && !(d > limit) && !a.submitted && !a.missing
        }) { x, y in
            if x.due != y.due { return lessNullsFirst(x.due, y.due) }
            if x.courseName != y.courseName { return x.courseName.kLess(y.courseName) }
            return x.assignmentId < y.assignmentId
        }
        // 누락: 최근에 놓친 것을 먼저 보인다(마감 없는 항목은 맨 뒤).
        let missing = stableSorted(snapshot.assignments.filter { $0.missing && !$0.submitted }) { x, y in
            let dx = x.due ?? .distantPast, dy = y.due ?? .distantPast
            if dx != dy { return dx > dy }
            return x.assignmentId < y.assignmentId
        }
        let since = now.addingTimeInterval(-Double(max(lookbackHours, 0)) * 3600)
        let recent = stableSorted(snapshot.announcements.filter { !($0.postedAt < since) }) { x, y in
            if x.postedAt != y.postedAt { return x.postedAt > y.postedAt }
            return x.id > y.id
        }

        let title = "eTL 브리핑 · \(NoticeText.dayLabel(now, zone))"
        let allEmpty = upcoming.isEmpty && missing.isEmpty && recent.isEmpty
        let counts = allEmpty ? EMPTY : "마감 임박 \(upcoming.count)건 · 누락 \(missing.count)건 · 새 공지 \(recent.count)건"
        // 오늘·내일 시험은 맨 앞에 알린다.
        let soonExams = exams.filter { $0.hasPrefix("• [오늘]") || $0.hasPrefix("• [내일]") }.count
        let head = [
            soonExams > 0 ? "시험 \(soonExams)개(오늘·내일)" : nil,
            todayClasses.isEmpty ? nil : "수업 \(todayClasses.count)개",
            todayEvents.isEmpty ? nil : "일정 \(todayEvents.count)개",
        ].compactMap { $0 }.joined(separator: " · ")
        let text = head.isEmpty ? counts : "\(head) · \(counts)"

        var sections: [String] = []
        if !exams.isEmpty { sections.append("■ 시험 (7일 이내)\n" + NoticeText.joinLimited(exams, UPCOMING_LINES)) }
        if !todayClasses.isEmpty { sections.append("■ 오늘 수업\n" + todayClasses.joined(separator: "\n")) }
        if !todayEvents.isEmpty { sections.append("■ 오늘 일정\n" + NoticeText.joinLimited(todayEvents, UPCOMING_LINES)) }
        if !upcoming.isEmpty {
            let lines = upcoming.map { a in NoticeText.dueLine(a, now, ZoneClock.localDate(a.due!, zone) == today ? "[오늘] " : "") }
            sections.append("■ 마감 예정 (7일 이내, 미제출)\n" + NoticeText.joinLimited(lines, UPCOMING_LINES))
        }
        if !missing.isEmpty {
            let lines = missing.map { "• \(NoticeText.dateLabel($0.due)) · \(NoticeText.course($0.courseName)) — \(NoticeText.name($0.name))" }
            sections.append("■ 누락 (마감 지남, 미제출)\n" + NoticeText.joinLimited(lines, MISSING_LINES))
        }
        if !recent.isEmpty {
            sections.append("■ 새 공지 (최근 \(lookbackHours)시간)\n" + NoticeText.joinLimited(recent.map { NoticeText.annLine($0) }, ANN_LINES))
        }
        if allEmpty && exams.isEmpty && todayEvents.isEmpty { sections.append(EMPTY) }
        if let staleSince { sections.append(SnapshotFallback.staleLine(staleSince, now)) }
        else if !snapshot.errors.isEmpty { sections.append("※ 일부 항목을 조회하지 못했어요") }

        return Notice(.BRIEF, NoticeText.cut(title, NoticeText.TITLE_MAX), text, bigText: sections.joined(separator: "\n\n"), url: nil,
                      tag: "brief", firedKeys: [])
    }
}
