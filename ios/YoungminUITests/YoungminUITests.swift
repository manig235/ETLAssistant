import XCTest

/// Exercise the real app with its existing DEBUG sample data, without accounts.
final class YoungminUITests: XCTestCase {
    @MainActor
    func testNativeCodexStartup() throws {
        let app = XCUIApplication()
        app.launchArguments += ["--native-codex-smoke"]
        app.launch()
        let status = app.staticTexts["native-codex-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 20))
        let terminal = NSPredicate(format: "label BEGINSWITH 'ready:' OR label BEGINSWITH 'failed:'")
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: terminal, object: status)], timeout: 90)
        capture("native-codex-startup")
        XCTAssertEqual(result, .completed, "Native startup timed out: \(status.label)")
        XCTAssertTrue(status.label.hasPrefix("ready:"), status.label)
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testSampleDataNavigation() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR"]
        app.launch()

        XCTAssertTrue(app.buttons["시작하기"].waitForExistence(timeout: 20))
        capture("01-welcome")
        let demo = app.buttons["샘플 데이터로 둘러보기"]
        for _ in 0..<8 {
            if demo.exists && demo.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(demo.exists && demo.isHittable, "Sample data entry must be reachable")
        demo.tap()

        XCTAssertTrue(app.buttons["대화 목록"].waitForExistence(timeout: 15))
        capture("02-chat")
        let darkMode = app.buttons["다크 모드로 전환"]
        if darkMode.exists { darkMode.tap() }
        XCTAssertTrue(app.buttons["라이트 모드로 전환"].waitForExistence(timeout: 5))
        capture("03-chat-dark")
        app.buttons["라이트 모드로 전환"].tap()
        XCTAssertTrue(app.buttons["다크 모드로 전환"].waitForExistence(timeout: 5))

        app.buttons["설정"].tap()
        XCTAssertTrue(app.staticTexts["ChatGPT 계정"].waitForExistence(timeout: 10))
        capture("04-settings")
        app.buttons["뒤로"].tap()

        for (title, screenshot) in [("내 일정", "05-schedule"), ("시간표", "06-timetable"), ("알림 기록", "07-notifications")] {
            XCTAssertTrue(app.buttons["대화 목록"].waitForExistence(timeout: 10))
            app.buttons["대화 목록"].tap()
            XCTAssertTrue(app.buttons[title].waitForExistence(timeout: 5))
            app.buttons[title].tap()
            XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 10))
            XCTAssertTrue(app.buttons["뒤로"].waitForExistence(timeout: 5))
            capture(screenshot)
            app.buttons["뒤로"].tap()
        }
        XCTAssertTrue(app.buttons["대화 목록"].waitForExistence(timeout: 10))
    }

    @MainActor
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
