import Foundation
import ETLKit

/// 제시간 알림(내가 정한 알림 시각, 마감 창, 수업 전, 시험 끝)은 받아 둔 데이터만 쓴다. 네트워크 조회는 [NotifyRunner] 몫.
public enum TimedNotifications {
    public struct Result: Hashable, Sendable {
        public var plan: Plan
        /// 다음에 다시 계획할 시각(그때 알릴 것이 생긴다). 없으면 nil.
        public var nextAt: Date?
        public init(_ plan: Plan, _ nextAt: Date?) {
            self.plan = plan
            self.nextAt = nextAt
        }
    }

    public static func plan(_ cached: CachedSnapshot?, _ mine: [MyDeadline], _ days: [TimetableLogic.Day], _ state: NotifyState, _ now: Date,
                            _ settings: NotifySettings, courses: CourseFilter = CourseFilter(), zone: TimeZone = .current, casual: Bool = false) -> Result {
        let usable = cached.flatMap { SnapshotFallback.usable($0, now) ? $0 : nil }
        let snapshot = MyDeadlines.merge(Snapshot(now, usable?.assignments ?? [], []), mine, now)
        var noAnn = settings
        noAnn.announcementAlerts = false
        let deadlines = CourseMute.plan(snapshot, state, now, noAnn, courses, zone)
        let quiet = Quiet.isQuiet(ZoneClock.minuteOfDay(now, zone), settings)
        let fired = Set(state.fired.keys)
        let (examDone, examSilent) = MyDeadlines.examDoneNotices(mine, now, fired, enabled: settings.examDoneAlerts, quiet: quiet, casual: casual)
        let reminders = MyDeadlines.reminderNotices(mine, now, fired, casual: casual)
        let alertDays = days.map(courses.alertDay)
        let classes = settings.classAlerts && !quiet ? alertDays.flatMap { ClassNotices.plan($0, now, zone, settings.classLeadMinutes, fired) } : []
        let marked = deadlines.notices.map { n -> Notice in
            // 내 마감은 지금 폰 안의 데이터라 저장본 표시를 붙이지 않는다(eTL 과제만).
            guard let usable, n.firedKeys.contains(where: { $0.hasPrefix("deadline-m:") && !$0.hasPrefix("deadline-m:\(MyDeadlines.COURSE_ID):") }) else { return n }
            var c = n
            c.bigText = (n.bigText ?? n.text) + "\n\n※ \(SnapshotFallback.ageLabel(Date(epochMilli: usable.takenAt), now)) 받아 둔 eTL 정보예요"
            return c
        }
        let plan = Plan(marked + reminders + examDone + classes, deadlines.silentKeys + examSilent)
        var after = state
        for k in plan.notices.flatMap(\.firedKeys) + plan.silentKeys { after.fired[k] = now.epochMilli }
        let afterKeys = Set(after.fired.keys)
        let alertable = courses.alertable(snapshot)
        let candidates: [Date?] = [
            NotificationPlanner.nextDeadlineCheckAt(alertable, after, now, settings),
            settings.classAlerts ? ClassNotices.nextAt(alertDays, now, zone, settings.classLeadMinutes, afterKeys) : nil,
            MyDeadlines.nextReminderAt(mine, now, afterKeys),
            // 방해 금지 중인 마감은 기록하지 않았다: 방해 금지가 끝날 때(마감 전이면) 다시 본다.
            (quiet && settings.deadlineAlerts) ? {
                let end = nextQuietEnd(now, settings, zone)
                return alertable.assignments.contains { !$0.submitted && !$0.missing && ($0.due.map { $0 > end } ?? false) } ? end : nil
            }() : nil,
        ]
        return Result(plan, candidates.compactMap { $0 }.min())
    }

    /// 지난 직접 알림도 마감 전까지는 보낼 수 있다(앱을 열 때 지우지 않는다).
    public static func hasPersonalReminders(_ mine: [MyDeadline], _ now: Date, _ fired: Set<String>) -> Bool {
        MyDeadlines.nextReminderAt(mine, now, fired) != nil || hasDuePersonalReminders(mine, now, fired)
    }

    public static func hasDuePersonalReminders(_ mine: [MyDeadline], _ now: Date, _ fired: Set<String>) -> Bool {
        !MyDeadlines.reminderNotices(mine, now, fired).isEmpty ||
            !MyDeadlines.examDoneNotices(mine, now, fired, enabled: true, quiet: false, casual: false).0.isEmpty
    }

    private static func nextQuietEnd(_ now: Date, _ settings: NotifySettings, _ zone: TimeZone) -> Date {
        var date = ZoneClock.localDate(now, zone)
        var end = ZoneClock.instant(date, settings.quietEndMinutes / 60, settings.quietEndMinutes % 60, 0, zone)
        if !(end > now) {
            date = date.plusDays(1)
            end = ZoneClock.instant(date, settings.quietEndMinutes / 60, settings.quietEndMinutes % 60, 0, zone)
        }
        return end
    }

    /// 계획에 필요한 로컬 데이터(안드로이드 `readInputs`). 네트워크 없음.
    public struct Inputs: Sendable {
        public var cached: CachedSnapshot?
        public var mine: [MyDeadline]
        public var days: [TimetableLogic.Day]
        public var settings: NotifySettings
        public var courses: CourseFilter
        public var zone: TimeZone
        public var casual: Bool

