package io.github.cacack.tagpaper.waveshare

/**
 * The 7-byte NFC-A UID Waveshare classic tags report, "WSDZ10m" in ASCII.
 * Page 0 holds it split by a check byte, so match the UID, not page 0.
 */
private val WAVESHARE_UID = "WSDZ10m".encodeToByteArray()

/** Whether a tag with NFC-A [uid] is a Waveshare classic tag. */
fun isWaveshareTag(uid: ByteArray): Boolean = uid.contentEquals(WAVESHARE_UID)

/**
 * The ATS historical bytes of Waveshare Rev2.2 boards begin with the chip's
 * operating system name, "YZRCOS" in ASCII (then a version, `03 00` seen).
 * Their UID is 4 bytes and varies per board, so it can't identify them.
 */
private val YZRCOS = "YZRCOS".encodeToByteArray()

/** Whether an ISO-DEP tag with these ATS [historicalBytes] is a Waveshare Rev2.2 board. */
fun isWaveshareIsoDepTag(historicalBytes: ByteArray): Boolean =
    historicalBytes.size >= YZRCOS.size && historicalBytes.copyOf(YZRCOS.size).contentEquals(YZRCOS)
