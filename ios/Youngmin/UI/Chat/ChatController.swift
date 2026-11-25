import ChatKit
import ETLKit
import SwiftUI
import UserNotifications

/// 화면에 그릴 대화 한 줄(ChatKit `Message`는 클래스라 SwiftUI가 바뀐 것을 알 수 있게 값으로 옮긴다).
struct ChatItem: Identifiable, Equatable {
    let id: Int64
    let role: Role
    let text: String
    let streaming: Bool
    let attachments: [Attachment]
}

/// 채팅 화면 상태(Kotlin `ChatViewModel` + `CodexSession`의 대화 부분). 대화 저장소와 ResponsesEngine 한 턴을 맡는다.
/// 도구·지침은 `AssistantToolbox`, 쓰기 승인은 `WriteApprovalSource`로 끼운다.
@MainActor
@Observable
final class ChatController {
    let auth: AssistantAuth
    @ObservationIgnored let toolbox: AssistantToolbox
    @ObservationIgnored let approvals: WriteApprovalSource
    @ObservationIgnored let chats: ChatStore
    @ObservationIgnored let threads: ConversationThreadStore
    @ObservationIgnored let model: ChatModel
    @ObservationIgnored var settings: ChatSettings { auth.settings }

    private(set) var chatId: String
    private(set) var items: [ChatItem] = []
    private(set) var conversations: [ChatMeta] = []
    /// 입력창 글.
    var input = ""
    /// 보낼 첨부(최대 `AttachmentLogic.MAX_PENDING`).
    var pending: [Attachment] = []
    /// 가져오는 중인 첨부 수.
    var importing = 0
    private(set) var busy = false
    /// "생각하는 중…"/"조회하는 중…" 한 줄. nil이면 숨김.
    private(set) var activity: String?
    private(set) var models: [ModelInfo] = []
    private(set) var modelsLoading = false
    private(set) var modelsFailed = false
    private(set) var modelsError: String?
    private(set) var choice: ModelChoice
    /// 지금 띄울 쓰기 승인 요청.
    private(set) var approval: WriteApprovalRequest?
    /// "등록할 공지 N개" 칩. 0이면 숨김(NotifyKit이 연결되면 통합 담당이 채운다).
    var pendingNoticeCount = 0
    /// 바뀌면 목록을 맨 아래로(새 메시지·대화 전환·화면 복귀).
    private(set) var scrollToBottomToken = 0
    private(set) var hasEtlToken = false
    private(set) var notifyStatus: UNAuthorizationStatus = .authorized
    private(set) var notifyDismissedAt: Int64 = 0
    /// DEBUG 샘플 데이터 모드: 배너를 숨기고 답변을 흉내 낸다(DemoSeed).
    var demoMode = false

    @ObservationIgnored private var engine: ResponsesEngine?
    @ObservationIgnored private var engineMode: String?
    @ObservationIgnored private var codexEngine: CodexEngine?
    @ObservationIgnored private var turnTask: Task<Void, Never>?
    @ObservationIgnored private var turnCancellation: TurnCancellation?
    @ObservationIgnored private var events: EtlEvents.Subscription?
    @ObservationIgnored private var approvalHost: String?
    @ObservationIgnored private var modelsTask: Task<Void, Never>?
    @ObservationIgnored private var modelsRequestID = UUID()

    init(auth: AssistantAuth, toolbox: AssistantToolbox, approvals: WriteApprovalSource) {
        self.auth = auth
        self.toolbox = toolbox
        self.approvals = approvals
        let chats = ChatStore(ChatPaths.chats, attachmentRoot: AppPaths.directory(EtlDownloads.DIR))
        chats.load()
        self.chats = chats
        let threads = ConversationThreadStore(ChatPaths.threads)
        self.threads = threads
        let settings = auth.settings
        settings.pendingThreadDeletes = threads.purgePending(settings.pendingThreadDeletes)
        let id = settings.currentChatId.flatMap { chats.get($0) != nil ? $0 : nil } ?? chats.newId()
        chatId = id
        choice = settings.modelChoice
        model = ChatModel(chats.fileFor(id))
        model.load()
        demoMode = DemoSeed.isActive
        conversations = chats.list()
        hasEtlToken = AppStores.tokens.hasToken()
        notifyDismissedAt = AppStores.ui.int64("notifyBannerDismissedAt") ?? 0
        sync()
        events = EtlEvents.subscribe { [weak self] e in
            Task { @MainActor in self?.onEtlEvent(e) }
        }
        auth.onLoginDone = { [weak self] ok in
            if ok {
                ToastCenter.shared.show("로그인했어요")
                self?.modelsTask?.cancel()
                self?.modelsTask = nil
                self?.modelsRequestID = UUID()
                self?.models = []
                Task { await self?.loadModels() }
            }
        }
    }

