package io.github.cacack.tagpaper.waveshare

/** A Waveshare classic NFC-powered e-paper panel. */
enum class WavesharePanel(
    val width: Int,
    val height: Int,
    /** Panel type byte sent with `CD 00`. */
    val typeCode: Int,
    /** Image bytes per data command. */
    val chunkSize: Int,
) {
    /** 2.7" black/white, 264 × 176. */
    EPD_2IN7(width = 264, height = 176, typeCode = 0x10, chunkSize = 121);

    /** Bytes in one frame at 1 bit per pixel. */
    val frameSize: Int get() = width * height / 8

    val chunkCount: Int get() = frameSize / chunkSize
}
