import Foundation
import Testing
import ETLKit
@testable import NotifyKit

// Kotlin `calendar/CalendarLogicTest.kt`, `CalendarSyncEditsTest.kt`, `CalendarCleanupPolicyTest.kt` + 맞추기 순서(가짜 캘린더).
// 일정 ID는 EventKit 식별자(문자열)라 안드로이드의 정수 ID를 문자열로 바꿨다.

private typealias L = CalendarSyncLogic

private let calNow = kst("2026-11-23T12:00") // 월요일

private func assignment(_ cid: Int64, _ aid: Int64, _ due: String?, submitted: Bool = false, name: String = "과제 1",
                        course: String = "2026-2 자료구조 (001)") -> AssignmentItem {
    AssignmentItem(cid, course, aid, name, due.map(kst), true, 10.0, "https://myetl.snu.ac.kr/courses/\(cid)/assignments/\(aid)", submitted, false)
}

private func mine(_ id: Int64, _ due: String, _ repeat: MyRepeat? = nil, done: Bool = false, doneDates: Set<DayDate> = [],
                  skipDates: Set<DayDate> = [], memo: String = "", title: String = "실험 보고서") -> MyDeadline {
    MyDeadline(id, title, kst(due), memo, done: done, repeat: `repeat`, doneDates: doneDates, skipDates: skipDates)
}

private func exam(_ id: Int64 = 7) -> MyDeadline {
    MyDeadline(id, "중간고사", kst("2026-11-30T10:00"), kind: .EXAM, end: kst("2026-11-30T12:00"),
               location: "301동 118호", course: "자료구조", supplies: "공학용 계산기, 신분증")
}

struct CalendarLogicTests {
    // ---- ICS 기본 조각 ----

    @Test func escapesText() {
        #expect(L.escapeText("a,b;c\\d\ne\r\nf") == "a\\,b\\;c\\\\d\\ne\\nf")
        #expect(L.escapeText("탭\u{0000}없음") == "탭없음")
    }

