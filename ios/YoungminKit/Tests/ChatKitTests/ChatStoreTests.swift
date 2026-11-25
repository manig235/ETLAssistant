import Foundation
import Testing
@testable import ChatKit

// Kotlin ChatStoreTest
@Suite struct ChatStoreTests {
    private let seoul = TimeZone(identifier: "Asia/Seoul")!

    private func at(_ iso: String) -> Int64 {
        Int64(ISO8601DateFormatter().date(from: iso)!.timeIntervalSince1970 * 1000)
    }

    // ---- 제목 ----

    @Test func titleFromText() {
        #expect(ChatTitles.from("  이번 주\n마감   알려줘 ", []) == "이번 주 마감 알려줘")
        let long = String(repeating: "가", count: 60)
        let t = ChatTitles.from(long, [])
        #expect(t.length16 == ChatTitles.MAX_LEN)
        #expect(t.hasSuffix("…"))
    }

    @Test func titleCutDoesNotSplitSurrogatePairs() {
        let s = String(repeating: "a", count: 38) + "😀" + "bbbb"
        let t = ChatTitles.cut(s)
        #expect(t == String(repeating: "a", count: 38) + "…")
    }

    @Test func titleFromAttachmentsOnly() {
        let img = Attachment("p.jpg", "image/jpeg", 1, "/p")
        let pdf = Attachment("보고서.pdf", "application/pdf", 1, "/r")
        #expect(ChatTitles.from("", [img, img]) == "사진 2장")
        #expect(ChatTitles.from(" ", [pdf]) == "파일: 보고서.pdf")
        #expect(ChatTitles.from("", [pdf, pdf]) == "파일 2개")
        #expect(ChatTitles.from("", [img, pdf]) == "사진 1장, 파일 1개")
        #expect(ChatTitles.from("", []) == ChatTitles.DEFAULT)
    }

    // ---- 날짜 묶음 ----

    @Test func dateGroups() {
        // 2026-11-21(토) 00:30 KST
        let now = at("2026-11-20T15:30:00Z")
        #expect(ChatTitles.groupOf(at("2026-11-20T15:10:00Z"), now: now, zone: seoul) == .today)
        #expect(ChatTitles.groupOf(at("2026-11-20T14:50:00Z"), now: now, zone: seoul) == .yesterday)
        #expect(ChatTitles.groupOf(at("2026-11-15T03:00:00Z"), now: now, zone: seoul) == .week)
        #expect(ChatTitles.groupOf(at("2026-11-14T03:00:00Z"), now: now, zone: seoul) == .older)
        let list = [
            ChatMeta("a", "a", 0, at("2026-11-10T00:00:00Z")),
            ChatMeta("b", "b", 0, at("2026-11-20T15:20:00Z")),
            ChatMeta("c", "c", 0, at("2026-11-20T15:25:00Z")),
        ]
        let g = ChatTitles.grouped(list, now: now, zone: seoul)
        #expect(g.map(\.0) == [.today, .older])
        #expect(g[0].1.map(\.id) == ["c", "b"])
        #expect(ChatTitles.Group.week.label == "지난 7일")
    }

    // ---- 색인 ----

