import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import ChatKit

@Suite struct MathLayoutTests {

    /// 강의 답변에 실제로 나온 식들(안드로이드 phase2-log 수식 절의 예 포함).
    static let lectureSamples = [
        "3k_B", "\\omega", "C_V\\simeq\\frac{12\\pi^4}{5}Nk_B\\left(\\frac{T}{\\Theta_D}\\right)^3",
        "\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}",
        "i\\hbar\\frac{\\partial}{\\partial t}\\Psi(\\mathbf{r},t) = \\hat{H}\\Psi(\\mathbf{r},t)",
        "\\sum_{n=1}^{N} n = \\frac{N(N+1)}{2}", "\\prod_{i=1}^n x_i", "\\lim_{x\\to 0}\\frac{\\sin x}{x} = 1",
        "\\sqrt[3]{x^2+1}", "x_{i,j}^{(k)}", "e^{i\\pi}+1=0", "\\vec{F} = m\\vec{a}", "\\bar{x} \\pm \\sigma",
        "a \\le b \\ne c \\approx d \\ge e", "\\alpha\\beta\\Gamma\\Delta", "\\mathrm{d}x", "\\text{속도 } v = \\frac{d}{t}",
        "\\operatorname{Var}(X) = E[X^2] - E[X]^2", "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
        "\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}",
        "f(x) = \\begin{cases} x^2 & x \\ge 0 \\\\ -x & x < 0 \\end{cases}",
        "\\begin{aligned} a &= b + c \\\\ &= d \\end{aligned}", "\\left[ \\frac{a}{b} \\right]", "\\left\\{ x \\right\\}",
        "\\binom{n}{k}", "\\dfrac{1}{2}", "x \\cdot y \\times z", "\\infty", "A \\Rightarrow B \\iff C",
        "\\nabla \\cdot \\mathbf{E} = \\frac{\\rho}{\\varepsilon_0}", "\\oint_C \\mathbf{B}\\cdot d\\mathbf{l} = \\mu_0 I",
        "\\hat{\\mathbf{n}}", "\\overline{AB}", "\\tilde{x}", "\\dot{x}, \\ddot{x}", "f'(x)", "\\mathbb{R}^n",
        "\\left| x \\right|", "\\langle \\psi | \\phi \\rangle", "\\underbrace{a+b}_{n}", "\\xrightarrow{k}", "\\not\\in",
        "\\max_{x \\in S} f(x)", "\\boxed{E=mc^2}", "\\Delta G = \\Delta H - T\\Delta S", "10^{-3}\\,\\text{m}",
    ]

    @Test func parsesLectureSamples() throws {
        for s in Self.lectureSamples {
            #expect(MathLayout.canParse(s), "\(s)")
            let box = try #require(MathLayout.layout(s, fontSize: 18, display: true), "\(s)")
            #expect(box.width > 0 && box.height > 0, "\(s)")
            #expect(!box.items.isEmpty, "\(s)")
        }
    }

    @Test func unsupportedFallsBack() {
        for s in ["\\foo{x}", "\\frac{a}", "x^", "{x", "x}", "\\left( x", "\\begin{tikzcd}a\\end{tikzcd}", "\\text{$x$}",
                  "a^b^c", "\\begin{matrix} a \\end{pmatrix}", "\\middle|", "#1"] {
            #expect(!MathLayout.canParse(s), "\(s)")
            #expect(MathLayout.layout(s, fontSize: 16, display: false) == nil, "\(s)")
        }
        #expect(MathLayout.layout("", fontSize: 16, display: false) == nil)
        #expect(MathLayout.layout(String(repeating: "x", count: MathParser.MAX_LENGTH + 1), fontSize: 16, display: false) == nil)
    }

