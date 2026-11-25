import Foundation
import ETLKit

/// 알림 쪽 저장소 모음. 기본값은 안드로이드 prefs 이름과 같은 namespace의 UserDefaults 저장소.
public struct NotifyStores: @unchecked Sendable {
    public var settings: NotifySettingsStore
    public var state: NotifyStateStore
    public var mine: MyDeadlineStore
    public var courses: CourseFilterStore
    public var watch: WatchStore
    public var files: NewFilesStore
    public var snapshot: SnapshotCache
    public var pending: PendingNoticeStore
    public var history: NotifyHistory
    public var timetable: TimetableStore
    public var timetableChanges: TimetableChangeStore

    public init(settings: NotifySettingsStore = NotifySettingsStore(), state: NotifyStateStore = NotifyStateStore(),
                mine: MyDeadlineStore = MyDeadlineStore(), courses: CourseFilterStore = CourseFilterStore(), watch: WatchStore = WatchStore(),
                files: NewFilesStore = NewFilesStore(), snapshot: SnapshotCache = SnapshotCache(), pending: PendingNoticeStore = PendingNoticeStore(),
                history: NotifyHistory = NotifyHistory(), timetable: TimetableStore = TimetableStore(),
                timetableChanges: TimetableChangeStore = TimetableChangeStore()) {
        self.settings = settings
        self.state = state
        self.mine = mine
        self.courses = courses
        self.watch = watch
        self.files = files
        self.snapshot = snapshot
        self.pending = pending
        self.history = history
        self.timetable = timetable
        self.timetableChanges = timetableChanges
    }

    /// 테스트·미리보기용: 모두 메모리 저장소.
    public static func inMemory() -> NotifyStores {
        NotifyStores(settings: NotifySettingsStore(InMemoryStore()), state: NotifyStateStore(InMemoryStore()), mine: MyDeadlineStore(InMemoryStore()),
                     courses: CourseFilterStore(InMemoryStore()), watch: WatchStore(InMemoryStore()), files: NewFilesStore(InMemoryStore()),
                     snapshot: SnapshotCache(InMemoryStore()), pending: PendingNoticeStore(InMemoryStore()), history: NotifyHistory(InMemoryStore()),
                     timetable: TimetableStore(InMemoryStore()), timetableChanges: TimetableChangeStore(InMemoryStore()))
    }
}

/// 주기 확인과 설정 화면의 "지금 확인"이 함께 쓰는 실행기. AI는 쓰지 않는다. 토큰·서명 URL·응답 본문은 결과 문구에 남기지 않는다.
///
/// 안드로이드는 여기서 알림을 바로 게시했지만, iOS 판은 **게시할 알림 목록을 돌려준다**. 돌려준 알림의 중복 방지 키는
/// 이미 [NotifyState]에 기록되어 있으므로 앱은 돌려받은 알림을 모두 `UNUserNotificationCenter`로 게시하고,
/// 게시한 뒤 [NotifyHistory.record]를 부른다. 알림 권한이 없으면 [Environment.canPost]를 false로 넘긴다(그러면 아무것도 기록하지 않는다).
public enum NotifyRunner {
    public enum Outcome: Hashable, Sendable { case SUCCESS, RETRY, SKIPPED, FAILED }

    private static let AUTH_KEY = "authfail"

    /// 실행 환경.
    public struct Environment: @unchecked Sendable {
        public var stores: NotifyStores
        /// eTL 토큰(Keychain `TokenStore`에서 읽은 값). nil이면 내 마감만 확인한다.
        public var token: String?
        /// 기기 시간대(방해 금지·브리핑·수업 알림).
        public var zone: TimeZone
        /// 설정의 "반말로 대화"(시험 응원 문구).
        public var casual: Bool
        /// 알림 권한이 있는가. false면 확인을 건너뛰고 아무것도 기록하지 않는다.
        public var canPost: Bool
        /// 지금 시각(네트워크 뒤의 제시간 계획에 쓴다).
        public var clock: () -> Date
        /// eTL 클라이언트 만들기(테스트에서 바꿔 끼운다).
        public var makeClient: (String) throws -> EtlClient
        /// 앱 로그(토큰·본문은 들어가지 않는다).
        public var log: (String) -> Void
        /// 브리핑의 오늘 캘린더 일정 줄(내 캘린더 일정 보기가 켜져 있을 때 앱이 EventKit에서 만들어 넘긴다).
        public var todayEvents: () -> [String]

