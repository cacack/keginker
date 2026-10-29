package io.github.cacack.keginker

import io.github.cacack.keginker.ezsign.ezSignLabelPixels
import io.github.cacack.keginker.ezsign.infoFrom
import io.github.cacack.keginker.ezsign.packFrame
import io.github.cacack.keginker.image.BLACK
import io.github.cacack.keginker.image.ColorImage
import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.image.GrayImage
import io.github.cacack.keginker.image.Palette
import io.github.cacack.keginker.image.WHITE
import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.renderView as renderPanelView
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** The A0 TLV from the 2.9" display report (#130). */
private val A0_29 = bytes(0xA0, 0x07, 0xF0, 0x07, 0x20, 0x02, 0x50, 0x00, 0x80)

/** The A0 TLV from the 4.2" display report (#131). */
private val A0_42 = bytes(0xA0, 0x07, 0xF0, 0x07, 0x20, 0x02, 0x58, 0x01, 0x90)

class LabelDisplayTest {
    @Test
    fun viewsAreEachDisplaysReadingShape() {
        assertEquals(176 to 264, LabelDisplay.WAVESHARE_2IN7.let { it.viewWidth to it.viewHeight })
        assertEquals(128 to 296, LabelDisplay.EZ_SIGN_2IN9.let { it.viewWidth to it.viewHeight })
        assertEquals(400 to 300, LabelDisplay.EZ_SIGN_4IN2.let { it.viewWidth to it.viewHeight })
    }

    @Test
    fun ezSignNativeSizesMatchTheReportedA0() {
        for ((display, a0) in listOf(LabelDisplay.EZ_SIGN_2IN9 to A0_29, LabelDisplay.EZ_SIGN_4IN2 to A0_42)) {
            val info = infoFrom(a0)

            assertEquals(info.nativeWidth to info.nativeHeight, display.nativeWidth to display.nativeHeight, display.name)
        }
        assertEquals(128 to 296, LabelDisplay.EZ_SIGN_2IN9.let { it.nativeWidth to it.nativeHeight })
        assertEquals(400 to 300, LabelDisplay.EZ_SIGN_4IN2.let { it.nativeWidth to it.nativeHeight })
    }

    @Test
    fun the29ViewCornersLandTurned180() {
        // View (vx, vy) → native (127 − vx, 295 − vy), 128 wide.
        assertCornersLand(
            LabelDisplay.EZ_SIGN_2IN9,
            mapOf(
                (0 to 0) to (127 to 295),
                (127 to 0) to (0 to 295),
                (0 to 295) to (127 to 0),
                (127 to 295) to (0 to 0),
            ),
        )
    }

    @Test
    fun the42ViewCornersLandUnturned() {
        assertCornersLand(
            LabelDisplay.EZ_SIGN_4IN2,
            mapOf(
                (0 to 0) to (0 to 0),
                (399 to 0) to (399 to 0),
                (0 to 299) to (0 to 299),
                (399 to 299) to (399 to 299),
            ),
        )
    }

    @Test
    fun allWhiteViewsPackToAll0x55() {
        for ((display, a0) in listOf(LabelDisplay.EZ_SIGN_2IN9 to A0_29, LabelDisplay.EZ_SIGN_4IN2 to A0_42)) {
            val white = ByteArray(display.viewWidth * display.viewHeight) { WHITE }

            val packed = packFrame(ezSignLabelPixels(display, white), infoFrom(a0))

            assertEquals(display.nativeWidth / 4 * display.nativeHeight, packed.size, display.name)
            assertTrue(packed.all { it == 0x55.toByte() }, display.name)
        }
    }

    @Test
    fun labelPixelsNeedAnEzSignDisplayAndItsViewSize() {
        assertFailsWith<IllegalArgumentException> {
            ezSignLabelPixels(LabelDisplay.WAVESHARE_2IN7, ByteArray(176 * 264))
        }
        assertFailsWith<IllegalArgumentException> {
            ezSignLabelPixels(LabelDisplay.EZ_SIGN_4IN2, ByteArray(400 * 300 - 1))
        }
    }

