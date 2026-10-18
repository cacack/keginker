package io.github.cacack.keginker.nfc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SwiftInteropTest {
    @Test
    fun kotlinIntsReadsNativeEndianInts() {
        // iOS devices and simulators are little-endian.
        val data = nsData(byteArrayOf(0x44, 0x33, 0x22, 0x11, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

        assertContentEquals(intArrayOf(0x11223344, -1), kotlinInts(data))
    }

    @Test
    fun kotlinIntsOfNoBytesIsEmpty() {
        assertContentEquals(intArrayOf(), kotlinInts(nsData(byteArrayOf())))
    }

    @Test
    fun kotlinIntsRejectsAPartialInt() {
        assertFailsWith<IllegalArgumentException> { kotlinInts(nsData(byteArrayOf(1, 2, 3))) }
    }
}
