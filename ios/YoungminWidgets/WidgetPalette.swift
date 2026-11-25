import SwiftUI
import UIKit

/// 위젯 색(안드로이드 `res/values(-night)/widget_colors.xml`). 시스템 라이트/다크를 따른다.
enum WidgetPalette {
    static let background = dynamic(0xF7F8FC, 0x202430)
    static let text = dynamic(0x1D2535, 0xF0F2F8)
    static let secondary = dynamic(0x5B6578, 0xB3BDCE)
    static let accent = dynamic(0x465DC8, 0xAFBCFF)
    static let separator = dynamic(0xDCE1EC, 0x414859)

    /// 앱 시간표와 같은 과목색 9쌍(배경 / 글자, `TimetableColors.pairs`).
    static let coursePairs: [(Color, Color)] = [
        (hex(0x9ACBF0), hex(0x19364E)), (hex(0xE5AFBC), hex(0x492A34)),
        (hex(0xAFA9E4), hex(0x302C55)), (hex(0x88CDBD), hex(0x183D35)),
        (hex(0xECC596), hex(0x49351E)), (hex(0xE3CC8E), hex(0x473C1A)),
        (hex(0xC6B1DD), hex(0x3E2D4D)), (hex(0xB4CE9E), hex(0x2F4023)),
        (hex(0x9DB3CF), hex(0x24364D)),
    ]

    static func hex(_ v: UInt32) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    private static func ui(_ v: UInt32) -> UIColor {
        UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }

    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        let l = ui(light), d = ui(dark)
        return Color(UIColor { $0.userInterfaceStyle == .dark ? d : l })
    }
}

extension DynamicTypeSize {
    /// 안드로이드 `fontScale`에 해당하는 배율(기본 크기 = 1).
    var widgetFontScale: Double {
        switch self {
        case .xSmall: return 0.82
        case .small: return 0.88
        case .medium: return 0.94
        case .large: return 1
        case .xLarge: return 1.1
        case .xxLarge: return 1.2
        case .xxxLarge: return 1.3
        default: return 1.5
        }
    }
}
