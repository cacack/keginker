package io.github.cacack.tagpaper.waveshare

import io.github.cacack.tagpaper.image.Dither
import io.github.cacack.tagpaper.image.GrayImage
import io.github.cacack.tagpaper.image.fill
import io.github.cacack.tagpaper.image.toBlackWhite

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
 * wide) into the panel's memory layout and packs it: rows of the memory image,
 * 8 pixels per byte, most significant bit first, 1 = white.
 */
fun packFrame(panel: WavesharePanel, white: BooleanArray): ByteArray {
    require(white.size == panel.viewWidth * panel.viewHeight) {
        "Expected ${panel.viewWidth}×${panel.viewHeight} pixels, got ${white.size}"
    }
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
