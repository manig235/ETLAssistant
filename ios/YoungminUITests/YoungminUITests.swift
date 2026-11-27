import UIKit
import XCTest

/// Exercise the real app with its existing DEBUG sample data, without accounts.
final class YoungminUITests: XCTestCase {
    @MainActor
    func testNativeCodexStartup() throws {
        let app = XCUIApplication()
        app.launchArguments += ["--native-codex-smoke", "-studyData.mode", "real"]
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
        // iPad 실행에서 앞 시험이 돌려 둔 방향이 남지 않게 한다(아이폰은 세로만 지원).
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR", "-studyData.mode", "real"]
        app.launch()

        XCTAssertTrue(app.buttons["시작하기"].waitForExistence(timeout: 20))
        capture("01-welcome")
        if UIDevice.current.userInterfaceIdiom == .pad {
            rotate(.landscapeLeft)
            XCTAssertTrue(app.buttons["시작하기"].waitForExistence(timeout: 5))
            capture("01-welcome-landscape")
            rotate(.portrait)
        }
        let demo = app.buttons["샘플 데이터로 둘러보기"]
        for _ in 0..<8 {
            if demo.exists && demo.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(demo.exists && demo.isHittable, "Sample data entry must be reachable")
        demo.tap()

        XCTAssertTrue(app.buttons["chat-menu"].waitForExistence(timeout: 15))
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
        // 기타 → 홈 화면에 위젯 추가하기(iOS는 안내 화면).
        let widgetGuide = app.staticTexts["홈 화면에 위젯 추가하기"]
        for _ in 0..<15 {
            if widgetGuide.exists && widgetGuide.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(widgetGuide.exists && widgetGuide.isHittable, "Widget guide row must be reachable")
        widgetGuide.tap()
        XCTAssertTrue(app.staticTexts["위젯 종류"].waitForExistence(timeout: 10))
        capture("04b-widget-guide")
        app.buttons["뒤로"].tap()
        XCTAssertTrue(app.staticTexts["처음 안내 다시 보기"].waitForExistence(timeout: 10))
        app.buttons["뒤로"].tap()

        for (title, screenshot) in [("내 일정", "05-schedule"), ("시간표", "06-timetable"), ("알림 기록", "07-notifications")] {
            XCTAssertTrue(app.buttons["chat-menu"].waitForExistence(timeout: 10))
            openDrawerEntry(app, title)
            XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 10))
            XCTAssertTrue(app.buttons["뒤로"].waitForExistence(timeout: 5))
            capture(screenshot)
            app.buttons["뒤로"].tap()
        }
        XCTAssertTrue(app.buttons["chat-menu"].waitForExistence(timeout: 10))
    }

