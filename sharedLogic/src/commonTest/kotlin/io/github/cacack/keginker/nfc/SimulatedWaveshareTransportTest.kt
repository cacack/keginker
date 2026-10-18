package io.github.cacack.keginker.nfc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class SimulatedWaveshareTransportTest {
    @Test
    fun losesTheTagAfterTheGivenNumberOfCommands() = runTest {
        val transport = SimulatedWaveshareTransport(commandDelayMs = 0, failAfterCommands = 2)

        repeat(2) { transport.transceive(byteArrayOf(0xCD.toByte(), 0x0D), 0) }

        val lost = assertFailsWith<SimulatedTagLostException> { transport.transceive(byteArrayOf(0xCD.toByte(), 0x0D), 0) }
        assertEquals("Tag was lost", lost.message)
    }
}
