package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.image.forEachSeed
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.TRANSCEIVE_TIMEOUT_MS
import io.github.cacack.keginker.waveshare.WRITE_DEADLINE_MS
import io.github.cacack.keginker.waveshare.WriteStep
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

private fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun family(info: EzSignDeviceInfo) = EzSignTestWriteFamily(info)

private fun probeResult(info: EzSignDeviceInfo?) = ProbeResult(
    tag = null,
    protocol = "unsupported",
    kind = TagKind.ISO_DEP,
    acceptedAids = emptyList(),
    capabilityContainer = null,
    ndef = null,
    note = null,
    exchanges = emptyList(),
    deviceInfo = info,
)

/** Answers like [tag], except that [failing] commands get [instead]. */
private class Interfering(
    private val tag: Transport,
    private val failing: (ByteArray) -> Boolean,
    private val instead: suspend () -> ByteArray,
) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray =
        if (failing(command)) instead() else tag.transceive(command, timeoutMs)
}

class EzSignTestWriterTest {
    @Test
    fun everyPatternLandsInPanelMemoryBitForBit() = runTest {
        for (info in listOf(PANEL_29, PANEL_42, PANEL_MONO_122)) {
            for (pattern in ezSignTestPatterns(info)) {
                val tag = SimulatedEzSignTag(info)

                val outcome = family(info).write(tag, pattern, ExchangeLog())

                val label = "${pattern.id} on ${info.nativeWidth} × ${info.nativeHeight}"
                assertNull(outcome.failure, "$label: ${outcome.failure?.reason}, tag said ${tag.lastError}")
                assertNotNull(outcome.refreshMs, label)
                assertContentEquals(packFrame(pattern.pixels, info), tag.shown, label)
                assertContentEquals(pattern.pixels, tag.shownPixels(), label)
            }
        }
    }

    @Test
    fun randomFramesOnRandomPanelsLandBitForBit() = runTest {
        forEachSeed { random ->
            val info = panelInfo(random.nextInt(1, 401), random.nextInt(1, 301), if (random.nextBoolean()) 0x07 else 0x01)
            val pixels = ByteArray(info.nativeWidth * info.nativeHeight) { random.nextInt(1 shl info.bitsPerPixel).toByte() }
            val pattern = TestWritePattern("random", "Random", info.nativeWidth, info.nativeHeight, pixels, emptyList())
            val tag = SimulatedEzSignTag(info)

            val outcome = family(info).write(tag, pattern, ExchangeLog())

            assertNull(outcome.failure, "${outcome.failure?.reason}, tag said ${tag.lastError}")
            assertContentEquals(pixels, tag.shownPixels())
        }
    }

    @Test
    fun sendsSelectAuthenticateFragmentsThenTheBlockingRefresh() = runTest {
        val tag = SimulatedEzSignTag(PANEL_42)
        val log = ExchangeLog()

        family(PANEL_42).write(tag, cornerPattern(PANEL_42), log)

        val sent = tag.sent.map { it.hex() }
        assertEquals("00 a4 04 00 07 d2 76 00 00 85 01 01 00", sent[0])
        assertEquals("00 20 00 01 04 20 09 12 10", sent[1])
        // 15 blocks, each a 2,012-byte stream in 9 fragments: 8 of 250 and one of 12.
        val data = tag.sent.subList(2, 2 + 135)
        for ((i, command) in data.withIndex()) {
            val block = i / 9
            val fragment = i % 9
            val last = fragment == 8
            val header = listOf(0xF0, 0xD3, 0x00, if (last) 0x01 else 0x00, if (last) 14 else 252, block, fragment)
            assertEquals(header.map { it.toByte() }, command.take(7), "command ${i + 2}")
        }
        // The blocking refresh's reply ends the write; no poll follows (#138).
        assertEquals(listOf("f0 d4 85 00 00"), sent.drop(2 + 135))
        // The log keeps each fragment's header, never its data.
        val images = log.entries.filter { it.step == WriteStep.SEND_DATA }
        assertEquals(135, images.size)
        assertTrue(images.all { it.command.size == 7 && it.imageBytes == (it.command[4].toInt() and 0xFF) - 2 })
    }

    @Test
    fun theRefreshTimeIsHowLongTheTagHeldItsReply() = runTest {
        forEachSeed { random ->
            // From instant to beyond an iOS session: a 4-colour refresh took about 24 s (#138).
            val busyMs = random.nextLong(0, BLOCKING_REFRESH_TIMEOUT_MS + 1)
            val clock = TestTimeSource()
            val tag = SimulatedEzSignTag(PANEL_29, clock = clock, commandMs = 8, busyMs = busyMs)

            val outcome = family(PANEL_29).write(tag, colourPattern(PANEL_29), ExchangeLog(clock))

            assertNull(outcome.failure, "refresh of $busyMs ms")
            assertEquals(busyMs + 8, outcome.refreshMs)
            assertTrue(tag.sent.none { it[1] == 0xDE.toByte() }, "polled after the blocking refresh")
        }
    }