    /// iPad 전용: 세로·가로, 옆 대화 목록, 다크 모드, 시트를 찍는다. 13인치 기기에서 찍은 것은 App Store 스크린샷 크기다.
    /// 이름 순서로 `testSampleDataNavigation` 뒤에 돌아 샘플 데이터가 이미 켜져 있다(혼자 돌리면 환영 화면에서 켠다).
    @MainActor
    func testWideLayoutOnIPad() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "iPad simulator only")
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR", "-studyData.mode", "real"]
        app.launch()

        let menu = app.buttons["chat-menu"]
        let start = app.buttons["시작하기"]
        for _ in 0..<40 where !(menu.exists || start.exists) {
            Thread.sleep(forTimeInterval: 0.5)
        }
        if !menu.exists && start.exists {
            let demo = app.buttons["샘플 데이터로 둘러보기"]
            for _ in 0..<8 {
                if demo.exists && demo.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(demo.exists && demo.isHittable, "Sample data entry must be reachable")
            demo.tap()
        }

        // regular 폭: 대화 목록이 옆에 붙어 있고, 메뉴 버튼은 숨기기/보이기다.
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["내 일정"].firstMatch.waitForExistence(timeout: 5), "Sidebar must be visible on a full-screen iPad")
        XCTAssertEqual(menu.label, "대화 목록 숨기기")
        capture("ipad-02-chat-portrait")
        rotate(.landscapeLeft)
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        capture("ipad-03-chat-landscape")
        let darkMode = app.buttons["다크 모드로 전환"]
        if darkMode.exists { darkMode.tap() }
        XCTAssertTrue(app.buttons["라이트 모드로 전환"].waitForExistence(timeout: 5))
        capture("ipad-04-chat-dark-landscape")
        rotate(.portrait)
        capture("ipad-04b-chat-dark-portrait")
        app.buttons["라이트 모드로 전환"].tap()
        XCTAssertTrue(app.buttons["다크 모드로 전환"].waitForExistence(timeout: 5))
        rotate(.landscapeLeft)

        // 옆 목록 숨기기 → 본문만, 다시 보이기.
        menu.tap()
        XCTAssertTrue(waitForLabel(menu, "대화 목록 보기"))
        XCTAssertFalse(app.buttons["내 일정"].exists)
        capture("ipad-05-chat-sidebar-hidden-landscape")
        menu.tap()
        XCTAssertTrue(waitForLabel(menu, "대화 목록 숨기기"))
        XCTAssertTrue(app.buttons["내 일정"].firstMatch.waitForExistence(timeout: 5))

        // 입력창: 하드웨어/소프트웨어 키보드 어느 쪽이든 입력 칸이 화면 안에 보여야 한다.
        let input = app.descendants(matching: .any).matching(identifier: "chat-input").firstMatch
        if input.waitForExistence(timeout: 5) {
            input.tap()
            input.typeText("이번 주 마감 알려줘")
            XCTAssertTrue(input.isHittable, "Input must stay visible above the keyboard")
            capture("ipad-06-chat-typing-landscape")
            if app.keyboards.firstMatch.exists, app.keyboards.buttons["Hide keyboard"].exists {
                app.keyboards.buttons["Hide keyboard"].tap()
            }
        }

        app.buttons["설정"].tap()
        XCTAssertTrue(app.staticTexts["ChatGPT 계정"].waitForExistence(timeout: 10))
        capture("ipad-07-settings-landscape")
        rotate(.portrait)
        capture("ipad-07b-settings-portrait")
        rotate(.landscapeLeft)

        // 옆 목록에서 고르면 열린 화면이 바뀐다(설정 → 내 일정).
        app.buttons["내 일정"].firstMatch.tap()
        XCTAssertTrue(app.buttons["내 마감·시험 추가"].waitForExistence(timeout: 10))
        capture("ipad-08-schedule-landscape")
        let add = app.buttons["내 마감·시험 추가"]
        if add.exists {
            add.tap()
            let cancel = app.buttons["취소"].firstMatch
            XCTAssertTrue(cancel.waitForExistence(timeout: 5))
            capture("ipad-09-schedule-edit-sheet-landscape")
            cancel.tap()
            XCTAssertTrue(cancel.waitForNonExistence(timeout: 5))
        }
        app.buttons["시간표"].firstMatch.tap()
        XCTAssertTrue(app.buttons["수업 직접 추가"].waitForExistence(timeout: 10))
        capture("ipad-10-timetable-landscape")
        rotate(.portrait)
        capture("ipad-10b-timetable-portrait")
        app.buttons["알림 기록"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["알림 기록"].waitForExistence(timeout: 10))
        capture("ipad-11-notifications-portrait")
        app.buttons["뒤로"].tap()
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
    }

    @MainActor
    func testFakeEtlWithoutSchoolCredentials() throws {
        // iPad 실행에서 앞 시험이 돌려 둔 방향이 남지 않게 한다(아이폰은 세로만 지원).
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR", "-studyData.mode", "fake"]
        app.launch()
        XCTAssertTrue(app.staticTexts["fake-etl-banner"].waitForExistence(timeout: 20))
        // No school-token onboarding or DEBUG sample-answer mode is used.
        XCTAssertTrue(app.buttons["chat-menu"].waitForExistence(timeout: 20))
        openDrawerEntry(app, "내 일정")
        XCTAssertTrue(app.staticTexts["RC 회로 과제"].firstMatch.waitForExistence(timeout: 20))
        XCTAssertGreaterThanOrEqual(app.buttons["뒤로"].frame.minY,
                                    app.staticTexts["fake-etl-banner"].frame.maxY - 1,
                                    "The fake-data banner must not cover the navigation controls")
        capture("fake-etl-schedule")
        app.buttons["뒤로"].tap()
        app.buttons["설정"].tap()
        let profile = app.staticTexts["가상 eTL 테스트 중"]
        for _ in 0..<5 { if profile.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(profile.exists)
        capture("fake-etl-settings")
    }

    /// 서랍 항목을 연다. iPad regular 폭에서는 옆 목록에 이미 보이고, 아이폰은 메뉴로 서랍을 먼저 연다.
    @MainActor
    private func openDrawerEntry(_ app: XCUIApplication, _ title: String) {
        let entry = app.buttons[title].firstMatch
        if !(entry.exists && entry.isHittable) { app.buttons["chat-menu"].tap() }
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        entry.tap()
    }

    @MainActor
    private func rotate(_ orientation: UIDeviceOrientation) {
        XCUIDevice.shared.orientation = orientation
        // 회전 애니메이션과 다시 그리기를 기다린다.
        Thread.sleep(forTimeInterval: 1.5)
    }

    @MainActor
    private func waitForLabel(_ element: XCUIElement, _ label: String) -> Bool {
        let predicate = NSPredicate(format: "label == %@", label)
        return XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: element)], timeout: 5) == .completed
    }

    @MainActor
    private func capture(_ name: String) {
        // 화면 전환·회전 애니메이션이 끝난 뒤 찍는다.
        Thread.sleep(forTimeInterval: 0.8)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
