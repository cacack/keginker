package io.github.cacack.keginker.ezsign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pixel at native ([x], [y]). */
private fun TestWritePattern.at(x: Int, y: Int): Int = pixels[y * width + x].toInt()

/** The set of native pixels with colour index [index]. */
private fun TestWritePattern.pixelsOf(index: Int): Set<Pair<Int, Int>> =
    pixels.indices.filter { pixels[it].toInt() == index }.map { it % width to it / width }.toSet()

private fun rect(xs: IntRange, ys: IntRange) = xs.flatMap { x -> ys.map { y -> x to y } }.toSet()

class TestPatternsTest {
    @Test
    fun theCornerPatternOnANative128By296Panel() {
        val pattern = cornerPattern(PANEL_29)

        assertEquals(128 to 296, pattern.width to pattern.height)
        // A 32-pixel square (a quarter of 128) at native (0, 0), and rows 0–3 on to x = 63, the row's middle.
        assertEquals(rect(0..31, 0..31) + rect(32..63, 0..3), pattern.pixelsOf(0))
        assertEquals(128 * 296 - 32 * 32 - 32 * 4, pattern.pixelsOf(1).size)
    }

    @Test
    fun theCornerPatternOnANative400By300Panel() {
        val pattern = cornerPattern(PANEL_42)

        assertEquals(400 to 300, pattern.width to pattern.height)
        // A 75-pixel square (a quarter of 300), and rows 0–3 on to x = 199, the middle of the long side.
        assertEquals(rect(0..74, 0..74) + rect(75..199, 0..3), pattern.pixelsOf(0))
        assertEquals(400 * 300 - 75 * 75 - 125 * 4, pattern.pixelsOf(1).size)
    }

    @Test
    fun theColourBandsRunAlongNativeRowsFromRow0() {
        for ((info, bandRows) in listOf(PANEL_29 to 74, PANEL_42 to 75)) {
            val pattern = colourPattern(info)

            for (y in 0 until pattern.height) {
                val band = y / bandRows
                assertTrue((0 until pattern.width).all { pattern.at(it, y) == band }, "row $y of ${info.nativeWidth} × ${info.nativeHeight}")
            }
        }
    }

    @Test
    fun aFourColourPanelGetsBothPatternsAndATwoColourOneOnlyTheCorner() {
        assertEquals(listOf("corner", "colours"), ezSignTestPatterns(PANEL_42).map { it.id })
        assertEquals(listOf("corner"), ezSignTestPatterns(PANEL_MONO_122).map { it.id })
    }

    @Test
    fun eachPatternAsksItsQuestionsHeldTheWayUpYouReadIt() {
        val (corner, colours) = ezSignTestPatterns(PANEL_42)

        assertEquals(listOf("square", "line"), corner.questions.map { it.id })
        assertEquals(
            listOf("Top left", "Top right", "Bottom left", "Bottom right", "No square, or something else"),
            corner.questions[0].options,
        )
        assertEquals(listOf("Along the top or bottom edge", "Along a side edge", "No line"), corner.questions[1].options)
        assertEquals(listOf("band1", "band2", "band3", "band4"), colours.questions.map { it.id })
        assertTrue(colours.questions.all { it.options == listOf("Black", "White", "Yellow", "Red", "Other") })
        for (pattern in listOf(corner, colours)) {
            assertTrue(pattern.questions.first().text.startsWith("Hold the display the way up you'd read it."), pattern.id)
        }
    }
}
