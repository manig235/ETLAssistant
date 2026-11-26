import ChatKit
import ETLKit
import NotifyKit
import SwiftUI

/// 설정(Kotlin `SettingsActivity` / `SettingsScreen.kt` `SettingsRoot`). 채팅 NavigationStack에 `AppRoute.settings`로 push된다.
/// 섹션 순서는 원본 그대로: ChatGPT 계정 · eTL 토큰 · 알림 · 대화 · AI 모델 · OpenAI API 키 (고급) · 기타 · 앱 정보.
/// 원본의 정확한 알림·배터리 행, 업데이트 알림·확인은 iOS에 없어 뺐다(INTEGRATION.md). 캘린더·받은 파일 섹션은 2단계에서 옮겼다.
/// "홈 화면에 위젯 추가하기"는 iOS 앱이 위젯을 직접 추가할 수 없어 추가 순서를 알려 주는 안내 화면(`WidgetGuideView`)을 연다.
struct SettingsView: View {
    /// 과목별 알림 화면. `AppRoute`에 과목별 알림 경로가 생기기 전까지 통합 담당이 넘겨 준다(INTEGRATION.md).
    var courseAlerts: () -> AnyView = { AnyView(SettingsPendingScreen(title: "과목별 알림")) }

    @Environment(AppState.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var model: SettingsModel?

    var body: some View {
        VStack(spacing: 0) {
            YMBackTopBar(title: "설정") { dismiss() }
            if let model {
                SettingsContent(model: model, courseAlerts: courseAlerts)
            } else {
                Spacer()
            }
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            if model == nil { model = SettingsModel(auth: app.auth) }
        }
    }
}

private struct SettingsContent: View {
    @Bindable var model: SettingsModel
    let courseAlerts: () -> AnyView

