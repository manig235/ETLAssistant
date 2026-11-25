import Foundation
import Testing
@testable import ETLKit

// Kotlin DownloadCleanupLogicTest·WorkFilesTest(경로는 iOS `Documents` 기준 `etl/`).
@Suite struct FilesCleanupTests {
    let now: Int64 = 1_800_000_000_000
    let etl = "etl/1주차.pdf"
    let day = DownloadCleanupLogic.DAY_MS

    @Test func deletesOnlyAfterChosenPeriod() {
        let p = CleanupPeriod.SEVEN
        #expect(!DownloadCleanupLogic.shouldDelete(etl, addedAtMs: now - 7 * day + 1, nowMs: now, period: p, generated: false))
        #expect(DownloadCleanupLogic.shouldDelete(etl, addedAtMs: now - 7 * day, nowMs: now, period: p, generated: false)) // 정확히 7일
        #expect(DownloadCleanupLogic.shouldDelete(etl, addedAtMs: now - 30 * day, nowMs: now, period: p, generated: false))
        #expect(DownloadCleanupLogic.shouldDelete(etl, addedAtMs: now - 2 * day, nowMs: now, period: .TWO, generated: false))
        #expect(!DownloadCleanupLogic.shouldDelete(etl, addedAtMs: now - 29 * day, nowMs: now, period: .THIRTY, generated: false))
    }

    @Test func nothingIsDeletedBeforeChoosingOrWithNever() {
        #expect(!DownloadCleanupLogic.shouldDelete(etl, addedAtMs: now - 400 * day, nowMs: now, period: nil, generated: false))
        #expect(!DownloadCleanupLogic.shouldDelete(etl, addedAtMs: now - 400 * day, nowMs: now, period: .NEVER, generated: false))
    }

    @Test func excludesGeneratedOtherFoldersUnownedAndBadTimes() {
        let old = now - 100 * day
        let p = CleanupPeriod.TWO
        func del(_ path: String?, _ added: Int64? = nil, generated: Bool = false, owned: Bool = true) -> Bool {
            DownloadCleanupLogic.shouldDelete(path, addedAtMs: added ?? old, nowMs: now, period: p, generated: generated, owned: owned)
        }
        #expect(!del("etl/영민이가 만든 파일/요약.pdf"))
        #expect(!del(etl, generated: true)) // 기록된 AI 파일
        #expect(!del("a.pdf"))
        #expect(!del("etlx/a.pdf"))
        #expect(!del("Inbox/etl/a.pdf"))
        #expect(!del(nil))
        #expect(!del(etl, owned: false))
        #expect(!del(etl, now + day)) // 미래 시각
        #expect(!DownloadCleanupLogic.shouldDelete(etl, addedAtMs: nil, nowMs: now, period: p, generated: false))
        #expect(!del(etl, 0))
        #expect(del("ETL/sub/a.pdf"))
    }

    @Test func runsOncePerDayAndAsksAtMostTwice() {
        #expect(DownloadCleanupLogic.due(lastRunMs: 0, nowMs: now))
        #expect(!DownloadCleanupLogic.due(lastRunMs: now - 3_600_000, nowMs: now))
        #expect(DownloadCleanupLogic.due(lastRunMs: now - 20 * 3_600_000, nowMs: now))
        #expect(DownloadCleanupLogic.due(lastRunMs: now + 3_600_000, nowMs: now)) // 시계가 뒤로 감
        #expect(DownloadCleanupLogic.shouldAsk(nil, asks: 0))
        #expect(DownloadCleanupLogic.shouldAsk(nil, asks: 1))
        #expect(!DownloadCleanupLogic.shouldAsk(nil, asks: 2))
        #expect(!DownloadCleanupLogic.shouldAsk(.NEVER, asks: 0))
    }

