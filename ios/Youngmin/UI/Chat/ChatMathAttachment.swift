import ChatKit
import UIKit

/// 수식 그림(Android `MathRender`·`MathImageSpan`의 iOS 판). ChatKit `MathLayout`으로 배치한 상자를 그림으로 그려
/// `NSTextAttachment`로 글 안에 넣는다. 외부 라이브러리·웹뷰 없음. 그리지 못한 수식은 nil(호출한 쪽이 유니코드 글로 대신).
enum ChatMathRender {
    /// 그린 수식 하나.
    struct Image {
        let image: UIImage
        /// 그림 아래 끝에서 수식 기준선까지(포인트).
        let baselineOffset: CGFloat
        let size: CGSize
    }

    private static let cache: NSCache<NSString, CacheBox> = {
        let c = NSCache<NSString, CacheBox>()
        c.totalCostLimit = 12 * 1024 * 1024 // Android LRU 12MB와 같게
        return c
    }()
    private static var failed = Set<String>()

    private final class CacheBox {
        let value: Image
        init(_ value: Image) { self.value = value }
    }

    /// 너무 큰 수식(잘못된 입력 등)은 그리지 않는다(Android MAX_PIXELS).
    private static let MAX_PIXELS: CGFloat = 4_000_000

    /// [latex]를 [fontSize]로 [color] 색에 그린다. [scale]은 줄여 맞출 때의 배율.
    static func render(_ latex: String, fontSize: CGFloat, display: Bool, color: UIColor, scale: CGFloat = 1, verticalPadding: CGFloat = 0) -> Image? {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        let key = "\(display)|\(fontSize)|\(scale)|\(verticalPadding)|\(r),\(g),\(b),\(a)|\(latex)"
        if let hit = cache.object(forKey: key as NSString) { return hit.value }
        if failed.contains(key) { return nil }
        guard let laid = layout(latex, fontSize: fontSize, display: display) else { failed.insert(key); return nil }
        let box = laid.scaled(Double(scale))
        let pad = max(1, (fontSize * scale * 0.08).rounded(.up))
        let size = CGSize(width: ceil(box.width + 2 * pad), height: ceil(box.height + 2 * pad + 2 * verticalPadding))
        let screen = UIScreen.main.scale
        guard size.width > 0, size.height > 0, size.width * size.height * screen * screen <= MAX_PIXELS else { failed.insert(key); return nil }
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            MathLayout.draw(box, in: ctx.cgContext, x: Double(pad), baseline: Double(pad + verticalPadding) + box.ascent,
                            color: color.cgColor, yDown: true)
        }
        let result = Image(image: image, baselineOffset: pad + verticalPadding + CGFloat(box.descent), size: size)
        cache.setObject(CacheBox(result), forKey: key as NSString, cost: Int(size.width * size.height * screen * screen * 4))
        return result
    }

    /// 배치만(크기 재기). 해석하지 못하면 nil.
    static func layout(_ latex: String, fontSize: CGFloat, display: Bool) -> MathBox? {
        MathLayout.layout(latex, fontSize: Double(fontSize), display: display)
    }

    /// 첫 수식에서 글꼴을 읽느라 멈칫하지 않게 미리 한 번 배치한다(Android `MathRender.warmUp`).
    static func warmUp() {
        DispatchQueue.global(qos: .utility).async { _ = MathLayout.layout("x^2+\\frac{1}{2}", fontSize: 18, display: false) }
    }
}

/// 복사할 때 되돌릴 수식 원문(구분자 포함). 값이 같은 수식이 나란히 있어도 따로 보이도록 객체로 둔다.
final class ChatMathSource: NSObject {
    let raw: String
    init(_ raw: String) { self.raw = raw }
}

extension NSAttributedString.Key {
    /// 수식(그림 또는 대체 글) 구간. 값은 `ChatMathSource`.
    static let ymMathSource = NSAttributedString.Key("YMMathSource")
}

/// 말풍선보다 넓은 블록 수식: 0.78배까지 줄이고(Android와 같음) 그래도 넘치면 말풍선 폭만 보이고 가로로 밀어 본다.
/// 글 안에서는 보이는 부분 그림만 그리고(표시), 채팅 말풍선은 `YMMarkdown.segments`로 이 글자에서 본문을 나눠
/// 가로 스크롤 보기(`YMWideMathView`)로 전체 그림을 띄운다. (UITextView의 TextKit 2 첨부 보기는 읽기 전용 본문에서
/// `loadView`가 불리지 않아 쓰지 않는다.)
final class ChatMathScrollAttachment: NSTextAttachment {
    let full: ChatMathRender.Image

    init(full: ChatMathRender.Image, visibleWidth: CGFloat) {
        self.full = full
        super.init(data: nil, ofType: nil)
        let visible = CGSize(width: visibleWidth, height: full.size.height)
        image = UIGraphicsImageRenderer(size: visible, format: full.image.imageRendererFormat).image { _ in
            full.image.draw(at: .zero)
        }
        bounds = CGRect(x: 0, y: -full.baselineOffset, width: visibleWidth, height: full.size.height)
    }

    required init?(coder: NSCoder) { fatalError("not used") }
}
