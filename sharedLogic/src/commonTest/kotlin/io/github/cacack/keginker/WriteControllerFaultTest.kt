package io.github.cacack.keginker

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.Fault
import io.github.cacack.keginker.waveshare.Protocol
import io.github.cacack.keginker.waveshare.REFRESH_UNCONFIRMED_MESSAGE
import io.github.cacack.keginker.waveshare.ScriptedTag
import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.activity
import io.github.cacack.keginker.waveshare.faultyTag
import io.github.cacack.keginker.waveshare.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PANEL = WavesharePanel.EPD_2IN7

/** Wraps [transport] as the kind of tag that speaks [protocol]. */
private fun found(protocol: Protocol, transport: Transport): FoundTag.Writable =
    if (protocol == Protocol.CLASSIC) FoundTag.Waveshare(transport) else FoundTag.WaveshareIsoDep(transport)

private fun testView() = BooleanArray(PANEL.viewWidth * PANEL.viewHeight) { true }

private val imageWriter = ImageWriter(pause = {})

/**
 * CONSTITUTION.md C2 through the controller: whichever command fails, and
 * however, the user is told what the tag was doing and how to recover, the
 * tag is released, and a retry works at once.
 */
class WriteControllerFaultTest {
    @Test
    fun everyFailedCommandEndsInAClearFailureAndAWorkingRetry() = runTest {
        for (protocol in Protocol.ALL) {
            val commands = protocol.cleanWrite().size
            for (failAt in 0 until commands) {
                for (fault in Fault.entries) {
                    val case = "$protocol, command ${failAt + 1}/$commands, $fault"
                    val expected = imageWriter.write(found(protocol, faultyTag(protocol, failAt, fault)), PANEL, testView()) {}
                    assertIs<WriteResult.Failure>(expected, case)
                    val controller = WriteController(PANEL, imageWriter)
                    val tag = faultyTag(protocol, failAt, fault)

                    controller.arm(testView())
                    controller.onTag(found(protocol, tag))

                    val failed = assertIs<WriteState.Failed>(controller.state.value, case)
                    assertEquals(expected.userMessage, failed.message, case)
                    if (expected.kind == FailureKind.REFRESH_UNCONFIRMED) {
                        assertEquals(REFRESH_UNCONFIRMED_MESSAGE, failed.message, case)
                    } else {
                        assertTrue("while ${expected.step.activity}" in failed.message, "$case: ${failed.message}")
                    }
                    assertTrue("try again" in failed.message, "$case: ${failed.message}")
                    assertTrue(tag.closed, "$case: tag not released")

                    val retry = ScriptedTag { command, _ -> protocol.goodReply(command) }
                    controller.arm(testView())
                    controller.onTag(found(protocol, retry))

                    assertIs<WriteState.Done>(controller.state.value, case)
                    assertTrue(retry.closed, "$case: retried tag not released")
                }
            }
        }
    }
}
