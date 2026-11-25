import Foundation

/// 모델이 지원하는 추론 강도 하나(Kotlin `EffortOption`). `effort`는 API 값(`none`, `low`, `medium`, `high` 등).
public struct EffortOption: Sendable, Hashable {
    public var effort: String
    public var description: String
    public init(_ effort: String, _ description: String) {
        self.effort = effort
        self.description = description
    }
}

/// 선택 가능한 모델 하나(Kotlin `ModelInfo`).
public struct ModelInfo: Sendable, Hashable {
    /// 목록의 고유 ID.
    public var id: String
    /// 요청의 `model`에 넣는 값.
    public var model: String
    public var displayName: String
    public var description: String
    public var isDefault: Bool
    public var hidden: Bool
    public var defaultEffort: String?
    public var efforts: [EffortOption]

    public init(id: String, model: String, displayName: String, description: String, isDefault: Bool, hidden: Bool,
                defaultEffort: String?, efforts: [EffortOption]) {
        self.id = id
        self.model = model
        self.displayName = displayName
        self.description = description
        self.isDefault = isDefault
        self.hidden = hidden
        self.defaultEffort = defaultEffort
        self.efforts = efforts
    }
}

/// 사용자의 선택(Kotlin `ModelChoice`). nil은 "자동"이다.
/// `model`이 nil이면 모델은 자동, `effort`가 nil이면 추론 강도는 그 모델의 기본값.
public struct ModelChoice: Sendable, Hashable {
    public var model: String?
    public var effort: String?
    public init(_ model: String? = nil, _ effort: String? = nil) {
        self.model = model
        self.effort = effort
    }
    public var isAuto: Bool { model == nil && effort == nil }
}

/// 모델 목록 해석과 선택 규칙(Kotlin `ModelCatalog`) + iOS용 OpenAI 모델 목록 해석(새로 만듦).
public enum ModelCatalog {
    /// Codex `model/list` 응답의 result를 `ModelInfo` 목록으로. 숨김 모델은 뺀다.
    /// 필드가 빠졌거나 타입이 이상한 항목은 조용히 건너뛴다. 순서는 응답 그대로 두되 기본 모델은 맨 앞으로 올린다.
    public static func parse(_ result: ChatJSON) -> [ModelInfo] {
        guard let data = result["data"]?.array else { return [] }
        let list = data.compactMap(parseOne).filter { !$0.hidden }
        return stableSorted(list) { ($0.isDefault ? 0 : 1) < ($1.isDefault ? 0 : 1) }
    }

    private static func parseOne(_ o: ChatJSON) -> ModelInfo? {
        guard o.object != nil else { return nil }
        guard let id = o["id"]?.string, !id.isEmpty,
              let model = o["model"]?.string, !model.isEmpty,
              let name = o["displayName"]?.string, !name.isEmpty else { return nil }
        var efforts: [EffortOption] = []
        for e in o["supportedReasoningEfforts"]?.array ?? [] {
            guard e.object != nil, let v = e["reasoningEffort"]?.string, !v.isEmpty else { continue }
            efforts.append(EffortOption(v, e["description"]?.string ?? ""))
        }
        let def = o["defaultReasoningEffort"]?.string
        return ModelInfo(id: id, model: model, displayName: name, description: o["description"]?.string ?? "",
                         isDefault: o["isDefault"]?.bool == true, hidden: o["hidden"]?.bool == true,
                         defaultEffort: (def?.isEmpty ?? true) ? nil : def, efforts: efforts)
    }

    /// 추론 강도의 한국어 이름. 모르는 값은 그대로.
    public static func effortLabel(_ effort: String) -> String {
        switch effort {
        case "none": return "끔(가장 빠름)"
        case "minimal": return "최소"
        case "low": return "낮음"
        case "medium": return "보통"
        case "high": return "높음"
        case "xhigh": return "매우 높음"
        default: return effort
        }
    }

    /// 지금 선택을 한 줄로(채팅 화면 상단 칩 등). 예: "자동", "gpt-6.1-sol · 높음".
    /// [savedName]은 고를 때 저장해 둔 표시 이름(iOS): 목록을 아직 받지 못했으면 슬러그 대신 이것을 쓴다.
    public static func choiceLabel(_ choice: ModelChoice, _ models: [ModelInfo], savedName: String? = nil) -> String {
        let modelName = choice.model.map { m in models.first { $0.model == m }?.displayName ?? savedName ?? m }
        let effort = choice.effort.map(effortLabel)
        switch (modelName, effort) {
        case (nil, nil): return "자동"
        case (nil, let e?): return "자동 · \(e)"
        case (let m?, nil): return m
        case (let m?, let e?): return "\(m) · \(e)"
        }
    }

    /// 요청을 만들 때 쓸 "유효한" 선택으로 다듬는다. 저장값을 바꾸는 게 아니라 이번 요청에서만 쓴다.
    /// 목록이 비어 있으면(아직 모르면) 입력 그대로 돌려준다.
    public static func sanitize(_ choice: ModelChoice, _ models: [ModelInfo]) -> ModelChoice {
        if models.isEmpty { return choice }
        let chosen = choice.model.flatMap { m in models.first { $0.model == m } }
        let model = (choice.model != nil && chosen == nil) ? nil : choice.model
        var effort = choice.effort
        if let e = effort {
            let target = chosen ?? models.first { $0.isDefault } ?? models[0]
            if !target.efforts.contains(where: { $0.effort == e }) { effort = nil }
        }
        return ModelChoice(model, effort)
    }

    // MARK: - iOS (새로 만듦)

