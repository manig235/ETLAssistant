// Writes an upright copy of a screenshot PNG: applies its EXIF orientation to the
// pixels and saves RGB without alpha or metadata (App Store Connect rejects alpha).
// XCTest stores iPad landscape screenshots as portrait pixels plus an orientation tag.
// usage: ios-upright-png in.png out.png
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write(Data("usage: ios-upright-png in.png out.png\n".utf8))
    exit(2)
}
let input = URL(fileURLWithPath: args[1]) as CFURL
let output = URL(fileURLWithPath: args[2]) as CFURL
guard let source = CGImageSourceCreateWithURL(input, nil),
      let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
      let width = props[kCGImagePropertyPixelWidth] as? Int,
      let height = props[kCGImagePropertyPixelHeight] as? Int else { exit(1) }
let options: [CFString: Any] = [
    kCGImageSourceCreateThumbnailFromImageAlways: true,
    kCGImageSourceCreateThumbnailWithTransform: true,
    kCGImageSourceThumbnailMaxPixelSize: max(width, height),
    kCGImageSourceShouldCacheImmediately: true,
]
guard let rotated = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
      let context = CGContext(data: nil, width: rotated.width, height: rotated.height, bitsPerComponent: 8,
                              bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { exit(1) }
context.draw(rotated, in: CGRect(x: 0, y: 0, width: rotated.width, height: rotated.height))
guard let upright = context.makeImage(),
      let destination = CGImageDestinationCreateWithURL(output, UTType.png.identifier as CFString, 1, nil) else { exit(1) }
CGImageDestinationAddImage(destination, upright, nil)
exit(CGImageDestinationFinalize(destination) ? 0 : 1)
