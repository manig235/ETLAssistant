import CoreGraphics
import Foundation
import ImageIO
import PDFKit
import UniformTypeIdentifiers

/// PDF를 쪽 이미지로 바꾼다(Kotlin `PdfPages`; 안드로이드 `PdfRenderer` 대신 CoreGraphics `CGPDFDocument`).
/// 손글씨·스캔본도 이미지로 볼 수 있다. 글 뽑기([text])는 PDFKit을 쓴다(iOS에서 새로 더한 보조 함수).
public enum PdfPages {
    public struct Rendered: Sendable, Hashable {
        public let page: Int
        public let file: URL
        public let width: Int
        public let height: Int
        public init(page: Int, file: URL, width: Int, height: Int) {
            self.page = page
            self.file = file
            self.width = width
            self.height = height
        }
    }

    public static func pageCount(_ file: URL) throws -> Int { try open(file).numberOfPages }

    /// [pages](1부터)를 [outDir]에 `{baseName}-p{n}.png`로 그린다. 가로는 [targetWidth]픽셀로 맞추고(작은 쪽은 키우지 않음),
    /// 세로가 너무 길면 [maxPixels]를 넘지 않게 줄인다. 투명 배경은 흰색으로 채운다.
    public static func render(_ file: URL, _ pages: [Int], _ outDir: URL, _ baseName: String,
                              targetWidth: Int = 1400, maxPixels: Int = 4_000_000) throws -> [Rendered] {
        try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let doc = try open(file)
        var out: [Rendered] = []
        for p in pages {
            if p < 1 || p > doc.numberOfPages { continue }
            guard let page = doc.page(at: p) else { continue }
            let (pw, ph) = pageSize(page)
            var w = min(targetWidth, max(pw * 2, 600))
            var h = max(Int(Int64(w) * Int64(ph) / Int64(max(pw, 1))), 1)
            if Int64(w) * Int64(h) > Int64(maxPixels) {
                let k = (Double(maxPixels) / (Double(w) * Double(h))).squareRoot()
                w = max(Int(Double(w) * k), 1)
                h = max(Int(Double(h) * k), 1)
            }
            guard let image = draw(page, w, h) else { throw EtlException("PDF 쪽을 그리지 못했습니다") }
            let f = outDir.appendingPathComponent("\(baseName)-p\(p).png")
            try writePng(image, f)
            out.append(Rendered(page: p, file: f, width: w, height: h))
        }
        return out
    }

    /// PDF의 글(PDFKit). [pages](1부터)가 nil이면 모든 쪽. 쪽 사이는 `--- N쪽 ---`으로 나눈다.
    /// 글 층이 없는 스캔본은 빈 문자열이다.
    public static func text(_ file: URL, pages: [Int]? = nil, maxChars: Int = EtlFiles.MAX_TEXT_CHARS) throws -> String {
        _ = try open(file) // 같은 오류 문구
        guard let doc = PDFDocument(url: file) else { throw EtlException("PDF를 열 수 없습니다(암호가 있거나 손상된 파일일 수 있습니다)") }
        if doc.isLocked && !doc.unlock(withPassword: "") { throw EtlException("PDF를 열 수 없습니다(암호가 있거나 손상된 파일일 수 있습니다)") }
        var parts: [String] = []
        var used = 0
        for p in pages ?? Array(1...max(doc.pageCount, 1)) where p >= 1 && p <= doc.pageCount {
            let t = (doc.page(at: p - 1)?.string ?? "").kTrim
            parts.append("--- \(p)쪽 ---\n\(t)")
            used += t.utf16.count
            if used > maxChars { break }
        }
        return parts.joined(separator: "\n\n")
    }

    /// 회전을 반영한 쪽 크기(포인트, 안드로이드 `Page.width/height`).
    static func pageSize(_ page: CGPDFPage) -> (Int, Int) {
        let box = page.getBoxRect(.cropBox)
        let quarter = ((page.rotationAngle % 360) + 360) % 360
        let (w, h) = quarter % 180 == 0 ? (box.width, box.height) : (box.height, box.width)
        return (max(Int(w.rounded()), 1), max(Int(h.rounded()), 1))
    }

    private static func draw(_ page: CGPDFPage, _ w: Int, _ h: Int) -> CGImage? {
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        let box = page.getBoxRect(.cropBox)
        let angle = ((page.rotationAngle % 360) + 360) % 360
        let (pw, ph) = angle % 180 == 0 ? (box.width, box.height) : (box.height, box.width)
        guard pw > 0, ph > 0 else { return ctx.makeImage() }
        ctx.interpolationQuality = .high
        ctx.scaleBy(x: CGFloat(w) / pw, y: CGFloat(h) / ph)
        // /Rotate는 시계 방향(아래-왼쪽 원점 좌표계에서 회전 뒤 다시 제자리로 옮긴다).
        switch angle {
        case 90: ctx.translateBy(x: 0, y: ph); ctx.rotate(by: -.pi / 2)
        case 180: ctx.translateBy(x: pw, y: ph); ctx.rotate(by: .pi)
        case 270: ctx.translateBy(x: pw, y: 0); ctx.rotate(by: .pi / 2)
        default: break
        }
        ctx.translateBy(x: -box.minX, y: -box.minY)
        ctx.clip(to: box)
        ctx.drawPDFPage(page)
        return ctx.makeImage()
    }

    private static func writePng(_ image: CGImage, _ url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw EtlException("쪽 이미지를 저장하지 못했습니다")
        }
        CGImageDestinationAddImage(dest, image, nil)
        if !CGImageDestinationFinalize(dest) { throw EtlException("쪽 이미지를 저장하지 못했습니다") }
    }

    private static func open(_ file: URL) throws -> CGPDFDocument {
        guard FileManager.default.isReadableFile(atPath: file.path) else { throw EtlException("PDF 파일을 열 수 없습니다") }
        // 암호가 걸렸거나 손상된 파일. 내용은 노출하지 않는다.
        guard let doc = CGPDFDocument(file as CFURL) else { throw EtlException("PDF를 열 수 없습니다(암호가 있거나 손상된 파일일 수 있습니다)") }
        if doc.isEncrypted && !doc.isUnlocked && !doc.unlockWithPassword("") {
            throw EtlException("PDF를 열 수 없습니다(암호가 있거나 손상된 파일일 수 있습니다)")
        }
        if !doc.isUnlocked || doc.numberOfPages < 1 { throw EtlException("PDF를 열 수 없습니다(암호가 있거나 손상된 파일일 수 있습니다)") }
        return doc
    }
}