    // MARK: 화면 상태

    /// ChatModel → 화면 줄.
    func sync(scroll: Bool = false) {
        items = model.messages.map {
            ChatItem(id: $0.id, role: $0.role, text: $0.text, streaming: $0.status == "streaming", attachments: $0.attachments)
        }
        if scroll { scrollToBottomToken &+= 1 }
    }

    func refreshConversations() { conversations = chats.list() }

    var sendMode: SendMode {
        ChatLogic.sendMode(busy: busy, input: input, hasAttachments: !pending.isEmpty, importing: importing > 0)
    }

    /// 칩 글. 목록을 받기 전(앱을 연 직후)에는 고를 때 저장한 표시 이름을 쓴다(슬러그가 보이지 않게).
    var choiceLabel: String { ModelCatalog.choiceLabel(choice, models, savedName: settings.modelName) }

    /// 위쪽 배너(하나만). iOS에는 세션 준비·배터리 상태가 없어 토큰 → 로그인 → 알림 꺼짐만 나온다.
    var banner: BannerSpec? {
        if demoMode { return nil }
        let granted = notifyStatus == .authorized || notifyStatus == .provisional || notifyStatus == .ephemeral
        let problem = ChatLogic.notifyProblem(appEnabled: granted, usedChannels: NotifyPrefs.usedChannels(AppStores.notifySettings),
                                              blockedChannels: [])
        return ChatLogic.banner(hasToken: hasEtlToken, state: .ready, detail: nil, loggedIn: auth.isSignedIn,
                                notifyProblem: problem, notifyDismissedAt: notifyDismissedAt, nowMs: Self.nowMs())
    }

    /// 화면이 다시 보일 때마다(Android onResume) 다시 판정한다.
    func refreshEnvironment() async {
        hasEtlToken = AppStores.tokens.hasToken()
        notifyStatus = await LocalNotifications.shared.authorizationStatus()
        await auth.refreshAccount()
        await purgeNativeThreads()
        scrollToBottomToken &+= 1
    }

