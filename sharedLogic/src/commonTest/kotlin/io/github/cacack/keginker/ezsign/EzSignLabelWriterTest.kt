package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.LabelDisplay
import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.image.forEachSeed
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.EZ_SIGN_29_DEVICE_INFO
import io.github.cacack.keginker.probe.EZ_SIGN_42_DEVICE_INFO
import io.github.cacack.keginker.waveshare.BLOCKING_REFRESH_UNCONFIRMED_MESSAGE
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.REPOSITION_ADVICE
import io.github.cacack.keginker.waveshare.WRITE_DEADLINE_MS
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

private val OK = byteArrayOf(0x90.toByte(), 0x00)

/** The EZ Sign displays KegInker writes labels to. */
internal val EZ_SIGN_DISPLAYS = LabelDisplay.entries.filter { it.isEzSign }

/** The device-information reply data (no status) of the [display] in its hardware report. */
internal fun deviceInfoData(display: LabelDisplay): ByteArray = when (display) {
    LabelDisplay.EZ_SIGN_2IN9 -> EZ_SIGN_29_DEVICE_INFO
    LabelDisplay.EZ_SIGN_4IN2 -> EZ_SIGN_42_DEVICE_INFO
    else -> error("${display.name} isn't an EZ Sign display")
}

/** A simulated EZ Sign tag whose device information gives [data]; [clock] and [busyMs] as in [SimulatedEzSignTag]. */
internal fun simulatedEzSign(data: ByteArray, clock: TestTimeSource? = null, busyMs: Long? = null) =
    SimulatedEzSignTag(infoFrom(data), clock = clock, busyMs = busyMs, deviceInfoReply = data + OK)

/** A random view of [display]. */
internal fun randomView(display: LabelDisplay, random: kotlin.random.Random) =
    BooleanArray(display.viewWidth * display.viewHeight) { random.nextBoolean() }

private fun isRefresh(command: ByteArray) = command[1] == 0xD4.toByte()

private suspend fun write(display: LabelDisplay, tag: Transport, view: BooleanArray, log: ExchangeLog = ExchangeLog()) =
    EzSignLabelWriter(tag, infoFrom(deviceInfoData(display))).write(display, view, log)

class EzSignLabelWriterTest {
    @Test
    fun aLabelLandsInEachDisplaysMemoryAsItsNativePixels() = runTest {
        forEachSeed { random ->
            for (display in EZ_SIGN_DISPLAYS) {
                val tag = simulatedEzSign(deviceInfoData(display))
                val view = randomView(display, random)

                val result = write(display, tag, view)

                assertEquals(WriteResult.Success, result, "${display.name}: tag said ${tag.lastError}")
                assertContentEquals(ezSignLabelPixels(display, view), tag.shownPixels(), display.name)
            }
        }
    }

    @Test
    fun blocksAreCompressed() = runTest {
        for (display in EZ_SIGN_DISPLAYS) {
            val info = infoFrom(deviceInfoData(display))
            val tag = simulatedEzSign(deviceInfoData(display))
            // All white but one black pixel per row: mostly repeats, as labels are.
            val view = BooleanArray(display.viewWidth * display.viewHeight) { it % display.viewWidth != 0 }

            assertEquals(WriteResult.Success, write(display, tag, view))

            val blocks = frameBlocks(packFrame(ezSignLabelPixels(display, view), info))
            val compressed = blocks.sumOf { fragments(lzo1xCompress(it)).size }
            val literal = blocks.sumOf { fragments(lzoLiteralStream(it)).size }
            assertEquals(compressed, tag.sent.count { it[1] == 0xD3.toByte() }, display.name)
            assertTrue(compressed < literal, "${display.name}: $compressed fragments, $literal uncompressed")
            assertContentEquals(ezSignLabelPixels(display, view), tag.shownPixels(), display.name)
        }
    }

    @Test
    fun sendsTheVerifiedSequenceEndingInTheBlockingRefresh() = runTest {
        val display = LabelDisplay.EZ_SIGN_4IN2
        val tag = simulatedEzSign(deviceInfoData(display))

        write(display, tag, BooleanArray(display.viewWidth * display.viewHeight) { true })

        val sent = tag.sent.map { it.toList() }
        assertEquals(selectAidCommand(), sent[0])
        assertEquals(0x20.toByte(), sent[1][1])
        assertTrue(sent.subList(2, sent.size - 1).all { it[1] == 0xD3.toByte() })
        assertEquals(listOf(0xF0, 0xD4, 0x85, 0x00, 0x00).map { it.toByte() }, sent.last())
    }

    @Test
    fun aRefreshThatOutlastsItsTimeoutIsUnconfirmedWithRepositionAdvice() = runTest {
        val display = LabelDisplay.EZ_SIGN_2IN9
        val tag = simulatedEzSign(deviceInfoData(display), clock = TestTimeSource(), busyMs = BLOCKING_REFRESH_TIMEOUT_MS + 1)

        val failure = assertIs<WriteResult.Failure>(write(display, tag, BooleanArray(display.viewWidth * display.viewHeight)))

        assertEquals(WriteStep.REFRESH, failure.step)
        assertEquals(FailureKind.REFRESH_UNCONFIRMED, failure.kind)
        assertIs<TagTimeout>(failure.cause)
        assertEquals(BLOCKING_REFRESH_UNCONFIRMED_MESSAGE, failure.userMessage)
        assertTrue(failure.userMessage.startsWith("The display didn't confirm it finished updating."), failure.userMessage)
        assertTrue(REPOSITION_ADVICE in failure.userMessage, failure.userMessage)
    }

