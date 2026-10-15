package io.github.cacack.tagpaper.waveshare

/** How a panel's memory is turned to the orientation the user sees. */
enum class PanelView {
    /** Viewed in portrait: memory turned 90° clockwise. */
    PORTRAIT,
}

/** A Waveshare classic NFC-powered e-paper panel. */
enum class WavesharePanel(
    /** Width of the panel memory, in pixels. */
    val width: Int,
    /** Height of the panel memory, in pixels. */
    val height: Int,
    /** Panel type byte sent with `CD 00`. */
    val typeCode: Int,
    /** Image bytes per data command. */
    val chunkSize: Int,
    /** Orientation the image is composed in; verify against hardware for each panel. */
    val view: PanelView,
) {
    /** 2.7" black/white, 264 × 176 memory, viewed 176 × 264. Orientation verified against the vendor codec. */
    EPD_2IN7(width = 264, height = 176, typeCode = 0x10, chunkSize = 121, view = PanelView.PORTRAIT);

    init {
        require(width % 8 == 0) { "Memory rows must be whole bytes" }
    }

    /** Width of the image as viewed, in pixels. */
    val viewWidth: Int get() = when (view) {
        PanelView.PORTRAIT -> height
    }

    /** Height of the image as viewed, in pixels. */
    val viewHeight: Int get() = when (view) {
        PanelView.PORTRAIT -> width
    }

    /** Bytes in one frame at 1 bit per pixel. */
    val frameSize: Int get() = width * height / 8

    val chunkCount: Int get() = frameSize / chunkSize
}
