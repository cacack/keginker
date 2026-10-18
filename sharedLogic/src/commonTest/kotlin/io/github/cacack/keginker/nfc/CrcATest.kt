package io.github.cacack.keginker.nfc

import kotlin.test.Test
import kotlin.test.assertContentEquals

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

class CrcATest {
    @Test
    fun readPage0() {
        // MIFARE Ultralight READ 0, as captured on the air: 30 00 02 A8.
        assertContentEquals(bytes(0x02, 0xA8), crcA(bytes(0x30, 0x00)))
    }

    @Test
    fun halt() {
        // ISO 14443-3A HLTA: 50 00 57 CD.
        assertContentEquals(bytes(0x57, 0xCD), crcA(bytes(0x50, 0x00)))
    }
}