        public init(stores: NotifyStores = NotifyStores(), token: String?, zone: TimeZone = .current, casual: Bool = false, canPost: Bool = true,
                    clock: @escaping () -> Date = { Date() }, makeClient: @escaping (String) throws -> EtlClient = { try EtlClient($0) },
                    log: @escaping (String) -> Void = { _ in }, todayEvents: @escaping () -> [String] = { [] }) {
            self.stores = stores
            self.token = token
            self.zone = zone
            self.casual = casual
            self.canPost = canPost
            self.clock = clock
            self.makeClient = makeClient
            self.log = log
            self.todayEvents = todayEvents
        }
    }

    /// 한 번 실행한 결과.
    public struct CheckResult: Hashable, Sendable {
        public var outcome: Outcome
        /// 게시할 알림(이 순서대로). 중복 방지 키는 이미 기록됐다.
        public var notices: [Notice]
        /// 다음 제시간 계획 시각(내가 정한 알림·마감 창·수업 전·방해 금지 끝). 앱은 이 시각에 맞춰 로컬 알림/백그라운드 작업을 잡는다.
        public var nextAt: Date?
        /// 설정 화면에 보일 마지막 결과 한 줄([NotifyState.lastResult]와 같다).
        public var lastResult: String

        public init(outcome: Outcome, notices: [Notice], nextAt: Date?, lastResult: String) {
            self.outcome = outcome
            self.notices = notices
            self.nextAt = nextAt
            self.lastResult = lastResult
        }
    }

    /// 실행 중 모은 것.
    private final class Run {
        var notices: [Notice] = []
        var nextAt: Date?
        func post(_ n: Notice) -> Bool {
            notices.append(n)
            return true
        }
        func earlier(_ t: Date?) {
            guard let t else { return }
            if nextAt == nil || t < nextAt! { nextAt = t }
        }
    }

