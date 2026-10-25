import CoreNFC
import XCTest
@testable import KegInker

/// Checks how `Iso15693Transport` turns raw ISO 15693 frames into Core NFC's
/// `sendRequest` arguments and its replies back into raw frames, as Android's
/// `NfcV` returns them.
final class Iso15693TransportTests: XCTestCase {
    func testSplitsAFrameIntoFlagsCommandAndData() throws {
        let request = try XCTUnwrap(Iso15693Transport.request(Data([0x02, 0x20, 0x05])))

        XCTAssertEqual(request.flags, 0x02)
        XCTAssertEqual(request.command, 0x20)
        XCTAssertEqual(request.data, Data([0x05]))
    }

    func testAFrameWithNoDataHasNilData() throws {
        let request = try XCTUnwrap(Iso15693Transport.request(Data([0x02, 0x2B])))

        XCTAssertEqual(request.command, 0x2B)
        XCTAssertNil(request.data)
    }

    func testRejectsAFrameWithoutACommand() {
        XCTAssertNil(Iso15693Transport.request(Data([0x02])))
    }

    func testReplyStartsWithTheResponseFlags() {
        XCTAssertEqual(Iso15693Transport.reply(flags: [], data: Data([0xE1, 0x40])), Data([0x00, 0xE1, 0x40]))
        XCTAssertEqual(Iso15693Transport.reply(flags: .error, data: nil), Data([0x01]))
    }

    func testTagErrorCodeBecomesAnErrorReply() {
        let error = NSError(domain: NFCErrorDomain, code: 102, userInfo: [NFCISO15693TagResponseErrorKey: 0x10])

        XCTAssertEqual(Iso15693Transport.errorReply(error), Data([0x01, 0x10]))
    }

    func testOtherFailuresStayErrors() {
        XCTAssertNil(Iso15693Transport.errorReply(tagLost))
    }
}
