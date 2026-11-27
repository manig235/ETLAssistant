import ETLKit
import NotifyKit
import SwiftUI

/// "내 일정"(Kotlin `AgendaScreen`, chat/MyDeadlinesActivity.kt): 임박 요약 + 오늘 포함 7일 + 그 이후.
/// eTL 과제(받아 둔 캐시) + 내 마감 + 시험. 내 마감·시험은 여기서 추가·수정·완료·삭제한다.
///
/// - Parameters:
///   - openDeadlineId: 열자마자 고치기 창을 띄울 내 마감(`MyDeadlines.findByItemId`; 알림·알림 기록의 "등록됨 · 열기").
///   - draft: 공지 초안(`schedule-draft`). 시간표 초안이면 시간표 화면으로 넘긴다.
struct MyScheduleView: View {
    var openDeadlineId: Int64?
    var draft: ScheduleDraft?

    @State private var model = MyScheduleModel()
    @State private var editing: MyDeadline?
    @State private var adding = false
    @State private var draftState: ScheduleDraft?
    @State private var deleting: MyDeadline?
    @State private var showOverdue = false
    @State private var showTimetable = false
    @State private var timetableDraft: ScheduleDraft?
    @State private var showCourseAlerts = false
    @State private var handledIntent = false
    @State private var calendarAdd: CalendarAddDraft?

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.schedOpenChat) private var openChat
    @Environment(AppState.self) private var app

    var body: some View {
        let schedule = model.schedule
        VStack(alignment: .leading, spacing: 0) {
            SchedTopBar(title: "내 일정", onBack: { dismiss() }) {
                SchedIconButton(systemName: "calendar.day.timeline.left", label: "수업 시간표") { showTimetable = true }
                if model.loading {
                    ProgressView().controlSize(.small).frame(width: 48, height: 48)
                } else if model.hasToken {
                    SchedIconButton(systemName: "arrow.clockwise", label: "eTL 과제 새로 받기") { model.refresh() }
                }
                SchedIconButton(systemName: "plus", label: "내 마감·시험 추가", tint: YMColor.brandPrimary, size: 24) { adding = true }
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(model.statusText)
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .padding(.horizontal, 24)
                Spacer().frame(height: 8)
                YMFlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(Agenda.Filter.allCases, id: \.self) { f in
                        if f != .CALENDAR || model.calendarOn {
                            YMChip(f.label, isActive: model.filter == f) { model.filter = f }
                        }
                    }
                    YMChip(model.hideDone ? "제출·완료 숨김" : "제출·완료 보기", isActive: !model.hideDone) { model.hideDone.toggle() }
                    // 과목별 알림에서 숨긴 과목의 과제는 여기 보이지 않는다. 누르면 과목별 알림 화면으로 간다.
                    if model.courseFilter.hiddenCount > 0 && (model.filter == .ALL || model.filter == .ASSIGNMENT) {
                        YMChip("숨긴 과목 \(model.courseFilter.hiddenCount)개") { showCourseAlerts = true }
                    }
                }
                .padding(.horizontal, 20)
            }
            // 넓은 화면에서는 아래 목록과 같은 폭으로 가운데에 모은다(아이폰은 그대로).
            .frame(maxWidth: .infinity, alignment: .leading)
            .ymReadableWidth()
            Spacer().frame(height: 8)
            list(schedule)
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            model.onStart()
            handleIntent()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.reload() }
        }
        .task {
            // 30초마다 남은 시간을 다시 그린다(원본과 같다).
            while !Task.isCancelled {
                model.now = Date()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
        .sheet(isPresented: editSheetBinding) {
            DeadlineEditSheet(
                initial: editing,
                countWithoutThis: editing != nil ? model.mine.count - 1 : model.mine.count,
                courses: model.courses,
                startAsExam: model.filter == .EXAM,
                draft: editing == nil ? draftState : nil,
                onDismiss: closeEditor,
                onSave: { new in
                    model.save(old: editing, new: new)
                    closeEditor()
                },
                onAskAssistant: { d in
                    closeEditor()
                    askAssistant(d.prefill)
                },
                onCancelClass: { day, course, ann in model.cancelClass(day, course, ann) }
            )
        }
        .alert(deleteTitle, isPresented: deleteBinding, presenting: deleting) { d in
            if d.repeat != nil, d.occurrence != nil {
                Button("이번 회차만 지우기") { model.delete(d, onlyThis: true) }
                Button("반복 전체 지우기") { model.delete(d, onlyThis: false) }
                Button("취소", role: .cancel) {}
            } else {
                Button("취소", role: .cancel) {}
                Button("삭제", role: .destructive) { model.delete(d, onlyThis: false) }
            }
        } message: { d in
            if d.repeat != nil, let occ = d.occurrence {
                Text("\"\(d.title)\"은(는) \(MyDeadlines.repeatLabel(d)) 반복돼요. \(Agenda.dateLabel(occ)) 회차만 지울지, 반복 전체를 지울지 골라 주세요.")
            } else {
                Text("\"\(d.title)\"이(가) 목록과 알림에서 사라져요.")
            }
        }
        .overlay {
            if let st = model.submitCheck {
                SubmissionCheckDialog(state: st, onDismiss: { model.submitCheck = nil }, onOpen: { url in
                    if url.hasPrefix("https://myetl.snu.ac.kr/") { SchedLinks.openEtl(url) }
                })
            }
        }
        .animation(.easeOut(duration: 0.15), value: model.submitCheck)
        .sheet(item: $calendarAdd) { d in
            CalendarAddSheet(draft: d.draft) { calendarAdd = nil }.ignoresSafeArea()
        }
        // 캘린더에서 고친 내 마감을 가져왔거나 캘린더가 바뀌면 다시 읽는다.
        .onReceive(NotificationCenter.default.publisher(for: CalendarSyncService.changed)) { _ in
            model.reload()
            model.loadCalendar()
        }
        .navigationDestination(isPresented: $showTimetable) { TimetableView(draft: timetableDraft) }
        .navigationDestination(isPresented: $showCourseAlerts) { CourseAlertsView() }
    }

    // MARK: 목록

    private func list(_ schedule: Agenda.Schedule) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                DeadlineSummary(schedule: schedule, now: model.now, showOverdue: showOverdue,
                                onToggleOverdue: { withAnimation(.easeOut(duration: 0.2)) { showOverdue.toggle() } },
                                onOpen: openRow)
                if showOverdue && !schedule.overdue.isEmpty {
                    Text("마감 지남 · \(schedule.overdue.count)개")
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.negativeForeground)
                        .padding(.leading, 8)
                        .padding(.top, 8)
                    ForEach(schedule.overdue, id: \.id) { r in row(r) }
                }
                section(upcoming: true, schedule)
                section(upcoming: false, schedule)
                Spacer().frame(height: 24)
            }
            .ymReadableWidth(YMLayout.formWidth - 32)
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private func section(upcoming: Bool, _ s: Agenda.Schedule) -> some View {
        let groups = upcoming ? s.upcoming : s.later
        DeadlineSectionHeader(
            title: upcoming ? "앞으로 7일" : "그 이후 마감",
            count: upcoming ? s.upcomingCount : s.laterCount,
            subtitle: upcoming ? "오늘 포함 · \(Agenda.dateLabel(s.today))~\(Agenda.dateLabel(s.lastDay))"
                : "\(Agenda.dateLabel(s.lastDay.plusDays(1)))부터"
        )
        if groups.isEmpty {
            let period = upcoming ? "앞으로 7일 동안" : "그 이후에"
            let what: String = switch model.filter {
            case .EXAM: "시험이 없어요"
            case .CALENDAR: "캘린더 일정이 없어요"
            default: "일정이 없어요"
            }
            Text("\(period) \(model.hideDone ? "남은 " : "")" + what)
                .font(YMFont.body2Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 16)
        }
        ForEach(groups, id: \.date) { g in
            Text(Agenda.dayLabel(g.date, s.today))
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.brandPrimary)
                .padding(.leading, 8)
                .padding(.top, 8)
            ForEach(g.rows, id: \.id) { r in row(r) }
        }
    }

    private func row(_ r: Agenda.Row) -> some View {
        AgendaRowView(
            r: r, now: model.now,
            onClick: { openRow(r) },
            onToggleDone: { if let m = r.mine { model.toggleDone(m) } },
            onDelete: { deleting = r.mine },
            onCheck: r.assignment.flatMap { a in a.submitted ? { model.checkSubmission(a) } : nil },
            onAddToCalendar: (r.kind == .CALENDAR || StudyDataProfile.isDemo) ? nil : { addToCalendar(r) }
        )
    }

    // MARK: 동작

    private func openRow(_ r: Agenda.Row) {
        if let m = r.mine {
            // 반복 마감은 회차가 아니라 반복 전체를 고친다.
            editing = model.seriesOf(m) ?? m
        } else if let e = r.calendar {
            CalendarSyncService.openInCalendarApp(e)
        } else if let url = r.url {
            SchedLinks.openEtl(url)
        }
    }

    /// 캘린더 앱의 일정 추가 화면을 연다(권한 없음, 사용자가 저장). 반복 내 마감은 반복 규칙을 함께 넘긴다.
    private func addToCalendar(_ r: Agenda.Row) {
        if let d = model.calendarDraft(r) { calendarAdd = CalendarAddDraft(draft: d) } else { ToastCenter.shared.show("캘린더에 넣을 수 없어요") }
    }

    private var editSheetBinding: Binding<Bool> {
        Binding(get: { adding || editing != nil || draftState != nil }, set: { if !$0 { closeEditor() } })
    }

    private func closeEditor() {
        adding = false
        editing = nil
        draftState = nil
    }

    private var deleteBinding: Binding<Bool> {
        Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })
    }

    private var deleteTitle: String {
        guard let d = deleting else { return "" }
        if d.isExam { return "이 시험을 지울까요?" }
        if d.repeat != nil && d.occurrence != nil { return "반복 마감을 지울까요?" }
        return "이 마감을 지울까요?"
    }

    /// 열 때 한 번: 위젯·알림에서 온 항목 ID, 공지 초안.
    private func handleIntent() {
        guard !handledIntent else { return }
        handledIntent = true
        if let id = openDeadlineId, let d = MyDeadlines.findByItemId(SchedStores.mine.load(), id) {
            editing = d
        }
        guard let d = draft else { return }
        if d.timetable {
            timetableDraft = d
            showTimetable = true
            return
        }
        if let existing = SchedStores.mine.load().first(where: { $0.sourceAnnouncementId == d.announcementId }) {
            editing = existing
            ToastCenter.shared.show("이 공지로 이미 넣은 일정이에요")
        } else {
            draftState = d
        }
    }

    /// 창을 닫고 채팅 입력창에 요청을 채운다(보내지는 않는다). 공지 내용은 이때만 비서로 간다.
    private func askAssistant(_ prefill: String) {
        if let openChat {
            openChat(prefill)
        } else {
            app.chatController().input = prefill
            dismiss()
        }
    }
}

