package io.github.cacack.keginker

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.image.GrayImage
import io.github.cacack.keginker.image.fill
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.waveshare.WavesharePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the [WriteSession] and the [probe], and runs writes and reads in
 * [viewModelScope], so neither is cut short if the activity is recreated.
 */
class WriteViewModel : ViewModel() {
    private val session = WriteSession(WavesharePanel.EPD_2IN7)
    val panel = session.panel
    val controller = session.controller

    /** "Report a display"; while it waits, found tags are read instead of written. */
    val probe = ProbeController()
    val dither: StateFlow<Dither> = session.dither

    /** The image exactly as the panel will show it; `true` is white. */
    val view: StateFlow<BooleanArray> = session.view

    private val mutableLoadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableLoadError

    fun onTag(tag: FoundTag) {
        viewModelScope.launch { if (probe.isWaiting) probe.onTag(tag) else session.onTag(tag) }
    }

    /** Arms "Report a display", first disarming any write, so a tag held during the report is never written. */
    fun reportDisplay() {
        controller.cancel()
        probe.arm()
    }

    fun write() = session.write()

    fun toggleDither() = session.toggleDither()

    /** Sets where grey becomes black (0–255) when dithering is off. */
    fun setThreshold(level: Int) = session.setThreshold(level)

    fun load(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val image = loadGrayImage(context, uri)
                withContext(Dispatchers.Default) { image.fill(panel.viewWidth, panel.viewHeight) }
            }.onSuccess { image: GrayImage ->
                mutableLoadError.value = null
                session.imageLoaded(image)
            }.onFailure {
                mutableLoadError.value = "Couldn't open that image."
            }
        }
    }
}
