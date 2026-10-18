import CoreNFC
import SharedLogic
import XCTest

/// Base for fake Core NFC tags: the parts of `NFCTag` and `NFCNDEFTag` that
/// the transports never use.
class FakeTag: NSObject, __NFCTag, NFCNDEFTag {
    /// Whether each command reached the tag on the main thread, in order.
    private(set) var sentOnMainThread: [Bool] = []

    var type: __NFCTagType { fatalError("unused") }
    weak var session: (any NFCReaderSessionProtocol)? { nil }
    var isAvailable: Bool { true }

    func asNFCISO15693Tag() -> (any NFCISO15693Tag)? { nil }
    func asNFCISO7816Tag() -> (any NFCISO7816Tag)? { nil }
    func asNFCFeliCaTag() -> (any NFCFeliCaTag)? { nil }
    func asNFCMiFareTag() -> (any NFCMiFareTag)? { nil }

    func queryNDEFStatus(completionHandler: @escaping (NFCNDEFStatus, Int, (any Error)?) -> Void) { fatalError("unused") }
    func readNDEF(completionHandler: @escaping (NFCNDEFMessage?, (any Error)?) -> Void) { fatalError("unused") }
    func writeNDEF(_ ndefMessage: NFCNDEFMessage, completionHandler: @escaping ((any Error)?) -> Void) { fatalError("unused") }
    func writeLock(completionHandler: @escaping ((any Error)?) -> Void) { fatalError("unused") }

    static var supportsSecureCoding: Bool { false }
    func encode(with coder: NSCoder) { fatalError("unused") }
    override init() { super.init() }
    required init?(coder: NSCoder) { fatalError("unused") }
    func copy(with zone: NSZone? = nil) -> Any { self }

    @objc var identifier: Data { Data([0x04, 0x01, 0x02, 0x03]) }
    @objc var historicalBytes: Data? { nil }

    func recordSend() {
        sentOnMainThread.append(Thread.isMainThread)
    }
}

/// Fake ISO 7816 tag: answers every APDU with `reply` (data, SW1, SW2), or fails with `error`.
final class FakeIso7816Tag: FakeTag, NFCISO7816Tag {
    var reply: (data: Data, sw1: UInt8, sw2: UInt8) = (Data(), 0x90, 0x00)
    var error: (any Error)?
    private(set) var sent: [NFCISO7816APDU] = []

    var initialSelectedAID: String { "" }
    var applicationData: Data? { nil }
    var proprietaryApplicationDataCoding: Bool { false }
    var supportsPACE: Bool { false }

    func sendCommand(apdu: NFCISO7816APDU, completionHandler: @escaping (Data, UInt8, UInt8, (any Error)?) -> Void) {
        recordSend()
        sent.append(apdu)
        if let error {
            completionHandler(Data(), 0, 0, error)
        } else {
            completionHandler(reply.data, reply.sw1, reply.sw2, nil)
        }
    }
}

/// Fake MIFARE tag of `family`: answers every frame with `reply`, or fails with `error`.
final class FakeMiFareTag: FakeTag, NFCMiFareTag {
    let mifareFamily: NFCMiFareFamily
    var reply = Data()
    var error: (any Error)?
    private(set) var sent: [Data] = []

    init(family: NFCMiFareFamily) {
        mifareFamily = family
        super.init()
    }

    required init?(coder: NSCoder) { fatalError("unused") }

    func sendMiFareCommand(commandPacket command: Data, completionHandler: @escaping (Data, (any Error)?) -> Void) {
        recordSend()
        sent.append(command)
        completionHandler(error == nil ? reply : Data(), error)
    }

    func sendMiFareISO7816Command(_ apdu: NFCISO7816APDU, completionHandler: @escaping (Data, UInt8, UInt8, (any Error)?) -> Void) {
        fatalError("unused")
    }
}

/// The tag-lost error Core NFC reports when the tag leaves the field.
let tagLost = NFCReaderError(.readerTransceiveErrorTagConnectionLost)

extension XCTestCase {
    /// Calls `transport` from a background thread, as Kotlin coroutines may,
    /// and waits for its reply.
    func transceive(_ transport: any Transport, _ command: [UInt8]) -> Result<[UInt8], any Error> {
        let done = expectation(description: "transceive completes")
        var result: Result<[UInt8], any Error>?
        DispatchQueue.global().async {
            transport.transceive(command: SwiftInteropKt.kotlinBytes(data: Data(command)), timeoutMs: 1000) { reply, error in
                if let reply {
                    result = .success([UInt8](SwiftInteropKt.nsData(bytes: reply) as Data))
                } else {
                    result = .failure(error!)
                }
                done.fulfill()
            }
        }
        wait(for: [done], timeout: 5)
        return result!
    }
}