    /// 알림 배너 × "나중에": 7일 숨김.
    func snoozeNotifyBanner() {
        notifyDismissedAt = Self.nowMs()
        AppStores.ui.set(notifyDismissedAt, for: "notifyBannerDismissedAt")
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: 보내기

    /// 보내기/중단 버튼(Android `onSendClicked`).
    func submit() {
        if busy { stop(); return }
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if importing > 0 { return }
        if text.isEmpty && pending.isEmpty { return }
        send(text, attachments: pending, clearInput: true)
    }

    /// 문제가 있으면 이유를 토스트로 알리고 입력과 첨부는 그대로 둔다.
    func send(_ text: String, attachments: [Attachment] = [], clearInput: Bool = false) {
        if let problem = sendProblem() {
            ToastCenter.shared.show(problem)
            return
        }
        if clearInput { input = "" }
        if !attachments.isEmpty { pending.removeAll { attachments.contains($0) } }
        let images = attachments.filter { $0.isImage && $0.path != nil }
        let files = attachments.filter { !$0.isImage && $0.path != nil }.map { ($0.path.map(Self.workspaceRelative) ?? $0.name, $0.mime, $0.size) }
        let prompt = AttachmentLogic.promptText(text, files, imageCount: images.count)
        let hadHistory = model.messages.contains { $0.role == .user || $0.role == .assistant }
        model.addUser(text, attachments)
        chats.noteUserMessage(chatId, text, attachments, now: Self.nowMs())
        refreshConversations()
        sync(scroll: true)
        busy = true
        activity = ActivityTracker.THINKING
        let forChat = chatId
        let imageFiles = images.compactMap { a in a.path.map { ($0, a.mime) } }
        let cancellation = TurnCancellation()
        turnCancellation = cancellation
        turnTask = Task { [weak self] in
            await self?.runTurn(forChat: forChat, prompt: prompt, imageFiles: imageFiles, hadHistory: hadHistory, cancellation: cancellation)
        }
    }

    private func sendProblem() -> String? {
        if demoMode { return busy ? "이전 답변이 끝날 때까지 기다려 주세요" : nil }
        if !auth.isSignedIn { return "먼저 로그인해야 해요" }
        if busy { return "이전 답변이 끝날 때까지 기다려 주세요" }
        return nil
    }

    /// 답변 중단(NOTICE "답변을 멈췄어요"는 턴이 끝날 때 붙는다).
    func stop() {
        approvals.cancelAll()
        if demoMode { turnTask?.cancel(); return }
        turnCancellation?.cancel()
    }

    private func currentEngine() -> ResponsesEngine {
        let mode = settings.authMode
        if let engine, engineMode == mode { return engine }
        let e = ResponsesEngine(auth: auth.provider)
        engine = e
        engineMode = mode
        return e
    }

    private func runTurn(forChat: String, prompt: String, imageFiles: [(String, String)], hadHistory: Bool,
                         cancellation: TurnCancellation) async {
        defer {
            busy = false
            activity = nil
            turnCancellation = nil
            sync()
        }
        if demoMode {
            await DemoSeed.runDemoTurn(self, prompt: prompt)
            return
        }
        if models.isEmpty {
            activity = "모델 목록을 불러오는 중이에요"
            await loadModels()
            activity = ActivityTracker.THINKING
        }
        guard !cancellation.isCancelled else {
            model.addNotice("답변을 멈췄어요")
            model.closeStreaming()
            return
        }
        guard let modelId = ModelCatalog.resolveModel(choice, models) else {
            model.addNotice("보내지 못했어요: \(modelsError ?? "모델 목록을 불러오지 못했어요")")
            model.closeStreaming()
            return
        }
        let sanitized = ModelCatalog.sanitize(choice, models)

        var history: [ChatJSON] = []
        let threadId: String
        if let saved = chats.get(forChat)?.threadId {
            threadId = saved
            history = threads.load(saved)
            if history.isEmpty && hadHistory { model.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.") }
        } else {
            threadId = threads.newThreadId()
            chats.setThread(forChat, threadId)
            if hadHistory { model.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.") }
        }
        sync()

        let images = imageFiles.compactMap { path, mime in
            (try? Data(contentsOf: URL(fileURLWithPath: path))).map { InputImage(data: $0, mime: mime) }
        }
        let instructions = [toolbox.instructions, settings.toneInstructions()].filter { !$0.isEmpty }.joined(separator: "\n\n")
        let config = TurnConfig(model: modelId, effort: sanitized.effort, instructions: instructions,
                                tools: toolbox.toolDefinitions, toolPackaging: toolbox.toolPackaging)
        let toolbox = self.toolbox
        let usingNative = auth.usesNativeCodex
        let nativeReferences = usingNative ? [] : history.filter { $0["type"]?.string == CodexEngine.threadMarker }
        let apiHistory = history.filter { $0["type"]?.string != CodexEngine.threadMarker }
        if !usingNative, !nativeReferences.isEmpty, apiHistory.isEmpty, hadHistory {
            model.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.")
            sync()
        }
        let execute: ToolExecutor = { call in await toolbox.run(name: call.name, arguments: call.arguments) }
        let stream: AsyncStream<ResponsesEvent>
        if usingNative, let client = auth.codex {
            if codexEngine == nil { codexEngine = CodexEngine(client: client) }
            stream = await codexEngine!.send(history: history, input: [ResponsesInput.userMessage(prompt, images: images)],
                                              config: config, cancellation: cancellation, tools: execute)
        } else {
        stream = await currentEngine().send(
            history: apiHistory,
            input: [ResponsesInput.userMessage(prompt, images: images)],
            config: config,
            cancellation: cancellation,
            tools: execute
        )
        }
        for await event in stream {
            switch event {
            case .activity(let label):
                activity = label
            case .textDelta(let itemId, let delta):
                model.appendAssistant(itemId, delta)
                sync()
            case .textDone(let itemId, let text):
                model.finishAssistant(itemId, text)
                sync()
            case .turnEnded(let result):
                threads.save(threadId, nativeReferences + result.history)
                model.closeStreaming()
                switch result.status {
                case .interrupted:
                    approvals.cancelAll()
                    model.addNotice("답변을 멈췄어요")
                case .failed(let err):
                    approvals.cancelAll()
                    model.addNotice(err.userMessage)
                    if !choice.isAuto { model.addNotice("선택한 모델 설정 때문일 수 있어요. 설정에서 '자동'으로 되돌려 보세요.") }
                    if err.needsSignIn { await auth.refreshAccount() }
                case .incomplete:
                    if result.finalText.isEmpty { model.addNotice("답변을 만들지 못했어요") }
                case .completed:
                    break
                }
                sync()
            default:
                break
            }
        }
    }

    /// 모델이 읽을 첨부 경로: 앱 작업 폴더(Application Support/Youngmin) 기준 상대 경로.
    static func workspaceRelative(_ path: String) -> String {
        let root = AppPaths.root.standardizedFileURL.path
        let p = URL(fileURLWithPath: path).standardizedFileURL.path
        if p.hasPrefix(root + "/") { return String(p.dropFirst(root.count + 1)) }
        return (path as NSString).lastPathComponent
    }

    // MARK: 모델 고르기

    func loadModels() async {
        if demoMode { models = DemoSeed.models; return }
        // Login, the picker and sending can all arrive while the same RPC is
        // pending. Every caller must await it before deciding the list is empty.
        if let modelsTask { await modelsTask.value; return }
        guard auth.isSignedIn else {
            modelsError = "비서 계정에 로그인한 뒤 다시 시도해 주세요"
            return
        }
        modelsLoading = true
        modelsFailed = false
        modelsError = nil
        let requestID = UUID()
        modelsRequestID = requestID
        // A sheet disappearing must not cancel the request shared with chat.
        let task = Task<Void, Never> { [weak self] in await self?.fetchModels() }
        modelsTask = task
        await task.value
        guard modelsRequestID == requestID else { return }
        modelsTask = nil
        modelsLoading = false
    }

    private func fetchModels() async {
        do {
            let loaded: [ModelInfo]
            if auth.usesNativeCodex, let client = auth.codex {
                let result = try await client.request("model/list")
                loaded = ModelCatalog.parse(result)
                if loaded.isEmpty {
                    let received = result["data"]?.array?.count ?? 0
                    throw CodexError(received > 0
                        ? "모델 목록을 받았지만 표시 정보를 읽지 못했어요"
                        : "서버에서 이 계정의 모델 목록이 비어 돌아왔어요. 잠시 뒤 다시 불러와 주세요")
                }
            } else {
                loaded = try await ModelListClient.fetch(auth: auth.provider, transport: URLSessionTransport())
            }
            try Task.checkCancellation()
            guard auth.isSignedIn else { return }
            guard !loaded.isEmpty else { throw CodexError("이 계정에서 사용할 수 있는 모델을 찾지 못했어요") }
            models = loaded
            if !models.isEmpty {
                let availableChoice = ModelCatalog.sanitize(choice, models)
                if availableChoice != choice { setChoice(availableChoice) }
            }
            // 예전 판에서 고른 모델도 이름을 기억해 둔다(다음 실행 때 칩에 바로 보이게).
            if let id = choice.model, let m = models.first(where: { $0.model == id }) { settings.modelName = m.displayName }
            modelsFailed = models.isEmpty
            modelsError = nil
        } catch {
            guard !Task.isCancelled else { return }
            modelsFailed = true
            if let e = error as? CodexError { modelsError = e.message }
            else if let e = error as? ResponsesError { modelsError = e.userMessage }
            else if let e = error as? AuthError { modelsError = e.userMessage }
            else { modelsError = "모델 목록 요청에 실패했어요: \(error.localizedDescription.prefix(240))" }
        }
    }

    /// 모델 카드: 지원하지 않는 강도는 기본으로.
    func selectModel(_ id: String?) {
        var effort = choice.effort
        let target = id.flatMap { m in models.first { $0.model == m } } ?? models.first { $0.isDefault } ?? models.first
        if let e = effort, let target, !target.efforts.contains(where: { $0.effort == e }) { effort = nil }
        setChoice(ModelChoice(id, effort))
    }

    func selectEffort(_ effort: String?) { setChoice(ModelChoice(choice.model, effort)) }

    private func setChoice(_ c: ModelChoice) {
        settings.modelChoice = c
        settings.modelName = c.model.flatMap { id in models.first { $0.model == id }?.displayName }
        choice = c
    }

    // MARK: 대화 목록

    /// 빈 새 대화를 연다(아무것도 보내지 않은 대화는 목록에 쌓이지 않는다).
    @discardableResult
    func newConversation() -> Bool {
        if busy {
            ToastCenter.shared.show("답변이 끝난 뒤에 새 대화를 시작할 수 있어요")
            return false
        }
        if chats.get(chatId) == nil && model.messages.isEmpty { return true }
        openChat(chats.newId())
        return true
    }

    @discardableResult
    func openConversation(_ id: String) -> Bool {
        if busy {
            ToastCenter.shared.show("답변이 끝난 뒤에 다른 대화로 옮길 수 있어요")
            return false
        }
        if !ChatStore.isSafeId(id) || id == chatId { return true }
        openChat(id)
        return true
    }

    private func openChat(_ id: String) {
        if chats.get(chatId) != nil { model.save() }
        chatId = id
        settings.currentChatId = id
        model.switchTo(chats.fileFor(id))
        activity = nil
        sync(scroll: true)
    }

    func renameConversation(_ id: String, _ title: String) {
        if busy { ToastCenter.shared.show("답변이 끝난 뒤에 바꾸거나 지울 수 있어요"); return }
        chats.rename(id, title)
        refreshConversations()
    }

    func deleteConversation(_ id: String) {
        if busy { ToastCenter.shared.show("답변이 끝난 뒤에 지울 수 있어요"); return }
        guard let removed = chats.delete(id) else { return }
        if id == chatId { openChat(chats.newId()) }
        if let t = removed.threadId, ThreadPurge.isThreadId(t) {
            if let nativeID = threads.load(t).first(where: { $0["type"]?.string == CodexEngine.threadMarker })?["thread_id"]?.string,
               ThreadPurge.isThreadId(nativeID) {
                settings.pendingNativeThreadDeletes = settings.pendingNativeThreadDeletes.union([nativeID])
                Task { await purgeNativeThreads() }
            }
            if !threads.delete(t) { settings.pendingThreadDeletes = settings.pendingThreadDeletes.union([t]) }
        }
        refreshConversations()
    }

    private func purgeNativeThreads() async {
        guard let client = auth.codex else { return }
        for id in settings.pendingNativeThreadDeletes {
            do {
                _ = try await client.request("thread/delete", ["threadId": .string(id)])
                settings.pendingNativeThreadDeletes = settings.pendingNativeThreadDeletes.subtracting([id])
            } catch let error as CodexError where error.message == "thread not found: \(id)" {
                settings.pendingNativeThreadDeletes = settings.pendingNativeThreadDeletes.subtracting([id])
            } catch {
                // Keep the ID for the next foreground refresh, including after restart.
            }
        }
    }

    // MARK: 쓰기 승인

    /// 채팅 화면이 보일 때(Android onResume).
    func attachApprovals() {
        guard approvalHost == nil else { return }
        approvalHost = approvals.attachHost()
        approvals.setChangeHandler { [weak self] in
            Task { @MainActor in self?.refreshApproval() }
        }
        refreshApproval()
    }

    /// 화면을 떠날 때(Android onPause): 창을 닫고 대기 요청은 거절된다.
    func detachApprovals() {
        approvals.setChangeHandler(nil)
        if let h = approvalHost { approvals.detachHost(h) }
        approvalHost = nil
        approval = nil
    }

    private func refreshApproval() {
        guard approvalHost != nil else { approval = nil; return }
        let first = approvals.pending().first
        if first?.id != approval?.id { approval = first }
    }

    /// "변경 승인"(true) / "취소"(false).
    func decideApproval(_ approved: Bool) {
        guard let host = approvalHost, let req = approval else { return }
        approval = nil
        approvals.decide(hostId: host, requestId: req.id, approved: approved)
        refreshApproval()
    }

    // MARK: 도구 이벤트

    private func onEtlEvent(_ e: EtlEvents.Event) {
        switch e {
        case .filesSaved(let files, let pagePreview):
            model.addFiles(files.map {
                Attachment($0.name, $0.mime, $0.size, $0.file.path, $0.phoneUri, $0.shownPath,
                           pagePreview: pagePreview, referenceOnly: pagePreview || $0.referenceOnly)
            })
            sync()
        case .notice(let text):
            model.addNotice(text)
            sync()
        case .dataChanged:
            break
        }
    }

    // MARK: 시연(DemoSeed)

    /// 시연 답변이 화면을 움직이는 데 쓴다.
    func demoSetActivity(_ label: String?) { activity = label }
}
