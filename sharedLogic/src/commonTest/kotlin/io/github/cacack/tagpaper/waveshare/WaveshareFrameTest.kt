package io.github.cacack.tagpaper.waveshare

import io.github.cacack.tagpaper.image.Dither
import io.github.cacack.tagpaper.image.GrayImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val PANEL = WavesharePanel.EPD_2IN7

/** Bytes holding the visible view in a Rev2.2 frame: 264 rows of 22 bytes. */
private const val VIEW_BYTES = 264 * 22

/** A white view-orientation image with black pixels at the given (x, y) points. */
private fun viewWithBlack(vararg points: Pair<Int, Int>): BooleanArray =
    BooleanArray(PANEL.viewWidth * PANEL.viewHeight) { true }.also { view ->
        for ((x, y) in points) view[y * PANEL.viewWidth + x] = false
    }

/** Indices and values of every byte that isn't all-white. */
private fun ByteArray.nonWhite(): Map<Int, Int> =
    withIndex().filter { it.value != 0xFF.toByte() }.associate { it.index to (it.value.toInt() and 0xFF) }

class WaveshareFrameTest {
    @Test
    fun viewIsPortrait176By264() {
        assertEquals(176, PANEL.viewWidth)
        assertEquals(264, PANEL.viewHeight)
    }

    @Test
    fun packsToExactly5808BytesWithOneAsWhite() {
        val white = packFrame(PANEL, viewWithBlack())
        val black = packFrame(PANEL, BooleanArray(PANEL.viewWidth * PANEL.viewHeight))

        assertEquals(5808, white.size)
        assertTrue(white.all { it == 0xFF.toByte() })
        assertTrue(black.all { it == 0x00.toByte() })
    }

    @Test
    fun viewTopLeftLandsInTheLastMemoryRow() {
        assertEquals(mapOf(5775 to 0x7F), packFrame(PANEL, viewWithBlack(0 to 0)).nonWhite())
    }

    @Test
    fun viewTopRightLandsInTheFirstByte() {
        assertEquals(mapOf(0 to 0x7F), packFrame(PANEL, viewWithBlack(175 to 0)).nonWhite())
    }

    @Test
    fun adjacentViewRowsAreAdjacentBitsWithNoWrap() {
        // View (0, 0) and (0, 1) sit next to each other in the same memory byte.
        assertEquals(mapOf(5775 to 0x3F), packFrame(PANEL, viewWithBlack(0 to 0, 0 to 1)).nonWhite())
        assertEquals(mapOf(5775 to 0xBF), packFrame(PANEL, viewWithBlack(0 to 1)).nonWhite())
    }

    @Test
    fun rejectsAViewOfTheWrongSize() {
        assertFailsWith<IllegalArgumentException> { packFrame(PANEL, BooleanArray(264 * 176 - 1)) }
    }

    @Test
    fun controllerFrameSendsTheViewTurned180ThenWhitePadding() {
        // View bottom-right goes first; view top-left is the last image byte.
        assertEquals(mapOf(0 to 0x7F), packControllerFrame(PANEL, viewWithBlack(175 to 263)).nonWhite())
        assertEquals(mapOf(VIEW_BYTES - 1 to 0xFE), packControllerFrame(PANEL, viewWithBlack(0 to 0)).nonWhite())
        // Rows are 22 bytes; view row 262's right edge starts the second row.
        assertEquals(mapOf(22 to 0x7F), packControllerFrame(PANEL, viewWithBlack(175 to 262)).nonWhite())
    }

    @Test
    fun controllerFrameCoversAll296RowsWithHiddenRowsWhite() {
        val frame = packControllerFrame(PANEL, BooleanArray(PANEL.viewWidth * PANEL.viewHeight))

        assertEquals(296 * 22, frame.size)
        assertTrue(frame.copyOfRange(0, VIEW_BYTES).all { it == 0.toByte() })
        assertTrue(frame.copyOfRange(VIEW_BYTES, frame.size).all { it == 0xFF.toByte() })
        assertFailsWith<IllegalArgumentException> { packControllerFrame(PANEL, BooleanArray(10)) }
    }

    @Test
    fun encodesAnImageOfAnySizeToAFrame() {
        val landscapeWhite = GrayImage(640, 480, ByteArray(640 * 480) { 0xFF.toByte() })

        val frame = encodeFrame(PANEL, landscapeWhite)

        assertEquals(5808, frame.size)
        assertTrue(frame.all { it == 0xFF.toByte() })
    }

    @Test
    fun encodesALandscapeImageByCroppingToTheCentredPortraitView() {
        // 352×264 crops to the centred 176 columns starting at x = 88.
        val pixels = ByteArray(352 * 264) { 0xFF.toByte() }
        pixels[0] = 0 // cropped away
        pixels[88] = 0 // becomes view top-left
        pixels[88 + 175] = 0 // becomes view top-right

        val frame = encodeFrame(PANEL, GrayImage(352, 264, pixels), Dither.Threshold())

        assertEquals(mapOf(0 to 0x7F, 5775 to 0x7F), frame.nonWhite())
    }
}
