import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct MyDeadlinesTests {
    /// 2026-11-19(목) 12:00 KST
    let now = instant("2026-11-19T03:00:00Z")
    func hours(_ h: Double) -> Date { now.h(h) }
    func mine(_ id: Int64, _ due: Date, _ title: String? = nil, done: Bool = false) -> MyDeadline {
        MyDeadline(id, title ?? "내 일\(id)", due, "", done: done)
    }

    @Test func jsonRoundTrip() {
        let list = [MyDeadline(1, "장학금 서류", hours(5), "행정실", done: false), MyDeadline(2, "회의", hours(-3), "", done: true)]
        #expect(MyDeadlines.fromJson(MyDeadlines.toJson(list)) == list)
    }

    @Test func fromJsonSkipsBrokenEntries() {
        let raw = #"[{"id":1,"title":"ok","due":0},{"id":0,"title":"no id","due":0},{"id":3,"title":"  ","due":0},{"id":4,"title":"no due"},5]"#
        #expect(MyDeadlines.fromJson(raw).map(\.id) == [1])
        #expect(MyDeadlines.fromJson("not json").isEmpty)
        #expect(MyDeadlines.fromJson(nil).isEmpty)
    }

    @Test func toItemsMapsDoneAndOverdue() {
        let items = MyDeadlines.toItems([mine(1, hours(2)), mine(2, hours(-1)), mine(3, hours(-1), done: true)], now)
        #expect(items.allSatisfy { $0.courseId == MyDeadlines.COURSE_ID && $0.courseName == "내 마감" && $0.url == nil })
        #expect(items.map(\.missing) == [false, true, false])
        #expect(items.map(\.submitted) == [false, false, true])
    }

    @Test func deadlineAlertFiresForMyDeadline() {
        let etl = AssignmentItem(10, "자료구조", 7, "HW3", hours(50), true, nil, nil, false, false)
        let snap = MyDeadlines.merge(Snapshot(now, [etl], []), [mine(1, hours(2), "장학금 서류")], now)
        #expect(snap.assignments.map(\.assignmentId) == [1, 7]) // 마감순
        let plan = NotificationPlanner.plan(snap, NotifyState(announcementBaselineDone: true), now, NotifySettings(), seoul)
        let ns = plan.notices.filter { $0.tag == "deadline:-1:1" }
        #expect(ns.count == 1)
        let n = ns[0]
        #expect(n.title == "마감 임박 · 내 마감")
        #expect(n.text.hasPrefix("장학금 서류"))
        #expect(!n.text.contains("오프라인"))
        #expect(n.url == nil)
        #expect(n.firedKeys.contains("deadline-m:-1:1:180"))
    }

    @Test func doneDeadlineIsNotAlerted() {
        let snap = MyDeadlines.merge(Snapshot(now, [], []), [mine(1, hours(2), done: true)], now)
        #expect(NotificationPlanner.plan(snap, NotifyState(announcementBaselineDone: true), now, NotifySettings(), seoul).notices.isEmpty)
    }

    @Test func briefIncludesMyDeadline() {
        let snap = MyDeadlines.merge(Snapshot(now, [], []), [mine(1, hours(30), "동아리 회의록")], now)
        let brief = BriefBuilder.build(snap, now, NotifySettings(), seoul)
        #expect(brief.bigText != nil)
        #expect(brief.bigText!.contains("내 마감 — 동아리 회의록"))
    }

    @Test func validateAndSort() {
        #expect(MyDeadlines.validate("  ", "", 0) == "제목을 입력하세요")
        #expect(MyDeadlines.validate(String(repeating: "a", count: 61), "", 0) != nil)
        #expect(MyDeadlines.validate("ok", "", MyDeadlines.MAX_ITEMS) != nil)
        #expect(MyDeadlines.validate("ok", "memo", 0) == nil)
        let sorted = MyDeadlines.sorted([mine(1, hours(9), done: true), mine(2, hours(5)), mine(3, hours(1)), mine(4, hours(20), done: true)])
        #expect(sorted.map(\.id) == [3, 2, 4, 1])
    }

    @Test func toolResultFiltersByWindowAndDone() throws {
        let list = [
            MyDeadline(1, "장학금 서류", hours(30), "행정실", done: false), // 11/20(금) 이번 주
            mine(2, hours(24 * 6)),                                        // 11/25 다음 주
            mine(3, hours(-5)),                                            // 지남, 미완료 → 항상 포함
            mine(4, hours(10), done: true),                                // 완료
        ]
        let week = AssignmentQuery.window(.THIS_WEEK, now)
        let r = MyDeadlines.toolResult(list, now, week, includeDone: false)
        let items = try r.getJSONArray("items")
        let titles = (0..<items.length()).map { try! items.getJSONObject($0).getString("title") }
        #expect(titles == ["내 일3", "장학금 서류"])
        #expect(try items.getJSONObject(0).getBoolean("overdue"))
        #expect(try items.getJSONObject(1).getString("memo") == "행정실")
        #expect(try MyDeadlines.toolResult(list, now, nil, includeDone: true).getInt("count") == 4)
        #expect(try MyDeadlines.toolResult(list, now, nil, includeDone: false).getInt("count") == 3)
    }

    @Test func parseDueKst() throws {
        #expect(MyDeadlines.parseDue("2026-11-23 23:59") == instant("2026-11-23T14:59:00Z"))
        #expect(MyDeadlines.parseDue("2026-11-23T9:30") == instant("2026-11-23T00:30:00Z"))
        #expect(MyDeadlines.parseDue("2026-11-23") == instant("2026-11-23T14:59:00Z"))
        #expect(MyDeadlines.parseDue("2026-11-23 18:00:00") == instant("2026-11-23T09:00:00Z"))
        #expect(MyDeadlines.parseDue("내일 6시") == nil)
        #expect(MyDeadlines.parseDue("2026-13-01 10:00") == nil)
        #expect(try MyDeadlines.toolResult([mine(7, hours(5))], now, nil, includeDone: false).getJSONArray("items").getJSONObject(0).has("id"))
    }

    @Test func customReminders() {
        // 오늘(11/19) 22:00 마감, 21:00·21:50 알림
        let due = instant("2026-11-19T13:00:00Z")
        let r1 = MyDeadlines.parseReminder("21:00", due)!
        let r2 = MyDeadlines.parseReminder("2026-11-19 21:50", due)!
        #expect(r1 == instant("2026-11-19T12:00:00Z"))
        #expect(r2 == instant("2026-11-19T12:50:00Z"))
        #expect(MyDeadlines.parseReminder("23:30", due) == instant("2026-11-18T14:30:00Z")) // 마감보다 늦은 시각이면 전날
        #expect(MyDeadlines.parseReminder("저녁", due) == nil)
        #expect(MyDeadlines.validateReminders([due], due) != nil)
        let dl = MyDeadline(5, "보고서", due, reminders: [r1, r2])
        #expect(MyDeadlines.fromJson(MyDeadlines.toJson([dl])) == [dl])
        #expect(MyDeadlines.reminderLabel(dl) == "21:00, 21:50")

        let at2105 = instant("2026-11-19T12:05:00Z")
        let n = MyDeadlines.reminderNotices([dl], at2105, [])
        #expect(n.count == 1)
        #expect(n[0].title == "알림 · 보고서")
        #expect(n[0].text.contains("55분"))
        let fired = Set(n[0].firedKeys)
        #expect(MyDeadlines.reminderNotices([dl], at2105, fired).isEmpty)
        #expect(MyDeadlines.nextReminderAt([dl], at2105, fired) == r2)
        // 늦게 깨어나도 마감 전이면 두 개를 한 알림으로
        let late = MyDeadlines.reminderNotices([dl], instant("2026-11-19T12:55:00Z"), [])
        #expect(late.count == 1 && late[0].firedKeys.count == 2)
        // 마감이 지났거나 완료면 울리지 않는다
        #expect(MyDeadlines.reminderNotices([dl], due.secs(60), []).isEmpty)
        var doneDl = dl
        doneDl.done = true
        #expect(MyDeadlines.reminderNotices([doneDl], at2105, []).isEmpty)
        #expect(MyDeadlines.nextReminderAt([dl], due, []) == nil)
    }
}
