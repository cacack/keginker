package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.diagnostics.Outcome
import io.github.cacack.keginker.diagnostics.WriteReport
import io.github.cacack.keginker.diagnostics.writeReport
import io.github.cacack.keginker.ezsign.EZ_SIGN_WRITE_DEADLINE_MS
import io.github.cacack.keginker.ezsign.EzSignIdentity
import io.github.cacack.keginker.ezsign.EzSignLabelWriter
import io.github.cacack.keginker.ezsign.identifyEzSign
import io.github.cacack.keginker.nfc.EZ_SIGN_PROTOCOL_NAME
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.nfc.protocolName
import io.github.cacack.keginker.waveshare.DONE_MESSAGE
import io.github.cacack.keginker.waveshare.EZ_SIGN_SIZE_UNSUPPORTED_MESSAGE
import io.github.cacack.keginker.waveshare.UNSUPPORTED_TAG_MESSAGE
import io.github.cacack.keginker.waveshare.WRITE_DEADLINE_MS
import io.github.cacack.keginker.waveshare.WRITE_TIMED_OUT_MESSAGE
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.chooseDisplayMessage
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

/** Shown when the found tag couldn't be opened to write it. */
private const val CONNECT_FAILED_MESSAGE = "Couldn't connect to the tag. Hold it still and try again."

/**
 * Runs one write at a time to the chosen [display]: [arm] with a view, then
 * each found tag is written while armed. A failure leaves the view in place
 * so [arm] retries. A Waveshare tag is written with [imageWriter]. An EZ Sign
 * display is found as an unsupported ISO-DEP tag, so it's opened and
 * identified by its device information before anything is written. A tag
 * that isn't the chosen display fails, saying which display to choose.
 *
 * A write still running after its deadline is stopped and fails:
 * [WRITE_DEADLINE_MS], or [EZ_SIGN_WRITE_DEADLINE_MS] to leave room for EZ
 * Sign's blocking refresh. Both apps use it: Android's write screen, and
 * iOS's `TagWriter` around its Core NFC session.
 */
