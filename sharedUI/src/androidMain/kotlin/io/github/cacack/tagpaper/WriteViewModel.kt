package io.github.cacack.tagpaper

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cacack.tagpaper.image.Dither
import io.github.cacack.tagpaper.image.GrayImage
import io.github.cacack.tagpaper.image.fill
import io.github.cacack.tagpaper.image.testPattern
import io.github.cacack.tagpaper.nfc.FoundTag
import io.github.cacack.tagpaper.waveshare.WavesharePanel
import io.github.cacack.tagpaper.waveshare.renderView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the chosen image and runs writes in [viewModelScope], so a write
 * isn't cut short if the activity is recreated.
 */
class WriteViewModel : ViewModel() {
    val panel = WavesharePanel.EPD_2IN7
    val controller = WriteController(panel)

    /** The chosen image, already cropped and scaled to the panel's view. */
    private val source = MutableStateFlow(testPattern(panel.viewWidth, panel.viewHeight))
    private val mutableDither = MutableStateFlow<Dither>(Dither.FloydSteinberg)
    val dither: StateFlow<Dither> = mutableDither

    /** The image exactly as the panel will show it; `true` is white. */
    private val mutableView = MutableStateFlow(renderView(panel, source.value, mutableDither.value))
    val view: StateFlow<BooleanArray> = mutableView

    private val mutableLoadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableLoadError

    fun onTag(tag: FoundTag) {
        viewModelScope.launch { controller.onTag(tag) }
    }

    fun write() = controller.arm(view.value)

    /** Threshold level used whenever dithering is off; kept while dithering is on. */
    private var thresholdLevel = Dither.Threshold.DEFAULT_LEVEL

    fun toggleDither() {
        mutableDither.value =
            if (mutableDither.value == Dither.FloydSteinberg) Dither.Threshold(thresholdLevel) else Dither.FloydSteinberg
        rerender()
    }

    /** Sets where grey becomes black (0–255) when dithering is off. */
    fun setThreshold(level: Int) {
        thresholdLevel = level.coerceIn(0, 255)
        if (mutableDither.value is Dither.Threshold) {
            mutableDither.value = Dither.Threshold(thresholdLevel)
            rerender()
        }
    }

    fun load(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val image = loadGrayImage(context, uri)
                withContext(Dispatchers.Default) { image.fill(panel.viewWidth, panel.viewHeight) }
            }.onSuccess { image: GrayImage ->
                source.value = image
                mutableLoadError.value = null
                rerender()
            }.onFailure {
                mutableLoadError.value = "Couldn't open that image."
            }
        }
    }

    private fun rerender() {
        controller.cancel()
        mutableView.value = renderView(panel, source.value, mutableDither.value)
    }
}
