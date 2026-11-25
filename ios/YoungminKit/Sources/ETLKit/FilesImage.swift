import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// 이미지 해독기(Kotlin `WorkspaceImage.Codec`). 테스트에서 가짜를 넣는다.
public protocol WorkspaceImageCodec {
    func bounds(_ bytes: Data) throws -> WorkspaceImage.Bounds
    /// 구현은 원본 컨테이너 바이트가 아니라 **새로 인코딩한 JPEG 픽셀**을 돌려줘야 한다.
    func sanitize(_ bytes: Data, sample: Int) throws -> WorkspaceImage.Encoded
}

/// 이미지 도구(`etl_view_image`)는 etl_read_file과 같은 앱 전용 작업 폴더만 읽는다(Kotlin `WorkspaceImage`).
/// 크기 상한을 지키고, 픽셀을 다시 인코딩해 EXIF·XMP·GPS 같은 메타데이터를 버린다(ImageIO/CoreGraphics).
public enum WorkspaceImage {
    public static let NAME = "etl_view_image"
    public static let MAX_FILE_BYTES = 20 * 1024 * 1024
    public static let MAX_SOURCE_PIXELS: Int64 = 64_000_000
    public static let MAX_SOURCE_EDGE = 16_384
    public static let MAX_DECODE_PIXELS: Int64 = 3_000_000
    public static let MAX_DECODE_EDGE = 3_072
    public static let MAX_OUTPUT_BYTES = 6 * 1024 * 1024

    public typealias Codec = WorkspaceImageCodec

    public struct Bounds: Sendable, Hashable {
        public let width: Int
        public let height: Int
        public let mime: String?
        public init(_ width: Int, _ height: Int, _ mime: String?) {
            self.width = width
            self.height = height
            self.mime = mime
        }
    }

    public struct Encoded: Sendable, Hashable {
        public let bytes: Data
        public let width: Int
        public let height: Int
        public init(_ bytes: Data, _ width: Int, _ height: Int) {
            self.bytes = bytes
            self.width = width
            self.height = height
        }
    }

    /// 도구 결과의 이미지 하나(Kotlin `ToolImage`; ChatKit `InputImage`로 바꿔 모델에 보낸다).
    public struct ToolImage: Sendable, Hashable {
        public let mimeType: String
        public let data: Data
    }

    /// 도구 결과(Kotlin `ToolResult`). 오류면 [isError]이고 [text]는 고정 문구다.
    public struct Result: Sendable, Hashable {
        public let text: String
        public let isError: Bool
        public let images: [ToolImage]
        /// 로그용(모델에 보내지 않는다).
        public let detail: String?
    }

    public struct Rejected: Error, Sendable {
        public let reason: String
        public init(_ reason: String) { self.reason = reason }
    }

    /// 동시에 여러 요청이 와도 비트맵 메모리가 겹치지 않게 한다.
    private static let imageLock = NSLock()

    public static func sample(_ bounds: Bounds) throws -> Int {
        guard let mime = bounds.mime, ["image/png", "image/jpeg", "image/webp"].contains(mime) else { throw Rejected("format") }
        let w = bounds.width
        let h = bounds.height
        if w <= 0 || h <= 0 || w > MAX_SOURCE_EDGE || h > MAX_SOURCE_EDGE || Int64(w) * Int64(h) > MAX_SOURCE_PIXELS { throw Rejected("pixels") }
        var sample = 1
        while true {
            let sw = (Int64(w) + Int64(sample) - 1) / Int64(sample)
            let sh = (Int64(h) + Int64(sample) - 1) / Int64(sample)
            if max(sw, sh) <= Int64(MAX_DECODE_EDGE) && sw * sh <= MAX_DECODE_PIXELS { return sample }
            sample *= 2
        }
    }

