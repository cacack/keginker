package io.github.cacack.keginker.image

/**
 * An 8-bit grayscale image, row-major; 0 is black and 255 is white.
 *
 * [pixels] holds unsigned values in a signed [ByteArray]; read them through
 * [get] or [gray]. The array is not copied, so don't modify it afterwards.
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "Image must be at least 1×1, was ${width}×$height" }
        require(width.toLong() * height == pixels.size.toLong()) {
            "A ${width}×$height image needs ${width.toLong() * height} pixels, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) is outside ${width}×$height" }
        return gray(y * width + x)
    }

    /** The 0–255 value at row-major [index]. */
    fun gray(index: Int): Int = pixels[index].toInt() and 0xFF

    companion object {
        /**
         * Converts non-premultiplied `0xAARRGGBB` pixels (as from Android
         * `Bitmap.getPixels`), compositing transparency over white.
         *
         * Memory scales with the source, so decode large photos downsampled
         * (to a few megapixels at most) before calling this.
         */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            require(width > 0 && height > 0) { "Image must be at least 1×1, was ${width}×$height" }
            require(width.toLong() * height == argb.size.toLong()) {
                "A ${width}×$height image needs ${width.toLong() * height} pixels, got ${argb.size}"
            }
            return GrayImage(width, height, ByteArray(argb.size) { luminance(argb[it]).toByte() })
        }

        /** Integer Rec. 601 luma, composited over a white background. */
        private fun luminance(argb: Int): Int {
            val a = (argb ushr 24) and 0xFF
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val luma = (77 * r + 150 * g + 29 * b) shr 8
            return (luma * a + 255 * (255 - a)) / 255
        }
    }
}
