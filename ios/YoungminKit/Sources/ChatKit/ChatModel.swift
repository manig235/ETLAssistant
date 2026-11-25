import Foundation

/// 대화 한 줄의 주체(Kotlin `Role`). 저장 형식은 Kotlin 이름(`USER` 등) 그대로.
public enum Role: String, Sendable, Hashable, CaseIterable, Codable {
    case user = "USER", assistant = "ASSISTANT", notice = "NOTICE"
    /// 비서가 받거나 만든 파일·이미지 묶음(왼쪽에 카드로 보인다).
    case files = "FILES"
}

/// 대화에 붙은 파일 하나(Kotlin `Attachment`). 실제 파일은 앱 작업 폴더(`path`)에 있다.
/// 작업 폴더는 일정 기간이 지나면 정리되므로 파일이 없을 수 있다(화면은 "정리됨"으로 보여 준다).
public struct Attachment: Sendable, Hashable {
    public var name: String
    public var mime: String
    public var size: Int64
    /// 앱 작업 폴더 안의 절대 경로.
    public var path: String?
    /// 바깥 사본(안드로이드 MediaStore content:// 에 해당). iOS에서는 파일 앱 사본 URL 등.
    public var uri: String?
    /// 바깥 사본 위치 표시.
    public var shownPath: String?
    /// 비서가 PDF를 읽으려고 만든 쪽 이미지인가(작은 미리보기로만 보인다).
    public var pagePreview: Bool
    /// 전달용 결과물이 아니라 비서가 내용을 확인하려고 받은 참고 자료.
    public var referenceOnly: Bool

    public init(_ name: String, _ mime: String, _ size: Int64, _ path: String?, _ uri: String? = nil, _ shownPath: String? = nil,
                pagePreview: Bool = false, referenceOnly: Bool? = nil) {
        self.name = name
        self.mime = mime
        self.size = size
        self.path = path
        self.uri = uri
        self.shownPath = shownPath
        self.pagePreview = pagePreview
        self.referenceOnly = referenceOnly ?? pagePreview
    }

    public var isImage: Bool { mime.hasPrefix("image/") }

    /// 저장 형식(키 이름은 원본과 같다).
    public func toJson() -> [String: Any] {
        [
            "name": name, "mime": mime, "size": size,
            "path": path ?? NSNull(), "uri": uri ?? NSNull(),
            "shownPath": shownPath ?? NSNull(), "pagePreview": pagePreview,
            "referenceOnly": referenceOnly,
        ]
    }

    public static func fromJson(_ o: [String: Any]) -> Attachment? {
        let name = OrgJSON.optString(o, "name")
        if name.isEmpty { return nil }
        func s(_ k: String) -> String? {
            if OrgJSON.isNull(o, k) { return nil }
            let v = OrgJSON.optString(o, k)
            return v.isEmpty ? nil : v
        }
        let pagePreview = OrgJSON.optBoolean(o, "pagePreview", false)
        return Attachment(
            name, OrgJSON.optString(o, "mime", "application/octet-stream"), OrgJSON.optLong(o, "size"),
            s("path"), s("uri"), s("shownPath"), pagePreview: pagePreview,
            referenceOnly: OrgJSON.optBoolean(o, "referenceOnly", pagePreview || (s("uri") == nil && s("shownPath") == nil))
        )
    }
}

/// 화면에 보이는 대화 한 줄(Kotlin `Message`). 도구 호출은 대화에 남기지 않는다(진행 상태는 `ActivityTracker`가 한 줄로 알려 준다).
public final class Message: Identifiable {
    public let id: Int64
    public let role: Role
    public var text: String
    public var status: String?
    public var error: String?
    /// 서버 쪽 항목 ID(스트리밍 중인 어시스턴트 메시지를 찾는 데 쓴다).
    public let itemId: String?
    /// 사용자가 첨부한 파일(USER) 또는 비서가 받은 파일(FILES).
    public var attachments: [Attachment]

    public init(_ id: Int64, _ role: Role, _ text: String, status: String? = nil, error: String? = nil,
                itemId: String? = nil, attachments: [Attachment] = []) {
        self.id = id
        self.role = role
        self.text = text
        self.status = status
        self.error = error
        self.itemId = itemId
        self.attachments = attachments
    }
}

/// 대화 상태(Kotlin `ChatModel`). **메인 스레드에서만** 만지고, 바뀌면 `listener`로 알린다.
public final class ChatModel {

    public protocol Listener: AnyObject {
        func onAdded(_ index: Int)
        func onChanged(_ index: Int)
        func onReset()
    }

    /// 지금 대화의 저장 파일. `switchTo`로 다른 대화로 바꾼다.
    public private(set) var file: URL?

