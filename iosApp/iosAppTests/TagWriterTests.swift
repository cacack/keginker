import Combine
import SharedLogic
import XCTest
@testable import KegInker

/// `TagWriter` around the shared `WriteController`, whose own tests cover
/// each way a write can end: what the iOS screen shows of it.
@MainActor
final class TagWriterTests: XCTestCase {
    private var shown: [TagWriter.State] = []
    private var watching: AnyCancellable?

    // The simulated tags are Waveshare, whichever display an earlier run chose.
    override func setUp() { UserDefaults.standard.removeObject(forKey: TagWriter.displayKey) }

    override func tearDown() { UserDefaults.standard.removeObject(forKey: TagWriter.displayKey) }

    private func writer() -> TagWriter {
        let writer = TagWriter()
        watching = writer.$state.sink { [weak self] in self?.shown.append($0) }
        return writer
    }

    private func view(_ writer: TagWriter) -> KotlinBooleanArray {
        KotlinBooleanArray(size: writer.display.viewWidth * writer.display.viewHeight)
    }

    func testTheChosenDisplayIsRememberedAndWritesNeedItsSize() {
        let writer = writer()
        XCTAssertEqual(writer.display, .waveshare2in7)

        writer.choose(.ezSign2in9)

        XCTAssertEqual(TagWriter().display, .ezSign2in9)
        XCTAssertEqual(view(writer).size, 128 * 296)
    }

    func testAWaveshareTagWhileAnEzSignDisplayIsChosenNamesTheWaveshareDisplays() async throws {
        let writer = writer()
        writer.choose(.ezSign4in2)

        writer.simulate(view: view(writer), testPattern: true, transport: SimulatedWaveshareTransport(commandDelayMs: 0))
        try await waitUntilDone(writer)

        guard case let .failed(message, _) = writer.state else { return XCTFail("Not failed: \(writer.state)") }
        XCTAssertEqual(message, WriteMessagesKt.chooseWaveshareMessage())
    }

    /// Waits for the write to end, as the screen does before enabling its buttons.
    private func waitUntilDone(_ writer: TagWriter) async throws {
        for _ in 0..<200 where writer.isBusy {
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertFalse(writer.isBusy, "The write didn't end")
    }

    func testWriteShowsProgressThenDone() async throws {
        let writer = writer()

        writer.simulate(view: view(writer), testPattern: true, transport: SimulatedWaveshareTransport(commandDelayMs: 0))
        try await waitUntilDone(writer)

        guard case let .done(report) = writer.state else { return XCTFail("Not done: \(writer.state)") }
        XCTAssertEqual(report.outcome, .succeeded)
        XCTAssertTrue(report.testPattern)
        XCTAssertTrue(shown.contains { if case let .writing(percent) = $0 { percent > 0 } else { false } }, "\(shown)")
    }

    func testLostTagFailsNamingTheStepWithAReportAndRetrySucceeds() async throws {
        let writer = writer()
        let losing = SimulatedWaveshareTransport(
            commandDelayMs: 0,
            failAfterCommands: KotlinInt(int: SimulatedWaveshareTransport.companion.FAIL_WHILE_SENDING_IMAGE)
        )

        writer.simulate(view: view(writer), testPattern: false, transport: losing)
        try await waitUntilDone(writer)

        guard case let .failed(message, report) = writer.state else { return XCTFail("Not failed: \(writer.state)") }
        XCTAssertTrue(message.contains("sending the image"), message)
        XCTAssertEqual(report?.outcome, .failed)
        XCTAssertEqual(report?.step, .sendData)

        writer.simulate(view: view(writer), testPattern: false, transport: SimulatedWaveshareTransport(commandDelayMs: 0))
        try await waitUntilDone(writer)

        guard case .done = writer.state else { return XCTFail("Retry not done: \(writer.state)") }
    }
}
