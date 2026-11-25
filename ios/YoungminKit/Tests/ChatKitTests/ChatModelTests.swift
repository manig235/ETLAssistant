import Foundation
import Testing
@testable import ChatKit

/// Swift Testing에도 `Attachment`가 있어서 ChatKit 쪽을 가리킨다.
typealias Attachment = ChatKit.Attachment

func tempFile(_ prefix: String = "chat") -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString).json")
}

func tempDir(_ prefix: String = "chats") -> URL {
    let u = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
    try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
    return u
}

// Kotlin ChatModelTest
@Suite struct ChatModelTests {

    final class Recorder: ChatModel.Listener {
        var added: [Int] = []
        var changed: [Int] = []
        var resets = 0
        func onAdded(_ index: Int) { added.append(index) }
        func onChanged(_ index: Int) { changed.append(index) }
        func onReset() { resets += 1 }
    }

    @Test func streamingDeltasBuildOneAssistantMessage() {
        let m = ChatModel(nil)
        let r = Recorder()
        m.listener = r
        m.addUser("안녕")
        m.appendAssistant("a1", "이번 ")
        m.appendAssistant("a1", "주 마감")
        #expect(m.messages.count == 2)
        #expect(m.messages[1].text == "이번 주 마감")
        #expect(m.messages[1].status == "streaming")
        #expect(r.added == [0, 1])
        #expect(r.changed == [1])
    }

    @Test func finishReplacesTextWithFinalBody() {
        let m = ChatModel(nil)
        m.appendAssistant("a1", "부분")
        m.finishAssistant("a1", "최종 본문")
        #expect(m.messages[0].text == "최종 본문")
        #expect(m.messages[0].status == "done")
    }

    @Test func finishWithoutDeltasStillCreatesMessageUnlessEmpty() {
        let m = ChatModel(nil)
        m.finishAssistant("a1", "한 번에 온 답")
        m.finishAssistant("a2", "")
        #expect(m.messages.count == 1)
        #expect(m.messages[0].text == "한 번에 온 답")
    }

    @Test func separateItemsStaySeparate() {
        let m = ChatModel(nil)
        m.appendAssistant("a1", "첫째")
        m.appendAssistant("a2", "둘째")
        #expect(m.messages.map(\.text) == ["첫째", "둘째"])
        #expect(m.messages.map(\.role) == [.assistant, .assistant])
    }

    @Test func closeStreamingClosesUnfinishedAssistantMessage() {
        let m = ChatModel(nil)
        m.appendAssistant("a1", "중간에 끊긴")
        m.closeStreaming()
        #expect(m.messages[0].status == "done")
    }

    @Test func saveAndLoadKeepOnlyUserAndAssistant() {
        let f = tempFile()
        defer { try? FileManager.default.removeItem(at: f) }
        let m = ChatModel(f)
        m.addUser("질문")
        m.addNotice("안내")
        m.appendAssistant("a1", "답")
        m.finishAssistant("a1", "답변입니다")
        let loaded = ChatModel(f)
        loaded.load()
        #expect(loaded.messages.map(\.role) == [.user, .assistant])
        #expect(loaded.messages[1].text == "답변입니다")
        #expect(loaded.messages[1].status == "done")
    }

    @Test func savedHistoryIsCapped() {
        let f = tempFile()
        defer { try? FileManager.default.removeItem(at: f) }
        let m = ChatModel(f)
        for i in 0..<(ChatModel.MAX_SAVED + 30) { m.addUser("m\(i)") }
        let loaded = ChatModel(f)
        loaded.load()
        #expect(loaded.messages.count == ChatModel.MAX_SAVED)
        #expect(loaded.messages.first!.text == "m30")
    }

    @Test func clearResetsAndPersistsEmpty() {
        let f = tempFile()
        defer { try? FileManager.default.removeItem(at: f) }
        let m = ChatModel(f)
        let r = Recorder()
        m.listener = r
        m.addUser("x")
        m.clear()
        #expect(m.messages.isEmpty)
        #expect(r.resets == 1)
        let loaded = ChatModel(f)
        loaded.load()
        #expect(loaded.messages.isEmpty)
    }

