import SwiftUI
import UIKit

/// 화면 생성 여부와 관계없이 백그라운드 시작에서도 등록한다.
final class YoungminAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        AppWiring.wipeKeychainAfterReinstall()
        BackgroundRefresh.register { await NotificationSync.check() }
        return true
    }
}

/// 영민이 iOS 앱 진입점. 안드로이드의 런처 액티비티(ChatActivity)처럼 채팅 화면이 시작 화면이다.
/// 비서 도구·쓰기 승인은 `AppState(toolbox:approvals:)`로 끼운다(AssistantKit 연결 지점).
@main
struct YoungminApp: App {
    @UIApplicationDelegateAdaptor(YoungminAppDelegate.self) private var appDelegate
    @State private var appearance = AppAppearanceStore.shared
    @State private var app = AppWiring.makeAppState()

    init() {
        LocalNotifications.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
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
                .preferredColorScheme(appearance.mode.colorScheme)
                .tint(YMColor.brandPrimary)
                .task { AppWiring.start(app: app) }
                // 위젯·공유 확장의 youngmin://schedule · timetable · item/{key} · share/{id} (다른 주소는 그냥 둔다).
                .onOpenURL { url in HomeWidgetBridge.handle(url, app: app) }
        }
    }
}
