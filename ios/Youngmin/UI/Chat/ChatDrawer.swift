import ChatKit
import SwiftUI

/// 왼쪽에서 밀려 들어오는 서랍(Material3 `ModalNavigationDrawer`의 iOS 판). 사이드바가 아니라 내용 위를 덮는 오버레이.
/// 폭 min(360, 화면×0.85), 오른쪽 모서리 16, 뒤 막 검정 32%. 왼쪽 가장자리에서 끌어 열고, 막을 누르거나 왼쪽으로 끌어 닫는다.
/// regular 폭(iPad)에서는 `overlayEnabled = false`로 서랍을 그리지 않고 내용만 보인다(대화 목록은 `ChatView`가 옆에 붙인다).
struct ChatDrawerContainer<Content: View, Drawer: View>: View {
    @Binding var isOpen: Bool
    var overlayEnabled = true
    @ViewBuilder var content: Content
    @ViewBuilder var drawer: Drawer
    @State private var closeDrag: CGFloat = 0
    @State private var edgeDrag: CGFloat = 0
    @State private var edgeActive = false

    /// 왼쪽 가장자리에서 시작한 가로 끌기만 서랍을 연다.
    private static var edgeWidth: CGFloat { 24 }

    var body: some View {
        GeometryReader { geo in
            let width = min(360, geo.size.width * 0.85)
            let base: CGFloat = isOpen ? 0 : -width
            let offset = min(0, max(-width, base + closeDrag + edgeDrag))
            let progress = 1 + offset / width
            ZStack(alignment: .leading) {
                content
                    .frame(width: geo.size.width, height: geo.size.height)
                    .accessibilityHidden(isOpen && overlayEnabled)
                    // 내용 위에 띠를 덮으면 메뉴·첨부 버튼의 탭을 가로채므로, 함께 인식되는 끌기로 가장자리 시작만 받는다.
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 10, coordinateSpace: .local)
                            .onChanged { v in
                                guard overlayEnabled, !isOpen else { return }
                                if !edgeActive {
                                    guard v.startLocation.x <= Self.edgeWidth,
                                          abs(v.translation.width) > abs(v.translation.height) else { return }
                                    edgeActive = true
                                }
                                edgeDrag = max(0, min(width, v.translation.width))
                            }
                            .onEnded { v in
                                guard edgeActive else { return }
                                edgeActive = false
                                let open = v.translation.width > width * 0.35 || v.predictedEndTranslation.width > width * 0.6
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) {
                                    edgeDrag = 0
                                    isOpen = open
                                }
                            }
                    )
                if overlayEnabled && progress > 0.001 {
                    Color.black.opacity(0.32 * progress)
                        .ignoresSafeArea()
                        .onTapGesture { close() }
                        .accessibilityLabel("대화 목록 닫기")
                        .accessibilityAddTraits(.isButton)
                }
                if overlayEnabled && (progress > 0.001 || isOpen) {
                    drawer
                        .frame(width: width)
                        .frame(maxHeight: .infinity)
                        .background(
                            UnevenRoundedRectangle(topLeadingRadius: 0, bottomLeadingRadius: 0,
                                                   bottomTrailingRadius: 16, topTrailingRadius: 16)
                                .fill(YMColor.background)
                                .ignoresSafeArea(edges: [.top, .bottom, .leading])
                                .shadow(color: .black.opacity(0.12 * progress), radius: 8)
                        )
                        .offset(x: offset)
                        // 서랍 목록의 세로 스크롤과 같이 인식되게 하고, 왼쪽으로 미는 가로 끌기만 닫기로 쓴다.
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 12)
                                .onChanged { v in
                                    guard isOpen, abs(v.translation.width) > abs(v.translation.height) else { return }
                                    closeDrag = min(0, v.translation.width)
                                }
                                .onEnded { v in
                                    let shouldClose = isOpen && closeDrag < 0
                                        && (v.translation.width < -width * 0.3 || v.predictedEndTranslation.width < -width * 0.6)
                                    withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) {
                                        closeDrag = 0
                                        if shouldClose { isOpen = false }
                                    }
                                }
                        )
                }
            }
            .animation(.spring(response: 0.3, dampingFraction: 0.9), value: isOpen)
        }
    }

    private func close() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { isOpen = false }
    }
}

/// 서랍 내용(Kotlin `ChatDrawer`): "대화" → 진입 행(새 대화·내 일정·시간표·알림 기록) → 날짜 묶음별 대화 목록.
/// `sidebar`면 regular 폭에서 채팅 옆에 늘 붙어 있는 목록이다: 열고 닫기가 없고, 대화가 바뀌면 바로 목록을 새로 읽는다.
struct ChatDrawer: View {
    let controller: ChatController
    @Binding var isOpen: Bool
    var sidebar = false
    let navigate: (AppRoute) -> Void
    /// 대화를 열거나 새 대화를 만든 뒤 채팅 화면을 보이게 한다(옆 목록에서 설정 등을 보던 중일 때).
    var showChat: () -> Void = {}

