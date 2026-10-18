package io.github.cacack.keginker.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestPatternTest {
    private val pattern = testPattern(176, 264)

    @Test
    fun hasTheRequestedSize() {
        assertEquals(176, pattern.width)
        assertEquals(264, pattern.height)
    }

    @Test
    fun topLeftIsMarkedButTopRightIsNot() {
        assertEquals(0, pattern[20, 20])
        assertEquals(255, pattern[155, 20])
    }

    @Test
    fun rampBrightensRightward() {
        val y = 132
        assertTrue(pattern[10, y] < pattern[90, y])
        assertTrue(pattern[90, y] < pattern[165, y])
    }

    // At 176 × 264 (see testPattern): border = 176 / 40 = 4 px, the marker
    // where x + y < 176 / 3 = 58, the ramp on rows 264·2/5 = 105 to
    // 264·3/5 - 1 = 157 with grey x · 255 / 175 (145 at x = 100, 5 at x = 4,
    // 249 at x = 171), and 176 / 16 = 11 px checker cells from row 264·4/5 = 211.

    @Test
    fun borderIsFourPixelsOfBlackOnEverySide() {
        assertEquals(listOf(0, 255), listOf(pattern[3, 80], pattern[4, 80]))
        assertEquals(listOf(255, 0), listOf(pattern[171, 80], pattern[172, 80]))
        assertEquals(listOf(0, 255), listOf(pattern[88, 3], pattern[88, 4]))
        assertEquals(listOf(255, 0), listOf(pattern[88, 259], pattern[88, 260]))
    }

    @Test
    fun markerIsTheTriangleBelowAThirdOfTheWidth() {
        assertEquals(listOf(0, 255), listOf(pattern[30, 27], pattern[30, 28]))
    }

    @Test
    fun rampFillsTheMiddleFifthFromBlackToWhite() {
        assertEquals(listOf(255, 145, 145, 255), listOf(104, 105, 157, 158).map { pattern[100, it] })
        assertEquals(listOf(5, 249), listOf(pattern[4, 132], pattern[171, 132]))
    }

    @Test
    fun checkerboardFillsTheBottomFifthInElevenPixelCells() {
        assertEquals(255, pattern[33, 210])
        assertEquals(listOf(255, 0, 0, 255), listOf(32 to 211, 33 to 211, 43 to 211, 44 to 211).map { (x, y) -> pattern[x, y] })
        assertEquals(listOf(0, 255), listOf(pattern[33, 219], pattern[33, 220]))
        assertEquals(listOf(0, 0, 0), listOf(34 to 211, 33 to 212, 43 to 219).map { (x, y) -> pattern[x, y] }, "A cell is solid")
    }

    @Test
    fun rejectsPatternsSmallerThanTwoByTwo() {
        assertEquals(2 to 2, testPattern(2, 2).let { it.width to it.height })
        assertFailsWith<IllegalArgumentException> { testPattern(1, 2) }
        assertFailsWith<IllegalArgumentException> { testPattern(2, 1) }
    }
}
