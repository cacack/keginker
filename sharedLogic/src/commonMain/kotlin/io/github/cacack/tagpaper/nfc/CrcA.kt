package io.github.cacack.tagpaper.nfc

/**
 * The ISO 14443-3A CRC (CRC_A) of [data], low byte first, as appended to
 * NFC-A frames. Android adds it in hardware; iOS requires it on commands
 * sent to MIFARE Ultralight-family tags.
 */
fun crcA(data: ByteArray): ByteArray {
    var crc = 0x6363
    for (byte in data) {
        var b = (byte.toInt() xor crc) and 0xFF
        b = (b xor (b shl 4)) and 0xFF
        crc = (crc ushr 8) xor (b shl 8) xor (b shl 3) xor (b ushr 4)
    }
    return byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
}