    /// 주기 확인 한 번. 순서: 내가 정한 알림(네트워크 없이 먼저) → eTL 조회(실패하면 받아 둔 과제로 메움) → 마감·공지 계획 →
    /// 새 과제·마감 변경·성적·제출 점검 → 수업 알림 → 제시간 알림 다시 계획 → 새 강의자료(4시간마다) → (있으면) 브리핑.
    /// - briefDueAt: 이번 실행에서 브리핑도 보낼 때 그 예정 시각([NotifySchedule.dueBriefSlot]). nil이면 브리핑 없음.
    public static func runCheck(_ env: Environment, now: Date? = nil, briefDueAt: Date? = nil) async -> CheckResult {
        let now = now ?? env.clock()
        let run = Run()
        let stateStore = env.stores.state
        let outcome = await guarded(env, run, now) {
            let settings = env.stores.settings.load()
            let token = env.token.flatMap { $0.isEmpty ? nil : $0 }
            let mine = env.stores.mine.load()
            if token == nil && mine.isEmpty { return skipped(env, now, "건너뜀 · eTL 토큰 없음") }
            if !env.canPost {
                // 중복 키를 기록하지 않는다: 권한을 줄 때까지 계속 시도한다.
                return skipped(env, now, "건너뜀 · 알림 권한 없음")
            }
            // 내가 정한 알림 시각(내 마감)은 네트워크 없이 먼저 보낸다: eTL 조회가 실패해도 울려야 한다.
            let quietEarly = Quiet.isQuiet(ZoneClock.minuteOfDay(now, env.zone), settings)
            NoticeDelivery.deliver(now, stateStore.load, stateStore.save, run.post) { state in
                let fired = Set(state.fired.keys)
                let (examDone, examSilent) = MyDeadlines.examDoneNotices(mine, now, fired, enabled: settings.examDoneAlerts, quiet: quietEarly, casual: env.casual)
                return Plan(MyDeadlines.reminderNotices(mine, now, fired, casual: env.casual) + examDone, examSilent)
            }
            run.earlier(MyDeadlines.nextReminderAt(mine, now, Set(stateStore.load().fired.keys)))
            // 과목별 알림 끄기·숨기기: 그 과목은 알리지 않고 중복 방지 기록만 남긴다.
            let courses = env.stores.courses.load()
            let watchStore = env.stores.watch
            let watchBefore = watchStore.load()
            // 제출 직후 점검: 새 제출인데 과제 목록에 첨부 목록이 없으면 그 제출만 `submissions/self`로 더 받는다.
            let needDetail: (AssignmentItem) -> Bool = { a in
                settings.submitAlerts && !courses.muted(a.courseId) && AssignmentWatch.isNewSubmission(watchBefore, a)
            }
            let fetched = try await fetchWithFallback(env, token, now, announcementDays: 2, needDetail)
            let failure = fetched.failure
            // eTL을 실제로 조회했는가. 아니면 공지 기준선·새 과제/변경 비교를 하지 않는다.
            let etlOk = token != nil && failure == nil
            let snapshot = MyDeadlines.merge(fetched.snapshot, mine, now)
            // 시험·과제 공지는 채팅 화면의 "등록 안 한 공지" 시트용으로 남겨 둔다.
            if etlOk { env.stores.pending.record(fetched.snapshot.announcements, now) }
            let quiet = Quiet.isQuiet(ZoneClock.minuteOfDay(now, env.zone), settings)

            // 새 과제·마감 변경·성적과 제출 직후 점검. 방해 금지 중에는 비교하지 않아 끝난 뒤 알린다.
            var changes: [Notice] = []
            if (settings.changeAlerts || settings.submitAlerts) && etlOk && !quiet {
                let (newState, notices) = AssignmentWatch.diff(watchStore.load(), snapshot, now, { courses.muted($0) },
                                                               changes: settings.changeAlerts, submits: settings.submitAlerts, submitOk: settings.submitOkAlerts)
                watchStore.save(newState)
                let fired = stateStore.load().fired
                changes = notices.filter { n in !n.firedKeys.contains { fired[$0] != nil } }
            }

            // 수업 알림: 오늘·내일 시간표(하루 캐시, 보강·휴강 반영).
            var classDays: [TimetableLogic.Day] = []
            if settings.classAlerts, let token {
                do {
                    let etl = try env.makeClient(token)
                    let w = try await Timetable.cachedOrFetch(etl, env.stores.timetable, nowMs: now.epochMilli)
                    let ch = env.stores.timetableChanges.load()
                    let today = ZoneClock.localDate(now, env.zone)
                    classDays = [TimetableLogic.day(w, today, ch), TimetableLogic.day(w, today.plusDays(1), ch)].map(courses.alertDay)
                } catch let e as EtlAuthException {
                    throw e
                } catch {
                    classDays = []
                }
            }
            let delivered = NoticeDelivery.deliver(now, stateStore.load, stateStore.save, run.post) { state in
                var plan = CourseMute.plan(snapshot, state, now, settings, courses, env.zone)
                if !etlOk { plan.markBaselineDone = false }
                if let at = fetched.cachedAt { plan.notices = plan.notices.map { staleMark($0, at, now) } }
                let classes = (quiet || !settings.classAlerts) ? [] :
                    (classDays.first.map { ClassNotices.plan($0, now, env.zone, settings.classLeadMinutes, Set(state.fired.keys)) } ?? [])
                plan.notices += changes + classes
                return plan
            }
            let nowMs = now.epochMilli
            let deadlines = delivered.notices.filter { $0.channel == .DEADLINE }.count
            let anns = delivered.notices.filter { $0.channel == .ANNOUNCEMENT }.count
            let classes = delivered.notices.filter { $0.channel == .CLASS }
            stateStore.update { s in
                s.lastCheckAt = nowMs
                let counts = "마감 \(deadlines)건, 공지 \(anns)건" +
                    (changes.isEmpty ? "" : ", 과제·성적·제출 \(changes.count)건") + (classes.isEmpty ? "" : ", 수업 \(classes.count)건")
                if let failure {
                    s.lastResult = "실패 · \(reasonOf(failure))" +
                        (fetched.cachedAt.map { " · \(SnapshotFallback.ageLabel($0, now)) 받아 둔 과제로 마감 \(deadlines)건" } ?? "")
                } else if let at = fetched.cachedAt {
                    s.lastResult = "성공 · \(counts) · 일부 과목은 \(SnapshotFallback.ageLabel(at, now)) 정보"
                } else {
                    s.lastResult = "성공 · \(counts)"
                }
                s.prune(nowMs)
            }
            // 다음 마감·수업 알림 시점: 조회 중에 지난 경계의 로컬 알림을 먼저 보내고 다음 시각을 고른다.
            let later = max(env.clock(), now)
            let timed = TimedNotifications.deliver(env.stores, later, zone: env.zone, casual: env.casual, post: run.post)
            run.earlier(timed.nextAt)
            logErrors(env, snapshot.errors, fetched.cachedAt)
            env.log("확인 완료: 마감 \(deadlines), 공지 \(anns), 조회 오류 \(snapshot.errors.count)")
            // 새 강의자료: 마지막 단계로, 4시간에 한 번만. 실패해도 이번 확인 결과에는 영향이 없다.
            if settings.newFileAlerts && etlOk && !quiet, let token {
                do {
                    try await checkNewFiles(env, run, token, fetched.snapshot.courses, courses, now)
                } catch {
                    env.log("새 강의자료 확인 실패: \(reasonOf(error))")
                }
            }
            // (iOS) 브리핑: 이번 조회 결과로 만든다. 브리핑은 방해 금지와 무관하게 보낸다.
            if let briefDueAt, settings.briefEnabled, !NotifySchedule.isStale(briefDueAt.epochMilli, nowMs) {
                let brief = try await buildBrief(env, settings, token, fetched, mine, courses, now)
                _ = run.post(brief)
                stateStore.update { s in s.lastBriefAt = nowMs }
            }
            // 연결 실패면 받아 둔 정보로 보낼 것은 보냈지만, 새 공지·변경을 보려고 다시 시도한다(중복은 fired 키가 막는다).
            return failure != nil ? .RETRY : .SUCCESS
        }
        return CheckResult(outcome: outcome, notices: run.notices, nextAt: run.nextAt, lastResult: stateStore.load().lastResult)
    }