    @Test func foldsAt75OctetsWithoutSplittingCharacters() {
        let ascii = "DESCRIPTION:" + String(repeating: "x", count: 200)
        let folded = L.fold(ascii)
        for line in folded.components(separatedBy: "\r\n") { #expect(line.utf8.count <= 75) }
        #expect(folded.replacingOccurrences(of: "\r\n ", with: "") == ascii)

        let korean = "SUMMARY:" + String(repeating: "가나다라마바사아자차카타파하", count: 5)
        let f2 = L.fold(korean)
        let lines = f2.components(separatedBy: "\r\n")
        #expect(lines.count > 1)
        for line in lines {
            #expect(line.utf8.count <= 75)
            #expect(String(decoding: Array(line.utf8), as: UTF8.self) == line)
        }
        #expect(lines.dropFirst().allSatisfy { $0.hasPrefix(" ") })
        #expect(f2.replacingOccurrences(of: "\r\n ", with: "") == korean)
        #expect(L.fold("SHORT:1") == "SHORT:1")
    }

    @Test func formatsTimes() {
        #expect(L.local(kst("2026-11-25T23:59")) == "20261125T235900")
        #expect(L.utc(kst("2026-11-25T23:59")) == "20261125T145900Z")
    }

    // ---- RRULE ----

    @Test func mapsRepeatRules() {
        #expect(L.rrule(mine(1, "2026-11-23T18:00")) == nil)
        #expect(L.rrule(mine(1, "2026-11-23T18:00", MyRepeat(.DAILY))) == "FREQ=DAILY")
        #expect(L.rrule(mine(1, "2026-11-23T18:00", MyRepeat(.WEEKLY, weekdays: [.wednesday, .monday]))) == "FREQ=WEEKLY;BYDAY=MO,WE")
        // 요일을 안 고르면 시작일 요일(월)
        #expect(L.rrule(mine(1, "2026-11-23T18:00", MyRepeat(.WEEKLY))) == "FREQ=WEEKLY;BYDAY=MO")
        #expect(L.rrule(mine(1, "2026-11-23T18:00", MyRepeat(.BIWEEKLY, weekdays: [.tuesday, .friday]))) == "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=TU,FR")
        #expect(L.rrule(mine(1, "2026-11-15T18:00", MyRepeat(.MONTHLY))) == "FREQ=MONTHLY;BYMONTHDAY=15")
        #expect(L.rrule(mine(1, "2026-10-31T18:00", MyRepeat(.MONTHLY))) == "FREQ=MONTHLY;BYMONTHDAY=-1")
        #expect(L.rrule(mine(1, "2026-11-30T18:00", MyRepeat(.MONTHLY))) == "FREQ=MONTHLY;BYMONTHDAY=30")
        // 끝 날짜는 그날 KST 23:59:59를 UTC로
        #expect(L.rrule(mine(1, "2026-11-23T18:00", MyRepeat(.DAILY, until: d("2026-12-20")))) == "FREQ=DAILY;UNTIL=20261220T145959Z")
    }

    @Test func dtstartIsFirstOccurrenceMatchingTheRule() {
        // 수요일에 만들고 월요일만 고른 매주 반복 → 첫 회차는 다음 월요일
        let weekly = mine(1, "2026-11-25T18:00", MyRepeat(.WEEKLY, weekdays: [.monday]))
        #expect(L.dtstartFor(weekly)?.due == kst("2026-11-30T18:00"))
        // 30일 매월 반복을 2월 말일 회차에서 넣으면 RRULE(BYMONTHDAY=30)에 맞는 3/30부터
        let monthly = mine(2, "2027-01-30T09:00", MyRepeat(.MONTHLY))
        let feb = MyDeadline(2, "x", kst("2027-02-28T09:00"), repeat: monthly.repeat, occurrence: d("2027-02-28"))
        #expect(L.dtstartFor(monthly, feb)?.due == kst("2027-03-30T09:00"))
        // 31일은 BYMONTHDAY=-1이라 2월 말일 그대로
        let m31 = mine(3, "2027-01-31T09:00", MyRepeat(.MONTHLY))
        let feb31 = MyDeadline(3, "x", kst("2027-02-28T09:00"), repeat: m31.repeat, occurrence: d("2027-02-28"))
        #expect(L.dtstartFor(m31, feb31)?.due == kst("2027-02-28T09:00"))
    }

    // ---- .ics 전체 ----

    @Test func buildsIcsWithTimezoneRepeatAndExdates() {
        let filter = CourseFilter().with(CourseRef(77, "숨긴 과목"), .HIDE)
        let weekly = mine(5, "2026-11-23T18:00", MyRepeat(.WEEKLY, weekdays: [.monday, .wednesday], until: d("2026-12-20")),
                          doneDates: [d("2026-11-23")], skipDates: [d("2026-11-25")], memo: "실험실, 3층; 제출")
        let ics = L.ics(CalendarExportSettings(), [
            assignment(10, 100, "2026-11-25T23:59", name: "보고서, 1차; 최종"),
            assignment(10, 101, "2026-11-26T23:59", submitted: true),
            assignment(77, 200, "2026-11-26T23:59"),
            assignment(10, 102, nil),
        ], [weekly, mine(6, "2026-11-28T10:00", done: true)], filter, calNow)
        #expect(ics.hasPrefix("BEGIN:VCALENDAR\r\n"))
        #expect(ics.hasSuffix("END:VCALENDAR\r\n"))
        #expect(!ics.replacingOccurrences(of: "\r\n", with: "").contains("\n"))
        let unfolded = ics.replacingOccurrences(of: "\r\n ", with: "")
        #expect(unfolded.contains("BEGIN:VTIMEZONE\r\nTZID:Asia/Seoul\r\n"))
        #expect(unfolded.contains("TZOFFSETTO:+0900"))
        #expect(unfolded.contains("SUMMARY:[자료구조] 보고서\\, 1차\\; 최종\r\n"))
        #expect(unfolded.contains("DTSTART;TZID=Asia/Seoul:20261125T235900\r\n"))
        #expect(unfolded.contains("URL:https://myetl.snu.ac.kr/courses/10/assignments/100\r\n"))
        #expect(unfolded.contains("UID:etl-10-100@youngmin.etl-android"))
        #expect(!unfolded.contains("etl-10-101@"), "제출한 과제는 빠진다")
        #expect(!unfolded.contains("etl-77-200@"), "숨긴 과목은 빠진다")
        #expect(!unfolded.contains("etl-10-102@"), "마감 없는 과제는 빠진다")
        #expect(!unfolded.contains("mine-6@"), "완료한 내 마감은 빠진다")
        #expect(!unfolded.contains("DTEND"), "0분 일정이라 DTEND가 없다")
        #expect(unfolded.contains("UID:mine-5@youngmin.etl-android"))
        #expect(unfolded.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261220T145959Z\r\n"))
        #expect(unfolded.contains("EXDATE;TZID=Asia/Seoul:20261123T180000,20261125T180000\r\n"))
        #expect(unfolded.contains("DESCRIPTION:실험실\\, 3층\\; 제출\\n내 마감 · 매주 월·수 · 12/20까지"))

        // 제출·완료 포함이면 제출한 과제와 완료한 마감이 들어가고, 완료 회차는 EXDATE에서 빠진다.
        let all = L.ics(CalendarExportSettings(includeDone: true), [assignment(10, 101, "2026-11-26T23:59", submitted: true)],
                        [weekly, mine(6, "2026-11-28T10:00", done: true)], CourseFilter(), calNow).replacingOccurrences(of: "\r\n ", with: "")
        #expect(all.contains("SUMMARY:[자료구조] 과제 1 (제출함)"))
        #expect(all.contains("UID:mine-6@"))
        #expect(all.contains("EXDATE;TZID=Asia/Seoul:20261125T180000\r\n"))

        // 항목 끄기
        let none = L.ics(CalendarExportSettings(includeEtl: false, includeMine: false), [assignment(10, 100, "2026-11-25T23:59")],
                         [weekly], CourseFilter(), calNow)
        #expect(!none.contains("BEGIN:VEVENT"))
    }

    @Test func dropsSignedOrQueryUrls() {
        #expect(L.safeEtlUrl("https://myetl.snu.ac.kr/courses/1/assignments/2") == "https://myetl.snu.ac.kr/courses/1/assignments/2")
        #expect(L.safeEtlUrl("https://myetl.snu.ac.kr/files/1/download?verifier=abc") == nil)
        #expect(L.safeEtlUrl("https://evil.example/courses/1") == nil)
        #expect(L.safeEtlUrl(nil) == nil)
    }

    // ---- 내보낼 항목 ----

    @Test func desiredItemsFollowSettingsAndFilters() {
        let filter = CourseFilter().with(CourseRef(77, "숨긴 과목"), .HIDE)
        let daily = mine(9, "2026-11-20T09:00", MyRepeat(.DAILY), doneDates: [d("2026-11-24")])
        let r = L.desired(CalendarExportSettings(), [
            assignment(10, 100, "2026-11-25T23:59"),
            assignment(10, 101, "2026-11-26T23:59", submitted: true),
            assignment(77, 200, "2026-11-26T23:59"),
            assignment(10, 103, "2026-10-01T23:59"), // 14일보다 오래됨
        ], [daily, mine(8, "2026-11-30T10:00")], filter, calNow)
        let keys = r.items.map(\.key)
        #expect(keys.contains("etl:10:100"))
        #expect(!keys.contains("etl:10:101"))
        #expect(!keys.contains("etl:77:200"))
        #expect(!keys.contains("etl:10:103"))
        #expect(keys.contains("mine:8"))
        // 매일 반복: 시작(11/20)부터 앞으로 60일까지, 완료한 11/24 회차 제외
        let dailyKeys = keys.filter { $0.hasPrefix("mine:9:") }
        #expect(dailyKeys.contains("mine:9:\(d("2026-11-20").epochDay)"))
        #expect(!dailyKeys.contains("mine:9:\(d("2026-11-24").epochDay)"))
        #expect(dailyKeys.contains("mine:9:\(d("2027-01-21").epochDay)"))
        #expect(!dailyKeys.contains("mine:9:\(d("2027-01-23").epochDay)"))
        #expect(r.authoritative == ["etl", "mine"])

        let item = r.items.first { $0.key == "etl:10:100" }!
        #expect(item.title == "[자료구조] 과제 1")
        #expect(item.start == item.end)
        #expect(item.description.contains("https://myetl.snu.ac.kr/courses/10/assignments/100"))

        // 받아 둔 eTL 과제가 없으면 eTL 일정은 건드리지 않는다.
        #expect(L.desired(CalendarExportSettings(), nil, [], CourseFilter(), calNow).authoritative == ["mine"])
        // eTL을 끄면 eTL 일정은 모두 지울 대상
        #expect(L.desired(CalendarExportSettings(includeEtl: false), nil, [], CourseFilter(), calNow).authoritative == ["etl", "mine"])
    }

    // ---- 맞추기 ----

    private func item(_ key: String, _ due: String, _ title: String? = nil) -> CalItem {
        CalItem(key, title ?? key, kst(due), kst(due), "d")
    }

    private func entry(_ id: String, _ it: CalItem) -> L.Entry { L.Entry(id, it.hash, it.start.epochMilli) }

    @Test func planInsertsUpdatesDeletesAndTombstones() {
        let a = item("etl:1:1", "2026-11-25T23:59")
        let b = item("etl:1:2", "2026-11-26T23:59")
        let bChanged = item("etl:1:2", "2026-11-27T23:59")
        let c = item("mine:5", "2026-11-28T10:00")
        let gone = item("etl:1:3", "2026-11-29T23:59")
        let userDeleted = item("mine:6", "2026-11-30T10:00")
        let tomb = item("mine:7", "2026-12-01T10:00")
        let oldPast = item("etl:1:9", "2026-10-01T23:59")
        let ancient = item("etl:1:8", "2026-03-01T23:59")
        let mapping: [String: L.Entry] = [
            b.key: entry("12", b),
            c.key: entry("13", c),
            gone.key: entry("14", gone),
            userDeleted.key: entry("15", userDeleted),
            tomb.key: L.Entry(L.TOMBSTONE, tomb.hash, tomb.start.epochMilli),
            oldPast.key: entry("16", oldPast),
            ancient.key: entry("17", ancient),
        ]
        let desired = L.Desired([a, bChanged, c, userDeleted, tomb], ["etl", "mine"])
        let p = L.plan(desired, mapping, ["12", "13", "14", "16", "17"], calNow)
        #expect(p.ops.filter { if case .Insert = $0 { return true }; return false } == [.Insert(a)])
        #expect(p.ops.filter { if case .Update = $0 { return true }; return false } == [.Update("12", bChanged)])
        #expect(p.ops.filter { if case .Delete = $0 { return true }; return false } == [.Delete(gone.key, "14")])
        #expect(p.tombstone == [userDeleted.key], "사용자가 지운 일정은 묘비로")
        #expect(p.forget == [ancient.key], "오래된 매핑은 잊는다")
        #expect(p.inserts == 1 && p.updates == 1 && p.deletes == 1)
    }

    @Test func planKeepsNonAuthoritativeKindsAndForgetsMissingRows() {
        let e = item("etl:1:1", "2026-11-25T23:59")
        let m = item("mine:1", "2026-11-25T23:59")
        let mapping = [e.key: entry("1", e), m.key: entry("2", m)]
        // eTL 정보가 없을 때(authoritative에 etl 없음): eTL 일정은 그대로, 사라진 내 마감은 지운다.
        let p = L.plan(L.Desired([], ["mine"]), mapping, ["1", "2"], calNow)
        #expect(p.ops == [.Delete(m.key, "2")])
        // 원본도 없고 캘린더에서도 없어졌으면 매핑만 정리
        let p2 = L.plan(L.Desired([], ["etl", "mine"]), mapping, [], calNow)
        #expect(p2.ops.isEmpty)
        #expect(Set(p2.forget) == [e.key, m.key])
        // 그대로면 할 일 없음
        let p3 = L.plan(L.Desired([e, m], ["etl", "mine"]), mapping, ["1", "2"], calNow)
        #expect(p3.ops.isEmpty && p3.tombstone.isEmpty && p3.forget.isEmpty)
    }

    @Test func mappingAndSettingsRoundTrip() {
        let m: [String: L.Entry] = ["etl:1:2": L.Entry("5", "abc", 123), "mine:3": L.Entry(L.TOMBSTONE, "def", 456)]
        let back = L.mappingFromJson(L.mappingToJson("cal-42", m))!
        #expect(back.calendarId == "cal-42")
        #expect(back.entries == m)
        #expect(back.entries["mine:3"]!.tombstone)
        #expect(L.mappingFromJson("깨짐") == nil)

        let s = CalendarExportSettings(enabled: true, target: .GOOGLE, googleCalendarId: "7", googleCalendarName: "영민이",
                                       includeEtl: false, includeMine: true, includeDone: true)
        #expect(CalendarExportSettings.fromJson(s.toJson()) == s)
        #expect(CalendarExportSettings.fromJson(nil) == CalendarExportSettings())
        #expect(!CalendarExportSettings().enabled)
        #expect(CalendarExportSettings().includeEtl && CalendarExportSettings().includeMine && !CalendarExportSettings().includeDone)
    }

    @Test func keysAndAppUri() {
        let occ = MyDeadline(5, "x", kst("2026-11-25T18:00"), repeat: MyRepeat(.DAILY), occurrence: d("2026-11-25"))
        #expect(L.mineKey(occ) == "mine:5:\(d("2026-11-25").epochDay)")
        #expect(L.mineKey(mine(5, "2026-11-25T18:00")) == "mine:5")
        #expect(L.etlKey(3, 4) == "etl:3:4")
        #expect(L.mineIdFromUri("youngmin://item/mine:5:20000") == 5)
        #expect(L.mineIdFromUri("youngmin://item/etl:3:4") == nil)
        #expect(L.mineIdFromUri("https://example.com/mine:5") == nil)
        // iOS 주소는 위젯 링크와 같은 모양(`-`)
        #expect(L.appUri("mine:5:20000") == "youngmin://item/mine-5-20000")
        #expect(L.keyFromAppUri(L.appUri("etl:3:4")) == "etl:3:4")
        #expect(L.mineIdFromUri(L.appUri("mine:5:20000")) == 5)
        #expect(URL(string: L.appUri("mine:5:20000"))?.pathComponents == ["/", "mine-5-20000"])
        #expect(!L.hash(["a"]).isEmpty)
        #expect(L.hash(["ab", "c"]) != L.hash(["a", "bc"]))
        // SHA-256 앞 12바이트(안드로이드와 같은 값)
        #expect(L.hash(["a"]).count == 24)
    }
}

/// 캘린더에서 고친 것 되가져오기, 시험 일정 매핑, 반복 횟수 RRULE(Kotlin `CalendarSyncEditsTest`).
struct CalendarSyncEditsTests {
    private let settings = CalendarExportSettings(enabled: true)

