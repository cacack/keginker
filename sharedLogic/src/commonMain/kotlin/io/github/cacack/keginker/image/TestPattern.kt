package io.github.cacack.keginker.image

/**
 * A pattern for checking a panel by eye: a black border, a solid triangle in
 * the top-left corner to show orientation, a left-to-right grey ramp to show
 * dithering, and a checkerboard along the bottom to show sharpness.
 */
fun testPattern(width: Int, height: Int): GrayImage {
    require(width >= 2 && height >= 2) { "Pattern must be at least 2×2, was ${width}×$height" }
    val border = maxOf(2, width / 40)
    val marker = width / 3
    val rampTop = height * 2 / 5
    val rampBottom = height * 3 / 5
    val checkerTop = height * 4 / 5
    val cell = maxOf(4, width / 16)
    val pixels = ByteArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val gray = when {
            x < border || y < border || x >= width - border || y >= height - border -> 0
            x + y < marker -> 0
            y in rampTop until rampBottom -> x * 255 / (width - 1)
            y >= checkerTop -> if ((x / cell + y / cell) % 2 == 0) 0 else 255
            else -> 255
        }
        gray.toByte()
    }
    return GrayImage(width, height, pixels)
}
