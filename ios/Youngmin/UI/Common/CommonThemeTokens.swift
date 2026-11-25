import SwiftUI
import UIKit

/// Montage `DesignSystemTheme.colors` 중 Theme.swift에 아직 없는 토큰(Docs/screens/chat-onboarding.md 0.1 표).
/// "추정" 값은 Montage 공개 팔레트에서 옮긴 것이라 실기기 스크린샷과 맞춰 봐야 한다.
extension YMColor {
    /// surfaceBrandPrimary / foregroundBrandPrimary / lineBrandPrimary (보내기 버튼, 단계 번호 원, 링크).
    static let brandPrimary = dynamic(light: 0x0066FF, dark: 0x3385FF)
    /// surfaceBrandStrong (확정).
    static let brandStrong = Color(hex: 0x005EEB)
    /// surfaceBrandSubtle (아이콘 타일, 선택된 대화 배경).
    static let brandSubtle = dynamic(light: 0xEAF2FE, dark: 0x0066FF, darkAlpha: 0.16)

    /// surfaceNeutralSecondary (카드·말풍선·입력창).
    static let surfaceSecondary = dynamic(light: 0xF4F4F5, dark: 0x2E2F33)
    /// surfaceNeutralTertiary (코드 배경).
    static let surfaceTertiary = dynamic(light: 0xEAEBEC, dark: 0x37383C)
    /// surfaceNeutralInverse / foregroundNeutralInverse.
    static let surfaceInverse = dynamic(light: 0x171719, dark: 0xF7F7F8)
    static let foregroundInverse = dynamic(light: 0xFFFFFF, dark: 0x171719)
    /// foregroundNeutralQuaternary (입력창 placeholder).
    static let labelQuaternary = dynamic(light: 0x37383C, dark: 0xC2C4C8, lightAlpha: 0.22, darkAlpha: 0.28)

    static let cautionarySurface = dynamic(light: 0xFFF4E5, dark: 0xFF9200, darkAlpha: 0.16)
    static let cautionaryForeground = dynamic(light: 0xFF9200, dark: 0xFFA938)
    static let positiveSurface = dynamic(light: 0xE5F9EC, dark: 0x00BF40, darkAlpha: 0.16)
    static let positiveForeground = dynamic(light: 0x00BF40, dark: 0x1ED45A)
    static let negativeSurface = dynamic(light: 0xFFEDED, dark: 0xFF4242, darkAlpha: 0.16)
    static let negativeForeground = dynamic(light: 0xFF4242, dark: 0xFF6363)
    static let disableSurface = dynamic(light: 0xF4F4F5, dark: 0x2E2F33)
    static let disableForeground = dynamic(light: 0x37383C, dark: 0xC2C4C8, lightAlpha: 0.16, darkAlpha: 0.16)
}

/// UIKit 쪽(UITextView 본문)에서 같은 토큰을 쓰기 위한 값.
enum YMUIColor {
    static func dynamic(light: UInt32, dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark, alpha: darkAlpha) : UIColor(hex: light, alpha: lightAlpha) }
    }
    static let label = dynamic(light: 0x171719, dark: 0xF7F7F8)
    static let labelSecondary = dynamic(light: 0x37383C, dark: 0xC2C4C8, lightAlpha: 0.61, darkAlpha: 0.88)
    static let brandPrimary = dynamic(light: 0x0066FF, dark: 0x3385FF)
    static let surfaceTertiary = dynamic(light: 0xEAEBEC, dark: 0x37383C)
    static let userSelection = UIColor(hex: 0x181B35)
}

/// Montage 글꼴 중 Theme.swift에 없는 것.
extension YMFont {
    /// title1Bold 36/48 (추정). 온보딩 단계 제목, 빈 채팅 제목.
    static let title1Bold = Font.system(size: 36, weight: .bold)
    /// body1ReadingRegular 16/26 — 줄 간격은 `.lineSpacing(YMFont.readingLineSpacing)`.
    static let body1Reading = Font.system(size: 16)
    static let readingLineSpacing: CGFloat = 6
    static let caption1Medium = Font.system(size: 12, weight: .medium)
    static let label1Bold = Font.system(size: 14, weight: .bold)
    static let label2Bold = Font.system(size: 13, weight: .bold)
}