    private func asg(_ cid: Int64, _ aid: Int64, _ due: String) -> AssignmentItem {
        AssignmentItem(cid, "2026-2 자료구조 (001)", aid, "과제 1", kst(due), true, 10.0, "https://myetl.snu.ac.kr/courses/\(cid)/assignments/\(aid)", false, false)
    }

    /// 지금 desired를 그대로 쓴 매핑(일정 ID = "e100"부터)과 그 모양의 캘린더 행.
    private func synced(_ desired: L.Desired) -> ([String: L.Entry], [String: CalRow]) {
        var entries: [String: L.Entry] = [:]
        var rows: [String: CalRow] = [:]
        for (i, it) in desired.items.enumerated() {
            let id = "e\(100 + i)"
            let row = CalRow(id, it.title, it.start.epochMilli, it.end.epochMilli, it.location)
            entries[it.key] = L.Entry(id, it.hash, it.start.epochMilli, row.hash)
            rows[id] = row
        }
        return (entries, rows)
    }

    private func pull(_ e: [L.Edit]) -> L.Pull? {
        guard e.count == 1, case .PullMine(let p) = e[0] else { return nil }
        return p
    }

    @Test func unchangedRowsNeedNothingAndOldMappingsGetBaseline() {
        let list = [mine(1, "2026-11-25T23:59")]
        let dd = L.desired(settings, [], list, CourseFilter(), calNow)
        let (entries, rows) = synced(dd)
        #expect(L.edits(dd, entries, rows, list, pullBack: true).isEmpty)
        let old = entries.mapValues { var e = $0; e.rowHash = ""; return e }
        let edits = L.edits(dd, old, rows, list, pullBack: true)
        #expect(edits.count == 1)
        if case .Baseline = edits[0] {} else { Issue.record("Baseline이어야 한다") }
    }

