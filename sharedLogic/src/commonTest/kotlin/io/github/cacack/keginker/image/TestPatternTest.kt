package io.github.cacack.keginker.image

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
