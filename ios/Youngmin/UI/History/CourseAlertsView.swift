import ETLKit
import NotifyKit
import SwiftUI

/// 과목별 알림(Kotlin `CourseAlertsActivity`): 이번 학기 과목마다 "알림 받기 / 알림 끄기 / 숨기기".
/// 목록은 최근에 받은 과목을 바로 보여 주고(없으면 받아 둔 과제·시간표의 과목), 6시간 지났으면 뒤에서 eTL 강의 목록을 다시 받는다.
/// 순서는 과목명순으로 고정하고, 보는 중에 새로 받으면 새 과목만 끝에 붙인다.
struct CourseAlertsView: View {
    @State private var rows: [CourseFilterLogic.CourseRow] = []
    @State private var hideTimetable: Set<Int64> = []
    @State private var loading = false
    @State private var hasToken = false

    @Environment(\.dismiss) private var dismiss

    private static let STALE_MS: Int64 = 6 * 3600 * 1000

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SchedTopBar(title: "과목별 알림", onBack: { dismiss() }) {
                if loading {
                    ProgressView().controlSize(.small).frame(width: 48, height: 48)
                } else if hasToken {
                    SchedIconButton(systemName: "arrow.clockwise", label: "과목 목록 새로 받기", action: refresh)
                }
            }
            Text("알림 끄기: 이 과목의 마감·공지·새 과제·성적·수업 알림을 보내지 않아요.\n" +
                 "숨기기: 알림도 끄고 내 일정·브리핑·일정 위젯에서도 과제와 공지를 빼요. 시간표에서도 뺄지는 과목마다 고를 수 있어요.\n" +
                 "비서에게 물어보면 숨긴 과목도 답해요. 설정은 이 폰에만 저장돼요.")
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 24)
            if loading && !rows.isEmpty {
                Text("과목 목록을 새로 받는 중… 새 과목은 맨 아래에 붙어요")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.brandPrimary)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 4)
            }
            Spacer().frame(height: 12)
            if rows.isEmpty {
                Text(!hasToken ? "eTL 토큰을 저장하면 수강 중인 과목이 보여요" : (loading ? "과목 목록을 받는 중…" : "과목을 찾지 못했어요"))
                    .font(YMFont.body1Medium)
                    .foregroundStyle(YMColor.labelSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(rows, id: \.course.id) { r in card(r) }
                        Spacer().frame(height: 24)
                    }
                    .ymReadableWidth(YMLayout.formWidth - 32)
                    .padding(.horizontal, 16)
                }
            }
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            #if DEBUG
            DemoScheduleSeed.seed(.courses)
            #endif
            hasToken = SchedStores.hasToken
            reload()
            let knownAt = SchedStores.courses.loadAll().knownAt
            let age = SchedFormat.nowMs() - knownAt
            if !(age >= 0 && age < Self.STALE_MS) { refresh() }
        }
    }

    private func card(_ r: CourseFilterLogic.CourseRow) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(TimetableLogic.shortName(r.course.name))
                .font(YMFont.body1Medium)
                .foregroundStyle(YMColor.label)
            if r.stale {
                Text("이번 학기 목록에 없는 과목")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
            }
            Spacer().frame(height: 8)
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(CourseMode.allCases, id: \.self) { m in
                    // 셋 중 하나를 고르는 칩이라 접근성에 선택 상태를 알린다(라디오 버튼처럼).
                    YMChip(m.label, isActive: r.mode == m) {
                        if r.mode != m {
                            SchedStores.courses.setMode(r.course, m)
                            reload()
                        }
                    }
                    .accessibilityAddTraits(r.mode == m ? .isSelected : [])
                }
            }
            if r.mode == .HIDE {
                Spacer().frame(height: 8)
                hideTimetableRow(r)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
    }

    /// 줄 전체가 스위치다(원본 `toggleable` Row).
    private func hideTimetableRow(_ r: CourseFilterLogic.CourseRow) -> some View {
        let on = hideTimetable.contains(r.course.id)
        return Button {
            SchedStores.courses.setHideTimetable(r.course.id, !on)
            reload()
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("시간표·오늘 수업에서도 숨기기")
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.label)
                    Text("조교로 듣는 과목처럼 수업에 가지 않는 경우")
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: .constant(on))
                    .labelsHidden()
                    .tint(YMColor.brandPrimary)
                    .allowsHitTesting(false)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("시간표·오늘 수업에서도 숨기기")
        .accessibilityValue(on ? "켬" : "끔")
        .accessibilityAddTraits(.isButton)
    }

    private func reload() {
        let stored = SchedStores.courses.loadAll()
        let fallback = stored.known.isEmpty
            ? CourseFilterLogic.fallbackCourses(SchedStores.snapshot.load()?.assignments ?? [], SchedStores.timetable.loadCatalog()?.courses ?? [])
            : []
        rows = CourseFilterLogic.keepOrder(rows.map(\.course.id), CourseFilterLogic.rows(stored.known, fallback, stored.filter))
        hideTimetable = stored.filter.hideTimetable
    }

    /// eTL 강의 목록(이번 학기)을 다시 받는다. 토큰은 eTL에만 보내고 결과·오류 문구에 넣지 않는다.
    private func refresh() {
        if loading || DemoScheduleSeed.isActive { return }
        guard let token = SchedStores.token() else { return }
        loading = true
        Task { @MainActor in
            let msg: String?
            do {
                let list = try await CourseAssignments.currentCourses(try EtlClient(token))
                    .map { CourseRef($0.optLong("id"), $0.str("name") ?? "") }
                    .filter { $0.id > 0 && !SchedFormat.trim($0.name).isEmpty }
                SchedStores.courses.saveKnown(list, SchedFormat.nowMs())
                msg = nil
            } catch is EtlAuthException {
                msg = "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요"
            } catch let e as EtlException {
                msg = "과목 목록을 새로 받지 못했어요: \(e.message)"
            } catch {
                msg = "과목 목록을 새로 받지 못했어요(\(type(of: error)))"
            }
            loading = false
            reload()
            if let msg { ToastCenter.shared.show(msg) }
        }
    }
}
