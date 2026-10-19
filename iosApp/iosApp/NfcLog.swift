import CoreNFC
import os

/// Debug-only NFC log, shown in Xcode's console (and Console.app) under the
/// `nfc` category. Frames carry the user's image, so release builds log nothing.
enum NfcLog {
    #if DEBUG
    private static let logger = Logger(subsystem: "io.github.cacack.keginker", category: "nfc")
    #endif

    static func log(_ message: @autoclosure () -> String) {
        #if DEBUG
        let text = message()
        logger.debug("\(text, privacy: .public)")
        #endif
    }

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined(separator: " ")
    }

    static func describe(_ tag: NFCTag) -> String {
        switch tag {
        case let .miFare(tag): "MIFARE family=\(name(tag.mifareFamily)) uid=\(hex(tag.identifier))"
        case let .iso7816(tag):
            "ISO 7816 uid=\(hex(tag.identifier)) aid=\(tag.initialSelectedAID)"
                + " historical=\(tag.historicalBytes.map(hex) ?? "none")"
                + " appData=\(tag.applicationData.map(hex) ?? "none")"
        case let .iso15693(tag): "ISO 15693 uid=\(hex(tag.identifier))"
        case let .feliCa(tag): "FeliCa idm=\(hex(tag.currentIDm))"
        @unknown default: "unknown tag type"
        }
    }

    static func name(_ family: NFCMiFareFamily) -> String {
        switch family {
        case .ultralight: "ultralight"
        case .plus: "plus"
        case .desfire: "desfire"
        case .unknown: "unknown"
        @unknown default: "other(\(family.rawValue))"
        }
    }
}
