package io.github.cacack.keginker.nfc

import io.github.cacack.keginker.LabelDisplay
import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.waveshare.WaveshareIsoDepWriter
import io.github.cacack.keginker.waveshare.WaveshareWriter
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.packControllerFrame
import io.github.cacack.keginker.waveshare.packFrame
import kotlinx.coroutines.delay

/**
 * Writes an image to whichever kind of Waveshare tag was found, packing it in
 * the layout that tag's protocol needs. Platform UIs only deal in views (from
 * `renderView`); they don't need to know which board revision they're talking
 * to. EZ Sign tags are found unclassified; [io.github.cacack.keginker.WriteController]
 * identifies and writes them.
 */
class ImageWriter(private val pause: suspend (Long) -> Unit) {
    /** For Swift, which can't supply the suspending [pause]. */
    constructor() : this({ delay(it) })

    /**
     * Writes [view] (row-major, [LabelDisplay.viewWidth] wide, `true` is
     * white), composed for the Waveshare [display], to [tag] with the
     * display's panel. Doesn't close the tag's transport.
     *
     * Problems with the tag (lost contact, unexpected replies, an unconfirmed
     * refresh) are returned as [WriteResult.Failure]. Only programming errors,
     * such as a view of the wrong size or an EZ Sign [display], and
     * cancellation are thrown.
     */
    @Throws(Exception::class)
    suspend fun write(
        tag: FoundTag.Writable,
        display: LabelDisplay,
        view: BooleanArray,
        onProgress: (Int) -> Unit,
    ): WriteResult = write(tag, display, view, null, onProgress)

    /** Like the other [write], and records every exchange with the tag in [log]. */
    @Throws(Exception::class)
    suspend fun write(
        tag: FoundTag.Writable,
        display: LabelDisplay,
        view: BooleanArray,
        log: ExchangeLog?,
        onProgress: (Int) -> Unit,
    ): WriteResult {
        val panel = requireNotNull(display.wavesharePanel) { "${display.name} isn't a Waveshare display" }
        return when (tag) {
            is FoundTag.Waveshare ->
                WaveshareWriter(tag.transport, pause, log).write(panel, packFrame(panel, view), onProgress)
            is FoundTag.WaveshareIsoDep ->
                WaveshareIsoDepWriter(tag.transport, pause, log).write(panel, packControllerFrame(panel, view), onProgress)
        }
    }
}
