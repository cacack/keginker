package io.github.cacack.keginker.image

/** Palette index of black, in every [Palette]. */
const val BLACK: Byte = 0

/** Palette index of white, in every [Palette]. */
const val WHITE: Byte = 1

/**
 * The colours a display can show, by the index its panel memory holds them
 * at. Views (from `renderView`) are row-major arrays of these indices.
 */
enum class Palette(
    /** `0xRRGGBB` by palette index: dithering aims for these, and previews show them. */
    val colours: List<Int>,
) {
    BLACK_WHITE(listOf(0x000000, 0xFFFFFF)),

    /** EZ Sign panels: index 2 yellow and 3 red (#130, #131). */
    BLACK_WHITE_YELLOW_RED(listOf(0x000000, 0xFFFFFF, 0xFFFF00, 0xFF0000)),
    ;

    /** Whether it has colours beyond black and white. */
    val hasColour: Boolean get() = colours.size > 2

    /** [view] as opaque `0xAARRGGBB` pixels, for previewing. */
    fun argb(view: ByteArray): IntArray = IntArray(view.size) { colours[view[it].toInt()] or OPAQUE }
}

private const val OPAQUE = 0xFF shl 24