    @Test
    fun losingContactDuringTheRefreshIsUnconfirmed() = runTest {
        val display = LabelDisplay.EZ_SIGN_4IN2
        val lost = Interfering(simulatedEzSign(deviceInfoData(display)), ::isRefresh) { throw Exception("Tag was lost") }

        val failure = assertIs<WriteResult.Failure>(write(display, lost, BooleanArray(display.viewWidth * display.viewHeight)))

        assertEquals(FailureKind.REFRESH_UNCONFIRMED, failure.kind)
        assertEquals(BLOCKING_REFRESH_UNCONFIRMED_MESSAGE, failure.userMessage)
    }

    @Test
    fun otherFailuresKeepTheirKind() = runTest {
        val display = LabelDisplay.EZ_SIGN_4IN2
        val view = BooleanArray(display.viewWidth * display.viewHeight)
        val refused = Interfering(simulatedEzSign(deviceInfoData(display)), ::isRefresh) { byteArrayOf(0x6A, 0x80.toByte()) }
        val lostMidImage = Interfering(simulatedEzSign(deviceInfoData(display)), { it[1] == 0xD3.toByte() }) { throw Exception("lost") }

        val refusedFailure = assertIs<WriteResult.Failure>(write(display, refused, view))
        val lostFailure = assertIs<WriteResult.Failure>(write(display, lostMidImage, view))

        assertEquals(WriteStep.REFRESH to FailureKind.UNEXPECTED_REPLY, refusedFailure.step to refusedFailure.kind)
        assertEquals(WriteStep.SEND_DATA to FailureKind.LOST_CONTACT, lostFailure.step to lostFailure.kind)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val display = LabelDisplay.EZ_SIGN_2IN9
        val cancelled = Interfering(simulatedEzSign(deviceInfoData(display)), ::isRefresh) { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { write(display, cancelled, BooleanArray(display.viewWidth * display.viewHeight)) }
    }

    @Test
    fun aViewForAnotherDisplayIsRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            EzSignLabelWriter(simulatedEzSign(EZ_SIGN_29_DEVICE_INFO), infoFrom(EZ_SIGN_29_DEVICE_INFO))
                .write(LabelDisplay.EZ_SIGN_4IN2, BooleanArray(400 * 300), ExchangeLog())
        }
    }

    @Test
    fun identifyingSendsSelectThenTheDeviceInformationQuery() = runTest {
        for (display in EZ_SIGN_DISPLAYS) {
            val tag = simulatedEzSign(deviceInfoData(display))
            val log = ExchangeLog()

            val identity = assertIs<EzSignIdentity.Identified>(identifyEzSign(tag, log))

            assertSame(display, identity.display)
            assertEquals(listOf(selectAidCommand(), listOf<Byte>(0x00, 0xD1.toByte(), 0x00, 0x00, 0x00)), tag.sent.map { it.toList() })
            assertEquals(2, log.entries.size)
        }
    }

    @Test
    fun aSizeNoDisplayHasIsIdentifiedWithoutADisplay() = runTest {
        val data = byteArrayOf(0xA0.toByte(), 0x07, 0xF0.toByte(), 0x07, 0x20, 0x01, 0xF4.toByte(), 0x00, 0x7A)

        val identity = assertIs<EzSignIdentity.Identified>(identifyEzSign(simulatedEzSign(data), ExchangeLog()))

        assertEquals(null, identity.display)
    }

    @Test
    fun aReplyThatDoesNotDecodeIsNotEzSign() = runTest {
        val tag = SimulatedEzSignTag(PANEL_29, deviceInfoReply = byteArrayOf(0x01, 0x02) + OK)

        val identity = assertIs<EzSignIdentity.NotEzSign>(identifyEzSign(tag, ExchangeLog()))

        assertTrue("didn't decode" in identity.reason, identity.reason)
    }

    @Test
    fun aRefusedSelectIsNotEzSignAndAsksNothingMore() = runTest {
        val tag = SimulatedEzSignTag(PANEL_29)
        val refusing = Interfering(tag, { it[1] == 0xA4.toByte() }) { byteArrayOf(0x6A, 0x82.toByte()) }

        assertIs<EzSignIdentity.NotEzSign>(identifyEzSign(refusing, ExchangeLog()))

        assertTrue(tag.sent.isEmpty())
    }

    @Test
    fun losingTheTagWhileIdentifyingIsAFailure() = runTest {
        val lost = Interfering(simulatedEzSign(EZ_SIGN_29_DEVICE_INFO), { it[1] == 0xD1.toByte() }) { throw Exception("lost") }

        val identity = assertIs<EzSignIdentity.Failed>(identifyEzSign(lost, ExchangeLog()))

        assertEquals(FailureKind.LOST_CONTACT, identity.failure.kind)
    }

    @Test
    fun labelAndTestWritesShareOneDeadline() {
        assertEquals(WRITE_DEADLINE_MS + BLOCKING_REFRESH_TIMEOUT_MS, EZ_SIGN_WRITE_DEADLINE_MS)
        assertEquals(EZ_SIGN_WRITE_DEADLINE_MS, EzSignTestWriteFamily(PANEL_29).deadlineMs)
    }
}

private fun selectAidCommand() = listOf(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01, 0x00).map { it.toByte() }
