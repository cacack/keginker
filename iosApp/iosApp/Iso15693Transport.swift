import CoreNFC
import SharedLogic

/// `Transport` over Core NFC's `NFCISO15693Tag`: raw ISO 15693 frames, as
/// Android's `NfcV` takes and returns them. A command `[flags, command, data…]`
/// is split into `sendRequest`'s arguments, and the reply is rebuilt as the
/// response-flags byte followed by the data; Core NFC adds and checks the CRC.
///
/// A tag that answers with an ISO 15693 error code gets the error reply
/// `[01, code]`, as on Android, rather than a throw; any other failure throws.
///
/// Core NFC has no per-command timeout, so `timeoutMs` is ignored and the
/// session's own limits apply. Commands always go out on the main queue,
/// which is the session's queue, whatever thread Kotlin calls from.
final class Iso15693Transport: NSObject, Transport {
    private let tag: NFCISO15693Tag

    init(tag: NFCISO15693Tag) {
        self.tag = tag
    }

    func transceive(
        command: KotlinByteArray,
        timeoutMs: Int64,
        completionHandler: @escaping (KotlinByteArray?, (any Error)?) -> Void
    ) {
        let frame = SwiftInteropKt.nsData(bytes: command) as Data
        guard let request = Self.request(frame) else {
            completionHandler(nil, NSError(domain: "keginker", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Not an ISO 15693 frame (\(frame.count) bytes)",
            ]))
            return
        }
        let tag = self.tag
        DispatchQueue.main.async {
            NfcLog.log("> \(NfcLog.hex(frame))")
            tag.sendRequest(requestFlags: request.flags, commandCode: request.command, data: request.data) { result in
                switch result {
                case let .success((flags, data)):
                    let reply = Self.reply(flags: flags, data: data)
                    NfcLog.log("< \(NfcLog.hex(reply))")
                    completionHandler(SwiftInteropKt.kotlinBytes(data: reply), nil)
                case let .failure(error):
                    if let reply = Self.errorReply(error) {
                        NfcLog.log("< \(NfcLog.hex(reply))")
                        completionHandler(SwiftInteropKt.kotlinBytes(data: reply), nil)
                    } else {
                        NfcLog.log("! \(error.localizedDescription)")
                        completionHandler(nil, error)
                    }
                }
            }
        }
    }

    func close() {}

    /// Splits a raw frame `[flags, command, data…]` into `sendRequest`'s
    /// arguments, with nil data when there is none; nil if the frame is shorter than two bytes.
    static func request(_ frame: Data) -> (flags: Int, command: Int, data: Data?)? {
        let bytes = [UInt8](frame)
        guard bytes.count >= 2 else { return nil }
        return (Int(bytes[0]), Int(bytes[1]), bytes.count > 2 ? Data(bytes[2...]) : nil)
    }

    /// The reply as a raw frame: the response flags, then the data.
    static func reply(flags: NFCISO15693ResponseFlag, data: Data?) -> Data {
        Data([flags.rawValue]) + (data ?? Data())
    }

    /// The raw error reply `[01, code]` when [error] carries the tag's ISO 15693
    /// error code; nil for any other failure, such as the tag leaving the field.
    static func errorReply(_ error: any Error) -> Data? {
        guard let code = (error as NSError).userInfo[NFCISO15693TagResponseErrorKey] as? NSNumber else { return nil }
        return Data([NFCISO15693ResponseFlag.error.rawValue, code.uint8Value])
    }
}
