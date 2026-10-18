package io.github.cacack.keginker.replay

import io.github.cacack.keginker.image.testPattern
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.WaveshareIsoDepWriter
import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.packControllerFrame
import io.github.cacack.keginker.waveshare.renderView
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PANEL = WavesharePanel.EPD_2IN7

/** The frame the app writes for the test pattern with its default settings. */
private fun testPatternFrame() =
    packControllerFrame(PANEL, renderView(PANEL, testPattern(PANEL.viewWidth, PANEL.viewHeight)))

private suspend fun assertReplaysToSuccess(recording: Recording) {
    val replay = ReplayTransport(recording)

    val result = WaveshareIsoDepWriter(replay, pause = {}).write(PANEL, testPatternFrame())

    assertEquals(WriteResult.Success, result, "${recording.notes}")
    assertTrue(replay.finished, "Write ended before the recording did: ${recording.notes}")
}

/** The bytes written to black/white RAM: the data APDUs after selecting register `0x24`. */
private fun imageBytes(recording: Recording): ByteArray {
    val commands = recording.exchanges.map { it.command }
    val start = commands.indexOfFirst { it.hex() == "74 99 00 0d 01 24" } + 1
    assertTrue(start > 0, "Recording never selects black/white RAM")
    return commands.drop(start)
        .takeWhile { it.hex().startsWith("74 9a 00 0e") }
        .fold(ByteArray(0)) { image, apdu -> image + apdu.copyOfRange(5, apdu.size) }
}

private fun assertCarriesTheTestPattern(recording: Recording) =
    assertContentEquals(testPatternFrame(), imageBytes(recording), "${recording.notes}")

/**
 * A recording-format transcript of the writer against a tag that always says
 * `90 00` and "not busy". Made in-test, not from hardware: it proves the
 * checks above work, not that the writer is right.
 */
private suspend fun transcriptOfFakeWrite(): String {
    val lines = mutableListOf("# board: none (in-test fake)", "# phone: none", "# os: none", "# app: this build")
    val fake = object : Transport {
        override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
            val reply = if (command.hex().startsWith("74 9b")) byteArrayOf(0x00, 0x90.toByte(), 0x00) else byteArrayOf(0x90.toByte(), 0x00)
            lines += "> ${command.hex()}"
            lines += "< ${reply.hex()}"
            return reply
        }
    }
    WaveshareIsoDepWriter(fake, pause = {}).write(PANEL, testPatternFrame())
    return lines.joinToString("\n")
}

class RecordingReplayTest {
    @Test
    fun everyRev22RecordingReplaysToSuccess() = runTest {
        REV22_RECORDINGS.forEach { assertReplaysToSuccess(parseRecording(it)) }
    }

    @Test
    fun everyRev22RecordingCarriesTheTestPattern() {
        REV22_RECORDINGS.forEach { assertCarriesTheTestPattern(parseRecording(it)) }
    }

    @Test
    fun checksPassForATranscriptOfTheWriter() = runTest {
        val recording = parseRecording(transcriptOfFakeWrite())

        assertReplaysToSuccess(recording)
        assertCarriesTheTestPattern(recording)
    }

    @Test
    fun aDifferentImageFailsTheChecks() = runTest {
        val lines = transcriptOfFakeWrite().lines().toMutableList()
        val chunk = lines.indexOfFirst { it.startsWith("> 74 9a 00 0e fa") }
        lines[chunk] = lines[chunk].dropLast(2) + if (lines[chunk].endsWith("00")) "01" else "00"
        val recording = parseRecording(lines.joinToString("\n"))

        assertFailsWith<AssertionError> { assertCarriesTheTestPattern(recording) }
        assertFailsWith<AssertionError> { assertReplaysToSuccess(recording) }
    }

    @Test
    fun aCommandThatDiffersFailsTheTestRatherThanTheWrite() = runTest {
        val replay = ReplayTransport(parseRecording(RECORDING.replace("> 74 97 00 08 00", "> 74 97 00 09 00")))

        val error = assertFailsWith<AssertionError> { WaveshareIsoDepWriter(replay, pause = {}).write(PANEL, testPatternFrame()) }

        assertTrue("Command 2 differs" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aCommandPastTheEndFailsTheTest() = runTest {
        val replay = ReplayTransport(parseRecording(RECORDING))
        replay.transceive(UNLOCK, 0)
        replay.transceive(byteArrayOf(0x74, 0x97.toByte(), 0x00, 0x08, 0x00), 0)
        assertTrue(replay.finished)

        assertFailsWith<AssertionError> { replay.transceive(byteArrayOf(0x74), 0) }
    }

    @Test
    fun repliesComeBackInOrder() = runTest {
        val replay = ReplayTransport(parseRecording(RECORDING))

        assertEquals("90 00", replay.transceive(UNLOCK, 0).hex())
        assertFalse(replay.finished)
        assertEquals("6a 82", replay.transceive(byteArrayOf(0x74, 0x97.toByte(), 0x00, 0x08, 0x00), 0).hex())
    }

    @Test
    fun parsesNotesAndExchanges() {
        val recording = parseRecording(RECORDING)

        assertEquals("Waveshare 2.7\" Rev2.2", recording.notes["board"])
        assertEquals("1.2.3", recording.notes["app"])
        assertEquals(listOf(UNLOCK.hex(), "74 97 00 08 00"), recording.exchanges.map { it.command.hex() })
    }

    @Test
    fun rejectsMissingNotes() {
        val error = assertFailsWith<IllegalArgumentException> { parseRecording(RECORDING.replace("# phone: Test phone\n", "")) }
        assertTrue("phone" in error.message.orEmpty(), error.message)
    }

    @Test
    fun rejectsStrayLines() {
        assertFailsWith<IllegalArgumentException> { parseRecording("$RECORDING\nWrite succeeded") }
        assertFailsWith<IllegalArgumentException> { parseRecording("$RECORDING\n< 90 00") }
        assertFailsWith<IllegalArgumentException> { parseRecording("$RECORDING\n> 74 97") }
        assertFailsWith<IllegalArgumentException> { parseRecording(RECORDING.replace("6a 82", "6a 8")) }
    }
}

private val UNLOCK = byteArrayOf(0x74, 0xB1.toByte(), 0x00, 0x00, 0x08, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)

/** A tiny hand-written recording for testing the format, not from hardware. */
private val RECORDING = """
    # board: Waveshare 2.7" Rev2.2
    # phone: Test phone
    # os: Test OS 1
    # app: 1.2.3
    # Free comments are allowed.

    > 74 b1 00 00 08 00 11 22 33 44 55 66 77
    < 90 00
    > 74 97 00 08 00
    < 6a 82
""".trimIndent()