    @Test func movedMyDeadlineIsPulledBackWithNewIdAndShiftedReminders() throws {
        var d0 = mine(1, "2026-11-25T23:59")
        d0.reminders = [kst("2026-11-25T21:00")]
        let dd = L.desired(settings, [], [d0], CourseFilter(), calNow)
        let (entries, r0) = synced(dd)
        var rows = r0
        let id = entries["mine:1"]!.eventId
        rows[id]!.title = "[내 마감] 실험 보고서 최종"
        rows[id]!.start = kst("2026-11-26T18:00").epochMilli
        rows[id]!.end = kst("2026-11-26T18:00").epochMilli
        let e = try #require(pull(L.edits(dd, entries, rows, [d0], pullBack: true)))
        #expect(e.title == "실험 보고서 최종")
        #expect(e.start == kst("2026-11-26T18:00"))
        let new = try #require(L.applyPull(d0, e, 999))
        #expect(new.id == 999) // 시각이 바뀌면 새 ID(알림 기록 규칙)
        #expect(new.reminders == [kst("2026-11-26T15:01")]) // 마감 2시간 59분 전 그대로
        // 제목만 바꾸면 ID는 그대로
        rows[id]!.title = "[내 마감] 새 이름"
        rows[id]!.start = d0.due.epochMilli
        rows[id]!.end = d0.due.epochMilli
        let e2 = try #require(pull(L.edits(dd, entries, rows, [d0], pullBack: true)))
        #expect(e2.start == nil)
        #expect(L.applyPull(d0, e2, 999)?.id == 1)
    }