    @Test func periodKeysAndLabels() {
        for p in CleanupPeriod.allCases { #expect(CleanupPeriod.of(p.key) == p) }
        #expect(CleanupPeriod.of(nil) == nil)
        #expect(CleanupPeriod.of("5") == nil)
        #expect(CleanupPeriod.DEFAULT == .TWO)
        #expect(CleanupPeriod.allCases.map(\.label) == ["2일", "7일", "30일", "정리 안 함"])
        #expect(DownloadCleanupLogic.lastRunLabel(lastRunMs: kst("2026-11-24T09:00:00").epochMilli, count: 3) == "최근 정리: 11/24, 3개")
        #expect(DownloadCleanupLogic.lastRunLabel(lastRunMs: 0, count: 0) == nil)
    }

    @Test func documentsRelativeSurvivesContainerMove() {
        #expect(DownloadCleanupLogic.documentsRelative("file:///var/mobile/Containers/Data/Application/AAA/Documents/etl/1%EC%A3%BC.pdf") == "etl/1주.pdf")
        #expect(DownloadCleanupLogic.documentsRelative("/private/var/x/Documents/etl/a.pdf") == "etl/a.pdf")
        #expect(DownloadCleanupLogic.documentsRelative("content://x") == nil)
        #expect(DownloadCleanupLogic.documentsRelative(nil) == nil)
    }

    private func tempDir() throws -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("cleanup-\(UUID().uuidString)/Documents", isDirectory: true)
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    @Test func runDeletesOnlyTopLevelFilesAfterChoosingAndRemembersThem() throws {
        let docs = try tempDir()
        defer { try? FileManager.default.removeItem(at: docs.deletingLastPathComponent()) }
        let etlDir = docs.appendingPathComponent("etl", isDirectory: true)
        let made = etlDir.appendingPathComponent("영민이가 만든 파일", isDirectory: true)
        try FileManager.default.createDirectory(at: made, withIntermediateDirectories: true)
        let file = etlDir.appendingPathComponent("1주차.pdf")
        let ai = made.appendingPathComponent("요약.pdf")
        let recorded = etlDir.appendingPathComponent("기록.pdf")
        for f in [file, ai, recorded] { try Data("x".utf8).write(to: f) }
        let prefs = InMemoryStore()
        let cleanup = DownloadCleanup(prefs: prefs, documents: docs, generated: { $0 == "etl/기록.pdf" })
        let later = Date().addingTimeInterval(3 * 86_400)

        // 고르기 전에는 지우지 않고 기록도 없다
        #expect(cleanup.shouldAsk)
        #expect(cleanup.run(force: true, now: later) == 0)
        #expect(FileManager.default.fileExists(atPath: file.path))
        #expect(cleanup.lastRunLabel == nil)
        #expect(cleanup.statusLabel == DownloadCleanupLogic.NOT_CHOSEN)
        cleanup.markAsked()
        cleanup.markAsked()
        #expect(!cleanup.shouldAsk)

        // 정리 안 함
        cleanup.setPeriod(.NEVER, now: later)
        #expect(FileManager.default.fileExists(atPath: file.path))

        // 2일: 사흘 뒤에 돌면 맨 위의 받은 파일만 지운다
        #expect(cleanup.setPeriod(.TWO, now: later) == 1)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(FileManager.default.fileExists(atPath: ai.path))
        #expect(FileManager.default.fileExists(atPath: recorded.path))
        #expect(cleanup.wasCleaned(file.absoluteString))
        #expect(cleanup.wasCleaned("file:///other/container/Documents/etl/1%EC%A3%BC%EC%B0%A8.pdf"))
        #expect(!cleanup.wasCleaned(ai.absoluteString))
        #expect(cleanup.lastRunLabel?.hasSuffix(", 1개") == true)
        // 하루 한 번
        try Data("y".utf8).write(to: file)
        #expect(cleanup.runIfDue(now: later) == 0)
        #expect(FileManager.default.fileExists(atPath: file.path))
    }

    @Test func pruneDeletesOldWorkFilesAndDoesNotFollowLinks() throws {
        let base = try tempDir()
        defer { try? FileManager.default.removeItem(at: base) }
        let root = base.appendingPathComponent("etl-files", isDirectory: true)
        let sub = root.appendingPathComponent("uploads", isDirectory: true)
        let outside = base.appendingPathComponent("outside", isDirectory: true)
        try FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        let old = sub.appendingPathComponent("old.pdf")
        let fresh = root.appendingPathComponent("fresh.pdf")
        let secret = outside.appendingPathComponent("auth.json")
        for f in [old, fresh, secret] { try Data("x".utf8).write(to: f) }
        let past = Date(timeIntervalSince1970: 1)
        try FileManager.default.setAttributes([.modificationDate: past], ofItemAtPath: old.path)
        try FileManager.default.setAttributes([.modificationDate: past], ofItemAtPath: secret.path)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("link"), withDestinationURL: outside)
        #expect(WorkFiles.prune(root, nowMs: Date().epochMilli) == 1)
        #expect(!FileManager.default.fileExists(atPath: old.path))
        #expect(FileManager.default.fileExists(atPath: fresh.path))
        #expect(FileManager.default.fileExists(atPath: secret.path))
        // 정확히 14일
        let f = root.appendingPathComponent("edge")
        try Data("a".utf8).write(to: f)
        try FileManager.default.setAttributes([.modificationDate: Date(epochMilli: 1_000_000)], ofItemAtPath: f.path)
        let limit: Int64 = 1_000_000 + 14 * DownloadCleanupLogic.DAY_MS
        #expect(WorkFiles.prune(root, nowMs: limit - 1) == 0)
        #expect(WorkFiles.prune(root, nowMs: limit) == 1)
        // 하루 한 번
        let prefs = InMemoryStore()
        #expect(WorkFiles.runIfDue(root: root, prefs: prefs, now: Date()) == 0)
        try Data("b".utf8).write(to: f)
        try FileManager.default.setAttributes([.modificationDate: past], ofItemAtPath: f.path)
        #expect(WorkFiles.runIfDue(root: root, prefs: prefs, now: Date()) == 0)
        #expect(FileManager.default.fileExists(atPath: f.path))
    }
}