    @Test
    fun renderingForTheWaveshareDisplayMatchesItsPanel() {
        val image = GrayImage(5, 7, ByteArray(35) { (it * 7).toByte() })
        val white = renderPanelView(WavesharePanel.EPD_2IN7, image)

        assertContentEquals(
            ByteArray(white.size) { if (white[it]) WHITE else BLACK },
            renderView(LabelDisplay.WAVESHARE_2IN7, ColorImage.fromGray(image)),
        )
        assertEquals(400 * 300, renderView(LabelDisplay.EZ_SIGN_4IN2, ColorImage.fromGray(image)).size)
    }

    @Test
    fun palettesFollowTheDisplay() {
        assertEquals(Palette.BLACK_WHITE, LabelDisplay.WAVESHARE_2IN7.palette)
        assertEquals(Palette.BLACK_WHITE_YELLOW_RED, LabelDisplay.EZ_SIGN_2IN9.palette)
        assertEquals(Palette.BLACK_WHITE_YELLOW_RED, LabelDisplay.EZ_SIGN_4IN2.palette)
    }

    @Test
    fun colorPhotosKeepTheirColorsOnlyWhereShownAndAsked() {
        // Left half red, right half yellow.
        val photo = ColorImage.fromArgb(4, 2, IntArray(8) { if (it % 4 < 2) 0xFFFF0000.toInt() else 0xFFFFFF00.toInt() })
        val display = LabelDisplay.EZ_SIGN_4IN2

        for (dither in listOf(Dither.FloydSteinberg, Dither.Threshold())) {
            val color = renderView(display, photo, dither, color = true)
            assertEquals(3.toByte(), color[0], "$dither: left is red")
            assertEquals(2.toByte(), color[display.viewWidth - 1], "$dither: right is yellow")

            val plain = renderView(display, photo, dither, color = false)
            assertTrue(plain.all { it == BLACK || it == WHITE }, "$dither: color off")
            assertTrue(renderView(LabelDisplay.WAVESHARE_2IN7, photo, dither).all { it == BLACK || it == WHITE })
        }
    }

    @Test
    fun grayImagesRenderTheSameInColorAsInBlackAndWhite() {
        val image = ColorImage.fromGray(GrayImage(40, 30, ByteArray(1200) { (it * 13).toByte() }))

        for (dither in listOf(Dither.FloydSteinberg, Dither.Threshold(), Dither.Threshold(200))) {
            assertContentEquals(
                renderView(LabelDisplay.EZ_SIGN_4IN2, image, dither, color = false),
                renderView(LabelDisplay.EZ_SIGN_4IN2, image, dither, color = true),
                "$dither",
            )
        }
    }

    @Test
    fun yellowAndRedPackAsIndices2And3() {
        for ((index, packed) in listOf(2 to 0xAA, 3 to 0xFF)) {
            val display = LabelDisplay.EZ_SIGN_4IN2
            val view = ByteArray(display.viewWidth * display.viewHeight) { index.toByte() }

            val frame = packFrame(ezSignLabelPixels(display, view), infoFrom(A0_42))

            assertTrue(frame.all { it == packed.toByte() }, "index $index")
        }
    }

    /** One black pixel at each view corner in [corners] lands at its native pixel, and only there. */
    private fun assertCornersLand(display: LabelDisplay, corners: Map<Pair<Int, Int>, Pair<Int, Int>>) {
        for ((view, native) in corners) {
            val white = ByteArray(display.viewWidth * display.viewHeight) { WHITE }
            white[view.second * display.viewWidth + view.first] = BLACK

            val pixels = ezSignLabelPixels(display, white)

            val expected = native.second * display.nativeWidth + native.first
            assertEquals(listOf(expected), pixels.indices.filter { pixels[it] == 0.toByte() }, "view $view")
            assertTrue(pixels.all { it == 0.toByte() || it == 1.toByte() })
        }
    }
}
