package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.waveshare.WriteResult
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
 * Writes test patterns to an EZ Sign tag over [transport] with
 * [sendEzSignFrame], each block as one LZO1X literal run: the stream the
 * hardware reports verified (#130, #131).
 */
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
        val streams = frameBlocks(packFrame(pattern.pixels, info)).map(::lzoLiteralStream)
        var refreshMs: Long? = null
        val result = runSteps { refreshMs = transport.sendEzSignFrame(streams, log, onProgress) }
        return TestWriteOutcome(log, refreshMs, result as? WriteResult.Failure)
    }
}
