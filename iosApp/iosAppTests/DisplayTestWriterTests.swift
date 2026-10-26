import CoreNFC
import SharedLogic
import SwiftUI
import XCTest
@testable import KegInker

/// The iOS side of the test writes: nothing reaches the display before the
/// user starts them from the confirmation, and the probed display is written after.
@MainActor
final class DisplayTestWriterTests: XCTestCase {
    /// A 1-bit EZ Sign "Get Device Information" reply.
    private let deviceInfoReply = Data([0xA0, 0x07, 0x00, 0x47, 0x20, 0x01, 0x2C, 0x01, 0x90, 0x90, 0x00])

    private func writer(for tag: FakeIso7816Tag) throws -> DisplayTestWriter {
        let parse = EzSignDeviceInfoKt.parseEzSignDeviceInfo(reply: SwiftInteropKt.kotlinBytes(data: deviceInfoReply))
        let info = try XCTUnwrap(parse as? EzSignDeviceInfoParseDecoded).info
        let result = ProbeResult(
            tag: Diagnostics.tagInfo(.iso7816(tag)), protocol: "EZ Sign", kind: .isoDep,
            acceptedAids: [], capabilityContainer: nil, ndef: nil, note: nil, exchanges: [],
            version: nil, signature: nil, systemInfo: nil, memory: nil, memoryAddress: 0,
            vendorQueriesListed: nil, vendorQueriesSent: 0, vendorPassSkipped: nil,
            deviceInfo: info, deviceInfoNotDecoded: nil
        )
        let controller = try XCTUnwrap(TestWriteControllerKt.testWriteControllerFor(result: result))
        return DisplayTestWriter(result: result, controller: controller, crcMode: .auto)
    }

    func testNothingIsSentBeforeTheOptIn() async throws {
        let tag = FakeIso7816Tag()
        let writer = try writer(for: tag)

        await writer.handle(classify(.iso7816(tag), crcMode: .auto), session: nil)

        XCTAssertTrue(tag.sent.isEmpty)
        XCTAssertTrue(writer.state is TestWriteStateOffered)
    }

    func testTheConfirmationWritesNothingUntilStarted() async throws {
        let tag = FakeIso7816Tag()
        let writer = try writer(for: tag)
        let screen = UIHostingController(rootView: NavigationStack { TestWriteView(writer: writer) })
        screen.view.layoutIfNeeded()

        await writer.handle(classify(.iso7816(tag), crcMode: .auto), session: nil)
        // Back from the confirmation, as the screen's onDisappear does.
        writer.finish()

        XCTAssertTrue(tag.sent.isEmpty)
        XCTAssertTrue(writer.controller.state.value is TestWriteStateOffered)

        writer.start()
        await writer.handle(classify(.iso7816(tag), crcMode: .auto), session: nil)

        XCTAssertFalse(tag.sent.isEmpty)
    }

    func testTheProbedDisplayIsWrittenAfterTheOptIn() async throws {
        let tag = FakeIso7816Tag()
        let writer = try writer(for: tag)
        writer.controller.optIn()

        await writer.handle(classify(.iso7816(tag), crcMode: .auto), session: nil)

        XCTAssertFalse(tag.sent.isEmpty)
        XCTAssertFalse(writer.controller.state.value is TestWriteStateWaiting)
    }

    func testFinishingKeepsTheRecords() async throws {
        let tag = FakeIso7816Tag()
        let writer = try writer(for: tag)
        writer.controller.optIn()
        await writer.handle(classify(.iso7816(tag), crcMode: .auto), session: nil)

        writer.finish()

        let done = try XCTUnwrap(writer.controller.state.value as? TestWriteStateDone)
        XCTAssertEqual(done.records.count, 1)
    }

    func testTheReportAddsTheRecordsWhenTheTestWritesEnd() async throws {
        let tag = FakeIso7816Tag()
        let writer = try writer(for: tag)
        let offered = writer.report.text
        writer.controller.optIn()
        await writer.handle(classify(.iso7816(tag), crcMode: .auto), session: nil)
        await caughtUp(writer)

        XCTAssertFalse(writer.state is TestWriteStateDone)
        XCTAssertEqual(writer.report.text, offered, "Steps before the end leave the report as it was")

        writer.finish()
        await caughtUp(writer)

        XCTAssertNotEqual(writer.report.text, offered)
    }

    func testADiscardedWriterIsFreed() throws {
        weak var discarded: DisplayTestWriter?
        try autoreleasepool {
            let writer = try writer(for: FakeIso7816Tag())
            discarded = writer
        }

        XCTAssertNil(discarded, "Its deinit closes the watcher")
    }

    /// Waits until [writer] shows its controller's state, which reaches it on the main queue.
    private func caughtUp(_ writer: DisplayTestWriter) async {
        let caughtUp = { writer.state === (writer.controller.state.value as AnyObject) }
        for _ in 0..<100 where !caughtUp() {
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(caughtUp())
    }
}
