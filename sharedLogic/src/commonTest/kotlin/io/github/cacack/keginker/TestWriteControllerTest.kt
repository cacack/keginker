package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.ezsign.PANEL_29
import io.github.cacack.keginker.ezsign.SimulatedEzSignTag
import io.github.cacack.keginker.ezsign.TestQuestion
import io.github.cacack.keginker.ezsign.TestWriteFamily
import io.github.cacack.keginker.ezsign.TestWriteOutcome
import io.github.cacack.keginker.ezsign.TestWritePattern
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val UID = byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
private val OTHER_UID = byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x67)

/** A tag that answers `90 00` to everything. */
private class RecordingTag : Transport {
    val sent = mutableListOf<ByteArray>()
    var closed = false

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        return byteArrayOf(0x90.toByte(), 0x00)
    }

    override fun close() {
        closed = true
    }
}

private fun pattern(id: String, vararg questions: TestQuestion) =
    TestWritePattern(id, "Pattern $id", 1, 1, ByteArray(1), questions.toList())

private val SIDE = TestQuestion("side", "Which side?", listOf("Left", "Right"))
private val COLOUR = TestQuestion("colour", "Which colour?", listOf("Black", "Red"))

/** Two patterns; sends one command per write and fails the first [failures] writes. */
private class FakeFamily(private val failures: Int = 0) : TestWriteFamily {
    override val name = "Fake"
    override val patterns = listOf(pattern("one", SIDE, COLOUR), pattern("two", SIDE))
    val written = mutableListOf<String>()

    override suspend fun write(
        transport: Transport,
        pattern: TestWritePattern,
        log: ExchangeLog,
        onProgress: (Int) -> Unit,
    ): TestWriteOutcome {
        transport.transceive(byteArrayOf(0x01), 100)
        written += pattern.id
        onProgress(100)
        return if (written.size <= failures) {
            TestWriteOutcome(log, null, null, WriteResult.Failure(WriteStep.SEND_DATA, FailureKind.LOST_CONTACT, "Tag was lost"))
        } else {
            TestWriteOutcome(log, refreshMs = 1200, busyAtMs = null, failure = null)
        }
    }
}

/** The probed display, or another ISO-DEP tag with [uid], whose transport opens as [tag]. */
private fun display(tag: Transport, uid: ByteArray = UID, kind: TagKind = TagKind.ISO_DEP) =
    FoundTag.Unsupported(TagInfo(listOf("IsoDep"), uid), kind) { tag }

class TestWriteControllerTest {
    @Test
    fun nothingIsWrittenBeforeTheOptIn() = runTest {
        val family = FakeFamily()
        val controller = TestWriteController(family, UID)
        var opened = false
        val writable = RecordingTag()

        controller.onTag(FoundTag.Unsupported(TagInfo(listOf("IsoDep"), UID), TagKind.ISO_DEP) { opened = true; RecordingTag() })
        controller.onTag(FoundTag.WaveshareIsoDep(writable))

        assertEquals(TestWriteState.Offered, controller.state.value)
        assertTrue(!opened)
        assertTrue(writable.sent.isEmpty())
        assertTrue(writable.closed)
        assertTrue(family.written.isEmpty())
    }

    @Test
    fun onlyTheProbedDisplayIsWritten() = runTest {
        val family = FakeFamily()
        val controller = TestWriteController(family, UID)
        controller.optIn()
        val other = RecordingTag()
        val nfcA = RecordingTag()
        val writable = RecordingTag()

        controller.onTag(display(other, OTHER_UID))
        controller.onTag(display(nfcA, kind = TagKind.NFC_A))
        controller.onTag(FoundTag.WaveshareIsoDep(writable, TagInfo(listOf("IsoDep"), UID)))

        val waiting = assertIs<TestWriteState.Waiting>(controller.state.value)
        assertEquals(SAME_DISPLAY_MESSAGE, waiting.message)
        assertEquals(0, waiting.index)
        listOf(other, nfcA, writable).forEach { assertTrue(it.sent.isEmpty()) }
        assertTrue(writable.closed)
        assertTrue(family.written.isEmpty())
    }

    @Test
    fun recordsAccumulateAcrossPatterns() = runTest {
        val controller = TestWriteController(FakeFamily(), UID)
        controller.optIn()
        val first = RecordingTag()
        val second = RecordingTag()

        controller.onTag(display(first))
        val asking = assertIs<TestWriteState.Asking>(controller.state.value)
        assertEquals(listOf(SIDE, COLOUR), asking.questions)
        controller.answer(listOf("Left", "Red"))
        assertEquals(1, assertIs<TestWriteState.Waiting>(controller.state.value).index)
        controller.onTag(display(second))
        controller.answer(listOf("Right"))

        val done = assertIs<TestWriteState.Done>(controller.state.value)
        assertEquals(listOf("Pattern one", "Pattern two"), done.records.map { it.pattern })
        assertEquals(listOf("Left", "Red"), done.records[0].answers.map { it.answer })
        assertEquals(listOf("side", "colour"), done.records[0].answers.map { it.questionId })
        assertEquals(listOf("Right"), done.records[1].answers.map { it.answer })
        assertEquals(1200, done.records[1].outcome.refreshMs)
        assertTrue(first.closed && second.closed)
    }

