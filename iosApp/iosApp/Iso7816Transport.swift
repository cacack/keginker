import CoreNFC
import SharedLogic

/// `Transport` over Core NFC's `NFCISO7816Tag`: ISO-DEP APDUs. Replies end
/// with the status word (SW1 SW2), as Android's `IsoDep` returns them.
///
/// Core NFC has no per-command timeout, so `timeoutMs` is ignored and the
/// session's own limits apply. Commands always go out on the main queue,
/// which is the session's queue, whatever thread Kotlin calls from.
final class Iso7816Transport: NSObject, Transport {
    private let tag: NFCISO7816Tag

    init(tag: NFCISO7816Tag) {
        self.tag = tag
    }

    func transceive(
        command: KotlinByteArray,
        timeoutMs: Int64,
        completionHandler: @escaping (KotlinByteArray?, (any Error)?) -> Void
    ) {
        let packet = SwiftInteropKt.nsData(bytes: command) as Data
        guard let apdu = NFCISO7816APDU(data: packet) else {
            completionHandler(nil, NSError(domain: "tagpaper", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Not a valid APDU (header \(NfcLog.hex(packet.prefix(5))), \(packet.count) bytes)",
            ]))
            return
        }
        let tag = self.tag
        DispatchQueue.main.async {
            NfcLog.log("> \(NfcLog.hex(packet))")
            tag.sendCommand(apdu: apdu) { data, sw1, sw2, error in
                if let error {
                    NfcLog.log("! \(error.localizedDescription)")
                    completionHandler(nil, error)
                } else {
                    let reply = data + [sw1, sw2]
                    NfcLog.log("< \(NfcLog.hex(reply))")
                    completionHandler(SwiftInteropKt.kotlinBytes(data: reply), nil)
                }
            }
        }
    }

    func close() {}
}
