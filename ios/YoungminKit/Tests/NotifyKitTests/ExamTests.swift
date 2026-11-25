import Foundation
import Testing
import ETLKit
@testable import NotifyKit

/// 시험 종류: 저장 호환, 알림 기본값, 응원 문구, 브리핑·내 일정·도구 결과.
@Suite struct ExamTests {
    let now = kst("2026-11-24T12:00") // 화요일

    func exam(_ id: Int64 = 77, start: String = "2026-11-26T10:00", end: String? = "2026-11-26T12:00", location: String = "301동 118호",
              course: String = "자료구조", supplies: String = "") -> MyDeadline {
        MyDeadline(id, "중간고사", kst(start), reminders: MyDeadlines.defaultExamReminders(kst(start), now), kind: .EXAM,
                   end: end.map(kst), location: location, course: course, supplies: supplies)
    }

    @Test func oldDataReadsAsDeadlineAndExamRoundTrips() {
        let old = #"[{"id":5,"title":"옛 항목","due":0,"memo":"","done":false,"reminders":[]}]"#
        let list = MyDeadlines.fromJson(old)
        #expect(list.count == 1)
        let d = list[0]
        #expect(d.kind == .DEADLINE)
        #expect(d.end == nil)
        #expect(d.sourceAnnouncementId == nil)
        var e = exam(supplies: "공학용 계산기, 신분증")
        e.sourceAnnouncementId = 42
        let back = MyDeadlines.fromJson(MyDeadlines.toJson([e]))
        #expect(back == [e])
        // 마감은 시험 칸을 쓰지 않는다(예전 형식 그대로)
        #expect(!MyDeadlines.toJson([d]).contains("kind"))
        // 시험에 반복이 들어 있어도 읽을 때 뺀다
        let bad = #"[{"id":6,"title":"시험","due":1,"kind":"exam","repeat":{"freq":"daily"}}]"#
        #expect(MyDeadlines.fromJson(bad).first!.repeat == nil)
    }

    @Test func defaultRemindersAreEveAndTwoHoursBefore() {
        #expect(MyDeadlines.defaultExamReminders(kst("2026-11-26T10:00"), now) == [kst("2026-11-25T20:00"), kst("2026-11-26T08:00")])
        // 이미 지난 시각은 뺀다(1시간 뒤 시험이면 둘 다 지남)
        #expect(MyDeadlines.defaultExamReminders(kst("2026-11-24T13:00"), now).isEmpty)
    }

    @Test func validation() {
        #expect(MyDeadlines.validateExam(exam()) == nil)
        #expect(MyDeadlines.validateExam(exam(end: "2026-11-26T09:00")) == "끝 시각은 시작 시각보다 뒤여야 해요")
        var r = exam()
        r.repeat = MyRepeat(.DAILY)
        #expect(MyDeadlines.validateExam(r)!.contains("반복"))
        #expect(MyDeadlines.normalizeSupplies("공학용 계산기\n신분증 · A4 요약지 1장, 신분증") == "공학용 계산기, 신분증, A4 요약지 1장")
    }

    @Test func examsAreNotDeadlineAlertItemsButShowInBriefAndWidgetList() {
        let list = [exam(), MyDeadline(1, "서류", kst("2026-11-25T18:00"))]
        #expect(MyDeadlines.toItems(list, now).map(\.name) == ["서류"])
        let lines = MyDeadlines.examLines(list, now)
        #expect(lines == ["• 11/26(목) 10:00~12:00 · [시험] 자료구조 중간고사 · 301동 118호"])
        let tomorrowLines = MyDeadlines.examLines([exam(start: "2026-11-25T10:00", end: nil, supplies: "계산기")], now)
        #expect(tomorrowLines.count == 1)
        #expect(tomorrowLines[0].hasPrefix("• [내일] "))
        #expect(tomorrowLines[0].hasSuffix("준비물: 계산기"))
        // 끝난 시험은 빠진다
        #expect(MyDeadlines.examLines(list, kst("2026-11-26T12:01")).isEmpty)
    }

    @Test func reminderPutsInfoFirstThenCheer() {
        let e = exam(supplies: "공학용 계산기")
        let ns = MyDeadlines.reminderNotices([e], kst("2026-11-25T20:01"), [])
        #expect(ns.count == 1)
        let n = ns[0]
        #expect(n.title == "시험 · 중간고사 · 자료구조")
        let big = n.bigText!.components(separatedBy: "\n")
        #expect(big[0].hasPrefix("11/26(목) 10:00~12:00"))
        #expect(big[0].contains("· 301동 118호 · 준비물: 공학용 계산기"))
        #expect(ExamCheer.EVE.map { $0.replacingOccurrences(of: "{s}", with: "자료구조 시험") }.contains(big[1]))
        #expect(n.firedKeys == [MyDeadlines.reminderKey(e, kst("2026-11-25T20:00"))])
    }

