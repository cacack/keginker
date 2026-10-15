package io.github.cacack.tagpaper.waveshare

/**
 * The 7-byte NFC-A UID Waveshare classic tags report, "WSDZ10m" in ASCII.
 * Page 0 holds it split by a check byte, so match the UID, not page 0.
 */
private val WAVESHARE_UID = "WSDZ10m".encodeToByteArray()

/** Whether a tag with NFC-A [uid] is a Waveshare classic tag. */
fun isWaveshareTag(uid: ByteArray): Boolean = uid.contentEquals(WAVESHARE_UID)
