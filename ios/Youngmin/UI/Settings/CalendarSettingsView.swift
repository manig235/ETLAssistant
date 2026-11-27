import AssistantKit
import ETLKit
import NotifyKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 설정 → 캘린더 연동(Kotlin `CalendarExportActivity`).
/// - 영민이 → 캘린더: 과제·내 마감(시험 포함)을 기기 캘린더에 자동으로 넣고, 캘린더에서 고친 내 마감은 되가져온다.
/// - 캘린더 → 영민이: 고른 캘린더의 일정을 내 일정·브리핑에 보이고, 비서가 그 캘린더를 읽고 일정을 넣게 한다.
/// - 고급(접힘): 일정 파일(.ics) 공유·저장과 자세한 동작 안내.
/// 무거운 일(EventKit)은 `CalendarSyncService`의 한 줄에서 한다.
struct CalendarSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var model = CalendarSettingsModel()
    /// "공유하기" 버튼 위치(창 좌표). iPad 공유 팝오버가 여기에 붙는다.
    @State private var shareAnchor: CGRect?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                YMBackTopBar(title: "캘린더 연동") { dismiss() }
                if model.busy { ProgressView().controlSize(.small).padding(.trailing, 20) }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 32) {
                    exportSection
                    readSection
                    advancedSection
                }
                .ymReadableWidth(YMLayout.formWidth - 48)
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 40)
            }
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { model.reload() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.reload() } }
        .onReceive(NotificationCenter.default.publisher(for: CalendarSyncService.changed)) { _ in model.reload() }
        .sheet(isPresented: $model.picking) {
            CalendarPickSheet(calendars: model.calendars, selected: model.read.calendarIds,
                              onSave: { model.savePicked($0) }, onCancel: { model.cancelPick() },
                              onSettings: openAppSettings, onRetry: { model.reload() })
        }
        .sheet(isPresented: accountPickerBinding) {
            AccountCalendarSheet(calendars: model.accountPicker ?? [],
                                 onChoose: { model.chooseAccount($0) }, onDismiss: { model.dismissAccountPicker() },
                                 onSettings: openAppSettings, onRetry: { model.loadAccountCalendars(enableAfter: model.pendingEnable) })
        }
        .onChange(of: model.shareFile?.id) { _, id in
            guard id != nil, let f = model.shareFile else { return }
            model.shareFile = nil
            YMShareSheet.present([f.url], anchor: shareAnchor)
        }
        .fileExporter(isPresented: $model.exporting, document: model.exportDocument, contentType: .calendarEvent,
                      defaultFilename: model.exportName) { result in
            if case .success = result { ToastCenter.shared.show("저장했어요") }
        }
    }

    private var accountPickerBinding: Binding<Bool> {
        Binding(get: { model.accountPicker != nil }, set: { if !$0 { model.dismissAccountPicker() } })
    }

    private func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }

    // MARK: 영민이 → 캘린더

    private var exportSection: some View {
        let s = model.settings
        return SettingsSection(title: "영민이 → 캘린더") {
            YMCard {
                SettingsSwitchRow(title: "영민이 일정을 캘린더에 자동으로 넣기", desc: "과제·내 마감이 바뀌면 캘린더도 따라 바뀌어요",
                                  isOn: s.enabled, enabled: !model.busy) { on in Task { await model.setEnabled(on) } }
                if let note = model.note {
                    SettingsCaption(note, color: YMColor.cautionaryForeground).padding(.top, 6)
                }
                if s.enabled && !model.permitted {
                    VStack(alignment: .leading, spacing: 8) {
                        SettingsCaption("캘린더 권한이 꺼져 있어요", color: YMColor.negativeForeground)
                        SettingsSoftButton("설정 앱에서 권한 켜기", action: openAppSettings)
                    }
                    .padding(.top, 6)
                }
                Text("넣을 곳")
                    .font(YMFont.body2Medium)
                    .foregroundStyle(YMColor.label)
                    .padding(.top, 12)
                targetOption(model.localTitle, model.localDesc, active: s.target == .LOCAL) { Task { await model.chooseTarget(.LOCAL) } }
                    .padding(.top, 8)
                targetOption("계정 캘린더", "iCloud·구글 등 고른 캘린더에 넣어요. 그 계정의 다른 기기·웹에서도 보여요", active: s.target == .GOOGLE) {
                    Task { await model.chooseTarget(.GOOGLE) }
                }
                SettingsCaption("두 곳에 함께 넣지는 않아요(같은 일정이 두 번 보이지 않게). 캘린더 앱에서 안 보이면 캘린더 앱 → 캘린더 목록에서 켜 주세요.")
                    .padding(.top, 4)
                if s.target == .LOCAL {
                    SettingsCaption(model.localHint).padding(.top, 4)
                } else {
                    SettingsCaption("고른 캘린더는 그 계정과 동기화돼요(일정 제목과 과목 이름이 계정 서버로 가요). 캘린더 앱에서 \"영민이\" 같은 새 캘린더를 먼저 만들어 고르면 다른 일정과 섞이지 않아요.")
                        .padding(.top, 4)
                    HStack(spacing: 8) {
                        Text(s.googleCalendarId == nil ? "아직 고르지 않았어요" : "\"\(s.googleCalendarName)\"")
                            .font(YMFont.body2Medium)
                            .foregroundStyle(YMColor.label)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        SettingsSoftButton("캘린더 고르기", enabled: !model.busy) { Task { await model.pickAccount() } }
                    }
                    .padding(.top, 8)
                }
            }
            YMCard {
                Text("넣을 일정").font(YMFont.body2Medium).foregroundStyle(YMColor.label)
                SettingsSwitchRow(title: "eTL 과제 마감", desc: "알림 확인 때 받아 둔 과제(숨긴 과목 제외)", isOn: s.includeEtl) { on in
                    model.setOptions { $0.includeEtl = on }
                }
                SettingsSwitchRow(title: "내 마감·시험", desc: "시험은 시작~끝 시간과 장소로 들어가요", isOn: s.includeMine) { on in
                    model.setOptions { $0.includeMine = on }
                }
                SettingsSwitchRow(title: "제출·완료한 항목도 넣기", desc: "끄면 제출하거나 완료한 항목은 캘린더에서 지워요", isOn: s.includeDone) { on in
                    model.setOptions { $0.includeDone = on }
                }
                SettingsSwitchRow(title: "캘린더에서 고친 것 가져오기",
                                  desc: "캘린더 앱에서 내 마감 일정을 옮기거나 이름을 바꾸면 앱에도 반영해요. eTL 과제 일정은 원래대로 돌아가요",
                                  isOn: s.pullBack) { on in model.setOptions { $0.pullBack = on } }
                if let status = model.status {
                    SettingsCaption(status).padding(.top, 8)
                }
                SettingsSoftButton("지금 맞추기", enabled: s.enabled && !model.busy) { Task { await model.syncNow() } }
                    .padding(.top, 8)
                SettingsCaption("끄면 \"영민이\" 캘린더를 지우거나(이 기기에만) 앱이 넣은 일정만 지워요(계정 캘린더). 앱을 그냥 지우면 넣은 일정을 정리할 수 없으니, 지우기 전에 먼저 꺼 주세요.")
                    .padding(.top, 12)
            }
        }
    }

    private func targetOption(_ title: String, _ desc: String, active: Bool, _ action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            YMChip(title, isActive: active) { if !model.busy { action() } }
                .accessibilityLabel("\(title), \(desc)" + (active ? ", 선택됨" : ""))
            SettingsCaption(desc)
        }
        .padding(.vertical, 4)
    }

    // MARK: 캘린더 → 영민이

    private var readSection: some View {
        SettingsSection(title: "캘린더 → 영민이") {
            YMCard {
                SettingsSwitchRow(title: "내 캘린더 일정 보기", desc: "고른 캘린더의 일정을 내 일정(\"캘린더\")과 아침 브리핑(오늘 일정)에 보여 줘요",
                                  isOn: model.read.showEnabled, enabled: !model.busy) { on in Task { await model.setShow(on) } }
                SettingsSwitchRow(title: "비서가 캘린더 보고 등록하기",
                                  desc: "대화에서 부탁하면 비서가 고른 캘린더의 일정을 읽고, 일정을 넣거나 고치거나 지워요",
                                  isOn: model.read.assistantEnabled, enabled: !model.busy) { on in Task { await model.setAssistant(on) } }
                SettingsCaption("비서가 일정을 읽으면 그 내용(제목·시간·장소·메모)이 내 ChatGPT 계정으로 OpenAI에 전달돼요. 비서는 내가 부탁할 때만 쓰고, 바꿀 때마다 채팅에 한 줄로 알려요.")
                if (model.read.showEnabled || model.read.assistantEnabled) && !model.canRead {
                    VStack(alignment: .leading, spacing: 8) {
                        SettingsCaption("캘린더 권한이 꺼져 있어요", color: YMColor.negativeForeground)
                        SettingsSoftButton("설정 앱에서 권한 켜기", action: openAppSettings)
                    }
                    .padding(.top, 6)
                }
                HStack(spacing: 8) {
                    Text(model.selectionLabel)
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.label)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    SettingsSoftButton("볼 캘린더 고르기", enabled: !model.busy) { Task { await model.pick() } }
                }
                .padding(.top, 10)
                if model.read.assistantEnabled {
                    let writable = model.calendars.filter { model.read.calendarIds.contains($0.id) && $0.writable }
                    Text("비서가 새 일정을 넣을 캘린더")
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.label)
                        .padding(.top, 10)
                    if writable.isEmpty {
                        SettingsCaption("고른 캘린더 중 일정을 넣을 수 있는 캘린더가 없어요(읽기 전용만 골랐어요).").padding(.top, 6)
                    }
                    YMFlowLayout(spacing: 8, lineSpacing: 8) {
                        ForEach(writable) { cal in
                            YMChip(cal.name, isActive: model.read.writeCalendarId == cal.id) { model.setWriteCalendar(cal) }
                        }
                    }
                    .padding(.top, 6)
                }
            }
            if !model.changes.isEmpty {
                YMCard {
                    Text("비서가 바꾼 일정").font(YMFont.body2Medium).foregroundStyle(YMColor.label)
                    SettingsCaption("최근 14일. 되돌리면 넣은 일정은 지우고, 고친 일정은 전 모양으로, 지운 일정은 다시 넣어요(알림·초대는 되살리지 않아요).")
                        .padding(.top, 4)
                    ForEach(model.changes.prefix(10)) { ch in
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(ch.action.label) · \(ch.summary)")
                                    .font(YMFont.body2Regular)
                                    .foregroundStyle(ch.undone ? YMColor.labelTertiary : YMColor.label)
                                    .fixedSize(horizontal: false, vertical: true)
                                SettingsCaption(ch.calendarName + " · " + NoticeText.dateLabel(Date(epochMilli: ch.at)) + (ch.undone ? " · 되돌림" : ""))
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if !ch.undone {
                                Button("되돌리기") { model.undo(ch) }
                                    .font(YMFont.body2Bold)
                                    .foregroundStyle(YMColor.brandPrimary)
                                    .buttonStyle(YMPressStyle())
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
        }
    }

    // MARK: 고급

    private var advancedSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button { withAnimation(.easeOut(duration: 0.2)) { model.advanced.toggle() } } label: {
                HStack {
                    Text("고급").font(YMFont.headline2Bold).foregroundStyle(YMColor.label)
                    Spacer()
                    Image(systemName: model.advanced ? "chevron.up" : "chevron.down").foregroundStyle(YMColor.labelSecondary)
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(YMPressStyle())
            .accessibilityHint(model.advanced ? "접기" : "펼치기")
            if model.advanced {
                YMCard {
                    Text("일정 파일(.ics)로 공유·저장").font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                    SettingsCaption("위에서 고른 일정을 .ics 파일 하나로 만들어요. 네이버 캘린더(웹의 일정 가져오기)나 다른 캘린더 앱의 가져오기에 쓸 수 있어요. 만든 때의 내용이라 나중에 바뀐 것은 반영되지 않아요.")
                        .padding(.top, 4)
                    YMFlowLayout(spacing: 8, lineSpacing: 8) {
                        SettingsSoftButton("공유하기", enabled: !model.busy) { Task { await model.shareIcs() } }
                            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { shareAnchor = $0 }
                        SettingsSoftButton("파일로 저장", enabled: !model.busy) { Task { await model.saveIcs() } }
                    }
                    .padding(.top, 10)
                }
                YMCard {
                    Text("자세한 동작").font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                    VStack(alignment: .leading, spacing: 2) {
                        SettingsCaption("• 마감은 마감 시각에 0분짜리 일정, 시험은 시작~끝 일정으로 들어가요. 영민이가 넣는 일정에는 캘린더 알림을 달지 않아요.")
                        SettingsCaption("• 반복 마감은 앞으로 60일의 회차를 하나씩 넣어요. 캘린더에서 반복 회차를 옮겨도 반복 전체는 바뀌지 않고 원래대로 돌아가요(반복 마감은 앱에서 고쳐 주세요).")
                        SettingsCaption("• 캘린더에서 지운 일정은 다시 넣지 않아요(내 마감은 지워지지 않아요). 마감이 14일 넘게 지난 일정은 그대로 둬요.")
                        SettingsCaption("• 앱이 열려 있으면 캘린더가 바뀐 뒤 몇 초 안에 맞춰요(닫혀 있었다면 다음에 열 때). 같은 일정을 몇 번 되돌려도 캘린더가 다시 바꾸면 그 모양을 그대로 둬요.")
                        SettingsCaption("• 캘린더 앱에서 영민이 일정의 링크를 누르면 영민이의 내 일정이 열려요.")
                        SettingsCaption("• 비서는 반복 일정과 다른 사람이 초대된 일정은 고치거나 지우지 않아요. 고른 캘린더 밖의 일정은 읽지 않아요.")
                        SettingsCaption("• 모두 기기 안에서 처리하고, 앱이 직접 인터넷으로 보내는 것은 없어요(iCloud·계정 캘린더는 그 계정의 동기화가 올려요).")
                    }
                    .padding(.top, 4)
                }
            }
        }
    }
}

// MARK: - 화면 상태

/// 화면 상태와 동작(Kotlin `CalendarExportActivity`의 settings·read·status·note·pickers). 저장은 `CalendarSyncService.export`와
/// `CalendarToolContext.shared`(비서 캘린더 도구와 같은 저장소).
@MainActor
@Observable
final class CalendarSettingsModel {
    private let export = CalendarSyncService.export
    private let ctx: CalendarToolContext = CalendarSyncService.read

    var settings = CalendarExportSettings()
    var read = CalendarReadSettings()
    var status: String?
    var note: String?
    var permitted = false
    var canRead = false
    var busy = false
    var placement: CalendarLocalPlacement = .unavailable
    var calendars: [CalendarInfo] = []
    var changes: [CalendarToolLogic.Change] = []
    var advanced = false

    /// 계정 캘린더 고르기 창(nil이면 닫힘).
    var accountPicker: [CalendarExportInfo]?
    /// 스위치를 켜려다 캘린더 고르기로 온 경우: 고르면 바로 켠다.
    var pendingEnable = false
    /// 볼 캘린더 고르기 창.
    var picking = false
    private var pendingShow = false
    private var pendingAssistant = false

    var shareFile: SharedIcsFile?
    var exporting = false
    var exportDocument = IcsDocument(text: "")
    var exportName = "youngmin.ics"

    func reload() {
        settings = export.settings()
        read = ctx.settings.load()
        status = export.store.lastResult()
        note = export.store.note()
        permitted = export.hasPermission
        canRead = ctx.store.canRead()
        changes = ctx.undo.list()
        calendars = canRead ? ctx.store.calendars() : []
        placement = permitted ? export.localPlacement() : .unavailable
    }

    var selectionLabel: String {
        let names = calendars.filter { read.calendarIds.contains($0.id) }.map(\.name)
        return CalendarReadLogic.selectionLabel(names.isEmpty && !read.calendarIds.isEmpty && !canRead ? ["…"] : names)
    }

    // ---- 앱 캘린더가 있는 곳(iOS) ----

    var localTitle: String {
        switch placement {
        case .iCloud: return "\"영민이\" 캘린더(iCloud)"
        case .other(let name): return "\"영민이\" 캘린더(\(name))"
        case .device, .unavailable: return "이 기기에만"
        }
    }

    var localDesc: String {
        switch placement {
        case .iCloud: return "이 기기에 로컬 캘린더가 없어 iCloud에 만들어요(같은 Apple 계정의 기기에도 보여요)"
        case .other(let name): return "이 기기에 로컬 캘린더가 없어 \(name) 계정에 만들어요"
        case .device, .unavailable: return "캘린더 앱에서 보여요(다른 기기·웹에는 안 보여요)"
        }
    }

    var localHint: String {
        switch placement {
        case .iCloud: return "iCloud에 \"영민이\" 캘린더를 만들어 넣어요. 일정 제목과 과목 이름이 iCloud로 동기화돼요."
        case .other(let name): return "\(name) 계정에 \"영민이\" 캘린더를 만들어 넣어요. 일정 제목과 과목 이름이 그 계정 서버로 동기화돼요."
        case .device, .unavailable: return "이 기기에 \"영민이\" 캘린더를 만들어 넣어요. 서버로 올라가지 않아요."
        }
    }

    // ---- 권한 ----

    /// 전체 접근 권한이 없으면 묻는다. 허용되면 true.
    private func ensureAccess() async -> Bool {
        if ctx.store.canRead() { return true }
        busy = true
        defer { busy = false }
        if ctx.store.access == .notDetermined, await ctx.store.requestAccess() {
            reload()
            return true
        }
        if ctx.store.canRead() { return true }
        ToastCenter.shared.show("캘린더 전체 접근 권한이 필요해요. 설정 앱 → 영민이 → 캘린더에서 켜 주세요.", long: true)
        reload()
        return false
    }

    /// [work]를 맞추기 줄에서 돌리고 결과 문구를 알린다(그 뒤 화면을 다시 읽는다).
    private func background(_ work: @escaping @Sendable () -> String?) async {
        busy = true
        let msg = await CalendarSyncService.run(work)
        busy = false
        reload()
        if let msg, !msg.isEmpty { ToastCenter.shared.show(msg) }
    }

    // ---- 영민이 → 캘린더 ----

    func setEnabled(_ on: Bool) async {
        let export = self.export
        if !on {
            await background { export.disable() ?? "껐어요. 넣은 일정을 지웠어요" }
            return
        }
        guard await ensureAccess() else { return }
        let s = export.settings()
        if s.target == .GOOGLE && s.googleCalendarId == nil {
            loadAccountCalendars(enableAfter: true)
        } else {
            await background { export.enable(s) ?? "켰어요" }
        }
    }

    func chooseTarget(_ t: CalendarTarget) async {
        let export = self.export
        let s = export.settings()
        if s.target == t { return }
        var next = s
        next.target = t
        if t == .GOOGLE && next.googleCalendarId == nil {
            // 켜져 있으면 캘린더를 고른 뒤에 옮긴다. 꺼져 있으면 고르는 것만.
            guard await ensureAccess() else { return }
            loadAccountCalendars()
            return
        }
        if !s.enabled {
            await background { export.switchTarget(next) }
            return
        }
        guard await ensureAccess() else { return }
        next.enabled = true
        let moving = next
        await background { export.switchTarget(moving) ?? "옮겼어요" }
    }

    func pickAccount() async {
        guard await ensureAccess() else { return }
        loadAccountCalendars()
    }

    func loadAccountCalendars(enableAfter: Bool = false) {
        pendingEnable = enableAfter
        accountPicker = export.accountCalendars()
    }

    func dismissAccountPicker() {
        accountPicker = nil
        pendingEnable = false
    }

    func chooseAccount(_ c: CalendarExportInfo) {
        accountPicker = nil
        let export = self.export
        let s = export.settings()
        let turnOn = s.enabled || pendingEnable
        pendingEnable = false
        if s.enabled && s.target == .GOOGLE && s.googleCalendarId == c.id { return }
        var next = s
        next.target = .GOOGLE
        next.googleCalendarId = c.id
        next.googleCalendarName = c.name
        Task {
            if !turnOn {
                let plain = next
                await background { export.switchTarget(plain) }
                return
            }
            next.enabled = true
            let on = next
            await background { export.switchTarget(on) ?? "\"\(c.name)\" 캘린더에 넣어요" }
        }
    }

    func setOptions(_ change: (inout CalendarExportSettings) -> Void) {
        var next = export.settings()
        change(&next)
        export.saveSettings(next)
        reload()
        CalendarSyncService.requestSync()
    }

    func syncNow() async {
        guard await ensureAccess() else { return }
        let export = self.export
        await background { export.syncIfEnabled() ?? "꺼져 있어요" }
    }

    // ---- .ics ----

    private func icsText() async -> (String, String) {
        let export = self.export
        return await CalendarSyncService.run { (export.icsContent(), CalendarSyncLogic.icsFileName(Date())) }
    }

    func shareIcs() async {
        busy = true
        let (text, name) = await icsText()
        busy = false
        do {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ics", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let url = dir.appendingPathComponent(name)
            try text.write(to: url, atomically: true, encoding: .utf8)
            shareFile = SharedIcsFile(url: url)
        } catch {
            ToastCenter.shared.show("하지 못했어요(\(type(of: error)))")
        }
    }

    func saveIcs() async {
        busy = true
        let (text, name) = await icsText()
        busy = false
        exportDocument = IcsDocument(text: text)
        exportName = name
        exporting = true
    }

    // ---- 캘린더 → 영민이 ----

    private func save(_ s: CalendarReadSettings) {
        ctx.settings.save(s)
        reload()
        CalendarSyncService.notifyChanged()
    }

    func setShow(_ on: Bool) async {
        if !on {
            var s = ctx.settings.load()
            s.showEnabled = false
            save(s)
            return
        }
        guard await ensureAccess() else { return }
        reload()
        let cur = ctx.settings.load()
        if cur.calendarIds.isEmpty {
            pendingShow = true
            picking = true
        } else {
            var s = cur
            s.showEnabled = true
            save(s)
        }
    }

    func setAssistant(_ on: Bool) async {
        if !on {
            var s = ctx.settings.load()
            s.assistantEnabled = false
            save(s)
            return
        }
        guard await ensureAccess() else { return }
        reload()
        let cur = ctx.settings.load()
        if cur.calendarIds.isEmpty {
            pendingAssistant = true
            picking = true
        } else {
            var s = fixWrite(cur)
            s.assistantEnabled = true
            save(s)
        }
    }

    func pick() async {
        guard await ensureAccess() else { return }
        reload()
        picking = true
    }

    func cancelPick() {
        picking = false
        pendingShow = false
        pendingAssistant = false
    }

    /// 스위치를 켜려다 고르기로 왔으면 이어서 켠다. 하나도 고르지 않으면 둘 다 끈다.
    func savePicked(_ ids: Set<Int64>) {
        picking = false
        var next = ctx.settings.load()
        next.calendarIds = ids
        next = fixWrite(next)
        if ids.isEmpty {
            next.showEnabled = false
            next.assistantEnabled = false
        } else {
            next.showEnabled = next.showEnabled || pendingShow
            next.assistantEnabled = next.assistantEnabled || pendingAssistant
        }
        pendingShow = false
        pendingAssistant = false
        save(next)
    }

    func setWriteCalendar(_ cal: CalendarInfo) {
        var s = ctx.settings.load()
        s.writeCalendarId = cal.id
        s.writeCalendarName = cal.name
        save(s)
    }

    /// 쓸 캘린더가 선택에서 빠졌으면 비우고, 쓸 수 있는 것이 하나뿐이면 그것으로.
    private func fixWrite(_ s: CalendarReadSettings) -> CalendarReadSettings {
        let writable = calendars.filter { s.calendarIds.contains($0.id) && $0.writable }
        var out = s
        if let w = s.writeCalendarId, writable.contains(where: { $0.id == w }) { return out }
        if writable.count == 1 {
            out.writeCalendarId = writable[0].id
            out.writeCalendarName = writable[0].name
        } else {
            out.writeCalendarId = nil
            out.writeCalendarName = ""
        }
        return out
    }

    func undo(_ c: CalendarToolLogic.Change) {
        let ctx = self.ctx
        busy = true
        Task.detached {
            let msg = ctx.undo.undo(c.id, store: ctx.store)
            await MainActor.run {
                self.busy = false
                ToastCenter.shared.show(msg)
                self.reload()
            }
        }
    }
}

// MARK: - .ics 공유·저장

struct SharedIcsFile: Identifiable {
    let id = UUID()
    let url: URL
}

struct IcsDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.calendarEvent] }
    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        text = configuration.file.regularFileContents.flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

