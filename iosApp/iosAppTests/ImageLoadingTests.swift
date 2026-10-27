import SharedLogic
import XCTest
@testable import KegInker

/// Loads each fixture photo in `fixtures/photos` through `loadImage` and
/// checks its view against the golden beside it, pixel for pixel. The goldens
/// are shared with the Android loader test (`ImageLoadingTest` in sharedUI),
/// which writes any that are missing.
final class ImageLoadingTests: XCTestCase {
    private let fixtures = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("fixtures/photos")
    private let display = LabelDisplay.waveshare2in7

    func testExifRotatedPhotoComesInUpright() throws { try checkGolden("exif-rotated.jpg") }

    func testTransparencyIsWhite() throws { try checkGolden("transparent.png") }

    func testColoursBecomeRec601Greys() throws { try checkGolden("saturated.png") }

    func testWideImageIsCentreCropped() throws { try checkGolden("wide.png") }

    func testTallImageIsCentreCropped() throws { try checkGolden("tall.png") }

    func testTinyImageIsScaledUp() throws { try checkGolden("tiny.png") }

    func testUnreadableImageFails() {
        // ContentView shows "Couldn't open that image." when this is nil.
        XCTAssertNil(loadImage(Data("not an image".utf8)))
    }

    func testPhoto48MpStillLoads() throws {
        let photo = try Data(contentsOf: fixtures.appendingPathComponent("48mp.png"))
        XCTAssertNotNil(loadImage(photo))
    }

    func testImageOverPixelLimitFails() throws {
        let photo = try Data(contentsOf: fixtures.appendingPathComponent("over-pixel-limit.png"))
        XCTAssertNil(loadImage(photo))
    }

    private func checkGolden(_ name: String, file: StaticString = #filePath, line: UInt = #line) throws {
        let photo = fixtures.appendingPathComponent(name)
        let image = try XCTUnwrap(loadImage(try Data(contentsOf: photo)), file: file, line: line)
        let view = LabelDisplayKt.renderView(display: display, image: image, dither: DitherFloydSteinberg.shared, colour: true)
        let golden = photo.deletingPathExtension().appendingPathExtension("pbm")
        let expected = try readPbm(golden)

        XCTAssertEqual(Int(view.size), expected.count, "\(name): view size", file: file, line: line)
        let differing = expected.indices.filter { (view.get(index: Int32($0)) == PaletteKt.WHITE) != expected[$0] }.count
        XCTAssertEqual(differing, 0, "\(name): pixels that differ from \(golden.lastPathComponent)", file: file, line: line)
    }
}

/// Reads a plain (`P1`) PBM, as the Android test writes it, into a view
/// (`true` is white; PBM's 1 is black).
private func readPbm(_ url: URL) throws -> [Bool] {
    let lines = try String(contentsOf: url, encoding: .ascii).split(separator: "\n")
    guard lines.first == "P1" else { throw CocoaError(.fileReadCorruptFile) }
    return lines.dropFirst(2).joined().map { $0 == "0" }
}
