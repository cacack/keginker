package io.github.cacack.keginker.image

import io.github.cacack.keginker.waveshare.WavesharePanel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 4×4 gradient, 0 at top-left rising by 17 per pixel to 255. */
private val GRADIENT = GrayImage(4, 4, ByteArray(16) { (it * 17).toByte() })

/** Largest gap allowed between Floyd–Steinberg's white share and gray ÷ 255. */
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
    fun higherThresholdKeepsLightGrayBlack() {
        // A four-level label (0, 85, 170, 255): at 200 only true white stays white.
        val levels = GrayImage(4, 1, byteArrayOf(0, 85, 170.toByte(), 255.toByte()))

        assertEquals(listOf("BBWW"), levels.toBlackWhite(Dither.Threshold()).rows(4))
        assertEquals(listOf("BBBW"), levels.toBlackWhite(Dither.Threshold(200)).rows(4))
    }

    @Test
    fun defaultThresholdIsMidGray() {
        assertEquals(128, Dither.Threshold().level)
        assertEquals(Dither.Threshold.DEFAULT_LEVEL, Dither.Threshold().level)
    }

    @Test
    fun floydSteinbergDiffusesAGradient() {
        // Expected output derived by a separate script applying the same integer
        // rules (7/3/5 sixteenths, truncating division, the remainder to the last
        // neighbor, values clamped to 0–255).
        assertEquals(
            listOf("BBBB", "BWBW", "WBWW", "WWWW"),
            GRADIENT.toBlackWhite(Dither.FloydSteinberg).rows(4),
        )
    }

    @Test
    fun floydSteinbergRendersMidGrayAsHalfWhite() {
        val gray = GrayImage(16, 16, ByteArray(256) { 127 })

        assertEquals(128, gray.toBlackWhite(Dither.FloydSteinberg).count { it })
    }

    @Test
    fun thresholdCountsALevelPixelAsWhite() {
        val image = GrayImage(2, 1, byteArrayOf(127, 128.toByte()))

        assertEquals(listOf("BW"), image.toBlackWhite(Dither.Threshold(128)).rows(2))
    }

    @Test
    fun thresholdRejectsLevelsOutsideTheGrayRange() {
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
            assertTrue(atLower[i] || !atHigher[i], "Pixel $i (gray ${image.gray(i)}) turned white from $lower to $higher")
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
    fun floydSteinbergRendersEachGrayAsThatShareOfWhite() {
        val panel = WavesharePanel.EPD_2IN7
        forEachCase(0..255, "gray") { gray ->
            val image = GrayImage(panel.viewWidth, panel.viewHeight, ByteArray(panel.viewWidth * panel.viewHeight) { gray.toByte() })

            val whiteShare = image.toBlackWhite(Dither.FloydSteinberg).count { it }.toDouble() / image.pixels.size

            assertTrue(abs(whiteShare - gray / 255.0) <= FLOYD_STEINBERG_TOLERANCE, "White share was $whiteShare")
        }
    }

    @Test
    fun saturatedColorsTakeTheNearestPaletteColor() {
        // Red, orange, yellow, blue, and a pale pink that counts as gray.
        val argb = intArrayOf(0xFF0000, 0xFF9000, 0xFFFF00, 0x0000FF, 0xFFC0C0).map { it or (0xFF shl 24) }.toIntArray()
        val image = ColorImage.fromArgb(argb.size, 1, argb)

        val indices = image.toPalette(Palette.BLACK_WHITE_YELLOW_RED, Dither.Threshold()).toList()

        assertEquals(listOf<Byte>(3, 2, 2, BLACK, WHITE), indices)
    }

    @Test
    fun paletteFloydSteinbergRendersMidGrayAsHalfWhiteWithNoColor() {
        val gray = ColorImage.fromGray(GrayImage(16, 16, ByteArray(256) { 127 }))

        val indices = gray.toPalette(Palette.BLACK_WHITE_YELLOW_RED, Dither.FloydSteinberg)

        assertEquals(128, indices.count { it == WHITE })
        assertTrue(indices.all { it == BLACK || it == WHITE })
    }

    @Test
    fun previewsShowThePaletteColors() {
        val view = byteArrayOf(0, 1, 2, 3)

        assertEquals(
            listOf(0xFF000000, 0xFFFFFFFF, 0xFFFFFF00, 0xFFFF0000).map { it.toInt() },
            Palette.BLACK_WHITE_YELLOW_RED.argb(view).toList(),
        )
    }
}