    @Test func etlAssignmentAndRepeatOccurrenceAreReverted() {
        let rep = mine(2, "2026-11-23T18:00", MyRepeat(.WEEKLY))
        let dd = L.desired(settings, [asg(5, 50, "2026-11-27T23:59")], [rep], CourseFilter(), calNow)
        let (entries, r0) = synced(dd)
        var rows = r0
        for k in ["etl:5:50", dd.items.first { $0.key.hasPrefix("mine:2:") }!.key] {
            let id = entries[k]!.eventId
            rows[id]!.start += 3_600_000
        }
        let edits = L.edits(dd, entries, rows, [rep], pullBack: true)
        #expect(edits.count == 2)
        var keys = Set<String>()
        var repeating = 0
        for e in edits {
            guard case let .Revert(key, r) = e else { Issue.record("Revert여야 한다"); continue }
            keys.insert(key)
            if r { repeating += 1 }
        }
        #expect(repeating == 1)
        // 되돌리기는 plan에서 Update로(내용 해시가 같아도)
        let plan = L.plan(dd, entries, Set(rows.keys), calNow, forceUpdate: keys)
        #expect(plan.updates == 2)
    }

    @Test func pullBackOffRevertsMineToo() {
        let d0 = mine(1, "2026-11-25T23:59")
        let dd = L.desired(settings, [], [d0], CourseFilter(), calNow)
        let (entries, r0) = synced(dd)
        var rows = r0
        rows[entries["mine:1"]!.eventId]!.title = "바뀜"
        let edits = L.edits(dd, entries, rows, [d0], pullBack: false)
        #expect(edits.count == 1)
        if case .Revert = edits[0] {} else { Issue.record("Revert여야 한다") }
    }

    @Test func repeatedRevertsStopAfterLimitToAvoidLoop() {
        let dd = L.desired(settings, [asg(5, 50, "2026-11-27T23:59")], [], CourseFilter(), calNow)
        var (entries, rows) = synced(dd)
        let k = "etl:5:50"
        rows[entries[k]!.eventId]!.end += 1
        entries[k]!.reverts = L.MAX_REVERTS
        let edits = L.edits(dd, entries, rows, [], pullBack: true)
        #expect(edits.count == 1)
        if case .Accept = edits[0] {} else { Issue.record("Accept여야 한다") }
    }

    @Test func appSideChangeWinsOverCalendarEdit() {
        let d0 = mine(1, "2026-11-25T23:59")
        let dd = L.desired(settings, [], [d0], CourseFilter(), calNow)
        let (entries, r0) = synced(dd)
        var rows = r0
        rows[entries["mine:1"]!.eventId]!.title = "캘린더에서 바꿈"
        var c = d0
        c.title = "앱에서 바꿈"
        let d2 = L.desired(settings, [], [c], CourseFilter(), calNow)
        #expect(L.edits(d2, entries, rows, [c], pullBack: true).isEmpty)
        #expect(L.plan(d2, entries, Set(rows.keys), calNow).updates == 1)
    }

