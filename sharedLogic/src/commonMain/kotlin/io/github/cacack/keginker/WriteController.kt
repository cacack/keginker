package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.diagnostics.Outcome
import io.github.cacack.keginker.diagnostics.WriteReport
import io.github.cacack.keginker.diagnostics.writeReport
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.DONE_MESSAGE
import io.github.cacack.keginker.waveshare.UNSUPPORTED_TAG_MESSAGE
import io.github.cacack.keginker.waveshare.WRITE_DEADLINE_MS
import io.github.cacack.keginker.waveshare.WRITE_TIMED_OUT_MESSAGE
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

sealed interface WriteState {
    /** Nothing to write yet. */
    data object Idle : WriteState

    /** An image is ready; waiting for the user to hold a tag to the phone. */
    data object Waiting : WriteState

    data class Writing(val percent: Int) : WriteState

    /** The tag confirmed the refresh; [report] holds what happened, for a diagnostics report. */
    data class Done(val report: WriteReport) : WriteState

    /** The write failed; [report] holds what happened, for a diagnostics report. */
    data class Failed(val message: String, val report: WriteReport? = null) : WriteState
}

/**
 * Runs one write at a time: [arm] with a view, then each found tag is
 * written while armed. A failure leaves the view in place so [arm] retries.
 * A write still running after [WRITE_DEADLINE_MS] is stopped and fails.
 * Both apps use it: Android's write screen, and iOS's `TagWriter` around its
 * Core NFC session.
 */
class WriteController(
    private val panel: WavesharePanel,
    private val imageWriter: ImageWriter = ImageWriter(),
) {
    private val mutableState = MutableStateFlow<WriteState>(WriteState.Idle)
    val state: StateFlow<WriteState> = mutableState

    private var view: BooleanArray? = null
    private var testPattern = false

    /** The running write's tag and exchanges, for [fail]'s report. */
    private var running: Pair<FoundTag, ExchangeLog>? = null

    /**
     * Readies [view] (from `renderView`) for the next tag; it's packed for
     * whichever kind of tag turns up. [testPattern] says, for reports, whether
     * it shows the built-in test pattern. Ignored while a write is running.
     */
    fun arm(view: BooleanArray, testPattern: Boolean = false) {
        require(view.size == panel.viewWidth * panel.viewHeight) {
            "View is ${view.size} pixels; ${panel.name} needs ${panel.viewWidth}×${panel.viewHeight}"
        }
        if (mutableState.value is WriteState.Writing) return
        this.view = view
        this.testPattern = testPattern
        mutableState.value = WriteState.Waiting
    }

    /** Returns to [WriteState.Idle]. Ignored while a write is running; it can't be stopped safely. */
    fun cancel() {
        if (mutableState.value !is WriteState.Writing) mutableState.value = WriteState.Idle
    }

    /**
     * Fails the waiting or running write with [message], for a step outside
     * the controller that ended it: on iOS, connecting to the tag or the NFC
     * session ending. A running write's report holds its exchanges so far,
     * and its own result is dropped when it ends; a waiting write reports on
     * [tag], if there is one. [reason] goes in the report. Ignored otherwise.
     */
    fun fail(message: String, reason: String?, tag: FoundTag?) {
        mutableState.value = when (mutableState.value) {
            is WriteState.Writing -> {
                val (runningTag, log) = running ?: return
                WriteState.Failed(message, report(runningTag, message, Outcome.FAILED, log, reason = reason))
            }
            WriteState.Waiting -> WriteState.Failed(message, tag?.let { report(it, message, Outcome.FAILED, log = null, reason = reason) })
            else -> return
        }
    }

    /**
     * Handles a found tag, taking ownership of its transport; ignored unless
     * armed. Returns once any write ends.
     */
    suspend fun onTag(tag: FoundTag) {
        val view = view
        if (mutableState.value != WriteState.Waiting || view == null) {
            (tag as? FoundTag.Writable)?.transport?.close()
            return
        }
        when (tag) {
            is FoundTag.Unsupported ->
                mutableState.value = WriteState.Failed(
                    UNSUPPORTED_TAG_MESSAGE,
                    report(tag, UNSUPPORTED_TAG_MESSAGE, Outcome.NOT_ATTEMPTED, log = null),
                )
            is FoundTag.Writable -> {
                val log = ExchangeLog()
                running = tag to log
                mutableState.value = WriteState.Writing(0)
                val ended = try {
                    val result = withTimeoutOrNull(WRITE_DEADLINE_MS) {
                        imageWriter.write(tag, panel, view, log) { showUnlessFailed(WriteState.Writing(it)) }
                    }
                    when (result) {
                        WriteResult.Success -> WriteState.Done(report(tag, DONE_MESSAGE, Outcome.SUCCEEDED, log))
                        is WriteResult.Failure -> failed(
                            tag, result.userMessage, log, result.step, result.reason, result.cause,
                        )
                        null -> failed(
                            tag, WRITE_TIMED_OUT_MESSAGE, log, log.entries.lastOrNull()?.step,
                            "No result within $WRITE_DEADLINE_MS ms",
                        )
                    }
                } catch (e: CancellationException) {
                    showUnlessFailed(
                        failed(tag, "The write was interrupted. Hold the tag to the phone and try again.", log, cause = e),
                    )
                    throw e
                } catch (e: Exception) {
                    failed(tag, "The write failed unexpectedly: ${e.message}", log, cause = e)
                } finally {
                    if (running?.second === log) running = null
                    tag.transport.close()
                }
                showUnlessFailed(ended)
            }
        }
    }

    /** Shows [state] for the running write, unless [fail] has already ended it. */
    private fun showUnlessFailed(state: WriteState) {
        if (mutableState.value is WriteState.Writing) mutableState.value = state
    }

    /** A [WriteState.Failed] showing [message], with a report of what happened. */
    private fun failed(
        tag: FoundTag,
        message: String,
        log: ExchangeLog,
        step: WriteStep? = null,
        reason: String? = null,
        cause: Throwable? = null,
    ) = WriteState.Failed(message, report(tag, message, Outcome.FAILED, log, step, reason, cause))

    private fun report(
        tag: FoundTag,
        message: String,
        outcome: Outcome,
        log: ExchangeLog?,
        step: WriteStep? = null,
        reason: String? = null,
        cause: Throwable? = null,
    ) = writeReport(tag, panel, message, outcome, testPattern, log, step, reason, cause)
}

