import SharedLogic
import UIKit

/// Longest side photos are decoded to; far more than any panel needs.
private let maxSide: CGFloat = 1024

/// Decodes image data into greyscale, upright and downsampled, then crops
/// and scales it to [panel]'s view. Transparency is composited over white.
func loadGrayImage(_ data: Data, panel: WavesharePanel) -> GrayImage? {
    guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
    let scale = min(1, maxSide / max(image.size.width, image.size.height))
    let width = max(1, Int(image.size.width * scale))
    let height = max(1, Int(image.size.height * scale))

    var pixels = [UInt8](repeating: 255, count: width * height)
    let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(
            data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else { return false }
        // UIKit drawing applies the image's orientation; flip into its coordinate space.
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(context)
        image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        UIGraphicsPopContext()
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