    @Test func indexLifecycle() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let s = ChatStore(dir)
        s.load()
        let id = s.newId()
        #expect(s.get(id) == nil) // 빈 대화는 목록에 없다
        s.noteUserMessage(id, "첫 질문", [], now: 1000)
        s.noteUserMessage(id, "두 번째 질문", [], now: 2000)
        #expect(s.get(id)!.title == "첫 질문")
        #expect(s.get(id)!.updatedAt == 2000)
        s.setThread(id, "thr-1")
        s.rename(id, "  내   대화 ")
        let s2 = ChatStore(dir)
        s2.load()
        let m = s2.get(id)!
        #expect(m.title == "내 대화")
        #expect(m.renamed)
        #expect(m.threadId == "thr-1")
        try "[]".write(to: s2.fileFor(id), atomically: true, encoding: .utf8)
        #expect(s2.delete(id)!.threadId == "thr-1")
        #expect(!FileManager.default.fileExists(atPath: s2.fileFor(id).path))
        let s3 = ChatStore(dir)
        s3.load()
        #expect(s3.list().isEmpty)
    }

    @Test func unsafeIdsInIndexAreIgnored() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        try #"[{"id":"../../x","title":"t","createdAt":1,"updatedAt":1},{"id":"ok-1","title":"t","createdAt":1,"updatedAt":1}]"#
            .write(to: dir.appendingPathComponent(ChatStore.INDEX), atomically: true, encoding: .utf8)
        let s = ChatStore(dir)
        s.load()
        #expect(s.list().map(\.id) == ["ok-1"])
    }

    @Test func deleteRemovesOnlyUnsharedAttachments() throws {
        let root = tempDir("work")
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: dir) }
        let own = root.appendingPathComponent("own.pdf")
        let shared = root.appendingPathComponent("shared.pdf")
        let outside = tempFile("outside")
        defer { try? FileManager.default.removeItem(at: outside) }
        for f in [own, shared, outside] { try Data("x".utf8).write(to: f) }
        let s = ChatStore(dir, attachmentRoot: root)
        s.load()
        let a = s.newId(), b = s.newId()
        s.noteUserMessage(a, "a", [], now: 1)
        s.noteUserMessage(b, "b", [], now: 2)
        let ma = ChatModel(s.fileFor(a))
        ma.addUser("a", [Attachment("own.pdf", "application/pdf", 1, own.path), Attachment("shared.pdf", "application/pdf", 1, shared.path),
                         Attachment("o.pdf", "application/pdf", 1, outside.path)])
        ChatModel(s.fileFor(b)).addUser("b", [Attachment("shared.pdf", "application/pdf", 1, shared.path)])
        s.delete(a)
        #expect(!FileManager.default.fileExists(atPath: own.path))
        #expect(FileManager.default.fileExists(atPath: shared.path)) // 다른 대화가 참조
        #expect(FileManager.default.fileExists(atPath: outside.path)) // 작업 폴더 밖
    }

    // ---- 예전 형식 옮기기 ----

    @Test func migratesLegacySingleChat() {
        let root = tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let legacy = root.appendingPathComponent("chat.json")
        let old = ChatModel(legacy)
        old.addUser("예전 질문")
        old.finishAssistant("a", "예전 답변")
        let s = ChatStore(root.appendingPathComponent("chats"))
        s.load()
        let id = s.migrateLegacy(legacy, "old-thread", now: 5000)
        #expect(id != nil)
        #expect(!FileManager.default.fileExists(atPath: legacy.path))
        let m = s.get(id!)!
        #expect(m.title == "예전 질문")
        #expect(m.threadId == "old-thread")
        let moved = ChatModel(s.fileFor(id!))
        moved.load()
        #expect(moved.messages.map(\.text) == ["예전 질문", "예전 답변"])
        #expect(s.migrateLegacy(legacy, nil, now: 6000) == nil)
    }

    @Test func emptyLegacyIsDropped() throws {
        let root = tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let legacy = root.appendingPathComponent("chat.json")
        try "[]".write(to: legacy, atomically: true, encoding: .utf8)
        let s = ChatStore(root.appendingPathComponent("chats"))
        s.load()
        #expect(s.migrateLegacy(legacy, "t", now: 1) == nil)
        #expect(s.list().isEmpty)
    }

    @Test func switchToLoadsOtherConversation() {
        let root = tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let a = root.appendingPathComponent("a.json"), b = root.appendingPathComponent("b.json")
        ChatModel(a).addUser("A의 질문")
        let m = ChatModel(b)
        m.switchTo(a)
        #expect(m.messages.map(\.text) == ["A의 질문"])
        m.switchTo(root.appendingPathComponent("none.json"))
        #expect(m.messages.isEmpty)
    }
}
