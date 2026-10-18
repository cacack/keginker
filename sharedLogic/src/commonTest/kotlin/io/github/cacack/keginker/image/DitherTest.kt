package io.github.cacack.keginker.image

import io.github.cacack.keginker.waveshare.WavesharePanel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 4×4 gradient, 0 at top-left rising by 17 per pixel to 255. */
private val GRADIENT = GrayImage(4, 4, ByteArray(16) { (it * 17).toByte() })

/** Largest gap allowed between Floyd–Steinberg's white share and grey ÷ 255. */
private const val FLOYD_STEINBERG_TOLERANCE = 0.005

private fun BooleanArray.rows(width: Int) =
    toList().chunked(width).map { row -> row.joinToString("") { if (it) "W" else "B" } }

class DitherTest {
    @Test
    fun thresholdSplitsAGradientAtTheLevel() {
        val gradient = GrayImage(16, 1, ByteArray(16) { (it * 17).toByte() })

        assertEquals(listOf("BBBBBBBBWWWWWWWW"), gradient.toBlackWhite(Dither.Threshold()).rows(16))
    }

    @Test
    fun higherThresholdKeepsLightGreyBlack() {
        // A four-level label (0, 85, 170, 255): at 200 only true white stays white.
        val levels = GrayImage(4, 1, byteArrayOf(0, 85, 170.toByte(), 255.toByte()))

        assertEquals(listOf("BBWW"), levels.toBlackWhite(Dither.Threshold()).rows(4))
        assertEquals(listOf("BBBW"), levels.toBlackWhite(Dither.Threshold(200)).rows(4))
    }

    @Test
    fun defaultThresholdIsMidGrey() {
        assertEquals(128, Dither.Threshold().level)
        assertEquals(Dither.Threshold.DEFAULT_LEVEL, Dither.Threshold().level)
    }

    @Test
    fun floydSteinbergDiffusesAGradient() {
        // Expected output derived by a separate script applying the same integer
        // rules (7/3/5 sixteenths, truncating division, the remainder to the last
        // neighbour, values clamped to 0–255).
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

    @Test
    fun raisingTheThresholdNeverTurnsBlackWhite() = forEachSeed { random ->
        val image = random.nextGrayImage()
        val lower = random.nextInt(0, 256)
        val higher = random.nextInt(lower, 256)

        val atLower = image.toBlackWhite(Dither.Threshold(lower))
        val atHigher = image.toBlackWhite(Dither.Threshold(higher))

        for (i in atLower.indices) {
            assertTrue(atLower[i] || !atHigher[i], "Pixel $i (grey ${image.gray(i)}) turned white from $lower to $higher")
        }
    }

    @Test
    fun thresholdZeroIsAllWhite() = forEachSeed { random ->
        assertTrue(random.nextGrayImage().toBlackWhite(Dither.Threshold(0)).all { it })
    }

    @Test
    fun threshold255KeepsOnlyPureWhite() = forEachSeed { random ->
        val image = random.nextGrayImage()

        assertEquals(image.grays().map { it == 255 }, image.toBlackWhite(Dither.Threshold(255)).toList())
    }

    @Test
    fun floydSteinbergRendersEachGreyAsThatShareOfWhite() {
        val panel = WavesharePanel.EPD_2IN7
        forEachCase(0..255, "grey") { grey ->
            val image = GrayImage(panel.viewWidth, panel.viewHeight, ByteArray(panel.viewWidth * panel.viewHeight) { grey.toByte() })

            val whiteShare = image.toBlackWhite(Dither.FloydSteinberg).count { it }.toDouble() / image.pixels.size

            assertTrue(abs(whiteShare - grey / 255.0) <= FLOYD_STEINBERG_TOLERANCE, "White share was $whiteShare")
        }
    }
}
