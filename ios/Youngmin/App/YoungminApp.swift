import ETLKit
import SwiftUI
import UIKit
import UserNotifications

/// 화면 생성 여부와 관계없이 백그라운드 시작에서도 등록한다.
final class YoungminAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        AppWiring.wipeKeychainAfterReinstall()
        BackgroundRefresh.register {
            guard StudyDataProfile.hasSelection else { return true }
            return await NotificationSync.check()
        }
        return true
    }
}

/// 영민이 iOS 앱 진입점. 안드로이드의 런처 액티비티(ChatActivity)처럼 채팅 화면이 시작 화면이다.
/// 비서 도구·쓰기 승인은 `AppState(toolbox:approvals:)`로 끼운다(AssistantKit 연결 지점).
@main
struct YoungminApp: App {
    @UIApplicationDelegateAdaptor(YoungminAppDelegate.self) private var appDelegate
    @State private var appearance = AppAppearanceStore.shared
    @State private var app: AppState?

    init() {
        if StudyDataProfile.prepareLaunch() {
            UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        }
        _app = State(initialValue: StudyDataProfile.hasSelection ? AppWiring.makeAppState() : nil)
        LocalNotifications.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if let app {
                    AppSessionView(app: app)
                } else {
                    StudyDataChoiceView { demo in
                        if demo {
                            UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
                            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
                        }
                        StudyDataProfile.selectInitial(demo: demo)
                        app = AppWiring.makeAppState()
                    }
                }
            }
            .preferredColorScheme(appearance.mode.colorScheme)
            .tint(YMColor.brandPrimary)
        }
    }
}

private struct AppSessionView: View {
    let app: AppState
    var body: some View {
        Group {
            #if DEBUG && YOUNGMIN_NATIVE_CODEX
            if ProcessInfo.processInfo.arguments.contains("--native-codex-smoke") {
                NativeCodexSmokeView(client: app.auth.codex)
            } else { RootView() }
            #else
            RootView()
            #endif
        }
        .environment(app)
        .safeAreaInset(edge: .top, spacing: 0) {
            if StudyDataProfile.isDemo {
                Text("가상 eTL · 채팅은 실제 ChatGPT 계정을 사용해요")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.label)
                    .frame(maxWidth: .infinity).padding(.vertical, 6)
                    .background(YMColor.bannerBackground)
                    .accessibilityIdentifier("fake-etl-banner")
            }
        }
        .task { AppWiring.start(app: app) }
        .onOpenURL { url in HomeWidgetBridge.handle(url, app: app) }
    }
}

private struct StudyDataChoiceView: View {
    let onChoose: (Bool) -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image("RobotLogo").resizable().scaledToFit().frame(width: 96, height: 96)
                Text("어떤 데이터로 시작할까요?").font(YMFont.title2Bold)
                Text("가상 eTL로 학교 계정 없이 과제 조회·파일 읽기·내 일정 등록을 테스트할 수 있어요. 실제 데이터와 별도로 저장해요.")
                    .font(YMFont.body1Regular)
                YMPrimaryButton("가상 eTL로 테스트하기") { onChoose(true) }
                    .accessibilityIdentifier("choose-fake-etl")
                YMOutlineButton("실제 eTL 사용하기") { onChoose(false) }
                    .accessibilityIdentifier("choose-real-etl")
                Text("채팅은 본인의 ChatGPT 로그인 또는 OpenAI API 키가 필요해요. 가상 자료를 조회해도 질문과 조회한 내용은 OpenAI로 전달돼요. 캘린더 연동은 실제 eTL 모드에서 사용할 수 있어요.")
                    .font(YMFont.caption1Regular).foregroundStyle(YMColor.labelSecondary)
                Text("설정에서 모드를 바꾸거나 가상 데이터를 초기화할 수 있어요.")
                    .font(YMFont.caption1Regular).foregroundStyle(YMColor.labelSecondary)
            }.padding(24).frame(maxWidth: 560)
        }.frame(maxWidth: .infinity).background(YMColor.background.ignoresSafeArea())
    }
}
