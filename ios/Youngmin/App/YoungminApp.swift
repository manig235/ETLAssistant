import SwiftUI

/// 영민이 iOS 앱 진입점. 안드로이드의 런처 액티비티(ChatActivity)처럼 채팅 화면이 시작 화면이다.
/// 비서 도구·쓰기 승인은 `AppState(toolbox:approvals:)`로 끼운다(AssistantKit 연결 지점).
@main
struct YoungminApp: App {
    @State private var appearance = AppAppearanceStore.shared
    @State private var app = AppWiring.makeAppState()

    init() {
        LocalNotifications.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .preferredColorScheme(appearance.mode.colorScheme)
                .tint(YMColor.brandPrimary)
                .task { AppWiring.start(app: app) }
                // 위젯·공유 확장의 youngmin://schedule · timetable · item/{key} · share/{id} (다른 주소는 그냥 둔다).
                .onOpenURL { url in HomeWidgetBridge.handle(url, app: app) }
        }
    }
}
