package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.diagnostics.formatDisplayReport
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
import io.github.cacack.keginker.probe.EZ_SIGN_PROTOCOL
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.WRITE_DEADLINE_MS
import io.github.cacack.keginker.waveshare.WRITE_TIMED_OUT_MESSAGE
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.toHex
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

private val REPORT_APP = AppInfo(version = "1.4.0", buildType = "release", commit = "abc1234")
private val REPORT_DEVICE = DeviceInfo(manufacturer = "Google", model = "Pixel 8", os = "Android 15", nfcEnabled = true)

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
            TestWriteOutcome(log, null, WriteResult.Failure(WriteStep.SEND_DATA, FailureKind.LOST_CONTACT, "Tag was lost"))
        } else {
            TestWriteOutcome(log, refreshMs = 1200, failure = null)
        }
    }
}

/** One pattern with a question; its write sends one command, then runs [write]. */
private class ScriptedFamily(
    override val deadlineMs: Long = WRITE_DEADLINE_MS,
    private val write: suspend () -> TestWriteOutcome,
) : TestWriteFamily {
    override val name = "Scripted"
    override val patterns = listOf(pattern("one", SIDE))

    override suspend fun write(
        transport: Transport,
        pattern: TestWritePattern,
        log: ExchangeLog,
        onProgress: (Int) -> Unit,
    ): TestWriteOutcome {
        transport.transceive(byteArrayOf(0x01), 100)
        return write()
    }
}

private fun succeeded() = TestWriteOutcome(ExchangeLog(), refreshMs = 1200, failure = null)

/** A probe of the EZ Sign display [PANEL_29] whose UID is [uid]. */
private fun ezSignProbe(uid: ByteArray) = ProbeResult(
    tag = TagInfo(listOf("IsoDep"), uid),
    protocol = EZ_SIGN_PROTOCOL,
    kind = TagKind.ISO_DEP,
    acceptedAids = emptyList(),
    capabilityContainer = null,
    ndef = null,
    note = null,
    exchanges = emptyList(),
    deviceInfo = PANEL_29,
)

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
        controller.answer(listOf("Left", "Red"))
        // The failed attempt stays, so the retried pattern appears twice.
        assertEquals(listOf("Pattern one", "Pattern one"), controller.records.map { it.pattern })
    }

    @Test
    fun answersThatAreNotOptionsOfEachQuestionAreIgnored() = runTest {
        val controller = TestWriteController(FakeFamily(), UID)
        controller.optIn()
        controller.onTag(display(RecordingTag()))
        val asking = controller.state.value

        controller.answer(listOf("Left"))
        controller.answer(listOf("Left", "Red", "Black"))
        controller.answer(listOf("Left", "Green"))
        controller.answer(emptyList())

        assertSame(asking, controller.state.value)
        assertTrue(controller.records.isEmpty())
        controller.answer(listOf("Left", "Red"))
        assertIs<TestWriteState.Waiting>(controller.state.value)
    }

    @Test
    fun anExceptionFromTheWriteIsRecordedAsAFailedWrite() = runTest {
        // The message quotes the UID, as an exception's might; the report hides it.
        val bug = IllegalStateException("Bug near ${UID.toHex()}")
        val tag = RecordingTag()
        val controller = TestWriteController(ScriptedFamily { throw bug }, UID)
        controller.optIn()

        controller.onTag(display(tag))

        val failed = assertIs<TestWriteState.Failed>(controller.state.value)
        assertEquals(TEST_WRITE_ERROR_MESSAGE, failed.message)
        assertSame(failed.record, controller.records.single())
        val failure = assertNotNull(failed.record.outcome.failure)
        // Coroutines may rethrow a copy, so compare what the report shows.
        assertIs<IllegalStateException>(failure.cause)
        assertEquals(bug.message, failure.cause?.message)
        assertTrue(tag.closed)
        val report = formatDisplayReport(REPORT_APP, REPORT_DEVICE, ezSignProbe(UID), controller.records)
        assertTrue("Cause: IllegalStateException: Bug near [UID 04 11 22 … (7 bytes)]" in report, report)
        assertTrue(UID.toHex() !in report, report)
    }

    @Test
    fun aWriteThatNeverEndsFailsAtTheDeadline() = runTest {
        val tag = RecordingTag()
        val controller = TestWriteController(ScriptedFamily { awaitCancellation() }, UID)
        controller.optIn()

        controller.onTag(display(tag))

        val failed = assertIs<TestWriteState.Failed>(controller.state.value)
        assertEquals(WRITE_TIMED_OUT_MESSAGE, failed.message)
        assertEquals(WRITE_DEADLINE_MS, testScheduler.currentTime)
        val failure = assertNotNull(controller.records.single().outcome.failure)
        assertEquals("didn't finish within ${WRITE_DEADLINE_MS / 1_000} s", failure.reason)
        assertTrue(tag.closed)
    }

    @Test
    fun aFamilyThatHoldsACommandOpenGetsItsOwnDeadline() = runTest {
        // EZ Sign's blocking refresh can take over 20 s on its own (#138).
        val controller = TestWriteController(ScriptedFamily(deadlineMs = 75_000) { delay(60_000); succeeded() }, UID)
        controller.optIn()

        controller.onTag(display(RecordingTag()))

        assertIs<TestWriteState.Asking>(controller.state.value)
        assertEquals(60_000, testScheduler.currentTime)
    }

    @Test
    fun cancelWhileWritingIsIgnoredAndTheWriteMovesOn() = runTest {
        val finish = CompletableDeferred<TestWriteOutcome>()
        val controller = TestWriteController(ScriptedFamily { finish.await() }, UID)
        controller.optIn()
        val write = launch { controller.onTag(display(RecordingTag())) }
        runCurrent()
        val writing = assertIs<TestWriteState.Writing>(controller.state.value)

        controller.cancel()

        assertSame(writing, controller.state.value)
        finish.complete(succeeded())
        write.join()
        assertIs<TestWriteState.Asking>(controller.state.value)
    }

    @Test
    fun thePatternsAreGeneratedOnlyOnceTheUserOptsIn() {
        var reads = 0
        val family = object : TestWriteFamily by FakeFamily() {
            override val patterns: List<TestWritePattern>
                get() = FakeFamily().patterns.also { reads++ }
        }

        val controller = TestWriteController(family, UID)

        assertEquals(0, reads)
        controller.optIn()
        assertIs<TestWriteState.Waiting>(controller.state.value)
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
            protocol = EZ_SIGN_PROTOCOL,
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

    @Test
    fun aProbeWithAUidTooShortToMatchOnOffersNone() {
        for (uid in listOf(ByteArray(0), byteArrayOf(0x04), byteArrayOf(0x04, 0x11, 0x22))) {
            assertNull(testWriteControllerFor(ezSignProbe(uid)), "${uid.size}-byte UID")
        }
        assertNotNull(testWriteControllerFor(ezSignProbe(byteArrayOf(0x04, 0x11, 0x22, 0x33))))
    }
}