    /// 정기 브리핑만 보낸다(안드로이드 `BriefWorker`). [allowFallback]: 마지막 시도면 true(연결 실패 때 받아 둔 과제로 보낸다).
    public static func runBrief(_ env: Environment, now: Date? = nil, scheduledAtMillis: Int64? = nil, allowFallback: Bool = true) async -> CheckResult {
        let now = now ?? env.clock()
        let run = Run()
        let stateStore = env.stores.state
        let outcome = await guarded(env, run, now) {
            if let s = scheduledAtMillis, NotifySchedule.isStale(s, now.epochMilli) { return skipped(env, now, "건너뜀 · 예정 시각이 지남") }
            let settings = env.stores.settings.load()
            let token = env.token.flatMap { $0.isEmpty ? nil : $0 }
            let mine = env.stores.mine.load()
            if token == nil && mine.isEmpty { return skipped(env, now, "건너뜀 · eTL 토큰 없음") }
            if !env.canPost { return skipped(env, now, "건너뜀 · 알림 권한 없음") }
            let fetched = try await fetchWithFallback(env, token, now, announcementDays: 1)
            if let f = fetched.failure, !allowFallback || fetched.cachedAt == nil { throw f }
            // 일부만 연결 실패: 마지막 시도 전이면 보내지 않고 다시 시도한다.
            if fetched.failure == nil && !allowFallback && SnapshotFallback.hasConnectionErrors(fetched.snapshot.errors) {
                logErrors(env, fetched.snapshot.errors, nil)
                env.log("브리핑 미룸: 일부 조회가 연결 실패라 다시 시도")
                return .RETRY
            }
            let brief = try await buildBrief(env, settings, token, fetched, mine, env.stores.courses.load(), now)
            _ = run.post(brief)
            let nowMs = now.epochMilli
            stateStore.update { s in
                s.lastBriefAt = nowMs
                s.lastResult = fetched.cachedAt.map { "브리핑 전송 · \(SnapshotFallback.ageLabel($0, now)) 받아 둔 과제 정보" } ?? "브리핑 전송"
            }
            env.log("브리핑 전송" + (fetched.cachedAt.map { " (받아 둔 정보: \(SnapshotFallback.ageLabel($0, now)))" } ?? ""))
            return .SUCCESS
        }
        return CheckResult(outcome: outcome, notices: run.notices, nextAt: run.nextAt, lastResult: stateStore.load().lastResult)
    }

