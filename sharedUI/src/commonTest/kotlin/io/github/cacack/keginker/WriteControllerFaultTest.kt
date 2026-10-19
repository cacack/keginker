package io.github.cacack.keginker

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.REFRESH_UNCONFIRMED_MESSAGE
import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.activity
import io.github.cacack.keginker.waveshare.userMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PANEL = WavesharePanel.EPD_2IN7

// A deliberately smaller mirror of sharedLogic's test model of both
// protocols (waveshare/WriteFaults.kt), whose fixtures are internal to that
// module. Keep the reply formats here in step with it.

/** A kind of tag: how a good one answers, and its malformed replies. */
private class TagKind(
    val name: String,
    val found: (Transport) -> FoundTag.Writable,
    val goodReply: (ByteArray) -> ByteArray,
    val refusal: ByteArray,
    val truncated: ByteArray,
    /** Whether [reply] has the success status of some command. */
    val isSuccessShaped: (ByteArray) -> Boolean,
) {
    override fun toString() = name
}

private val CLASSIC = TagKind(
    name = "classic",
    found = { FoundTag.Waveshare(it) },
    goodReply = { if (it[1] == 0x0A.toByte()) bytes(0xFF, 0x00) else bytes(0x00, 0x00) },
    refusal = bytes(0x01, 0x00),
    truncated = bytes(0x00),
    isSuccessShaped = { it.size >= 2 && (it[0] == 0x00.toByte() || it[0] == 0xFF.toByte()) && it[1] == 0x00.toByte() },
)

private val REV22 = TagKind(
    name = "Rev2.2",
    found = { FoundTag.WaveshareIsoDep(it) },
    goodReply = { if (it[1] == 0x9B.toByte()) bytes(0x00, 0x90, 0x00) else bytes(0x90, 0x00) },
    refusal = bytes(0x69, 0x82),
    truncated = bytes(0x90),
    isSuccessShaped = { it.size >= 2 && it[it.size - 2] == 0x90.toByte() && it[it.size - 1] == 0x00.toByte() },
)

/** How a tag misbehaves from one command onwards. */
private enum class Fault { LIFTED, REFUSED, EMPTY, TRUNCATED, GARBAGE }

/** Answers like a good [kind] of tag until command [failAt], then with [fault]. */
private class FaultyTag(private val kind: TagKind, private val failAt: Int, private val fault: Fault) : Transport {
    private val random = Random(failAt)
    var commandCount = 0
    var closed = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (commandCount++ < failAt) return kind.goodReply(command)
        return when (fault) {
            Fault.LIFTED -> throw IllegalStateException("Tag was lost")
            Fault.REFUSED -> kind.refusal
            Fault.EMPTY -> ByteArray(0)
            Fault.TRUNCATED -> kind.truncated
            Fault.GARBAGE -> generateSequence { random.nextBytes(random.nextInt(0, 9)) }.first { !kind.isSuccessShaped(it) }
        }
    }

    override fun close() {
        closed = true
    }
}

/** A tag that answers every command like a good [kind] of tag. */
private fun goodTag(kind: TagKind) = FaultyTag(kind, failAt = Int.MAX_VALUE, Fault.LIFTED)

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

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
        for (kind in listOf(CLASSIC, REV22)) {
            val clean = goodTag(kind)
            assertEquals(WriteResult.Success, imageWriter.write(kind.found(clean), PANEL, testView()) {})
            val commands = clean.commandCount
            for (failAt in 0 until commands) {
                for (fault in Fault.entries) {
                    val case = "$kind, command ${failAt + 1}/$commands, $fault"
                    val expected = imageWriter.write(kind.found(FaultyTag(kind, failAt, fault)), PANEL, testView()) {}
                    assertIs<WriteResult.Failure>(expected, case)
                    val controller = WriteController(PANEL, imageWriter)
                    val tag = FaultyTag(kind, failAt, fault)

                    controller.arm(testView())
                    controller.onTag(kind.found(tag))

                    val failed = assertIs<WriteState.Failed>(controller.state.value, case)
                    assertEquals(expected.userMessage, failed.message, case)
                    if (expected.kind == FailureKind.REFRESH_UNCONFIRMED) {
                        assertEquals(REFRESH_UNCONFIRMED_MESSAGE, failed.message, case)
                    } else {
                        assertTrue("while ${expected.step.activity}" in failed.message, "$case: ${failed.message}")
                    }
                    assertTrue("try again" in failed.message, "$case: ${failed.message}")
                    assertTrue(tag.closed, "$case: tag not released")

                    val retry = goodTag(kind)
                    controller.arm(testView())
                    controller.onTag(kind.found(retry))

                    assertIs<WriteState.Done>(controller.state.value, case)
                    assertTrue(retry.closed, "$case: retried tag not released")
                }
            }
        }
    }
}
