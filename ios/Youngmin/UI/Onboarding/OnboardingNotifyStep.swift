import ETLKit
import SwiftUI

/// 4. 알림·방해 금지·받은 파일 자동 정리(Kotlin `NotifyStep`). 배터리 최적화 카드·절전 모드 문구·삼성 안내는 iOS에 없어 뺐다.
/// 정리 기간은 기본 2일로 보이고 "다음"을 눌러야 저장된다(그 전에는 지우지 않는다).
struct OnboardingNotifyStep: View {
    @Bindable var model: OnboardingModel
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var picking: TimeTarget?
    @State private var cleanupPeriod = FilesCleanupService.cleanup.period ?? .DEFAULT

    enum TimeTarget: String, Identifiable {
        case brief, quietStart, quietEnd
        var id: String { rawValue }
    }

    var body: some View {
        OnboardingStepFrame(
            title: "알림을 켜 볼까요?",
            subtitle: "마감과 공지는 AI 없이 앱이 한 시간마다 조용히 확인해서 알려드려요."
        ) {
            if model.notifyGranted {
                YMStatusCard(text: "알림이 허용돼 있어요", positive: true)
            } else {
                YMCard {
                    Text("알림 권한이 필요해요")
                        .font(YMFont.body1Bold)
                        .foregroundStyle(YMColor.label)
                    Text("허용하지 않으면 마감·공지 알림이 오지 않아요.")
                        .font(YMFont.body2Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .padding(.top, 4)
                    YMOutlineButton("알림 허용하기") {
                        Task { await model.requestNotifications(openURL: openURL) }
                    }
                    .padding(.top, 12)
                }
            }
            VStack(alignment: .leading, spacing: 0) {
                SwitchRow(title: "마감 임박 알림", detail: model.deadlineSummary, isOn: $model.deadlineAlerts)
                SwitchRow(title: "새 공지 알림", detail: "수강 중인 강의에 새 공지가 올라오면", isOn: $model.announcementAlerts)
                SwitchRow(title: "정기 브리핑", detail: "마감·누락·새 공지를 한 번에 요약해서", isOn: $model.briefEnabled)
                if model.briefEnabled {
                    YMFlowLayout(spacing: 8, lineSpacing: 8) {
                        ForEach(OnboardingLogic.briefTimeChoices, id: \.self) { m in
                            YMChip(NotifyPrefs.timeLabel(m), isActive: model.briefMinutes == m) { model.briefMinutes = m }
                        }
                        let custom = !OnboardingLogic.briefTimeChoices.contains(model.briefMinutes)
                        YMChip(custom ? "\(NotifyPrefs.timeLabel(model.briefMinutes)) · 바꾸기" : "직접 선택", isActive: custom) {
                            picking = .brief
                        }
                    }
                    .padding(.top, 8)
                }
                SwitchRow(title: "방해 금지 시간",
                          detail: "이 시간에는 마감·공지 알림을 미뤘다가 끝나면 보내줘요(브리핑과 직접 정한 알림은 그대로)",
                          isOn: $model.quietEnabled)
                if model.quietEnabled {
                    HStack(spacing: 8) {
                        YMChip("\(NotifyPrefs.timeLabel(model.quietStart)) 부터", isActive: true) { picking = .quietStart }
                        YMChip("\(NotifyPrefs.timeLabel(model.quietEnd)) 까지", isActive: true) { picking = .quietEnd }
                    }
                    .padding(.top, 8)
                }
            }
            .padding(.top, 20)
            VStack(alignment: .leading, spacing: 0) {
                Text(DownloadCleanupLogic.QUESTION)
                    .font(YMFont.body1Bold)
                    .foregroundStyle(YMColor.label)
                Text(DownloadCleanupLogic.DESC)
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
                CleanupChoices(selected: cleanupPeriod) { cleanupPeriod = $0 }
                    .padding(.top, 10)
                Text(DownloadCleanupLogic.KEEP_HINT)
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
            .padding(.top, 20)
        } buttons: {
            YMPrimaryButton("다음") {
                FilesCleanupService.setPeriod(cleanupPeriod)
                model.applyNotificationChoices()
                model.goNext()
            }
            YMTextButton("나중에 할게요", fullWidth: true) { model.goNext() }
        }
        .task { await model.refreshNotifyStatus() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refreshNotifyStatus() } }
        }
        .sheet(item: $picking) { target in
            TimePickerSheet(initial: minutes(for: target)) { set(target, $0) }
        }
    }

    private func minutes(for t: TimeTarget) -> Int {
        switch t {
        case .brief: return model.briefMinutes
        case .quietStart: return model.quietStart
        case .quietEnd: return model.quietEnd
        }
    }

    private func set(_ t: TimeTarget, _ m: Int) {
        let v = min(max(m, 0), 1439)
        switch t {
        case .brief: model.briefMinutes = v
        case .quietStart: model.quietStart = v
        case .quietEnd: model.quietEnd = v
        }
    }
}

/// 행 전체를 눌러도 토글되는 스위치 행(Kotlin `SwitchRow`): 위아래 12, [제목 / 2 / 설명] + 스위치.
struct SwitchRow: View {
    let title: String
    let detail: String
    @Binding var isOn: Bool

    var body: some View {
        Button { isOn.toggle() } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                    Text(detail).font(YMFont.caption1Regular).foregroundStyle(YMColor.labelSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    .tint(YMColor.brandPrimary)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// 시각 선택(Android `TimePickerDialog`). 휠 + 확인/취소. 기기 24시간제 설정을 따른다.
struct TimePickerSheet: View {
    let initial: Int
    let onPick: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var date = Date()

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                YMTextButton("취소") { dismiss() }
                Spacer()
                YMTextButton("확인") {
                    let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                    onPick((c.hour ?? 0) * 60 + (c.minute ?? 0))
                    dismiss()
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            DatePicker("", selection: $date, displayedComponents: .hourAndMinute)
                .datePickerStyle(.wheel)
                .labelsHidden()
        }
        .onAppear {
            date = Calendar.current.date(bySettingHour: initial / 60, minute: initial % 60, second: 0, of: Date()) ?? Date()
        }
        .presentationDetents([.height(300)])
        .background(YMColor.background)
    }
}
