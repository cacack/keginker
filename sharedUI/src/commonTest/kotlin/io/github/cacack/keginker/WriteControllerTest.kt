package io.github.cacack.keginker

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.WriteStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
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

/** A Rev2.2 tag: every APDU answers `90 00`, and the busy poll says idle. */
private class FakeIsoDepTag : Transport {
    val sent = mutableListOf<ByteArray>()
    var closed = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        val busyPoll = command[1] == 0x9B.toByte()
        return if (busyPoll) byteArrayOf(0x00, 0x90.toByte(), 0x00) else byteArrayOf(0x90.toByte(), 0x00)
    }

    override fun close() {
        closed = true
    }
}

private fun testView() = BooleanArray(PANEL.viewWidth * PANEL.viewHeight) { true }

private fun controller() = WriteController(PANEL, ImageWriter(pause = {}))

class WriteControllerTest {
    @Test
    fun rev22TagIsWrittenWithItsOwnProtocol() = runTest {
        val tag = FakeIsoDepTag()
        val controller = controller()
        controller.arm(testView())

        controller.onTag(FoundTag.WaveshareIsoDep(tag))

        assertEquals(WriteState.Done, controller.state.value)
        // The Rev2.2 sequence starts by unlocking the chip.
        assertEquals(0xB1.toByte(), tag.sent.first()[1])
        assertTrue(tag.sent.all { it[0] == 0x74.toByte() })
        assertTrue(tag.closed)
    }

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

        controller.arm(testView())
        controller.onTag(FoundTag.Waveshare(tag))

        assertEquals(WriteState.Done, controller.state.value)
        assertTrue(tag.closed)
    }

    @Test
    fun lostTagFailsWithStepAndRetrySucceeds() = runTest {
        val controller = controller()
        controller.arm(testView())

        // 9 setup commands precede the 48 data chunks, so command 20 is mid-image.
        controller.onTag(FoundTag.Waveshare(FakeTag(failAfter = 20)))

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertTrue("sending the image" in failed.message, failed.message)

        controller.arm(testView())
        controller.onTag(FoundTag.Waveshare(FakeTag()))

        assertEquals(WriteState.Done, controller.state.value)
    }

    @Test
    fun lostTagReportsTheFailingStepAndExchangesWithoutImageData() = runTest {
        val controller = controller()
        controller.arm(testView())

        controller.onTag(FoundTag.Waveshare(FakeTag(failAfter = 20)))

        val report = assertNotNull(assertIs<WriteState.Failed>(controller.state.value).report)
        assertEquals(WriteStep.SEND_DATA, report.step)
        assertEquals("Waveshare classic (NFC-A)", report.protocol)
        assertEquals(PANEL.name, report.panel)
        assertEquals("Tag was lost", report.cause?.message)
        assertEquals(21, report.exchanges.size)
        assertNotNull(report.exchanges.last().error)
        val chunks = report.exchanges.filter { it.step == WriteStep.SEND_DATA }
        assertTrue(chunks.isNotEmpty())
        // Only the CD 19 <length> header is kept; none of the chunk's image bytes.
        val header = byteArrayOf(0xCD.toByte(), 0x19, PANEL.chunkSize.toByte())
        assertTrue(chunks.all { it.imageBytes == PANEL.chunkSize && it.command.contentEquals(header) })
    }

    @Test
    fun unsupportedTagReportsItsInfoWithoutExchanges() = runTest {
        val controller = controller()
        controller.arm(testView())
        val info = TagInfo(technologies = listOf("NfcA"), uid = byteArrayOf(4, 1, 2, 3, 4, 5, 6), sak = 0x08)

        controller.onTag(FoundTag.Unsupported(info.uid, info))

        val report = assertNotNull(assertIs<WriteState.Failed>(controller.state.value).report)
        assertSame(info, report.tag)
        assertEquals("unsupported", report.protocol)
        assertNull(report.step)
        assertTrue(report.exchanges.isEmpty())
    }

    @Test
    fun unsupportedTagFailsWithoutWriting() = runTest {
        val controller = controller()
        controller.arm(testView())

        controller.onTag(FoundTag.Unsupported(byteArrayOf(4, 1, 2, 3, 4, 5, 6)))

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertTrue("isn't supported" in failed.message, failed.message)
    }

    @Test
    fun aSecondTagAfterSuccessIsIgnored() = runTest {
        val controller = controller()
        controller.arm(testView())
        controller.onTag(FoundTag.Waveshare(FakeTag()))
        val second = FakeTag()

        controller.onTag(FoundTag.Waveshare(second))

        assertEquals(0, second.sent)
        assertTrue(second.closed)
        assertEquals(WriteState.Done, controller.state.value)
    }

    @Test
    fun cancelReturnsToIdleAndIgnoresLaterTags() = runTest {
        val controller = controller()
        controller.arm(testView())
        val tag = FakeTag()

        controller.cancel()
        controller.onTag(FoundTag.Waveshare(tag))

        assertEquals(WriteState.Idle, controller.state.value)
        assertEquals(0, tag.sent)
    }

    @Test
    fun cancelledWriteIsReportedAsFailed() = runTest {
        val controller = WriteController(PANEL, ImageWriter(pause = { kotlinx.coroutines.awaitCancellation() }))
        controller.arm(testView())
        val tag = FakeTag()

        val job = launch { controller.onTag(FoundTag.Waveshare(tag)) }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertIs<WriteState.Failed>(controller.state.value)
        assertTrue(tag.closed)
    }
}
