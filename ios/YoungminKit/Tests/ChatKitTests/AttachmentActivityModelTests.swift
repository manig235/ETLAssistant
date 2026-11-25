import Testing
@testable import ChatKit

// Kotlin AttachmentLogicTest
@Suite struct AttachmentLogicTests {
    @Test func promptWithoutAttachmentsIsUnchanged() {
        #expect(AttachmentLogic.promptText("안녕", [], imageCount: 0) == "안녕")
    }

    @Test func promptListsFilesAndImages() {
        let p = AttachmentLogic.promptText("이거 봐줘", [("etl-files/uploads/1-보고서.pdf", "application/pdf", 1_300_000)], imageCount: 2)
        #expect(p.hasPrefix("이거 봐줘\n\n[사용자가 첨부함]"))
        #expect(p.contains("사진 2장"))
        #expect(p.contains("- etl-files/uploads/1-보고서.pdf (application/pdf, 1.2MB)"))
        #expect(p.contains("etl_render_pages"))
    }

    @Test func emptyTextWithAttachmentGetsDefaultQuestion() {
        let p = AttachmentLogic.promptText("  ", [], imageCount: 1)
        #expect(p.hasPrefix("첨부한 것을 확인해 줘."))
        #expect(!p.contains("etl_read_file"))
    }

    @Test func uploadNameSwapsExtensionWhenReencoded() {
        #expect(AttachmentLogic.uploadName(7, "IMG_1.HEIC", reencoded: true) == "7-IMG_1.jpg")
        #expect(AttachmentLogic.uploadName(7, "a.pdf", reencoded: false) == "7-a.pdf")
        #expect(AttachmentLogic.uploadName(7, "noext", reencoded: true) == "7-noext.jpg")
    }

    @Test func reencodeRules() {
        #expect(AttachmentLogic.shouldReencode("image/jpeg"))
        #expect(AttachmentLogic.shouldReencode("image/heic"))
        #expect(!AttachmentLogic.shouldReencode("image/gif"))
        #expect(!AttachmentLogic.shouldReencode("application/pdf"))
    }

    @Test func sizes() {
        #expect(AttachmentLogic.formatSize(12) == "12B")
        #expect(AttachmentLogic.formatSize(340 * 1024) == "340KB")
        #expect(AttachmentLogic.formatSize(1536 * 1024) == "1.5MB")
    }

    @Test func kinds() {
        #expect(AttachmentLogic.kindOf("a.PDF", "application/octet-stream") == .pdf)
        #expect(AttachmentLogic.kindOf("과제.hwp", "application/x-hwp") == .hwp)
        #expect(AttachmentLogic.kindOf("lec.pptx", "x") == .slides)
        #expect(AttachmentLogic.kindOf("p", "image/png") == .image)
        #expect(AttachmentLogic.kindOf("main.py", "text/x-python") == .code)
        #expect(AttachmentLogic.kindOf("x.bin", "application/octet-stream") == .other)
    }

    @Test func availabilityAndRedownload() {
        #expect(AttachmentLogic.availability(workspaceExists: true, phoneUri: nil, phoneAlive: false, cleaned: true) == .ok)
        #expect(AttachmentLogic.availability(workspaceExists: false, phoneUri: "x", phoneAlive: true, cleaned: true) == .ok)
        #expect(AttachmentLogic.availability(workspaceExists: false, phoneUri: "x", phoneAlive: false, cleaned: true) == .cleaned)
        #expect(AttachmentLogic.availability(workspaceExists: false, phoneUri: nil, phoneAlive: false, cleaned: false) == .deleted)
        #expect(AttachmentLogic.redownloadPrompt("a.pdf", generated: true) == "\"a.pdf\" 다시 만들어줘")
        #expect(AttachmentLogic.redownloadPrompt("a.pdf", generated: false) == "\"a.pdf\" 파일 다시 받아줘")
    }

    @Test func sendModeAllowsAttachmentOnly() {
        #expect(ChatLogic.sendMode(busy: false, input: " ", hasAttachments: true) == .send)
        #expect(ChatLogic.sendMode(busy: false, input: "안녕", hasAttachments: true, importing: true) == .disabled)
        #expect(ChatLogic.sendMode(busy: true, input: "", hasAttachments: true) == .stop)
    }
}

// Kotlin ActivityTrackerTest
@Suite struct ActivityTrackerTests {
    @Test func idleShowsNothing() {
        let t = ActivityTracker()
        #expect(t.current() == nil)
        #expect(t.toolStarted("a", "etl_digest") == nil)
        #expect(t.textDelta() == nil)
    }

