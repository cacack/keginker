package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.Outcome
import io.github.cacack.keginker.ezsign.BLOCKING_REFRESH_TIMEOUT_MS
import io.github.cacack.keginker.ezsign.EZ_SIGN_DISPLAYS
import io.github.cacack.keginker.ezsign.EZ_SIGN_WRITE_DEADLINE_MS
import io.github.cacack.keginker.ezsign.SimulatedEzSignTag
import io.github.cacack.keginker.ezsign.TagTimeout
import io.github.cacack.keginker.ezsign.deviceInfoData
import io.github.cacack.keginker.ezsign.ezSignLabelPixels
import io.github.cacack.keginker.ezsign.infoFrom
import io.github.cacack.keginker.ezsign.simulatedEzSign
import io.github.cacack.keginker.image.WHITE
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.EZ_SIGN_29_DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_42_DEVICE_INFO
import io.github.cacack.keginker.waveshare.BLOCKING_REFRESH_UNCONFIRMED_MESSAGE
import io.github.cacack.keginker.waveshare.DONE_MESSAGE
import io.github.cacack.keginker.waveshare.EZ_SIGN_SIZE_UNSUPPORTED_MESSAGE
import io.github.cacack.keginker.waveshare.IMAGE_SENT_PROGRESS
import io.github.cacack.keginker.waveshare.UNSUPPORTED_TAG_MESSAGE
import io.github.cacack.keginker.waveshare.WRITE_DEADLINE_MS
import io.github.cacack.keginker.waveshare.WRITE_TIMED_OUT_MESSAGE
import io.github.cacack.keginker.waveshare.WriteStep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private val DISPLAY = LabelDisplay.WAVESHARE_2IN7
private val PANEL = DISPLAY.wavesharePanel!!

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

/** A tag that answers like [FakeTag], each command just inside the per-command timeout. */
private class SlowTag : Transport {
    private val tag = FakeTag()
    var closed = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        delay(timeoutMs - 100)
        return tag.transceive(command, timeoutMs)
    }

    override fun close() {
        closed = true
    }
}

private fun testView() = ByteArray(PANEL.viewWidth * PANEL.viewHeight) { WHITE }

private fun controller() = WriteController(DISPLAY, ImageWriter(pause = {}))

class WriteControllerTest {
    @Test
    fun rev22TagIsWrittenWithItsOwnProtocol() = runTest {
        val tag = FakeIsoDepTag()
        val controller = controller()
        controller.arm(testView())

        controller.onTag(FoundTag.WaveshareIsoDep(tag))

        assertIs<WriteState.Done>(controller.state.value)
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

        assertIs<WriteState.Done>(controller.state.value)
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

        assertIs<WriteState.Done>(controller.state.value)
    }

    @Test
    fun successfulWriteReportsItsExchangesWithoutImageData() = runTest {
        val controller = controller()
        controller.arm(testView(), testPattern = true)

        controller.onTag(FoundTag.Waveshare(FakeTag()))

        val report = assertIs<WriteState.Done>(controller.state.value).report
        assertEquals(Outcome.SUCCEEDED, report.outcome)
        assertTrue(report.testPattern)
        assertEquals(DONE_MESSAGE, report.message)
        assertEquals("Waveshare classic (NFC-A)", report.protocol)
        assertNull(report.step)
        assertNull(report.cause)
        val chunks = report.exchanges.filter { it.step == WriteStep.SEND_DATA }
        assertEquals(PANEL.chunkCount, chunks.size)
        val header = byteArrayOf(0xCD.toByte(), 0x19, PANEL.chunkSize.toByte())
        assertTrue(chunks.all { it.imageBytes == PANEL.chunkSize && it.command.contentEquals(header) })
    }

    @Test
    fun reportsSayWhenTheImageIsNotTheTestPattern() = runTest {
        val controller = controller()
        controller.arm(testView(), testPattern = false)

        controller.onTag(FoundTag.Waveshare(FakeTag(failAfter = 3)))

        val report = assertNotNull(assertIs<WriteState.Failed>(controller.state.value).report)
        assertEquals(false, report.testPattern)
        assertEquals(Outcome.FAILED, report.outcome)
    }

    @Test
    fun lostTagReportsTheFailingStepAndExchangesWithoutImageData() = runTest {
        val controller = controller()
        controller.arm(testView())

        controller.onTag(FoundTag.Waveshare(FakeTag(failAfter = 20)))

        val report = assertNotNull(assertIs<WriteState.Failed>(controller.state.value).report)
        assertEquals(WriteStep.SEND_DATA, report.step)
        assertEquals("Waveshare classic (NFC-A)", report.protocol)
        assertEquals(DISPLAY.name, report.panel)
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

        controller.onTag(FoundTag.Unsupported(info))

        val report = assertNotNull(assertIs<WriteState.Failed>(controller.state.value).report)
        assertSame(info, report.tag)
        assertEquals("unsupported", report.protocol)
        assertEquals(Outcome.NOT_ATTEMPTED, report.outcome)
        assertNull(report.step)
        assertTrue(report.exchanges.isEmpty())
    }

