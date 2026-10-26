package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.diagnostics.MIN_HIDDEN_UID_BYTES
import io.github.cacack.keginker.ezsign.TestAnswer
import io.github.cacack.keginker.ezsign.TestQuestion
import io.github.cacack.keginker.ezsign.TestWriteFamily
import io.github.cacack.keginker.ezsign.TestWriteOutcome
import io.github.cacack.keginker.ezsign.TestWritePattern
import io.github.cacack.keginker.ezsign.TestWriteRecord
import io.github.cacack.keginker.ezsign.testWriteFamilyFor
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.WRITE_TIMED_OUT_MESSAGE
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Shown when the tag presented for a test write isn't the display that was probed. */
const val SAME_DISPLAY_MESSAGE = "That isn't the display you reported. Hold the same display to the phone."

/** Shown when contact with the display was lost before a test write could start. */
const val TEST_WRITE_LOST_MESSAGE = "Lost contact with the display. Hold it still against the phone again."

/** Shown when a test write stopped with an error in KegInker itself; the report has the details. */
const val TEST_WRITE_ERROR_MESSAGE = "The test write stopped with an error in KegInker. Try again, or share the report so it can be fixed."

sealed interface TestWriteState {
    /** Test writes are offered; nothing is written until the user opts in. */
    data object Offered : TestWriteState

    /**
     * Waiting for the user to hold the probed display to the phone, to write
     * [pattern], number [index] (from 0) of [count]. [message] says why the
     * last tag presented wasn't written, if one wasn't.
     */
    data class Waiting(val pattern: TestWritePattern, val index: Int, val count: Int, val message: String? = null) : TestWriteState

    data class Writing(val pattern: TestWritePattern, val percent: Int) : TestWriteState

    /** [pattern] is on the display; waiting for the answers to its [questions]. */
    data class Asking(val pattern: TestWritePattern, val questions: List<TestQuestion>) : TestWriteState

    /**
     * Every pattern written, or the user stopped; [records] holds each write
     * so far, oldest first. A failed write stays in them, so a pattern that
     * was retried appears once per attempt.
     */
    data class Done(val records: List<TestWriteRecord>) : TestWriteState

    /** The write of [record]'s pattern failed, showing [message]; retry or cancel. */
    data class Failed(val record: TestWriteRecord, val message: String) : TestWriteState
}

/**
 * Report a display's guided test writes to the display a probe found:
 * [optIn], then for each of the [family]'s patterns, the next tag is written
 * if its UID is [probedUid], and the user answers what it showed. Nothing is
 * written before [optIn]. Both apps use it, like [WriteController].
 * A write still running after its family's [TestWriteFamily.deadlineMs] is stopped and fails.
 */
class TestWriteController(private val family: TestWriteFamily, probedUid: ByteArray) {
    private val uid = probedUid.copyOf()

    /** Read only after [optIn]: generating the patterns allocates their pixels. */
    private val patterns get() = family.patterns

    private val mutableState = MutableStateFlow<TestWriteState>(TestWriteState.Offered)
    val state: StateFlow<TestWriteState> = mutableState

    /** The family as reports name it, for the offer. */
    val familyName: String get() = family.name

    /** Whether the next found tag is for a test write. */
    val isWaiting: Boolean get() = mutableState.value is TestWriteState.Waiting

    /** Every write so far, oldest first; a retried pattern appears once per attempt. */
    val records: List<TestWriteRecord> get() = recorded.toList()

    private val recorded = mutableListOf<TestWriteRecord>()
    private var index = 0

    /** The write whose questions are being asked. */
    private var unanswered: TestWriteOutcome? = null

    /** The user's opt-in: waits for the display to write the first pattern. Ignored unless offered. */
    fun optIn() {
        if (mutableState.value == TestWriteState.Offered) mutableState.value = nextState()
    }

    /**
     * Records [choices], one of each question's options in order, and waits
     * for the display to write the next pattern, or ends. Ignored unless
     * asking, and ignored when [choices] doesn't answer every question with
     * one of its options.
     */
    fun answer(choices: List<String>) {
        val asking = mutableState.value as? TestWriteState.Asking ?: return
        val outcome = unanswered ?: return
        if (choices.size != asking.questions.size) return
        if (asking.questions.zip(choices).any { (question, choice) -> choice !in question.options }) return
        recorded += TestWriteRecord(asking.pattern.title, outcome, asking.questions.zip(choices) { question, choice ->
            TestAnswer(question.id, question.text, choice)
        })
        unanswered = null
        index++
        mutableState.value = nextState()
    }

    /** After a failure, waits for the display to write the same pattern again. Ignored otherwise. */
    fun retry() {
        if (mutableState.value is TestWriteState.Failed) mutableState.value = nextState()
    }