    @Test func corruptFileIsIgnored() throws {
        let f = tempFile()
        defer { try? FileManager.default.removeItem(at: f) }
        try "{not json".write(to: f, atomically: true, encoding: .utf8)
        let m = ChatModel(f)
        m.load()
        #expect(m.messages.isEmpty)
    }

    // ---- 첨부 ----

    private func att(_ name: String, path: String? = nil, mime: String = "application/pdf") -> Attachment {
        Attachment(name, mime, 10, path ?? "/w/etl-files/\(name)")
    }

    @Test func filesMergeIntoPreviousFilesRowAndSkipDuplicates() {
        let m = ChatModel(nil)
        m.addUser("받아줘")
        m.addFiles([att("a.pdf")])
        m.addFiles([att("b.pdf"), att("a.pdf")])
        #expect(m.messages.map(\.role) == [.user, .files])
        #expect(m.messages[1].attachments.map(\.name) == ["a.pdf", "b.pdf"])
        // 사이에 답변이 끼면 새 묶음
        m.finishAssistant("x", "받았어요")
        m.addFiles([att("c.pdf")])
        #expect(m.messages.last!.role == .files)
        #expect(m.messages.last!.attachments.count == 1)
    }

    @Test func attachmentsSurviveSaveAndLoad() {
        let f = tempFile()
        defer { try? FileManager.default.removeItem(at: f) }
        let m = ChatModel(f)
        m.addUser("", [Attachment("p.jpg", "image/jpeg", 5, "/w/p.jpg")])
        m.addFiles([Attachment("강의.pdf", "application/pdf", 9, "/w/x.pdf", "content://media/1", "Download/etl/강의.pdf")])
        m.addFiles([Attachment("강의 1쪽", "image/png", 3, "/w/r.png", pagePreview: true)])
        let loaded = ChatModel(f)
        loaded.load()
        #expect(loaded.messages.map(\.role) == [.user, .files])
        #expect(loaded.messages[0].attachments.map(\.name) == ["p.jpg"])
        let files = loaded.messages[1].attachments
        #expect(files[0].uri == "content://media/1")
        #expect(files[0].shownPath == "Download/etl/강의.pdf")
        #expect(files[1].pagePreview)
        #expect(files[1].uri == nil)
    }

    @Test func referenceClassificationSurvivesReloadAndKeepsRequestedFilesVisible() {
        let f = tempFile("chat-reference")
        defer { try? FileManager.default.removeItem(at: f) }
        let m = ChatModel(f)
        var reference = att("reference.pdf")
        reference.referenceOnly = true
        m.addFiles([att("requested.pdf"), reference, Attachment("1쪽", "image/png", 3, "/w/page.png", pagePreview: true)])
        let loaded = ChatModel(f)
        loaded.load()
        let files = loaded.messages.first!.attachments
        #expect(loaded.messages.count == 1)
        #expect(!files[0].referenceOnly)
        #expect(files[1].referenceOnly)
        #expect(files[2].referenceOnly)
    }

    @Test func legacyHistoryCollapsesWorkspaceFilesAndPreviewsButKeepsPhoneCopiesVisible() throws {
        let f = tempFile("chat-reference-old")
        defer { try? FileManager.default.removeItem(at: f) }
        try """
        [{"role":"FILES","attachments":[
            {"name":"requested.pdf","uri":"content://media/1","shownPath":"Download/etl/requested.pdf"},
            {"name":"reference.pdf","path":"/w/reference.pdf"},
            {"name":"1쪽","mime":"image/png","pagePreview":true}
        ]}]
        """.write(to: f, atomically: true, encoding: .utf8)
        let loaded = ChatModel(f)
        loaded.load()
        let files = loaded.messages.first!.attachments
        #expect(!files[0].referenceOnly)
        #expect(files[1].referenceOnly)
        #expect(files[2].referenceOnly)
    }
}
