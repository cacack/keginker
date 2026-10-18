package io.github.cacack.tagpaper

import io.github.cacack.tagpaper.nfc.FoundTag
import io.github.cacack.tagpaper.nfc.ImageWriter
import io.github.cacack.tagpaper.waveshare.WavesharePanel
import io.github.cacack.tagpaper.waveshare.UNSUPPORTED_TAG_MESSAGE
import io.github.cacack.tagpaper.waveshare.WriteResult
import io.github.cacack.tagpaper.waveshare.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface WriteState {
    /** Nothing to write yet. */
    data object Idle : WriteState

    /** An image is ready; waiting for the user to hold a tag to the phone. */
    data object Waiting : WriteState

    data class Writing(val percent: Int) : WriteState

    /** The tag confirmed the refresh. */
    data object Done : WriteState

    data class Failed(val message: String) : WriteState
}

/**
 * Runs one write at a time: [arm] with a view, then each found tag is
 * written while armed. A failure leaves the view in place so [arm] retries.
 */
class WriteController(
    private val panel: WavesharePanel,
    private val imageWriter: ImageWriter = ImageWriter(),
) {
    private val mutableState = MutableStateFlow<WriteState>(WriteState.Idle)
    val state: StateFlow<WriteState> = mutableState

    private var view: BooleanArray? = null

    /**
     * Readies [view] (from `renderView`) for the next tag; it's packed for
     * whichever kind of tag turns up. Ignored while a write is running.
     */
    fun arm(view: BooleanArray) {
        require(view.size == panel.viewWidth * panel.viewHeight) {
            "View is ${view.size} pixels; ${panel.name} needs ${panel.viewWidth}×${panel.viewHeight}"
        }
        if (mutableState.value is WriteState.Writing) return
        this.view = view
        mutableState.value = WriteState.Waiting
    }

    /** Returns to [WriteState.Idle]. Ignored while a write is running; it can't be stopped safely. */
    fun cancel() {
        if (mutableState.value !is WriteState.Writing) mutableState.value = WriteState.Idle
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
            is FoundTag.Unsupported -> mutableState.value = WriteState.Failed(UNSUPPORTED_TAG_MESSAGE)
            is FoundTag.Writable -> {
                mutableState.value = WriteState.Writing(0)
                mutableState.value = try {
                    when (val result = imageWriter.write(tag, panel, view) { mutableState.value = WriteState.Writing(it) }) {
                        WriteResult.Success -> WriteState.Done
                        is WriteResult.Failure -> WriteState.Failed(result.userMessage)
                    }
                } catch (e: CancellationException) {
                    mutableState.value = WriteState.Failed("The write was interrupted. Hold the tag to the phone and try again.")
                    throw e
                } catch (e: Exception) {
                    WriteState.Failed("The write failed unexpectedly: ${e.message}")
                } finally {
                    tag.transport.close()
                }
            }
        }
    }
}
