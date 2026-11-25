import SwiftUI

/// Android `Toast`(화면 아래 검은 알약)를 대신하는 공용 알림. `ToastCenter.shared.show("복사했어요")`.
@MainActor
@Observable
final class ToastCenter {
    static let shared = ToastCenter()

    private(set) var message: String?
    private var serial = 0

    /// `long`이면 3.5초(Android LENGTH_LONG), 아니면 2초.
    func show(_ text: String, long: Bool = false) {
        serial += 1
        let mine = serial
        withAnimation(.easeOut(duration: 0.15)) { message = text }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64((long ? 3.5 : 2.0) * 1_000_000_000))
            if mine == serial { withAnimation(.easeIn(duration: 0.25)) { message = nil } }
        }
    }
}

/// 하단 safe area 위 24pt, 캡슐, surfaceNeutralInverse 배경 + 반전 글씨 body2Medium.
struct ToastOverlay: ViewModifier {
    @State private var center = ToastCenter.shared

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let text = center.message {
                Text(text)
                    .font(YMFont.body2Medium)
                    .foregroundStyle(YMColor.foregroundInverse)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(Capsule().fill(YMColor.surfaceInverse.opacity(0.92)))
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                    .transition(.opacity)
                    .allowsHitTesting(false)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
    }
}

extension View {
    func toastOverlay() -> some View { modifier(ToastOverlay()) }
}