    @State private var menuTarget: ChatMeta?
    @State private var renameTarget: ChatMeta?
    @State private var renameText = ""
    @State private var deleteTarget: ChatMeta?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("대화")
                .font(YMFont.title2Bold)
                .foregroundStyle(YMColor.label)
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
            entry("square.and.pencil", "새 대화", brand: true) {
                if controller.newConversation() { close() }
            }
            entry("note.text", "내 일정") { go(.mySchedule) }
            entry("calendar", "시간표") { go(.timetable) }
            entry("bell", "알림 기록") { go(.notifyHistory) }
            Spacer().frame(height: 8)
            if controller.conversations.isEmpty {
                Text("아직 대화가 없어요")
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.labelTertiary)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        let groups = ChatTitles.grouped(controller.conversations, now: ChatController.nowMs(), zone: .current)
                        ForEach(groups, id: \.0) { group, list in
                            Text(group.label)
                                .font(YMFont.caption1Bold)
                                .foregroundStyle(YMColor.labelSecondary)
                                .padding(.leading, 24)
                                .padding(.trailing, 24)
                                .padding(.top, 16)
                                .padding(.bottom, 6)
                            ForEach(list, id: \.id) { meta in row(meta) }
                        }
                    }
                    .padding(.bottom, 12)
                }
            }
        }
        .padding(.top, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onChange(of: isOpen) { _, open in if open { controller.refreshConversations() } }
        // 옆 목록은 늘 보이므로 처음 보일 때와 대화가 바뀔 때(새 대화 저장·답변 시작/끝) 다시 읽는다.
        .onAppear { if sidebar { controller.refreshConversations() } }
        .onChange(of: controller.chatId) { _, _ in if sidebar { controller.refreshConversations() } }
        .onChange(of: controller.items.count) { _, _ in if sidebar { controller.refreshConversations() } }
        .onChange(of: controller.runningChats) { _, _ in if sidebar { controller.refreshConversations() } }
        // 길게 누르기 메뉴(Kotlin AlertDialog: 이름 바꾸기 / 삭제 / 닫기).
        .confirmationDialog(menuTarget?.title ?? "", isPresented: Binding(get: { menuTarget != nil }, set: { if !$0 { menuTarget = nil } }),
                            titleVisibility: .visible, presenting: menuTarget) { meta in
            Button("이름 바꾸기") { startRename(meta) }
            Button("삭제") { startDelete(meta) }
            Button("닫기", role: .cancel) {}
        }
        .alert("이름 바꾸기", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField("대화 이름", text: $renameText)
                .onChange(of: renameText) { _, v in if v.count > ChatTitles.MAX_LEN { renameText = String(v.prefix(ChatTitles.MAX_LEN)) } }
            Button("취소", role: .cancel) { renameTarget = nil }
            Button("저장") {
                if let t = renameTarget, !renameText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    controller.renameConversation(t.id, renameText)
                }
                renameTarget = nil
            }
        }
        .alert("이 대화를 지울까요?", isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }),
               presenting: deleteTarget) { meta in
            Button("취소", role: .cancel) {}
            Button("삭제", role: .destructive) { controller.deleteConversation(meta.id) }
        } message: { meta in
            Text("\"\(meta.title)\" 대화가 목록에서 사라지고 되돌릴 수 없어요." + (controller.runningChats.contains(meta.id) ? " 답하는 중인 답변은 멈춰요." : ""))
        }
    }

    private func close() {
        if sidebar {
            showChat()
            return
        }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { isOpen = false }
    }

    private func go(_ route: AppRoute) {
        if !sidebar { close() }
        navigate(route)
    }

    private func entry(_ icon: String, _ title: String, brand: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundStyle(brand ? YMColor.brandPrimary : YMColor.label)
                    .frame(width: 20, height: 20)
                Text(title)
                    .font(YMFont.body1Medium)
                    .foregroundStyle(brand ? YMColor.brandPrimary : YMColor.label)
                Spacer(minLength: 0)
            }
            .padding(12)
            .contentShape(Rectangle())
        }
        .buttonStyle(YMRowPressStyle())
        .padding(.horizontal, 12)
    }

    /// 답하는 대화는 제목 옆에 작은 돌아가는 표시(2026-11-27). 답하는 중에도 열기·이름 바꾸기가 되고, 지우면 그 답변을 먼저 멈춘다.
    private func row(_ meta: ChatMeta) -> some View {
        let current = meta.id == controller.chatId
        let answering = controller.runningChats.contains(meta.id)
        let tint = YMColor.labelSecondary
        return HStack(spacing: 0) {
            HStack(spacing: 8) {
                Text(meta.title)
                    .font(YMFont.body2Medium)
                    .foregroundStyle(current ? YMColor.brandPrimary : YMColor.label)
                    .lineLimit(1)
                if answering {
                    ProgressView()
                        .controlSize(.small)
                        .accessibilityHidden(true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
            .accessibilityElement(children: .combine)
            .accessibilityValue(answering ? "답변 중" : "")
            Button { startRename(meta) } label: {
                Image(systemName: "pencil").font(.system(size: 17)).foregroundStyle(tint)
                    .frame(width: 40, height: 40).contentShape(Rectangle())
            }
            .buttonStyle(YMPressStyle())
            .accessibilityLabel("'\(meta.title)' 이름 바꾸기")
            Button { startDelete(meta) } label: {
                Image(systemName: "trash").font(.system(size: 17)).foregroundStyle(tint)
                    .frame(width: 40, height: 40).contentShape(Rectangle())
            }
            .buttonStyle(YMPressStyle())
            .accessibilityLabel("'\(meta.title)' 지우기")
        }
        .padding(.leading, 12)
        .padding(.trailing, 2)
        .background(RoundedRectangle(cornerRadius: 12).fill(current ? YMColor.brandSubtle : Color.clear))
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .onTapGesture {
            if controller.openConversation(meta.id) { close() }
        }
        .onLongPressGesture { menuTarget = meta }
        .padding(.horizontal, 12)
    }

    private func startRename(_ meta: ChatMeta) {
        renameText = ChatTitles.cut(meta.title)
        renameTarget = meta
    }

    private func startDelete(_ meta: ChatMeta) {
        deleteTarget = meta
    }
}
