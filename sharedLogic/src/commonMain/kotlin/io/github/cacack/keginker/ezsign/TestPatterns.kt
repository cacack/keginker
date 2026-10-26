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
 * [pixels] are colour indices, row-major in the panel's native layout,
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

/** The patterns for a panel: the corner, then the colour bands when it has 4 colours. */
internal fun ezSignTestPatterns(info: EzSignDeviceInfo): List<TestWritePattern> =
    listOfNotNull(cornerPattern(info), if (info.bitsPerPixel == 2) colourPattern(info) else null)

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
            TestQuestion(
                id = "square",
                text = "$HOLD_IT Where is the black square?",
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

/** Names of the colour bands' positions, from the edge the corner pattern's line ran along. */
private val BAND_ORDINALS = listOf("first", "second", "third", "fourth")

/**
 * Four equal bands along native rows, band k in colour index k, band 0
 * starting at native row 0: the edge the corner pattern's square and line
 * were on. Only for 4-colour (2 bits per pixel) panels.
 */
internal fun colourPattern(info: EzSignDeviceInfo): TestWritePattern {
    require(info.bitsPerPixel == 2) { "Colour bands need 4 colours; the panel has ${1 shl info.bitsPerPixel}" }
    val width = info.nativeWidth
    val height = info.nativeHeight
    val pixels = ByteArray(width * height) { i -> (i / width * 4 / height).toByte() }
    return TestWritePattern(
        id = "colours",
        title = "Colour bands",
        width = width,
        height = height,
        pixels = pixels,
        questions = BAND_ORDINALS.mapIndexed { band, ordinal ->
            TestQuestion(
                id = "band${band + 1}",
                text = listOfNotNull(
                    if (band == 0) HOLD_IT else null,
                    "Starting from the edge where the square and line were, what colour is the $ordinal band?",
                ).joinToString(" "),
                options = listOf("Black", "White", "Yellow", "Red", "Other"),
            )
        },
    )
}