    @Test
    fun unsupportedTagFailsWithoutWriting() = runTest {
        val controller = controller()
        controller.arm(testView())

        controller.onTag(FoundTag.Unsupported(TagInfo(listOf("NfcA"), byteArrayOf(4, 1, 2, 3, 4, 5, 6))))

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
        assertIs<WriteState.Done>(controller.state.value)
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

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun aWriteThatOverrunsTheDeadlineFailsWithAReportAndRetrySucceeds() = runTest {
        val controller = controller()
        controller.arm(testView())
        val tag = SlowTag()

        controller.onTag(FoundTag.Waveshare(tag))

        assertTrue(testScheduler.currentTime <= WRITE_DEADLINE_MS, "Stopped at ${testScheduler.currentTime} ms")
        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals(WRITE_TIMED_OUT_MESSAGE, failed.message)
        val report = assertNotNull(failed.report)
        assertEquals(Outcome.FAILED, report.outcome)
        assertEquals(report.exchanges.last().step, report.step)
        assertNotNull(report.exchanges.last().error)
        assertTrue(tag.closed)

        controller.arm(testView())
        controller.onTag(FoundTag.Waveshare(FakeTag()))

        assertIs<WriteState.Done>(controller.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.runCurrent
    @Test
    fun failingARunningWriteKeepsItsExchangesAndDropsItsLateResult() = runTest {
        val tagHeld = CompletableDeferred<Unit>()
        val tag = object : Transport {
            val inner = FakeTag()
            var closed = false

            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
                if (inner.sent == 20) tagHeld.await()
                return inner.transceive(command, timeoutMs)
            }

            override fun close() {
                closed = true
            }
        }
        val controller = controller()
        controller.arm(testView())

        val write = launch { controller.onTag(FoundTag.Waveshare(tag)) }
        testScheduler.runCurrent()
        controller.fail("The NFC session ended before the write finished.", "Session timeout", tag = null)
        tagHeld.complete(Unit)
        write.join()

        // The write went on to succeed, but the failure stands.
        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals("The NFC session ended before the write finished.", failed.message)
        val report = assertNotNull(failed.report)
        assertEquals(Outcome.FAILED, report.outcome)
        assertEquals("Session timeout", report.reason)
        assertEquals(20, report.exchanges.size)
        assertTrue(tag.closed)

        controller.arm(testView())
        controller.onTag(FoundTag.Waveshare(FakeTag()))

        assertIs<WriteState.Done>(controller.state.value)
    }

    @Test
    fun failingAWaitingWriteReportsOnTheTagWithoutExchanges() = runTest {
        val controller = controller()
        controller.arm(testView())
        val tag = FoundTag.Waveshare(FakeTag())

        controller.fail("Couldn't connect to the tag. Hold it still and try again.", "Connection lost", tag)

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        val report = assertNotNull(failed.report)
        assertEquals(Outcome.FAILED, report.outcome)
        assertEquals("Connection lost", report.reason)
        assertEquals("Waveshare classic (NFC-A)", report.protocol)
        assertTrue(report.exchanges.isEmpty())

        controller.arm(testView())
        controller.onTag(FoundTag.Waveshare(FakeTag()))

        assertIs<WriteState.Done>(controller.state.value)
    }

    @Test
    fun failingAWaitingWriteWithoutATagHasNoReport() {
        val controller = controller()
        controller.arm(testView())

        controller.fail("Session timeout", reason = null, tag = null)

        assertEquals(WriteState.Failed("Session timeout", report = null), controller.state.value)
    }

    @Test
    fun failIsIgnoredOnceTheWriteHasEnded() = runTest {
        val controller = controller()
        controller.fail("Session timeout", reason = null, tag = null)
        assertEquals(WriteState.Idle, controller.state.value)

        controller.arm(testView())
        controller.onTag(FoundTag.Waveshare(FakeTag()))
        controller.fail("Session ended", reason = null, tag = null)

        assertIs<WriteState.Done>(controller.state.value)
    }

    @Test
    fun cancelledWriteIsReportedAsFailed() = runTest {
        val controller = WriteController(DISPLAY, ImageWriter(pause = { kotlinx.coroutines.awaitCancellation() }))
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

private val ISO_DEP_INFO = TagInfo(listOf("IsoDep"), byteArrayOf(4, 1, 2, 3, 4, 5, 6))

/** [transport] as the phone finds an EZ Sign display: an unsupported ISO-DEP tag it can open. */
private fun ezSignFound(transport: Transport) = FoundTag.Unsupported(ISO_DEP_INFO, TagKind.ISO_DEP) { transport }

/** A view cycling through every color of [display]'s palette. */
private fun viewOf(display: LabelDisplay) =
    ByteArray(display.viewWidth * display.viewHeight) { (it % display.palette.colors.size).toByte() }

private fun isRefresh(command: ByteArray) = command[0] == 0xF0.toByte() && command[1] == 0xD4.toByte()

/**
 * Answers like [tag], in virtual time: the blocking refresh's reply takes
 * [refreshMs], or if that's longer than its timeout, the timeout runs out.
 */
private class EzSignTagInTime(private val tag: SimulatedEzSignTag, private val refreshMs: Long = 0) : Transport {
    var closed = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (isRefresh(command)) {
            if (refreshMs > timeoutMs) {
                delay(timeoutMs)
                throw TagTimeout("no reply to the refresh within $timeoutMs ms")
            }
            delay(refreshMs)
        }
        return tag.transceive(command, timeoutMs)
    }

    override fun close() {
        closed = true
    }
}

class EzSignWriteControllerTest {
    @Test
    fun aLabelIsWrittenToTheChosenEzSignDisplay() = runTest {
        for (display in EZ_SIGN_DISPLAYS) {
            val simulated = simulatedEzSign(deviceInfoData(display))
            val tag = EzSignTagInTime(simulated)
            val controller = WriteController(display)
            val view = viewOf(display)
            controller.arm(view, testPattern = true)

            controller.onTag(ezSignFound(tag))

            val report = assertIs<WriteState.Done>(controller.state.value, "${display.name}: ${simulated.lastError}").report
            assertEquals("EZ Sign (ISO-DEP)", report.protocol)
            assertEquals(display.name, report.panel)
            assertTrue(report.testPattern)
            assertContentEquals(ezSignLabelPixels(display, view), simulated.shownPixels(), display.name)
            assertTrue(tag.closed)
        }
    }

    @Test
    fun anEzSignDisplayOfAnotherSizeNamesTheOneToChoose() = runTest {
        val simulated = simulatedEzSign(EZ_SIGN_42_DEVICE_INFO)
        val tag = EzSignTagInTime(simulated)
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        controller.onTag(ezSignFound(tag))

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals("This is the EZ Sign 4.2\" display. Choose EZ Sign 4.2\" and try again.", failed.message)
        val report = assertNotNull(failed.report)
        assertEquals(Outcome.FAILED, report.outcome)
        assertEquals("EZ Sign (ISO-DEP)", report.protocol)
        assertEquals(WriteStep.INIT, report.step)
        // Only SELECT and the device-information query; nothing that writes.
        assertEquals(2, report.exchanges.size)
        assertEquals(2, simulated.sent.size)
        assertTrue(tag.closed)
    }

    @Test
    fun anEzSignDisplayOfASizeNoDisplayHasSaysSo() = runTest {
        // 122 × 250: decodes, but no LabelDisplay is that size.
        val data = byteArrayOf(0xA0.toByte(), 0x07, 0xF0.toByte(), 0x07, 0x20, 0x01, 0xF4.toByte(), 0x00, 0x7A)
        val controller = WriteController(LabelDisplay.EZ_SIGN_4IN2)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_4IN2))

        controller.onTag(ezSignFound(EzSignTagInTime(simulatedEzSign(data))))

        assertEquals(EZ_SIGN_SIZE_UNSUPPORTED_MESSAGE, assertIs<WriteState.Failed>(controller.state.value).message)
    }

    @Test
    fun anIsoDepTagWhoseDeviceInformationDoesNotDecodeIsUnsupported() = runTest {
        val simulated = SimulatedEzSignTag(infoFrom(EZ_SIGN_29_DEVICE_INFO), deviceInfoReply = byteArrayOf(0x6D, 0x00))
        val tag = EzSignTagInTime(simulated)
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        controller.onTag(ezSignFound(tag))

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals(UNSUPPORTED_TAG_MESSAGE, failed.message)
        val report = assertNotNull(failed.report)
        assertEquals("unsupported", report.protocol)
        // The report keeps what the tag said.
        assertEquals(2, report.exchanges.size)
        assertTrue(tag.closed)
    }

    @Test
    fun aWaveshareTagWhileAnEzSignDisplayIsChosenIsSentNothing() = runTest {
        val tag = FakeTag()
        val controller = WriteController(LabelDisplay.EZ_SIGN_4IN2)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_4IN2))

