package io.github.cacack.tagpaper.nfc

import io.github.cacack.tagpaper.waveshare.isWaveshareIsoDepTag
import io.github.cacack.tagpaper.waveshare.isWaveshareTag

/** A tag the phone has just found, classified by protocol family. */
sealed interface FoundTag {
    /**
     * A tag tagpaper can write; write it with [ImageWriter], which uses the
     * right protocol for each kind. The receiver owns [transport] and must close it.
     */
    sealed interface Writable : FoundTag {
        val transport: Transport
    }

    /** A Waveshare classic board: raw NFC-A frames. */
    class Waveshare(override val transport: Transport) : Writable

    /** A Waveshare Rev2.2 board: ISO-DEP with a YZRCOS chip. */
    class WaveshareIsoDep(override val transport: Transport) : Writable

    /** A tag no supported protocol recognises. [uid] identifies it in reports. */
    class Unsupported(val uid: ByteArray) : FoundTag
}

/**
 * Classifies an NFC-A tag that isn't ISO-DEP by its [uid], without sending it
 * any command. [openTransport] is called only for a supported tag.
 */
fun classifyNfcATag(uid: ByteArray, openTransport: () -> Transport): FoundTag =
    if (isWaveshareTag(uid)) FoundTag.Waveshare(openTransport()) else FoundTag.Unsupported(uid)

/**
 * Classifies an ISO-DEP tag by its ATS [historicalBytes] (empty if it has
 * none), without sending it any command. [openTransport] is called only for
 * a supported tag.
 */
fun classifyIsoDepTag(uid: ByteArray, historicalBytes: ByteArray, openTransport: () -> Transport): FoundTag =
    if (isWaveshareIsoDepTag(historicalBytes)) FoundTag.WaveshareIsoDep(openTransport()) else FoundTag.Unsupported(uid)