// MARK: - 임박 요약 · 섹션 머리 · 행

private struct DeadlineSummary: View {
    let schedule: Agenda.Schedule
    let now: Date
    let showOverdue: Bool
    let onToggleOverdue: () -> Void
    let onOpen: (Agenda.Row) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("임박한 마감 요약")
                .font(YMFont.body1Medium)
                .foregroundStyle(YMColor.label)
            Text(schedule.urgent.isEmpty ? "24시간 내 남은 마감이 없어요" : "24시간 내 미완료 마감 \(schedule.urgent.count)개")
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
            ForEach(Array(schedule.urgent.prefix(3)), id: \.id) { r in
                let tappable = r.mine != nil || r.url != nil
                let content = VStack(alignment: .leading, spacing: 0) {
                    Text(r.title)
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.label)
                        .lineLimit(1)
                    Text("\(r.subtitle) · \(Agenda.whenLabel(r, now))")
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
                if tappable {
                    Button { onOpen(r) } label: { content }
                        .buttonStyle(YMPressStyle())
                        .accessibilityHint(r.mine != nil ? "고치기" : "eTL에서 열기")
                } else {
                    content
                }
            }
            if schedule.urgent.count > 3 {
                Text("외 \(schedule.urgent.count - 3)개")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
            }
            if !schedule.overdue.isEmpty {
                HStack {
                    Text("마감 지남 \(schedule.overdue.count)개")
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.negativeForeground)
                    Spacer()
                    YMTextButton(showOverdue ? "접기" : "펼치기", action: onToggleOverdue)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16)
            .fill(schedule.urgent.isEmpty ? YMColor.surfaceSecondary : YMColor.cautionarySurface))
    }
}