    /**
     * Ends with the records so far, including a write whose questions weren't
     * answered; from [TestWriteState.Offered] it ends with none. Ignored while
     * writing: the write can't be stopped safely, and it ends within
     * [TestWriteFamily.deadlineMs], moving on as usual.
     */
    fun cancel() {
        if (mutableState.value is TestWriteState.Writing) return
        val pattern = (mutableState.value as? TestWriteState.Asking)?.pattern
        val outcome = unanswered
        if (pattern != null && outcome != null) recorded += TestWriteRecord(pattern.title, outcome, emptyList())
        unanswered = null
        mutableState.value = TestWriteState.Done(records)
    }

    /**
     * Writes the waiting pattern to [tag] if it's the probed display, taking
     * ownership of its transport; any other tag, or one that arrives while
     * not waiting, is closed untouched. Returns once the write ends.
     */
    suspend fun onTag(tag: FoundTag) {
        val waiting = mutableState.value as? TestWriteState.Waiting
        // Only the probed display; its UID is compared here and never logged.
        val open = (tag as? FoundTag.Unsupported)
            ?.takeIf { it.kind == TagKind.ISO_DEP && it.uid.contentEquals(uid) }
            ?.openTransport
        if (waiting == null || open == null) {
            (tag as? FoundTag.Writable)?.transport?.close()
            if (waiting != null) mutableState.value = waiting.copy(message = SAME_DISPLAY_MESSAGE)
            return
        }
        val pattern = waiting.pattern
        mutableState.value = TestWriteState.Writing(pattern, 0)
        val transport = try {
            open()
        } catch (e: CancellationException) {
            mutableState.value = waiting.copy(message = null)
            throw e
        } catch (e: Exception) {
            // Nothing was sent, so there's no write to record.
            mutableState.value = waiting.copy(message = TEST_WRITE_LOST_MESSAGE)
            return
        }
        val (outcome, message) = try {
            write(transport, pattern)
        } catch (e: CancellationException) {
            mutableState.value = waiting.copy(message = null)
            throw e
        } finally {
            transport.close()
        }
        val failure = outcome.failure
        mutableState.value = when {
            failure != null -> {
                val record = TestWriteRecord(pattern.title, outcome, emptyList())
                recorded += record
                TestWriteState.Failed(record, message ?: failure.userMessage)
            }
            pattern.questions.isEmpty() -> {
                recorded += TestWriteRecord(pattern.title, outcome, emptyList())
                index++
                nextState()
            }
            else -> {
                unanswered = outcome
                TestWriteState.Asking(pattern, pattern.questions)
            }
        }
    }

    /**
     * The family's write of [pattern] over [transport], within its
     * [TestWriteFamily.deadlineMs]. A write that runs past it, or throws, becomes a
     * failed outcome, paired with the message to show instead of the
     * failure's own; cancellation propagates.
     */
    private suspend fun write(transport: Transport, pattern: TestWritePattern): Pair<TestWriteOutcome, String?> {
        val log = ExchangeLog()
        // Where the write had got to, for a failure the family didn't report itself.
        fun lastStep() = log.entries.lastOrNull()?.step ?: WriteStep.INIT
        return try {
            val outcome = withTimeoutOrNull(family.deadlineMs) {
                family.write(transport, pattern, log) { mutableState.value = TestWriteState.Writing(pattern, it) }
            }
            if (outcome != null) {
                outcome to null
            } else {
                val failure = WriteResult.Failure(lastStep(), FailureKind.LOST_CONTACT, "didn't finish within ${family.deadlineMs / 1_000} s")
                TestWriteOutcome(log, null, null, failure) to WRITE_TIMED_OUT_MESSAGE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A bug in the writer, not the tag: kept, with its class and message, for the report.
            // UNEXPECTED_REPLY is the nearest kind; the message shown says what happened.
            val failure = WriteResult.Failure(lastStep(), FailureKind.UNEXPECTED_REPLY, "the writer stopped with an error", e)
            TestWriteOutcome(log, null, null, failure) to TEST_WRITE_ERROR_MESSAGE
        }
    }

    /** Waiting for the pattern at [index], or done after the last. */
    private fun nextState(): TestWriteState =
        if (index < patterns.size) TestWriteState.Waiting(patterns[index], index, patterns.size) else TestWriteState.Done(records)
}

/**
 * The test writes for a probed display; null unless its family can be
 * test-written and the probe captured a UID of at least [MIN_HIDDEN_UID_BYTES]
 * bytes, since a shorter one could match other tags.
 */
fun testWriteControllerFor(result: ProbeResult): TestWriteController? {
    val family = testWriteFamilyFor(result) ?: return null
    val uid = result.tag?.uid?.takeIf { it.size >= MIN_HIDDEN_UID_BYTES } ?: return null
    return TestWriteController(family, uid)
}
