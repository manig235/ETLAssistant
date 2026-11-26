import ChatKit
import ETLKit
import SwiftUI
import UIKit
import UserNotifications

/// 화면에 그릴 대화 한 줄(ChatKit `Message`는 클래스라 SwiftUI가 바뀐 것을 알 수 있게 값으로 옮긴다).
struct ChatItem: Identifiable, Equatable {
    let id: Int64
    let role: Role
    let text: String
    let streaming: Bool
    let attachments: [Attachment]
}

/// 다른 대화의 답변이 끝났다는 아래 막대(Kotlin `FinishBarSpec`).
struct FinishBarItem: Identifiable, Equatable {
    let id = UUID()
    let chatId: String
    let text: String
}

/// 답하는 대화 하나의 턴.
@MainActor
private final class RunningTurn {
    let cancellation: TurnCancellation
    let modelAuto: Bool
    var task: Task<Void, Never>?
    /// 답변 중에 대화를 지움: 대화에 아무것도 쓰지 않고, 끝나면 대화 기록(엔진 스레드 포함)을 지운다.
    var deleted = false
    /// 이 대화의 앱 쪽 스레드 기록 ID(지울 때).
    var localThreadId: String?
    init(cancellation: TurnCancellation, modelAuto: Bool) {
        self.cancellation = cancellation
        self.modelAuto = modelAuto
    }
}

/// 채팅 화면 상태(Kotlin `ChatViewModel` + `CodexSession`의 대화 부분). 대화 저장소와 대화별 턴을 맡는다.
/// 도구·지침은 `AssistantToolbox`, 쓰기 승인은 `WriteApprovalSource`로 끼운다.
///
/// 답변은 대화마다 따로 돈다(2026-11-27). 대화 A가 답하는 동안 다른 대화를 열고, 새 대화를 시작하고, 다른 대화에서 보낼 수 있다
/// (동시에 도는 답변 수 제한 없음). 도구 실행기는 `ToolOrigin.$chatId`로 감싸 부르므로 받은 파일·바뀐 데이터 알림·쓰기 승인 요청이
/// 그 도구를 부른 대화에 붙는다.
@MainActor
@Observable
final class ChatController {
    let auth: AssistantAuth
    @ObservationIgnored let toolbox: AssistantToolbox
    @ObservationIgnored let approvals: WriteApprovalSource
    @ObservationIgnored let chats: ChatStore
    @ObservationIgnored let threads: ConversationThreadStore
    /// 지금 보는 대화의 모델.
    @ObservationIgnored private(set) var model: ChatModel
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
    /// 답하는 대화(서랍의 돌아가는 표시).
    private(set) var runningChats: Set<String> = []
    /// 대화별 "생각하는 중…"/"조회하는 중…" 한 줄.
    private(set) var activities: [String: String] = [:]
    /// 지금 보는 대화가 답하는 중인가(보내기 대신 멈춤 버튼). 다른 대화의 답변과는 상관없다.
    var busy: Bool { runningChats.contains(chatId) }
    /// 지금 보는 대화의 "지금 하는 일". nil이면 숨김.
    var activity: String? { activities[chatId] }
    /// 다른 대화의 답변이 끝남(입력창 위 막대, "보기"로 그 대화).
    var finishBar: FinishBarItem?
    /// 다른 대화가 쓰기 확인을 기다림(위쪽 배너, 누르면 그 대화).
    private(set) var approvalElsewhere: String?
    /// 채팅 화면이 보이는가(ChatView가 알린다). 안 보일 때 끝난 답변은 기기 알림으로 알린다.
    @ObservationIgnored var chatScreenVisible = true
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

