package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PANEL = WavesharePanel.EPD_2IN7

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun ByteArray.isIns(ins: Int) = size >= 2 && this[0] == 0x74.toByte() && this[1] == ins.toByte()

/** Records every APDU; by default answers `90 00`, and "not busy" to the busy poll. */
private class FakeIsoDepTag(
    private val reply: (command: ByteArray, index: Int) -> ByteArray = { cmd, _ -> defaultReply(cmd) },
) : Transport {
    val sent = mutableListOf<ByteArray>()

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        return reply(command, sent.size - 1)
    }

    companion object {
        val OK = bytes(0x90, 0x00)
        val IDLE = bytes(0x00, 0x90, 0x00)
        val BUSY = bytes(0x01, 0x90, 0x00)

        fun defaultReply(command: ByteArray) = if (command.isIns(0x9B)) IDLE else OK
    }
}

private fun testFrame() = ByteArray(PANEL.controllerFrameSize) { (it * 7).toByte() }

/** APDUs before the image data: unlock, 2 resets, 8 register writes of 2 APDUs, select RAM. */
private const val SETUP_APDUS = 1 + 2 + 8 * 2 + 1

/** APDUs after the image data: update control (2), activate, one busy poll. */
private const val REFRESH_APDUS = 4

class WaveshareIsoDepWriterTest {
    @Test
    fun writesTheRev22SequenceFor2in7() = runTest {
        val tag = FakeIsoDepTag()

        val result = WaveshareIsoDepWriter(tag).write(PANEL, testFrame())

        assertEquals(WriteResult.Success, result)
        val sent = tag.sent.map { it.hex() }
        val header = listOf(
            "74 b1 00 00 08 00 11 22 33 44 55 66 77", // unlock
            "74 97 00 08 00", "74 97 01 08 00", // reset
            "74 99 00 0d 01 01", "74 9a 00 0e 03 27 01 01", // gates: 296
            "74 99 00 0d 01 11", "74 9a 00 0e 01 01", // X+, Y−
            "74 99 00 0d 01 44", "74 9a 00 0e 02 00 15", // X 0–21 bytes
            "74 99 00 0d 01 45", "74 9a 00 0e 04 27 01 00 00", // Y 295–0
            "74 99 00 0d 01 3c", "74 9a 00 0e 01 01", // border
            "74 99 00 0d 01 18", "74 9a 00 0e 01 80", // temperature sensor
            "74 99 00 0d 01 4e", "74 9a 00 0e 01 00", // X counter
            "74 99 00 0d 01 4f", "74 9a 00 0e 02 27 01", // Y counter
            "74 99 00 0d 01 24", // black/white RAM
        )
        val footer = listOf(
            "74 99 00 0d 01 22", "74 9a 00 0e 01 f7", // update sequence
            "74 99 00 0d 01 20", // activate
            "74 9b 00 0f 01", // busy poll
        )
        assertEquals(SETUP_APDUS, header.size)
        assertEquals(header, sent.take(header.size))
        assertEquals(footer, sent.takeLast(footer.size))
        assertEquals(27, sent.size - header.size - footer.size)
    }

    @Test
    fun dataChunksCarryTheWholeControllerFrameIn250ByteApdus() = runTest {
        val tag = FakeIsoDepTag()
        val frame = testFrame()

        WaveshareIsoDepWriter(tag).write(PANEL, frame)

        val chunks = tag.sent.drop(SETUP_APDUS).dropLast(REFRESH_APDUS)
        assertTrue(chunks.all { it.isIns(0x9A) && (it[4].toInt() and 0xFF) == it.size - 5 })
        assertEquals(List(26) { 250 } + 12, chunks.map { it.size - 5 })
        assertContentEquals(frame, chunks.fold(ByteArray(0)) { acc, apdu -> acc + apdu.copyOfRange(5, apdu.size) })
    }

    @Test
    fun logKeepsOnlyTheHeaderOfRamChunksAndRegisterWritesInFull() = runTest {
        val tag = FakeIsoDepTag()
        val log = ExchangeLog()

        WaveshareIsoDepWriter(tag, pause = {}, log = log).write(PANEL, testFrame())

        val entries = log.entries
        assertEquals(tag.sent.size, entries.size)
        val chunks = entries.drop(SETUP_APDUS).dropLast(REFRESH_APDUS)
        assertEquals(List(26) { "74 9a 00 0e fa" } + "74 9a 00 0e 0c", chunks.map { it.command.hex() })
        assertEquals(List(26) { 250 } + 12, chunks.map { it.imageBytes })
        // Unlock, resets, register writes and refresh are recorded exactly as sent.
        val others = entries.zip(tag.sent).take(SETUP_APDUS) + entries.zip(tag.sent).takeLast(REFRESH_APDUS)
        assertTrue(others.all { (entry, sent) -> entry.imageBytes == 0 && entry.command.contentEquals(sent) })
        assertTrue(others.any { (entry, _) -> entry.command.hex() == "74 9a 00 0e 03 27 01 01" })
    }

