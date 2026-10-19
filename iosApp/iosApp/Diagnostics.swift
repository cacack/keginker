import CoreNFC
import SharedLogic
import UIKit

/// What goes into a diagnostics report on iOS, besides the write itself.
enum Diagnostics {
    static let appInfo = AppInfo(
        version: BuildInfo.shared.VERSION,
        buildType: isDebug ? "debug" : "release",
        commit: BuildInfo.shared.COMMIT
    )

    /// The hardware identifier (such as `iPhone16,1`) rather than a marketing
    /// name, which iOS doesn't expose.
    static func deviceInfo() -> DeviceInfo {
        var system = utsname()
        uname(&system)
        let model = withUnsafeBytes(of: &system.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return DeviceInfo(
            manufacturer: "Apple",
            model: model,
            os: "iOS \(UIDevice.current.systemVersion)",
            nfcEnabled: NFCTagReaderSession.readingAvailable
        )
    }

    /// What Core NFC reports about [tag]: its type, with the MIFARE family or
    /// ISO 7816 AID. Core NFC doesn't expose ATQA or SAK.
    static func tagInfo(_ tag: NFCTag) -> TagInfo {
        let (technology, uid, historicalBytes): (String, Data, Data?) = switch tag {
        case let .miFare(tag): ("MIFARE (\(NfcLog.name(tag.mifareFamily)))", tag.identifier, nil)
        case let .iso7816(tag): ("ISO 7816 (AID \(tag.initialSelectedAID))", tag.identifier, tag.historicalBytes)
        case let .iso15693(tag): ("ISO 15693", tag.identifier, nil)
        case let .feliCa(tag): ("FeliCa", tag.currentIDm, nil)
        @unknown default: ("unknown", Data(), nil)
        }
        return TagInfo(
            technologies: [technology],
            uid: SwiftInteropKt.kotlinBytes(data: uid),
            atqa: nil,
            sak: nil,
            historicalBytes: historicalBytes.map { SwiftInteropKt.kotlinBytes(data: $0) }
        )
    }

    private static var isDebug: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
}
