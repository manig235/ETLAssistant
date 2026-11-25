import ETLKit
import NotifyKit
import SwiftUI

/// 시간표 상태(Kotlin `TimetableActivity`): 수강편람 주간 수업 + 직접 추가한 수업 + 보강·휴강(오늘 이후).
/// 과목별 알림에서 "시간표·오늘 수업에서도 숨기기"를 켠 과목은 수업과 보강·휴강 모두 보이지 않는다.
@MainActor
@Observable
final class TimetableModel {
    private(set) var weekly: TimetableLogic.Weekly?
    private(set) var changes: [TimetableLogic.Change] = []
    private(set) var loading = false
    private(set) var hiddenCourses = 0

    func onStart() {
        #if DEBUG
        DemoScheduleSeed.seed(.timetable)
        #endif
        reload()
        if DemoScheduleSeed.isActive { return }
        let cached = SchedStores.timetable.loadCatalog()
        let now = SchedFormat.nowMs()
        if cached == nil || cached!.metadataVersion < 2 || now < cached!.fetchedAt || now - cached!.fetchedAt >= Timetable.CACHE_MS {
            refresh(force: false)
        }
    }

    func reload() {
        let filter = SchedStores.courses.load()
        let all = SchedStores.timetable.load()
        weekly = filter.timetableWeekly(all)
        let today = DayDate.today()
        changes = filter.timetableChanges(SchedStores.changes.load().filter { !($0.date < today) }, all)
        hiddenCourses = filter.timetableHiddenCount
    }

    func refresh(force: Bool) {
        if loading { return }
        guard let token = SchedStores.token() else { return }
        loading = true
        Task {
            let msg: String?
            do {
                _ = try await Timetable.weekly(try EtlClient(token), SchedStores.timetable, force: force)
                msg = nil
            } catch is EtlAuthException {
                msg = "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요"
            } catch let e as EtlException {
                msg = "시간표를 가져오지 못했어요: \(e.message)"
            } catch let e as CatalogError {
                msg = "시간표를 가져오지 못했어요: \(e.message)"
            } catch {
                msg = "시간표를 가져오지 못했어요(\(type(of: error)))"
            }
            loading = false
            reload()
            if let msg { ToastCenter.shared.show(msg, long: true) }
        }
    }

    /// 이번 주(월~일, KST) 휴강으로 빠지는 수업. 원본 화면 격자에는 없고 시간표 위젯에만 있던 표시다.
    var cancelledThisWeek: Set<TimetableCancelKey> {
        guard let w = weekly else { return [] }
        let monday = DayDate.today().previousOrSame(.monday)
        let sunday = monday.plusDays(6)
        var out = Set<TimetableCancelKey>()
        for ch in changes where ch.kind == "휴강" && !(ch.date < monday) && !(ch.date > sunday) {
            let day = TimetableLogic.weekdayOf(ch.date)
            for c in w.courses where ch.courseId.map({ $0 == c.courseId }) ?? TimetableLogic.sameCourse(ch.course, c.name) {
                for m in c.meetings where m.day == day && (ch.start == nil || TimetableLogic.normTime(ch.start) == TimetableLogic.normTime(m.start)) {
                    if let s = TimetableLogic.normTime(m.start) { out.insert(TimetableCancelKey(courseId: c.courseId, day: day, start: s)) }
                }
            }
        }
        return out
    }

    func saveCustom(_ c: TimetableLogic.CourseMeetings) throws {
        try SchedStores.timetable.saveCustom(c)
        reload()
    }

    func deleteCustom(_ id: Int64) {
        SchedStores.timetable.deleteCustom(id)
        reload()
    }

    func add(_ c: TimetableLogic.Change) {
        SchedStores.changes.add(c)
        reload()
    }

    func delete(_ c: TimetableLogic.Change) {
        SchedStores.changes.delete(c.id)
        reload()
    }
}

/// 시간표 화면(Kotlin `TimetableScreen`).
/// - Parameter draft: 보강·휴강 공지의 "시간표에 반영" 초안(`timetable-change-edit` 초안 모드로 연다).
struct TimetableView: View {
    var draft: ScheduleDraft?

    @State private var model = TimetableModel()
    @State private var addingChange = false
    @State private var draftState: ScheduleDraft?
    @State private var customEditing: CustomEdit?
    @State private var showCourseAlerts = false
    @State private var handledIntent = false

    @Environment(\.dismiss) private var dismiss
    @Environment(\.schedOpenChat) private var openChat
    @Environment(AppState.self) private var app

    struct CustomEdit: Identifiable {
        let id = UUID()
        let course: TimetableLogic.CourseMeetings?
    }

