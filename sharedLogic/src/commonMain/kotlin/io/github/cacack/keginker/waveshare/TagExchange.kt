package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import kotlin.coroutines.cancellation.CancellationException

// Plumbing shared by the Waveshare writers.

/** How long each command may take. */
internal const val TRANSCEIVE_TIMEOUT_MS = 3_000L

/** Core NFC ends a tag connection about this long after it starts; Android sets no limit. */
internal const val IOS_TAG_SESSION_MS = 20_000L

/** Kept back from the iOS session for finding the tag before a write and reporting after it. */
internal const val SESSION_MARGIN_MS = 5_000L

/**
 * The time a whole write must fit in on either platform, so a refresh that
 * never confirms ends in the writer's own failure, not the phone's. See
 * "Write time budget" in docs/architecture/protocols.md.
 */
internal const val WRITE_BUDGET_MS = IOS_TAG_SESSION_MS - SESSION_MARGIN_MS

/** The time each command is assumed to take when budgeting a write. */
internal const val COMMAND_ALLOWANCE_MS = 50L

/**
 * Busy polls that fit in what [WRITE_BUDGET_MS] leaves after a write's other
 * [pausesMs] and [commands]; each poll is one command and a [pollPauseMs] pause.
 */
internal fun busyPollLimit(pausesMs: Long, commands: Int, pollPauseMs: Long): Int {
    val polls = ((WRITE_BUDGET_MS - pausesMs - commands * COMMAND_ALLOWANCE_MS) / (COMMAND_ALLOWANCE_MS + pollPauseMs)).toInt()
    check(polls >= 1) { "A write of $commands commands and $pausesMs ms of pauses leaves no busy poll in the budget" }
    return polls
}

/** A write step failed; [runSteps] turns it into a [WriteResult.Failure]. */
internal class StepFailed(
    val step: WriteStep,
    val kind: FailureKind,
    val reason: String,
    cause: Throwable? = null,
) : Exception(reason, cause)

/** Runs a write's steps, returning success, or the failure of the first step that fails. */
internal inline fun runSteps(steps: () -> Unit): WriteResult =
    try {
        steps()
        WriteResult.Success
    } catch (failure: StepFailed) {
        WriteResult.Failure(failure.step, failure.kind, failure.reason, failure.cause)
    }

/**
 * Sends [command], which carries no image data, as part of [step]; a
 * communication error fails the step. Records the exchange, success or not,
 * in [log] in full. Image data goes through [exchangeImageOk] instead, so it
 * can never reach the log.
 */
internal suspend fun Transport.exchange(
    step: WriteStep,
    command: ByteArray,
    detail: String? = null,
    log: ExchangeLog? = null,
): ByteArray = send(step, command, imageBytes = 0, detail, log)

/** Like [exchange], and the step also fails unless [isOk] accepts the reply. */
internal suspend fun Transport.exchangeOk(
    step: WriteStep,
    command: ByteArray,
    isOk: (ByteArray) -> Boolean,
    detail: String? = null,
    log: ExchangeLog? = null,
) = checkReply(step, exchange(step, command, detail, log), isOk, detail)

/**
 * Sends [header] followed by [image] as one command, like [exchangeOk]. The
 * log keeps only [header] and the size of [image]: reports are shared
 * publicly with a promise that they hold no image data.
 */
internal suspend fun Transport.exchangeImageOk(
    step: WriteStep,
    header: ByteArray,
    image: ByteArray,
    isOk: (ByteArray) -> Boolean,
    detail: String? = null,
    log: ExchangeLog? = null,
) = checkReply(step, send(step, header + image, imageBytes = image.size, detail, log), isOk, detail)

/** Sends [command], recording it in [log] without its last [imageBytes]. */
private suspend fun Transport.send(
    step: WriteStep,
    command: ByteArray,
    imageBytes: Int,
    detail: String?,
    log: ExchangeLog?,
): ByteArray {
    val atMs = log?.elapsedMs() ?: 0L
    val reply = try {
        transceive(command, TRANSCEIVE_TIMEOUT_MS)
    } catch (e: Exception) {
        log?.record(atMs, step, command, imageBytes, reply = null, error = e)
        if (e is CancellationException) throw e
        throw StepFailed(step, FailureKind.LOST_CONTACT, listOfNotNull("Tag communication failed", detail).joinToString(" at "), e)
    }
    log?.record(atMs, step, command, imageBytes, reply = reply, error = null)
    return reply
}

private fun checkReply(step: WriteStep, reply: ByteArray, isOk: (ByteArray) -> Boolean, detail: String?) {
    if (!isOk(reply)) {
        throw StepFailed(step, FailureKind.UNEXPECTED_REPLY, listOfNotNull("Unexpected reply ${reply.toShownHex(REPLY_SHOWN_BYTES)}", detail).joinToString(" at "))
    }
}

internal fun ByteArray.toHex(): String =
    joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

/**
 * Most reply bytes a report shows: more than any reply the app asks for (a
 * 250-byte READ BINARY and its status word), so only an oversized one is cut.
 */
internal const val REPLY_SHOWN_BYTES = 256

/** [toHex] of at most [limit] bytes, then "… (N bytes)" if there were more, so a long reply can't swell a report. */
internal fun ByteArray.toShownHex(limit: Int): String =
    if (size > limit) "${copyOfRange(0, limit).toHex()} … ($size bytes)" else toHex()