    @ObservationIgnored private var codexEngine: CodexEngine?
    @ObservationIgnored private var codexEngineClient: CodexClient?
    /// 답하는 대화의 턴.
    @ObservationIgnored private var turns: [String: RunningTurn] = [:]
    /// 메모리에 둔 대화(지금 대화, 답하는 대화, 이번 실행에 열어 본 대화).
    @ObservationIgnored private var liveModels: [String: ChatModel] = [:]
    /// 대화마다 입력창 글과 보낼 첨부를 따로 둔다.
    @ObservationIgnored private var drafts: [String: (String, [Attachment])] = [:]
    /// 답하는 대화가 있는 동안 앱이 뒤로 가도 잠깐 더 돌게 한다(Android RunService에 해당).
    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
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
        liveModels[id] = model
        demoMode = DemoSeed.isActive
        conversations = chats.list()
        hasEtlToken = AppStores.tokens.hasToken()
        notifyDismissedAt = AppStores.ui.int64("notifyBannerDismissedAt") ?? 0
        sync()
        events = EtlEvents.subscribe { [weak self] e in
            // 처리기는 도구의 Task에서 바로 불리므로 그 도구를 부른 대화를 여기서 읽는다.
            let origin = ToolOrigin.chatId
            Task { @MainActor in self?.onEtlEvent(e, origin: origin) }
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

    /// [chat]이 지금 보는 대화일 때만 화면 줄을 다시 만든다(뒤에서 답하는 대화는 화면을 건드리지 않는다).
    func sync(_ chat: String, scroll: Bool = false) {
        if chat == chatId { sync(scroll: scroll) }
    }

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

    /// 문제가 있으면 이유를 토스트로 알리고 입력과 첨부는 그대로 둔다. 다른 대화가 답하는 중이어도 보낼 수 있다.
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
        let m = model
        let hadHistory = m.messages.contains { $0.role == .user || $0.role == .assistant }
        m.addUser(text, attachments)
        let forChat = chatId
        chats.noteUserMessage(forChat, text, attachments, now: Self.nowMs())
        refreshConversations()
        sync(scroll: true)
        let imageFiles = images.compactMap { a in a.path.map { ($0, a.mime) } }
        let turn = RunningTurn(cancellation: TurnCancellation(), modelAuto: choice.isAuto)
        turns[forChat] = turn
        runningChats.insert(forChat)
        activities[forChat] = ActivityTracker.THINKING
        holdBackground()
        turn.task = Task { [weak self] in
            await self?.runTurn(forChat: forChat, model: m, turn: turn, prompt: prompt, imageFiles: imageFiles, hadHistory: hadHistory)
        }
    }

    private func sendProblem() -> String? {
        if demoMode { return busy ? "이전 답변이 끝날 때까지 기다려 주세요" : nil }
        if !auth.isSignedIn { return "먼저 로그인해야 해요" }
        if busy { return "이전 답변이 끝날 때까지 기다려 주세요" }
        return nil
    }

    /// [chat] 대화(기본: 지금 대화)의 답변만 멈춘다(NOTICE "답변을 멈췄어요"는 턴이 끝날 때 붙는다). 다른 대화의 답변과 확인 창은 그대로다.
    func stop(_ chat: String? = nil) {
        let id = chat ?? chatId
        approvals.cancel(origin: id)
        guard let turn = turns[id] else { return }
        if demoMode { turn.task?.cancel(); return }
        turn.cancellation.cancel()
    }

