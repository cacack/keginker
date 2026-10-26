package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.ProbeResult
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * A display family that Report a display can test-write to: generated
 * patterns, and a writer that sends one and times the refresh. The UI
 * walks [patterns] and asks each one's questions without knowing the family.
 */
interface TestWriteFamily {
    /** The family as reports name it. */
    val name: String

    /**
     * The patterns to write, in order, generated for this display. Read only
     * once the user opts in, since a pattern holds a byte per pixel.
     */
    val patterns: List<TestWritePattern>

    /**
     * Writes [pattern] to the display over [transport], recording every
     * exchange in [log]. A write the tag fails, and a refresh that runs past
     * the budget, are in the outcome, not thrown. It throws
     * [kotlin.coroutines.cancellation.CancellationException] when cancelled,
     * and [IllegalArgumentException] when [pattern] wasn't generated for this
     * display (not one of [patterns]). Nothing else is expected;
     * [io.github.cacack.keginker.TestWriteController] records any exception
     * but cancellation as a failed write.
     * [onProgress] receives 0–100 and never decreases.
     */
    @Throws(Exception::class)
    suspend fun write(
        transport: Transport,
        pattern: TestWritePattern,
        log: ExchangeLog,
        onProgress: (Int) -> Unit = {},
    ): TestWriteOutcome
}

/** The family to test-write to for a probed tag; null unless its EZ Sign device information decoded. */
fun testWriteFamilyFor(result: ProbeResult): TestWriteFamily? = result.deviceInfo?.let { EzSignTestWriteFamily(it) }

/** EZ Sign, for the display [info] describes; [pause] and [timeSource] are the writer's. */
internal class EzSignTestWriteFamily(
    private val info: EzSignDeviceInfo,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : TestWriteFamily {
    override val name = "EZ Sign"

    /** Built on first use, so offering test writes allocates no pixels. */
    override val patterns by lazy { ezSignTestPatterns(info) }

    @Throws(Exception::class)
    override suspend fun write(
        transport: Transport,
        pattern: TestWritePattern,
        log: ExchangeLog,
        onProgress: (Int) -> Unit,
    ): TestWriteOutcome = EzSignTestWriter(transport, info, pause, timeSource).write(pattern, log, onProgress)
}

/** The answer the user chose to one of a test write's questions. */
class TestAnswer(
    /** The [TestQuestion.id]. */
    val questionId: String,
    /** The question as it was asked. */
    val question: String,
    val answer: String,
)

/**
 * One test write, for the display report: the pattern written, how the
 * write went, and the answers to its questions; empty when the write failed
 * or the user stopped before answering. A failed write keeps its record when
 * the pattern is retried, so a retried pattern has one record per attempt.
 */
class TestWriteRecord(
    /** The [TestWritePattern.title]. */
    val pattern: String,
    val outcome: TestWriteOutcome,
    val answers: List<TestAnswer>,
)
