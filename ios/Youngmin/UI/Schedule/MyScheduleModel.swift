import ETLKit
import NotifyKit
import SwiftUI

/// "내 일정" 제출 확인 대화상자 상태(Kotlin `SubmissionCheckUi`).
enum SubmissionCheckUi: Equatable {
    case loading(String)
    case done(String, SubmissionCheck.Live)
    case failed(String, String)

    var title: String {
        switch self {
        case let .loading(t), let .done(t, _), let .failed(t, _): return t
        }
    }
}

/// Kotlin `MyDeadlinesActivity`의 상태와 동작. 화면이 보이는 동안 하나.
@MainActor
@Observable
final class MyScheduleModel {
    private(set) var mine: [MyDeadline] = []
    private(set) var cached: CachedSnapshot?
    var now = Date()
    private(set) var loading = false
    var filter: Agenda.Filter = .ALL
    var hideDone = true
    private(set) var courseFilter = CourseFilter()
    var submitCheck: SubmissionCheckUi?
    private(set) var hasToken = false
    private(set) var alertsOn = true

    private static let STALE_MS: Int64 = 3 * 3600 * 1000

    @ObservationIgnored private let store = SchedStores.mine

    /// "내 캘린더 일정 보기"가 켜져 있으면 고른 캘린더의 일정(오늘~30일)을 "캘린더"로 함께 보인다.
    private(set) var calendarOn = false
    private(set) var calEvents: [NotifyKit.CalEvent] = []

    var schedule: Agenda.Schedule {
        Agenda.build(courseFilter.visibleAssignments(cached?.assignments ?? []), mine, now, .current, filter, hideDone: hideDone,
                     calendar: calendarOn ? calEvents : [])
    }

    /// 고른 캘린더의 일정(오늘~30일)을 뒤에서 읽는다. 네트워크 없음.
    func loadCalendar() {
        let on = CalendarSyncService.showEnabled
        calendarOn = on
        if !on {
            calEvents = []
            if filter == .CALENDAR { filter = .ALL }
            return
        }
        Task.detached {
            let list = CalendarSyncService.agendaEvents()
            await MainActor.run { self.calEvents = list }
        }
    }

    /// 시험 과목 칩: 받아 둔 과제의 과목 짧은 이름(가나다순).
    var courses: [String] {
        var seen = Set<String>()
        return (cached?.assignments ?? []).map { TimetableLogic.shortName($0.courseName) }
            .filter { !SchedFormat.trim($0).isEmpty && seen.insert($0).inserted }
            .sorted()
    }

    var statusText: String {
        let base: String
        if !hasToken {
            base = "eTL 토큰을 저장하면 eTL 과제 마감도 여기에 모여요."
        } else if let takenAt = cached?.takenAt {
            base = "eTL 과제 마지막 확인 \(SchedFormat.myDeadlineLabel(Date(epochMilli: takenAt)))"
        } else {
            base = loading ? "eTL 과제를 받는 중…" : "아직 eTL 과제를 받지 않았어요."
        }
        return base + (alertsOn ? "" : " · 마감 알림 꺼짐(설정에서 켤 수 있어요)")
    }

    func onStart() {
        #if DEBUG
        DemoScheduleSeed.seed(.schedule)
        #endif
        hasToken = SchedStores.hasToken || DemoScheduleSeed.isActive
        alertsOn = SchedStores.settings.load().deadlineAlerts
        reload()
        // 캘린더에서 고친 내 마감을 가져오고(켜져 있을 때), 고른 캘린더의 일정을 다시 읽는다.
        CalendarSyncService.requestSync()
        loadCalendar()
        // 저장된 결과를 바로 보여 주고, 없거나 오래됐으면(3시간) 뒤에서 새로 받는다.
        let age = cached.map { SchedFormat.nowMs() - $0.takenAt }
        if age == nil || age! > Self.STALE_MS { refresh() }
    }

    func reload() {
        mine = store.load()
        cached = SchedStores.snapshot.load()
        courseFilter = SchedStores.courses.load()
    }