        controller.onTag(FoundTag.Waveshare(tag))

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals("This is a Waveshare display. Choose Waveshare 2.7\" and try again.", failed.message)
        assertEquals(Outcome.NOT_ATTEMPTED, failed.report?.outcome)
        assertEquals(0, tag.sent)
        assertTrue(tag.closed)
    }

    @Test
    fun anIsoDepTagWhileWaveshareIsChosenIsNeverOpened() = runTest {
        var opened = false
        val controller = WriteController(DISPLAY)
        controller.arm(viewOf(DISPLAY))

        controller.onTag(FoundTag.Unsupported(ISO_DEP_INFO, TagKind.ISO_DEP) { opened = true; FakeTag() })

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        // It may be an EZ Sign display, so the message points to the display picker.
        assertEquals("This isn't a Waveshare 2.7\" display. If it's an EZ Sign display, choose it and try again.", failed.message)
        assertEquals(Outcome.NOT_ATTEMPTED, failed.report?.outcome)
        assertFalse(opened)
    }

    @Test
    fun anNfcATagWhileAnEzSignDisplayIsChosenIsUnsupported() = runTest {
        var opened = false
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        controller.onTag(FoundTag.Unsupported(ISO_DEP_INFO, TagKind.NFC_A) { opened = true; FakeTag() })

        assertEquals(UNSUPPORTED_TAG_MESSAGE, assertIs<WriteState.Failed>(controller.state.value).message)
        assertFalse(opened)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun theDeadlinePassingDuringTheRefreshSaysItWasNotConfirmed() = runTest {
        // Slow to answer SELECT, so the deadline passes while the refresh, in time on its own, is pending.
        val inTime = EzSignTagInTime(simulatedEzSign(EZ_SIGN_29_DEVICE_INFO), refreshMs = BLOCKING_REFRESH_TIMEOUT_MS - 1)
        val slowStart = object : Transport by inTime {
            var first = true

            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
                if (first) delay(WRITE_DEADLINE_MS + 1_000)
                first = false
                return inTime.transceive(command, timeoutMs)
            }
        }
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        controller.onTag(ezSignFound(slowStart))

        assertEquals(EZ_SIGN_WRITE_DEADLINE_MS, testScheduler.currentTime)
        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals(BLOCKING_REFRESH_UNCONFIRMED_MESSAGE, failed.message)
        assertEquals(WriteStep.REFRESH, failed.report?.step)
        assertEquals("No result within $EZ_SIGN_WRITE_DEADLINE_MS ms", failed.report?.reason)
        assertTrue(inTime.closed)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun theDeadlinePassingBeforeTheRefreshTimesOut() = runTest {
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))
        val stuck = object : Transport {
            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray = awaitCancellation()

            override fun close() {}
        }

        controller.onTag(ezSignFound(stuck))

        assertEquals(WRITE_TIMED_OUT_MESSAGE, assertIs<WriteState.Failed>(controller.state.value).message)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun aRefreshThatNeverAnswersIsUnconfirmedWithinTheDeadline() = runTest {
        val tag = EzSignTagInTime(simulatedEzSign(EZ_SIGN_42_DEVICE_INFO), refreshMs = Long.MAX_VALUE)
        val controller = WriteController(LabelDisplay.EZ_SIGN_4IN2)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_4IN2))

        controller.onTag(ezSignFound(tag))

        assertTrue(testScheduler.currentTime <= EZ_SIGN_WRITE_DEADLINE_MS, "Stopped at ${testScheduler.currentTime} ms")
        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals(BLOCKING_REFRESH_UNCONFIRMED_MESSAGE, failed.message)
        assertEquals(WriteStep.REFRESH, failed.report?.step)
        assertTrue(tag.closed)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun aLongRefreshFinishesPastTheWaveshareDeadline() = runTest {
        val tag = EzSignTagInTime(simulatedEzSign(EZ_SIGN_29_DEVICE_INFO), refreshMs = BLOCKING_REFRESH_TIMEOUT_MS - 1)
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        controller.onTag(ezSignFound(tag))

        assertTrue(testScheduler.currentTime > WRITE_DEADLINE_MS, "Took ${testScheduler.currentTime} ms")
        assertIs<WriteState.Done>(controller.state.value)
    }

    @Test
    fun aTagThatCannotBeOpenedFailsWithoutSendingAnything() = runTest {
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        controller.onTag(FoundTag.Unsupported(ISO_DEP_INFO, TagKind.ISO_DEP) { throw IllegalStateException("Tag was lost") })

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertTrue("Couldn't connect" in failed.message, failed.message)
        assertEquals("Tag was lost", failed.report?.cause?.message)
        assertEquals(emptyList(), failed.report?.exchanges)

        // A retry works.
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))
        controller.onTag(ezSignFound(EzSignTagInTime(simulatedEzSign(EZ_SIGN_29_DEVICE_INFO))))
        assertIs<WriteState.Done>(controller.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.runCurrent
    @Test
    fun cancellingAnEzSignWritePropagatesAndClosesTheTag() = runTest {
        val simulated = simulatedEzSign(EZ_SIGN_29_DEVICE_INFO)
        var closed = false
        val holding = object : Transport {
            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
                if (isRefresh(command)) awaitCancellation()
                return simulated.transceive(command, timeoutMs)
            }

            override fun close() {
                closed = true
            }
        }
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        val job = launch { controller.onTag(ezSignFound(holding)) }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        // Cancelled during the refresh, the panel may still be updating.
        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals(BLOCKING_REFRESH_UNCONFIRMED_MESSAGE, failed.message)
        assertEquals(WriteStep.REFRESH, failed.report?.step)
        assertTrue(closed)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.runCurrent
    @Test
    fun failingDuringTheRefreshSaysItWasNotConfirmed() = runTest {
        val tag = EzSignTagInTime(simulatedEzSign(EZ_SIGN_29_DEVICE_INFO), refreshMs = 20_000)
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        val write = launch { controller.onTag(ezSignFound(tag)) }
        testScheduler.runCurrent()
        assertEquals(WriteState.Writing(IMAGE_SENT_PROGRESS), controller.state.value)
        controller.fail("The NFC session ended before the write finished.", "Session timeout", tag = null)
        write.join()

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals(BLOCKING_REFRESH_UNCONFIRMED_MESSAGE, failed.message)
        assertEquals(WriteStep.REFRESH, failed.report?.step)
        assertEquals("Session timeout", failed.report?.reason)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.runCurrent
    @Test
    fun failingWhileSendingTheImageShowsTheGivenMessage() = runTest {
        val simulated = simulatedEzSign(EZ_SIGN_29_DEVICE_INFO)
        val slowImage = object : Transport by simulated {
            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
                // Each image fragment (F0 D3) takes a second.
                if (command[0] == 0xF0.toByte() && command[1] == 0xD3.toByte()) delay(1_000)
                return simulated.transceive(command, timeoutMs)
            }
        }
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))

        val write = launch { controller.onTag(ezSignFound(slowImage)) }
        testScheduler.runCurrent()
        assertIs<WriteState.Writing>(controller.state.value)
        controller.fail("The NFC session ended before the write finished.", "Session timeout", tag = null)
        write.join()

        val failed = assertIs<WriteState.Failed>(controller.state.value)
        assertEquals("The NFC session ended before the write finished.", failed.message)
        assertNull(failed.report?.step)
    }

    @Test
    fun choosingAnotherDisplayDropsTheArmedViewAndWritesTheNewOne() = runTest {
        val controller = WriteController(DISPLAY)
        controller.arm(viewOf(DISPLAY))

        controller.choose(LabelDisplay.EZ_SIGN_4IN2)

        assertEquals(WriteState.Idle, controller.state.value)
        assertEquals(LabelDisplay.EZ_SIGN_4IN2, controller.display)
        val simulated = simulatedEzSign(EZ_SIGN_42_DEVICE_INFO)
        val view = viewOf(LabelDisplay.EZ_SIGN_4IN2)
        controller.arm(view)
        controller.onTag(ezSignFound(EzSignTagInTime(simulated)))
        assertIs<WriteState.Done>(controller.state.value)
        assertContentEquals(ezSignLabelPixels(LabelDisplay.EZ_SIGN_4IN2, view), simulated.shownPixels())
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.runCurrent
    @Test
    fun choosingIsIgnoredDuringAWrite() = runTest {
        val controller = WriteController(LabelDisplay.EZ_SIGN_2IN9)
        controller.arm(viewOf(LabelDisplay.EZ_SIGN_2IN9))
        val write = launch { controller.onTag(ezSignFound(EzSignTagInTime(simulatedEzSign(EZ_SIGN_29_DEVICE_INFO), refreshMs = 1_000))) }
        testScheduler.runCurrent()

        controller.choose(LabelDisplay.EZ_SIGN_4IN2)
        write.join()

        assertEquals(LabelDisplay.EZ_SIGN_2IN9, controller.display)
        assertIs<WriteState.Done>(controller.state.value)
    }
}
