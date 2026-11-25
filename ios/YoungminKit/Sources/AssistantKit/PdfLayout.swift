import Foundation
import ETLKit

/// make_pdf의 순수 규칙: 쪽 나누기, 파일 이름, 상한(Kotlin `pdf/PdfLayoutLogic.kt`).
public enum PdfLayoutLogic {
    /// A4(포인트, 1/72인치).
    public static let PAGE_WIDTH = 595
    public static let PAGE_HEIGHT = 842
    public static let MARGIN_X: Double = 56
    public static let MARGIN_TOP: Double = 56
    /// 아래 여백(쪽 번호 자리 포함).
    public static let MARGIN_BOTTOM: Double = 64

    public static let MAX_PAGES = 200
    public static let MAX_BYTES: Int64 = 20 * 1024 * 1024
    public static let MAX_MARKDOWN_CHARS = 300_000
    public static let MAX_TITLE_CHARS = 120

    public static var contentWidth: Double { Double(PAGE_WIDTH) - 2 * MARGIN_X }
    public static var contentHeight: Double { Double(PAGE_HEIGHT) - MARGIN_TOP - MARGIN_BOTTOM }

    /// 쪽에 놓을 조각 하나(글 한 줄, 수식, 표 한 행 등). [gapBefore]는 앞 조각과의 간격(쪽 맨 위에서는 버린다).
    /// [keepWithNext]면 다음 조각이 같은 쪽에 들어가지 않을 때 이 조각부터 다음 쪽으로 넘긴다(제목, 문단 첫 줄).
    public struct Item: Sendable, Hashable {
        public var height: Double
        public var gapBefore: Double
        public var keepWithNext: Bool
        public init(_ height: Double, _ gapBefore: Double = 0, _ keepWithNext: Bool = false) {
            self.height = height
            self.gapBefore = gapBefore
            self.keepWithNext = keepWithNext
        }
    }

    /// [page]는 0부터, [y]는 본문 영역 위에서부터의 거리.
    public struct Placement: Sendable, Hashable {
        public var page: Int
        public var y: Double
        public init(_ page: Int, _ y: Double) {
            self.page = page
            self.y = y
        }
    }

    /// 조각들을 차례로 쪽에 놓는다. 쪽 높이보다 큰 조각은 새 쪽 맨 위에 놓는다(그리는 쪽이 잘라서 그린다).
    public static func paginate(_ items: [Item], pageHeight: Double = contentHeight) -> [Placement] {
        var out: [Placement] = []
        out.reserveCapacity(items.count)
        var page = 0
        var y = 0.0
        for (i, item) in items.enumerated() {
            var gap = y == 0 ? 0 : item.gapBefore
            let h = min(item.height, pageHeight)
            if y > 0 && y + gap + h > pageHeight {
                page += 1
                y = 0
                gap = 0
            } else if y > 0 && item.keepWithNext, i + 1 < items.count {
                let next = items[i + 1]
                let nh = min(next.height, pageHeight)
                if y + gap + h + next.gapBefore + nh > pageHeight && h + next.gapBefore + nh <= pageHeight {
                    page += 1
                    y = 0
                    gap = 0
                }
            }
            out.append(Placement(page, y + gap))
            y += gap + h
        }
        return out
    }

    public static func pageCount(_ placements: [Placement]) -> Int { (placements.map(\.page).max() ?? 0) + 1 }

    private static let SPACES = KRegex("\\s+")

    /// 저장할 파일 이름. [filename]이 있으면 그것, 없으면 제목. 경로 구분자·제어문자를 지우고(앱 공통 규칙),
    /// 확장자는 늘 `.pdf`, UTF-8 200바이트 안으로 줄인다.
    public static func fileName(_ title: String, _ filename: String?) -> String {
        let raw = filename?.kt.nilIfEmpty ?? title.kt
        var base = SPACES.replace(EtlLogic.safeName(raw, "영민이 문서"), " ").kt
        if base.lowercased().hasSuffix(".pdf") { base = String(base.dropLast(4)) }
        while let last = base.last, last == "." || last == " " { base.removeLast() }
        if base.isEmpty { base = "영민이 문서" }
        return EtlDownloads.fitName("\(base).pdf")
    }

    /// 쪽 아래 표시: "제목 · 3 / 10". 제목이 길면 줄인다.
    public static func footer(_ title: String, _ page: Int, _ total: Int) -> String {
        var t = title.kt
        if t.utf16.count > 40 { t = t.take16(39) + "…" }
        return t.isEmpty ? "\(page) / \(total)" : "\(t) · \(page) / \(total)"
    }
}
