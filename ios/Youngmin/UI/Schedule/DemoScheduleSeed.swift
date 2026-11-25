import ETLKit
import NotifyKit
import SwiftUI

/// DEBUG 전용 시연 데이터(내 일정·시간표·알림 기록·과목별 알림). `UserDefaults` `debug.demo`가 켜져 있을 때
/// 각 화면을 처음 열 때 한 번 심는다(부분마다 `debug.demo.seeded.<부분>` 표시). 시연 중에는 eTL에 요청하지 않는다.
/// 릴리스 빌드에서는 아무것도 하지 않는다.
enum DemoScheduleSeed {
    static var isActive: Bool { DemoSeed.isActive }

    enum Screen { case schedule, timetable, history, courses }

    #if DEBUG
    private enum Part: String { case snapshot, mine, timetable, change, history }

    static func seed(_ screen: Screen) {
        guard isActive else { return }
        let parts: [Part] = switch screen {
        case .schedule: [.snapshot, .mine]
        case .timetable: [.timetable, .change]
        case .history: [.snapshot, .mine, .timetable, .change, .history]
        case .courses: [.snapshot, .timetable]
        }
        for p in parts {
            let key = "debug.demo.seeded.\(p.rawValue)"
            if UserDefaults.standard.bool(forKey: key) { continue }
            UserDefaults.standard.set(true, forKey: key)
            switch p {
            case .snapshot: seedSnapshot()
            case .mine: seedMine()
            case .timetable: seedTimetable()
            case .change: seedChange()
            case .history: seedHistory()
            }
        }
    }

    // MARK: 데이터

    private static let ETL = "https://myetl.snu.ac.kr/courses"
    private static let examAnnouncementId: Int64 = 770_001

    private struct DemoCourse {
        let id: Int64
        let name: String
        let days: [String]
        let start: String
        let end: String
        let room: String
        let professor: String
    }

    private static let courses = [
        DemoCourse(id: 31001, name: "2026-2 회로이론 (001)", days: ["월", "수"], start: "09:30", end: "10:45", room: "301-118", professor: "김재현"),
        DemoCourse(id: 31002, name: "2026-2 자료구조 (002)", days: ["화", "목"], start: "11:00", end: "12:15", room: "302-208", professor: "이수민"),
        DemoCourse(id: 31003, name: "2026-2 글쓰기의 기초 (015)", days: ["월", "수"], start: "14:00", end: "15:15", room: "7-201", professor: "박지영"),
        DemoCourse(id: 31004, name: "2026-2 확률과 통계 (001)", days: ["화", "목"], start: "15:30", end: "16:45", room: "26-104", professor: "최민호"),
        DemoCourse(id: 31005, name: "2026-2 공학수학 2 (003)", days: ["금"], start: "13:00", end: "15:50", room: "301-203(무선랜제공)", professor: "정하늘"),
    ]

    private static func at(_ days: Int, _ h: Int, _ m: Int) -> Date { DayDate.today().plusDays(days).atTime(h, m) }

    private static func seedSnapshot() {
        let now = Date()
        func item(_ c: Int, _ aid: Int64, _ name: String, _ due: Date, online: Bool = true, submitted: Bool = false,
                  missing: Bool = false, check: SubmitCheck? = nil) -> AssignmentItem {
            let course = courses[c]
            return AssignmentItem(course.id, course.name, aid, name, due, online, 10, "\(ETL)/\(course.id)/assignments/\(aid)",
                                  submitted, missing, nil, submitted ? now.addingTimeInterval(-86_400) : nil, check)
        }
        let items = [
            item(0, 90001, "HW5 · RC 회로 과도응답", at(1, 23, 59)),
            item(1, 90002, "실습 7 · 해시 테이블", at(2, 18, 0)),
            item(1, 90003, "실습 6 · 이진 탐색 트리", now.addingTimeInterval(5 * 3600), submitted: true,
                 check: SubmitCheck(late: false, problems: ["빈 파일(0바이트)이 있어요: bst.zip"], files: 1)),
            item(2, 90004, "에세이 초안", at(4, 23, 59), submitted: true, check: SubmitCheck(late: false, problems: [], files: 1)),
            item(3, 90005, "HW4 · 조건부 확률", at(10, 23, 59), online: false),
            item(4, 90006, "퀴즈 3", at(-1, 23, 59), missing: true),
        ]
        SchedStores.snapshot.save(Snapshot(now.addingTimeInterval(-40 * 60), items, []))
    }

    private static func seedMine() {
        let base = SchedFormat.nowMs()
        let scholarship = at(2, 17, 0)
        let weeklyTime = DayTime.of(23, 59)
        let weeklyDue = (DeadlineForm.firstWeekly(Date(), weeklyTime, [.thursday]) ?? DayDate.today().plusDays(1)).atTime(23, 59)
        let examStart = at(6, 14, 0)
        let list = [
            MyDeadline(id: base - 3, title: "장학금 서류 제출", due: scholarship, memo: "학생지원센터 2층",
                       reminders: [scholarship.addingTimeInterval(-3600)]),
            MyDeadline(id: base - 2, title: "주간 실험 보고서", due: weeklyDue, reminders: [weeklyDue.addingTimeInterval(-3 * 3600)],
                       repeat: MyRepeat(.WEEKLY, weekdays: [.thursday])),
            MyDeadline(id: base - 1, title: "기말고사", due: examStart,
                       reminders: MyDeadlines.defaultExamReminders(examStart, Date()), kind: .EXAM,
                       end: examStart.addingTimeInterval(2 * 3600), location: "301동 118호", course: "자료구조",
                       supplies: "공학용 계산기, 신분증", sourceAnnouncementId: examAnnouncementId),
        ]
        SchedStores.mine.save(list)
    }