    /// 오류에는 고정 문구만 담는다(경로나 해독기 예외로 비밀이 새지 않게).
    public static func view(_ workspace: URL, _ path: String, codec: Codec = ImageIOCodec()) -> Result {
        imageLock.lock()
        defer { imageLock.unlock() }
        do {
            let bytes = try read(workspace, path)
            let s = try sample(codec.bounds(bytes))
            let image = try codec.sanitize(bytes, sample: s)
            if image.width <= 0 || image.height <= 0 || max(image.width, image.height) > MAX_DECODE_EDGE ||
                Int64(image.width) * Int64(image.height) > MAX_DECODE_PIXELS { throw Rejected("pixels") }
            if image.bytes.isEmpty || image.bytes.count > MAX_OUTPUT_BYTES { throw Rejected("output-size") }
            return Result(text: "이미지 \(image.width)×\(image.height). 원본 메타데이터를 제외하고 다시 인코딩했어요.", isError: false,
                          images: [ToolImage(mimeType: "image/jpeg", data: image.bytes)], detail: nil)
        } catch let e as Rejected {
            let message: String
            switch e.reason {
            case "file-size", "output-size": message = "이미지가 너무 커서 열 수 없어요. 파일은 20MB 이하로 줄여 주세요."
            case "pixels": message = "이미지의 픽셀 크기가 너무 크거나 올바르지 않아요. 더 작은 이미지로 바꿔 주세요."
            case "format": message = "PNG·JPEG·WebP 이미지만 열 수 있어요. PDF는 etl_render_pages로 쪽을 먼저 만들어 주세요."
            default: message = "etl-files/ 안의 이미지 파일만 열 수 있어요. etl_render_pages나 첨부가 준 상대 경로를 사용해 주세요."
            }
            return Result(text: message, isError: true, images: [], detail: "image-rejected:\(e.reason)")
        } catch {
            return Result(text: "이미지를 열 수 없어요. 파일이 손상되었거나 지원하지 않는 이미지일 수 있어요.", isError: true, images: [], detail: "image-decode")
        }
    }

    private static func read(_ workspace: URL, _ path: String) throws -> Data {
        // iOS의 workspace는 etl-files 폴더 자체다. 그 폴더가 심볼릭 링크면 거부한다.
        if let type = try? FileManager.default.attributesOfItem(atPath: workspace.path)[.type] as? FileAttributeType, type == .typeSymbolicLink {
            throw Rejected("path")
        }
        let file: URL
        do { file = try EtlFiles.resolveInside(workspace, path) } catch { throw Rejected("path") }
        let size = ((try? FileManager.default.attributesOfItem(atPath: file.path)[.size]) as? NSNumber)?.intValue ?? 0
        if size > MAX_FILE_BYTES { throw Rejected("file-size") }
        let h = try FileHandle(forReadingFrom: file)
        defer { try? h.close() }
        let data: Data = ((try h.read(upToCount: MAX_FILE_BYTES + 1)) ?? nil) ?? Data()
        if data.count > MAX_FILE_BYTES { throw Rejected("file-size") }
        return data
    }

    /// ImageIO로 크기만 읽고, 줄여 해독한 픽셀(EXIF 방향 반영)을 흰 바탕에 그려 JPEG로 다시 인코딩한다.
    /// 새로 만든 CGImage만 쓰므로 EXIF·XMP·GPS·컨테이너 주석은 남지 않는다.
    public struct ImageIOCodec: Codec {
        public init() {}

        public func bounds(_ bytes: Data) throws -> Bounds {
            guard let src = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                  CGImageSourceGetCount(src) > 0,
                  let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return Bounds(-1, -1, nil) }
            let mime = (CGImageSourceGetType(src) as String?).flatMap { UTType($0)?.preferredMIMEType }
            let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? -1
            let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? -1
            return Bounds(w, h, mime)
        }

        public func sanitize(_ bytes: Data, sample: Int) throws -> Encoded {
            let b = try bounds(bytes)
            guard let src = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { throw Rejected("format") }
            let maxSide = max((b.width + sample - 1) / sample, (b.height + sample - 1) / sample)
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxSide,
                kCGImageSourceShouldCacheImmediately: true,
            ]
            guard let decoded = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else { throw Rejected("format") }
            var w = decoded.width
            var h = decoded.height
            if w <= 0 || h <= 0 { throw Rejected("pixels") }
            // 반올림으로 상한을 살짝 넘으면 한 번 더 줄인다.
            if max(w, h) > WorkspaceImage.MAX_DECODE_EDGE || Int64(w) * Int64(h) > WorkspaceImage.MAX_DECODE_PIXELS {
                let k = min(Double(WorkspaceImage.MAX_DECODE_EDGE) / Double(max(w, h)),
                            (Double(WorkspaceImage.MAX_DECODE_PIXELS) / (Double(w) * Double(h))).squareRoot())
                w = max(Int(Double(w) * k), 1)
                h = max(Int(Double(h) * k), 1)
            }
            guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { throw Rejected("pixels") }
            ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.interpolationQuality = .high
            ctx.draw(decoded, in: CGRect(x: 0, y: 0, width: w, height: h))
            guard let clean = ctx.makeImage() else { throw Rejected("pixels") }
            let out = NSMutableData()
            guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else {
                throw Rejected("output-size")
            }
            CGImageDestinationAddImage(dest, clean, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
            if !CGImageDestinationFinalize(dest) { throw Rejected("output-size") }
            if out.length > WorkspaceImage.MAX_OUTPUT_BYTES { throw Rejected("output-size") }
            return Encoded(out as Data, w, h)
        }
    }
}
