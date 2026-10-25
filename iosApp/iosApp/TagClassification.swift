import CoreNFC
import SharedLogic

/// Classifies a Core NFC tag as Android's `TagReader` does, without sending it
/// anything. A transport is opened here only for a supported tag; an
/// unsupported one keeps its opener for the display probe.
@MainActor
func classify(_ tag: NFCTag, crcMode: CrcMode) -> FoundTag {
    let info = Diagnostics.tagInfo(tag)
    switch tag {
    case let .miFare(miFare):
        return FoundTagKt.classifyNfcATag(info: info) { MiFareTransport(tag: miFare, crcMode: crcMode) }
    case let .iso7816(iso7816):
        return FoundTagKt.classifyIsoDepTag(info: info) { Iso7816Transport(tag: iso7816) }
    case let .iso15693(iso15693):
        return FoundTagKt.classifyNfcVTag(info: info) { Iso15693Transport(tag: iso15693) }
    default:
        return FoundTagUnsupported(info: info)
    }
}
