/*
 * The command sequence follows alt-core/nfc-eink's src/nfc_eink/protocol.py
 * and card.py (authenticate, send_image, refresh;
 * https://github.com/alt-core/nfc-eink, commit 682519f), licensed under the
 * MIT License.
 *
 * Modified: ported from Python to Kotlin; the NDEF application is selected
 * first; the refresh is the blocking form niw/EPaperNFCSwift sends
 * (Sources/EPaperNFCSwift/SendImageOperation.swift, commit ee0440e, MIT
 * License), whose reply ends the write, instead of alt-core's busy poll; the
 * refresh is timed for the report; failures are reported as a typed result
 * naming the failing step.
 */
package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.NDEF_APPLICATION_AID
import io.github.cacack.keginker.probe.selectAid
import io.github.cacack.keginker.waveshare.IMAGE_SENT_PROGRESS
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.exchangeImageOk
import io.github.cacack.keginker.waveshare.exchangeOk
import io.github.cacack.keginker.waveshare.runSteps

/** How a test write went. */
class TestWriteOutcome internal constructor(
    /** Every exchange, image bytes counted but never kept. */
    val log: ExchangeLog,
    /** How long the tag held its reply to the blocking refresh: the refresh time. Null if the write failed. */
    val refreshMs: Long?,
    /** The step that failed, if one did; then [refreshMs] is null. */
    val failure: WriteResult.Failure?,
)

/**
 * Writes test patterns to an EZ Sign tag (ISO-DEP) over [transport]: SELECT
 * the NDEF application, authenticate, send the frame as LZO1X blocks in
 * `F0 D3` fragments, then refresh with the blocking `F0 D4 85 00` and time
 * how long the tag holds its reply.
 *
 * The tag answers the blocking refresh only once the panel has finished, so
 * the NFC field stays up for the whole refresh, and the `90 00` is the sign
 * it's done. A 4-colour refresh takes over 20 s, longer than Core NFC keeps
 * a connection open; a command the tag is still answering holds the
 * connection open, as niw/EPaperNFCSwift found and a 2.9" panel confirmed
 * (about 24 s). Polling after the non-blocking `F0 D4 85 80` left the
 * session to close mid-refresh and the panel half drawn, and the `F0 DE`
 * poll still answered busy after a blocking refresh that had drawn the
 * panel, so the writer doesn't poll (#138). The refresh gets its own
 * [BLOCKING_REFRESH_TIMEOUT_MS] on Android; every other command keeps the
 * usual one.
 */
/**
 * How long the tag may hold its reply to the blocking refresh. niw found 4-colour
 * refreshes take over 20 s; this leaves room for a slow panel. Core NFC has no
 * per-command timeout, so it applies only on Android.
 */
internal const val BLOCKING_REFRESH_TIMEOUT_MS = 45_000L

internal class EzSignTestWriter(
    private val transport: Transport,
    private val info: EzSignDeviceInfo,
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
            onProgress(IMAGE_SENT_PROGRESS)
            val refreshStart = log.elapsedMs()
            transport.exchangeOk(WriteStep.REFRESH, REFRESH, ::isOk, log = log, timeoutMs = BLOCKING_REFRESH_TIMEOUT_MS)
            refreshMs = log.elapsedMs() - refreshStart
            onProgress(100)
        }
        val failure = result as? WriteResult.Failure
        return if (failure != null) {
            TestWriteOutcome(log, refreshMs = null, failure = failure)
        } else {
            TestWriteOutcome(log, refreshMs, failure = null)
        }
    }

    private suspend fun sendOk(step: WriteStep, command: ByteArray, log: ExchangeLog) =
        transport.exchangeOk(step, command, ::isOk, log = log)

    private companion object {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        /** The fixed key alt-core authenticates with. */
        val AUTHENTICATE = bytes(0x00, 0x20, 0x00, 0x01, 0x04, 0x20, 0x09, 0x12, 0x10)
        /** P2 `00`: answered only when the refresh is done. P2 `80` answers at once. */
        val REFRESH = bytes(0xF0, 0xD4, 0x85, 0x00, 0x00)

        fun isOk(reply: ByteArray) =
            reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()
    }
}
