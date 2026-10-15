import CoreNFC
import SharedLogic

/// Whether commands get a CRC_A appended. Apple's docs require it for the
/// MIFARE Ultralight family; how iOS classifies Waveshare tags is unverified (#10).
enum CrcMode: String, CaseIterable, Identifiable {
    case auto, on, off
    var id: Self { self }
}

/// `Transport` over Core NFC's `NFCMiFareTag`: raw ISO 14443-3A frames.
///
/// Core NFC has no per-command timeout, so `timeoutMs` is ignored and the
/// session's own limits apply. Replies arrive on the session's queue.
final class MiFareTransport: NSObject, Transport {
    private let tag: NFCMiFareTag
    let appendsCrc: Bool

    init(tag: NFCMiFareTag, crcMode: CrcMode) {
        self.tag = tag
        switch crcMode {
        case .auto: appendsCrc = tag.mifareFamily == .ultralight
        case .on: appendsCrc = true
        case .off: appendsCrc = false
        }
    }

    func transceive(
        command: KotlinByteArray,
        timeoutMs: Int64,
        completionHandler: @escaping (KotlinByteArray?, (any Error)?) -> Void
    ) {
        var packet = SwiftInteropKt.nsData(bytes: command) as Data
        if appendsCrc {
            packet.append(SwiftInteropKt.nsData(bytes: CrcAKt.crcA(data: command)) as Data)
        }
        tag.sendMiFareCommand(commandPacket: packet) { reply, error in
            if let error {
                completionHandler(nil, error)
            } else {
                completionHandler(SwiftInteropKt.kotlinBytes(data: reply), nil)
            }
        }
    }

    func close() {}
}
