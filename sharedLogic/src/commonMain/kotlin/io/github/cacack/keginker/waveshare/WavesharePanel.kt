package io.github.cacack.keginker.waveshare

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
    /**
     * Rev2.2 boards only: rows in the panel controller's RAM, each
     * [viewWidth] pixels wide. The panel shows the highest [viewHeight] row
     * addresses; the lower ones are hidden. Measure it on hardware.
     */
    val controllerRows: Int,
) {
    /**
     * 2.7" black/white, 264 × 176 memory, viewed 176 × 264. Classic orientation
     * verified against the vendor codec; Rev2.2 layout (296-row controller,
     * image rotated 180°) measured on a Rev2.2 board.
     */
    EPD_2IN7(width = 264, height = 176, typeCode = 0x10, chunkSize = 121, view = PanelView.PORTRAIT, controllerRows = 296);

    init {
        require(width % 8 == 0) { "Memory rows must be whole bytes" }
        require(controllerRows >= viewHeight) { "The controller must hold every visible row" }
        require(frameSize % chunkSize == 0) { "A frame must split into whole chunks" }
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

    /** Rev2.2 only: bytes in a frame covering the whole controller RAM. */
    val controllerFrameSize: Int get() = controllerRows * viewWidth / 8
}