    @Test func cheerIsDeterministicAndRespectsToneLocationAndSupplies() {
        let at = kst("2026-11-26T08:00")
        let start = kst("2026-11-26T10:00")
        let a = ExamCheer.message(.DAY, 77, at, "자료구조", start, "301동 118호", "", casual: false)
        #expect(a == ExamCheer.message(.DAY, 77, at, "자료구조", start, "301동 118호", "", casual: false))
        // 같은 번호의 반말 문구
        let i = ExamCheer.pick(77, at, .DAY, ExamCheer.DAY.count)
        #expect(ExamCheer.DAY_CASUAL[i].replacingOccurrences(of: "{s}", with: "자료구조 시험").replacingOccurrences(of: "{w}", with: "10:00 301동 118호에서")
                == ExamCheer.message(.DAY, 77, at, "자료구조", start, "301동 118호", "", casual: true))
        // 장소가 없으면 "10:00에"
        let noPlace = ExamCheer.message(.DAY, 77, at, "", start, "", "", casual: false)
        #expect(!noPlace.contains("에서"))
        #expect(!noPlace.contains("{"))
        // 준비물이 있으면 일반 확인 문구를 쓰지 않는다
        for id in Int64(1)...30 {
            let m = ExamCheer.message(.DAY, id, at, "물리", start, "", "계산기", casual: false)
            #expect(!m.contains("필기구"))
            #expect(m.contains("준비물"))
        }
        // 서로 다른 시험이면 다른 문구가 나올 수 있다
        #expect(Set((Int64(1)...30).map { ExamCheer.pick($0, at, .EVE, ExamCheer.EVE.count) }).count > 1)
        #expect([ExamCheer.EVE, ExamCheer.EVE_CASUAL, ExamCheer.DAY, ExamCheer.DAY_CASUAL, ExamCheer.AFTER, ExamCheer.AFTER_CASUAL, ExamCheer.DAY_SUPPLIES].allSatisfy { $0.count >= 5 })
        #expect(ExamCheer.phaseOf(kst("2026-11-25T20:00"), start) == .EVE)
        #expect(ExamCheer.phaseOf(kst("2026-11-26T08:00"), start) == .DAY)
    }

    @Test func doneAlertAfterEndUnlessQuietOrOff() {
        let e = exam()
        let after = kst("2026-11-26T12:10")
        let (sent, silent) = MyDeadlines.examDoneNotices([e], after, [], enabled: true, quiet: false, casual: false)
        #expect(sent.map(\.title) == ["수고했어요 · 자료구조 중간고사"])
        #expect(silent.isEmpty)
        let (none, keys) = MyDeadlines.examDoneNotices([e], after, [], enabled: true, quiet: true, casual: false)
        #expect(none.isEmpty)
        #expect(keys == [MyDeadlines.examDoneKey(e)])
        #expect(MyDeadlines.examDoneNotices([e], after, [MyDeadlines.examDoneKey(e)], enabled: true, quiet: false, casual: false).0.isEmpty)
        // 끝 시각 없는 시험·너무 늦은 확인은 없음
        #expect(MyDeadlines.examDoneNotices([exam(end: nil)], after, [], enabled: true, quiet: false, casual: false).0.isEmpty)
        #expect(MyDeadlines.examDoneNotices([e], kst("2026-11-26T16:00"), [], enabled: true, quiet: false, casual: false).0.isEmpty)
        #expect(MyDeadlines.examDoneNotices([e], after, [], enabled: true, quiet: false, casual: true).0.map(\.title) == ["수고했어 · 자료구조 중간고사"])
        // 끝 시각에 제시간 알람
        #expect(MyDeadlines.nextReminderAt([e], kst("2026-11-26T09:00"), [MyDeadlines.reminderKey(e, kst("2026-11-26T08:00"))]) == kst("2026-11-26T12:00"))
    }

    @Test func agendaShowsExamRowsAndFilter() {
        let list = [exam(supplies: "계산기"), MyDeadline(1, "서류", kst("2026-11-25T18:00"))]
        let all = Agenda.build([], list, now, seoul)
        let rows = all.upcoming.flatMap(\.rows).filter { $0.kind == .EXAM }
        #expect(rows.count == 1)
        let row = rows[0]
        #expect(row.title == "자료구조 중간고사")
        #expect(row.subtitle.hasPrefix("시험 · 301동 118호 · 준비물: 계산기"))
        #expect(row.timeText!.hasPrefix("11/26(목) 10:00~12:00"))
        let examsOnly = Agenda.build([], list, now, seoul, .EXAM)
        #expect(examsOnly.upcoming.flatMap(\.rows).map(\.kind) == [.EXAM])
        let mineOnly = Agenda.build([], list, now, seoul, .MINE)
        #expect(mineOnly.upcoming.flatMap(\.rows).map(\.title) == ["서류"])
        // 끝난 시험은 지난 항목에도 남지 않는다
        #expect(!Agenda.build([], list, kst("2026-11-26T13:00"), seoul).overdue.contains { $0.kind == .EXAM })
    }

    @Test func toolResultIncludesExamFields() throws {
        let o = try MyDeadlines.toolResult([exam(supplies: "계산기")], now, nil, includeDone: false).getJSONArray("items").getJSONObject(0)
        #expect(try o.getString("kind") == "exam")
        #expect(try o.getString("location") == "301동 118호")
        #expect(try o.getString("supplies") == "계산기")
        #expect(o.has("end_kst"))
        #expect(try o.getBoolean("overdue") == false)
        #expect(try o.getString("start_kst") != "")
    }
}
