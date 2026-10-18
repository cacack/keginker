package io.github.cacack.keginker.waveshare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * CONSTITUTION.md C2: every point where a write can fail ends in a failure
 * that names what the tag was doing, never a success.
 */
class WriteFaultInjectionTest {
    /**
     * Fails each command of a full write in turn, with each [Fault], and
     * checks the result. Cases come from a clean write's commands, so new
     * commands are covered without editing this test.
     */
    @Test
    fun everyCommandFailsCleanlyWithEveryFault() = runTest {
        for (protocol in Protocol.ALL) {
            val clean = protocol.cleanWrite()
            for (failAt in clean.indices) {
                for (fault in Fault.entries) {
                    val case = "$protocol, command ${failAt + 1}/${clean.size} (${clean[failAt].toHex()}), $fault"
                    val progress = mutableListOf<Int>()
                    val tag = faultyTag(protocol, failAt, fault)

                    val result = try {
                        protocol.write(tag) { progress += it }
                    } catch (e: Exception) {
                        fail("$case: threw $e")
                    }

                    if (result !is WriteResult.Failure) fail("$case: expected a failure, got $result")
                    assertEquals(protocol.stepOf(clean, failAt), result.step, case)
                    assertEquals(expectedKind(protocol, clean[failAt], fault), result.kind, case)
                    assertTrue(progress.all { it < 100 }, "$case: progress reached ${progress.maxOrNull()}")
                }
            }
        }
    }

    /**
     * A busy poll that never confirms keeps polling and then fails the
     * refresh; any other command fails on the first bad reply.
     */
    private fun expectedKind(protocol: Protocol, command: ByteArray, fault: Fault) = when {
        fault == Fault.LIFTED -> FailureKind.LOST_CONTACT
        protocol.isBusyPoll(command) -> FailureKind.REFRESH_UNCONFIRMED
        else -> FailureKind.UNEXPECTED_REPLY
    }
}
