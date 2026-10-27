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
import io.github.cacack.keginker.waveshare.WRITE_DEADLINE_MS
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.exchangeImageOk
import io.github.cacack.keginker.waveshare.exchangeOk

/**
 * How long the tag may hold its reply to the blocking refresh. niw found 4-color
 * refreshes take over 20 s; this leaves room for a slow panel. Core NFC has no
 * per-command timeout, so it applies only on Android.
 */
internal const val BLOCKING_REFRESH_TIMEOUT_MS = 45_000L

/** The real time an EZ Sign write, test or label, may take: a label write's deadline plus the blocking refresh. */
internal const val EZ_SIGN_WRITE_DEADLINE_MS = WRITE_DEADLINE_MS + BLOCKING_REFRESH_TIMEOUT_MS

/**
 * Writes one frame to an EZ Sign tag (ISO-DEP), as test and label writes
 * both do: SELECT the NDEF application, authenticate, send [streams] (one
 * LZO1X stream per block, in order) in `F0 D3` fragments, then refresh with
 * the blocking `F0 D4 85 00`. Returns how long the tag held its reply to the
 * refresh, recording every exchange in [log]. A step that fails throws, for
 * `runSteps`; cancellation propagates. [onProgress] receives 0–100 and never
 * decreases.
 *
 * The tag answers the blocking refresh only once the panel has finished, so
 * the NFC field stays up for the whole refresh, and the `90 00` is the sign
 * it's done. A 4-color refresh takes over 20 s, longer than Core NFC keeps
 * a connection open; a command the tag is still answering holds the
 * connection open, as niw/EPaperNFCSwift found and a 2.9" panel confirmed
 * (about 24 s). Polling after the non-blocking `F0 D4 85 80` left the
 * session to close mid-refresh and the panel half drawn, and the `F0 DE`
 * poll still answered busy after a blocking refresh that had drawn the
 * panel, so the writer doesn't poll (#138). The refresh gets its own
 * [BLOCKING_REFRESH_TIMEOUT_MS] on Android; every other command keeps the
 * usual one.
 */
internal suspend fun Transport.sendEzSignFrame(streams: List<ByteArray>, log: ExchangeLog, onProgress: (Int) -> Unit): Long {
    val blocks = streams.map { fragments(it) }
    val total = blocks.sumOf { it.size }
    exchangeOk(WriteStep.INIT, selectAid(NDEF_APPLICATION_AID), ::isEzSignOk, log = log)
    exchangeOk(WriteStep.AUTHENTICATE, AUTHENTICATE, ::isEzSignOk, log = log)
    var sent = 0
    for ((blockNo, blockFragments) in blocks.withIndex()) {
        for ((fragNo, fragment) in blockFragments.withIndex()) {
            val last = if (fragNo == blockFragments.lastIndex) 0x01 else 0x00
            exchangeImageOk(
                WriteStep.SEND_DATA,
                bytes(0xF0, 0xD3, 0x00, last, 2 + fragment.size, blockNo, fragNo),
                fragment,
                ::isEzSignOk,
                "block ${blockNo + 1}/${blocks.size} fragment ${fragNo + 1}/${blockFragments.size}",
                log,
            )
            sent++
            onProgress(sent * IMAGE_SENT_PROGRESS / total)
        }
    }
    onProgress(IMAGE_SENT_PROGRESS)
    val refreshStart = log.elapsedMs()
    exchangeOk(WriteStep.REFRESH, REFRESH, ::isEzSignOk, log = log, timeoutMs = BLOCKING_REFRESH_TIMEOUT_MS)
    val refreshMs = log.elapsedMs() - refreshStart
    onProgress(100)
    return refreshMs
}

/** Whether [reply] ends in the `90 00` success status. */
internal fun isEzSignOk(reply: ByteArray) =
    reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** The fixed key alt-core authenticates with. */
private val AUTHENTICATE = bytes(0x00, 0x20, 0x00, 0x01, 0x04, 0x20, 0x09, 0x12, 0x10)

/** P2 `00`: answered only when the refresh is done. P2 `80` answers at once. */
private val REFRESH = bytes(0xF0, 0xD4, 0x85, 0x00, 0x00)
