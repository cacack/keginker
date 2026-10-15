import ImageIO
import SharedLogic
import UIKit

/// Longest side photos are decoded to; far more than any panel needs.
private let maxSide = 1024

/// Decodes image data into greyscale, upright and downsampled while
/// decoding, then crops and scales it to [panel]'s view. Transparency is
/// composited over white.
func loadGrayImage(_ data: Data, panel: WavesharePanel) -> GrayImage? {
    let options = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxSide,
    ] as CFDictionary
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
    let width = image.width
    let height = image.height

    var pixels = [UInt8](repeating: 255, count: width * height)
    let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(
            data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    guard drawn else { return nil }
    let gray = GrayImage(width: Int32(width), height: Int32(height), pixels: SwiftInteropKt.kotlinBytes(data: Data(pixels)))
    return gray.fill(width: panel.viewWidth, height: panel.viewHeight)
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
