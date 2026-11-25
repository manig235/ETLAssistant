import SwiftUI

/// Compose `FlowRow`: 가로로 놓다가 넘치면 다음 줄로. `alignment`가 `.trailing`이면 줄마다 오른쪽 정렬.
struct YMFlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8
    var alignment: HorizontalAlignment = .leading

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let lines = arrange(maxWidth: maxWidth, subviews: subviews)
        let width = lines.map(\.width).max() ?? 0
        let height = lines.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(lines.count - 1, 0))
        return CGSize(width: proposal.width.map { min($0, width) } ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let lines = arrange(maxWidth: bounds.width, subviews: subviews)
        var y = bounds.minY
        for line in lines {
            var x: CGFloat
            switch alignment {
            case .trailing: x = bounds.maxX - line.width
            case .center: x = bounds.minX + (bounds.width - line.width) / 2
            default: x = bounds.minX
            }
            for item in line.items {
                let size = item.size
                subviews[item.index].place(at: CGPoint(x: x, y: y + (line.height - size.height) / 2),
                                           proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += line.height + lineSpacing
        }
    }

    private struct Item { let index: Int; let size: CGSize }
    private struct Line { var items: [Item] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(maxWidth: CGFloat, subviews: Subviews) -> [Line] {
        var lines: [Line] = []
        var current = Line()
        for (i, sub) in subviews.enumerated() {
            var size = sub.sizeThatFits(.unspecified)
            if size.width > maxWidth { size = sub.sizeThatFits(ProposedViewSize(width: maxWidth, height: nil)) }
            let needed = current.items.isEmpty ? size.width : current.width + spacing + size.width
            if needed > maxWidth && !current.items.isEmpty {
                lines.append(current)
                current = Line()
            }
            current.width = current.items.isEmpty ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
            current.items.append(Item(index: i, size: size))
        }
        if !current.items.isEmpty { lines.append(current) }
        return lines
    }
}

/// 자식에게 `maxWidth`까지만 제안하고 자식 크기를 그대로 쓰는 틀(말풍선이 내용 폭에 맞게 줄어들되 최대 폭을 넘지 않게).
/// `.frame(maxWidth:)`는 제안 폭이 크면 최대 폭까지 늘어나므로 대신 쓴다.
struct YMCapWidth: Layout {
    var maxWidth: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = min(proposal.width ?? maxWidth, maxWidth)
        return subviews.first?.sizeThatFits(ProposedViewSize(width: w, height: proposal.height)) ?? .zero
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}