    private static func seedTimetable() {
        let list = courses.map { c in
            TimetableLogic.CourseMeetings(c.id, c.name, c.days.map { TimetableLogic.Meeting($0, c.start, c.end, c.room) }, c.professor, credits: 3)
        }
        SchedStores.timetable.save(TimetableLogic.Weekly(courses.map { String($0.id) }.joined(separator: ","), SchedFormat.nowMs(), list, []))
    }

    private static func seedChange() {
        let c = courses[2]
        SchedStores.changes.add(TimetableLogic.Change(
            SchedFormat.nowMs(), DayDate.today().nextOrSame(.wednesday), "휴강", TimetableLogic.shortName(c.name),
            courseId: c.id, note: "교수님 학회 참석"))
    }

    private static func seedHistory() {
        let now = SchedFormat.nowMs()
        let hour: Int64 = 3600 * 1000
        let hw5 = at(1, 23, 59)
        let examStart = at(6, 14, 0)
        let examDraft = ScheduleDraft(examAnnouncementId, .EXAM, "기말고사", "자료구조", "자료구조 · 공지: \(ETL)/31002/discussion_topics/\(examAnnouncementId)",
                                      supplies: "공학용 계산기, 신분증", start: examStart, end: examStart.addingTimeInterval(2 * 3600),
                                      prefill: "자료구조 기말고사 공지를 읽고 내 일정에 넣어 줘", category: .EXAM)
        let hwDue = at(8, 23, 59)
        let hwDraft = ScheduleDraft(770_002, .DEADLINE, "프로젝트 제안서 제출", "확률과 통계", "확률과 통계 · 공지: \(ETL)/31004/discussion_topics/770002",
                                    start: hwDue, prefill: "확률과 통계 프로젝트 공지를 읽고 마감을 넣어 줘", category: .ASSIGNMENT)
        let makeupDay = DayDate.today().next(.saturday)
        let makeupDraft = ScheduleDraft(770_003, .DEADLINE, "보강 안내", "회로이론", "공지: \(ETL)/31001/discussion_topics/770003",
                                        start: makeupDay.atTime(10, 0), end: makeupDay.atTime(11, 15), category: .MAKEUP, day: makeupDay)
        let notices: [(Int64, Notice)] = [
            (now - 30 * hour, Notice(.BRIEF, "eTL 브리핑 · \(Agenda.dateLabel(DayDate.today().minusDays(1)))",
                                    "수업 4개 · 마감 임박 2건 · 누락 1건 · 새 공지 1건", tag: "demo-brief")),
            (now - 20 * hour, Notice(.ANNOUNCEMENT, "자료구조 · 시험 공지", "기말고사 안내",
                                    bigText: "준비물·지참: 공학용 계산기, 신분증\n\n기말고사는 \(NoticeText.dateLabel(examStart))부터 2시간 동안 301동 118호에서 봅니다.",
                                    url: "\(ETL)/31002/discussion_topics/\(examAnnouncementId)", tag: "demo-ann-1", draft: examDraft.toJson())),
            (now - 9 * hour, Notice(.ANNOUNCEMENT, "확률과 통계 · 과제 공지", "프로젝트 제안서 제출 안내",
                                   bigText: "프로젝트 제안서를 \(NoticeText.dateLabel(hwDue))까지 eTL에 제출하세요.",
                                   url: "\(ETL)/31004/discussion_topics/770002", tag: "demo-ann-2", draft: hwDraft.toJson())),
            (now - 6 * hour, Notice(.ANNOUNCEMENT, "회로이론 · 보강 공지", "\(Agenda.dateLabel(makeupDay)) 10:00 보강",
                                   bigText: "\(Agenda.dateLabel(makeupDay)) 10:00~11:15 301동 118호에서 보강합니다.",
                                   url: "\(ETL)/31001/discussion_topics/770003", tag: "demo-ann-3", draft: makeupDraft.toJson())),
            (now - 3 * hour, Notice(.CLASS, "10분 뒤 수업 · 자료구조", "11:00~12:15 · 302-208", tag: "demo-class")),
            (now - 2 * hour, Notice(.DEADLINE, "마감 임박 · 회로이론", "HW5 · RC 회로 과도응답 · \(NoticeText.dateLabel(hw5)) (\(EtlLogic.remaining(hw5, Date())))",
                                   url: "\(ETL)/31001/assignments/90001", tag: "demo-deadline")),
        ]
        for (t, n) in notices { SchedStores.history.record(n, nowMs: t) }
    }
    #else
    static func seed(_ screen: Screen) {}
    #endif
}
