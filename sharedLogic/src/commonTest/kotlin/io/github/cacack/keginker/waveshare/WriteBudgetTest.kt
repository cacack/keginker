package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.ezsign.EZ_SIGN_DISPLAYS
import io.github.cacack.keginker.ezsign.EZ_SIGN_WRITE_DEADLINE_MS
import io.github.cacack.keginker.ezsign.EzSignIdentity
import io.github.cacack.keginker.ezsign.EzSignLabelWriter
import io.github.cacack.keginker.ezsign.TagTimeout
import io.github.cacack.keginker.ezsign.deviceInfoData
import io.github.cacack.keginker.ezsign.fragments
import io.github.cacack.keginker.ezsign.frameBlocks
import io.github.cacack.keginker.ezsign.identifyEzSign
import io.github.cacack.keginker.ezsign.infoFrom
import io.github.cacack.keginker.ezsign.lzo1xCompress
import io.github.cacack.keginker.ezsign.lzoLiteralStream
import io.github.cacack.keginker.ezsign.packFrame
import io.github.cacack.keginker.ezsign.simulatedEzSign
import io.github.cacack.keginker.nfc.Transport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** Answers every command with [reply] and counts them, advancing [clock], if given, by [commandMs] for each. */
private class CountingTag(
    private val reply: ByteArray,
    private val clock: TestTimeSource? = null,
    private val commandMs: Long = 0,
) : Transport {
    var commands = 0

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        commands++
        clock?.plusAssign(commandMs.milliseconds)
        return reply
    }
}

/** Totals the pauses a writer schedules. */
private class PauseTotal {
    var ms = 0L
    val pause: suspend (Long) -> Unit = { ms += it }
}

/** A write's worst case: what it paused for, plus the allowance for each command it sent. */
private fun scheduledMs(tag: CountingTag, pauses: PauseTotal) = pauses.ms + tag.commands * COMMAND_ALLOWANCE_MS

/**
 * Fails if [scheduled] exceeds the budget, or leaves room for one more busy
 * poll, so the poll limit uses the budget rather than an arbitrary share of it.
 */
private fun assertFitsTheBudget(scheduled: Long, pollPauseMs: Long) {
    assertTrue(WRITE_BUDGET_MS < IOS_TAG_SESSION_MS)
    assertTrue(scheduled <= WRITE_BUDGET_MS, "$scheduled ms exceeds the $WRITE_BUDGET_MS ms budget")
    assertTrue(
        scheduled > WRITE_BUDGET_MS - (pollPauseMs + COMMAND_ALLOWANCE_MS),
        "$scheduled ms leaves room for another poll in the $WRITE_BUDGET_MS ms budget",
    )
}

class WriteBudgetTest {
    @Test
    fun classicRefreshThatNeverConfirmsFailsWithinTheBudget() = runTest {
        for (panel in WavesharePanel.entries) {
            // `00 00` accepts every command but never reports the refresh done.
            val tag = CountingTag(byteArrayOf(0x00, 0x00))
            val pauses = PauseTotal()

            val result = WaveshareWriter(tag, pauses.pause).write(panel, ByteArray(panel.frameSize))

            assertEquals(WriteStep.WAIT_FOR_REFRESH, assertIs<WriteResult.Failure>(result).step)
            // The budget counts the power-off command that follows a confirmed refresh.
            assertFitsTheBudget(scheduledMs(tag, pauses) + COMMAND_ALLOWANCE_MS, pollPauseMs = 25)
        }
    }

    @Test
    fun rev22RefreshThatNeverConfirmsFailsWithinTheBudget() = runTest {
        for (panel in WavesharePanel.entries) {
            // `01 90 00` accepts every command, and reads as busy to the busy poll.
            val tag = CountingTag(byteArrayOf(0x01, 0x90.toByte(), 0x00))
            val pauses = PauseTotal()

            val result = WaveshareIsoDepWriter(tag, pauses.pause).write(panel, ByteArray(panel.controllerFrameSize))

            assertEquals(WriteStep.WAIT_FOR_REFRESH, assertIs<WriteResult.Failure>(result).step)
            assertFitsTheBudget(scheduledMs(tag, pauses), pollPauseMs = 200)
        }
    }

    @Test
    fun ezSignWorstCaseBeforeTheRefreshFitsTheBudget() = runTest {
        for (display in EZ_SIGN_DISPLAYS) {
            val info = infoFrom(deviceInfoData(display))
            // The commands a real label write sends before its image: identifying the display, then starting the write.
            val tag = simulatedEzSign(deviceInfoData(display))
            val log = ExchangeLog()
            assertIs<EzSignIdentity.Identified>(identifyEzSign(tag, log))
            EzSignLabelWriter(tag, info).write(display, BooleanArray(display.viewWidth * display.viewHeight), log)
            val setup = tag.sent.indexOfFirst { it[1] == 0xD4.toByte() } - tag.sent.count { it[1] == 0xD3.toByte() }
            // Random bytes hold almost nothing to match, so they compress no better than a literal run.
            val frame = Random(0).nextBytes(packFrame(ByteArray(info.nativeWidth * info.nativeHeight), info).size)
            val fragments = frameBlocks(frame).sumOf { maxOf(fragments(lzo1xCompress(it)).size, fragments(lzoLiteralStream(it)).size) }

            val scheduled = (setup + fragments) * COMMAND_ALLOWANCE_MS

            assertTrue(scheduled <= WRITE_BUDGET_MS, "${display.name}: $scheduled ms exceeds the $WRITE_BUDGET_MS ms budget")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun ezSignRefreshThatNeverAnswersFailsUnconfirmedWithinTheDeadline() = runTest {
        for (display in EZ_SIGN_DISPLAYS) {
            val start = testScheduler.currentTime
            val tag = simulatedEzSign(deviceInfoData(display))
            // Every command takes its allowance; the refresh never answers, so its timeout runs out.
            val slow = object : Transport {
                override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
                    delay(COMMAND_ALLOWANCE_MS)
                    if (command[1] == 0xD4.toByte()) {
                        delay(timeoutMs)
                        throw TagTimeout("no reply to the refresh within $timeoutMs ms")
                    }
                    return tag.transceive(command, timeoutMs)
                }
            }
            val log = ExchangeLog()
            val info = assertIs<EzSignIdentity.Identified>(identifyEzSign(slow, log)).info

            val result = EzSignLabelWriter(slow, info).write(display, BooleanArray(display.viewWidth * display.viewHeight), log)

            val failure = assertIs<WriteResult.Failure>(result, display.name)
            assertEquals(FailureKind.REFRESH_UNCONFIRMED, failure.kind, display.name)
            assertEquals(BLOCKING_REFRESH_UNCONFIRMED_MESSAGE, failure.userMessage, display.name)
            val tookMs = testScheduler.currentTime - start
            assertTrue(tookMs <= EZ_SIGN_WRITE_DEADLINE_MS, "${display.name}: took $tookMs ms")
        }
    }

    @Test
    fun aBudgetWithRoomForOnePollAllowsOne() {
        val onePoll = COMMAND_ALLOWANCE_MS + 200
        assertEquals(1, busyPollLimit(pausesMs = WRITE_BUDGET_MS - onePoll, commands = 0, pollPauseMs = 200))
        assertFailsWith<IllegalStateException> {
            busyPollLimit(pausesMs = WRITE_BUDGET_MS - onePoll + 1, commands = 0, pollPauseMs = 200)
        }
    }
}