    @Test func turnStartShowsThinking() {
        #expect(ActivityTracker().turnStarted() == ActivityTracker.THINKING)
    }

    @Test func toolsAreGroupedIntoPlainLabels() {
        for t in ["etl_digest", "etl_courses", "etl_assignment", "etl_search_files", "etl_verify_submission", "etl_get"] {
            #expect(ActivityTracker.forTool(t) == "조회하는 중…")
        }
        #expect(ActivityTracker.forTool("etl_download") == "다운로드하는 중…")
        #expect(ActivityTracker.forTool("etl_read_file") == "파일 읽는 중…")
        #expect(ActivityTracker.forTool("etl_render_pages") == "파일 읽는 중…")
        #expect(ActivityTracker.forTool("something_new") == "처리하는 중…")
    }

    @Test func toolLifecycleReturnsToThinkingThenHidesWhileTextStreams() {
        let t = ActivityTracker()
        t.turnStarted()
        #expect(t.toolStarted("1", "etl_digest") == "조회하는 중…")
        #expect(t.toolFinished("1") == ActivityTracker.THINKING)
        #expect(t.textDelta() == nil)
        #expect(t.textDelta() == nil)
        #expect(t.messageDone() == ActivityTracker.THINKING)
        #expect(t.turnEnded() == nil)
        #expect(t.current() == nil)
    }

    @Test func parallelToolsShowTheLatestAndKeepShowingUntilAllDone() {
        let t = ActivityTracker()
        t.turnStarted()
        #expect(t.toolStarted("a", "etl_assignment") == "조회하는 중…")
        #expect(t.toolStarted("b", "etl_verify_submission") == "조회하는 중…")
        #expect(t.toolFinished("a") == "조회하는 중…")
        #expect(t.toolFinished("b") == ActivityTracker.THINKING)
    }

    @Test func labelFollowsTheMostRecentlyStartedTool() {
        let t = ActivityTracker()
        t.turnStarted()
        t.toolStarted("a", "etl_search_files")
        #expect(t.toolStarted("b", "etl_download") == "다운로드하는 중…")
        #expect(t.toolFinished("b") == "조회하는 중…")
    }

    @Test func toolAfterTextShowsAgain() {
        let t = ActivityTracker()
        t.turnStarted()
        t.textDelta()
        #expect(t.toolStarted("1", "etl_download") == "다운로드하는 중…")
    }

    @Test func newTurnClearsLeftovers() {
        let t = ActivityTracker()
        t.turnStarted()
        t.toolStarted("1", "etl_download")
        t.turnEnded()
        #expect(t.turnStarted() == ActivityTracker.THINKING)
    }

    @Test func finishingUnknownToolIsHarmless() {
        let t = ActivityTracker()
        t.turnStarted()
        #expect(t.toolFinished("nope") == ActivityTracker.THINKING)
    }
}

// Kotlin ModelCatalogTest (+ iOS 모델 목록)
@Suite struct ModelCatalogTests {
    private func eff(_ e: String...) -> ChatJSON {
        .array(e.map { ["reasoningEffort": .string($0), "description": .string("\($0) 설명")] })
    }

    private func model(_ id: String, name: String? = nil, isDefault: Bool = false, hidden: Bool = false,
                       def: String? = "medium", efforts: ChatJSON? = nil, noEfforts: Bool = false) -> ChatJSON {
        let n = name ?? id.uppercased()
        var o: [String: ChatJSON] = ["id": .string(id), "model": .string(id), "displayName": .string(n), "description": .string("\(n) 설명"),
                                     "isDefault": .bool(isDefault), "hidden": .bool(hidden)]
        if let def { o["defaultReasoningEffort"] = .string(def) }
        if !noEfforts { o["supportedReasoningEfforts"] = efforts ?? eff("low", "medium", "high") }
        return .object(o)
    }

    private func result(_ m: ChatJSON...) -> ChatJSON { ["data": .array(m)] }

    private func info(_ id: String, _ isDefault: Bool = false, _ efforts: String...) -> ModelInfo {
        ModelInfo(id: id, model: id, displayName: id.uppercased(), description: "", isDefault: isDefault, hidden: false,
                  defaultEffort: efforts.first, efforts: efforts.map { EffortOption($0, "") })
    }

