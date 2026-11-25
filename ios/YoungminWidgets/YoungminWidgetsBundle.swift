import SwiftUI
import WidgetKit

/// 영민이 홈 화면·잠금 화면 위젯(안드로이드 `widget/HomeWidgets.kt`). 앱이 App Group에 쓴 사본만 읽는다.
@main
struct YoungminWidgetsBundle: WidgetBundle {
    var body: some Widget {
        DeadlinesWidget()
        TimetableWidget()
        NextClassWidget()
    }
}