    @Test func examPullBackKeepsLengthAndReadsLocation() throws {
        let x = exam()
        let dd = L.desired(settings, [], [x], CourseFilter(), calNow)
        let (entries, r0) = synced(dd)
        var rows = r0
        let id = entries["mine:7"]!.eventId
        rows[id]!.start = kst("2026-11-30T13:00").epochMilli
        rows[id]!.end = kst("2026-11-30T14:00").epochMilli + 3_600_000
        rows[id]!.location = "302동 101호"
        let e = try #require(pull(L.edits(dd, entries, rows, [x], pullBack: true)))
        let n = try #require(L.applyPull(x, e, 1000))
        #expect(n.due == kst("2026-11-30T13:00"))
        #expect(n.end == kst("2026-11-30T15:00"))
        #expect(n.location == "302동 101호")
        // 제목에서 [시험]·과목을 뗀다
        #expect(L.titleFromCalendar("[시험] 자료구조 기말고사", x) == "기말고사")
    }

    @Test func mappingKeepsRowHashAndReverts() {
        let m: [String: L.Entry] = ["mine:1": L.Entry("5", "h", 10, "r", 2), "etl:1:2": L.Entry(L.TOMBSTONE, "h2", 20)]
        #expect(L.mappingFromJson(L.mappingToJson("3", m))?.entries == m)
        // 예전 형식(3칸)도 읽는다
        let old = L.mappingFromJson(#"{"version":1,"calendarId":"3","entries":{"mine:1":["5","h",10]}}"#)!
        #expect(old.entries["mine:1"] == L.Entry("5", "h", 10, "", 0))
    }

    // ---- 시험 일정 ----

    @Test func examBecomesTimedEventWithLocationAndSupplies() {
        let it = L.mineItem(exam())
        #expect(it.title == "[시험] 자료구조 중간고사")
        #expect(it.start == kst("2026-11-30T10:00"))
        #expect(it.end == kst("2026-11-30T12:00"))
        #expect(it.location == "301동 118호")
        #expect(it.busy)
        #expect(it.description.hasPrefix("준비물: 공학용 계산기, 신분증"))
        // 마감 일정의 해시는 장소가 없으면 장소·바쁨을 넣지 않는다(이미 넣은 일정을 다시 쓰지 않는다).
        let plain = L.mineItem(mine(1, "2026-11-25T23:59"))
        #expect(plain.hash == L.hash([plain.title, String(plain.start.epochMilli), String(plain.end.epochMilli), plain.description, "", "Asia/Seoul"]))
        #expect(!plain.busy)
    }

    @Test func icsHasLocationAndEndForExam() {
        let ics = L.ics(settings, [], [exam()], CourseFilter(), calNow)
        #expect(ics.contains("DTSTART;TZID=Asia/Seoul:20261130T100000"))
        #expect(ics.contains("DTEND;TZID=Asia/Seoul:20261130T120000"))
        #expect(ics.contains("LOCATION:301동 118호"))
        #expect(ics.contains("SUMMARY:[시험] 자료구조 중간고사"))
        #expect(ics.contains("TRANSP:OPAQUE"))
        #expect(ics.replacingOccurrences(of: "\r\n ", with: "").contains("준비물: 공학용 계산기\\, 신분증"))
    }

    // ---- 반복 횟수 ----

    @Test func rruleUsesCountOrUntil() {
        let weekly = MyRepeat(.WEEKLY, weekdays: [.monday], count: 12)
        #expect(L.rrule(mine(1, "2026-11-23T18:00", weekly)) == "FREQ=WEEKLY;BYDAY=MO;COUNT=12")
        // 나중 회차에서 넣으면 COUNT가 어긋나므로 마지막 회차 시각을 UNTIL로
        #expect(L.rrule(mine(1, "2026-11-23T18:00", weekly), fromFirst: false) == "FREQ=WEEKLY;BYDAY=MO;UNTIL=20270208T090000Z")
        // 매월 30일 + 횟수: 2월이 빠지는 캘린더 앱과 어긋나므로 UNTIL
        let m30 = mine(1, "2026-11-30T18:00", MyRepeat(.MONTHLY, count: 4))
        #expect(L.rrule(m30) == "FREQ=MONTHLY;BYMONTHDAY=30;UNTIL=20270228T090000Z")
        // 날짜까지는 예전처럼
        #expect(L.rrule(mine(1, "2026-11-23T18:00", MyRepeat(.DAILY, until: d("2026-12-20")))) == "FREQ=DAILY;UNTIL=20261220T145959Z")
        // EventKit용 모양도 같은 뜻
        let rec = L.recurrence(mine(1, "2026-11-23T18:00", MyRepeat(.BIWEEKLY, weekdays: [.friday, .tuesday], count: 6)))!
        #expect(rec.interval == 2 && rec.weekdays == [.tuesday, .friday] && rec.end == .count(6))
    }

    @Test func insertDraftsMatchCalendarItems() {
        let a = asg(5, 50, "2026-11-27T23:59")
        let da = CalendarExport.insertDraft(a)!
        #expect(da.title == "[자료구조] 과제 1" && da.start == da.end && !da.busy && da.recurrence == nil)
        let x = CalendarExport.insertDraft(series: exam(), occurrence: nil)!
        #expect(x.busy && x.location == "301동 118호")
        let rep = mine(2, "2026-11-25T18:00", MyRepeat(.WEEKLY, weekdays: [.monday]), done: true)
        let dr = CalendarExport.insertDraft(series: rep, occurrence: nil)!
        #expect(dr.start == kst("2026-11-30T18:00"))
        #expect(dr.title == "[내 마감] 실험 보고서")
        #expect(dr.recurrence?.freq == .WEEKLY)
    }
}

struct CalendarCleanupPolicyTests {
    @Test func permissionLossKeepsMappingSoReenableDoesNotDuplicateExistingEvent() {
        let now = instant("2026-11-24T00:00:00Z")
        let it = CalItem("mine:1", "마감", now.addingTimeInterval(3600), now.addingTimeInterval(3600), "")
        var mapping: L.Mapping? = L.Mapping("7", [it.key: L.Entry("42", it.hash, it.start.epochMilli)])
        var deletes = 0
        let error = CalendarCleanupPolicy.cleanup(permitted: false, delete: { deletes += 1 }, forget: { mapping = nil })
        #expect(error != nil)
        #expect(deletes == 0)
        #expect(mapping != nil)
        let plan = L.plan(L.Desired([it], ["mine"]), mapping!.entries, ["42"], now)
        #expect(plan.ops.isEmpty)
        #expect(mapping!.entries[it.key]!.eventId == "42")
    }

