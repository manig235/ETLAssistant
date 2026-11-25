import Foundation

/// 수식 안의 개행은 한 덩어리로 배치하고, 복사할 때는 개행까지 원문으로 복원한다(Kotlin `MathSelectionText`).
/// 위치는 UTF-16 기준.
public struct MathSelectionText: Sendable, Hashable {
    public let original: MarkdownLite.Result
    public let display: String

    public init(original: MarkdownLite.Result, display: String) {
        self.original = original
        self.display = display
    }

    public var maths: [MarkdownLite.Span] {
        original.spans.filter { $0.kind == .math || $0.kind == .mathBlock }
    }

    public func copySelection(_ start: Int, _ end: Int) -> String {
        let len = original.text.length16
        var from = min(max(min(start, end), 0), len)
        var to = min(max(max(start, end), 0), len)
        if from == to { return "" }
        // 수식 이미지는 한 단위다. 이미지 중간을 가리켜도 LaTeX를 잘라 복사하지 않는다.
        for math in maths {
            if from > math.start && from < math.end { from = math.start }
            if to > math.start && to < math.end { to = math.end }
        }
        return original.text.substring16(from, to)
    }

    public static func prepare(_ result: MarkdownLite.Result) -> MathSelectionText {
        var chars = T16.units(result.text)
        for span in result.spans where span.kind == .math || span.kind == .mathBlock {
            var i = max(span.start, 0)
            while i < min(span.end, chars.count) {
                if chars[i] == 10 || chars[i] == 13 { chars[i] = 32 }
                i += 1
            }
        }
        return MathSelectionText(original: result, display: T16.string(chars))
    }
}
