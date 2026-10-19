package io.github.cacack.keginker.nfc

import io.github.cacack.keginker.waveshare.isWaveshareIsoDepTag
import io.github.cacack.keginker.waveshare.isWaveshareTag

/** A tag the phone has just found, classified by protocol family. */
sealed interface FoundTag {
    /** What the phone learned about the tag, for reports; null if not captured. */
    val info: TagInfo?

    /**
     * A tag KegInker can write; write it with [ImageWriter], which uses the
     * right protocol for each kind. The receiver owns [transport] and must close it.
     */
    sealed interface Writable : FoundTag {
        val transport: Transport
    }

    /** A Waveshare classic board: raw NFC-A frames. */
    class Waveshare(override val transport: Transport, override val info: TagInfo?) : Writable {
        /** For simulated tags and tests, which have no [TagInfo]. */
        constructor(transport: Transport) : this(transport, null)
    }

    /** A Waveshare Rev2.2 board: ISO-DEP with a YZRCOS chip. */
    class WaveshareIsoDep(override val transport: Transport, override val info: TagInfo?) : Writable {
        /** For simulated tags and tests, which have no [TagInfo]. */
        constructor(transport: Transport) : this(transport, null)
    }

    /** A tag no supported protocol recognises; [info] always has at least its UID. */
    class Unsupported(override val info: TagInfo) : FoundTag {
        val uid: ByteArray get() = info.uid
    }
}

/** The protocol a report names for this tag. */
val FoundTag.protocolName: String get() = when (this) {
    is FoundTag.Waveshare -> "Waveshare classic (NFC-A)"
    is FoundTag.WaveshareIsoDep -> "Waveshare Rev2.2 (ISO-DEP)"
    is FoundTag.Unsupported -> "unsupported"
}

/**
 * Classifies an NFC-A tag that isn't ISO-DEP by its UID, without sending it
 * any command; the result carries [info] for reports. [openTransport] is
 * called only for a supported tag.
 */
fun classifyNfcATag(info: TagInfo, openTransport: () -> Transport): FoundTag =
    if (isWaveshareTag(info.uid)) FoundTag.Waveshare(openTransport(), info) else FoundTag.Unsupported(info)

/**
 * Classifies an ISO-DEP tag by its ATS historical bytes (none counts as
 * empty), without sending it any command; the result carries [info] for
 * reports. [openTransport] is called only for a supported tag.
 */
fun classifyIsoDepTag(info: TagInfo, openTransport: () -> Transport): FoundTag =
    if (isWaveshareIsoDepTag(info.historicalBytes ?: ByteArray(0))) {
        FoundTag.WaveshareIsoDep(openTransport(), info)
    } else {
        FoundTag.Unsupported(info)
    }
