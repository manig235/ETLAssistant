import SwiftUI
import UIKit

/// 넓은 화면(iPad) 레이아웃 기준. 아이폰 세로 폭(최대 약 440pt)보다 넓어서 아이폰 화면은 바뀌지 않는다.
/// 나누는 기준은 기기 종류가 아니라 폭(size class·실제 폭)이다. iPad의 Slide Over·좁은 Split View는 아이폰과 같은 모양이 된다.
enum YMLayout {
    /// 채팅 본문(말풍선 줄·입력창·배너)의 최대 폭.
    static let chatWidth: CGFloat = 760
    /// 설정·내 일정·알림 기록처럼 글과 카드가 위아래로 놓이는 화면의 최대 폭(좌우 여백 포함).
    static let formWidth: CGFloat = 720
    /// 첫 실행 안내 본문과 아래 버튼의 최대 폭(좌우 여백 포함).
    static let onboardingWidth: CGFloat = 640
    /// regular 폭에서 옆에 붙는 대화 목록 폭.
    static let sidebarWidth: CGFloat = 320
}

extension View {
    /// 넓은 화면에서 가운데 [maxWidth] 폭으로 모은다. 그보다 좁으면(아이폰) 원래와 같다.
    func ymReadableWidth(_ maxWidth: CGFloat = YMLayout.formWidth) -> some View {
        frame(maxWidth: maxWidth).frame(maxWidth: .infinity)
    }
}

/// 공유 시트(UIActivityViewController)를 띄운다. iPad에서는 팝오버라 기준 위치가 없으면 앱이 멈추므로,
/// [anchor](창 좌표, `.global`)가 있으면 그 위치, 없으면 창 가운데 아래에 화살표 없이 붙인다.
@MainActor
enum YMShareSheet {
    static func present(_ items: [Any], anchor: CGRect?) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first,
              let window = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first,
              var top = window.rootViewController else { return }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let popover = vc.popoverPresentationController {
            popover.sourceView = window
            if let anchor, !anchor.isEmpty {
                popover.sourceRect = anchor
            } else {
                popover.sourceRect = CGRect(x: window.bounds.midX, y: window.bounds.maxY - 80, width: 1, height: 1)
                popover.permittedArrowDirections = []
            }
        }
        top.present(vc, animated: true)
    }
}