        public init(cached: CachedSnapshot?, mine: [MyDeadline], days: [TimetableLogic.Day], settings: NotifySettings, courses: CourseFilter,
                    zone: TimeZone, casual: Bool) {
            self.cached = cached
            self.mine = mine
            self.days = days
            self.settings = settings
            self.courses = courses
            self.zone = zone
            self.casual = casual
        }

        public func plan(_ state: NotifyState, _ now: Date) -> Result {
            TimedNotifications.plan(cached, mine, days, state, now, settings, courses: courses, zone: zone, casual: casual)
        }

        /// 저장소에서 읽는다. 기본은 오늘·내일, 미리 예약할 때는 through까지의 시간표(보강·휴강 반영)를 넣는다.
        public static func read(_ stores: NotifyStores, _ now: Date, zone: TimeZone = .current, casual: Bool = false,
                                through: Date? = nil) -> Inputs {
            let settings = stores.settings.load()
            let today = ZoneClock.localDate(now, zone)
            var days: [TimetableLogic.Day] = []
            if settings.classAlerts, let weekly = stores.timetable.load() {
                let changes = stores.timetableChanges.load()
                let last = through.map { max(today, ZoneClock.localDate($0, zone)) } ?? today.plusDays(1)
                var day = today
                while day <= last {
                    days.append(TimetableLogic.day(weekly, day, changes))
                    day = day.plusDays(1)
                }
            }
            return Inputs(cached: stores.snapshot.load(), mine: stores.mine.load(), days: days, settings: settings,
                          courses: stores.courses.load(), zone: zone, casual: casual)
        }
    }

    /// 로컬 데이터만으로 지금 보낼 알림을 계획하고 기록한다(안드로이드 `deliver`). [post]가 true를 돌려준 알림만 기록된다.
    /// 돌려준 [Result.nextAt]에 다시 부르면 된다(iOS: 그 시각에 맞춘 로컬 알림 또는 백그라운드 작업).
    @discardableResult
    public static func deliver(_ stores: NotifyStores, _ now: Date = Date(), zone: TimeZone = .current, casual: Bool = false,
                               post: (Notice) -> Bool) -> Result {
        NoticeDelivery.lock.withLock {
            let inputs = Inputs.read(stores, now, zone: zone, casual: casual)
            let delivered = NoticeDelivery.deliver(now, stores.state.load, stores.state.save, post) { inputs.plan($0, now).plan }
            return Result(delivered, inputs.plan(stores.state.load(), now).nextAt)
        }
    }

    /// (iOS 추가) 앞으로 [until]까지 로컬 데이터만으로 울릴 알림을 미리 펼친다: 지금 계획하고, 그 알림을 보낸 것으로 친 상태에서
    /// [Result.nextAt]마다 다시 계획하기를 [maxSteps]번까지 되풀이한다. iOS는 정확한 시각에 앱을 깨울 수 없으므로,
    /// 앱은 이 결과를 `UNCalendarNotificationTrigger`로 예약한다. 첫 항목(지금 보낼 것)은 [deliver]로 바로 보내는 편이 낫다.
    /// 상태는 바꾸지 않는다(예약한 알림이 실제로 뜬 뒤 앱이 열리면 [deliver]가 같은 키를 기록해 중복을 막는다).
    public static func forecast(_ inputs: Inputs, _ state: NotifyState, from now: Date, until: Date, maxSteps: Int = 64) -> [(at: Date, notice: Notice)] {
        var out: [(at: Date, notice: Notice)] = []
        var st = state
        var t = now
        var steps = 0
        while t <= until && steps < maxSteps {
            steps += 1
            let r = inputs.plan(st, t)
            for n in r.plan.notices where n.firedKeys.isEmpty || n.firedKeys.contains(where: { st.fired[$0] == nil }) {
                out.append((t, n))
            }
            for k in r.plan.notices.flatMap(\.firedKeys) + r.plan.silentKeys { st.fired[k] = t.epochMilli }
            guard let next = r.nextAt, next > t else { break }
            t = next
        }
        return out
    }
}

/// 계획·게시·기록은 알람과 네트워크 작업이 함께 쓰는 잠금 하나로 묶는다.
public enum NoticeDelivery {
    public static let lock = NSRecursiveLock()

    /// 테스트할 수 있게 저장·게시를 주입한다. [post]가 false를 돌려준 알림은 기록하지 않는다(권한을 주면 다시 시도).
    /// 더 좁은 창은 더 느슨한 창의 키도 함께 기록하므로, 이미 기록된 느슨한 키가 새 좁은 키를 막지 않게 전부 기록된 알림만 건너뛴다.
    @discardableResult
    public static func deliver(_ now: Date, _ load: () -> NotifyState, _ save: (NotifyState) -> Void, _ post: (Notice) -> Bool,
                               _ planner: (NotifyState) -> Plan) -> Plan {
        lock.withLock {
            var state = load()
            var planned = planner(state)
            let posted = planned.notices.filter { n in
                let hasUnfiredKey = n.firedKeys.isEmpty || n.firedKeys.contains { state.fired[$0] == nil }
                return hasUnfiredKey && post(n)
            }
            for k in posted.flatMap(\.firedKeys) + planned.silentKeys { state.fired[k] = now.epochMilli }
            if planned.markBaselineDone { state.announcementBaselineDone = true }
            state.prune(now.epochMilli)
            save(state)
            planned.notices = posted
            return planned
        }
    }
}
