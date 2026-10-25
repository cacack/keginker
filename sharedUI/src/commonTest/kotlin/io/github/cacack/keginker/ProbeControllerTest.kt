package io.github.cacack.keginker

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.nfc.classifyIsoDepTag
import io.github.cacack.keginker.probe.probeDisplay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A tag that rejects every command, the way a tag without the listed applications answers a SELECT. */
private class RejectingTag : Transport {
    val sent = mutableListOf<ByteArray>()
    var closed = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        return byteArrayOf(0x6A, 0x82.toByte())
    }

    override fun close() {
        closed = true
    }
}

private val INFO = TagInfo(listOf("IsoDep"), byteArrayOf(1, 2, 3, 4))

class ProbeControllerTest {
    @Test
    fun anArmedProbeReadsTheNextTag() = runTest {
        val controller = ProbeController()
        val tag = RejectingTag()
        controller.arm()

        controller.onTag(classifyIsoDepTag(INFO) { tag })

        val done = assertIs<ProbeState.Done>(controller.state.value)
        assertEquals(tag.sent.size, done.result.exchanges.size)
        assertTrue(tag.closed)
        assertTrue(!controller.isWaiting)
    }

    @Test
    fun anUnarmedProbeIgnoresTagsAndClosesThem() = runTest {
        val controller = ProbeController()
        val tag = RejectingTag()

        controller.onTag(FoundTag.WaveshareIsoDep(tag))

        assertEquals(ProbeState.Idle, controller.state.value)
        assertTrue(tag.sent.isEmpty())
        assertTrue(tag.closed)
    }

    @Test
    fun cancelReturnsToIdle() {
        val controller = ProbeController()
        controller.arm()

        controller.cancel()

        assertEquals(ProbeState.Idle, controller.state.value)
    }

    @Test
    fun anInterruptedReadWaitsForTheNextTag() = runTest {
        val controller = ProbeController { _, _ -> throw CancellationException("screen closed") }
        controller.arm()

        assertFailsWith<CancellationException> { controller.onTag(FoundTag.Unsupported(INFO)) }

        assertEquals(ProbeState.Waiting, controller.state.value)
    }

    @Test
    fun armingPassesTheVendorQueryChoiceToTheProbe() = runTest {
        val asked = mutableListOf<Boolean>()
        val controller = ProbeController { tag, vendorQueries ->
            asked += vendorQueries
            probeDisplay(tag, vendorQueries)
        }

        controller.arm(vendorQueries = true)
        controller.onTag(FoundTag.Unsupported(INFO))
        controller.arm()
        controller.onTag(FoundTag.Unsupported(INFO))

        assertEquals(listOf(true, false), asked)
        assertEquals(null, assertIs<ProbeState.Done>(controller.state.value).result.vendorQueriesListed)
    }
}
