package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.exchange
import io.github.cacack.keginker.waveshare.exchangeOk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** Answers every command with [reply], advancing [clock] by [takesMs] first. */
private class FakeTag(
    private val clock: TestTimeSource? = null,
    private val takesMs: Long = 0,
    private val reply: (ByteArray) -> ByteArray = { bytes(0x90, 0x00) },
) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        clock?.plusAssign(takesMs.milliseconds)
        return reply(command)
    }
}

class ExchangeLogTest {
    @Test
    fun recordsWhenEachCommandWasSent() = runTest {
        val clock = TestTimeSource()
        val log = ExchangeLog(clock)
        val tag = FakeTag(clock, takesMs = 7)

        clock += 5.milliseconds
        tag.exchange(WriteStep.UNLOCK, bytes(0x74, 0xB1), log = log)
        clock += 100.milliseconds
        tag.exchange(WriteStep.RESET, bytes(0x74, 0x97), log = log)

        assertEquals(listOf(5L, 112L), log.entries.map { it.atMs })
    }

    @Test
    fun keepsCommandAndReplyInFull() = runTest {
        val log = ExchangeLog()

        FakeTag().exchange(WriteStep.CONFIGURE, bytes(0x74, 0x9A, 0x00, 0x0E, 0x01, 0x80), log = log)

        val entry = log.entries.single()
        assertEquals(WriteStep.CONFIGURE, entry.step)
        assertContentEquals(bytes(0x74, 0x9A, 0x00, 0x0E, 0x01, 0x80), entry.command)
        assertEquals(0, entry.imageBytes)
        assertContentEquals(bytes(0x90, 0x00), entry.reply)
        assertNull(entry.error)
    }

    @Test
    fun dropsImageBytesKeepingOnlyTheHeader() = runTest {
        val log = ExchangeLog()

        FakeTag().exchange(WriteStep.SEND_DATA, bytes(0xCD, 0x19, 0x03, 0xAA, 0xBB, 0xCC), log = log, imageBytes = 3)

        val entry = log.entries.single()
        assertContentEquals(bytes(0xCD, 0x19, 0x03), entry.command)
        assertEquals(3, entry.imageBytes)
    }

    @Test
    fun recordsTheExceptionOfAFailedExchange() = runTest {
        val log = ExchangeLog()
        val tag = FakeTag { error("tag lost") }

        assertFails { tag.exchange(WriteStep.SEND_DATA, bytes(0xCD, 0x19, 0x01, 0xAA), log = log, imageBytes = 1) }

        val entry = log.entries.single()
        assertEquals("IllegalStateException: tag lost", entry.error)
        assertNull(entry.reply)
        assertContentEquals(bytes(0xCD, 0x19, 0x01), entry.command)
    }

    @Test
    fun recordsAnUnexpectedReply() = runTest {
        val log = ExchangeLog()

        assertFails { FakeTag { bytes(0x69, 0x82) }.exchangeOk(WriteStep.UNLOCK, bytes(0x74, 0xB1), { false }, log = log) }

        assertContentEquals(bytes(0x69, 0x82), log.entries.single().reply)
    }

    @Test
    fun cancellationIsRecordedAndStillPropagates() = runTest {
        val log = ExchangeLog()
        val tag = FakeTag { throw CancellationException("stopped") }

        assertFailsWith<CancellationException> { tag.exchange(WriteStep.REFRESH, bytes(0xCD, 0x09), log = log) }

        assertEquals("CancellationException: stopped", log.entries.single().error)
    }
}
