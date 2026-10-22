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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** "Report a display"; while it waits, found tags are read instead of written. Arm it with [reportDisplay]. */
    val probe = ProbeController()
    val dither: StateFlow<Dither> = session.dither

    /** The image exactly as the panel will show it; `true` is white. */
    val view: StateFlow<BooleanArray> = session.view

    private val mutableLoadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableLoadError

    fun onTag(tag: FoundTag) {
        viewModelScope.launch { if (probe.isWaiting) probe.onTag(tag) else session.onTag(tag) }
    }

    /**
     * Arms "Report a display", first disarming a waiting write, so a tag held
     * during the report is never written. A failed write keeps its warning and report.
     */
    fun reportDisplay() {
        if (controller.state.value == WriteState.Waiting) controller.cancel()
        probe.arm()
    }

    fun write() = session.write()

    fun toggleDither() = session.toggleDither()

    /** Sets where grey becomes black (0–255) when dithering is off. */
    fun setThreshold(level: Int) = session.setThreshold(level)

    private var loading: Job? = null

    fun load(context: Context, uri: Uri) {
        load { loadGrayImage(context, uri) }
    }

    /** Loads an image from [read], cancelling any load still running, so the latest pick wins. */
    internal fun load(read: suspend () -> GrayImage): Job {
        loading?.cancel()
        return viewModelScope.launch {
            runCatching {
                val image = read()
                withContext(Dispatchers.Default) { image.fill(panel.viewWidth, panel.viewHeight) }
            }.onSuccess { image: GrayImage ->
                mutableLoadError.value = null
                session.imageLoaded(image)
            }.onFailure {
                if (it is CancellationException) throw it
                mutableLoadError.value = "Couldn't open that image."
            }
        }.also { loading = it }
    }
}
