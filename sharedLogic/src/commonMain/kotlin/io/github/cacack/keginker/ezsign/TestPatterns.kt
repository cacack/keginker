package io.github.cacack.keginker.ezsign

/** A multiple-choice question about what a test write showed; the answer is one of [options]. */
class TestQuestion internal constructor(
    /** Stable across releases, for reports. */
    val id: String,
    val text: String,
    val options: List<String>,
)

/**
 * An image a test write sends, and what to ask about it afterwards.
 * [pixels] are color indices, row-major in the panel's native layout,
 * [width] wide, so the write makes no assumption about orientation.
 */
class TestWritePattern internal constructor(
    /** Stable across releases, for reports. */
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    /** Asked in order once the pattern is on the display. */
    val questions: List<TestQuestion>,
)

private const val BLACK: Byte = 0
private const val WHITE: Byte = 1

/** Pixel rows the corner pattern's line covers. */
internal const val CORNER_LINE_ROWS = 4

private const val HOLD_IT = "Hold the display the way up you'd read it."

/** The patterns for a panel: the corner, then the color bands when it has 4 colors. */
internal fun ezSignTestPatterns(info: EzSignDeviceInfo): List<TestWritePattern> =
    listOfNotNull(cornerPattern(info), if (info.bitsPerPixel == 2) colorPattern(info) else null)

/**
 * White, with a black square at native (0, 0) a quarter of the short side
 * across, and a black line along native rows 0–3 from the square to the
 * middle of the row. Where the square lands shows how native (0, 0) is
 * turned; which edge the line follows shows which way native rows run.
 */
internal fun cornerPattern(info: EzSignDeviceInfo): TestWritePattern {
    val width = info.nativeWidth
    val height = info.nativeHeight
    val side = minOf(width, height) / 4
    val lineEnd = width / 2
    val pixels = ByteArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if ((x < side && y < side) || (y < CORNER_LINE_ROWS && x < lineEnd)) BLACK else WHITE
    }
    return TestWritePattern(
        id = "corner",
        title = "Corner and line",
        width = width,
        height = height,
        pixels = pixels,
        questions = listOf(
            // Without it, "top left" can't be turned into native coordinates:
            // a portrait and a landscape hold put the same corner in different places.
            TestQuestion(
                id = "hold",
                text = "$HOLD_IT Which way is its long edge?",
                options = listOf("Across: it's wider than it's tall", "Up and down: it's taller than it's wide"),
            ),
            TestQuestion(
                id = "square",
                text = "Where is the black square?",
                options = listOf("Top left", "Top right", "Bottom left", "Bottom right", "No square, or something else"),
            ),
            TestQuestion(
                id = "line",
                text = "Which way does the line from it run?",
                options = listOf("Along the top or bottom edge", "Along a side edge", "No line"),
            ),
        ),
    )
}

/** Names of the color bands' positions, from the edge the corner pattern's line ran along. */
private val BAND_ORDINALS = listOf("first", "second", "third", "fourth")

/**
 * Four equal bands along native rows, band k in color index k, band 0
 * starting at native row 0: the edge the corner pattern's square and line
 * were on. Only for 4-color (2 bits per pixel) panels.
 */
internal fun colorPattern(info: EzSignDeviceInfo): TestWritePattern {
    require(info.bitsPerPixel == 2) { "Color bands need 4 colors; the panel has ${1 shl info.bitsPerPixel}" }
    val width = info.nativeWidth
    val height = info.nativeHeight
    val pixels = ByteArray(width * height) { i -> (i / width * 4 / height).toByte() }
    return TestWritePattern(
        id = "colors",
        title = "Color bands",
        width = width,
        height = height,
        pixels = pixels,
        questions = BAND_ORDINALS.mapIndexed { band, ordinal ->
            TestQuestion(
                id = "band${band + 1}",
                text = listOfNotNull(
                    if (band == 0) HOLD_IT else null,
                    "Starting from the edge where the square and line were, what color is the $ordinal band?",
                ).joinToString(" "),
                options = listOf("Black", "White", "Yellow", "Red", "Other"),
            )
        },
    )
}
