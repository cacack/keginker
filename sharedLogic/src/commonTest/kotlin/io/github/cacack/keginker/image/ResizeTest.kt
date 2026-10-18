package io.github.cacack.keginker.image

import kotlin.test.Test
import kotlin.test.assertEquals

private fun gray(width: Int, height: Int, vararg values: Int) =
    GrayImage(width, height, ByteArray(values.size) { values[it].toByte() })

private fun GrayImage.values() = (0 until height).flatMap { y -> (0 until width).map { x -> this[x, y] } }

class ResizeTest {
    @Test
    fun cropsAWideImageToItsCentre() {
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
    fun downscalingByAnUnevenRatioAveragesUnevenBlocks() {
        val image = gray(
            3, 3,
            0, 30, 60,
            90, 120, 150,
            180, 210, 240,
        )

        // Output cells cover source rows/columns [0] and [1, 2].
        assertEquals(listOf(0, 45, 135, 180), image.fill(2, 2).values())
    }
}