    @Test
    fun onlyTheRefreshWaitsLongerThanTheUsualTimeout() = runTest {
        val timeouts = mutableListOf<Pair<Int, Long>>()
        val tag = SimulatedEzSignTag(PANEL_42)
        val recording = object : Transport {
            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
                timeouts += (command[1].toInt() and 0xFF) to timeoutMs
                return tag.transceive(command, timeoutMs)
            }
        }

        family(PANEL_42).write(recording, cornerPattern(PANEL_42), ExchangeLog())

        val (refresh, others) = timeouts.partition { it.first == 0xD4 }
        assertEquals(listOf(0xD4 to BLOCKING_REFRESH_TIMEOUT_MS), refresh)
        assertTrue(others.all { it.second == TRANSCEIVE_TIMEOUT_MS }, "$others")
        assertContentEquals(byteArrayOf(0xF0.toByte(), 0xD4.toByte(), 0x85.toByte(), 0x00, 0x00), tag.sent.single { it[1] == 0xD4.toByte() })
    }

    @Test
    fun aRefreshThatOutlastsItsTimeoutFailsAtTheRefresh() = runTest {
        val clock = TestTimeSource()
        val tag = SimulatedEzSignTag(PANEL_29, clock = clock, busyMs = BLOCKING_REFRESH_TIMEOUT_MS + 1)

        val outcome = family(PANEL_29).write(tag, cornerPattern(PANEL_29), ExchangeLog(clock))

        val failure = assertNotNull(outcome.failure)
        assertEquals(WriteStep.REFRESH, failure.step)
        assertEquals(FailureKind.LOST_CONTACT, failure.kind)
        assertNull(outcome.refreshMs)
        // The log still shows how long the refresh ran before the timeout.
        val refresh = outcome.log.entries.single { it.step == WriteStep.REFRESH }
        assertNotNull(refresh.error)
    }

    @Test
    fun ezSignWritesHaveRoomForTheBlockingRefresh() {
        assertEquals(WRITE_DEADLINE_MS + BLOCKING_REFRESH_TIMEOUT_MS, family(PANEL_29).deadlineMs)
    }

    @Test
    fun aRefusedAuthenticationFailsBeforeAnyImageIsSent() = runTest {
        val tag = SimulatedEzSignTag(PANEL_42)
        val refusing = Interfering(tag, { it[1] == 0x20.toByte() }) { byteArrayOf(0x63, 0x00) }

        val outcome = family(PANEL_42).write(refusing, cornerPattern(PANEL_42), ExchangeLog())

        val failure = assertNotNull(outcome.failure)
        assertEquals(WriteStep.AUTHENTICATE, failure.step)
        assertEquals(FailureKind.UNEXPECTED_REPLY, failure.kind)
        assertNull(outcome.refreshMs)
        assertEquals(1, tag.sent.size)
    }

    @Test
    fun losingTheTagMidImageNamesTheStep() = runTest {
        var fragments = 0
        val tag = SimulatedEzSignTag(PANEL_42)
        val lost = Interfering(tag, { it[1] == 0xD3.toByte() && ++fragments == 20 }) { throw Exception("Tag was lost") }

        val outcome = family(PANEL_42).write(lost, cornerPattern(PANEL_42), ExchangeLog())

        val failure = assertNotNull(outcome.failure)
        assertEquals(WriteStep.SEND_DATA, failure.step)
        assertEquals(FailureKind.LOST_CONTACT, failure.kind)
        assertEquals(outcome.log.entries.last().error, "Exception: Tag was lost")
    }

    @Test
    fun aRefusedRefreshIsAFailure() = runTest {
        val tag = SimulatedEzSignTag(PANEL_42)
        val odd = Interfering(tag, { it[1] == 0xD4.toByte() }) { byteArrayOf(0x6A, 0x80.toByte()) }

        val outcome = family(PANEL_42).write(odd, cornerPattern(PANEL_42), ExchangeLog())

        val failure = assertNotNull(outcome.failure)
        assertEquals(WriteStep.REFRESH, failure.step)
        assertEquals(FailureKind.UNEXPECTED_REPLY, failure.kind)
        assertNull(outcome.refreshMs)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val tag = SimulatedEzSignTag(PANEL_42)
        val cancelled = Interfering(tag, { it[1] == 0xD4.toByte() }) { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> {
            family(PANEL_42).write(cancelled, cornerPattern(PANEL_42), ExchangeLog())
        }
    }

    @Test
    fun onlyATagWhoseEzSignInformationDecodedGetsAFamily() {
        assertNull(testWriteFamilyFor(probeResult(null)))
        val family = assertIs<EzSignTestWriteFamily>(testWriteFamilyFor(probeResult(PANEL_29)))
        assertEquals("EZ Sign", family.name)
        assertEquals(listOf("corner", "colours"), family.patterns.map { it.id })
    }

    @Test
    fun progressRisesTo100() = runTest {
        val seen = mutableListOf<Int>()

        family(PANEL_29).write(SimulatedEzSignTag(PANEL_29), cornerPattern(PANEL_29), ExchangeLog()) { seen += it }

        assertEquals(seen.sorted(), seen)
        assertEquals(100, seen.last())
    }
}