    private func runTurn(forChat: String, model m: ChatModel, turn: RunningTurn, prompt: String, imageFiles: [(String, String)],
                         hadHistory: Bool) async {
        let cancellation = turn.cancellation
        var outcome: TurnEnd = .completed
        defer { finishTurn(forChat, turn, outcome) }
        if demoMode {
            await DemoSeed.runDemoTurn(self, chatId: forChat, model: m, prompt: prompt)
            if Task.isCancelled { outcome = .interrupted }
            return
        }
        if models.isEmpty {
            activities[forChat] = "모델 목록을 불러오는 중이에요"
            await loadModels()
            activities[forChat] = ActivityTracker.THINKING
        }
        guard !cancellation.isCancelled else {
            if !turn.deleted {
                m.addNotice("답변을 멈췄어요")
                m.closeStreaming()
            }
            outcome = .interrupted
            return
        }
        guard let modelId = ModelCatalog.resolveModel(choice, models) else {
            if !turn.deleted {
                m.addNotice("보내지 못했어요: \(modelsError ?? "모델 목록을 불러오지 못했어요")")
                m.closeStreaming()
            }
            outcome = .failed
            return
        }
        let sanitized = ModelCatalog.sanitize(choice, models)

        var history: [ChatJSON] = []
        let threadId: String
        if let saved = chats.get(forChat)?.threadId {
            threadId = saved
            history = threads.load(saved)
            if history.isEmpty && hadHistory { m.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.") }
        } else {
            threadId = threads.newThreadId()
            chats.setThread(forChat, threadId)
            if hadHistory { m.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.") }
        }
        turn.localThreadId = threadId
        sync(forChat)

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
            m.addNotice("이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.")
            sync(forChat)
        }
        // 도구가 알리는 일(받은 파일, 바뀐 데이터)과 쓰기 승인 요청을 이 대화에 붙인다.
        let execute: ToolExecutor = { call in
            await ToolOrigin.$chatId.withValue(forChat) { await toolbox.run(name: call.name, arguments: call.arguments) }
        }
        let stream: AsyncStream<ResponsesEvent>
        if usingNative, let client = auth.codex {
            // 엔진 하나가 여러 대화의 턴을 함께 돌린다(이벤트는 스레드로 나눈다).
            if codexEngine == nil || codexEngineClient !== client {
                codexEngine = CodexEngine(client: client)
                codexEngineClient = client
            }
            stream = await codexEngine!.send(history: history, input: [ResponsesInput.userMessage(prompt, images: images)],
                                              config: config, cancellation: cancellation, tools: execute)
        } else {
            // API 키 모드: 한 엔진은 한 번에 한 턴만 돌리므로 턴마다 새로 만든다.
            stream = await ResponsesEngine(auth: auth.provider).send(
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
                activities[forChat] = label
            case .textDelta(let itemId, let delta):
                m.appendAssistant(itemId, delta)
                sync(forChat)
            case .textDone(let itemId, let text):
                m.finishAssistant(itemId, text)
                sync(forChat)
            case .notice(let text):
                m.addNotice(text)
                sync(forChat)
            case .turnEnded(let result):
                if turn.deleted {
                    // 답변 중에 지운 대화: 기록을 남기지 않고, 이번 턴이 만든 엔진 스레드도 지운다.
                    queueNativeDeletes(result.history)
                } else {
                    threads.save(threadId, nativeReferences + result.history)
                }
                m.closeStreaming()
                switch result.status {
                case .interrupted:
                    approvals.cancel(origin: forChat)
                    m.addNotice("답변을 멈췄어요")
                    outcome = .interrupted
                case .failed(let err):
                    approvals.cancel(origin: forChat)
                    m.addNotice(err.userMessage)
                    if !turn.modelAuto { m.addNotice("선택한 모델 설정 때문일 수 있어요. 설정에서 '자동'으로 되돌려 보세요.") }
                    outcome = .failed
                    if err.needsSignIn { await auth.refreshAccount() }
                case .incomplete:
                    if result.finalText.isEmpty { m.addNotice("답변을 만들지 못했어요") }
                case .completed:
                    break
                }
                sync(forChat)
            default:
                break
            }
        }
    }

    /// 한 대화의 답변이 끝났다. 보지 않는 대화면 막대로, 채팅 화면이 안 보이면 기기 알림으로 알린다.
    private func finishTurn(_ chat: String, _ turn: RunningTurn, _ end: TurnEnd) {
        guard turns[chat] === turn else { return }
        turns[chat] = nil
        runningChats.remove(chat)
        activities[chat] = nil
        approvals.cancel(origin: chat)
        if turn.deleted {
            purgeLocalThread(turn.localThreadId)
        } else {
            sync(chat)
            let text = TurnLogic.finishText(title: chats.get(chat)?.title, ok: end == .completed)
            switch TurnLogic.finishNotice(chatId: chat, visibleChatId: chatId, chatScreenVisible: chatScreenVisible, end: end, deleted: false) {
            case .none: break
            case .bar: finishBar = FinishBarItem(chatId: chat, text: text)
            case .notification: LocalNotifications.shared.postAnswerDone(chatId: chat, text: text)
            }
        }
        refreshApproval()
        releaseBackgroundIfIdle()
    }

    /// 답하는 대화가 있는 동안 앱이 뒤로 가도 iOS가 허락하는 만큼 더 돌게 한다(Android RunService에 해당).
    /// 모든 답변이 끝나면 놓는다. 시간이 다 되면 놓기만 하고 답변은 멈추지 않는다(그 뒤는 iOS가 앱을 멈춘다).
    private func holdBackground() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "youngmin-answers") { [weak self] in
            MainActor.assumeIsolated { self?.endBackgroundTask() }
        }
    }

    private func releaseBackgroundIfIdle() {
        if turns.isEmpty { endBackgroundTask() }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
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

    /// 빈 새 대화를 연다(아무것도 보내지 않은 대화는 목록에 쌓이지 않는다). 다른 대화가 답하는 중이어도 열 수 있다.
    @discardableResult
    func newConversation() -> Bool {
        if chats.get(chatId) == nil && model.messages.isEmpty { return true }
        openChat(chats.newId())
        return true
    }

    /// 목록의 대화를 연다. 답하는 중인 대화도 열 수 있다(그 답변은 뒤에서 계속된다).
    @discardableResult
    func openConversation(_ id: String) -> Bool {
        if !ChatStore.isSafeId(id) { return false }
        if id == chatId { return true }
        if chats.get(id) == nil && turns[id] == nil { return false }
        openChat(id)
        return true
    }

    /// 다른 대화로 옮긴다. 지금 대화의 입력창 글과 첨부는 그 대화에 남겨 두고, 옮겨 간 대화의 것을 꺼낸다.
    private func openChat(_ id: String) {
        let oldId = chatId
        if chats.get(oldId) != nil { model.save() } else if turns[oldId] == nil { liveModels[oldId] = nil }
        if input.isEmpty && pending.isEmpty { drafts[oldId] = nil } else { drafts[oldId] = (input, pending) }
        chatId = id
        settings.currentChatId = id
        if let live = liveModels[id] {
            model = live
        } else {
            let m = ChatModel(chats.fileFor(id))
            m.load()
            liveModels[id] = m
            model = m
        }
        let draft = drafts.removeValue(forKey: id)
        input = draft?.0 ?? ""
        pending = draft?.1 ?? []
        if finishBar?.chatId == id { finishBar = nil }
        LocalNotifications.shared.removeAnswerDone(chatId: id)
        sync(scroll: true)
        refreshApproval()
    }

    /// 답하는 중에도 바꿀 수 있다.
    func renameConversation(_ id: String, _ title: String) {
        chats.rename(id, title)
        refreshConversations()
    }

    /// 대화를 지운다. 답하는 중이면 그 답변을 먼저 멈추고, 엔진 쪽 대화 기록은 답변이 끝난 뒤 지운다.
    func deleteConversation(_ id: String) {
        guard let removed = chats.delete(id) else { return }
        drafts[id] = nil
        if id == chatId { openChat(chats.newId()) }
        liveModels.removeValue(forKey: id)?.discard()
        if finishBar?.chatId == id { finishBar = nil }
        LocalNotifications.shared.removeAnswerDone(chatId: id)
        if let turn = turns[id] {
            turn.deleted = true
            turn.localThreadId = turn.localThreadId ?? removed.threadId
            stop(id)
        } else {
            purgeLocalThread(removed.threadId)
        }
        refreshConversations()
    }

    /// 앱 쪽 스레드 기록과 그 안의 엔진 스레드를 지운다.
    private func purgeLocalThread(_ t: String?) {
        guard let t, ThreadPurge.isThreadId(t) else { return }
        queueNativeDeletes(threads.load(t))
        if !threads.delete(t) { settings.pendingThreadDeletes = settings.pendingThreadDeletes.union([t]) }
    }

    /// 기록에 남은 엔진 스레드(교체된 것 포함)를 지우기로 기록하고 지운다.
    private func queueNativeDeletes(_ history: [ChatJSON]) {
        let nativeIDs = history.filter { $0["type"]?.string == CodexEngine.threadMarker }
            .compactMap { $0["thread_id"]?.string }.filter(ThreadPurge.isThreadId)
        guard !nativeIDs.isEmpty else { return }
        settings.pendingNativeThreadDeletes = settings.pendingNativeThreadDeletes.union(nativeIDs)
        Task { await purgeNativeThreads() }
    }

    private func purgeNativeThreads() async {
        guard let client = auth.codex else { return }
        for id in settings.pendingNativeThreadDeletes.union(StudyDataProfile.demoNativeThreadDeletes) {
            do {
                _ = try await client.request("thread/delete", ["threadId": .string(id)])
                settings.pendingNativeThreadDeletes = settings.pendingNativeThreadDeletes.subtracting([id])
                StudyDataProfile.demoNativeThreadDeletes = StudyDataProfile.demoNativeThreadDeletes.subtracting([id])
            } catch let error as CodexError where error.message == "thread not found: \(id)" {
                settings.pendingNativeThreadDeletes = settings.pendingNativeThreadDeletes.subtracting([id])
                StudyDataProfile.demoNativeThreadDeletes = StudyDataProfile.demoNativeThreadDeletes.subtracting([id])
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

    /// 화면을 떠날 때(Android onPause): 창을 닫고 대기 요청은 거절된다. 대화를 옮기는 것은 떠나는 것이 아니다.
    func detachApprovals() {
        approvals.setChangeHandler(nil)
        if let h = approvalHost { approvals.detachHost(h) }
        approvalHost = nil
        approval = nil
        approvalElsewhere = nil
    }

    /// 확인 창은 그 요청을 낸 대화를 보고 있을 때만 띄운다. 다른 대화의 요청만 있으면 위쪽 배너로 알린다(누르면 그 대화로).
    /// 대화를 옮겨도 요청은 취소되지 않는다(취소는 화면을 떠날 때, 그 답변이 끝날 때, 90초).
    private func refreshApproval() {
        guard approvalHost != nil else { approval = nil; approvalElsewhere = nil; return }
        let pending = approvals.pending()
        switch TurnLogic.approvalUi(pending.map { (id: $0.id, chatId: $0.origin) }, visibleChatId: chatId) {
        case .dialog(let requestId):
            approvalElsewhere = nil
            if approval?.id != requestId { approval = pending.first { $0.id == requestId } }
        case .elsewhere(let chat):
            approval = nil
            approvalElsewhere = chat
        case .none:
            approval = nil
            approvalElsewhere = nil
        }
    }

    /// "변경 승인"(true) / "취소"(false).
    func decideApproval(_ approved: Bool) {
        guard let host = approvalHost, let req = approval else { return }
        approval = nil
        approvals.decide(hostId: host, requestId: req.id, approved: approved)
        refreshApproval()
    }

    // MARK: 도구 이벤트

    /// [origin]: 그 도구를 부른 대화(`ToolOrigin.chatId`). 모르면(도구 시험 실행 등) 지금 대화에 붙인다.
    /// 지운 대화에서 온 바뀐 데이터 알림은 사용자가 꼭 봐야 하므로 지금 대화에 남긴다.
    private func onEtlEvent(_ e: EtlEvents.Event, origin: String?) {
        let target = origin.flatMap { id -> String? in
            if id == chatId { return id }
            if let turn = turns[id], turn.deleted { return nil }
            return liveModels[id] != nil ? id : nil
        }
        switch e {
        case .filesSaved(let files, let pagePreview):
            if origin != nil && target == nil { return }
            let m = target.flatMap { liveModels[$0] } ?? model
            m.addFiles(files.map {
                Attachment($0.name, $0.mime, $0.size, $0.file.path, $0.phoneUri, $0.shownPath,
                           pagePreview: pagePreview, referenceOnly: pagePreview || $0.referenceOnly)
            })
            sync(target ?? chatId)
        case .notice(let text):
            let m = target.flatMap { liveModels[$0] } ?? model
            m.addNotice(text)
            sync(target ?? chatId)
        case .dataChanged:
            break
        }
    }

    // MARK: 시연(DemoSeed)

    /// 시연 답변이 화면을 움직이는 데 쓴다.
    func demoSetActivity(_ chat: String, _ label: String?) { activities[chat] = label }
}
