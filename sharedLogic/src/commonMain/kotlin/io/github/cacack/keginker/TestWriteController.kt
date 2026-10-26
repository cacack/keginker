package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.ezsign.TestAnswer
import io.github.cacack.keginker.ezsign.TestQuestion
import io.github.cacack.keginker.ezsign.TestWriteFamily
import io.github.cacack.keginker.ezsign.TestWriteOutcome
import io.github.cacack.keginker.ezsign.TestWritePattern
import io.github.cacack.keginker.ezsign.TestWriteRecord
import io.github.cacack.keginker.ezsign.testWriteFamilyFor
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.waveshare.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shown when the tag presented for a test write isn't the display that was probed. */
const val SAME_DISPLAY_MESSAGE = "That isn't the display you reported. Hold the same display to the phone."

/** Shown when contact with the display was lost before a test write could start. */
const val TEST_WRITE_LOST_MESSAGE = "Lost contact with the display. Hold it still against the phone again."

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

    /** Every pattern written, or the user stopped; [records] holds each write so far, oldest first. */
    data class Done(val records: List<TestWriteRecord>) : TestWriteState

    /** The write of [record]'s pattern failed, showing [message]; retry or cancel. */
    data class Failed(val record: TestWriteRecord, val message: String) : TestWriteState
}

/**
 * Report a display's guided test writes to the display a probe found:
 * [optIn], then for each of the [family]'s patterns, the next tag is written
 * if its UID is [probedUid], and the user answers what it showed. Nothing is
 * written before [optIn]. Both apps use it, like [WriteController].
 */
class TestWriteController(private val family: TestWriteFamily, probedUid: ByteArray) {
    private val uid = probedUid.copyOf()
    private val patterns = family.patterns

    private val mutableState = MutableStateFlow<TestWriteState>(TestWriteState.Offered)
    val state: StateFlow<TestWriteState> = mutableState

    /** The family as reports name it, for the offer. */
    val familyName: String get() = family.name

    /** Whether the next found tag is for a test write. */
    val isWaiting: Boolean get() = mutableState.value is TestWriteState.Waiting

    /** Every write so far, oldest first. */
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
     * for the display to write the next pattern, or ends. Ignored unless asking.
     */
    fun answer(choices: List<String>) {
        val asking = mutableState.value as? TestWriteState.Asking ?: return
        val outcome = unanswered ?: return
        require(choices.size == asking.questions.size) { "${choices.size} answers to ${asking.questions.size} questions" }
        asking.questions.zip(choices).forEach { (question, choice) ->
            require(choice in question.options) { "\"$choice\" isn't an option for ${question.id}" }
        }
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
     * answered. Ignored while a write is running; it ends within the write budget.
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
        try {
            val transport = open()
            val outcome = try {
                family.write(transport, pattern, ExchangeLog()) { mutableState.value = TestWriteState.Writing(pattern, it) }
            } finally {
                transport.close()
            }
            val failure = outcome.failure
            mutableState.value = when {
                failure != null -> {
                    val record = TestWriteRecord(pattern.title, outcome, emptyList())
                    recorded += record
                    TestWriteState.Failed(record, failure.userMessage)
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
        } catch (e: CancellationException) {
            mutableState.value = waiting.copy(message = null)
            throw e
        } catch (e: Exception) {
            // Opening the tag failed; the family's write reports its own failures.
            mutableState.value = waiting.copy(message = TEST_WRITE_LOST_MESSAGE)
        }
    }

    /** Waiting for the pattern at [index], or done after the last. */
    private fun nextState(): TestWriteState =
        if (index < patterns.size) TestWriteState.Waiting(patterns[index], index, patterns.size) else TestWriteState.Done(records)
}

/** The test writes for a probed display; null unless its family can be test-written and the probe captured its UID. */
fun testWriteControllerFor(result: ProbeResult): TestWriteController? {
    val family = testWriteFamilyFor(result) ?: return null
    val uid = result.tag?.uid ?: return null
    return TestWriteController(family, uid)
}