    @Test
    fun logRecordsTheFailedChunk() = runTest {
        val tag = FakeIsoDepTag { cmd, index -> if (index == SETUP_APDUS + 1) error("tag lost") else FakeIsoDepTag.defaultReply(cmd) }
        val log = ExchangeLog()

        WaveshareIsoDepWriter(tag, pause = {}, log = log).write(PANEL, testFrame())

        val last = log.entries.last()
        assertEquals(WriteStep.SEND_DATA, last.step)
        assertEquals("IllegalStateException: tag lost", last.error)
        assertEquals("74 9a 00 0e fa", last.command.hex())
    }

    @Test
    fun replyWithoutSuccessStatusStopsAtTheFailingStep() = runTest {
        val tag = FakeIsoDepTag { cmd, _ -> if (cmd.isIns(0xB1)) bytes(0x69, 0x82) else FakeIsoDepTag.defaultReply(cmd) }

        val result = assertIs<WriteResult.Failure>(WaveshareIsoDepWriter(tag).write(PANEL, testFrame()))

        assertEquals(WriteStep.UNLOCK, result.step)
        assertEquals(FailureKind.UNEXPECTED_REPLY, result.kind)
        assertTrue("69 82" in result.reason, result.reason)
        assertEquals(1, tag.sent.size)
    }

    @Test
    fun transportErrorDuringDataNamesTheChunk() = runTest {
        val failingChunk = 11
        val tag = FakeIsoDepTag { cmd, index ->
            if (index == SETUP_APDUS + failingChunk - 1) error("tag lost") else FakeIsoDepTag.defaultReply(cmd)
        }

        val result = assertIs<WriteResult.Failure>(WaveshareIsoDepWriter(tag).write(PANEL, testFrame()))

        assertEquals(WriteStep.SEND_DATA, result.step)
        assertTrue("chunk $failingChunk/27" in result.reason, result.reason)
    }

    @Test
    fun keepsPollingWhileBusyThenSucceeds() = runTest {
        var polls = 0
        val tag = FakeIsoDepTag { cmd, _ ->
            if (cmd.isIns(0x9B)) (if (++polls < 5) FakeIsoDepTag.BUSY else FakeIsoDepTag.IDLE) else FakeIsoDepTag.OK
        }

        assertEquals(WriteResult.Success, WaveshareIsoDepWriter(tag).write(PANEL, testFrame()))
        assertEquals(5, polls)
    }

    @Test
    fun refreshThatNeverFinishesFails() = runTest {
        val tag = FakeIsoDepTag { cmd, _ -> if (cmd.isIns(0x9B)) FakeIsoDepTag.BUSY else FakeIsoDepTag.OK }

        val result = assertIs<WriteResult.Failure>(WaveshareIsoDepWriter(tag).write(PANEL, testFrame()))

        assertEquals(WriteStep.WAIT_FOR_REFRESH, result.step)
        assertEquals(FailureKind.REFRESH_UNCONFIRMED, result.kind)
        assertEquals(REFRESH_UNCONFIRMED_MESSAGE, result.userMessage)
        assertEquals(24, tag.sent.count { it.isIns(0x9B) })
    }

    @Test
    fun pollReplyWithoutSuccessStatusIsNotDone() = runTest {
        // An idle-looking byte without 90 00 doesn't confirm the refresh.
        val tag = FakeIsoDepTag { cmd, _ -> if (cmd.isIns(0x9B)) bytes(0x00, 0x6F, 0x00) else FakeIsoDepTag.OK }

        val result = assertIs<WriteResult.Failure>(WaveshareIsoDepWriter(tag).write(PANEL, testFrame()))

        assertEquals(WriteStep.WAIT_FOR_REFRESH, result.step)
    }

    @Test
    fun losingTheTagWhileWaitingIsAFailure() = runTest {
        val tag = FakeIsoDepTag { cmd, _ -> if (cmd.isIns(0x9B)) error("tag lost") else FakeIsoDepTag.OK }

        val result = assertIs<WriteResult.Failure>(WaveshareIsoDepWriter(tag).write(PANEL, testFrame()))

        assertEquals(WriteStep.WAIT_FOR_REFRESH, result.step)
        assertEquals(FailureKind.LOST_CONTACT, result.kind)
    }

    @Test
    fun progressRisesToOneHundredAndKeepsMovingDuringTheRefresh() = runTest {
        val progress = mutableListOf<Int>()

        WaveshareIsoDepWriter(FakeIsoDepTag()).write(PANEL, testFrame()) { progress += it }

        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.all { it in 0..100 }, "$progress")
        assertTrue(IMAGE_SENT_PROGRESS in progress, "The last chunk reports the image sent: $progress")
        assertEquals(listOf(91, 92, 93, 94, 95, 96), progress.filter { it in 91..99 })
    }

    @Test
    fun rejectsAFrameOfTheWrongSize() = runTest {
        assertFailsWith<IllegalArgumentException> { WaveshareIsoDepWriter(FakeIsoDepTag()).write(PANEL, ByteArray(10)) }
    }
}
