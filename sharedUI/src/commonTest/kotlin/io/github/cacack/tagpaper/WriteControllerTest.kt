package io.github.cacack.tagpaper

import io.github.cacack.tagpaper.nfc.FoundTag
import io.github.cacack.tagpaper.nfc.Transport
import io.github.cacack.tagpaper.waveshare.WaveshareWriter
import io.github.cacack.tagpaper.waveshare.WavesharePanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PANEL = WavesharePanel.EPD_2IN7

/** A tag that accepts everything, or drops out after [failAfter] commands. */
private class FakeTag(private val failAfter: Int = Int.MAX_VALUE) : Transport {
    var sent = 0
    var closed = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (sent++ >= failAfter) throw IllegalStateException("Tag was lost")
        val busyPoll = command[1] == 0x0A.toByte()
        return if (busyPoll) byteArrayOf(0xFF.toByte(), 0x00) else byteArrayOf(0x00, 0x00)
    }

    override fun close() {
        closed = true
    }
}

private fun controller() = WriteController(PANEL) { WaveshareWriter(it.transport, pause = {}) }

class WriteControllerTest {
    @Test
    fun ignoresTagsUntilArmed() = runTest {
        val tag = FakeTag()
        val controller = controller()

        controller.onTag(FoundTag.Waveshare(tag))

        assertEquals(WriteState.Idle, controller.state.value)
        assertEquals(0, tag.sent)
        assertTrue(tag.closed)
    }

    @Test
    fun armedWriteSucceedsAndClosesTheTag() = runTest {
        val tag = FakeTag()
        val controller = controller()

        controller.arm(ByteArray(PANEL.frameSize))
        controller.onTag(FoundTag.Waveshare(tag))

        assertEquals(WriteState.Done, controller.state.value)
        assertTrue(tag.closed)
    }

    @Test
    fun lostTagFailsWithStepAndRetrySucceeds() = runTest {
        val controller = controller()
        controller.arm(ByteArray(PANEL.frameSize))

        controller.onTag(FoundTag.Waveshare(FakeTag(failAfter = 20)))

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertTrue("sending the image" in failed.message, failed.message)

        controller.arm(ByteArray(PANEL.frameSize))
        controller.onTag(FoundTag.Waveshare(FakeTag()))

        assertEquals(WriteState.Done, controller.state.value)
    }

    @Test
    fun unsupportedTagFailsWithoutWriting() = runTest {
        val controller = controller()
        controller.arm(ByteArray(PANEL.frameSize))

        controller.onTag(FoundTag.Unsupported(byteArrayOf(4, 1, 2, 3, 4, 5, 6)))

        assertIs<WriteState.Failed>(controller.state.value)
    }

    @Test
    fun aSecondTagAfterSuccessIsIgnored() = runTest {
        val controller = controller()
        controller.arm(ByteArray(PANEL.frameSize))
        controller.onTag(FoundTag.Waveshare(FakeTag()))
        val second = FakeTag()

        controller.onTag(FoundTag.Waveshare(second))

        assertEquals(0, second.sent)
    }
}
