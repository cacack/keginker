import CoreNFC
import SharedLogic
import XCTest
@testable import KegInker

/// The iOS side of "Report a display": which tags iOS shows the probe, and
/// what each one can be sent.
@MainActor
final class DisplayProbeTests: XCTestCase {
    /// iOS only reports an ISO 7816 tag that accepts an AID in Info.plist, so it
    /// must list exactly the AIDs the probe tries.
    func testInfoPlistListsTheProbeAids() {
        let listed = Bundle.main.object(forInfoDictionaryKey: "com.apple.developer.nfc.readersession.iso7816.select-identifiers") as? [String]
        let probed = ProbeCommandsKt.PROBE_AIDS.map { aid in
            (SwiftInteropKt.nsData(bytes: aid) as Data).map { String(format: "%02X", $0) }.joined()
        }

        XCTAssertEqual(listed, probed)
    }

    func testUnsupportedMiFareTagKeepsAnNfcATransportForTheProbe() {
        let classified = classify(.miFare(FakeMiFareTag(family: .ultralight)), crcMode: .auto)

        let unsupported = try? XCTUnwrap(classified as? FoundTagUnsupported)
        XCTAssertEqual(unsupported?.kind, .nfcA)
        XCTAssertNotNil(unsupported?.openTransport)
    }

    func testUnsupportedIso7816TagKeepsAnIsoDepTransportForTheProbe() {
        let classified = classify(.iso7816(FakeIso7816Tag()), crcMode: .auto)

        XCTAssertEqual((classified as? FoundTagUnsupported)?.kind, .isoDep)
    }
}