    /// "자동"일 때 쓸 모델: 기본 표시가 있는 모델, 없으면 목록의 첫 모델. 목록이 비면 nil.
    /// Responses API는 `model`을 꼭 받으므로(Codex처럼 서버 기본값이 없다) 요청 전에 이것으로 정한다.
    public static func resolveModel(_ choice: ModelChoice, _ models: [ModelInfo]) -> String? {
        let c = sanitize(choice, models)
        if let m = c.model { return m }
        return (models.first { $0.isDefault } ?? models.first)?.model
    }

    /// API 키로 받은 목록에서 추론 강도를 모를 때 붙이는 기본값(검증되지 않은 추정, 문서 참고).
    public static let ASSUMED_EFFORTS: [EffortOption] = [EffortOption("low", ""), EffortOption("medium", ""), EffortOption("high", "")]

    /// `GET /v1/models` 응답을 `ModelInfo` 목록으로.
    /// - ChatGPT 로그인(SIWC): `{"models":[{"slug","display_name","visibility",…}]}` — `visibility == "list"`만, 서버 순서 유지,
    ///   첫 모델을 기본(자동)으로 본다. `supported_reasoning_levels`/`default_reasoning_level`이 있으면 읽는다(문서에 없는 추정 필드).
    /// - API 키: `{"object":"list","data":[{"id","created","owned_by"}]}` — 대화용으로 보이는 `gpt-`/`o<숫자>` 모델만(추정),
    ///   최신(`created`) 순. 접미사 없는 `gpt-<버전>` 중 가장 새 것을 기본으로 본다.
    public static func parseOpenAIModels(_ json: ChatJSON) -> [ModelInfo] {
        if let models = json["models"]?.array { return parseChatGPTModels(models) }
        if let data = json["data"]?.array { return parseAPIKeyModels(data) }
        return []
    }

    private static func parseChatGPTModels(_ models: [ChatJSON]) -> [ModelInfo] {
        var out: [ModelInfo] = []
        for m in models {
            guard let slug = m["slug"]?.string, !slug.isEmpty else { continue }
            guard m["visibility"]?.string == "list" else { continue }
            let name = m["display_name"]?.string.flatMap { $0.isEmpty ? nil : $0 } ?? slug
            var efforts: [EffortOption] = []
            for e in m["supported_reasoning_levels"]?.array ?? [] {
                if let v = e["effort"]?.string, !v.isEmpty { efforts.append(EffortOption(v, e["description"]?.string ?? "")) }
                else if let v = e.string, !v.isEmpty { efforts.append(EffortOption(v, "")) }
            }
            let def = m["default_reasoning_level"]?.string
            out.append(ModelInfo(id: slug, model: slug, displayName: name, description: m["description"]?.string ?? "",
                                 isDefault: out.isEmpty, hidden: false,
                                 defaultEffort: (def?.isEmpty ?? true) ? nil : def,
                                 efforts: efforts.isEmpty ? ASSUMED_EFFORTS : efforts))
        }
        return out
    }

    private static let EXCLUDED_PARTS = ["audio", "realtime", "tts", "transcribe", "image", "search", "embedding", "moderation", "instruct", "dall-e", "whisper", "davinci", "babbage", "computer-use"]

    /// 대화(Responses)에 쓸 만한 모델 ID인가(추정).
    static func isChatModelId(_ id: String) -> Bool {
        let lower = id.lowercased()
        guard lower.hasPrefix("gpt-") || isOSeries(lower) else { return false }
        return !EXCLUDED_PARTS.contains { lower.contains($0) }
    }

    private static func isOSeries(_ id: String) -> Bool {
        let u = Array(id.utf8)
        return u.count >= 2 && u[0] == UInt8(ascii: "o") && (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(u[1])
    }

    /// 추론(reasoning) 모델로 보이는가(추정): `gpt-5` 이상, `o<숫자>` 계열.
    static func looksLikeReasoningModel(_ id: String) -> Bool {
        let lower = id.lowercased()
        if isOSeries(lower) { return true }
        guard lower.hasPrefix("gpt-") else { return false }
        let digits = lower.dropFirst(4).prefix { $0.isNumber }
        return (Int(digits) ?? 0) >= 5
    }

    /// 접미사 없는 `gpt-<숫자>[.<숫자>]`인가.
    static func isBaseFamily(_ id: String) -> Bool {
        let lower = id.lowercased()
        guard lower.hasPrefix("gpt-") else { return false }
        let rest = lower.dropFirst(4)
        guard !rest.isEmpty else { return false }
        let parts = rest.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count <= 2 && parts.allSatisfy { !$0.isEmpty && $0.allSatisfy(\.isNumber) }
    }

    private static func parseAPIKeyModels(_ data: [ChatJSON]) -> [ModelInfo] {
        var rows: [(id: String, created: Double)] = []
        for m in data {
            guard let id = m["id"]?.string, !id.isEmpty, isChatModelId(id) else { continue }
            rows.append((id, m["created"]?.double ?? 0))
        }
        let sorted = stableSorted(rows) { $0.created > $1.created }
        let def = sorted.first { isBaseFamily($0.id) }?.id ?? sorted.first?.id
        let list = sorted.map { r in
            ModelInfo(id: r.id, model: r.id, displayName: r.id, description: "", isDefault: r.id == def, hidden: false,
                      defaultEffort: nil, efforts: looksLikeReasoningModel(r.id) ? ASSUMED_EFFORTS : [])
        }
        return stableSorted(list) { ($0.isDefault ? 0 : 1) < ($1.isDefault ? 0 : 1) }
    }
}
