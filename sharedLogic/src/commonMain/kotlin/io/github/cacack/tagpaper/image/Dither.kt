package io.github.cacack.tagpaper.image

/** Grey level at and above which a pixel counts as white by default. */
private const val MID_GREY = 128

/** How greyscale is reduced to black and white. */
sealed interface Dither {
    /** White where the pixel is at least [level] (0–255). */
    data class Threshold(val level: Int = DEFAULT_LEVEL) : Dither {
        init {
            require(level in 0..255) { "Threshold level must be 0–255, was $level" }
        }

        companion object {
            /** Mid-grey; UIs start a threshold control here. */
            const val DEFAULT_LEVEL = MID_GREY
        }
    }

    /** Error diffusion around mid-grey; renders photos and gradients as patterns of dots. */
    data object FloydSteinberg : Dither
}

/** Reduces to black and white, row-major; `true` is white. */
fun GrayImage.toBlackWhite(dither: Dither): BooleanArray = when (dither) {
    is Dither.Threshold -> BooleanArray(width * height) { gray(it) >= dither.level }
    Dither.FloydSteinberg -> floydSteinberg(this)
}

private fun floydSteinberg(image: GrayImage): BooleanArray {
    val width = image.width
    val height = image.height
    val work = IntArray(width * height) { image.gray(it) }
    val white = BooleanArray(work.size)

    fun spread(x: Int, y: Int, amount: Int) {
        if (x in 0 until width && y < height) work[y * width + x] += amount
    }

    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            val value = work[i].coerceIn(0, 255)
            white[i] = value >= MID_GREY
            val error = value - if (white[i]) 255 else 0
            spread(x + 1, y, error * 7 / 16)
            spread(x - 1, y + 1, error * 3 / 16)
            spread(x, y + 1, error * 5 / 16)
            spread(x + 1, y + 1, error / 16)
        }
    }
    return white
}
