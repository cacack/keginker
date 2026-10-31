package io.github.cacack.keginker

import io.github.cacack.keginker.image.ColorImage
import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.image.fill
import io.github.cacack.keginker.image.testPattern
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The write screen's state: the chosen display and image, how it's
 * rendered, and the [controller] that writes it. Keeps one promise: when a
 * write says Done, the tag got exactly the image in [view].
 *
 * So the preview never changes while a write runs: an image that finishes
 * loading then is held until the write ends, and display and rendering
 * changes are ignored.
 */
class WriteSession(
    display: LabelDisplay,
    imageWriter: ImageWriter = ImageWriter(),
) {
    val controller = WriteController(display, imageWriter)

    private val mutableDisplay = MutableStateFlow(display)

    /** The display the preview is rendered for and the next write goes to. */
    val display: StateFlow<LabelDisplay> = mutableDisplay

    /** The chosen image; null for the built-in test pattern. */
    private var source: FittedImage? = null

    /** [source], or the test pattern, cropped and scaled to the display's view. */
    private var fitted = fit(null, display, letterbox = false)

    private val sourceIsTestPattern get() = source == null

    /** An image that finished loading during a write, shown once it ends. */
    private var pendingSource: FittedImage? = null

    private val mutableDither = MutableStateFlow<Dither>(Dither.FloydSteinberg)
    val dither: StateFlow<Dither> = mutableDither

    private val mutableColor = MutableStateFlow(true)

    /** Whether labels are in color on displays that show it; otherwise black and white. */
    val color: StateFlow<Boolean> = mutableColor

    private val mutableLetterbox = MutableStateFlow(false)

    /** Whether the whole image shows, on white; otherwise it's center-cropped to fill the view. */
    val letterbox: StateFlow<Boolean> = mutableLetterbox

    /** The image exactly as the display will show it: indices into its palette. */
    private val mutableView = MutableStateFlow(renderView(display, fitted, mutableDither.value, mutableColor.value))
    val view: StateFlow<ByteArray> = mutableView

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

    /**
     * Renders the preview for [display] from the same image, keeping the
     * dither choice, and readies the controller to write to it. Ignored while
     * a write is running.
     */
    fun choose(display: LabelDisplay) {
        if (writing || display == mutableDisplay.value) return
        mutableDisplay.value = display
        controller.choose(display)
        fitted = fit(source, display, mutableLetterbox.value)
        rerender()
    }

    /** Switches between dithering and a threshold. Ignored while a write is running. */
    fun toggleDither() {
        if (writing) return
        mutableDither.value =
            if (mutableDither.value == Dither.FloydSteinberg) Dither.Threshold(thresholdLevel) else Dither.FloydSteinberg
        rerender()
    }

    /** Switches between color and black and white. Ignored while a write is running. */
    fun toggleColor() {
        if (writing) return
        mutableColor.value = !mutableColor.value
        rerender()
    }

    /** Switches between filling the view and showing the whole image. Ignored while a write is running. */
    fun toggleLetterbox() {
        if (writing) return
        mutableLetterbox.value = !mutableLetterbox.value
        fitted = fit(source, mutableDisplay.value, mutableLetterbox.value)
        rerender()
    }

    /** Sets where gray becomes black (0–255) when dithering is off. Ignored while a write is running. */
    fun setThreshold(level: Int) {
        if (writing) return
        thresholdLevel = level.coerceIn(0, 255)
        if (mutableDither.value is Dither.Threshold) {
            mutableDither.value = Dither.Threshold(thresholdLevel)
            rerender()
        }
    }

    /** Shows a newly loaded [image], or holds it until a running write ends. */
    fun imageLoaded(image: FittedImage) {
        if (writing) {
            pendingSource = image
            return
        }
        show(image)
    }

    private fun show(image: FittedImage, keepFailure: Boolean = false) {
        pendingSource = null
        source = image
        fitted = fit(image, mutableDisplay.value, mutableLetterbox.value)
        rerender(keepFailure)
    }

    /**
     * Re-renders the preview, disarming any waiting write so a stale view
     * can't go out. A failure is cleared too unless [keepFailure].
     */
    private fun rerender(keepFailure: Boolean = false) {
        if (!(keepFailure && controller.state.value is WriteState.Failed)) controller.cancel()
        mutableView.value = renderView(mutableDisplay.value, fitted, mutableDither.value, mutableColor.value)
    }
}

/**
 * A loaded [image] scaled to every display's view, both cropped and
 * letterboxed, so choosing a display or re-rendering needs no scaling.
 * Scaling a photo takes a while: make this off the main thread, then hand it
 * to [WriteSession.imageLoaded].
 */
class FittedImage(image: ColorImage) {
    private val filled = LabelDisplay.entries.associateWith { fitView(it, image) }
    private val letterboxed = LabelDisplay.entries.associateWith { fitView(it, image, letterbox = true) }

    /** The image scaled to [display]'s view: whole on white if [letterbox], otherwise cropped to fill it. */
    internal fun forDisplay(display: LabelDisplay, letterbox: Boolean): ColorImage =
        (if (letterbox) letterboxed else filled).getValue(display)
}

/** [image] for [display], or the test pattern at its size (which fits either way) if null. */
private fun fit(image: FittedImage?, display: LabelDisplay, letterbox: Boolean): ColorImage =
    image?.forDisplay(display, letterbox) ?: ColorImage.fromGray(testPattern(display.viewWidth, display.viewHeight))