// MARK: - 고르기 창

/// 쓸 수 있는 캘린더가 없을 때: 계정의 캘린더를 켜는 방법과 버튼(Kotlin `NoCalendarsHint`).
private struct NoCalendarsHint: View {
    let onSettings: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("쓸 수 있는 캘린더가 없어요.").font(YMFont.body2Medium).foregroundStyle(YMColor.label)
            Text("설정 앱 → 캘린더 → 계정에서 계정의 '캘린더'를 켜고, 설정 앱 → 영민이 → 캘린더에서 전체 접근을 허용해 주세요. 켠 뒤 잠시 기다렸다가 다시 확인해 주세요.")
                .font(YMFont.body2Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                SettingsSoftButton("설정 열기", action: onSettings)
                SettingsSoftButton("다시 확인", action: onRetry)
            }
        }
        .padding(.vertical, 4)
    }
}

/// 넣을 계정 캘린더 고르기(Kotlin `GooglePicker`).
private struct AccountCalendarSheet: View {
    let calendars: [CalendarExportInfo]
    let onChoose: (CalendarExportInfo) -> Void
    let onDismiss: () -> Void
    let onSettings: () -> Void
    let onRetry: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if calendars.isEmpty { NoCalendarsHint(onSettings: onSettings, onRetry: onRetry) }
                ForEach(calendars) { cal in
                    Button { onChoose(cal) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(cal.name).font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                            Text(cal.account).font(YMFont.caption1Regular).foregroundStyle(YMColor.labelSecondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(cal.name), \(cal.account)")
                }
            }
            .navigationTitle("넣을 캘린더 고르기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기", action: onDismiss) } }
        }
    }
}