    var body: some View {
        VStack(spacing: 0) {
            SchedTopBar(title: "시간표", onBack: { dismiss() }) {
                if model.loading { ProgressView().controlSize(.small).frame(width: 32, height: 48) }
                SchedIconButton(systemName: "plus", label: "수업 직접 추가", tint: YMColor.brandPrimary, size: 24) {
                    customEditing = CustomEdit(course: nil)
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer().frame(height: 8)
                    // 과목별 알림에서 시간표에서도 숨긴 과목이 있으면 알려 준다.
                    if model.hiddenCourses > 0 {
                        YMChip("숨긴 과목 \(model.hiddenCourses)개") { showCourseAlerts = true }
                            .padding(.leading, 16)
                            .padding(.bottom, 8)
                    }
                    if let w = model.weekly {
                        if TimetableGridLogic.build(w.courses).blocks.isEmpty {
                            hint("수업 시간이 없어요. 오른쪽 위 + 버튼으로 직접 추가할 수 있어요.")
                        } else {
                            WeeklyTimetableGrid(weekly: w, cancelled: model.cancelledThisWeek, onEditCustom: { c in
                                // 수업 정보 시트가 닫힌 뒤에 수정 창을 띄운다(시트 두 개가 겹치지 않게).
                                Task { @MainActor in
                                    try? await Task.sleep(nanoseconds: 450_000_000)
                                    customEditing = CustomEdit(course: c)
                                }
                            })
                        }
                        if !w.errors.isEmpty {
                            Text("일부 정보를 확인하지 못했어요\n" + w.errors.map { "· \($0)" }.joined(separator: "\n"))
                                .font(YMFont.caption1Regular)
                                .foregroundStyle(YMColor.labelSecondary)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                        }
                    } else {
                        hint(model.loading ? "가져오는 중…" : "오른쪽 위 + 버튼으로 수업을 추가해 보세요. eTL을 연결하면 수강 중인 수업도 자동으로 가져와요.")
                    }
                    Spacer().frame(height: 24)
                    changesSection
                }
                .padding(.horizontal, 8)
            }
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            model.onStart()
            handleIntent()
        }
        .sheet(isPresented: changeSheetBinding) {
            TimetableChangeSheet(
                courses: model.weekly?.courses ?? [],
                draft: draftState,
                onDismiss: closeChange,
                onSave: { c in
                    model.add(c)
                    closeChange()
                },
                onAskAssistant: { d in
                    closeChange()
                    if let openChat { openChat(d.prefill) } else {
                        app.chatController().input = d.prefill
                        dismiss()
                    }
                }
            )
        }
        .sheet(item: $customEditing) { edit in
            CustomCourseSheet(
                course: edit.course,
                onDismiss: { customEditing = nil },
                onSave: { c in
                    try model.saveCustom(c)
                    customEditing = nil
                },
                onDelete: { id in
                    model.deleteCustom(id)
                    customEditing = nil
                }
            )
        }
        .navigationDestination(isPresented: $showCourseAlerts) { CourseAlertsView() }
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(YMFont.body2Regular)
            .foregroundStyle(YMColor.labelSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
    }

    private var changesSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Text("보강 · 휴강")
                    .font(YMFont.headline1Bold)
                    .foregroundStyle(YMColor.label)
                    .frame(maxWidth: .infinity, alignment: .leading)
                SchedIconButton(systemName: "plus", label: "보강·휴강 추가", tint: YMColor.brandPrimary, size: 24) { addingChange = true }
            }
            Spacer().frame(height: 8)
            if model.changes.isEmpty {
                Text("예정된 보강·휴강이 없어요")
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.labelSecondary)
            }
            ForEach(model.changes, id: \.id) { ch in
                HStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("\(ch.date.monthValue)/\(ch.date.dayOfMonth)(\(TimetableLogic.weekdayOf(ch.date))) \(ch.kind) · \(ch.course)")
                            .font(YMFont.body2Medium)
                            .foregroundStyle(YMColor.label)
                        let detail = [ch.start.map { $0 + (ch.end.map { "~\($0)" } ?? "") }, ch.room, ch.note].compactMap { $0 }.joined(separator: " · ")
                        if !detail.isEmpty {
                            Text(detail)
                                .font(YMFont.caption1Regular)
                                .foregroundStyle(YMColor.labelSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    SchedIconButton(systemName: "trash", label: "지우기", size: 18) { model.delete(ch) }
                }
                .padding(.leading, 14)
                .padding(.trailing, 4)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
                .padding(.bottom, 8)
            }
            Spacer().frame(height: 32)
        }
        .padding(.horizontal, 16)
    }

    private var changeSheetBinding: Binding<Bool> {
        Binding(get: { addingChange || draftState != nil }, set: { if !$0 { closeChange() } })
    }

    private func closeChange() {
        addingChange = false
        draftState = nil
    }

    private func handleIntent() {
        guard !handledIntent else { return }
        handledIntent = true
        guard let d = draft, d.timetable else { return }
        if SchedStores.changes.load().contains(where: { $0.sourceAnnouncementId == d.announcementId }) {
            ToastCenter.shared.show("이 공지는 이미 시간표에 반영했어요", long: true)
            return
        }
        draftState = d
    }
}