    @Test func providerFailureAndPermissionRevocationKeepOwnershipAndPreventTargetSwitch() {
        let failures: [Error] = [CalendarPermissionError(), CalendarSyncError("x")]
        for failure in failures {
            var tracked = true
            var target = "7"
            let error = CalendarCleanupPolicy.switchTarget(
                cleanup: { CalendarCleanupPolicy.cleanup(permitted: true, delete: { throw failure }, forget: { tracked = false }) },
                activate: { target = "9"; return nil })
            #expect(error != nil)
            #expect(tracked)
            #expect(target == "7")
        }
    }

    @Test func retryDeletesOldTargetBeforeForgettingAndActivatingNewTarget() {
        var operations: [String] = []
        #expect(CalendarCleanupPolicy.switchTarget(
            cleanup: { CalendarCleanupPolicy.cleanup(permitted: true, delete: { operations.append("delete old") }, forget: { operations.append("forget old") }) },
            activate: { operations.append("activate new"); return nil }) == nil)
        #expect(operations == ["delete old", "forget old", "activate new"])
    }
}

// MARK: - 맞추기 순서(가짜 캘린더)

/// 메모리 캘린더. 일정 식별자는 "ev{n}".
final class FakeSyncBackend: CalendarSyncBackend, @unchecked Sendable {
    struct Ev { var calendar: String; var item: CalItem; var title: String; var start: Int64; var end: Int64; var location: String }
    var permitted = true
    var calendars: [String: (name: String, account: String)] = ["acc": ("개인", "iCloud")]
    var events: [String: Ev] = [:]
    var next = 1
    var failDelete = false

    func calendarExists(_ id: String) -> Bool { calendars[id] != nil }
    func findLocalCalendar(_ known: String?) -> String? { known.flatMap { calendars[$0] != nil ? $0 : nil } }
    func localPlacement(_ calendarId: String?) -> CalendarLocalPlacement { .device }
    func createLocalCalendar() throws -> String? { calendars["local"] = ("영민이", "나의 iPhone"); return "local" }
    func accountCalendars(excluding: String?) -> [CalendarExportInfo] {
        calendars.filter { $0.key != excluding }.map { CalendarExportInfo($0.key, $0.value.name, $0.value.account) }
    }
    func deleteCalendar(_ id: String) throws {
        if failDelete { throw CalendarSyncError("fail") }
        calendars.removeValue(forKey: id)
        events = events.filter { $0.value.calendar != id }
    }
    func wipeAppEvents(_ calendarId: String, now: Date) throws { events = events.filter { $0.value.calendar != calendarId } }
    func liveRows(_ calendarId: String, _ entries: [String: CalendarSyncLogic.Entry]) throws -> [String: CalRow] {
        var out: [String: CalRow] = [:]
        for (k, e) in entries { if let ev = events[e.eventId], ev.calendar == calendarId { out[k] = row(e.eventId, ev) } }
        return out
    }
    private func row(_ id: String, _ ev: Ev) -> CalRow { CalRow(id, ev.title, ev.start, ev.end, ev.location) }
    func readRow(_ eventId: String) -> CalRow? { events[eventId].map { row(eventId, $0) } }
    func insert(_ calendarId: String, _ item: CalItem) throws -> String? {
        let id = "ev\(next)"
        next += 1
        events[id] = Ev(calendar: calendarId, item: item, title: item.title, start: item.start.epochMilli, end: item.end.epochMilli, location: item.location)
        return id
    }
    func update(_ eventId: String, _ item: CalItem) throws -> Bool {
        guard let ev = events[eventId] else { return false }
        events[eventId] = Ev(calendar: ev.calendar, item: item, title: item.title, start: item.start.epochMilli, end: item.end.epochMilli, location: item.location)
        return true
    }
    func delete(_ eventId: String) throws { events.removeValue(forKey: eventId) }
    func deleteEvents(_ ids: [String]) throws {
        if failDelete { throw CalendarSyncError("fail") }
        for id in ids { events.removeValue(forKey: id) }
    }
    func existing(_ calendarId: String, _ ids: [String]) throws -> Set<String> { Set(ids.filter { events[$0]?.calendar == calendarId }) }

