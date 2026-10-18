package io.github.cacack.tagpaper.waveshare

import io.github.cacack.tagpaper.nfc.Transport
import kotlin.coroutines.cancellation.CancellationException

// Plumbing shared by the Waveshare writers.

/** How long each command may take. */
internal const val TRANSCEIVE_TIMEOUT_MS = 3_000L

/** Busy polls before a refresh counts as unconfirmed. */
internal const val MAX_BUSY_POLLS = 240

/** A write step failed; [runSteps] turns it into a [WriteResult.Failure]. */
internal class StepFailed(
    val step: WriteStep,
    val reason: String,
    cause: Throwable? = null,
) : Exception(reason, cause)

/** Runs a write's steps, returning success, or the failure of the first step that fails. */
internal inline fun runSteps(steps: () -> Unit): WriteResult =
    try {
        steps()
        WriteResult.Success
    } catch (failure: StepFailed) {
        WriteResult.Failure(failure.step, failure.reason, failure.cause)
    }

/** Sends [command] as part of [step]; a communication error fails the step. */
internal suspend fun Transport.exchange(step: WriteStep, command: ByteArray, detail: String? = null): ByteArray =
    try {
        transceive(command, TRANSCEIVE_TIMEOUT_MS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw StepFailed(step, listOfNotNull("Tag communication failed", detail).joinToString(" at "), e)
    }

/** Like [exchange], and the step also fails unless [isOk] accepts the reply. */
internal suspend fun Transport.exchangeOk(
    step: WriteStep,
    command: ByteArray,
    isOk: (ByteArray) -> Boolean,
    detail: String? = null,
) {
    val reply = exchange(step, command, detail)
    if (!isOk(reply)) {
        throw StepFailed(step, listOfNotNull("Unexpected reply ${reply.toHex()}", detail).joinToString(" at "))
    }
}

internal fun ByteArray.toHex(): String =
    joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