    /// 브리핑 알림 만들기: 숨긴 과목은 뺀다(알림만 끈 과목은 그대로 보인다). 오늘 수업은 시간표를 따른다.
    private static func buildBrief(_ env: Environment, _ settings: NotifySettings, _ token: String?, _ fetched: Fetched, _ mine: [MyDeadline],
                                   _ courses: CourseFilter, _ now: Date) async throws -> Notice {
        let snapshot = courses.visible(MyDeadlines.merge(fetched.snapshot, mine, now))
        logErrors(env, snapshot.errors, fetched.cachedAt)
        let classes = token == nil ? [] : try await todayClassLines(env, token!, courses, now)
        let exams = MyDeadlines.examLines(mine, now)
        return BriefBuilder.build(snapshot, now, settings, env.zone, todayClasses: classes, staleSince: fetched.cachedAt, exams: exams, todayEvents: env.todayEvents())
    }

    /// 오늘(기기 시간대 날짜) 수업 줄. 실패해도 브리핑은 그대로 보낸다(수업 섹션만 빠진다). 토큰 거부는 위로 올린다.
    private static func todayClassLines(_ env: Environment, _ token: String, _ courses: CourseFilter, _ now: Date) async throws -> [String] {
        do {
            let etl = try env.makeClient(token)
            let w = try await Timetable.cachedOrFetch(etl, env.stores.timetable, nowMs: now.epochMilli)
            let date = ZoneClock.localDate(now, env.zone)
            var events: [TimetableLogic.ClassRow] = []
            do { events = try await Timetable.calendarClasses(etl, w.courses, date) } catch let e as EtlAuthException { throw e } catch { events = [] }
            // "시간표·오늘 수업에서도 숨기기"를 켠 과목은 뺀다.
            let day = courses.timetableDay(TimetableLogic.day(w, date, env.stores.timetableChanges.load(), events))
            return TimetableLogic.briefLines(day.classes) + day.cancelled.map { "• \($0.start) \($0.course) 휴강" + ($0.note.map { n in " (\(n))" } ?? "") }
        } catch let e as EtlAuthException {
            throw e
        } catch {
            return []
        }
    }

    /// 과목마다 차례로 파일 목록 첫 쪽을 보고(GET), 새 파일을 과목별로 묶어 알린다. 처음 보는 과목은 기준선만.
    private static func checkNewFiles(_ env: Environment, _ run: Run, _ token: String, _ current: [CourseRef], _ filter: CourseFilter, _ now: Date) async throws {
        if current.isEmpty { return }
        let store = env.stores.files
        let before = store.load()
        let nowMs = now.epochMilli
        if !NewFiles.due(before, nowMs) { return }
        let results = try await NewFilesCheck.fetch(try env.makeClient(token), NewFiles.toCheck(before, current, nowMs))
        let (after, notices) = NewFiles.apply(before, nowMs, current, results, muted: { filter.muted($0) })
        store.save(after)
        for n in notices { _ = run.post(n) }
        let denied = results.values.filter { $0 == .denied || $0 == .authRejected }.count
        let failed = results.values.filter { $0 == .failed }.count
        env.log("새 강의자료 확인: 과목 \(results.count)개, 알림 \(notices.count)건" +
                (denied > 0 ? ", 파일 탭 막힘 \(denied)" : "") + (failed > 0 ? ", 실패 \(failed)" : ""))
    }

    /// eTL 조회 결과. [snapshot]은 내 마감을 합치기 전이고, 실패한 부분은 받아 둔 과제로 메웠을 수 있다([cachedAt]).
    /// [failure]: 강의 목록부터 받지 못한 연결 실패(토큰 거부는 여기 오지 않고 그대로 던진다).
    private struct Fetched {
        let snapshot: Snapshot
        let cachedAt: Date?
        let failure: EtlException?
    }

