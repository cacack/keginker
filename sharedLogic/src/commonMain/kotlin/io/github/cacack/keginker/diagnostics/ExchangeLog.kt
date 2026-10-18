package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.waveshare.WriteStep
import kotlin.time.TimeSource

/**
 * One command sent to a tag during a write and what came back.
 *
 * [command] never holds image data: for an image chunk it is only the
 * protocol header, and [imageBytes] says how many image bytes followed it.
 * Exactly one of [reply] and [error] is set.
 */
class Exchange internal constructor(
    /** Milliseconds from the start of the log to when the command was sent. */
    val atMs: Long,
    val step: WriteStep,
    val command: ByteArray,
    val imageBytes: Int,
    val reply: ByteArray?,
    /** The exception's class name and message, if the exchange failed. */
    val error: String?,
)

/**
 * Records every exchange of one write, for a diagnostics report. Image data
 * is dropped as each exchange is recorded, so the log never holds it.
 */
class ExchangeLog(timeSource: TimeSource) {
    /** For Swift, and the usual case: real elapsed time. */
    constructor() : this(TimeSource.Monotonic)

    private val start = timeSource.markNow()
    private val recorded = mutableListOf<Exchange>()

    /** The exchanges so far, oldest first. */
    val entries: List<Exchange> get() = recorded.toList()

    internal fun elapsedMs(): Long = start.elapsedNow().inWholeMilliseconds

    /**
     * Records [command], keeping only its header when its last [imageBytes] are image data.
     * Never throws: a wrong count is clamped, dropping too much rather than failing the write.
     */
    internal fun record(atMs: Long, step: WriteStep, command: ByteArray, imageBytes: Int, reply: ByteArray?, error: Throwable?) {
        val dropped = imageBytes.coerceIn(0, command.size)
        recorded += Exchange(
            atMs = atMs,
            step = step,
            command = command.copyOfRange(0, command.size - dropped),
            imageBytes = dropped,
            reply = reply?.copyOf(),
            error = error?.let(::describe),
        )
    }
}

/** An exception as a report shows it: class name and message. */
internal fun describe(error: Throwable): String =
    listOfNotNull(error::class.simpleName ?: "Exception", error.message).joinToString(": ")
