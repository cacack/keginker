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

    /**
     * A tag no supported protocol recognises; [info] always has at least its
     * UID. [openTransport] is null for a [kind] nothing can talk to.
     * Whoever calls it owns and closes what it returns: the display probe,
     * test writes, and EZ Sign label writes.
     */
    class Unsupported(
        override val info: TagInfo,
        val kind: TagKind,
        val openTransport: (() -> Transport)?,
    ) : FoundTag {
        /** For a tag no command can be sent to. */
        constructor(info: TagInfo) : this(info, TagKind.OTHER, null)

        val uid: ByteArray get() = info.uid
    }
}

/** How a tag takes commands, as far as the display probe needs to know. */
enum class TagKind {
    /** ISO 14443-4: ISO 7816 APDUs. */
    ISO_DEP,

    /** ISO 14443-3A raw frames, as NFC Forum Type 2 tags take them. */
    NFC_A,

    /** ISO 15693 raw frames, as NFC Forum Type 5 tags take them. */
    NFC_V,

    /** Anything else; the probe sends it nothing. */
    OTHER,
}

/** How [this] takes commands. */
val FoundTag.kind: TagKind get() = when (this) {
    is FoundTag.Waveshare -> TagKind.NFC_A
    is FoundTag.WaveshareIsoDep -> TagKind.ISO_DEP
    is FoundTag.Unsupported -> kind
}

/** The protocol a report names for this tag. */
val FoundTag.protocolName: String get() = when (this) {
    is FoundTag.Waveshare -> "Waveshare classic (NFC-A)"
    is FoundTag.WaveshareIsoDep -> "Waveshare Rev2.2 (ISO-DEP)"
    is FoundTag.Unsupported -> "unsupported"
}

/** The protocol a report names for an ISO-DEP tag once its EZ Sign device information decoded. */
internal const val EZ_SIGN_PROTOCOL_NAME = "EZ Sign (ISO-DEP)"

/**
 * Classifies an NFC-A tag that isn't ISO-DEP by its UID, without sending it
 * any command; the result carries [info] for reports. [openTransport] is
 * called here only for a supported tag; an unsupported one keeps it for
 * whoever reads or writes it next.
 */
fun classifyNfcATag(info: TagInfo, openTransport: () -> Transport): FoundTag =
    if (isWaveshareTag(info.uid)) {
        FoundTag.Waveshare(openTransport(), info)
    } else {
        FoundTag.Unsupported(info, TagKind.NFC_A, openTransport)
    }

/**
 * Classifies an ISO-DEP tag by its ATS historical bytes (none counts as
 * empty), without sending it any command; the result carries [info] for
 * reports. [openTransport] is called here only for a supported tag; an
 * unsupported one keeps it for whoever reads or writes it next, as an EZ
 * Sign display needs.
 */
fun classifyIsoDepTag(info: TagInfo, openTransport: () -> Transport): FoundTag =
    if (isWaveshareIsoDepTag(info.historicalBytes ?: ByteArray(0))) {
        FoundTag.WaveshareIsoDep(openTransport(), info)
    } else {
        FoundTag.Unsupported(info, TagKind.ISO_DEP, openTransport)
    }

/**
 * Classifies an NFC-V (ISO 15693) tag, without sending it any command; the
 * result carries [info] for reports. No supported protocol uses NFC-V, so
 * [openTransport] is never called here; the result keeps it for whoever reads it next.
 */
fun classifyNfcVTag(info: TagInfo, openTransport: () -> Transport): FoundTag =
    FoundTag.Unsupported(info, TagKind.NFC_V, openTransport)
