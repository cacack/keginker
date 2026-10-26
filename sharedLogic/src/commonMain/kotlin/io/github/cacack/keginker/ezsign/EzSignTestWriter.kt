/*
 * The command sequence follows alt-core/nfc-eink's src/nfc_eink/protocol.py
 * and card.py (authenticate, send_image, refresh;
 * https://github.com/alt-core/nfc-eink, commit 682519f), licensed under the
 * MIT License.
 *
 * Modified: ported from Python to Kotlin; the NDEF application is selected
 * first; the busy poll stops at the write budget, in real time, instead of
 * 30 s, and is reported as a finding rather than raised; failures
 * are reported as a typed result naming the failing step.
 */
package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.NDEF_APPLICATION_AID
import io.github.cacack.keginker.probe.selectAid
import io.github.cacack.keginker.waveshare.COMMAND_ALLOWANCE_MS
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.REPLY_SHOWN_BYTES
import io.github.cacack.keginker.waveshare.IMAGE_SENT_PROGRESS
import io.github.cacack.keginker.waveshare.StepFailed
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WRITE_BUDGET_MS
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.exchange
import io.github.cacack.keginker.waveshare.exchangeImageOk
import io.github.cacack.keginker.waveshare.exchangeOk
import io.github.cacack.keginker.waveshare.runSteps
import io.github.cacack.keginker.waveshare.toShownHex
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/** How a test write went. */
class TestWriteOutcome internal constructor(
    /** Every exchange, image bytes counted but never kept. */
    val log: ExchangeLog,
    /** From the refresh command to the poll that found the panel no longer busy; null if that never came. */
    val refreshMs: Long?,
    /**
     * From the refresh command to the last poll, when the panel was still
     * busy at the last poll that fits in the write budget. A finding, not a
     * failure: the image may still appear.
     */
    val busyAtMs: Long?,
    /** The step that failed, if one did; then [refreshMs] and [busyAtMs] are null. */
    val failure: WriteResult.Failure?,
)

/**
 * Writes test patterns to an EZ Sign tag (ISO-DEP) over [transport]: SELECT
 * the NDEF application, authenticate, send the frame as LZO1X blocks in
 * `F0 D3` fragments, refresh with `F0 D4`, then poll `F0 DE` every
 * [POLL_PAUSE_MS] until the panel isn't busy or another poll wouldn't fit in
 * [WRITE_BUDGET_MS] of real time, measured on [timeSource] from the first command.
 *
 * Label writers stop polling after a count worked out from
 * [COMMAND_ALLOWANCE_MS] per command, so their worst case is known before
 * they start. A test write is diagnostic, and its job is to measure the
 * refresh within the iOS session; real tags answer in well under the
 * allowance, so a count would give up seconds early, before a slow refresh
 * finishes. It watches the clock instead, keeping only one poll's allowance
 * in hand.
 */
internal class EzSignTestWriter(
    private val transport: Transport,
    private val info: EzSignDeviceInfo,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * Writes [pattern] and times the refresh, recording every exchange in [log].
     * [onProgress] receives 0–100 and never decreases. Cancellation propagates.
     */
    suspend fun write(pattern: TestWritePattern, log: ExchangeLog, onProgress: (Int) -> Unit = {}): TestWriteOutcome {
        require(pattern.width == info.nativeWidth && pattern.height == info.nativeHeight) {
            "Pattern is ${pattern.width} × ${pattern.height}; the panel is ${info.nativeWidth} × ${info.nativeHeight}"
        }
        val blocks = frameBlocks(packFrame(pattern.pixels, info)).map { fragments(lzoLiteralStream(it)) }
        val total = blocks.sumOf { it.size }
        var refreshMs: Long? = null
        var busyAtMs: Long? = null
        val start = timeSource.markNow()
        fun sinceStartMs() = start.elapsedNow().inWholeMilliseconds
        val result = runSteps {
            sendOk(WriteStep.INIT, selectAid(NDEF_APPLICATION_AID), log)
            sendOk(WriteStep.AUTHENTICATE, AUTHENTICATE, log)
            var sent = 0
            for ((blockNo, blockFragments) in blocks.withIndex()) {
                for ((fragNo, fragment) in blockFragments.withIndex()) {
                    val last = if (fragNo == blockFragments.lastIndex) 0x01 else 0x00
                    transport.exchangeImageOk(
                        WriteStep.SEND_DATA,
                        bytes(0xF0, 0xD3, 0x00, last, 2 + fragment.size, blockNo, fragNo),
                        fragment,
                        ::isOk,
                        "block ${blockNo + 1}/${blocks.size} fragment ${fragNo + 1}/${blockFragments.size}",
                        log,
                    )
                    sent++
                    onProgress(sent * IMAGE_SENT_PROGRESS / total)
                }
            }
            val refreshStart = log.elapsedMs()
            sendOk(WriteStep.REFRESH, REFRESH, log)
            // A tag slow enough to use up the budget before now still gets one poll.
            val refreshedAtMs = sinceStartMs()
            val waitMs = (WRITE_BUDGET_MS - refreshedAtMs).coerceAtLeast(1)
            while (true) {
                val reply = transport.exchange(WriteStep.WAIT_FOR_REFRESH, POLL, log = log)
                if (!isOk(reply) || reply.size != 3) {
                    throw StepFailed(WriteStep.WAIT_FOR_REFRESH, FailureKind.UNEXPECTED_REPLY, "Unexpected reply ${reply.toShownHex(REPLY_SHOWN_BYTES)}")
                }
                if (reply[0] == NOT_BUSY) {
                    refreshMs = log.elapsedMs() - refreshStart
                    break
                }
                val nowMs = sinceStartMs()
                val waited = ((nowMs - refreshedAtMs) * (99 - IMAGE_SENT_PROGRESS) / waitMs).toInt()
                onProgress(IMAGE_SENT_PROGRESS + waited.coerceIn(0, 99 - IMAGE_SENT_PROGRESS))
                if (nowMs + POLL_PAUSE_MS + COMMAND_ALLOWANCE_MS > WRITE_BUDGET_MS) {
                    busyAtMs = log.elapsedMs() - refreshStart
                    break
                }
                pause(POLL_PAUSE_MS)
            }
            onProgress(100)
        }
        val failure = result as? WriteResult.Failure
        return if (failure != null) {
            TestWriteOutcome(log, refreshMs = null, busyAtMs = null, failure = failure)
        } else {
            TestWriteOutcome(log, refreshMs, busyAtMs, failure = null)
        }
    }

    private suspend fun sendOk(step: WriteStep, command: ByteArray, log: ExchangeLog) =
        transport.exchangeOk(step, command, ::isOk, log = log)

    private companion object {
        const val POLL_PAUSE_MS = 500L

        const val NOT_BUSY: Byte = 0x00

        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        /** The fixed key alt-core authenticates with. */
        val AUTHENTICATE = bytes(0x00, 0x20, 0x00, 0x01, 0x04, 0x20, 0x09, 0x12, 0x10)
        val REFRESH = bytes(0xF0, 0xD4, 0x85, 0x80, 0x00)

        /** Answered `00 90 00` when the refresh is done and `01 90 00` while busy. */
        val POLL = bytes(0xF0, 0xDE, 0x00, 0x00, 0x01)

        fun isOk(reply: ByteArray) =
            reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()
    }
}
