package io.github.cacack.tagpaper.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 4×4 gradient, 0 at top-left rising by 17 per pixel to 255. */
private val GRADIENT = GrayImage(4, 4, ByteArray(16) { (it * 17).toByte() })

private fun BooleanArray.rows(width: Int) =
    toList().chunked(width).map { row -> row.joinToString("") { if (it) "W" else "B" } }

class DitherTest {
    @Test
    fun thresholdSplitsAGradientAtTheLevel() {
        val gradient = GrayImage(16, 1, ByteArray(16) { (it * 17).toByte() })

        assertEquals(listOf("BBBBBBBBWWWWWWWW"), gradient.toBlackWhite(Dither.Threshold()).rows(16))
    }

    @Test
    fun floydSteinbergDiffusesAGradient() {
        // Expected output derived by a separate script applying the same integer
        // rules (7/3/5/1 sixteenths, truncating division, values clamped to 0–255).
        assertEquals(
            listOf("BBBB", "BWBW", "WBWW", "WWWW"),
            GRADIENT.toBlackWhite(Dither.FloydSteinberg).rows(4),
        )
    }

    @Test
    fun floydSteinbergRendersMidGreyAsHalfWhite() {
        val grey = GrayImage(16, 16, ByteArray(256) { 127 })

        assertEquals(128, grey.toBlackWhite(Dither.FloydSteinberg).count { it })
    }

    @Test
    fun thresholdCountsALevelPixelAsWhite() {
        val image = GrayImage(2, 1, byteArrayOf(127, 128.toByte()))

        assertEquals(listOf("BW"), image.toBlackWhite(Dither.Threshold(128)).rows(2))
    }

    @Test
    fun thresholdRejectsLevelsOutsideTheGreyRange() {
        assertFailsWith<IllegalArgumentException> { Dither.Threshold(256) }
        assertFailsWith<IllegalArgumentException> { Dither.Threshold(-1) }
    }
}