    /// 화면의 행(반복 마감이면 회차)에 해당하는 저장된 원래 항목.
    func seriesOf(_ d: MyDeadline) -> MyDeadline? { store.load().first { $0.id == d.id } }

    /// eTL 과제를 새로 받아 저장한다(알림 확인과 같은 캐시).
    func refresh() {
        if loading || DemoScheduleSeed.isActive { return }
        guard let token = SchedStores.token() else { return }
        loading = true
        Task {
            let msg: String?
            do {
                let etl = try EtlClient(token)
                SchedStores.snapshot.save(try await SnapshotFetcher.fetch(etl, Date(), announcementDays: 1))
                msg = nil
            } catch is EtlAuthException {
                msg = "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요"
            } catch let e as EtlException {
                msg = "eTL 과제를 새로 받지 못했어요: \(e.message)"
            } catch {
                msg = "eTL 과제를 새로 받지 못했어요(\(type(of: error)))"
            }
            loading = false
            reload()
            if let msg { ToastCenter.shared.show(msg) }
        }
    }

    func save(_ d: MyDeadline) {
        // 알림 재예약은 MyDeadlineStore.onChanged / EtlEvents.dataChanged를 받는 앱 쪽이 한다(INTEGRATION.md).
        store.upsert(d)
        reload()
    }

    /// 마감 시각을 바꾸면 새 ID로 저장한다: 예전 시각 기준으로 이미 보낸 알림 기록이 새 알림을 막지 않게.
    func save(old: MyDeadline?, new: MyDeadline) {
        if let old, old.id != new.id { store.delete(old.id) }
        save(new)
    }

    /// 반복 마감의 회차는 그 회차만 완료로 표시한다.
    func toggleDone(_ d: MyDeadline) {
        guard let series = seriesOf(d) else { return }
        save(MyDeadlines.setDone(series, d.occurrence, !d.done, Date()))
    }

    func delete(_ d: MyDeadline, onlyThis: Bool) {
        let series = seriesOf(d)
        if onlyThis, let series, let occ = d.occurrence {
            save(MyDeadlines.skip(series, occ, Date()))
        } else {
            store.delete(d.id)
            reload()
        }
        ToastCenter.shared.show("지웠어요")
    }

    /// 동영상 강의 공지: "그날 대면 수업은 휴강으로 표시"를 체크하면 같은 저장에서 시간표 휴강도 넣는다.
    func cancelClass(_ day: DayDate, _ courseName: String, _ annId: Int64) {
        let course = SchedStores.timetable.load()?.courses.first { TimetableLogic.sameCourse(courseName, $0.name) }
        SchedStores.changes.add(TimetableLogic.Change(
            SchedFormat.nowMs() + 1, day, "휴강", course.map { TimetableLogic.shortName($0.name) } ?? courseName,
            courseId: course?.courseId, note: "동영상 강의로 대체", sourceAnnouncementId: annId))
        ToastCenter.shared.show("시간표에 휴강도 넣었어요")
    }

    /// "제출 확인": 과제와 내 제출을 지금 받아(GET 두 번) 판정한다. 파일은 받지 않는다.
    func checkSubmission(_ a: AssignmentItem) {
        guard let token = SchedStores.token() else {
            ToastCenter.shared.show("eTL 토큰을 먼저 저장하세요")
            return
        }
        let title = a.name
        submitCheck = .loading(title)
        Task {
            let result: SubmissionCheckUi
            do {
                result = .done(title, try await SubmissionCheck.live(try EtlClient(token), a.courseId, a.assignmentId, Date()))
            } catch is EtlAuthException {
                result = .failed(title, "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요")
            } catch let e as EtlException {
                result = .failed(title, e.message.isEmpty ? "eTL 요청 실패" : e.message)
            } catch {
                result = .failed(title, "확인하지 못했어요(\(type(of: error)))")
            }
            // 그사이 닫았으면 다시 띄우지 않는다.
            if submitCheck == .loading(title) { submitCheck = result }
        }
    }
}
