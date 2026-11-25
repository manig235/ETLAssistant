import SwiftUI
import UIKit

/// 안드로이드 `res/values/colors.xml`·`values-night/colors.xml`과 Montage(Wanted 디자인 시스템) 기본 색을 옮긴 색 토큰.
/// 라이트/다크 값은 안드로이드 리소스와 같다.
enum YMColor {
    // 브랜드 (하늘색 B3 로봇)
    static let brandNavy = Color(hex: 0x181B35)
    static let brandRobotBlue = Color(hex: 0x85BDE2)
    static let brandIvory = Color(hex: 0xFFF4DF)

    /// 강조색(버튼·링크). Montage primary 계열과 사용자 말풍선 색을 맞췄다.
    static let brand = dynamic(light: 0x2F6FED, dark: 0x3D7BFF)

    // 채팅 (사용자 말풍선은 ChatComponents.kt `UserBubbleBlue` = #005EEB, 라이트·다크 같음. 선택 띠는 남색 #181B35)
    static let bubbleUser = Color(hex: 0x005EEB)
    static let userSelection = Color(hex: 0x181B35)
    static let bubbleUserText = Color.white
    static let bubbleAssistant = dynamic(light: 0xEEF0F3, dark: 0x2B2F36)
    static let bubbleAssistantText = dynamic(light: 0x16181D, dark: 0xECEEF2)
    static let chipBackground = dynamic(light: 0xE4ECFB, dark: 0x26324D)
    static let chipText = dynamic(light: 0x2B3F6B, dark: 0xC7D6F5)
    static let chipErrorBackground = dynamic(light: 0xFBE4E4, dark: 0x4A2626)
    static let chipErrorText = dynamic(light: 0x7A1F1F, dark: 0xF5C7C7)
    static let noticeText = dynamic(light: 0x6B7280, dark: 0x9CA3AF)
    static let bannerBackground = dynamic(light: 0xFFF3CD, dark: 0x4A3B10)
    static let bannerText = dynamic(light: 0x5C4400, dark: 0xFFE9A8)
    static let codeBackground = Color(.sRGB, red: 0x88 / 255, green: 0x88 / 255, blue: 0x88 / 255, opacity: 0x22 / 255)
    static let panelBackground = Color(.sRGB, red: 0x88 / 255, green: 0x88 / 255, blue: 0x88 / 255, opacity: 0x11 / 255)

    // Montage 중립색(라이트/다크)
    static let background = dynamic(light: 0xFFFFFF, dark: 0x1B1C1E)
    static let backgroundAlternative = dynamic(light: 0xF7F7F8, dark: 0x0F0F10)
    static let label = dynamic(light: 0x171719, dark: 0xF7F7F8)
    static let labelSecondary = dynamic(light: 0x37383C, dark: 0xC2C4C8, lightAlpha: 0.61, darkAlpha: 0.88)
    static let labelTertiary = dynamic(light: 0x37383C, dark: 0xC2C4C8, lightAlpha: 0.28, darkAlpha: 0.43)
    static let line = dynamic(light: 0x70737C, dark: 0x70737C, lightAlpha: 0.22, darkAlpha: 0.32)
    static let negative = Color(hex: 0xFF4242)
    static let positive = Color(hex: 0x00BF40)
    static let cautionary = Color(hex: 0xFF9200)

    static func dynamic(light: UInt32, dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(hex: dark, alpha: darkAlpha)
                : UIColor(hex: light, alpha: lightAlpha)
        })
    }
}

/// Montage 타이포그래피(Pretendard 기준 크기)를 시스템 글꼴로 옮긴 것. Dynamic Type을 따른다.
enum YMFont {
    static let title2Bold = Font.system(size: 28, weight: .bold)
    static let headline1Bold = Font.system(size: 18, weight: .bold)
    static let headline2Bold = Font.system(size: 17, weight: .bold)
    static let body1Bold = Font.system(size: 16, weight: .bold)
    static let body1Medium = Font.system(size: 16, weight: .medium)
    static let body1Regular = Font.system(size: 16)
    static let body2Bold = Font.system(size: 15, weight: .bold)
    static let body2Medium = Font.system(size: 15, weight: .medium)
    static let body2Regular = Font.system(size: 15)
    static let label1Medium = Font.system(size: 14, weight: .medium)
    static let label1Regular = Font.system(size: 14)
    static let caption1Bold = Font.system(size: 12, weight: .bold)
    static let caption1Regular = Font.system(size: 12)
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255,
                  alpha: alpha)
    }
}

/// 화면 모드(설정 → 기타 → 화면 모드). 안드로이드 `AppAppearance`·`ThemeMode`와 같은 세 가지.
enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: "시스템 설정"
        case .light: "라이트"
        case .dark: "다크"
        }
    }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

@Observable
final class AppAppearanceStore {
    static let shared = AppAppearanceStore()
    var mode: ThemeMode {
        didSet { UserDefaults.standard.set(mode.rawValue, forKey: "app.themeMode") }
    }
    private init() {
        mode = ThemeMode(rawValue: UserDefaults.standard.string(forKey: "app.themeMode") ?? "") ?? .system
    }
}
