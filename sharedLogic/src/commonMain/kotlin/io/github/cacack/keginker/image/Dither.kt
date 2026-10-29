package io.github.cacack.keginker.image

/** Gray level at and above which a pixel counts as white by default. */
private const val MID_GRAY = 128

/** How grayscale is reduced to black and white. */
sealed interface Dither {
    /** White where the pixel is at least [level] (0–255). */
    data class Threshold(val level: Int = DEFAULT_LEVEL) : Dither {
        init {
            require(level in 0..255) { "Threshold level must be 0–255, was $level" }
        }

        companion object {
            /** Mid-gray; UIs start a threshold control here. */
            const val DEFAULT_LEVEL = MID_GRAY
        }
    }

    /** Error diffusion around mid-gray; renders photos and gradients as patterns of dots. */
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
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            val value = work[i].coerceIn(0, 255)
            white[i] = value >= MID_GRAY
            spreadError(work, width, height, x, y, value - if (white[i]) 255 else 0)
        }
    }
    return white
}

/** Spreads [error] at ([x], [y]) of [work], [width] × [height], to the neighbors not yet visited. */
private fun spreadError(work: IntArray, width: Int, height: Int, x: Int, y: Int, error: Int) {
    fun spread(x: Int, y: Int, amount: Int) {
        if (x in 0 until width && y < height) work[y * width + x] += amount
    }

    val right = error * 7 / 16
    val belowLeft = error * 3 / 16
    val below = error * 5 / 16
    spread(x + 1, y, right)
    spread(x - 1, y + 1, belowLeft)
    spread(x, y + 1, below)
    // The remainder, so rounding loses none of the error: otherwise
    // grays within a few levels of black or white vanish.
    spread(x + 1, y + 1, error - right - belowLeft - below)
}

/**
 * Saturation (largest channel less smallest) below which a pixel counts as
 * gray and becomes black or white, never a color, so grays and text don't
 * pick up colored dots.
 */
private const val MIN_CHROMA = 128

/**
 * Reduces to [palette]'s indices, row-major. A gray pixel (see [MIN_CHROMA])
 * becomes black or white by its luma, at the threshold level or mid-gray;
 * any other becomes the nearest palette color. Floyd–Steinberg spreads each
 * channel's error as [toBlackWhite] spreads gray's, so a gray image comes out
 * as it would in black and white.
 */
fun ColorImage.toPalette(palette: Palette, dither: Dither): ByteArray {
    val level = (dither as? Dither.Threshold)?.level ?: MID_GRAY
    val colors = palette.colors.map { intArrayOf(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }
    val channels = listOf(red, green, blue).map { channel -> IntArray(width * height) { channel.gray(it) } }
    val out = ByteArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            val rgb = IntArray(3) { channels[it][i].coerceIn(0, 255) }
            val index = nearest(rgb, colors, level)
            out[i] = index.toByte()
            if (dither == Dither.FloydSteinberg) {
                for (c in 0 until 3) spreadError(channels[c], width, height, x, y, rgb[c] - colors[index][c])
            }
        }
    }
    return out
}

/** The palette index for [rgb]: black or white by luma against [level] if it's gray, else the nearest of [colors]. */
private fun nearest(rgb: IntArray, colors: List<IntArray>, level: Int): Int {
    if (rgb.max() - rgb.min() < MIN_CHROMA) {
        val luma = (77 * rgb[0] + 150 * rgb[1] + 29 * rgb[2]) shr 8
        return if (luma >= level) WHITE.toInt() else BLACK.toInt()
    }
    return colors.indices.minBy { k -> (0 until 3).sumOf { (rgb[it] - colors[k][it]).let { d -> d * d } } }
}
