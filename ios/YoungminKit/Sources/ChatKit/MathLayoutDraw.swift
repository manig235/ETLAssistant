import CoreGraphics
import CoreText
import Foundation

extension MathLayout {

    /// [box]를 [ctx]에 그린다. (x, baseline)은 수식 기준선 왼쪽 끝.
    /// - Parameter yDown: UIKit 그림판처럼 y가 아래로 커지는 좌표계면 true, PDF처럼 위로 커지면 false.
    public static func draw(_ box: MathBox, in ctx: CGContext, x: Double, baseline: Double, color: CGColor, yDown: Bool) {
        ctx.saveGState()
        defer { ctx.restoreGState() }
        ctx.setFillColor(color)
        ctx.setStrokeColor(color)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        func pt(_ px: Double, _ py: Double) -> CGPoint {
            CGPoint(x: x + px, y: yDown ? baseline - py : baseline + py)
        }
        for item in box.items {
            switch item {
            case let .glyphs(text, face, size, gx, gy):
                let ln = line(text, face, size)
                ctx.saveGState()
                let origin = pt(gx, gy)
                ctx.translateBy(x: origin.x, y: origin.y)
                if yDown { ctx.scaleBy(x: 1, y: -1) }
                ctx.textMatrix = .identity
                ctx.textPosition = .zero
                CTLineDraw(ln, ctx)
                ctx.restoreGState()
            case let .rule(rx, ry, w, h):
                let a = pt(rx, ry), b = pt(rx + w, ry + h)
                ctx.fill(CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(b.x - a.x), height: abs(b.y - a.y)))
            case let .stroke(ops, lw):
                ctx.beginPath()
                add(ops, ctx, pt)
                ctx.setLineWidth(lw)
                ctx.strokePath()
            case let .fill(ops):
                ctx.beginPath()
                add(ops, ctx, pt)
                ctx.fillPath()
            }
        }
    }

    private static func add(_ ops: [MathPathOp], _ ctx: CGContext, _ pt: (Double, Double) -> CGPoint) {
        for op in ops {
            switch op {
            case let .move(a, b): ctx.move(to: pt(a, b))
            case let .line(a, b): ctx.addLine(to: pt(a, b))
            case let .quad(cx, cy, a, b): ctx.addQuadCurve(to: pt(a, b), control: pt(cx, cy))
            case let .curve(c1x, c1y, c2x, c2y, a, b): ctx.addCurve(to: pt(a, b), control1: pt(c1x, c1y), control2: pt(c2x, c2y))
            case .close: ctx.closePath()
            }
        }
    }
}