    @Environment(AppState.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var appearance = AppAppearanceStore.shared

    @State private var apiKeyInput = ""
    @State private var confirmDisconnect = false
    @State private var addingWindow = false
    @State private var feedbackOpen = false
    @State private var showModelPicker = false
    @State private var showCourseAlerts = false
    @State private var picking: TimeTarget?
    @State private var profileMessage: String?

    enum TimeTarget: Identifiable, Hashable {
        case brief(Int), quietStart, quietEnd
        var id: String {
            switch self {
            case .brief(let i): "brief\(i)"
            case .quietStart: "quietStart"
            case .quietEnd: "quietEnd"
            }
        }
    }

    private var auth: AssistantAuth { app.auth }
    private var is24h: Bool { SettingsLogic.is24Hour }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                accountSection
                studyDataSection
                if !StudyDataProfile.isDemo { etlSection }
                notifySection
                if !StudyDataProfile.isDemo {
                SettingsSection(title: "캘린더") { YMCard { NavigationLink { CalendarSettingsView() } label: { SettingsLinkRowLabel(title: "캘린더 연동", desc: CalendarSyncService.summary()) }.buttonStyle(YMPressStyle()) } }
                }
                SettingsSection(title: "받은 파일") { YMCard { CleanupSettingsContent() } } // 받은 파일 자동 정리(Kotlin `DownloadCleanupSettingsContent`)
                toneSection
                modelSection
                apiKeySection
                miscSection
                aboutSection
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .scrollDismissesKeyboard(.interactively)
        .overlay {
            if addingWindow {
                WindowAddDialog(current: NotifySettings.normalizeWindows(model.notify.deadlineWindowsMinutes),
                                onDismiss: { addingWindow = false },
                                onAdd: { m in
                                    addingWindow = false
                                    model.addWindow(m)
                                })
            }
        }
        .animation(.easeOut(duration: 0.15), value: addingWindow)
        .task { await model.refreshAll() }
        .onAppear { model.refreshLocal() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refreshAll() } }
        }
        .onChange(of: auth.loginInProgress) { was, now in
            if was && !now { model.loginFinished(app: app) }
        }
        .onChange(of: model.tokenFlow.tokenState) { _, state in
            model.etlTokenStateChanged(state, app: app)
        }
        .alert("eTL 연결을 해제할까요?", isPresented: $confirmDisconnect) {
            Button("취소", role: .cancel) {}
            Button("해제", role: .destructive) { model.disconnectEtl(app: app) }
        } message: {
            Text("저장한 eTL 토큰과, 앱이 eTL에서 받아 둔 과제 목록·시간표·강의계획서 캐시를 이 폰에서 지워요. " +
                 "대화, 받은 파일, 알림 기록, 내 마감은 그대로 남아요. eTL 웹사이트의 토큰은 eTL 설정에서 따로 지워 주세요.")
        }
        .alert("건의사항 · 오류 제보", isPresented: $feedbackOpen) {
            Button("닫기", role: .cancel) {}
            Button("오픈채팅 열기") { openFeedback() }
        } message: {
            Text("앱에 오류가 있나요? 오픈채팅에 접속해서 혼내주세요!\n영민이한테 원하는 기능도 여기서 알려주세요!")
        }
        .sheet(item: $picking) { target in
            TimePickerSheet(initial: minutes(for: target)) { set(target, $0) }
        }
        .sheet(isPresented: $showModelPicker) {
            if let chat = app.chat { ChatModelPicker(controller: chat) }
        }
        .navigationDestination(isPresented: $showCourseAlerts) { courseAlerts() }
    }

    private var studyDataSection: some View {
        SettingsSection(title: "학습 데이터") {
            YMCard {
                Text(StudyDataProfile.isDemo ? "가상 eTL 테스트 중" : "실제 eTL 사용 중")
                    .font(YMFont.body1Bold).foregroundStyle(YMColor.label)
                SettingsCaption(StudyDataProfile.isDemo
                    ? "학교 토큰 없이 가상 과목·과제·공지·파일을 조회해요. 답변은 실제 AI가 만들고, 내 일정 변경도 승인한 뒤 가상 저장소에 적용해요. 실제 캘린더·위젯은 연결하지 않아요."
                    : "가상 eTL로 바꿔도 실제 토큰·과제·내 일정·대화는 따로 보관해요.")
                    .padding(.top, 8)
                SettingsSoftButton(StudyDataProfile.isDemo ? "실제 eTL로 전환 예약" : "가상 eTL로 전환 예약") {
                    StudyDataProfile.selectNextLaunch(demo: !StudyDataProfile.isDemo)
                    profileMessage = "전환을 예약했어요. 앱 전환 화면에서 영민이를 완전히 종료한 뒤 다시 열어 주세요. 현재 작업은 지금 모드에서 계속돼요."
                }.padding(.top, 12)
                if StudyDataProfile.isDemo {
                    SettingsSoftButton("가상 데이터 초기화 예약") {
                        StudyDataProfile.selectNextLaunch(demo: true, reset: true)
                        profileMessage = "다음 실행에 가상 대화·파일·내 일정을 초기화해요. 앱을 완전히 종료한 뒤 다시 열어 주세요. 실제 데이터와 ChatGPT 로그인은 유지돼요."
                    }.padding(.top, 8)
                }
                if let profileMessage { SettingsCaption(profileMessage).padding(.top, 12) }
            }
        }
    }

    // MARK: 1. ChatGPT 계정

    private var accountSection: some View {
        let status = model.accountStatus
        let loggingIn = auth.loginInProgress
        let loggedIn = auth.account.loggedIn
        return SettingsSection(title: "ChatGPT 계정") {
            YMCard {
                SettingsStatusBanner(text: status.text, tone: status.tone, loading: status.loading)
                if loggingIn {
                    loginPanel.padding(.top, 12)
                }
                YMFlowLayout(spacing: 8, lineSpacing: 8) {
                    if loggedIn {
                        SettingsSoftButton("다른 계정으로 로그인", enabled: !loggingIn) { model.loginBrowser() }
                    } else {
                        SettingsMainButton("브라우저로 로그인", enabled: !loggingIn) { model.loginBrowser() }
                    }
                    SettingsSoftButton("로그아웃", enabled: auth.account.kind == .chatgpt) { model.logout() }
                }
                .padding(.top, 12)
            }
        }
    }

    /// 로그인 진행 패널. iOS는 앱 안 브라우저 시트로 로그인해 기기 코드 방식이 없다(스피너 + 다시 열기 + 취소).
    private var loginPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ProgressView().controlSize(.small)
                Text("브라우저에서 ChatGPT 로그인을 마치세요. 끝나면 이 화면으로 돌아와요.")
                    .font(YMFont.body2Medium)
                    .foregroundStyle(YMColor.label)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                SettingsSoftButton("브라우저 다시 열기") { auth.reopenBrowser() }
                SettingsSoftButton("취소") { auth.cancelLogin() }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(YMColor.background))
    }

    // MARK: 2. eTL 토큰

    private var etlSection: some View {
        @Bindable var flow = model.tokenFlow
        return SettingsSection(title: "eTL 토큰") {
            YMCard {
                if model.hasEtlToken {
                    SettingsStatusBanner(text: "저장됨 (Keychain으로 암호화)", tone: .positive)
                } else {
                    SettingsStatusBanner(text: "저장된 토큰이 없어요", tone: .caution)
                }
                SettingsSoftButton("eTL 토큰 만들러 가기") { open(SettingsLogic.ETL_TOKEN_URL) }
                    .padding(.top, 12)
                YMTextField(title: model.hasEtlToken ? "다른 토큰으로 바꾸기" : "eTL 토큰",
                            placeholder: "토큰을 붙여넣어 주세요",
                            text: $flow.tokenInput,
                            secure: true,
                            description: "이 폰의 Keychain에 암호화해서 저장해요.")
                    .padding(.top, 16)
                    .onSubmit { if canSaveToken { model.saveEtlToken() } }
                YMFlowLayout(spacing: 8, lineSpacing: 8) {
                    SettingsMainButton("저장", enabled: canSaveToken, loading: model.etlChecking) { model.saveEtlToken() }
                    SettingsSoftButton("eTL 연결 해제", enabled: model.hasEtlToken) { confirmDisconnect = true }
                }
                .padding(.top, 12)
            }
        }
    }

    private var canSaveToken: Bool {
        !model.tokenFlow.tokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.etlChecking
    }

    // MARK: 3. 알림

    private var notifySection: some View {
        let n = model.notify
        return SettingsSection(title: "알림") {
            YMCard {
                if !model.hasEtlToken {
                    SettingsStatusBanner(text: "eTL 토큰을 먼저 저장해야 알림이 동작해요.", tone: .caution)
                        .padding(.bottom, 8)
                }
                SettingsSwitchRow(title: "마감 임박 알림", desc: NotifyUiFormat.windowsSummary(n.deadlineWindowsMinutes),
                                  isOn: n.deadlineAlerts) { v in model.updateNotify { $0.deadlineAlerts = v } }
                if n.deadlineAlerts { deadlineWindows(n) }
                SettingsSwitchRow(title: "새 공지 알림", desc: "수강 중인 강의에 새 공지가 올라오면 알려줘요(보강·휴강 공지는 따로 표시)",
                                  isOn: n.announcementAlerts) { v in model.updateNotify { $0.announcementAlerts = v } }
                SettingsSwitchRow(title: "새 과제·마감 변경·성적 알림", desc: "과제가 새로 올라오거나, 마감이 바뀌거나, 점수가 등록되면 알려줘요",
                                  isOn: n.changeAlerts) { v in model.updateNotify { $0.changeAlerts = v } }
                SettingsSwitchRow(title: "제출 직후 확인",
                                  desc: "과제를 내면 eTL 제출 기록을 살펴보고 빈 파일·허용되지 않은 형식·첨부 없음 같은 문제가 있으면 바로 알려줘요",
                                  isOn: n.submitAlerts) { v in model.updateNotify { $0.submitAlerts = v } }
                if n.submitAlerts {
                    SettingsSwitchRow(title: "정상 제출도 알리기", desc: "문제가 없을 때도 \"제출 확인됐어요\"를 소리·팝업 없이 알림창에 남겨요",
                                      isOn: n.submitOkAlerts) { v in model.updateNotify { $0.submitOkAlerts = v } }
                }
                SettingsSwitchRow(title: "새 강의자료 알림",
                                  desc: "4시간마다 과목별 파일 목록을 보고 새 자료가 올라오면 알려줘요. 누르면 비서에게 받아 달라고 할 수 있어요",
                                  isOn: n.newFileAlerts) { v in model.updateNotify { $0.newFileAlerts = v } }
                SettingsSwitchRow(title: "앱을 열 때 등록할 공지 보여 주기",
                                  desc: "아직 넣지 않은 시험·과제·동영상 강의·보강·휴강 공지가 새로 오면 채팅 화면에서 한 번 보여 줘요",
                                  isOn: n.pendingSheet) { v in model.updateNotify { $0.pendingSheet = v } }
                SettingsSwitchRow(title: "시험 끝나면 수고했어요 알림",
                                  desc: "내 일정에 넣은 시험에 끝 시각이 있으면 끝난 뒤 한 번 알려줘요(방해 금지 시간에는 보내지 않아요)",
                                  isOn: n.examDoneAlerts) { v in model.updateNotify { $0.examDoneAlerts = v } }
                SettingsSwitchRow(title: "수업 알림", desc: "시간표(수강편람 + 보강·휴강)의 수업 시작 \(n.classLeadMinutes)분 전에 알려줘요",
                                  isOn: n.classAlerts) { v in model.updateNotify { $0.classAlerts = v } }
                if n.classAlerts {
                    YMFlowLayout(spacing: 8, lineSpacing: 8) {
                        ForEach([5, 10, 15, 30], id: \.self) { m in
                            YMChip("\(m)분 전", isActive: n.classLeadMinutes == m) { model.updateNotify { $0.classLeadMinutes = m } }
                        }
                    }
                    .padding(.top, 4)
                    .padding(.bottom, 4)
                }
                SettingsLinkRow(title: "과목별 알림", desc: model.courseAlertsSummary.isEmpty ? nil : model.courseAlertsSummary) {
                    showCourseAlerts = true
                }
                SettingsSwitchRow(title: "정기 브리핑", desc: "마감·누락·새 공지를 정해진 시각에 요약해서 보내줘요",
                                  isOn: n.briefEnabled) { v in model.updateNotify { $0.briefEnabled = v } }
                if n.briefEnabled { briefTimes(n) }
            }
            YMCard {
                SettingsSwitchRow(title: "방해 금지", desc: "방해 금지 시간에는 마감·공지 알림을 미뤄요",
                                  isOn: n.quietEnabled) { v in model.updateNotify { $0.quietEnabled = v } }
                if n.quietEnabled {
                    YMFlowLayout(spacing: 8, lineSpacing: 8) {
                        SettingsEditableValue(text: "시작 " + NotifyUiFormat.formatTime(n.quietStartMinutes, is24h),
                                              clickLabel: "방해 금지 시작 시각 바꾸기") { picking = .quietStart }
                        SettingsEditableValue(text: "종료 " + NotifyUiFormat.formatTime(n.quietEndMinutes, is24h),
                                              clickLabel: "방해 금지 종료 시각 바꾸기") { picking = .quietEnd }
                    }
                    .padding(.top, 4)
                    SettingsCaption("정기 브리핑은 방해 금지와 무관하게 정해진 시각에 와요.")
                        .padding(.vertical, 8)
                }
                SettingsSwitchRow(title: "잠금화면에서 알림 내용 숨기기", desc: nil,
                                  isOn: n.hideContentOnLockScreen) { v in model.updateNotify { $0.hideContentOnLockScreen = v } }
            }
            YMCard {
                if model.notifyGranted {
                    SettingsStatusBanner(text: "알림 권한: 허용됨", tone: .positive)
                } else {
                    SettingsStatusBanner(text: "알림 권한: 꺼져 있어요", tone: .caution)
                    SettingsSoftButton("권한 요청") { Task { await model.requestNotifyPermission(openURL: openURL) } }
                        .padding(.top, 10)
                }
                SettingsCaption(model.lastCheckText).padding(.top, 16)
                SettingsCaption(model.lastBriefText).padding(.top, 2)
                if SettingsHooks.checkNow != nil || SettingsHooks.briefNow != nil {
                    YMFlowLayout(spacing: 8, lineSpacing: 8) {
                        if let run = SettingsHooks.checkNow {
                            SettingsSoftButton("지금 확인") { model.manualRun(run) }
                        }
                        if let run = SettingsHooks.briefNow {
                            SettingsSoftButton("브리핑 미리 보내기") { model.manualRun(run) }
                        }
                    }
                    .padding(.top, 12)
                }
            }
        }
    }

    @ViewBuilder
    private func deadlineWindows(_ n: NotifySettings) -> some View {
        let windows = NotifySettings.normalizeWindows(n.deadlineWindowsMinutes)
        YMFlowLayout(spacing: 8, lineSpacing: 8) {
            ForEach(SettingsLogic.windowChips(windows), id: \.self) { m in
                SettingsRemovableChip(label: NotifyUiFormat.windowLabel(m) + " 전") { model.removeWindow(m) }
            }
            if SettingsLogic.canAddWindow(windows) {
                YMChip("+ 추가") {
                    model.windowError = nil
                    addingWindow = true
                }
            }
        }
        .padding(.top, 4)
        .padding(.bottom, 4)
        if let error = model.windowError {
            SettingsCaption(error, color: YMColor.negativeForeground)
        }
        if !SettingsLogic.isDefaultWindows(windows) {
            YMTextButton("기본값으로 되돌리기") { model.resetWindows() }
        }
        Spacer().frame(height: 4)
    }

    @ViewBuilder
    private func briefTimes(_ n: NotifySettings) -> some View {
        let times = NotifyUiFormat.normalizeBriefTimes(n.briefMinutesOfDay)
        Text("브리핑 시각")
            .font(YMFont.body2Medium)
            .foregroundStyle(YMColor.label)
            .padding(.top, 4)
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(times.enumerated()), id: \.offset) { index, minutes in
                HStack(spacing: 4) {
                    SettingsEditableValue(text: NotifyUiFormat.formatTime(minutes, is24h), clickLabel: "브리핑 시각 바꾸기") {
                        picking = .brief(index)
                    }
                    if index == 1 {
                        YMTextButton("삭제") {
                            model.updateNotify { $0.briefMinutesOfDay = SettingsLogic.removeSecondBriefTime($0.briefMinutesOfDay) }
                        }
                    }
                }
            }
        }
        .padding(.top, 8)
        .padding(.bottom, times.count < 2 ? 0 : 8)
        if times.count < 2 {
            YMTextButton("+ 시각 추가") {
                model.updateNotify { $0.briefMinutesOfDay = SettingsLogic.addBriefTime($0.briefMinutesOfDay) }
            }
        }
    }

    private func minutes(for t: TimeTarget) -> Int {
        let n = model.notify
        switch t {
        case .brief(let i):
            let times = NotifyUiFormat.normalizeBriefTimes(n.briefMinutesOfDay)
            return times.indices.contains(i) ? times[i] : times[0]
        case .quietStart: return n.quietStartMinutes
        case .quietEnd: return n.quietEndMinutes
        }
    }

    private func set(_ t: TimeTarget, _ picked: Int) {
        let m = min(max(picked, 0), 1439)
        switch t {
        case .brief(let i): model.updateNotify { $0.briefMinutesOfDay = SettingsLogic.replaceBriefTime($0.briefMinutesOfDay, i, m) }
        case .quietStart: model.updateNotify { $0.quietStartMinutes = m }
        case .quietEnd: model.updateNotify { $0.quietEndMinutes = m }
        }
    }

    // MARK: 대화

    private var toneSection: some View {
        SettingsSection(title: "대화") {
            YMCard {
                SettingsSwitchRow(title: "반말로 대화", desc: "영민이가 친구처럼 반말로 답해요", isOn: model.casualTone) { model.setCasualTone($0) }
                SettingsSwitchRow(title: "공개 기출 자료 찾기", desc: "시험 자료를 만들 때 MIT OpenCourseWare의 공개 기출·연습 문제를 찾아 함께 써요",
                                  isOn: model.studyLookup) { model.setStudyLookup($0) }
                SettingsCaption("찾을 주제(검색어)만 MIT로 가요. eTL 정보·파일·개인 정보는 보내지 않아요.").padding(.top, 4)
            }
        }
    }

    // MARK: AI 모델

    /// 원본은 `ModelPickerContent`를 카드 없이 그대로 넣는다. iOS는 같은 내용을 `ChatModelPicker` 시트로 연다(INTEGRATION.md).
    private var modelSection: some View {
        let label = app.chat?.choiceLabel ?? ModelCatalog.choiceLabel(auth.settings.modelChoice, [])
        return SettingsSection(title: "AI 모델") {
            YMCard {
                SettingsLinkRow(title: label, desc: "다음 질문부터 적용돼요. 잘 모르겠으면 '자동'을 쓰세요.") {
                    if app.chat == nil { _ = app.chatController() }
                    showModelPicker = true
                }
            }
        }
    }

    // MARK: OpenAI API 키 (고급)

    private var apiKeySection: some View {
        SettingsSection(title: "OpenAI API 키 (고급)") {
            YMCard {
                SettingsCaption("ChatGPT 계정 대신 본인의 API 키를 쓸 수 있어요. 사용한 만큼 본인의 OpenAI 계정에 요금이 청구돼요. " +
                                "키는 이 폰의 Keychain에 암호화해 저장하고, OpenAI에 요청할 때만 써요.")
                Group {
                    if !model.hasApiKey {
                        SettingsStatusBanner(text: "저장된 API 키가 없어요", tone: .neutral)
                    } else if let masked = model.apiKeyMasked {
                        SettingsStatusBanner(text: "저장됨 · \(masked)", tone: .positive)
                    } else {
                        SettingsStatusBanner(text: "저장된 키를 읽지 못했어요(삭제 후 다시 저장하세요)", tone: .caution)
                    }
                }
                .padding(.top, 12)
                YMTextField(title: "API 키", placeholder: "sk-…", text: $apiKeyInput, secure: true)
                    .padding(.top, 16)
                YMFlowLayout(spacing: 8, lineSpacing: 8) {
                    SettingsMainButton("저장", enabled: !apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                        Task { if await model.saveApiKey(apiKeyInput) { apiKeyInput = "" } }
                    }
                    SettingsSoftButton("삭제", enabled: model.hasApiKey) { Task { await model.clearApiKey(app: app) } }
                }
                .padding(.top, 12)
                SettingsSwitchRow(title: "API 키로 사용", desc: "끄면 ChatGPT 계정을 써요", isOn: model.useApiKey) { v in
                    Task { await model.setUseApiKey(v, app: app) }
                }
                .padding(.top, 8)
            }
        }
    }

    // MARK: 기타

    private var miscSection: some View {
        SettingsSection(title: "기타") {
            YMCard {
                Text("화면 모드").font(YMFont.body2Medium).foregroundStyle(YMColor.label)
                YMFlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(ThemeMode.allCases) { mode in
                        YMChip(mode.label, isActive: appearance.mode == mode) { appearance.mode = mode }
                    }
                }
                .padding(.top, 10)
                .padding(.bottom, 20)
                NavigationLink(value: AppRoute.notifyHistory) { SettingsLinkRowLabel(title: "알림 기록") }
                    .buttonStyle(YMPressStyle())
                NavigationLink(value: AppRoute.timetable) { SettingsLinkRowLabel(title: "시간표 · 보강/휴강") }
                    .buttonStyle(YMPressStyle())
                NavigationLink(value: AppRoute.mySchedule) { SettingsLinkRowLabel(title: "내 일정") }
                    .buttonStyle(YMPressStyle())
                // 안드로이드와 같은 자리. iOS는 앱이 위젯을 추가할 수 없어 안내 화면을 연다. 가상 eTL에서도 안내는 보이되 실제 자료만 보인다고 알린다.
                NavigationLink { WidgetGuideView() } label: {
                    SettingsLinkRowLabel(title: "홈 화면에 위젯 추가하기",
                                         desc: StudyDataProfile.isDemo ? "위젯에는 실제 eTL 자료만 보여요" : nil)
                }
                .buttonStyle(YMPressStyle())
                SettingsLinkRow(title: "처음 안내 다시 보기") { app.showOnboarding() }
            }
        }
    }

    // MARK: 앱 정보

    private var aboutSection: some View {
        SettingsSection(title: "앱 정보") {
            YMCard {
                Text("영민이").font(YMFont.body1Bold).foregroundStyle(YMColor.label)
                SettingsCaption("만든 사람: 박영민 · 버전 \(Self.version)").padding(.top, 4)
                SettingsCaption("서울대학교의 공식 앱이 아니에요. 개발자는 개인정보를 수집하지 않아요. 질문·첨부와 비서가 불러온 정보는 OpenAI로 전송돼요.")
                    .padding(.top, 10)
                NavigationLink(value: AppRoute.privacyPolicy) { SettingsLinkRowLabel(title: "개인정보 처리방침") }
                    .buttonStyle(YMPressStyle())
                    .padding(.top, 4)
                SettingsHyperLink(text: "건의사항 · 오류 제보", clickLabel: "제보 안내 열기") { feedbackOpen = true }
            }
        }
    }

    static var version: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0.3.3"
    }

    // MARK: 링크

    private func open(_ s: String) {
        guard let url = URL(string: s) else { return }
        openURL(url) { accepted in
            if !accepted { ToastCenter.shared.show("링크를 열 앱이 없어요: \(s)", long: true) }
        }
    }

    private func openFeedback() { open(SettingsLogic.FEEDBACK_URL) }
}

/// 아직 연결되지 않은 하위 화면 자리(통합 전 과목별 알림).
struct SettingsPendingScreen: View {
    let title: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            YMBackTopBar(title: title) { dismiss() }
            Spacer()
            Text("아직 만들지 않은 화면이에요.")
                .font(YMFont.body2Regular)
                .foregroundStyle(YMColor.labelSecondary)
            Spacer()
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}