    @Test func parserStructure() throws {
        #expect(try MathParser.parse("x^2") == [.scripts(base: .symbol("x", .ord, .italic), sup: [.symbol("2", .ord, .roman)], sub: nil)])
        #expect(try MathParser.parse("a-b") == [.symbol("a", .ord, .italic), .symbol("−", .bin, .roman), .symbol("b", .ord, .italic)])
        #expect(try MathParser.parse("\\Gamma\\gamma") == [.symbol("Γ", .ord, .roman), .symbol("γ", .ord, .italic)])
        #expect(try MathParser.parse("\\mathbf{v}") == [.group([.symbol("v", .ord, .bold)])])
        #expect(try MathParser.parse("\\mathbb{R}") == [.group([.symbol("ℝ", .ord, .roman)])])
        #expect(try MathParser.parse("\\not=") == [.symbol("≠", .rel, .roman)])
        #expect(try MathParser.parse("f''") == [.scripts(base: .symbol("f", .ord, .italic), sup: [.symbol("″", .ord, .roman)], sub: nil)])
        #expect(try MathParser.parse("\\text{a  b}") == [.text("a  b", .roman)])
        #expect(try MathParser.parse("\\sum\\limits_0") == [.scripts(base: .largeOp("∑", limits: true, isText: false, defaultLimits: true),
                                                                         sup: nil, sub: [.symbol("0", .ord, .roman)])])
        // 맨 바깥 \\는 여러 줄(가운데 정렬)
        if case let .array(rows, kind, _) = try MathParser.parse("a \\\\ b")[0] {
            #expect(rows.count == 2)
            #expect(kind == .gathered)
        } else { Issue.record("expected array") }
        // 끝의 \\는 빈 줄을 만들지 않는다
        if case let .array(rows, _, _) = try MathParser.parse("\\begin{matrix}a\\\\b\\\\\\end{matrix}")[0] { #expect(rows.count == 2) }
        // 주석은 건너뛴다
        #expect(try MathParser.parse("x % 주석\n+1").count == 3)
    }

    @Test func fractionIsTallerThanItsParts() throws {
        let x = try #require(MathLayout.layout("x", fontSize: 20, display: false))
        let f = try #require(MathLayout.layout("\\frac{x}{y}", fontSize: 20, display: true))
        let t = try #require(MathLayout.layout("\\frac{x}{y}", fontSize: 20, display: false))
        #expect(f.ascent > x.ascent && f.descent > x.descent)
        // 글 안(text style) 분수는 블록보다 작다(분자·분모가 첨자 크기)
        #expect(t.height < f.height)
        #expect(t.width < f.width)
        // 분수 선은 하나
        #expect(f.items.filter { if case .rule = $0 { return true }; return false }.count == 1)
        // \dfrac은 글 안에서도 블록 크기
        let d = try #require(MathLayout.layout("\\dfrac{x}{y}", fontSize: 20, display: false))
        #expect(abs(d.height - f.height) < 0.01)
    }

    @Test func scriptsShrinkAndShift() throws {
        let x = try #require(MathLayout.layout("x", fontSize: 20, display: false))
        let sup = try #require(MathLayout.layout("x^2", fontSize: 20, display: false))
        let sub = try #require(MathLayout.layout("x_2", fontSize: 20, display: false))
        let nested = try #require(MathLayout.layout("x^{2^{2}}", fontSize: 20, display: false))
        #expect(sup.ascent > x.ascent)
        #expect(sub.descent > x.descent)
        #expect(nested.ascent > sup.ascent)
        let sizes = sup.items.compactMap { item -> Double? in if case let .glyphs(_, _, s, _, _) = item { return s }; return nil }
        #expect(sizes.sorted() == [14, 20])
        let deep = nested.items.compactMap { item -> Double? in if case let .glyphs(_, _, s, _, _) = item { return s }; return nil }
        #expect(deep.min() == 10) // 두 단계 아래는 0.5배
    }

    @Test func limitsGoAboveAndBelowInDisplayOnly() throws {
        let display = try #require(MathLayout.layout("\\sum_{i=1}^{n} i", fontSize: 20, display: true))
        let inline = try #require(MathLayout.layout("\\sum_{i=1}^{n} i", fontSize: 20, display: false))
        #expect(display.height > inline.height)
        #expect(display.width < inline.width + 20)
        // 적분은 디스플레이에서도 옆에 붙는다
        let integral = try #require(MathLayout.layout("\\int_0^1", fontSize: 20, display: true))
        let glyphs = integral.items.compactMap { item -> (String, Double)? in if case let .glyphs(s, _, _, x, _) = item { return (s, x) }; return nil }
        let intX = try #require(glyphs.first { $0.0 == "∫" }).1
        let zeroX = try #require(glyphs.first { $0.0 == "0" }).1
        #expect(zeroX > intX)
    }

    @Test func atomSpacing() throws {
        let tight = try #require(MathLayout.layout("ab", fontSize: 18, display: false))
        let rel = try #require(MathLayout.layout("a=b", fontSize: 18, display: false))
        let eq = try #require(MathLayout.layout("=", fontSize: 18, display: false))
        // a = b 는 관계 기호 양쪽에 굵은 간격(5mu씩)
        #expect(rel.width > tight.width + eq.width + 2 * 5 * 18 / 18 - 0.5)
        // 맨 앞의 빼기는 이항 연산자가 아니다(간격 없음)
        let neg = try #require(MathLayout.layout("-a", fontSize: 18, display: false))
        let bin = try #require(MathLayout.layout("b-a", fontSize: 18, display: false))
        let b = try #require(MathLayout.layout("b", fontSize: 18, display: false))
        #expect(bin.width - b.width > neg.width + 7)
        // 첨자 안에서는 간격을 줄인다
        #expect(MathLayout.space(.ord, .rel, script: true) == 0)
        #expect(MathLayout.space(.ord, .rel, script: false) == 5)
        #expect(MathLayout.space(.op, .ord, script: true) == 3)
    }

    @Test func delimitersGrowWithContent() throws {
        let small = try #require(MathLayout.layout("\\left( x \\right)", fontSize: 20, display: true))
        let big = try #require(MathLayout.layout("\\left( \\frac{\\frac{a}{b}}{c} \\right)", fontSize: 20, display: true))
        #expect(big.height > small.height * 1.5)
        // 큰 괄호는 그림(채운 모양)으로 그린다
        #expect(big.items.contains { if case .fill = $0 { return true }; return false })
        let matrix = try #require(MathLayout.layout("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}", fontSize: 20, display: true))
        let glyphs = matrix.items.compactMap { item -> String? in if case let .glyphs(s, _, _, _, _) = item { return s }; return nil }
        #expect(glyphs == ["1", "2", "3", "4"])
        #expect(matrix.items.filter { if case .fill = $0 { return true }; return false }.count == 2)
    }

    @Test func centeredOnMathAxis() throws {
        let m = try #require(MathLayout.layout("\\begin{matrix} a \\\\ b \\\\ c \\end{matrix}", fontSize: 20, display: true))
        #expect(abs((m.ascent - m.descent) / 2 - 0.25 * 20) < 0.01)
    }

    @Test func scaleAndDraw() throws {
        let box = try #require(MathLayout.layout("\\sqrt{x^2+y^2}", fontSize: 20, display: true))
        let half = box.scaled(0.5)
        #expect(abs(half.width - box.width / 2) < 1e-9)
        #expect(abs(half.height - box.height / 2) < 1e-9)
        let w = Int(ceil(box.width)) + 4, h = Int(ceil(box.height)) + 4
        let ctx = try #require(CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                         space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        MathLayout.draw(box, in: ctx, x: 2, baseline: 2 + box.descent, color: CGColor(red: 0, green: 0, blue: 0, alpha: 1), yDown: false)
        let data = try #require(ctx.data)
        let bytes = data.bindMemory(to: UInt8.self, capacity: ctx.bytesPerRow * h)
        var inked = 0
        for k in 0..<(ctx.bytesPerRow * h / 4) where bytes[k * 4 + 3] > 0 { inked += 1 }
        #expect(inked > 50)
    }

    /// `MATH_RENDER_DIR`를 주면 예시 수식을 PNG로 그려 눈으로 확인할 수 있다.
    @Test func renderSamplesForInspection() throws {
        guard let dir = ProcessInfo.processInfo.environment["MATH_RENDER_DIR"] else { return }
        let size = 22.0
        let boxes = Self.lectureSamples.compactMap { MathLayout.layout($0, fontSize: size, display: true) }
        let w = Int(boxes.map(\.width).max() ?? 100) + 40
        let h = Int(boxes.reduce(0) { $0 + $1.height + 14 }) + 20
        let ctx = try #require(CGContext(data: nil, width: w * 2, height: h * 2, bitsPerComponent: 8, bytesPerRow: 0,
                                         space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: w * 2, height: h * 2))
        ctx.scaleBy(x: 2, y: 2)
        var y = Double(h) - 10
        for b in boxes {
            y -= b.ascent
            ctx.setFillColor(CGColor(red: 0.9, green: 0.95, blue: 1, alpha: 1))
            ctx.fill(CGRect(x: 20, y: y - b.descent, width: b.width, height: b.height))
            MathLayout.draw(b, in: ctx, x: 20, baseline: y, color: CGColor(red: 0, green: 0, blue: 0, alpha: 1), yDown: false)
            y -= b.descent + 14
        }
        let image = try #require(ctx.makeImage())
        let url = URL(fileURLWithPath: dir).appendingPathComponent("math-samples.png")
        let dest = try #require(CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(dest, image, nil)
        #expect(CGImageDestinationFinalize(dest))
    }
}
