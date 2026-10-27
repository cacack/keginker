package io.github.cacack.keginker.image

/**
 * An image in colour, as its [red], [green] and [blue] channels, plus the
 * [gray] that black-and-white rendering uses. [gray] is converted from the
 * source pixels just as [GrayImage.fromArgb] does, so a label rendered in
 * black and white is the same as from a [GrayImage].
 */
class ColourImage(val gray: GrayImage, val red: GrayImage, val green: GrayImage, val blue: GrayImage) {
    init {
        require(listOf(red, green, blue).all { it.width == gray.width && it.height == gray.height }) {
            "Channels must all be ${gray.width}×${gray.height}"
        }
    }

    val width: Int get() = gray.width
    val height: Int get() = gray.height

    companion object {
        /**
         * Converts non-premultiplied `0xAARRGGBB` pixels (as from Android
         * `Bitmap.getPixels`), compositing transparency over white.
         *
         * Memory scales with the source, so decode large photos downsampled
         * (to a few megapixels at most) before calling this.
         */
        fun fromArgb(width: Int, height: Int, argb: IntArray): ColourImage {
            val gray = GrayImage.fromArgb(width, height, argb)
            fun channel(shift: Int) = GrayImage(width, height, ByteArray(argb.size) { overWhite(argb[it], shift).toByte() })
            return ColourImage(gray, channel(16), channel(8), channel(0))
        }

        /** [image] in colour: every channel is its grey. */
        fun fromGray(image: GrayImage): ColourImage = ColourImage(image, image, image, image)

        /** The channel at [shift] of [argb], composited over white. */
        private fun overWhite(argb: Int, shift: Int): Int {
            val a = (argb ushr 24) and 0xFF
            val c = (argb shr shift) and 0xFF
            return (c * a + 255 * (255 - a)) / 255
        }
    }
}

/** Every channel [fill]ed to exactly [width] × [height]; a grey image's one channel, once. */
fun ColourImage.fill(width: Int, height: Int): ColourImage =
    if (red === gray && green === gray && blue === gray) {
        ColourImage.fromGray(gray.fill(width, height))
    } else {
        ColourImage(gray.fill(width, height), red.fill(width, height), green.fill(width, height), blue.fill(width, height))
    }
