import CoreNFC
import XCTest
@testable import KegInker

/// Checks `Iso7816Transport` against the `Transport` contract (see its KDoc
/// in sharedLogic), with a fake `NFCISO7816Tag`.
final class Iso7816TransportTests: XCTestCase {
    private let selectApdu: [UInt8] = [0x00, 0xA4, 0x04, 0x00, 0x02, 0xE1, 0x04]

    func testReplyEndsWithTheStatusWord() throws {
        let tag = FakeIso7816Tag()
        tag.reply = (Data([0x01, 0x02]), 0x90, 0x00)

        let reply = try transceive(Iso7816Transport(tag: tag), selectApdu).get()

        XCTAssertEqual(reply, [0x01, 0x02, 0x90, 0x00])
    }

    func testReplyWithNoDataIsJustTheStatusWord() throws {
        let tag = FakeIso7816Tag()
        tag.reply = (Data(), 0x6A, 0x82)

        let reply = try transceive(Iso7816Transport(tag: tag), selectApdu).get()

        XCTAssertEqual(reply, [0x6A, 0x82])
    }

    func testSendsTheCommandAsOneApdu() throws {
        let tag = FakeIso7816Tag()

        _ = try transceive(Iso7816Transport(tag: tag), selectApdu).get()

        XCTAssertEqual(tag.sent.count, 1)
        let apdu = try XCTUnwrap(tag.sent.first)
        XCTAssertEqual([apdu.instructionClass, apdu.instructionCode, apdu.p1Parameter, apdu.p2Parameter], [0x00, 0xA4, 0x04, 0x00])
        XCTAssertEqual(apdu.data, Data([0xE1, 0x04]))
    }

    func testRejectsAnInvalidApduWithoutSendingIt() {
        let tag = FakeIso7816Tag()

        let result = transceive(Iso7816Transport(tag: tag), [0x00, 0xA4])

        XCTAssertThrowsError(try result.get())
        XCTAssertTrue(tag.sent.isEmpty)
    }

    func testFailsWhenTheTagIsLost() {
        let tag = FakeIso7816Tag()
        tag.error = tagLost

        let result = transceive(Iso7816Transport(tag: tag), selectApdu)

        XCTAssertThrowsError(try result.get()) { error in
            XCTAssertEqual((error as? NFCReaderError)?.code, .readerTransceiveErrorTagConnectionLost)
        }
    }

    func testSendsOnTheMainThread() throws {
        let tag = FakeIso7816Tag()
        let transport = Iso7816Transport(tag: tag)

        _ = try transceive(transport, selectApdu).get()
        _ = try transceive(transport, selectApdu).get()

        XCTAssertEqual(tag.sentOnMainThread, [true, true])
    }

    func testClosesMoreThanOnce() {
        let transport = Iso7816Transport(tag: FakeIso7816Tag())

        transport.close()
        transport.close()
    }
}
