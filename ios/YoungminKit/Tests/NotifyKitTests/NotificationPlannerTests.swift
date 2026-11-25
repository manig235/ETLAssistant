import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct NotificationPlannerTests {
    /// 2026-11-19(목) 12:00 KST
    let now = instant("2026-11-19T03:00:00Z")
    /// 방해 금지 23:00~07:00이 켜진 상태. 시점은 3일, 24시간, 3시간, 1시간 전으로 고정.
    let settings = NotifySettings(deadlineWindowsMinutes: [4320, 1440, 180, 60], quietEnabled: true)

    func asg(_ id: Int64, _ due: Date?, course: String? = nil, name: String? = nil, cid: Int64 = 1, online: Bool = true,
             submitted: Bool = false, missing: Bool = false, url: String?? = .none) -> AssignmentItem {
        let u: String? = url ?? "https://myetl.snu.ac.kr/courses/\(cid)/assignments/\(id)"
        return AssignmentItem(cid, course ?? "강의\(id)", id, name ?? "과제\(id)", due, online, nil, u, submitted, missing)
    }

    func ann(_ id: Int64, hoursAgo: Double = 1, course: String = "강의", title: String? = nil, text: String? = nil, url: String?? = .none) -> AnnouncementItem {
        let u: String? = url ?? "https://myetl.snu.ac.kr/a/\(id)"
        return AnnouncementItem(id, 1, course, title ?? "제목\(id)", now.h(-hoursAgo), u, text ?? "본문\(id)")
    }

    func snap(_ a: [AssignmentItem] = [], _ n: [AnnouncementItem] = []) -> Snapshot { Snapshot(now, a, n) }
    func state(_ done: Bool = true, _ keys: String...) -> NotifyState {
        NotifyState(fired: Dictionary(uniqueKeysWithValues: keys.map { ($0, Int64(0)) }), announcementBaselineDone: done)
    }

    func plan(_ s: Snapshot, _ st: NotifyState? = nil, set: NotifySettings? = nil, at: Date? = nil) -> Plan {
        NotificationPlanner.plan(s, st ?? state(), at ?? now, set ?? settings, seoul)
    }

    func hours(_ h: Double) -> Date { now.h(h) }
    func lines(_ s: String) -> [String] { s.components(separatedBy: "\n") }
    func with(_ f: (inout NotifySettings) -> Void) -> NotifySettings { var s = settings; f(&s); return s }

    // ---- 마감: 경계 ----------------------------------------------------------

    @Test func exactlyWindowBoundaryFires() {
        let p = plan(snap([asg(1, hours(24))]))
        #expect(p.notices.count == 1)
        #expect(p.notices[0].firedKeys.first == "deadline-m:1:1:1440")
    }

    @Test func oneSecondBeyondWindowFiresNextWindow() {
        let p = plan(snap([asg(1, hours(24).secs(1))]))
        #expect(p.notices.count == 1)
        #expect(p.notices[0].firedKeys == ["deadline-m:1:1:4320"])
    }

    @Test func beyondMaxWindowDoesNothing() {
        #expect(plan(snap([asg(1, hours(72).secs(1))])).notices.isEmpty)
        #expect(plan(snap([asg(1, hours(72))])).notices.count == 1)
    }

    @Test func pastDueNowAndNullDueAreIgnored() {
        #expect(plan(snap([asg(1, now), asg(2, now.secs(-1)), asg(3, nil)])).notices.isEmpty)
    }

    @Test func submittedAndMissingAreIgnored() {
        #expect(plan(snap([asg(1, hours(5), submitted: true), asg(2, hours(5), missing: true)])).notices.isEmpty)
    }

    @Test func oneSecondFromNowFires() {
        #expect(plan(snap([asg(1, now.secs(1))])).notices.count == 1)
    }

    // ---- 마감: 키 --------------------------------------------------------------

    @Test func firstCheckInside24hRecordsLooserKeysToo() {
        let n = plan(snap([asg(7, hours(10), cid: 3)])).notices
        #expect(n.count == 1)
        #expect(Set(n[0].firedKeys) == ["deadline-m:3:7:1440", "deadline-m:3:7:4320"])
    }

    @Test func firedKeyIsSkipped() {
        #expect(plan(snap([asg(1, hours(10))]), state(true, "deadline-m:1:1:1440")).notices.isEmpty)
    }

    @Test func after72hFiredThen24hStillFires() {
        let n = plan(snap([asg(1, hours(10))]), state(true, "deadline-m:1:1:4320")).notices
        #expect(n.count == 1)
        #expect(n[0].firedKeys.first == "deadline-m:1:1:1440")
    }

    @Test func duplicateAssignmentsInSnapshotCountOnce() {
        let a = asg(1, hours(10))
        let n = plan(snap([a, a])).notices
        #expect(n.count == 1)
        #expect(n[0].tag == "deadline:1:1")
    }

    @Test func planDoesNotMutateState() {
        let st = state(false, "x")
        _ = plan(snap([asg(1, hours(5))], [ann(1)]), st)
        #expect(Set(st.fired.keys) == ["x"])
        #expect(!st.announcementBaselineDone)
    }

    @Test func deadlineAlertsOffAndEmptyWindows() {
        let list = [asg(1, hours(5))]
        #expect(plan(snap(list), set: with { $0.deadlineAlerts = false }).notices.isEmpty)
        #expect(plan(snap(list), set: with { $0.deadlineWindowsMinutes = [] }).notices.isEmpty)
    }

    @Test func customWindowsUnsortedWork() {
        let n = plan(snap([asg(1, hours(10))]), set: with { $0.deadlineWindowsMinutes = [360, 2880, 720] }).notices
        #expect(Set(n[0].firedKeys) == ["deadline-m:1:1:720", "deadline-m:1:1:2880"])
    }

    // ---- 마감: 묶음/단일/정렬 -----------------------------------------------------

    @Test func singleHasSpecificTitleTagAndUrl() {
        let ns = plan(snap([asg(5, hours(30), course: "자료구조", name: "HW1")])).notices
        #expect(ns.count == 1)
        let n = ns[0]
        #expect(n.channel == .DEADLINE)
        #expect(n.title == "마감 임박 · 자료구조")
        #expect(n.tag == "deadline:1:5")
        #expect(n.url == "https://myetl.snu.ac.kr/courses/1/assignments/5")
        #expect(n.text.contains("HW1"))
        #expect(n.bigText!.hasPrefix("• 11/20(금) 18:00 · 자료구조 — HW1 (1일 6시간 남음)"))
    }

    @Test func singleRestoresMissingOrUnsafeAssignmentLinksFromIds() {
        for url: String? in [nil, "", "https://evil.example.com/x", "http://myetl.snu.ac.kr/x",
                             "https://myetl.snu.ac.kr.evil.example/x", "https://myetl.snu.ac.kr/x\n" + "bad"] {
            let ns = plan(snap([asg(5, hours(30), url: .some(url))])).notices
            #expect(ns.count == 1)
            #expect(ns[0].url == "https://myetl.snu.ac.kr/courses/1/assignments/5")
        }
    }

    @Test func sameWindowGroupedInto1Notice() {
        let n = plan(snap([asg(1, hours(20)), asg(2, hours(10)), asg(3, hours(15))])).notices
        #expect(n.count == 1)
        let g = n[0]
        #expect(g.title == "마감 임박 3건 · 24시간 이내")
        #expect(g.tag == "deadline:wm1440")
        #expect(g.url == "https://myetl.snu.ac.kr/courses/1")
        let ls = lines(g.bigText!)
        #expect(ls.count == 3)
        // 마감 오름차순: 10h(2), 15h(3), 20h(1)
        #expect(ls[0].contains("과제2"))
        #expect(ls[1].contains("과제3"))
        #expect(ls[2].contains("과제1"))
        #expect(g.text.contains("과제2") && g.text.hasSuffix("외 2건"))
        #expect(g.firedKeys.filter { $0.hasSuffix(":1440") }.count == 3)
        #expect(g.firedKeys.filter { $0.hasSuffix(":4320") }.count == 3)
    }

    @Test func window72LabelIs3Days() {
        let n = plan(snap([asg(1, hours(50)), asg(2, hours(60))])).notices
        #expect(n.count == 1)
        #expect(n[0].title == "마감 임박 2건 · 3일 이내")
        #expect(n[0].tag == "deadline:wm4320")
    }

    @Test func differentWindowsGiveSeparateNoticesTightestFirst() {
        let n = plan(snap([asg(1, hours(60)), asg(2, hours(5)), asg(3, hours(70)), asg(4, hours(6))])).notices
        #expect(n.count == 2)
        #expect(n[0].tag == "deadline:wm1440")
        #expect(n[1].tag == "deadline:wm4320")
        #expect(n[0].title == "마감 임박 2건 · 24시간 이내")
    }

    @Test func bigTextLimitedTo6LinesWithRemainder() {
        let list = (Int64(1)...9).map { asg($0, hours(Double($0))) }
        // 묶음을 보려는 시험이라 모두 같은 창(24시간)에 들어가게 창을 고정한다.
        let n = plan(snap(list), set: with { $0.deadlineWindowsMinutes = [4320, 1440] }).notices
        #expect(n.count == 1)
        let ls = lines(n[0].bigText!)
        #expect(ls.count == 7)
        #expect(ls.last == "외 3건")
        #expect(ls.filter { $0.hasPrefix("• ") }.count == 6)
        #expect(n[0].title.hasPrefix("마감 임박 9건"))
    }

    @Test func exactlySixLinesHasNoRemainder() {
        let list = (Int64(1)...6).map { asg($0, hours(Double($0))) }
        let ls = lines(plan(snap(list), set: with { $0.deadlineWindowsMinutes = [4320, 1440] }).notices[0].bigText!)
        #expect(ls.count == 6)
        #expect(!ls.contains { $0.hasPrefix("외") })
    }

    @Test func offlineMarked() {
        let n = plan(snap([asg(1, hours(5), online: false)])).notices[0]
        #expect(n.bigText!.hasSuffix("(오프라인)"))
        #expect(n.text.hasSuffix("(오프라인)"))
        let online = plan(snap([asg(1, hours(5))])).notices[0]
        #expect(!online.bigText!.contains("오프라인"))
    }

    @Test func sameDueTimeIsStableAndDoesNotThrow() {
        let g = plan(snap([asg(2, hours(5), course: "B"), asg(1, hours(5), course: "A")])).notices
        #expect(g.count == 1)
        #expect(lines(g[0].bigText!)[0].contains("A —"))
    }

    @Test func emptySnapshotIsEmptyPlan() {
        let p = plan(snap())
        #expect(p.notices.isEmpty)
        #expect(p.silentKeys.isEmpty)
    }

    // ---- 방해 금지 ----------------------------------------------------------------

    @Test func quietHoursProduceNothingAndNoKeys() {
        let quiet = instant("2026-11-19T14:30:00Z") // 23:30 KST
        let p = plan(snap([asg(1, hours(5))], [ann(1)]), state(true), at: quiet)
        #expect(p.notices.isEmpty)
        #expect(p.silentKeys.isEmpty)
        #expect(!p.markBaselineDone)
    }

    @Test func quietAfterMidnightAndBoundaries() {
        let s = snap([asg(1, hours(30))])
        // 03:00 KST = 18:00Z: 조용
        #expect(plan(s, at: instant("2026-11-18T18:00:00Z")).notices.isEmpty)
        // 06:59 KST 조용 / 07:00 KST 해제
        #expect(plan(s, at: instant("2026-11-18T21:59:00Z")).notices.isEmpty)
        #expect(plan(s, at: instant("2026-11-18T22:00:00Z")).notices.count == 1)
        // 22:59 KST 해제 / 23:00 KST 조용
        #expect(plan(s, at: instant("2026-11-19T13:59:00Z")).notices.count == 1)
        #expect(plan(s, at: instant("2026-11-19T14:00:00Z")).notices.isEmpty)
    }

    @Test func quietUsesGivenZoneNotUtc() {
        let s = snap([asg(1, hours(30))])
        let at = instant("2026-11-19T03:00:00Z") // KST 12:00(낮), UTC 03:00(조용)
        #expect(NotificationPlanner.plan(s, state(), at, settings, seoul).notices.count == 1)
        #expect(NotificationPlanner.plan(s, state(), at, settings, utcZone).notices.isEmpty)
    }

    @Test func quietDisabledAllowsAlerts() {
        let quiet = instant("2026-11-19T14:30:00Z")
        let s = snap([asg(1, quiet.secs(3600))])
        #expect(plan(s, set: with { $0.quietEnabled = false }, at: quiet).notices.count == 1)
    }

    @Test func baselineRecordedEvenInQuietHours() {
        let quiet = instant("2026-11-19T14:30:00Z")
        let p = plan(snap([], [ann(1), ann(2)]), state(false), at: quiet)
        #expect(p.notices.isEmpty)
        #expect(p.silentKeys == ["ann:1", "ann:2"])
        #expect(p.markBaselineDone)
    }

    // ---- 공지 --------------------------------------------------------------------

    @Test func baselineFirstRunIsSilent() {
        let p = plan(snap([asg(1, hours(5))], [ann(1), ann(2), ann(3), ann(4, hoursAgo: 100)]), state(false))
        #expect(Set(p.silentKeys) == ["ann:1", "ann:2", "ann:3", "ann:4"])
        #expect(p.markBaselineDone)
        // 마감 알림은 기준선과 무관하게 나온다
        #expect(p.notices.count == 1)
        #expect(p.notices[0].channel == .DEADLINE)
    }

    @Test func baselineAlreadyDoneNotMarkedAgain() {
        let p = plan(snap([], [ann(1)]), state(true))
        #expect(!p.markBaselineDone)
        #expect(p.silentKeys.isEmpty)
        #expect(p.notices.count == 1)
    }

    @Test func announcementAlertsOffMeansNoBaseline() {
        let p = plan(snap([], [ann(1)]), state(false), set: with { $0.announcementAlerts = false })
        #expect(p.notices.isEmpty)
        #expect(p.silentKeys.isEmpty)
        #expect(!p.markBaselineDone)
    }

    @Test func firedAnnouncementsExcluded() {
        let p = plan(snap([], [ann(1), ann(2)]), state(true, "ann:1"))
        #expect(p.notices.count == 1)
        #expect(p.notices[0].tag == "ann:2")
    }

    @Test func threeAnnouncementsAreIndividualNewestFirst() {
        let n = plan(snap([], [ann(1, hoursAgo: 5), ann(2, hoursAgo: 1), ann(3, hoursAgo: 3)])).notices
        #expect(n.count == 3)
        #expect(n.map(\.tag) == ["ann:2", "ann:3", "ann:1"])
        for x in n {
            #expect(x.channel == .ANNOUNCEMENT)
            #expect(x.firedKeys == [x.tag])
        }
        #expect(n[0].title == "강의 · 새 공지")
        #expect(n[0].text == "제목2")
        #expect(n[0].bigText == "본문2")
        #expect(n[0].url == "https://myetl.snu.ac.kr/a/2")
    }

    @Test func fourAnnouncementsBecomeOneSummary() {
        let list = (Int64(1)...4).map { ann($0, hoursAgo: Double($0), course: "C\($0)") }
        let ns = plan(snap([], list)).notices
        #expect(ns.count == 1)
        let n = ns[0]
        #expect(n.title == "새 공지 4건")
        #expect(n.tag == "ann:summary")
        #expect(n.url == "https://myetl.snu.ac.kr/courses/1")
        #expect(Set(n.firedKeys) == ["ann:1", "ann:2", "ann:3", "ann:4"])
        #expect(lines(n.bigText!).count == 4)
        #expect(lines(n.bigText!)[0] == "• [C1] 제목1")
    }

    @Test func summaryLimitedTo8Lines() {
        let list = (Int64(1)...11).map { ann($0, hoursAgo: Double($0)) }
        let n = plan(snap([], list)).notices[0]
        let ls = lines(n.bigText!)
        #expect(ls.count == 9)
        #expect(ls.last == "외 3건")
        #expect(n.firedKeys.count == 11)
    }

    @Test func announcementWithoutBodyHasNullBigText() {
        let ns = plan(snap([], [ann(1, text: "  \n ")]), state()).notices
        #expect(ns.count == 1)
        #expect(ns[0].bigText == nil)
    }

    @Test func announcementBodyCutTo300() {
        let n = plan(snap([], [ann(1, text: String(repeating: "가", count: 700))])).notices[0]
        #expect(n.bigText!.utf16.count == 300)
        #expect(n.bigText!.hasSuffix("…"))
    }

    @Test func announcementForeignOrMissingUrlRestoredFromIds() {
        for url: String? in [nil, "https://myetl.snu.ac.kr.evil.com/x"] {
            let ns = plan(snap([], [ann(1, url: .some(url))])).notices
            #expect(ns.count == 1)
            #expect(ns[0].url == "https://myetl.snu.ac.kr/courses/1/discussion_topics/1")
        }
    }

    @Test func summaryLinksUseCourseOrHomeButNeverSendPersonalDeadlinesToEtl() {
        let acrossCourses = plan(snap([asg(1, hours(20)), asg(2, hours(20), cid: 2)])).notices
        #expect(acrossCourses.count == 1 && acrossCourses[0].url == "https://myetl.snu.ac.kr/")
        let personal = [asg(1, hours(20), cid: -1, url: .some(nil)), asg(2, hours(20), cid: -1, url: .some(nil))]
        #expect(plan(snap(personal)).notices[0].url == nil)
        #expect(plan(snap(Array(personal.prefix(1)))).notices[0].url == nil)
        let announcements = (Int64(1)...4).map { i -> AnnouncementItem in var a = ann(i); a.courseId = i; return a }
        #expect(plan(snap([], announcements)).notices[0].url == "https://myetl.snu.ac.kr/")
    }

    @Test func duplicateAnnouncementIdsCountOnce() {
        #expect(plan(snap([], [ann(1), ann(1)])).notices.count == 1)
    }

    // ---- 안전 --------------------------------------------------------------------

    @Test func controlCharsAndNewlinesAreSanitized() {
        let evil = "A\nB\r\nC\u{0000}D\u{0007}E\t\tF\u{202E}evil G"
        let ns = plan(snap([asg(1, hours(5), course: evil, name: evil)])).notices
        #expect(ns.count == 1)
        let n = ns[0]
        #expect(n.title == "마감 임박 · A B C D E Fevil G")
        #expect(!n.text.contains("\n"))
        #expect(lines(n.bigText!).count == 1)
        #expect(!n.bigText!.unicodeScalars.contains { $0 == "\u{0000}" || $0 == "\u{202E}" || $0 == "\r" })
    }

    @Test func longStringsAreTruncated() {
        let long = String(repeating: "가", count: 500)
        let n = plan(snap([asg(1, hours(5), course: long, name: long)])).notices[0]
        #expect(n.title.utf16.count <= 80)
        #expect(n.title.hasSuffix("…"))
        #expect(n.text.utf16.count <= 140)
        #expect(n.bigText!.utf16.count <= 140)
        let a = plan(snap([], [ann(1, course: long, title: long)])).notices[0]
        #expect(a.title.utf16.count <= 80)
        #expect(a.text.utf16.count <= 140)
        #expect(a.text.hasSuffix("…"))
    }

    @Test func truncationDoesNotSplitSurrogatePairs() {
        let emoji = String(repeating: "😀", count: 100)
        let n = plan(snap([], [ann(1, title: emoji)])).notices[0]
        let u = Array(n.text.utf16)
        #expect(u.count <= 140)
        #expect(!UTF16.isLeadSurrogate(u[u.count - 2]))
    }

    @Test func groupLinesStayWithinLimit() {
        let long = String(repeating: "x", count: 400)
        let g = plan(snap([asg(1, hours(5), course: long, name: long), asg(2, hours(6), course: long, name: long, online: false)])).notices[0]
        for l in lines(g.bigText!) { #expect(l.utf16.count <= 140) }
        #expect(g.text.utf16.count <= 140)
    }

    @Test func summaryTitlesSanitized() {
        let list = (Int64(1)...5).map { ann($0, title: "a\nb\($0)") }
        let n = plan(snap([], list)).notices[0]
        #expect(lines(n.bigText!).count == 5)
        #expect(!n.text.contains("\n"))
    }

    // ---- 다음 확인 시각(정확한 시각 알람) ----------------------------------------

    func next(_ s: Snapshot, _ st: NotifyState? = nil, _ set: NotifySettings? = nil) -> Date? {
        NotificationPlanner.nextDeadlineCheckAt(s, st ?? state(), now, set ?? settings)
    }

    @Test func defaultWindowsAre3d24h3h10m() {
        #expect(NotifySettings().deadlineWindowsMinutes == [4320, 1440, 180, 10])
    }

    @Test func nextCheckIsEarliestUnfiredWindow() {
        // 마감 5시간 뒤: 3시간 전(2시간 뒤)이 가장 이르다. 72·24시간 창은 이미 지났다.
        #expect(next(snap([asg(1, hours(5))])) == hours(2))
        // 3시간 창을 띄웠으면 1시간 전(4시간 뒤)
        #expect(next(snap([asg(1, hours(5))]), state(true, "deadline-m:1:1:180")) == hours(4))
    }

    @Test func nextCheckAcrossAssignments() {
        // 과제2의 1시간 전(1시간 뒤)이 과제1의 24시간 전(6시간 뒤)보다 이르다.
        #expect(next(snap([asg(1, hours(30)), asg(2, hours(2))])) == hours(1))
    }

    @Test func nextCheckIgnoresSubmittedPastAndAllFired() {
        #expect(next(snap([asg(1, hours(5), submitted: true)])) == nil)
        #expect(next(snap([asg(1, hours(-1))])) == nil)
        #expect(next(snap([asg(1, hours(5))]), state(true, "deadline-m:1:1:180", "deadline-m:1:1:60")) == nil)
        #expect(next(snap([asg(1, hours(5))]), nil, with { $0.deadlineAlerts = false }) == nil)
    }

    @Test func oneHourWindowFiresAtBoundary() {
        let p = plan(snap([asg(1, hours(1))]))
        #expect(p.notices.count == 1)
        #expect(p.notices[0].firedKeys.first == "deadline-m:1:1:60")
        #expect(Set(p.notices[0].firedKeys).isSuperset(of: ["deadline-m:1:1:180", "deadline-m:1:1:1440", "deadline-m:1:1:4320"]))
    }

    @Test func normalizeWindowsKeepsCustomValuesInRange() {
        // 프리셋에 없는 값(30분, 90분)도 남는다. 범위(10분~14일) 밖과 중복은 버린다.
        #expect(NotifySettings.normalizeWindows([30, 4320, 5, 90, 30, 20_161]) == [4320, 90, 30])
        #expect(NotifySettings.normalizeWindows([5]) == NotifySettings.DEFAULT_DEADLINE_WINDOWS)
        #expect(NotifySettings.normalizeWindows(Array(10...40)).count == NotifySettings.MAX_WINDOWS)
    }

    @Test func windowLabels() {
        #expect(NotifySettings.windowLabel(30) == "30분")
        #expect(NotifySettings.windowLabel(60) == "1시간")
        #expect(NotifySettings.windowLabel(90) == "1시간 30분")
        #expect(NotifySettings.windowLabel(1440) == "24시간")
        #expect(NotifySettings.windowLabel(2880) == "2일")
        #expect(NotifySettings.windowLabel(2160) == "36시간")
        #expect(NotifySettings.windowLabel(10080) == "7일")
    }

    @Test func customThirtyMinuteWindowFiresAndSchedules() {
        let set = with { $0.deadlineWindowsMinutes = [1440, 30] }
        // 마감 40분 전: 30분 창은 아직, 24시간 창에 해당
        #expect(plan(snap([asg(1, now.secs(40 * 60))]), set: set).notices.map(\.firedKeys) == [["deadline-m:1:1:1440"]])
        // 24시간 창은 이미 알렸으면 다음 확인은 30분 전(10분 뒤)
        let st = state(true, "deadline-m:1:1:1440")
        #expect(next(snap([asg(1, now.secs(40 * 60))]), st, set) == now.secs(10 * 60))
        // 마감 30분 전: 30분 창
        let p = plan(snap([asg(1, now.secs(30 * 60))]), st, set: set)
        #expect(p.notices.count == 1)
        #expect(p.notices[0].firedKeys.first == "deadline-m:1:1:30")
        #expect(!p.notices[0].title.isEmpty)
    }

    @Test func legacyHourKeysCountAsFired() {
        // 업데이트 전(시간 단위 키)에 24시간 알림을 띄웠으면 다시 띄우지 않는다.
        let st = state(true, "deadline:1:1:24")
        #expect(plan(snap([asg(1, hours(20))]), st).notices.isEmpty)
        #expect(next(snap([asg(1, hours(20))]), st) == hours(17))
    }

    @Test func groupTitleUsesMinuteLabel() {
        let set = with { $0.deadlineWindowsMinutes = [90] }
        let list = [asg(1, now.secs(80 * 60)), asg(2, now.secs(85 * 60))]
        #expect(plan(snap(list), set: set).notices[0].title.contains("1시간 30분 이내"))
    }

    // ---- 시험 공지 -------------------------------------------------------------

    @Test func examAnnouncementGetsBadgeAndRegisterDraft() {
        let ns = plan(snap([], [ann(1, course: "자료구조", title: "중간고사 안내", text: "10/20 10시, 301동 102호")])).notices
        #expect(ns.count == 1)
        let n = ns[0]
        #expect(n.title == "자료구조 · 시험 공지")
        // 버튼은 "내 일정에 등록" 하나.
        #expect(n.prefillAction == nil)
        let dr = ScheduleDraft.fromJson(n.draft)!
        #expect(dr.kind == .EXAM)
        #expect(dr.announcementId == 1)
        #expect(dr.prefill == "자료구조 ‘중간고사 안내’ 공지 보고 시험 일정(날짜·시간·장소·준비물) 내 일정에 넣어줘 (공지 ID 1)")
        // 본문 누르기는 그대로 eTL 원문
        #expect(n.url == "https://myetl.snu.ac.kr/a/1")
        let plain = plan(snap([], [ann(2)])).notices[0]
        #expect(plain.draft == nil)
        #expect(plain.prefillAction == nil)
    }

    @Test func summaryDraftUsesFirstExamOrAssignment() {
        let anns = [ann(1, title: "기말고사 장소"), ann(2, title: "과제 안내"), ann(3, title: "Quiz 3"), ann(4, title: "중간고사 일정")]
        let n = plan(snap([], anns)).notices[0]
        #expect(n.bigText!.contains("(시험 공지)"))
        #expect(n.bigText!.contains("(과제 공지)"))
        #expect(ScheduleDraft.fromJson(n.draft) != nil)
        #expect(n.prefillAction == nil)
    }

    @Test func assignmentAnnouncementGetsBadgeAndDeadlineDraft() {
        let n = plan(snap([], [ann(5, course: "회로이론", title: "[회로이론] 과제 3 안내", text: "11월 28일(금) 23:59까지 제출하세요")])).notices[0]
        #expect(n.title == "회로이론 · 과제 공지")
        let dr = ScheduleDraft.fromJson(n.draft)!
        #expect(dr.kind == .DEADLINE)
        #expect(dr.title == "과제 3 안내")
        #expect(dr.prefill.contains("과제 마감(날짜·시간·제출 방법)"))
    }

    @Test func examAnnouncementShowsSuppliesLine() {
        let n = plan(snap([], [ann(6, course: "물리", title: "중간고사 안내", text: "일시: 11월 30일 10:00\n준비물: 공학용 계산기, 신분증\n범위: 1~5장")])).notices[0]
        #expect(n.bigText!.hasPrefix("준비물·지참: 준비물: 공학용 계산기, 신분증"))
    }
}
