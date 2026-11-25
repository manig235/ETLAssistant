import Foundation
import Testing
import ETLKit
@testable import NotifyKit

@Suite struct NotifyScheduleTests {
    func at(_ h: Int, _ m: Int, _ s: Int = 0, _ zone: TimeZone = seoul) -> Date { ZoneClock.instant(DayDate.of(2026, 3, 10), h, m, s, zone) }
    let min: Int64 = 60_000
    let hour: Int64 = 3_600_000
    func delay(_ now: Date, _ minute: Int, _ zone: TimeZone = seoul) -> Int64 { NotifySchedule.nextOccurrenceDelayMillis(now, zone, minute) }

    @Test func todayWhenNotYetPassed() { #expect(delay(at(6, 0), 8 * 60) == 2 * hour) }

    @Test func tomorrowWhenAlreadyPassed() { #expect(delay(at(10, 0), 8 * 60) == 22 * hour) }

    @Test func exactSameMinuteGoesToTomorrow() { #expect(delay(at(8, 0), 8 * 60) == 24 * hour) }

    @Test func minLeadBoundary() {
        // 정확히 minLead만큼 남으면 오늘, 1초라도 모자라면 내일
        #expect(delay(at(7, 59), 8 * 60) == min)
        #expect(delay(at(7, 59, 1), 8 * 60) == 24 * hour + 59_000)
    }

    @Test func justAfterRunDoesNotRepickSameSlot() {
        // 08:00:30에 실행된 브리핑 다음 예약은 내일 08:00
        #expect(delay(at(8, 0, 30), 8 * 60) == 24 * hour - 30_000)
    }

    @Test func nearMidnight() {
        #expect(delay(at(23, 0), 0) == 1 * hour)
        // 23:59 기준 00:00은 1분 뒤(minLead와 같음)
        #expect(delay(at(23, 59), 0) == min)
    }

    @Test func twoSlotsPickIndependently() {
        let now = at(12, 0)
        #expect(delay(now, 8 * 60) == 20 * hour)
        #expect(delay(now, 20 * 60) == 8 * hour)
    }

    @Test func springForwardNonexistentTime() {
        // America/New_York 2026-03-08 02:00 -> 03:00. 02:30은 존재하지 않아 03:30(EDT)이 된다.
        let now = ZoneClock.instant(DayDate.of(2026, 3, 8), 0, 0, 0, newYork)
        let dl = delay(now, 2 * 60 + 30, newYork)
        let target = now.addingTimeInterval(Double(dl) / 1000)
        #expect(ZoneClock.hour(target, newYork) == 3)
        #expect(ZoneClock.minute(target, newYork) == 30)
        #expect(dl == 2 * hour + 30 * min) // 실제 경과는 2시간 30분
    }

    @Test func springForwardDayIsShort() {
        // 전환 전날 09:00(EST) -> 전환일 09:00(EDT)은 23시간 뒤
        #expect(delay(ZoneClock.instant(DayDate.of(2026, 3, 7), 9, 0, 0, newYork), 9 * 60, newYork) == 23 * hour)
        // 전환일 09:00(EDT) -> 다음 날 09:00은 24시간 뒤
        #expect(delay(ZoneClock.instant(DayDate.of(2026, 3, 8), 9, 0, 0, newYork), 9 * 60, newYork) == 24 * hour)
    }

    @Test func fallBackDayIsLong() {
        // 2026-11-01 01:00~02:00가 두 번. 00:00 기준 다음 08:00은 9시간 뒤(25시간짜리 하루 안)
        #expect(delay(ZoneClock.instant(DayDate.of(2026, 11, 1), 0, 0, 0, newYork), 8 * 60, newYork) == 9 * hour)
    }

    @Test func isStaleBoundary() {
        let sched: Int64 = 1_000_000
        #expect(!NotifySchedule.isStale(sched, sched))
        #expect(!NotifySchedule.isStale(sched, sched + NotifySchedule.MAX_LATE_MILLIS))
        #expect(NotifySchedule.isStale(sched, sched + NotifySchedule.MAX_LATE_MILLIS + 1))
        #expect(!NotifySchedule.isStale(sched, sched - 5000)) // 일찍 실행돼도 stale 아님
    }

    @Test func flexMillis() {
        #expect(NotifySchedule.flexMillis(1) == 15 * min)
        #expect(NotifySchedule.flexMillis(3) == 45 * min)
        #expect(NotifySchedule.flexMillis(12) == 3 * hour)
    }

    @Test func notificationIdNonNegativeAndStable() {
        for t in ["auth", "brief", "deadline:1:2:24", "polygenelubricants", ""] {
            #expect(NotifySchedule.notificationId(t) >= 0)
            #expect(NotifySchedule.notificationId(t) == NotifySchedule.notificationId(t))
        }
        // "Aa".hashCode() == 2112
        #expect(NotifySchedule.notificationId("Aa") == 2112)
        // "polygenelubricants".hashCode() == Int.MIN_VALUE → 0
        #expect(NotifySchedule.javaHash("polygenelubricants") == Int32.min)
        #expect(NotifySchedule.notificationId("polygenelubricants") == 0)
    }

    @Test func authFailureThrottle() {
        let now = 100 * hour
        #expect(NotifySchedule.authFailureDue(nil, now))
        #expect(!NotifySchedule.authFailureDue(now - 23 * hour, now))
        #expect(NotifySchedule.authFailureDue(now - 24 * hour, now))
    }

    @Test func oneAlarmTargetsTheReminderWithoutIntermediateWakes() {
        for left in [1_000, min, 90 * min, 3 * 24 * hour] {
            #expect(NotifySchedule.nextAlarm(1_000, 1_000 + left, true) == NotifySchedule.AlarmRequest(1_000 + left, true))
            #expect(NotifySchedule.nextAlarm(1_000, 1_000 + left, false) == NotifySchedule.AlarmRequest(1_000 + left, false))
        }
    }

    @Test func overdueAlarmIsRequestedNowWithEitherAccessState() {
        #expect(NotifySchedule.nextAlarm(2_000, 1_000, true) == NotifySchedule.AlarmRequest(2_000, true))
        #expect(NotifySchedule.nextAlarm(2_000, 1_000, false) == NotifySchedule.AlarmRequest(2_000, false))
    }

    @Test func rearmAfterRebootOrTimeChange() {
        #expect(NotifySchedule.rearmAction(0, 1_000) == .NONE)
        #expect(NotifySchedule.rearmAction(2_000, 1_000) == .ARM)
        // 꺼져 있던 사이 목표가 지났으면 바로 확인한다
        #expect(NotifySchedule.rearmAction(1_000, 1_000) == .FIRE_NOW)
        #expect(NotifySchedule.rearmAction(500, 1_000) == .FIRE_NOW)
    }

    // ---- 브리핑 예약 유지 ----

    func ms(_ d: Date) -> Int64 { d.epochMilli }

    @Test func keepBriefWhenBriefJobColdStartsProcess() {
        #expect(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(8, 0, 5)), seoul))
        #expect(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(9, 30)), seoul))
        #expect(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(6, 0)), seoul))
    }

    @Test func rescheduleBriefWhenMissingChangedOrTooOld() {
        #expect(!NotifySchedule.keepBriefTarget(nil, 8 * 60, ms(at(6, 0)), seoul))
        // 시각 설정이 바뀌었다(08:00 → 09:00).
        #expect(!NotifySchedule.keepBriefTarget(ms(at(8, 0)), 9 * 60, ms(at(6, 0)), seoul))
        // 4시간 넘게 지나 보낼 수 없는 예약은 다음 것으로 바꾼다.
        #expect(!NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(12, 0, 1)), seoul))
        // 이틀보다 먼 미래(시계가 뒤로 감)는 다시 계산한다.
        #expect(!NotifySchedule.keepBriefTarget(ms(at(8, 0)) + 3 * 24 * hour, 8 * 60, ms(at(6, 0)), seoul))
    }

    @Test func rescheduleBriefWhenZoneChanged() {
        #expect(NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(6, 0)), tokyo))
        #expect(!NotifySchedule.keepBriefTarget(ms(at(8, 0)), 8 * 60, ms(at(6, 0)), utcZone))
    }

    // ---- (iOS 추가) 놓친 브리핑 시각 ----

    @Test func dueBriefSlot() {
        var s = NotifySettings(briefEnabled: true, briefMinutesOfDay: [8 * 60, 20 * 60])
        #expect(NotifySchedule.dueBriefSlot(s, lastBriefAtMillis: 0, at(9, 0), seoul) == at(8, 0))
        #expect(NotifySchedule.dueBriefSlot(s, lastBriefAtMillis: ms(at(8, 0, 5)), at(9, 0), seoul) == nil)
        #expect(NotifySchedule.dueBriefSlot(s, lastBriefAtMillis: 0, at(12, 0, 1), seoul) == nil) // 4시간 넘게 늦음
        #expect(NotifySchedule.dueBriefSlot(s, lastBriefAtMillis: 0, at(7, 0), seoul) == nil) // 어제 20:00은 11시간 전
        s.briefEnabled = false
        #expect(NotifySchedule.dueBriefSlot(s, lastBriefAtMillis: 0, at(9, 0), seoul) == nil)
    }
}
