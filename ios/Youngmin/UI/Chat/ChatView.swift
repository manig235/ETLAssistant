import ChatKit
import ETLKit
import NotifyKit
import PhotosUI
import QuickLook
import SwiftUI

/// 채팅 화면(Kotlin `ChatRoot`/`ChatScreen`). 앱의 홈이자 NavigationStack 뿌리.
/// 구조: 상단바 → 칩 줄 → 배너 → 메시지 영역 → 활동 줄 → 보낼 첨부 줄 → 입력 바. 서랍은 전체를 덮는 커스텀 오버레이.
struct ChatView: View {
    @Bindable var controller: ChatController
    @Environment(AppState.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL

    @State private var path: [AppRoute] = []
    @State private var drawerOpen = false
    @State private var showModelPicker = false
    @State private var showPendingNotices = false
    @State private var showPhotoPicker = false
    @State private var showFileImporter = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var previewURL: URL?
    /// 등록 안 한 공지(시트·칩). 시트에서 고른 동작은 시트가 닫힌 뒤 처리한다.
    @State private var pendingNotices: [PendingNotice] = []
    @State private var pendingChoice: PendingNoticeChoice?
    @State private var showCamera = false
    @State private var handledOpenRequest = 0
    /// 이번 실행에 알림 권한 창을 띄웠으면 공지 시트를 자동으로 띄우지 않는다(다음 실행에).
    @State private var pendingAutoBlocked = false
    /// 첫 불러오기(알림 권한 창 뒤)를 마쳤는가. 그 전의 화면 복귀 이벤트로는 시트를 띄우지 않는다.
    @State private var pendingLoaded = false
    /// 받은 파일 정리 기간 묻기(`chat-cleanup-prompt`). "저장" 없이 닫으면 닫은 횟수를 센다.
    @State private var showCleanupPrompt = false
    @State private var cleanupSaved = false

    var body: some View {
        NavigationStack(path: $path) {
            ChatDrawerContainer(isOpen: $drawerOpen) {
                content
            } drawer: {
                ChatDrawer(controller: controller, isOpen: $drawerOpen) { route in
                    path.append(route)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .onChange(of: app.openRequest) { _, _ in
                drawerOpen = false
                showPendingNotices = false
                handledOpenRequest = app.openRequest
                path = app.openRoute.map { [$0] } ?? []
            }
            .navigationDestination(for: AppRoute.self) { route in
                // 초안 창의 "✨ 비서가 공지 읽고 채우기": 채팅 입력창을 채우고(보내지 않음) 채팅으로 돌아온다.
                route.destination.environment(\.schedOpenChat) { prefill in
                    controller.input = prefill
                    path = []
                }
            }
        }
        .sheet(isPresented: $showModelPicker) {
            ChatModelPicker(controller: controller)
        }
        .sheet(isPresented: $showCleanupPrompt, onDismiss: {
            if !cleanupSaved { FilesCleanupService.cleanup.markAsked() }
        }) {
            ChatCleanupPrompt {
                cleanupSaved = true
                showCleanupPrompt = false
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(24)
        }
        .sheet(isPresented: $showPendingNotices, onDismiss: applyPendingChoice) {
            // Android 하단 시트처럼 내용만큼(반 화면)으로 열고, 공지가 많으면 끌어 올려 본다.
            PendingNoticesSheet(notices: $pendingNotices) { pendingChoice = $0 }
                .presentationDetents(pendingNotices.count <= 2 ? [.medium, .large] : [.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(24)
        }
        .onChange(of: pendingNotices.count) { _, n in controller.pendingNoticeCount = n }
        .onReceive(NotificationCenter.default.publisher(for: .pendingNoticesChanged)) { _ in
            // 대화 중에 갑자기 뜨지 않게 목록·칩만 바꾼다(자동 표시는 다음에 채팅을 열 때).
            if path.isEmpty, !showPendingNotices { loadPendingNotices(otherPrompt: true) }
        }
        .alert(controller.approval?.operation ?? "", isPresented: approvalBinding, presenting: controller.approval) { _ in
            Button("취소", role: .cancel) { controller.decideApproval(false) }
            Button("변경 승인") { controller.decideApproval(true) }
        } message: { req in
            Text(req.details + "\n\n이 변경을 적용할까요? 취소하면 변경하지 않아요.")
        }
        .quickLookPreview($previewURL)
        .task {
            // 앱이 꺼져 있을 때 알림을 눌렀으면 그 화면을 연다.
            if app.openRequest != handledOpenRequest {
                handledOpenRequest = app.openRequest
                path = app.openRoute.map { [$0] } ?? []
            }
            controller.attachApprovals()
            await controller.refreshEnvironment()
            // Android 13+처럼 채팅을 처음 열 때 한 번만 알림 권한을 묻는다(`chat-notify-permission`).
            var askedPermission = false
            if controller.notifyStatus == .notDetermined, !controller.demoMode, !StudyDataProfile.isDemo,
               !AppStores.ui.bool("notifyPermissionAsked", default: false) {
                AppStores.ui.set(true, for: "notifyPermissionAsked")
                askedPermission = true
                pendingAutoBlocked = true
                _ = await LocalNotifications.shared.requestAuthorization()
                await controller.refreshEnvironment()
            }
            ChatMathRender.warmUp()
            FilesCleanupService.runIfDue()
            // 받은 파일 정리 기간을 아직 고르지 않은 기존 사용자에게 한 번 묻는다(알림 권한 창을 띄운 실행에는 다음에).
            if !askedPermission, !controller.demoMode, !StudyDataProfile.isDemo, ChatCleanupPrompt.pending {
                pendingAutoBlocked = true
                showCleanupPrompt = true
            }
            // 채팅을 열 때 뜨는 창은 한 번에 하나: 알림 권한 창을 띄운 실행에는 공지 시트를 다음 기회로 미룬다.
            pendingLoaded = true
            loadPendingNotices(otherPrompt: askedPermission)
            if controller.models.isEmpty { await controller.loadModels() }
        }
        .onChange(of: scenePhase) { _, phase in
            // 채팅 화면이 안 보이면(앱이 뒤로 감) 끝난 답변을 기기 알림으로 알린다.
            controller.chatScreenVisible = phase != .background && path.isEmpty
            switch phase {
            case .active:
                FilesCleanupService.runIfDue()
                controller.attachApprovals()
                Task { await controller.refreshEnvironment() }
                if path.isEmpty { loadPendingNotices(otherPrompt: false) }
            case .background:
                controller.detachApprovals()
            default:
                break
            }
        }
        .onChange(of: path) { _, newPath in
            controller.chatScreenVisible = scenePhase != .background && newPath.isEmpty
            // 화면을 떠나면(설정 등으로 push) 승인 창을 닫는다(Android onPause와 같음). 돌아오면 다시 붙인다.
            if newPath.isEmpty {
                controller.attachApprovals()
                Task { await controller.refreshEnvironment() }
                loadPendingNotices(otherPrompt: false)
            } else {
                controller.detachApprovals()
            }
        }
    }

    private var approvalBinding: Binding<Bool> {
        // 닫힘은 두 버튼으로만 일어난다(iOS 알림창은 바깥 탭으로 닫히지 않는다). 버튼 동작이 결정을 알린다.
        Binding(get: { controller.approval != nil }, set: { _ in })
    }

    private var content: some View {
        VStack(spacing: 0) {
            ChatTopBar(
                onMenu: { withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { drawerOpen = true } },
                onNewChat: { controller.newConversation() },
                onSettings: { path.append(.settings) }
            )
            chipRow
            if let banner = controller.banner {
                ChatBanner(spec: banner, onTap: { handleBanner(banner) }, onDismiss: { controller.snoozeNotifyBanner() })
            }
            if let other = controller.approvalElsewhere {
                // 다른 대화의 쓰기 확인: 누르면 그 대화로 옮겨 확인 창을 띄운다.
                ChatBanner(spec: BannerSpec(TurnLogic.APPROVAL_ELSEWHERE, .openChat), onTap: { controller.openConversation(other) }, onDismiss: {})
            }
            if controller.items.isEmpty {
                ChatEmptyState { q in controller.send(q) }
            } else {
                ChatMessageList(controller: controller, onOpen: open, onLink: { openURL($0) })
            }
            if let bar = controller.finishBar {
                ChatFinishBar(text: bar.text, onOpen: {
                    controller.finishBar = nil
                    controller.openConversation(bar.chatId)
                }, onDismiss: { controller.finishBar = nil })
                .task(id: bar.id) {
                    try? await Task.sleep(nanoseconds: 8_000_000_000)
                    if controller.finishBar?.id == bar.id { controller.finishBar = nil }
                }
            }
            if let activity = controller.activity {
                ChatActivityLine(text: activity)
            }
            if !controller.pending.isEmpty || controller.importing > 0 {
                ChatPendingAttachments(controller: controller, onOpen: open)
            }
            ChatInputBar(
                controller: controller,
                onPickPhotos: { showPhotoPicker = true },
                onPickFiles: { showFileImporter = true },
                onTakePhoto: ChatCameraPicker.isAvailable ? { showCamera = true } : nil
            )
        }
        .background(YMColor.background.ignoresSafeArea())
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems,
                      maxSelectionCount: max(1, controller.remainingSlots), matching: .images)
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            photoItems = []
            Task { await controller.importPhotos(items) }
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            controller.importFiles(result)
        }
        .fullScreenCover(isPresented: $showCamera) {
            ChatCameraPicker { controller.importCameraImage($0) }
                .ignoresSafeArea()
        }
    }

    private var chipRow: some View {
        HStack(spacing: 8) {
            ChatModelChip(label: controller.choiceLabel) { showModelPicker = true }
            if controller.pendingNoticeCount > 0 {
                YMChip("등록할 공지 \(controller.pendingNoticeCount)개") { showPendingNotices = true }
                    .accessibilityLabel("등록 안 한 공지 \(controller.pendingNoticeCount)개 보기")
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    /// 등록 안 한 공지(받아 둔 것만, 네트워크 없음). 새 공지가 있을 때만 시트를 자동으로 연다(Kotlin `loadPendingNotices`).
    private func loadPendingNotices(otherPrompt: Bool) {
        if controller.demoMode || !pendingLoaded { return }
        let (list, auto) = PendingNoticesLoader.load(otherPrompt: otherPrompt || pendingAutoBlocked || controller.approval != nil)
        pendingNotices = list
        controller.pendingNoticeCount = list.count
        if list.isEmpty { showPendingNotices = false } else if auto, path.isEmpty { showPendingNotices = true }
    }

    /// 시트가 닫힌 뒤: 초안 창을 열거나 입력창을 채운다(보내지 않음).
    private func applyPendingChoice() {
        guard let choice = pendingChoice else { return }
        pendingChoice = nil
        switch choice {
        case .open(let route): path.append(route)
        case .prefill(let text): controller.input = text
        }
    }

    private func handleBanner(_ banner: BannerSpec) {
        switch banner.action {
        case .settings: path.append(.settings)
        case .notifications:
            Task {
                if controller.notifyStatus == .notDetermined {
                    _ = await LocalNotifications.shared.requestAuthorization()
                    await controller.refreshEnvironment()
                } else if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                    openURL(url)
                }
            }
        case .retry: Task { await controller.refreshEnvironment() }
        case .openChat, .none: break
        }
    }

    /// 사진·파일 열기(Android 외부 뷰어 → iOS QuickLook).
    private func open(_ a: Attachment) {
        // 안드로이드처럼 사용자에게 보이는 폰 사본(파일 앱 영민이/etl)을 먼저 연다. 없으면 앱 작업 폴더 사본.
        let candidates = [ContainerPath.resolveFileURL(a.uri), a.path.flatMap(ContainerPath.resolve).map { URL(fileURLWithPath: $0) }].compactMap { $0 }
        guard let url = candidates.first else {
            ToastCenter.shared.show("파일이 정리되어 더는 없어요")
            return
        }
        previewURL = url
    }
}
