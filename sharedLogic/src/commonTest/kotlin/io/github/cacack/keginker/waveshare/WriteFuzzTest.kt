package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.nfc.Transport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * Seeded random replies: a write succeeds only when every command was
 * acknowledged and a busy poll confirmed the refresh, and never throws.
 */
class WriteFuzzTest {
    @Test
    fun randomRepliesNeverSucceedWithoutAConfirmedRefresh() = runTest {
        for (protocol in Protocol.ALL) {
            val clean = protocol.cleanWrite().filterNot(protocol.isBusyPoll)
            var successes = 0
            repeat(SEQUENCES) { run ->
                val seed = protocol.name.hashCode() + run
                val tag = RandomTag(protocol, Random(seed))
                val progress = mutableListOf<Int>()
                val case = "$protocol, seed $seed"

                val result = try {
                    protocol.write(tag) { progress += it }
                } catch (e: Exception) {
                    fail("$case: threw $e")
                }

                when (result) {
                    WriteResult.Success -> {
                        successes++
                        assertConfirmed(protocol, clean, tag, case)
                    }
                    is WriteResult.Failure ->
                        assertTrue(progress.all { it < 100 }, "$case: failed after progress reached 100")
                }
            }
            // Both outcomes must occur, or the check above proves nothing.
            assertTrue(successes in 1 until SEQUENCES, "$protocol: $successes of $SEQUENCES succeeded")
        }
    }

    private fun assertConfirmed(protocol: Protocol, clean: List<ByteArray>, tag: RandomTag, case: String) {
        val exchanges = tag.sent.zip(tag.replies)
        val commands = exchanges.filterNot { protocol.isBusyPoll(it.first) }
        val polls = exchanges.filter { protocol.isBusyPoll(it.first) }
        assertEquals(clean.map { it.toHex() }, commands.map { it.first.toHex() }, "$case: succeeded without sending every command")
        assertTrue(commands.all { protocol.isAcknowledged(it.second) }, "$case: succeeded with an unacknowledged command")
        assertTrue(polls.lastOrNull()?.let { protocol.confirmsRefresh(it.second) } == true, "$case: succeeded without a confirmed refresh")
    }

    /**
     * Mostly answers commands like a good tag, so some writes get all the way
     * through; otherwise drops out, refuses, truncates, sends garbage or
     * answers in the busy poll's shape. Answers a busy poll with "finished",
     * "busy", a plain acknowledgement or a fault.
     */
    private class RandomTag(private val protocol: Protocol, private val random: Random) : Transport {
        val sent = mutableListOf<ByteArray>()
        val replies = mutableListOf<ByteArray>()
        private val acknowledgement = protocol.goodReply(ByteArray(2))

        override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
            sent += command
            val roll = random.nextInt(100)
            val reply = if (protocol.isBusyPoll(command)) {
                when {
                    roll < 30 -> protocol.goodReply(command)
                    roll < 60 -> protocol.busy
                    roll < 90 -> acknowledgement
                    else -> fault()
                }
            } else {
                when {
                    roll < 97 -> protocol.goodReply(command)
                    roll < 98 -> protocol.busy
                    else -> fault()
                }
            }
            replies += reply
            return reply
        }

        private fun fault() = Fault.entries.random(random).reply(protocol, random)
    }

    private companion object {
        const val SEQUENCES = 2_000
    }
}