    public private(set) var messages: [Message] = []
    public weak var listener: Listener?
    private var nextId: Int64 = 1

    public init(_ file: URL?) { self.file = file }

    // MARK: 변경

    @discardableResult
    public func addUser(_ text: String, _ attachments: [Attachment] = []) -> Message {
        add(Message(take(), .user, text, attachments: attachments))
    }

    /// 비서가 받은 파일을 대화에 붙인다. 바로 앞 줄이 파일 묶음이면 거기에 더한다. 같은 경로의 파일은 다시 붙이지 않는다.
    public func addFiles(_ files: [Attachment]) {
        if files.isEmpty { return }
        if let last = messages.last, last.role == .files {
            let known = Set(last.attachments.compactMap(\.path))
            let fresh = files.filter { $0.path == nil || !known.contains($0.path!) }
            if fresh.isEmpty { return }
            last.attachments += fresh
            listener?.onChanged(messages.count - 1)
        } else {
            add(Message(take(), .files, "", attachments: files))
        }
        save()
    }

    @discardableResult
    public func addNotice(_ text: String) -> Message { add(Message(take(), .notice, text)) }

    /// 스트리밍 중인 어시스턴트 메시지에 글을 덧붙인다. 없으면 만든다.
    public func appendAssistant(_ itemId: String, _ delta: String) {
        let i = indexOfItem(itemId, .assistant)
        if i < 0 {
            add(Message(take(), .assistant, delta, status: "streaming", itemId: itemId))
        } else {
            messages[i].text += delta
            listener?.onChanged(i)
        }
    }

    /// 어시스턴트 메시지를 최종 본문으로 확정한다.
    public func finishAssistant(_ itemId: String, _ fullText: String) {
        let i = indexOfItem(itemId, .assistant)
        if i < 0 {
            if !fullText.isEmpty { add(Message(take(), .assistant, fullText, status: "done", itemId: itemId)) }
        } else {
            if !fullText.isEmpty { messages[i].text = fullText }
            messages[i].status = "done"
            listener?.onChanged(i)
        }
        save()
    }

    /// 턴이 끝났을 때: 아직 스트리밍 상태로 남은 메시지를 닫는다(중단된 경우 등).
    public func closeStreaming() {
        var changed = false
        for (i, m) in messages.enumerated() where m.role == .assistant && m.status == "streaming" {
            m.status = "done"
            listener?.onChanged(i)
            changed = true
        }
        if changed { save() }
    }

    /// 다른 대화로 바꾼다: 메시지를 비우고 `newFile`에서 읽는다(파일이 없으면 빈 대화).
    public func switchTo(_ newFile: URL?) {
        file = newFile
        messages.removeAll()
        load()
        listener?.onReset()
    }

    public func clear() {
        messages.removeAll()
        listener?.onReset()
        save()
    }

    private func take() -> Int64 {
        defer { nextId += 1 }
        return nextId
    }

    @discardableResult
    private func add(_ m: Message) -> Message {
        messages.append(m)
        listener?.onAdded(messages.count - 1)
        if m.role == .user { save() }
        return m
    }

    private func indexOfItem(_ itemId: String, _ role: Role) -> Int {
        messages.lastIndex { $0.itemId == itemId && $0.role == role } ?? -1
    }

    // MARK: 저장

    /// 사용자와 어시스턴트의 말, 파일 묶음만 저장한다(안내문은 저장하지 않는다). 최근 `MAX_SAVED`개.
    public func save() {
        guard let f = file else { return }
        let arr: [[String: Any]] = messages.filter { Self.SAVED_ROLES.contains($0.role) }
            .suffix(Self.MAX_SAVED)
            .map { m in
                var o: [String: Any] = ["role": m.role.rawValue, "text": m.text]
                if !m.attachments.isEmpty { o["attachments"] = m.attachments.map { $0.toJson() } }
                return o
            }
        guard let data = OrgJSON.write(arr) else { return }
        try? FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: f, options: .atomic)
    }

    public func load() {
        guard let f = file, let data = try? Data(contentsOf: f), let arr = OrgJSON.parseArray(data) else { return }
        messages.removeAll()
        for case let o as [String: Any] in arr {
            guard let role = Role(rawValue: OrgJSON.optString(o, "role")), Self.SAVED_ROLES.contains(role) else { continue }
            let atts = (o["attachments"] as? [Any])?.compactMap { ($0 as? [String: Any]).flatMap(Attachment.fromJson) } ?? []
            if role == .files && atts.isEmpty { continue }
            messages.append(Message(take(), role, OrgJSON.optString(o, "text"), status: "done", attachments: atts))
        }
        listener?.onReset()
    }

    public static let MAX_SAVED = 200
    private static let SAVED_ROLES: Set<Role> = [.user, .assistant, .files]
}