    @Test
    fun cancelMidRunKeepsTheRecordsSoFar() = runTest {
        val controller = TestWriteController(FakeFamily(), UID)
        controller.optIn()
        controller.onTag(display(RecordingTag()))
        controller.answer(listOf("Left", "Black"))
        controller.onTag(display(RecordingTag()))

        controller.cancel()

        val done = assertIs<TestWriteState.Done>(controller.state.value)
        assertEquals(2, done.records.size)
        assertEquals(2, done.records[0].answers.size)
        // Written, but its question wasn't answered.
        assertTrue(done.records[1].answers.isEmpty())
    }

    @Test
    fun cancelWhileWaitingKeepsTheAnsweredRecords() = runTest {
        val controller = TestWriteController(FakeFamily(), UID)
        controller.optIn()
        controller.onTag(display(RecordingTag()))
        controller.answer(listOf("Left", "Black"))

        controller.cancel()

        assertEquals(1, assertIs<TestWriteState.Done>(controller.state.value).records.size)
    }

    @Test
    fun declinedTestWritesEndWithNoRecords() {
        val controller = TestWriteController(FakeFamily(), UID)

        controller.cancel()

        assertEquals(TestWriteState.Done(emptyList()), controller.state.value)
    }

    @Test
    fun retryAfterAFailureWritesTheSamePatternAgain() = runTest {
        val family = FakeFamily(failures = 1)
        val controller = TestWriteController(family, UID)
        controller.optIn()

        controller.onTag(display(RecordingTag()))
        val failed = assertIs<TestWriteState.Failed>(controller.state.value)
        assertTrue("Lost contact" in failed.message, failed.message)
        controller.onTag(display(RecordingTag()))
        assertSame(failed, controller.state.value, "A tag while failed is ignored")
        controller.retry()
        val waiting = assertIs<TestWriteState.Waiting>(controller.state.value)
        assertEquals("one", waiting.pattern.id)
        controller.onTag(display(RecordingTag()))

        assertIs<TestWriteState.Asking>(controller.state.value)
        assertEquals(listOf("one", "one"), family.written)
        assertSame(failed.record, controller.records.single())
        assertNotNull(failed.record.outcome.failure)
    }

    @Test
    fun answersMustBeOptionsOfEachQuestion() = runTest {
        val controller = TestWriteController(FakeFamily(), UID)
        controller.optIn()
        controller.onTag(display(RecordingTag()))

        assertFailsWith<IllegalArgumentException> { controller.answer(listOf("Left")) }
        assertFailsWith<IllegalArgumentException> { controller.answer(listOf("Left", "Green")) }
        assertIs<TestWriteState.Asking>(controller.state.value)
    }

    @Test
    fun aDisplayThatCanNotBeOpenedIsWaitedForAgain() = runTest {
        val controller = TestWriteController(FakeFamily(), UID)
        controller.optIn()

        controller.onTag(FoundTag.Unsupported(TagInfo(listOf("IsoDep"), UID), TagKind.ISO_DEP) { throw IllegalStateException("Tag was lost") })

        assertEquals(TEST_WRITE_LOST_MESSAGE, assertIs<TestWriteState.Waiting>(controller.state.value).message)
        assertTrue(controller.records.isEmpty())
    }

    @Test
    fun anInterruptedWriteWaitsForTheDisplayAgain() = runTest {
        val tag = object : Transport {
            var closed = false

            override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray = throw CancellationException("screen closed")

            override fun close() {
                closed = true
            }
        }
        val controller = TestWriteController(FakeFamily(), UID)
        controller.optIn()

        assertFailsWith<CancellationException> { controller.onTag(display(tag)) }

        val waiting = assertIs<TestWriteState.Waiting>(controller.state.value)
        assertNull(waiting.message)
        assertTrue(tag.closed)
    }

    @Test
    fun aProbedEzSignDisplayIsTestWrittenEndToEnd() = runTest {
        val result = ProbeResult(
            tag = TagInfo(listOf("IsoDep"), UID),
            protocol = "EZ Sign (ISO-DEP), no writer yet",
            kind = TagKind.ISO_DEP,
            acceptedAids = emptyList(),
            capabilityContainer = null,
            ndef = null,
            note = null,
            exchanges = emptyList(),
            deviceInfo = PANEL_29,
        )
        val controller = assertNotNull(testWriteControllerFor(result))
        val tag = SimulatedEzSignTag(PANEL_29)
        controller.optIn()
        val corner = assertIs<TestWriteState.Waiting>(controller.state.value).pattern

        controller.onTag(display(tag))

        assertIs<TestWriteState.Asking>(controller.state.value)
        assertContentEquals(corner.pixels, tag.shownPixels())
    }

    @Test
    fun aProbeWithoutATestWriteFamilyOffersNone() {
        val result = ProbeResult(
            tag = TagInfo(listOf("IsoDep"), UID),
            protocol = "unsupported",
            kind = TagKind.ISO_DEP,
            acceptedAids = emptyList(),
            capabilityContainer = null,
            ndef = null,
            note = null,
            exchanges = emptyList(),
        )

        assertNull(testWriteControllerFor(result))
    }
}