private struct DeadlineSectionHeader: View {
    let title: String
    let count: Int
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(title) · \(count)개")
                .font(YMFont.headline2Bold)
                .foregroundStyle(YMColor.label)
            Text(subtitle)
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
        }
        .padding(.leading, 8)
        .padding(.top, 16)
        .padding(.bottom, 4)
    }
}

/// Kotlin `AgendaRow`: 왼쪽 아이콘(완료 체크/과제/시험) · 제목과 "언제 · 부제" · 오른쪽 아이콘(제출 확인·지우기).
/// "캘린더에 추가"는 iOS 일정 추가 화면(`EKEventEditViewController`)을 띄운다.
private struct AgendaRowView: View {
    let r: Agenda.Row
    let now: Date
    let onClick: () -> Void
    let onToggleDone: () -> Void
    let onDelete: () -> Void
    let onCheck: (() -> Void)?
    var onAddToCalendar: (() -> Void)? = nil

    private var clickable: Bool { r.mine != nil || r.url != nil || r.calendar != nil }
    private var clickLabel: String { r.mine != nil ? "고치기" : r.calendar != nil ? "캘린더에서 열기" : "eTL에서 열기" }

    var body: some View {
        HStack(spacing: 0) {
            leading
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    if r.kind == .EXAM {
                        Text("시험")
                            .font(YMFont.caption1Bold)
                            .foregroundStyle(YMColor.cautionaryForeground)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(RoundedRectangle(cornerRadius: 6).fill(YMColor.cautionarySurface))
                    }
                    Text(r.title)
                        .font(YMFont.body1Medium)
                        .foregroundStyle(r.done ? YMColor.labelTertiary : YMColor.label)
                        .strikethrough(r.done)
                        .lineLimit(2)
                }
                Text([Agenda.whenLabel(r, now), r.subtitle].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(r.overdue ? YMColor.negativeForeground : YMColor.labelSecondary)
                    .lineLimit(3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
            if let onCheck {
                SchedIconButton(systemName: "checklist", label: "'\(r.title)' 제출 확인",
                                tint: r.attention ? YMColor.negativeForeground : YMColor.labelSecondary, size: 18, action: onCheck)
            }
            if let onAddToCalendar {
                SchedIconButton(systemName: "calendar.badge.plus", label: "'\(r.title)' 캘린더에 추가", size: 18, action: onAddToCalendar)
            }
            if r.mine != nil {
                SchedIconButton(systemName: "trash", label: "'\(r.title)' 지우기", size: 18, action: onDelete)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .onTapGesture { if clickable { onClick() } }
        .accessibilityAction(named: clickLabel) { if clickable { onClick() } }
    }

    @ViewBuilder
    private var leading: some View {
        switch r.kind {
        case .MINE:
            SchedIconButton(systemName: r.done ? "checkmark.circle" : "circle",
                            label: r.done ? "'\(r.title)' 완료 취소" : "'\(r.title)' 완료",
                            tint: r.done ? YMColor.positiveForeground : YMColor.labelSecondary, size: 22, action: onToggleDone)
        case .ASSIGNMENT:
            Image(systemName: r.done ? "checkmark.circle" : "doc.text")
                .font(.system(size: 21))
                .foregroundStyle(r.done ? YMColor.positiveForeground : YMColor.brandPrimary)
                .frame(width: 48, height: 48)
                .accessibilityHidden(true)
        case .EXAM:
            Image(systemName: "square.and.pencil")
                .font(.system(size: 21))
                .foregroundStyle(YMColor.cautionaryForeground)
                .frame(width: 48, height: 48)
                .accessibilityLabel("시험")
        case .CALENDAR:
            Image(systemName: "calendar")
                .font(.system(size: 21))
                .foregroundStyle(r.calendar.map { Color(calendarArgb: $0.color) } ?? YMColor.labelSecondary)
                .frame(width: 48, height: 48)
                .accessibilityLabel("캘린더 일정")
        }
    }
}

private extension Color {
    /// 캘린더 색(0xAARRGGBB, 알파 0이면 불투명).
    init(calendarArgb argb: Int) {
        let a = (argb >> 24) & 0xFF
        self.init(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255, opacity: a == 0 ? 1 : Double(a) / 255)
    }
}
