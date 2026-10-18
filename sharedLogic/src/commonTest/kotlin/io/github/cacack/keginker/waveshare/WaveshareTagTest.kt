package io.github.cacack.keginker.waveshare

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaveshareTagTest {
    @Test
    fun historicalBytesOfJustTheOsNameAreRev22() {
        assertTrue(isWaveshareIsoDepTag("YZRCOS".encodeToByteArray()))
    }

    @Test
    fun aTruncatedOsNameIsNotRev22() {
        assertFalse(isWaveshareIsoDepTag("YZRCO".encodeToByteArray()))
    }
}
