package io.github.cacack.tagpaper.waveshare

import io.github.cacack.tagpaper.nfc.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PANEL = WavesharePanel.EPD_2IN7

/** Records every command and answers with [reply]. */
private class FakeTransport(
    private val reply: (command: ByteArray, index: Int) -> ByteArray = { cmd, _ -> defaultReply(cmd) },
) : Transport {
    val sent = mutableListOf<ByteArray>()

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        return reply(command, sent.size - 1)
    }

    companion object {
        val OK = bytes(0x00, 0x00)
        val DONE = bytes(0xFF, 0x00)
        val BUSY = bytes(0x00, 0x01)

        fun defaultReply(command: ByteArray) = if (command.isCommand(0x0A)) DONE else OK
    }
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun ByteArray.isCommand(code: Int) = size >= 2 && this[0] == 0xCD.toByte() && this[1] == code.toByte()

private fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** A frame whose bytes are distinguishable by position. */
private fun testFrame() = ByteArray(PANEL.frameSize) { (it * 7).toByte() }

class WaveshareWriterTest {
    @Test
    fun writesThe2in7SequenceInOrder() = runTest {
        val transport = FakeTransport()

        val result = WaveshareWriter(transport).write(PANEL, testFrame())

        assertEquals(WriteResult.Success, result)
        val header = listOf("cd 0d", "cd 00 10", "cd 01", "cd 02", "cd 03", "cd 05", "cd 06", "cd 07 00", "cd 18")
        val footer = listOf("cd 09", "cd 0a", "cd 04")
        val sent = transport.sent.map { it.hex() }
        assertEquals(header, sent.take(header.size))
        assertEquals(footer, sent.takeLast(footer.size))
        val data = sent.drop(header.size).dropLast(footer.size)
        assertEquals(48, data.size)
        assertTrue(data.all { it.startsWith("cd 19 79 ") })
    }

    @Test
    fun dataChunksCarryTheFrameInOrder() = runTest {
        val transport = FakeTransport()
        val frame = testFrame()

        WaveshareWriter(transport).write(PANEL, frame)

        val chunks = transport.sent.filter { it.isCommand(0x19) }
        assertTrue(chunks.all { it.size == 3 + 121 })
        val payload = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk.copyOfRange(3, chunk.size) }
        assertTrue(frame.contentEquals(payload))
    }

    @Test
    fun unexpectedReplyStopsAtTheFailingStep() = runTest {
        val transport = FakeTransport { cmd, _ -> if (cmd.isCommand(0x00)) bytes(0x01, 0x00) else FakeTransport.defaultReply(cmd) }

        val result = WaveshareWriter(transport).write(PANEL, testFrame())

        assertIs<WriteResult.Failure>(result)
        assertEquals(WriteStep.SELECT_PANEL, result.step)
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun transportErrorDuringDataNamesSendData() = runTest {
        var chunks = 0
        val transport = FakeTransport { cmd, _ ->
            if (cmd.isCommand(0x19) && ++chunks == 10) error("tag lost")
            FakeTransport.defaultReply(cmd)
        }

        val result = WaveshareWriter(transport).write(PANEL, testFrame())

        assertIs<WriteResult.Failure>(result)
        assertEquals(WriteStep.SEND_DATA, result.step)
        assertTrue("chunk 10/48" in result.reason)
    }

    @Test
    fun refreshThatNeverCompletesFails() = runTest {
        val transport = FakeTransport { cmd, _ -> if (cmd.isCommand(0x0A)) FakeTransport.BUSY else FakeTransport.OK }

        val result = WaveshareWriter(transport).write(PANEL, testFrame())

        assertIs<WriteResult.Failure>(result)
        assertEquals(WriteStep.WAIT_FOR_REFRESH, result.step)
        assertEquals(240, transport.sent.count { it.isCommand(0x0A) })
        assertTrue(transport.sent.none { it.isCommand(0x04) })
    }

    @Test
    fun losingTheTagWhileWaitingIsAFailure() = runTest {
        val transport = FakeTransport { cmd, _ -> if (cmd.isCommand(0x0A)) error("tag lost") else FakeTransport.OK }

        val result = WaveshareWriter(transport).write(PANEL, testFrame())

        assertIs<WriteResult.Failure>(result)
        assertEquals(WriteStep.WAIT_FOR_REFRESH, result.step)
    }

    @Test
    fun progressRisesToOneHundred() = runTest {
        val progress = mutableListOf<Int>()

        WaveshareWriter(FakeTransport()).write(PANEL, testFrame()) { progress += it }

        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun rejectsAFrameOfTheWrongSize() = runTest {
        assertFailsWith<IllegalArgumentException> {
            WaveshareWriter(FakeTransport()).write(PANEL, ByteArray(100))
        }
    }
}
