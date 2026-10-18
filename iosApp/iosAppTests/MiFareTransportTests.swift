import CoreNFC
import XCTest
@testable import KegInker

/// Checks `MiFareTransport` against the `Transport` contract (see its KDoc
/// in sharedLogic), with a fake `NFCMiFareTag`.
final class MiFareTransportTests: XCTestCase {
    /// MIFARE Ultralight READ 0, and its CRC_A as captured on the air.
    private let read0: [UInt8] = [0x30, 0x00]
    private let read0Crc: [UInt8] = [0x02, 0xA8]

    private func sentFrame(family: NFCMiFareFamily, crcMode: CrcMode) throws -> [UInt8] {
        let tag = FakeMiFareTag(family: family)
        _ = try transceive(MiFareTransport(tag: tag, crcMode: crcMode), read0).get()
        XCTAssertEqual(tag.sent.count, 1)
        return [UInt8](try XCTUnwrap(tag.sent.first))
    }

    func testAutoAppendsCrcForUltralight() throws {
        XCTAssertEqual(try sentFrame(family: .ultralight, crcMode: .auto), read0 + read0Crc)
    }

    func testAutoLeavesOtherFamiliesUnframed() throws {
        for family: NFCMiFareFamily in [.unknown, .plus, .desfire] {
            XCTAssertEqual(try sentFrame(family: family, crcMode: .auto), read0, "family \(family.rawValue)")
        }
    }

    func testOnAlwaysAppendsCrc() throws {
        for family: NFCMiFareFamily in [.unknown, .ultralight, .plus, .desfire] {
            XCTAssertEqual(try sentFrame(family: family, crcMode: .on), read0 + read0Crc, "family \(family.rawValue)")
        }
    }

    func testOffNeverAppendsCrc() throws {
        for family: NFCMiFareFamily in [.unknown, .ultralight, .plus, .desfire] {
            XCTAssertEqual(try sentFrame(family: family, crcMode: .off), read0, "family \(family.rawValue)")
        }
    }

    func testReturnsTheReplyUnchanged() throws {
        let tag = FakeMiFareTag(family: .ultralight)
        tag.reply = Data([0x04, 0x01, 0x02, 0x03, 0x0A])

        let reply = try transceive(MiFareTransport(tag: tag, crcMode: .auto), read0).get()

        XCTAssertEqual(reply, [0x04, 0x01, 0x02, 0x03, 0x0A])
    }

    func testFailsWhenTheTagIsLost() {
        let tag = FakeMiFareTag(family: .ultralight)
        tag.error = tagLost

        let result = transceive(MiFareTransport(tag: tag, crcMode: .auto), read0)

        XCTAssertThrowsError(try result.get()) { error in
            XCTAssertEqual((error as? NFCReaderError)?.code, .readerTransceiveErrorTagConnectionLost)
        }
    }

    func testSendsOnTheMainThread() throws {
        let tag = FakeMiFareTag(family: .unknown)
        let transport = MiFareTransport(tag: tag, crcMode: .off)

        _ = try transceive(transport, read0).get()
        _ = try transceive(transport, read0).get()

        XCTAssertEqual(tag.sentOnMainThread, [true, true])
    }

    func testClosesMoreThanOnce() {
        let transport = MiFareTransport(tag: FakeMiFareTag(family: .ultralight), crcMode: .auto)

        transport.close()
        transport.close()
    }
}