    /// 토큰이 없으면 eTL을 조회하지 않고 빈 결과를 쓴다. 성공한 과목은 [SnapshotCache]에 저장하고, 실패한 과목·전체 실패는 저장해 둔 과제로 메운다.
    private static func fetchWithFallback(_ env: Environment, _ token: String?, _ now: Date, announcementDays: Int,
                                          _ needsSubmissionDetail: @escaping (AssignmentItem) -> Bool = { _ in false }) async throws -> Fetched {
        guard let token else { return Fetched(snapshot: Snapshot(now, [], [], ["eTL 토큰 없음"]), cachedAt: nil, failure: nil) }
        let cache = env.stores.snapshot
        let before = cache.load()
        let fresh: Snapshot
        do {
            fresh = try await SnapshotFetcher.fetch(try env.makeClient(token), now, announcementDays: announcementDays, needsSubmissionDetail: needsSubmissionDetail)
        } catch let e as EtlAuthException {
            throw e
        } catch let e as EtlException {
            env.log("eTL 조회 실패: \(reasonOf(e))")
            let r = SnapshotFallback.apply(Snapshot(now, [], [], ["eTL 연결 실패"]), before, now, totalFailure: true)
            return Fetched(snapshot: r.snapshot, cachedAt: r.cachedAt, failure: e)
        }
        cache.save(fresh)
        let r = SnapshotFallback.apply(fresh, before, now, totalFailure: false)
        return Fetched(snapshot: r.snapshot, cachedAt: r.cachedAt, failure: nil)
    }

    /// 받아 둔 정보로 만든 마감 알림에 "N시간 전 정보" 줄을 붙인다.
    private static func staleMark(_ n: Notice, _ cachedAt: Date, _ now: Date) -> Notice {
        if n.channel != .DEADLINE { return n }
        var c = n
        c.bigText = (n.bigText ?? n.text) + "\n\n" + SnapshotFallback.staleLine(cachedAt, now)
        return c
    }

    private static func logErrors(_ env: Environment, _ errors: [String], _ cachedAt: Date?) {
        if errors.isEmpty { return }
        env.log("조회 오류 \(errors.count)건" + (cachedAt != nil ? "(받아 둔 과제로 메움)" : "") + ": " + errors.joined(separator: " | ").kTake(600))
    }

    private static func reasonOf(_ e: Error) -> String {
        if let ee = e as? EtlException {
            let m = ee.message.kTake(80)
            return m.kIsBlank ? String(describing: type(of: ee)) : m
        }
        return String(describing: type(of: e))
    }

    /// 공통 예외 처리: 어떤 예외도 밖으로 새지 않는다.
    private static func guarded(_ env: Environment, _ run: Run, _ now: Date, _ block: () async throws -> Outcome) async -> Outcome {
        do {
            return try await block()
        } catch is EtlAuthException {
            return handleAuthFailure(env, run, now)
        } catch {
            let reason = reasonOf(error)
            env.stores.state.update { s in
                s.lastCheckAt = now.epochMilli
                s.lastResult = "실패 · \(reason)"
            }
            env.log("실패: \(reason)")
            return .RETRY
        }
    }

    private static func handleAuthFailure(_ env: Environment, _ run: Run, _ now: Date) -> Outcome {
        let nowMs = now.epochMilli
        let last = env.stores.state.load().fired[AUTH_KEY]
        var posted = false
        if NotifySchedule.authFailureDue(last, nowMs) && env.canPost {
            posted = run.post(Notice(.DEADLINE, "eTL 토큰을 확인해 주세요", "토큰이 만료됐거나 올바르지 않아요. 설정에서 다시 저장해 주세요.",
                                     url: "\(NoticeText.ETL_PREFIX)profile/settings", tag: "auth", firedKeys: [AUTH_KEY]))
        }
        env.stores.state.update { s in
            if posted { s.fired[AUTH_KEY] = nowMs }
            s.lastCheckAt = nowMs
            s.lastResult = "실패 · eTL이 토큰을 거부했어요"
        }
        env.log("실패: 토큰 거부")
        return .FAILED
    }

    private static func skipped(_ env: Environment, _ now: Date, _ result: String) -> Outcome {
        env.stores.state.update { s in
            s.lastCheckAt = now.epochMilli
            s.lastResult = result
        }
        env.log(result)
        return .SKIPPED
    }
}
