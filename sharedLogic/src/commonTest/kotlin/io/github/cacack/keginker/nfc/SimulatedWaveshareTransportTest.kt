package io.github.cacack.keginker.nfc

import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class SimulatedWaveshareTransportTest {
    @Test
    fun losesTheTagAfterTheGivenNumberOfCommands() = runTest {
        val transport = SimulatedWaveshareTransport(commandDelayMs = 0, failAfterCommands = 2)

        repeat(2) { transport.transceive(byteArrayOf(0xCD.toByte(), 0x0D), 0) }

        val lost = assertFailsWith<SimulatedTagLostException> { transport.transceive(byteArrayOf(0xCD.toByte(), 0x0D), 0) }
        assertEquals("Tag was lost", lost.message)
    }

    @Test
    fun simulatedFailureLandsWhileSendingTheImage() = runTest {
        val panel = WavesharePanel.EPD_2IN7
        val transport = SimulatedWaveshareTransport(0, SimulatedWaveshareTransport.FAIL_WHILE_SENDING_IMAGE)
        val view = BooleanArray(panel.viewWidth * panel.viewHeight) { true }

        val result = ImageWriter(pause = {}).write(FoundTag.Waveshare(transport), panel, view) {}

        assertEquals(WriteStep.SEND_DATA, assertIs<WriteResult.Failure>(result).step)
    }
}
