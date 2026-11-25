import Foundation
import Testing
import ETLKit
@testable import NotifyKit

private final class Box: @unchecked Sendable {
    var state = NotifyState()
    var posts = 0
}

@Suite struct TimedNotificationsTests {
    let now = instant("2026-11-19T03:00:00Z") // Thu, noon in Seoul
    let settings = NotifySettings(deadlineWindowsMinutes: [60, 10])
    func assignment(_ cid: Int64 = 1, _ id: Int64 = 2) -> AssignmentItem {
        AssignmentItem(cid, "과목\(cid)", id, "과제\(id)", now.secs(600), true, nil, nil, false, false)
    }
    func plan(_ cached: CachedSnapshot? = nil, mine: [MyDeadline] = [], state: NotifyState = NotifyState(), s: NotifySettings? = nil,
              filter: CourseFilter = CourseFilter(), days: [TimetableLogic.Day] = []) -> TimedNotifications.Result {
        TimedNotifications.plan(cached, mine, days, state, now, s ?? settings, courses: filter, zone: seoul)
    }
    func with(_ f: (inout NotifySettings) -> Void) -> NotifySettings { var s = settings; f(&s); return s }
    func firedState(_ keys: [String], _ at: Int64) -> NotifyState { NotifyState(fired: Dictionary(keys.map { ($0, at) }, uniquingKeysWith: { a, _ in a })) }

