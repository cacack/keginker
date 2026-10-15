package io.github.cacack.tagpaper.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrayImageTest {
    @Test
    fun convertsArgbToLuminanceOverWhite() {
        val argb = intArrayOf(
            0xFFFFFFFF.toInt(), // opaque white
            0xFF000000.toInt(), // opaque black
            0x00000000, // fully transparent: shows the white panel
            0xFF808080.toInt(), // mid grey
        )

        val image = GrayImage.fromArgb(4, 1, argb)

        assertEquals(listOf(255, 0, 255, 128), (0 until 4).map { image[it, 0] })
    }

    @Test
    fun compositesPartialTransparencyOverWhite() {
        val argb = intArrayOf(0x80000000.toInt(), 0x80FFFFFF.toInt()) // half-transparent black, white

        val image = GrayImage.fromArgb(2, 1, argb)

        assertEquals(listOf(127, 255), listOf(image[0, 0], image[1, 0]))
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        val image = GrayImage(2, 2, ByteArray(4))

        assertFailsWith<IllegalArgumentException> { image[2, 0] }
    }

    @Test
    fun rejectsSizesThatOverflowInt() {
        assertFailsWith<IllegalArgumentException> { GrayImage.fromArgb(65_536, 65_536, IntArray(0)) }
    }
}
