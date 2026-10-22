import ImageIO
import SharedLogic
import UIKit

/// Longest side photos are decoded to; far more than any panel needs.
private let maxSide = 1024

/// Most pixels an image may have, checked from its header before decoding:
/// above the 108 MP of the largest common phone cameras. ImageIO may decode
/// a PNG or GIF in full before downsampling it. Android has the same limit.
private let maxPixels = 120_000_000

/// Decodes image data, upright and downsampled while decoding, then crops
/// and scales it to [panel]'s view. Like Android, it hands non-premultiplied
/// ARGB to `GrayImage.fromArgb`, which converts to grey and composites
/// transparency over white, so a photo makes the same label on both.
/// Images over `maxPixels` fail.
func loadGrayImage(_ data: Data, panel: WavesharePanel) -> GrayImage? {
    let options = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxSide,
    ] as CFDictionary
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let header = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let pixelWidth = header[kCGImagePropertyPixelWidth] as? Int,
          let pixelHeight = header[kCGImagePropertyPixelHeight] as? Int,
          pixelWidth * pixelHeight <= maxPixels,
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options),
          let srgb = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
    let width = image.width
    let height = image.height

    // Core Graphics draws only premultiplied RGBA, as Android bitmaps hold it.
    var rgba = [UInt8](repeating: 0, count: width * height * 4)
    let drawn: Bool = rgba.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(
            data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: srgb, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    guard drawn else { return nil }
    let argb = (0..<width * height).map { i in
        argbPixel(r: rgba[i * 4], g: rgba[i * 4 + 1], b: rgba[i * 4 + 2], a: rgba[i * 4 + 3])
    }
    let pixels = argb.withUnsafeBytes { SwiftInteropKt.kotlinInts(data: Data($0)) }
    let gray = GrayImage.companion.fromArgb(width: Int32(width), height: Int32(height), argb: pixels)
    return gray.fill(width: panel.viewWidth, height: panel.viewHeight)
}

/// A premultiplied RGBA pixel as non-premultiplied `0xAARRGGBB`, rounding
/// to nearest.
private func argbPixel(r: UInt8, g: UInt8, b: UInt8, a: UInt8) -> UInt32 {
    let alpha = UInt32(a)
    func unpremultiply(_ c: UInt8) -> UInt32 { alpha == 0 ? 0 : min(255, (UInt32(c) * 255 + alpha / 2) / alpha) }
    return alpha << 24 | unpremultiply(r) << 16 | unpremultiply(g) << 8 | unpremultiply(b)
}

/// A black/white view image (`true` is white) as a CGImage for previewing.
func previewImage(_ view: KotlinBooleanArray, width: Int) -> CGImage? {
    let count = Int(view.size)
    var pixels = [UInt8](repeating: 0, count: count)
    for i in 0..<count where view.get(index: Int32(i)) { pixels[i] = 255 }
    guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
    return CGImage(
        width: width, height: count / width, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
        space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
        provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
    )
}
