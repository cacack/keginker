package io.github.cacack.keginker.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun gray(width: Int, height: Int, vararg values: Int) =
    GrayImage(width, height, ByteArray(values.size) { values[it].toByte() })

private fun GrayImage.values() = (0 until height).flatMap { y -> (0 until width).map { x -> this[x, y] } }

class ResizeTest {
    @Test
    fun cropsAWideImageToItsCenter() {
        val wide = gray(4, 2, 0, 100, 200, 50, 0, 100, 200, 50)

        assertEquals(listOf(100, 200, 100, 200), wide.fill(2, 2).values())
    }

    @Test
    fun downscalingAveragesEachBlock() {
        val image = gray(
            4, 4,
            0, 40, 200, 200,
            80, 120, 200, 200,
            10, 10, 255, 255,
            10, 10, 255, 255,
        )

        assertEquals(listOf(60, 200, 10, 255), image.fill(2, 2).values())
    }

    @Test
    fun upscalingRepeatsPixels() {
        assertEquals(listOf(42, 42, 42, 42), gray(1, 1, 42).fill(2, 2).values())
    }

    @Test
    fun cropsATallImageToItsMiddle() {
        val tall = gray(2, 4, 0, 0, 100, 100, 200, 200, 50, 50)

        assertEquals(listOf(100, 100, 200, 200), tall.fill(2, 2).values())
    }

    @Test
    fun downscalingByAnUnevenRatioWeightsPartlyCoveredPixels() {
        val image = gray(
            3, 3,
            0, 30, 60,
            90, 120, 150,
            180, 210, 240,
        )

        // Each output cell covers 1.5 source rows and columns: one whole, one half.
        // Top-left: (4·0 + 2·30 + 2·90 + 1·120) / 9 = 40.
        assertEquals(listOf(40, 80, 160, 200), image.fill(2, 2).values())
    }

    @Test
    fun rejectsAnEmptyTarget() {
        assertFailsWith<IllegalArgumentException> { gray(1, 1, 42).fill(0, 1) }
        assertFailsWith<IllegalArgumentException> { gray(1, 1, 42).fill(1, 0) }
    }

    @Test
    fun outputIsAlwaysTheTargetSize() = forEachSeed { random ->
        val width = random.nextInt(1, 41)
        val height = random.nextInt(1, 41)

        val out = random.nextGrayImage().fill(width, height)

        assertEquals(width to height, out.width to out.height)
    }

    @Test
    fun aUniformImageStaysUniform() = forEachSeed { random ->
        val gray = random.nextInt(0, 256)
        val width = random.nextInt(1, 41)
        val height = random.nextInt(1, 41)
        val image = GrayImage(width, height, ByteArray(width * height) { gray.toByte() })

        val out = image.fill(random.nextInt(1, 41), random.nextInt(1, 41))

        assertTrue(out.grays().all { it == gray }, "Expected all $gray, got ${out.grays()}")
    }

    @Test
    fun mirroringTheInputMirrorsTheOutput() = forEachSeed { random ->
        val image = random.nextGrayImage()
        val width = random.nextInt(1, 41)
        val height = random.nextInt(1, 41)

        assertEquals(image.fill(width, height).mirrored().grays(), image.mirrored().fill(width, height).grays(), "Left to right")
        assertEquals(image.fill(width, height).flipped().grays(), image.flipped().fill(width, height).grays(), "Top to bottom")
    }

    @Test
    fun anImageAlreadyAtTheTargetSizeComesBackUnchanged() = forEachSeed { random ->
        val image = random.nextGrayImage()

        assertEquals(image.grays(), image.fill(image.width, image.height).grays())
    }
}
