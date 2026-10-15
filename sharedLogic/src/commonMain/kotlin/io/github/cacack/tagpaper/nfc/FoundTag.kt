package io.github.cacack.tagpaper.nfc

import io.github.cacack.tagpaper.waveshare.isWaveshareTag

/** A tag the phone has just found, classified by protocol family. */
sealed interface FoundTag {
    /** A Waveshare classic tag, ready to write through [transport]. */
    class Waveshare(val transport: Transport) : FoundTag

    /** A tag no supported protocol recognises. [uid] identifies it in reports. */
    class Unsupported(val uid: ByteArray) : FoundTag
}

/** Classifies a tag by its NFC-A [uid] without sending it any command. */
fun classifyTag(uid: ByteArray, transport: Transport): FoundTag =
    if (isWaveshareTag(uid)) FoundTag.Waveshare(transport) else FoundTag.Unsupported(uid)