    func byKey(_ key: String) -> (String, Ev)? { events.first { $0.value.item.key == key }.map { ($0.key, $0.value) } }
}

struct CalendarExportFlowTests {
    private func make(_ list: [MyDeadline] = []) -> (CalendarExport, FakeSyncBackend, MyDeadlineStore) {
        let backend = FakeSyncBackend()
        let mineStore = MyDeadlineStore(InMemoryStore())
        mineStore.save(list)
        let export = CalendarExport(store: CalendarExportStore(InMemoryStore()), backend: backend, mine: mineStore,
                                    snapshot: SnapshotCache(InMemoryStore()), courses: CourseFilterStore(InMemoryStore()))
        return (export, backend, mineStore)
    }

    @Test func enableCreatesAppCalendarInsertsAndDisableDeletesIt() {
        let (export, backend, _) = make([mine(1, "2026-11-25T23:59"), exam()])
        #expect(export.enable(CalendarExportSettings(), now: calNow) == nil)
        #expect(backend.calendars["local"] != nil)
        #expect(backend.events.count == 2)
        #expect(export.store.lastResult()?.contains("추가 2") == true)
        // 다시 맞춰도 바뀐 것 없음
        #expect(export.syncIfEnabled(calNow)?.hasSuffix("바뀐 것 없음") == true)
        #expect(export.store.ownIdentifiers().calendars == ["local"])
        #expect(export.disable() == nil)
        #expect(backend.calendars["local"] == nil)
        #expect(export.store.loadMapping() == nil)
        #expect(!export.settings().enabled)
    }

    @Test func calendarMoveIsPulledIntoMyDeadlineAndUserDeleteIsTombstoned() {
        let (export, backend, mineStore) = make([mine(1, "2026-11-25T23:59"), mine(2, "2026-11-26T10:00", title: "독후감")])
        _ = export.enable(CalendarExportSettings(), now: calNow)
        let (id1, _) = backend.byKey("mine:1")!
        backend.events[id1]!.start = kst("2026-11-27T09:00").epochMilli
        backend.events[id1]!.end = kst("2026-11-27T09:00").epochMilli
        let (id2, _) = backend.byKey("mine:2")!
        backend.events.removeValue(forKey: id2)
        let msg = export.syncIfEnabled(calNow) ?? ""
        #expect(msg.contains("캘린더에서 고친 내 마감 1개 반영"))
        let moved = mineStore.load().first { $0.title == "실험 보고서" }!
        #expect(moved.due == kst("2026-11-27T09:00"))
        #expect(moved.id != 1)
        // 같은 일정을 새 키로 옮겼다(다시 넣지 않음). 지운 일정은 다시 만들지 않는다.
        #expect(backend.events.count == 1)
        #expect(backend.events[id1]?.item.key == CalendarSyncLogic.mineKey(moved))
        #expect(export.store.loadMapping()?.entries["mine:2"]?.tombstone == true)
        #expect(mineStore.load().contains { $0.id == 2 }, "캘린더에서 지워도 내 마감은 지우지 않는다")
    }

    @Test func permissionLossStopsExportButKeepsMapping() {
        let (export, backend, _) = make([mine(1, "2026-11-25T23:59")])
        _ = export.enable(CalendarExportSettings(), now: calNow)
        backend.permitted = false
        #expect(export.syncIfEnabled(calNow)?.contains("권한") == true)
        #expect(!export.settings().enabled)
        #expect(export.store.loadMapping() != nil)
        #expect(export.store.note() != nil)
        #expect(export.summary(showEnabled: false, assistantEnabled: false) == export.store.note())
    }

    @Test func switchingToAccountCalendarCleansAppCalendarFirst() {
        let (export, backend, _) = make([mine(1, "2026-11-25T23:59")])
        _ = export.enable(CalendarExportSettings(), now: calNow)
        backend.failDelete = true
        let next = CalendarExportSettings(enabled: true, target: .GOOGLE, googleCalendarId: "acc", googleCalendarName: "개인")
        #expect(export.switchTarget(next) != nil, "정리에 실패하면 옮기지 않는다")
        #expect(export.store.loadMapping()?.calendarId == "local")
        backend.failDelete = false
        #expect(export.switchTarget(next) == nil)
        #expect(backend.calendars["local"] == nil)
        #expect(backend.events.values.allSatisfy { $0.calendar == "acc" } && backend.events.count == 1)
        #expect(export.summary(showEnabled: true, assistantEnabled: false) == "\"개인\" 캘린더에 넣는 중 · 내 캘린더 일정 보기")
        // 계정 캘린더를 끄면 넣은 일정만 지운다(캘린더는 둔다).
        #expect(export.disable() == nil)
        #expect(backend.calendars["acc"] != nil && backend.events.isEmpty)
    }
}
