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
        constructor(transport: Transport) : this(transport, null)
    }

    /** A Waveshare Rev2.2 board: ISO-DEP with a YZRCOS chip. */
    class WaveshareIsoDep(override val transport: Transport, override val info: TagInfo?) : Writable {
        constructor(transport: Transport) : this(transport, null)
    }

    /** A tag no supported protocol recognises. When [info] is captured, it carries the same [uid]. */
    class Unsupported(val uid: ByteArray, override val info: TagInfo?) : FoundTag {
        constructor(uid: ByteArray) : this(uid, null)

        init {
            require(info == null || info.uid.contentEquals(uid)) { "info.uid must match uid" }
        }
    }
}

/** The protocol a report names for this tag. */
val FoundTag.protocolName: String get() = when (this) {
    is FoundTag.Waveshare -> "Waveshare classic (NFC-A)"
    is FoundTag.WaveshareIsoDep -> "Waveshare Rev2.2 (ISO-DEP)"
    is FoundTag.Unsupported -> "unsupported"
}

/**
 * Classifies an NFC-A tag that isn't ISO-DEP by its [uid], without sending it
 * any command. [openTransport] is called only for a supported tag.
 */
fun classifyNfcATag(uid: ByteArray, openTransport: () -> Transport): FoundTag =
    classifyNfcA(uid, null, openTransport)

/** Like the `uid` overload, and the result carries [info] for reports. */
fun classifyNfcATag(info: TagInfo, openTransport: () -> Transport): FoundTag =
    classifyNfcA(info.uid, info, openTransport)

/**
 * Classifies an ISO-DEP tag by its ATS [historicalBytes] (empty if it has
 * none), without sending it any command. [openTransport] is called only for
 * a supported tag.
 */
fun classifyIsoDepTag(uid: ByteArray, historicalBytes: ByteArray, openTransport: () -> Transport): FoundTag =
    classifyIsoDep(uid, historicalBytes, null, openTransport)

/** Like the `uid` overload, taking the historical bytes from [info], and the result carries [info] for reports. */
fun classifyIsoDepTag(info: TagInfo, openTransport: () -> Transport): FoundTag =
    classifyIsoDep(info.uid, info.historicalBytes ?: ByteArray(0), info, openTransport)

private fun classifyNfcA(uid: ByteArray, info: TagInfo?, openTransport: () -> Transport): FoundTag =
    if (isWaveshareTag(uid)) FoundTag.Waveshare(openTransport(), info) else FoundTag.Unsupported(uid, info)

private fun classifyIsoDep(
    uid: ByteArray,
    historicalBytes: ByteArray,
    info: TagInfo?,
    openTransport: () -> Transport,
): FoundTag =
    if (isWaveshareIsoDepTag(historicalBytes)) {
        FoundTag.WaveshareIsoDep(openTransport(), info)
    } else {
        FoundTag.Unsupported(uid, info)
    }
