package io.github.cacack.keginker

import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.image.GrayImage
import io.github.cacack.keginker.image.testPattern
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.waveshare.WavesharePanel
import io.github.cacack.keginker.waveshare.renderView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The write screen's state: the chosen image, how it's rendered, and the
 * [controller] that writes it. Keeps one promise: when a write says Done,
 * the tag got exactly the image in [view].
 *
 * So the preview never changes while a write runs: an image that finishes
 * loading then is held until the write ends, and rendering changes are ignored.
 */
class WriteSession(
    val panel: WavesharePanel,
    imageWriter: ImageWriter = ImageWriter(),
) {
    val controller = WriteController(panel, imageWriter)

    /** The chosen image. */
    private var source = testPattern(panel.viewWidth, panel.viewHeight)

    /** Whether [source] is still the built-in test pattern. */
    private var sourceIsTestPattern = true

    /** An image that finished loading during a write, shown once it ends. */
    private var pendingSource: GrayImage? = null

    private val mutableDither = MutableStateFlow<Dither>(Dither.FloydSteinberg)
    val dither: StateFlow<Dither> = mutableDither

    /** The image exactly as the panel will show it; `true` is white. */
    private val mutableView = MutableStateFlow(renderView(panel, source, mutableDither.value))
    val view: StateFlow<BooleanArray> = mutableView

    /** Threshold level used whenever dithering is off; kept while dithering is on. */
    private var thresholdLevel = Dither.Threshold.DEFAULT_LEVEL

    private val writing get() = controller.state.value is WriteState.Writing

    /**
     * Handles a found tag; returns once any write ends, showing an image that
     * loaded meanwhile. That clears Done (it was about the old image) but
     * keeps Failed, so the user still learns the tag may be half-written;
     * Retry writes the new preview.
     */
    suspend fun onTag(tag: FoundTag) {
        try {
            controller.onTag(tag)
        } finally {
            pendingSource?.let { if (!writing) show(it, keepFailure = true) }
        }
    }

    fun write() = controller.arm(view.value, sourceIsTestPattern)

    /** Switches between dithering and a threshold. Ignored while a write is running. */
    fun toggleDither() {
        if (writing) return
        mutableDither.value =
            if (mutableDither.value == Dither.FloydSteinberg) Dither.Threshold(thresholdLevel) else Dither.FloydSteinberg
        rerender()
    }

    /** Sets where grey becomes black (0–255) when dithering is off. Ignored while a write is running. */
    fun setThreshold(level: Int) {
        if (writing) return
        thresholdLevel = level.coerceIn(0, 255)
        if (mutableDither.value is Dither.Threshold) {
            mutableDither.value = Dither.Threshold(thresholdLevel)
            rerender()
        }
    }

    /** Shows a newly loaded [image], or holds it until a running write ends. */
    fun imageLoaded(image: GrayImage) {
        if (writing) {
            pendingSource = image
            return
        }
        show(image)
    }

    private fun show(image: GrayImage, keepFailure: Boolean = false) {
        pendingSource = null
        source = image
        sourceIsTestPattern = false
        rerender(keepFailure)
    }

    /**
     * Re-renders the preview, disarming any waiting write so a stale view
     * can't go out. A failure is cleared too unless [keepFailure].
     */
    private fun rerender(keepFailure: Boolean = false) {
        if (!(keepFailure && controller.state.value is WriteState.Failed)) controller.cancel()
        mutableView.value = renderView(panel, source, mutableDither.value)
    }
}