class WriteController(
    private val display: LabelDisplay,
    private val imageWriter: ImageWriter = ImageWriter(),
) {
    private val mutableState = MutableStateFlow<WriteState>(WriteState.Idle)
    val state: StateFlow<WriteState> = mutableState

    private val deadlineMs = if (display.isEzSign) EZ_SIGN_WRITE_DEADLINE_MS else WRITE_DEADLINE_MS

    private var view: BooleanArray? = null
    private var testPattern = false

    /** A write in progress: its tag and exchanges, and the protocol its report names. */
    private class RunningWrite(val tag: FoundTag, val log: ExchangeLog) {
        /** The tag's classification, until an EZ Sign tag's device information decodes. */
        var protocol: String = tag.protocolName
    }

    /** The running write, for [fail]'s report. */
    private var running: RunningWrite? = null

    /**
     * Readies [view] (from `renderView`) for the next tag; it's packed for
     * whichever kind of tag turns up. [testPattern] says, for reports, whether
     * it shows the built-in test pattern. Ignored while a write is running.
     */
    fun arm(view: BooleanArray, testPattern: Boolean = false) {
        require(view.size == display.viewWidth * display.viewHeight) {
            "View is ${view.size} pixels; ${display.name} needs ${display.viewWidth}×${display.viewHeight}"
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
            is WriteState.Writing -> failed(running ?: return, message, reason = reason)
            WriteState.Waiting -> WriteState.Failed(message, tag?.let { report(it, message, Outcome.FAILED, log = null, reason = reason) })
            else -> return
        }
    }

    /**
     * Handles a found tag, taking ownership of its transport, or of the one
     * it opens for an EZ Sign display; ignored unless armed. Returns once any
     * write ends. A tag that can't be the chosen display is sent nothing.
     */
    suspend fun onTag(tag: FoundTag) {
        val view = view
        if (mutableState.value != WriteState.Waiting || view == null) {
            (tag as? FoundTag.Writable)?.transport?.close()
            return
        }
        when (tag) {
            is FoundTag.Unsupported -> {
                val open = tag.openTransport?.takeIf { display.isEzSign && tag.kind == TagKind.ISO_DEP }
                if (open == null) {
                    notAttempted(tag, UNSUPPORTED_TAG_MESSAGE)
                } else {
                    write(tag, open) { transport, write, onProgress -> writeEzSign(transport, write, view, onProgress) }
                }
            }
            is FoundTag.Writable ->
                if (display.wavesharePanel == null) {
                    tag.transport.close()
                    // There's one Waveshare display to choose; with more, the tag couldn't say which it is.
                    notAttempted(tag, chooseDisplayMessage(LabelDisplay.entries.single { it.wavesharePanel != null }))
                } else {
                    write(tag, { tag.transport }) { _, write, onProgress ->
                        ended(write, imageWriter.write(tag, display, view, write.log, onProgress))
                    }
                }
        }
    }

    /**
     * Runs [attempt] over the transport [open] returns, within the deadline,
     * then closes that transport. [attempt] reports progress through its
     * last argument and returns how the write ended.
     */
    private suspend fun write(
        tag: FoundTag,
        open: () -> Transport,
        attempt: suspend (Transport, RunningWrite, (Int) -> Unit) -> WriteState,
    ) {
        val write = RunningWrite(tag, ExchangeLog())
        val transport = try {
            open()
        } catch (e: Exception) {
            // Nothing was sent; the report says why.
            mutableState.value = failed(write, CONNECT_FAILED_MESSAGE, reason = "Couldn't open the tag", cause = e)
            return
        }
        running = write
        mutableState.value = WriteState.Writing(0)
        val ended = try {
            withTimeoutOrNull(deadlineMs) {
                attempt(transport, write) { showUnlessFailed(WriteState.Writing(it)) }
            } ?: failed(
                write, WRITE_TIMED_OUT_MESSAGE, write.log.entries.lastOrNull()?.step,
                "No result within $deadlineMs ms",
            )
        } catch (e: CancellationException) {
            showUnlessFailed(
                failed(write, "The write was interrupted. Hold the tag to the phone and try again.", cause = e),
            )
            throw e
        } catch (e: Exception) {
            failed(write, "The write failed unexpectedly: ${e.message}", cause = e)
        } finally {
            if (running === write) running = null
            transport.close()
        }
        showUnlessFailed(ended)
    }

    /**
     * Identifies the EZ Sign tag over [transport] and, if it's the chosen
     * display, writes [view] to it. Any other tag fails before an image is sent.
     */
    private suspend fun writeEzSign(
        transport: Transport,
        write: RunningWrite,
        view: BooleanArray,
        onProgress: (Int) -> Unit,
    ): WriteState {
        val identified = when (val identity = identifyEzSign(transport, write.log)) {
            is EzSignIdentity.Failed -> return ended(write, identity.failure)
            is EzSignIdentity.NotEzSign -> return failed(write, UNSUPPORTED_TAG_MESSAGE, WriteStep.INIT, identity.reason)
            is EzSignIdentity.Identified -> identity
        }
        write.protocol = EZ_SIGN_PROTOCOL_NAME
        val info = identified.info
        val found = identified.display
        if (found != display) {
            val message = if (found == null) EZ_SIGN_SIZE_UNSUPPORTED_MESSAGE else chooseDisplayMessage(found)
            val reason = "The display is ${info.nativeWidth} × ${info.nativeHeight}; " +
                "${display.name} is ${display.nativeWidth} × ${display.nativeHeight}"
            return failed(write, message, WriteStep.INIT, reason)
        }
        return ended(write, EzSignLabelWriter(transport, info).write(display, view, write.log, onProgress))
    }

    /** Shows [state] for the running write, unless [fail] has already ended it. */
    private fun showUnlessFailed(state: WriteState) {
        if (mutableState.value is WriteState.Writing) mutableState.value = state
    }

    /** How [write] ended, given its writer's [result]. */
    private fun ended(write: RunningWrite, result: WriteResult): WriteState = when (result) {
        WriteResult.Success -> WriteState.Done(report(write.tag, DONE_MESSAGE, Outcome.SUCCEEDED, write.log, protocol = write.protocol))
        is WriteResult.Failure -> failed(write, result.userMessage, result.step, result.reason, result.cause)
    }

    /** A [WriteState.Failed] for [write] showing [message], with a report of what happened. */
    private fun failed(
        write: RunningWrite,
        message: String,
        step: WriteStep? = null,
        reason: String? = null,
        cause: Throwable? = null,
    ) = WriteState.Failed(message, report(write.tag, message, Outcome.FAILED, write.log, step, reason, cause, write.protocol))

    /** Fails without sending [tag] anything, showing [message]. */
    private fun notAttempted(tag: FoundTag, message: String) {
        mutableState.value = WriteState.Failed(message, report(tag, message, Outcome.NOT_ATTEMPTED, log = null))
    }

    private fun report(
        tag: FoundTag,
        message: String,
        outcome: Outcome,
        log: ExchangeLog?,
        step: WriteStep? = null,
        reason: String? = null,
        cause: Throwable? = null,
        protocol: String = tag.protocolName,
    ) = writeReport(tag, display, message, outcome, testPattern, log, step, reason, cause, protocol)
}
