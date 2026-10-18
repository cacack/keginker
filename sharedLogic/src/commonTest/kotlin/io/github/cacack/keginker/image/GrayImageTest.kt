package io.github.cacack.keginker.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

        for ((x, y) in listOf(2 to 0, 0 to 2, -1 to 0, 0 to -1)) {
            assertFailsWith<IllegalArgumentException>("($x, $y)") { image[x, y] }
        }
    }

    @Test
    fun rejectsAnEmptyImage() {
        assertFailsWith<IllegalArgumentException> { GrayImage(0, 3, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { GrayImage(3, 0, ByteArray(0)) }
    }

    @Test
    fun rejectsSizesThatOverflowInt() {
        assertFailsWith<IllegalArgumentException> { GrayImage.fromArgb(65_536, 65_536, IntArray(0)) }
    }

    @Test
    fun anyFullyTransparentColourIsWhite() = forEachSeed { random ->
        val rgb = random.nextInt() and 0xFFFFFF

        assertEquals(255, GrayImage.fromArgb(1, 1, intArrayOf(rgb))[0, 0], "Colour ${rgb.toString(16)}")
    }

    @Test
    fun greyNeverDecreasesAsAChannelIncreases() = forEachSeed { random ->
        val argb = random.nextInt()
        val shift = listOf(16, 8, 0).random(random)
        val channel = (argb shr shift) and 0xFF
        val raised = (argb and (0xFF shl shift).inv()) or (random.nextInt(channel, 256) shl shift)

        val grey = GrayImage.fromArgb(1, 1, intArrayOf(argb))[0, 0]
        val raisedGrey = GrayImage.fromArgb(1, 1, intArrayOf(raised))[0, 0]

        assertTrue(raisedGrey >= grey, "${argb.toUInt().toString(16)} → ${raised.toUInt().toString(16)}: $grey → $raisedGrey")
    }
}