    @Test func parseNormal() {
        let list = ModelCatalog.parse(result(model("a", isDefault: true, def: "high", efforts: eff("medium", "high"))))
        #expect(list.count == 1)
        let m = list[0]
        #expect(m.id == "a" && m.model == "a" && m.displayName == "A" && m.description == "A 설명")
        #expect(m.isDefault)
        #expect(m.defaultEffort == "high")
        #expect(m.efforts == [EffortOption("medium", "medium 설명"), EffortOption("high", "high 설명")])
    }

    @Test func parseDropsHidden() {
        #expect(ModelCatalog.parse(result(model("a"), model("b", hidden: true), model("c"))).map(\.id) == ["a", "c"])
    }

    @Test func parseSkipsBrokenEntries() {
        let noModel: ChatJSON = ["id": "x", "displayName": "X"]
        let noName: ChatJSON = ["id": "y", "model": "y"]
        let wrongType: ChatJSON = ["id": 5, "model": "z", "displayName": "Z"]
        let list = ModelCatalog.parse(["data": [noModel, model("ok"), noName, "문자열", nil, wrongType]])
        #expect(list.map(\.id) == ["ok"])
    }

    @Test func parseSkipsBrokenEfforts() {
        let efforts: ChatJSON = [
            ["reasoningEffort": "low", "description": "빠름"], ["description": "이름 없음"],
            ["reasoningEffort": "", "description": "빈 이름"], "엉뚱한 값", ["reasoningEffort": "high"],
        ]
        let m = ModelCatalog.parse(result(model("a", efforts: efforts)))
        #expect(m.count == 1)
        #expect(m[0].efforts == [EffortOption("low", "빠름"), EffortOption("high", "")])
    }

    @Test func parseDefaultFirstKeepingOrder() {
        #expect(ModelCatalog.parse(result(model("a"), model("b"), model("c", isDefault: true), model("d"))).map(\.id) == ["c", "a", "b", "d"])
    }

    @Test func parseNoEfforts() {
        let none = ModelCatalog.parse(result(model("a", def: nil, noEfforts: true)))[0]
        #expect(none.efforts.isEmpty)
        #expect(none.defaultEffort == nil)
        #expect(ModelCatalog.parse(result(model("b", def: "")))[0].defaultEffort == nil)
    }

    @Test func parseEmptyOrMissingData() {
        #expect(ModelCatalog.parse(["data": []]).isEmpty)
        #expect(ModelCatalog.parse([:]).isEmpty)
    }

    @Test func effortLabels() {
        #expect(ModelCatalog.effortLabel("none") == "끔(가장 빠름)")
        #expect(ModelCatalog.effortLabel("minimal") == "최소")
        #expect(ModelCatalog.effortLabel("low") == "낮음")
        #expect(ModelCatalog.effortLabel("medium") == "보통")
        #expect(ModelCatalog.effortLabel("high") == "높음")
        #expect(ModelCatalog.effortLabel("xhigh") == "매우 높음")
        #expect(ModelCatalog.effortLabel("ultra") == "ultra")
        #expect(ModelCatalog.effortLabel("max_plus") == "max_plus")
    }

    private var models: [ModelInfo] { [info("gpt-a", true, "low", "high"), info("gpt-b", false, "medium")] }

    @Test func labels() {
        #expect(ModelCatalog.choiceLabel(ModelChoice(), models) == "자동")
        #expect(ModelCatalog.choiceLabel(ModelChoice("gpt-b"), models) == "GPT-B")
        #expect(ModelCatalog.choiceLabel(ModelChoice("gpt-a", "high"), models) == "GPT-A · 높음")
        #expect(ModelCatalog.choiceLabel(ModelChoice(nil, "low"), models) == "자동 · 낮음")
        #expect(ModelCatalog.choiceLabel(ModelChoice("gpt-old"), models) == "gpt-old")
        #expect(ModelCatalog.choiceLabel(ModelChoice("gpt-old", "medium"), []) == "gpt-old · 보통")
        // iOS: 목록을 받기 전에는 저장해 둔 표시 이름(앱을 연 직후 슬러그가 보이지 않게). 목록이 있으면 목록 이름이 먼저.
        #expect(ModelCatalog.choiceLabel(ModelChoice("gpt-6.1-terra"), [], savedName: "GPT-6.1 Terra") == "GPT-6.1 Terra")
        #expect(ModelCatalog.choiceLabel(ModelChoice("gpt-b", "high"), [], savedName: "GPT-B") == "GPT-B · 높음")
        #expect(ModelCatalog.choiceLabel(ModelChoice("gpt-b"), models, savedName: "옛 이름") == "GPT-B")
        #expect(ModelCatalog.choiceLabel(ModelChoice(), [], savedName: "GPT-B") == "자동")
        let settings = ChatSettings(InMemoryChatKeyValueStore())
        settings.modelName = "GPT-B"
        #expect(settings.modelName == "GPT-B")
        settings.modelName = ""
        #expect(settings.modelName == nil)
    }

