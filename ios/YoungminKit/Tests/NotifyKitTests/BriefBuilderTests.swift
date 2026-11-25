import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct BriefBuilderTests {
    /// 2026-11-19(목) 12:00 KST
    let now = instant("2026-11-19T03:00:00Z")
    let settings = NotifySettings()

    func h(_ x: Double) -> Date { now.h(x) }

    func asg(_ id: Int64, _ due: Date?, course: String? = nil, name: String? = nil, online: Bool = true, submitted: Bool = false, missing: Bool = false) -> AssignmentItem {
        AssignmentItem(1, course ?? "강의\(id)", id, name ?? "과제\(id)", due, online, nil, nil, submitted, missing)
    }

    func ann(_ id: Int64, _ hoursAgo: Double = 1, _ course: String = "강의", _ title: String? = nil) -> AnnouncementItem {
        AnnouncementItem(id, 1, course, title ?? "제목\(id)", now.h(-hoursAgo), nil, "본문")
    }

    func snap(_ a: [AssignmentItem] = [], _ n: [AnnouncementItem] = [], errors: [String] = []) -> Snapshot { Snapshot(now, a, n, errors) }

    func build(_ s: Snapshot, _ zone: TimeZone = seoul, look: Int = 24, at: Date? = nil) -> Notice {
        BriefBuilder.build(s, at ?? now, settings, zone, look)
    }

    func lines(_ s: String) -> [String] { s.components(separatedBy: "\n") }

    @Test func fixedFields() {
        let b = build(snap())
        #expect(b.channel == .BRIEF)
        #expect(b.tag == "brief")
        #expect(b.firedKeys.isEmpty)
        #expect(b.url == nil)
        #expect(b.title == "eTL 브리핑 · 11/19(목)")
    }

    @Test func allEmpty() {
        let b = build(snap())
        #expect(b.text == "급한 마감, 누락, 새 공지가 없어요")
        #expect(b.bigText == "급한 마감, 누락, 새 공지가 없어요")
    }

    @Test func allEmptyWithErrors() {
        let b = build(snap(errors: ["강의 3 조회 실패"]))
        #expect(b.text == "급한 마감, 누락, 새 공지가 없어요")
        #expect(b.bigText == "급한 마감, 누락, 새 공지가 없어요\n\n※ 일부 항목을 조회하지 못했어요")
    }

    @Test func countsAndSections() {
        let s = snap([
            asg(1, h(80), course: "자료구조", name: "HW1"), // 3일 8시간 → 7일 이내
            asg(2, h(1), submitted: true),
            asg(3, h(-30), course: "알고리즘", name: "PS2", missing: true),
        ], [ann(1, 2, "운영체제", "휴강 안내")])
        let b = build(s)
        #expect(b.text == "마감 임박 1건 · 누락 1건 · 새 공지 1건")
        let sections = b.bigText!.components(separatedBy: "\n\n")
        #expect(sections.count == 3)
        #expect(sections[0] == "■ 마감 예정 (7일 이내, 미제출)\n• 11/22(일) 20:00 · 자료구조 — HW1 (3일 8시간 남음)")
        #expect(sections[1] == "■ 누락 (마감 지남, 미제출)\n• 11/18(수) 06:00 · 알고리즘 — PS2")
        #expect(sections[2] == "■ 새 공지 (최근 24시간)\n• [운영체제] 휴강 안내")
    }

    @Test func emptySectionsOmitted() {
        let b = build(snap([], [ann(1)]))
        #expect(b.text == "마감 임박 0건 · 누락 0건 · 새 공지 1건")
        #expect(!b.bigText!.contains("마감 예정"))
        #expect(!b.bigText!.contains("누락"))
        #expect(b.bigText!.hasPrefix("■ 새 공지"))
    }

    @Test func upcomingBoundaries() {
        let list = [
            asg(1, now),                         // 정확히 now: 포함
            asg(2, now.secs(-1)),                // 지남: 제외
            asg(3, now.h(24 * 7)),               // 정확히 +7일: 포함
            asg(4, now.h(24 * 7).secs(1)),       // 제외
            asg(5, nil),                         // 마감 없음: 제외
        ]
        let b = build(snap(list))
        #expect(b.text.hasPrefix("마감 임박 2건"))
        let ls = lines(b.bigText!).filter { $0.hasPrefix("•") }
        #expect(ls.count == 2)
        #expect(ls[0].contains("과제1"))
        #expect(ls[1].contains("과제3"))
    }

    @Test func upcomingSortedAndLimited8WithRemainder() {
        let list = (Int64(1)...11).map { asg($0, h(Double(100 - $0))) } // id 클수록 마감이 빠름
        let b = build(snap(list))
        #expect(b.text.hasPrefix("마감 임박 11건"))
        let ls = lines(b.bigText!)
        #expect(ls.count == 1 + 8 + 1)
        #expect(ls.last == "외 3건")
        #expect(ls[1].contains("과제11"))
        #expect(ls[8].contains("과제4"))
    }

    @Test func todayMarkerUsesZoneDate() {
        // KST 11/19 18:00 = 오늘, 11/20 09:00 = 내일
        let ls = lines(build(snap([asg(1, h(6)), asg(2, h(21))])).bigText!).filter { $0.hasPrefix("•") }
        #expect(ls[0].hasPrefix("• [오늘] 11/19(목) 18:00"))
        #expect(ls[1].hasPrefix("• 11/20(금) 09:00"))
        #expect(!ls[1].contains("[오늘]"))
    }

    @Test func todayMarkerDiffersBetweenSeoulAndUtc() {
        // now(UTC 11/19 03:00, KST 11/19 12:00). due = UTC 11/19 16:00 = KST 11/20 01:00
        let list = [asg(1, instant("2026-11-19T16:00:00Z"))]
        let inSeoul = build(snap(list), seoul).bigText!
        let inUtc = build(snap(list), utcZone).bigText!
        #expect(!inSeoul.contains("[오늘]"))
        #expect(inUtc.contains("[오늘]"))
        // 표시 시각은 두 경우 모두 KST 고정
        #expect(inSeoul.contains("11/20(금) 01:00"))
        #expect(inUtc.contains("11/20(금) 01:00"))
    }

    @Test func titleDateFollowsZone() {
        let lateUtc = instant("2026-11-19T20:00:00Z") // KST 11/20(금) 05:00
        #expect(build(snap(), seoul, at: lateUtc).title == "eTL 브리핑 · 11/20(금)")
        #expect(build(snap(), utcZone, at: lateUtc).title == "eTL 브리핑 · 11/19(목)")
    }

    @Test func offlineMarkedInBrief() {
        #expect(lines(build(snap([asg(1, h(5), online: false)])).bigText!)[1].hasSuffix("(오프라인)"))
    }

    @Test func missingLimited5NewestFirstWithRemainder() {
        let list = (Int64(1)...7).map { asg($0, h(-Double($0)), missing: true) } // id 작을수록 최근
        let b = build(snap(list))
        #expect(b.text == "마감 임박 0건 · 누락 7건 · 새 공지 0건")
        let ls = lines(b.bigText!)
        #expect(ls.count == 1 + 5 + 1)
        #expect(ls[1].contains("과제1"))
        #expect(ls[5].contains("과제5"))
        #expect(ls.last == "외 2건")
    }

    @Test func missingWithoutDueShowsLabel() {
        #expect(build(snap([asg(1, nil, missing: true)])).bigText!.contains("• 마감일 없음 · 강의1 — 과제1"))
    }

    @Test func missingNotDoubleCountedAsUpcoming() {
        #expect(build(snap([asg(1, h(5), missing: true)])).text.hasPrefix("마감 임박 0건 · 누락 1건"))
    }

    @Test func submittedMissingFlagIgnored() {
        #expect(build(snap([asg(1, h(-5), submitted: true, missing: true)])).text == "급한 마감, 누락, 새 공지가 없어요")
    }

    @Test func announcementsLookbackBoundaryAndOrderAndLimit() {
        let list = [
            ann(1, 24),  // 경계 포함
            ann(2, 25),  // 제외
            ann(3, 2),
            ann(4, 3), ann(5, 4), ann(6, 5), ann(7, 6), ann(8, 7),
        ]
        let b = build(snap([], list))
        #expect(b.text == "마감 임박 0건 · 누락 0건 · 새 공지 7건")
        let ls = lines(b.bigText!)
        #expect(ls[0] == "■ 새 공지 (최근 24시간)")
        #expect(ls.count == 1 + 5 + 1)
        #expect(ls[1] == "• [강의] 제목3")
        #expect(ls.last == "외 2건")
    }

    @Test func lookbackHoursUsedInHeader() {
        let b = build(snap([], [ann(1, 30)]), look: 48)
        #expect(b.bigText!.hasPrefix("■ 새 공지 (최근 48시간)"))
        #expect(b.text.hasSuffix("새 공지 1건"))
    }

    @Test func errorsLineLast() {
        let b = build(snap([asg(1, h(5))], errors: ["x"]))
        #expect(b.bigText!.hasSuffix("\n\n※ 일부 항목을 조회하지 못했어요"))
        #expect(!b.bigText!.contains("x 조회"))
    }

    @Test func sanitizedAndTruncated() {
        let evil = "A\nB\u{0000}C"
        let long = String(repeating: "나", count: 300)
        let b = build(snap([asg(1, h(5), course: evil, name: long)], [ann(1, 1, evil, "t\r\nu")]))
        let big = b.bigText!
        #expect(big.contains("A B C"))
        #expect(big.contains("[A B C] t u"))
        #expect(!big.unicodeScalars.contains("\u{0000}"))
        #expect(!big.unicodeScalars.contains("\r"))
        for l in lines(big) { #expect(l.utf16.count <= 140) }
        #expect(big.contains("…"))
        // 줄바꿈은 구조의 \n 뿐: 섹션 2 + 줄 2 -> 빈 줄 하나 포함 총 5줄
        #expect(lines(big).count == 5)
    }

    @Test func emptyInputsDoNotThrow() {
        _ = build(Snapshot(now, [], []), look: 0)
        _ = build(snap([asg(1, now), asg(2, now)]))
    }

    @Test func staleSinceReplacesErrorLine() {
        let b = BriefBuilder.build(snap([asg(1, h(5))], errors: ["eTL 연결 실패"]), now, settings, seoul, 24, staleSince: now.h(-3))
        #expect(b.bigText!.hasSuffix("\n\n※ eTL에서 지금 받지 못한 과제는 3시간 전 받아 둔 정보예요"))
        #expect(!b.bigText!.contains("일부 항목"))
        #expect(b.text == "마감 임박 1건 · 누락 0건 · 새 공지 0건")
    }
}
