package io.github.cacack.keginker.waveshare

/** How a panel's memory is turned to the orientation the user sees. */
enum class PanelView {
    /** Memory holds the view turned 180°: memory pixel (x, y) shows view pixel (width − 1 − x, height − 1 − y). */
    TURNED_180,
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
    /**
     * Rev2.2 boards only: rows in the panel controller's RAM, each
     * [viewWidth] pixels wide. The panel shows the highest [viewHeight] row
     * addresses; the lower ones are hidden. Measure it on hardware.
     */
    val controllerRows: Int,
) {
    /**
     * 2.7" black/white, 176 × 264 memory and view. Classic layout (view turned
     * 180°) inferred from a classic board's garbled write; Rev2.2 layout (296-row controller,
     * view turned 180°) measured on a Rev2.2 board.
     */
    EPD_2IN7(width = 176, height = 264, typeCode = 0x10, chunkSize = 121, view = PanelView.TURNED_180, controllerRows = 296);

    init {
        require(width % 8 == 0) { "Memory rows must be whole bytes" }
        require(controllerRows >= viewHeight) { "The controller must hold every visible row" }
        require(frameSize % chunkSize == 0) { "$name: a frame of $frameSize bytes must split into whole $chunkSize-byte chunks" }
    }

    /** Width of the image as viewed, in pixels. */
    val viewWidth: Int get() = when (view) {
        PanelView.TURNED_180 -> width
    }

    /** Height of the image as viewed, in pixels. */
    val viewHeight: Int get() = when (view) {
        PanelView.TURNED_180 -> height
    }

    /** Bytes in one frame at 1 bit per pixel. */
    val frameSize: Int get() = width * height / 8

    val chunkCount: Int get() = frameSize / chunkSize

    /** Rev2.2 only: bytes in a frame covering the whole controller RAM. */
    val controllerFrameSize: Int get() = controllerRows * viewWidth / 8
}