    @Test func sanitize() {
        let c = ModelChoice("whatever", "weird")
        #expect(ModelCatalog.sanitize(c, []) == c)
        #expect(ModelCatalog.sanitize(ModelChoice("gpt-old", "low"), models) == ModelChoice(nil, "low"))
        #expect(ModelCatalog.sanitize(ModelChoice("gpt-b", "high"), models) == ModelChoice("gpt-b", nil))
        #expect(ModelCatalog.sanitize(ModelChoice("gpt-b", "medium"), models) == ModelChoice("gpt-b", "medium"))
        #expect(ModelCatalog.sanitize(ModelChoice(nil, "high"), models) == ModelChoice(nil, "high"))
        #expect(ModelCatalog.sanitize(ModelChoice(nil, "medium"), models) == ModelChoice(nil, nil))
        let noDefault = [info("x", false, "low"), info("y", false, "high")]
        #expect(ModelCatalog.sanitize(ModelChoice(nil, "low"), noDefault) == ModelChoice(nil, "low"))
        #expect(ModelCatalog.sanitize(ModelChoice(nil, "high"), noDefault) == ModelChoice(nil, nil))
        #expect(ModelCatalog.sanitize(ModelChoice("gpt-old", "high"), models) == ModelChoice(nil, "high"))
        #expect(ModelCatalog.sanitize(ModelChoice("gpt-old", "medium"), models) == ModelChoice(nil, nil))
    }

    // ---- iOS ----

    @Test func resolveAutoUsesDefault() {
        #expect(ModelCatalog.resolveModel(ModelChoice(), models) == "gpt-a")
        #expect(ModelCatalog.resolveModel(ModelChoice("gpt-b"), models) == "gpt-b")
        #expect(ModelCatalog.resolveModel(ModelChoice("gone"), models) == "gpt-a")
        #expect(ModelCatalog.resolveModel(ModelChoice(), []) == nil)
        #expect(ModelCatalog.resolveModel(ModelChoice("x"), []) == "x")
    }

    @Test func parseChatGPTModelList() throws {
        let json = try ChatJSON(parsing: """
        {"models":[
          {"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},
          {"slug":"internal","display_name":"Hidden","visibility":"hide"},
          {"slug":"gpt-6.2-sol","display_name":"GPT-6.2 Sol","visibility":"list",
           "supported_reasoning_levels":[{"effort":"low","description":"빠름"},{"effort":"high","description":"깊게"}],
           "default_reasoning_level":"low"}
        ]}
        """)
        let list = ModelCatalog.parseOpenAIModels(json)
        #expect(list.map(\.model) == ["gpt-6.1-sol", "gpt-6.2-sol"])
        #expect(list[0].isDefault && !list[1].isDefault)
        #expect(list[0].displayName == "GPT-6.1 Sol")
        #expect(list[0].efforts == ModelCatalog.ASSUMED_EFFORTS)
        #expect(list[1].efforts == [EffortOption("low", "빠름"), EffortOption("high", "깊게")])
        #expect(list[1].defaultEffort == "low")
    }

    @Test func parseAPIKeyModelList() throws {
        let json = try ChatJSON(parsing: """
        {"object":"list","data":[
          {"id":"text-embedding-3-small","object":"model","created":10},
          {"id":"gpt-4.1","object":"model","created":20},
          {"id":"gpt-6-mini","object":"model","created":40},
          {"id":"gpt-6","object":"model","created":30},
          {"id":"gpt-realtime","object":"model","created":50},
          {"id":"o4-mini","object":"model","created":25},
          {"id":"dall-e-3","object":"model","created":5}
        ]}
        """)
        let list = ModelCatalog.parseOpenAIModels(json)
        #expect(list.map(\.model) == ["gpt-6", "gpt-6-mini", "o4-mini", "gpt-4.1"])
        #expect(list[0].isDefault)
        #expect(list.first { $0.model == "gpt-4.1" }!.efforts.isEmpty)
        #expect(list.first { $0.model == "o4-mini" }!.efforts == ModelCatalog.ASSUMED_EFFORTS)
    }
}
