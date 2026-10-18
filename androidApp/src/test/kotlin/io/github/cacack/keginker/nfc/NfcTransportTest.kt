package io.github.cacack.keginker.nfc

import android.nfc.Tag
import android.nfc.tech.TagTechnology
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Checks [NfcTransport] against the [Transport] contract, with a fake tag
 * technology in place of `NfcA`/`IsoDep`.
 */
class NfcTransportTest {
    /** Records connects, timeouts, sends and closes, in order. */
    private class FakeTechnology : TagTechnology {
        val events = mutableListOf<String>()
        var connected = false
        var failClose = false

        override fun getTag(): Tag = throw UnsupportedOperationException()

        override fun connect() {
            events += "connect"
            connected = true
        }

        override fun isConnected() = connected

        override fun close() {
            events += "close"
            if (failClose) throw IOException("already closed")
        }
    }

    private fun transport(
        technology: FakeTechnology = FakeTechnology(),
        exchange: (ByteArray) -> ByteArray = { REPLY },
    ) = NfcTransport(
        technology,
        setTimeout = { technology.events += "timeout $it" },
        exchange = { command -> technology.events += "send"; exchange(command) },
        logExchanges = false,
    )

    @Test
    fun connectsOnFirstUseOnly() = runBlocking {
        val technology = FakeTechnology()
        val transport = transport(technology)

        transport.transceive(COMMAND, 1000)
        transport.transceive(COMMAND, 1000)

        assertEquals(1, technology.events.count { it == "connect" })
        assertEquals("connect", technology.events.first())
    }

    @Test
    fun appliesEachCommandsTimeoutBeforeSendingIt() = runBlocking {
        val technology = FakeTechnology()
        val transport = transport(technology)

        transport.transceive(COMMAND, 5000)
        transport.transceive(COMMAND, 200)

        assertEquals(listOf("connect", "timeout 5000", "send", "timeout 200", "send"), technology.events)
    }

    @Test
    fun clampsTimeoutsToWhatTheTagTechnologyAccepts() = runBlocking {
        val technology = FakeTechnology()
        val transport = transport(technology)

        transport.transceive(COMMAND, 0)
        transport.transceive(COMMAND, Long.MAX_VALUE)

        assertEquals(listOf("timeout 1", "timeout ${Int.MAX_VALUE}"), technology.events.filter { it.startsWith("timeout") })
    }

    @Test
    fun sendsTheCommandAndReturnsTheReplyUnchanged() = runBlocking {
        var sent: ByteArray? = null
        val transport = transport { sent = it; REPLY }

        val reply = transport.transceive(COMMAND, 1000)

        assertContentEquals(COMMAND, sent)
        assertContentEquals(REPLY, reply)
    }

    @Test
    fun throwsWhenTheTagIsLost() {
        val transport = transport { throw IOException("Tag was lost.") }

        val error = assertFailsWith<IOException> { runBlocking { transport.transceive(COMMAND, 1000) } }
        assertEquals("Tag was lost.", error.message)
    }

    @Test
    fun throwsWhenConnectingFails() {
        val technology = object : TagTechnology {
            override fun getTag(): Tag = throw UnsupportedOperationException()
            override fun connect() = throw IOException("Tag was lost.")
            override fun isConnected() = false
            override fun close() {}
        }
        val transport = NfcTransport(technology, setTimeout = {}, exchange = { REPLY }, logExchanges = false)

        assertFailsWith<IOException> { runBlocking { transport.transceive(COMMAND, 1000) } }
    }

    @Test
    fun runsTagIoOffTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        var ioThread: Thread? = null
        val transport = transport { ioThread = Thread.currentThread(); REPLY }

        transport.transceive(COMMAND, 1000)

        assertNotSame(caller, ioThread)
    }

    @Test
    fun serializesCallsAcrossInstances() = runBlocking {
        val inFlight = AtomicInteger()
        val mostInFlight = AtomicInteger()
        val slowExchange = { _: ByteArray ->
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(10)
            inFlight.decrementAndGet()
            REPLY
        }
        val transports = List(2) { transport(exchange = slowExchange) }

        List(8) { i -> async(Dispatchers.Default) { transports[i % 2].transceive(COMMAND, 1000) } }.awaitAll()

        assertEquals(1, mostInFlight.get())
    }

    @Test
    fun closesQuietlyAndMoreThanOnce() {
        val technology = FakeTechnology().apply { failClose = true }
        val transport = transport(technology)

        transport.close()
        transport.close()

        assertEquals(listOf("close", "close"), technology.events)
    }

    private companion object {
        val COMMAND = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
        val REPLY = byteArrayOf(0x01, 0x02, 0x90.toByte(), 0x00)
    }
}