    @Test func offlineCachedDeadlineAndManualReminderNeedNoTokenOrNetwork() {
        let mine = MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(-60)])
        let result = plan(CachedSnapshot(now.secs(-3600).epochMilli, [assignment()]), mine: [mine])
        #expect(result.plan.notices.count == 2)
        #expect(result.plan.notices.contains { $0.tag == "mine-remind:5" })
        #expect(result.plan.notices.first { $0.tag == "deadline:1:2" }!.bigText!.contains("받아 둔 eTL 정보"))
        #expect(!result.plan.markBaselineDone) // Offline delivery never initializes the announcement baseline.
        #expect(!result.plan.notices.contains { $0.channel == .ANNOUNCEMENT })
    }

    @Test func deliveredKeysPreventOfflineAndFreshWorkerDuplicates() {
        let cached = CachedSnapshot(now.epochMilli, [assignment()])
        let first = plan(cached).plan
        let state = firedState(first.notices.flatMap(\.firedKeys), now.epochMilli)
        #expect(plan(cached, state: state).plan.notices.isEmpty)
        #expect(NotificationPlanner.plan(Snapshot(now, cached.assignments, []), state, now, settings, seoul).notices.isEmpty)
    }

    @Test func staleCacheIsExcludedButLocalRemindersStillDeliver() {
        let mine = MyDeadline(5, "서류", now.secs(7200), reminders: [now])
        let stale = CachedSnapshot(now.epochMilli - SnapshotFallback.MAX_AGE_MS - 1, [assignment()])
        #expect(plan(stale, mine: [mine], s: with { $0.deadlineAlerts = false }).plan.notices.map(\.tag) == ["mine-remind:5"])
    }

    @Test func courseMuteRecordsDeadlineSilentlyWithoutMutingPersonalReminder() {
        let filter = CourseFilter().with(CourseRef(1, "과목1"), .MUTE)
        let mine = MyDeadline(5, "서류", now.secs(7200), reminders: [now])
        let result = plan(CachedSnapshot(now.epochMilli, [assignment()]), mine: [mine], filter: filter)
        #expect(result.plan.notices.map(\.tag) == ["mine-remind:5"])
        #expect(result.plan.silentKeys.contains("deadline-m:1:2:10"))
    }

    @Test func quietHoursDelayCachedDeadlinesButExplicitReminderKeepsItsContract() {
        let quiet = with { $0.quietEnabled = true; $0.quietStartMinutes = 11 * 60; $0.quietEndMinutes = 13 * 60 }
        var a = assignment()
        a.due = now.secs(7200)
        let cached = CachedSnapshot(now.epochMilli, [a])
        let mine = MyDeadline(5, "서류", now.secs(7200), reminders: [now])
        let result = plan(cached, mine: [mine], s: quiet)
        #expect(result.plan.notices.map(\.tag) == ["mine-remind:5"])
        #expect(result.nextAt == now.secs(3600))
    }

    @Test func cachedClassReminderRespectsCourseFilterAndDedup() {
        let day = TimetableLogic.Day(ZoneClock.localDate(now, seoul), "목", [TimetableLogic.ClassRow("12:05", "13:00", "과목1", 1, nil, "정규")], [])
        let s = with { $0.deadlineAlerts = false; $0.classAlerts = true }
        let firsts = plan(s: s, days: [day]).plan.notices
        #expect(firsts.count == 1)
        #expect(firsts[0].channel == .CLASS)
        let state = firedState(firsts[0].firedKeys, now.epochMilli)
        #expect(plan(state: state, s: s, days: [day]).plan.notices.isEmpty)
        #expect(plan(s: s, filter: CourseFilter().with(CourseRef(1, "과목1"), .MUTE), days: [day]).plan.notices.isEmpty)
    }

    @Test func overduePersonalReminderSurvivesStartupUntilDueOrAlreadyDelivered() {
        let mine = MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(-60)])
        #expect(TimedNotifications.hasPersonalReminders([mine], now, []))
        #expect(!TimedNotifications.hasPersonalReminders([mine], now, [MyDeadlines.reminderKey(mine, mine.reminders[0])]))
        #expect(!TimedNotifications.hasPersonalReminders([mine], now.secs(7200), []))
    }

    @Test func deniedPostIsNotRecordedAndCanBeRetried() {
        let box = Box()
        let notice = Notice(.DEADLINE, "제목", "본문", tag: "n", firedKeys: ["key"])
        func deliver(_ allowed: Bool) -> Plan {
            NoticeDelivery.deliver(now, { box.state }, { box.state = $0 }, { _ in allowed }) { _ in Plan([notice]) }
        }
        #expect(deliver(false).notices.isEmpty)
        #expect(box.state.fired["key"] == nil)
        #expect(deliver(true).notices.count == 1)
        #expect(box.state.fired["key"] != nil)
    }

    @Test func notificationGrantRecoversDueReminderEvenWithoutStoredAlarmTarget() {
        let mine = [MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(-60)])]
        let box = Box()
        let s = with { $0.deadlineAlerts = false }
        func deliver(_ allowed: Bool) -> Plan {
            NoticeDelivery.deliver(now, { box.state }, { box.state = $0 }, { _ in allowed }) { current in
                plan(mine: mine, state: current, s: s).plan
            }
        }
        #expect(deliver(false).notices.isEmpty)
        let due = TimedNotifications.hasDuePersonalReminders(mine, now, Set(box.state.fired.keys))
        #expect(due)
        // The failed alarm has been consumed; on permission grant/startup, no target must still recover it.
        #expect(NotifySchedule.rearmAction(0, now.epochMilli, false) == .NONE)
        #expect(NotifySchedule.rearmAction(0, now.epochMilli, due) == .FIRE_NOW)
        #expect(NotifySchedule.rearmAction(now.secs(3600).epochMilli, now.epochMilli, due) == .FIRE_NOW)
        #expect(deliver(true).notices.count == 1)
        #expect(!TimedNotifications.hasDuePersonalReminders(mine, now, Set(box.state.fired.keys)))
    }

    @Test func refreshCrossingTenMinuteBoundaryDeliversNewlyDueLocalNotice() {
        let cached = CachedSnapshot(now.secs(-1).epochMilli, [assignment()])
        let box = Box()
        box.state = NotifyState(fired: ["deadline-m:1:2:60": now.secs(-3600).epochMilli])
        let beforeBoundary = now.secs(-1)
        let afterRefresh = now.secs(2)
        // The network worker planned with its start time, before the next window opened.
        #expect(NotificationPlanner.plan(Snapshot(beforeBoundary, cached.assignments, []), box.state, beforeBoundary, settings, seoul).notices.isEmpty)
        var posts: [Notice] = []
        let delivered = NoticeDelivery.deliver(afterRefresh, { box.state }, { box.state = $0 }, { posts.append($0); return true }) { current in
            TimedNotifications.plan(cached, [], [], current, afterRefresh, settings, zone: seoul).plan
        }
        #expect(delivered.notices.count == 1)
        #expect(posts.count == 1)
        #expect(box.state.fired["deadline-m:1:2:10"] != nil)
    }

    @Test func broaderWindowKeyDoesNotSuppressNewTighterWindowAndFullReplayIsSkipped() {
        let box = Box()
        box.state = NotifyState(fired: ["loose": now.epochMilli])
        let notice = Notice(.DEADLINE, "제목", "본문", tag: "n", firedKeys: ["tight", "loose"])
        func deliver() -> Plan {
            NoticeDelivery.deliver(now, { box.state }, { box.state = $0 }, { _ in box.posts += 1; return true }) { _ in Plan([notice]) }
        }
        #expect(deliver().notices.count == 1)
        #expect(box.state.fired["tight"] != nil)
        #expect(deliver().notices.isEmpty)
        #expect(box.posts == 1)
    }

    @Test func simultaneousAlarmAndWorkerPostOnlyOnce() {
        let box = Box()
        let now = self.now
        let notice = Notice(.DEADLINE, "제목", "본문", tag: "n", firedKeys: ["key"])
        DispatchQueue.concurrentPerform(iterations: 2) { _ in
            NoticeDelivery.deliver(now, { box.state }, { box.state = $0 }, { _ in box.posts += 1; return true }) { current in
                current.fired["key"] != nil ? Plan([]) : Plan([notice])
            }
        }
        #expect(box.posts == 1)
        #expect(box.state.fired["key"] != nil)
    }

    // ---- (iOS 추가) 저장소에서 읽어 보내기·미리 펼치기 ----

    @Test func deliverFromStoresRecordsAndReturnsNext() {
        let stores = NotifyStores.inMemory()
        stores.mine.save([MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(-60), now.secs(3600)])])
        var posted: [Notice] = []
        let r = TimedNotifications.deliver(stores, now, zone: seoul) { posted.append($0); return true }
        // 2시간 뒤 마감이라 3시간 전 창 알림도 함께 나온다.
        #expect(posted.map(\.tag) == ["deadline:-1:5", "mine-remind:5"])
        #expect(r.plan.notices.count == 2)
        #expect(r.nextAt == now.secs(3600))
        posted.removeAll()
        TimedNotifications.deliver(stores, now, zone: seoul) { posted.append($0); return true }
        #expect(posted.isEmpty)
    }

    @Test func forecastUnrollsFutureTimedNotices() {
        let mine = [MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(1800), now.secs(3600)])]
        let inputs = TimedNotifications.Inputs(cached: CachedSnapshot(now.epochMilli, [assignment()]), mine: mine, days: [],
                                               settings: settings, courses: CourseFilter(), zone: seoul, casual: false)
        let f = TimedNotifications.forecast(inputs, NotifyState(), from: now, until: now.secs(4 * 3600))
        #expect(f.map(\.at) == [now, now.secs(1800), now.secs(3600), now.secs(3600), now.secs(6600)])
        #expect(f.map(\.notice.tag) == ["deadline:1:2", "mine-remind:5", "deadline:-1:5", "mine-remind:5", "deadline:-1:5"])
    }

    @Test func scheduledForecastKeepsEveryReminderAndDeadlineWindow() throws {
        let mine = [MyDeadline(5, "서류", now.secs(7200), reminders: [now.secs(1800), now.secs(3600)])]
        let inputs = TimedNotifications.Inputs(cached: nil, mine: mine, days: [], settings: settings,
                                               courses: CourseFilter(), zone: seoul, casual: false)
        let forecast = TimedNotifications.forecast(inputs, NotifyState(), from: now, until: now.secs(7200))
        let entries = forecast.filter { $0.at > now }.map { ScheduledNotices.timedEntry($0.notice, at: $0.at) }
        // iOS는 같은 식별자를 교체한다. 서로 다른 시각의 같은 tag도 모두 살아 있어야 한다.
        let pending = Dictionary(entries.map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest })
        #expect(entries.count == 4)
        #expect(pending.count == 4)
        let first = try #require(entries.first)
        let remaining = Set(entries.dropFirst().map(\.id))
        let resolved = ScheduledNotices.resolve(entries, pendingIds: remaining, nowMs: first.at)
        #expect(resolved.fired == [first])
        #expect(resolved.keep.count == 3)
    }

    @Test func forecastIncludesClassesBeyondTomorrowAndAppliesChanges() {
        let stores = NotifyStores.inMemory()
        stores.settings.save(NotifySettings(deadlineAlerts: false, classAlerts: true))
        stores.timetable.save(TimetableLogic.Weekly("test", now.epochMilli, [
            TimetableLogic.CourseMeetings(1, "수업", [
                TimetableLogic.Meeting("월", "09:00", "10:00", nil),
                TimetableLogic.Meeting("목", "09:00", "10:00", nil),
            ]),
        ], []))
        stores.timetableChanges.add(TimetableLogic.Change(1, d("2026-11-23"), "휴강", "수업", courseId: 1))
        stores.timetableChanges.add(TimetableLogic.Change(2, d("2026-11-24"), "보강", "수업", courseId: 1,
                                                         start: "09:00", end: "10:00"))
        let until = now.secs(7 * 86_400)
        let inputs = TimedNotifications.Inputs.read(stores, now, zone: seoul, through: until)
        #expect(inputs.days.count == 8)
        let forecast = TimedNotifications.forecast(inputs, NotifyState(), from: now, until: until)
        let classDates = forecast.filter { $0.notice.channel == .CLASS }.map { DayDate($0.at) }
        #expect(classDates == [d("2026-11-24"), d("2026-11-26")])
    }
}
