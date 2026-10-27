import SharedLogic
import SnapshotTesting
import SwiftUI
import XCTest
@testable import KegInker

/// The README screenshots (`docs/images/screenshots/ios.*.png`): the sample
/// label in `fixtures/screenshots`, loaded through `loadImage`, then
/// written. Runs only when `KEGINKER_SCREENSHOTS` is `record` or `verify`,
/// on the simulator the images were recorded on; see CONTRIBUTING.md.
@MainActor
final class ScreenshotTests: XCTestCase {
    /// Rendering differs between iOS versions and devices, so the images come from one.
    static let device = "iPhone 17e"
    static let os = "27.0"

    private let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    private let panel = WavesharePanel.epd2in7
    private var record = SnapshotTestingConfiguration.Record.never

    override func setUpWithError() throws {
        switch ProcessInfo.processInfo.environment["KEGINKER_SCREENSHOTS"] {
        case "record": record = .all
        case "verify": record = .never
        default: throw XCTSkip("Set KEGINKER_SCREENSHOTS to record or verify the README screenshots")
        }
        let device = ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"]
        let os = UIDevice.current.systemVersion
        guard device == Self.device, os == Self.os else {
            throw WrongSimulator(description: "Screenshots come from an \(Self.device) simulator on iOS \(Self.os), not \(device ?? "?") on iOS \(os)")
        }
        // A setting from an earlier run would add "Share details" after a write.
        UserDefaults.standard.set(false, forKey: offerReportsKey)
        // The README shows the Waveshare 2.7" label, whichever display an earlier run chose.
        UserDefaults.standard.removeObject(forKey: TagWriter.displayKey)
    }

    func testPreview() throws {
        try capture(TagWriter(), "1-preview")
    }

    func testWriting() throws {
        let writer = TagWriter()
        let percent = 60
        let chunks = percent * Int(panel.chunkCount) / Int(WriteMessagesKt.IMAGE_SENT_PROGRESS)
        writer.simulate(view: try sampleView(), testPattern: false, transport: HeldTransport(chunks: chunks))
        wait(until: writer.state == .writing(percent))
        try capture(writer, "2-writing")
    }

    func testDone() throws {
        let writer = TagWriter()
        writer.simulate(view: try sampleView(), testPattern: false, transport: SimulatedWaveshareTransport(commandDelayMs: 0))
        // The controller shows Done before the write settles, while the buttons are still disabled.
        wait(until: { if case .done = writer.state { !writer.isBusy } else { false } }())
        try capture(writer, "3-done")
    }

    private func sampleLabel() throws -> ColorImage {
        let data = try Data(contentsOf: root.appendingPathComponent("fixtures/screenshots/sample-label.png"))
        return try XCTUnwrap(loadImage(data))
    }

    /// The sample label as ContentView renders it, with its default dither.
    private func sampleView() throws -> KotlinByteArray {
        LabelDisplayKt.renderView(display: .waveshare2in7, image: try sampleLabel(), dither: DitherFloydSteinberg.shared, color: true)
    }

    private func capture(_ writer: TagWriter, _ name: String, file: StaticString = #filePath, line: UInt = #line) throws {
        let screen = UIHostingController(rootView: ContentView(writer: writer, source: try sampleLabel()))
        // The key window draws the toolbar's glass, as the app shows it; 2x keeps the files small.
        // The glass can shift by a shade between simulator boots, so a pixel matches within 2%.
        let image: Snapshotting<UIViewController, UIImage> = .image(
            on: .iPhone13, drawHierarchyInKeyWindow: true, perceptualPrecision: 0.98,
            traits: UITraitCollection(displayScale: 2)
        )
        // The snapshot is 16-bit extended-range sRGB, and the PNG saves it as
        // 8-bit sRGB. Compared before saving, the glass sometimes fails the 2%
        // on a frame whose PNG matches, so compare what the PNG holds.
        let asSaved = Snapshotting<UIViewController, UIImage>(pathExtension: image.pathExtension, diffing: image.diffing) {
            image.snapshot($0).map { UIImage(data: $0.pngData()!, scale: $0.scale)! }
        }
        let failure = verifySnapshot(
            of: screen,
            as: asSaved,
            named: name,
            record: record,
            snapshotDirectory: root.appendingPathComponent("docs/images/screenshots").path,
            testName: "ios"
        )
        // Recording reports every image it writes as a failure; only a mismatch is one here.
        if let failure, record == .never { XCTFail(failure, file: file, line: line) }
    }

    private func wait(until condition: @autoclosure () -> Bool, timeout: TimeInterval = 30, file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date(timeIntervalSinceNow: timeout)
        while !condition() && Date() < deadline {
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))
        }
        XCTAssertTrue(condition(), "Timed out", file: file, line: line)
    }
}

private struct WrongSimulator: Error, CustomStringConvertible {
    let description: String
}

/// A classic tag that accepts everything but holds the write once `chunks` image chunks are in.
private final class HeldTransport: NSObject, Transport {
    private let tag = SimulatedWaveshareTransport(commandDelayMs: 0)
    private let chunks: Int
    private var received = 0

    init(chunks: Int) { self.chunks = chunks }

    func transceive(
        command: KotlinByteArray,
        timeoutMs: Int64,
        completionHandler: @escaping (KotlinByteArray?, (any Error)?) -> Void
    ) {
        if command.get(index: 1) == 0x19 {
            defer { received += 1 }
            if received == chunks { return }
        }
        tag.transceive(command: command, timeoutMs: timeoutMs, completionHandler: completionHandler)
    }

    func close() {}
}
