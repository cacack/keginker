package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.nfc.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
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
    fun aBudgetWithRoomForOnePollAllowsOne() {
        val onePoll = COMMAND_ALLOWANCE_MS + 200
        assertEquals(1, busyPollLimit(pausesMs = WRITE_BUDGET_MS - onePoll, commands = 0, pollPauseMs = 200))
        assertFailsWith<IllegalStateException> {
            busyPollLimit(pausesMs = WRITE_BUDGET_MS - onePoll + 1, commands = 0, pollPauseMs = 200)
        }
    }
}