/// 볼 캘린더 고르기(Kotlin `ReadPicker`). 계정별로 묶어 체크한다.
private struct CalendarPickSheet: View {
    let calendars: [CalendarInfo]
    let onSave: (Set<Int64>) -> Void
    let onCancel: () -> Void
    let onSettings: () -> Void
    let onRetry: () -> Void
    @State private var chosen: Set<Int64>

    init(calendars: [CalendarInfo], selected: Set<Int64>, onSave: @escaping (Set<Int64>) -> Void, onCancel: @escaping () -> Void,
         onSettings: @escaping () -> Void, onRetry: @escaping () -> Void) {
        self.calendars = calendars
        self.onSave = onSave
        self.onCancel = onCancel
        self.onSettings = onSettings
        self.onRetry = onRetry
        _chosen = State(initialValue: selected.intersection(Set(calendars.map(\.id))))
    }

    var body: some View {
        NavigationStack {
            List {
                if calendars.isEmpty {
                    NoCalendarsHint(onSettings: onSettings, onRetry: onRetry)
                } else {
                    Text("영민이가 넣은 \"영민이\" 캘린더는 목록에 없어요(내 일정에 이미 보여요).")
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                }
                ForEach(groups, id: \.0) { account, cals in
                    Section(account.isEmpty ? "이 기기" : account) {
                        ForEach(cals) { cal in
                            Button {
                                if chosen.contains(cal.id) { chosen.remove(cal.id) } else { chosen.insert(cal.id) }
                            } label: {
                                HStack(spacing: 12) {
                                    Circle().fill(Color(argb: cal.color)).frame(width: 12, height: 12)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(cal.name).font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                                        if !cal.writable {
                                            Text("읽기 전용").font(YMFont.caption1Regular).foregroundStyle(YMColor.labelSecondary)
                                        }
                                    }
                                    Spacer(minLength: 0)
                                    Image(systemName: chosen.contains(cal.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(chosen.contains(cal.id) ? YMColor.brandPrimary : YMColor.labelTertiary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(cal.name), \(cal.account)" + (chosen.contains(cal.id) ? ", 선택됨" : ""))
                        }
                    }
                }
            }
            .navigationTitle("볼 캘린더 고르기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("취소", action: onCancel) }
                if !calendars.isEmpty {
                    ToolbarItem(placement: .confirmationAction) { Button("저장") { onSave(chosen) } }
                }
            }
        }
        .interactiveDismissDisabled()
    }

    private var groups: [(String, [CalendarInfo])] {
        var order: [String] = []
        var map: [String: [CalendarInfo]] = [:]
        for c in calendars {
            if map[c.account] == nil { order.append(c.account) }
            map[c.account, default: []].append(c)
        }
        return order.map { ($0, map[$0] ?? []) }
    }
}

private extension Color {
    /// 0xAARRGGBB(알파 0이면 불투명으로 본다).
    init(argb: Int) {
        let a = (argb >> 24) & 0xFF
        self.init(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255, opacity: a == 0 ? 1 : Double(a) / 255)
    }
}
