package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.image.GrayImage
import io.github.cacack.keginker.image.fill
import io.github.cacack.keginker.image.toBlackWhite

/**
 * Converts an image of any size into the frame [WaveshareWriter] sends.
 *
 * The image is centre-cropped to the panel's view shape (176 × 264 portrait
 * for the 2.7") and scaled; it is never letterboxed or rotated to fit, so
 * platform UIs should preview the crop. UIs that preview should call
 * [renderView] and then [packFrame] instead, so the preview is the frame.
 */
fun encodeFrame(panel: WavesharePanel, image: GrayImage, dither: Dither = Dither.FloydSteinberg): ByteArray =
    packFrame(panel, renderView(panel, image, dither))

/**
 * The black/white image exactly as the panel will show it: row-major,
 * [WavesharePanel.viewWidth] wide, `true` is white. UIs preview this and then
 * pass it to [packFrame], so the preview and the write can't disagree.
 */
fun renderView(panel: WavesharePanel, image: GrayImage, dither: Dither = Dither.FloydSteinberg): BooleanArray =
    image.fill(panel.viewWidth, panel.viewHeight).toBlackWhite(dither)

/**
 * Rotates a black/white view-orientation image (row-major, [WavesharePanel.viewWidth]
 * wide) into the classic tag's memory layout and packs it: rows of the memory
 * image, 8 pixels per byte, most significant bit first, 1 = white.
 */
fun packFrame(panel: WavesharePanel, white: BooleanArray): ByteArray {
    requireViewSize(panel, white)
    val bytesPerRow = panel.width / 8
    val frame = ByteArray(panel.frameSize)
    for (y in 0 until panel.height) {
        for (x in 0 until panel.width) {
            val viewIndex = when (panel.view) {
                // Memory pixel (x, y) shows view pixel (viewWidth − 1 − y, x).
                PanelView.PORTRAIT -> x * panel.viewWidth + (panel.viewWidth - 1 - y)
            }
            if (white[viewIndex]) {
                val i = y * bytesPerRow + x / 8
                frame[i] = (frame[i].toInt() or (0x80 ushr (x % 8))).toByte()
            }
        }
    }
    return frame
}

/**
 * Packs a black/white view-orientation image for a Rev2.2 board, which writes
 * straight into the panel controller's RAM: rows of [WavesharePanel.viewWidth]
 * pixels, 8 per byte, most significant bit first, 1 = white.
 *
 * The frame covers every controller row and is written from the highest
 * address down. The panel shows only the highest [WavesharePanel.viewHeight]
 * addresses (32–295 on the 2.7"), highest at the bottom, with RAM column 0 at
 * the right edge. So the view goes first, turned 180°, landing on the visible
 * rows; white rows fill the hidden low addresses. Measured on a 2.7" Rev2.2 board.
 */
fun packControllerFrame(panel: WavesharePanel, white: BooleanArray): ByteArray {
    requireViewSize(panel, white)
    val frame = ByteArray(panel.controllerFrameSize) { 0xFF.toByte() }
    for (i in white.indices) {
        if (!white[white.size - 1 - i]) frame[i / 8] = (frame[i / 8].toInt() and (0x80 ushr (i % 8)).inv()).toByte()
    }
    return frame
}

private fun requireViewSize(panel: WavesharePanel, white: BooleanArray) {
    require(white.size == panel.viewWidth * panel.viewHeight) {
        "Expected ${panel.viewWidth}×${panel.viewHeight} pixels, got ${white.size}"
    }
}
