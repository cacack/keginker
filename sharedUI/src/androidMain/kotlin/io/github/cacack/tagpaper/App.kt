package io.github.cacack.tagpaper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.cacack.tagpaper.image.Dither
import io.github.cacack.tagpaper.image.testPattern
import io.github.cacack.tagpaper.nfc.FoundTag
import io.github.cacack.tagpaper.waveshare.WavesharePanel
import io.github.cacack.tagpaper.waveshare.packFrame
import io.github.cacack.tagpaper.waveshare.renderView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val PANEL = WavesharePanel.EPD_2IN7

/**
 * The write screen: choose an image, preview it as the panel will show it,
 * then write it to a tag.
 *
 * [nfcProblem] explains why tags can't be read (no NFC, NFC off), if so.
 * [onSimulateTag] is offered in debug builds to exercise the flow without a tag.
 */
@Composable
fun App(tags: Flow<FoundTag>, nfcProblem: String?, onSimulateTag: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { WriteController(PANEL) }
    val state by controller.state.collectAsState()
    var source by remember { mutableStateOf(testPattern(PANEL.viewWidth, PANEL.viewHeight)) }
    var dither by remember { mutableStateOf<Dither>(Dither.FloydSteinberg) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val view = remember(source, dither) { renderView(PANEL, source, dither) }
    val preview = remember(view) { view.toPreviewBitmap(PANEL.viewWidth).asImageBitmap() }
    val busy = state is WriteState.Writing

    LaunchedEffect(tags) { tags.collect { controller.onTag(it) } }

    val pickPhoto = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { loadGrayImage(context, uri) }
                .onSuccess { source = it; loadError = null; controller.cancel() }
                .onFailure { loadError = "Couldn't open that image." }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Waveshare 2.7\" e-paper", style = MaterialTheme.typography.titleMedium)
                Image(
                    bitmap = preview,
                    contentDescription = "Preview of the tag",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.width(220.dp).aspectRatio(PANEL.viewWidth / PANEL.viewHeight.toFloat())
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    ) { Text("Choose image") }
                    FilterChip(
                        selected = dither == Dither.FloydSteinberg,
                        enabled = !busy,
                        onClick = {
                            dither = if (dither == Dither.FloydSteinberg) Dither.Threshold() else Dither.FloydSteinberg
                            controller.cancel()
                        },
                        label = { Text("Dither") },
                    )
                }
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                nfcProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                WriteStatus(
                    state = state,
                    onWrite = { controller.arm(packFrame(PANEL, view)) },
                    onCancel = controller::cancel,
                    onSimulateTag = onSimulateTag,
                )
            }
        }
    }
}

@Composable
private fun WriteStatus(state: WriteState, onWrite: () -> Unit, onCancel: () -> Unit, onSimulateTag: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            WriteState.Idle -> Button(onClick = onWrite) { Text("Write to tag") }
            WriteState.Waiting -> {
                Text("Hold the back of the phone against the tag and keep it still.")
                Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    onSimulateTag?.let { TextButton(onClick = it) { Text("Simulate a tag") } }
                }
            }
            is WriteState.Writing -> {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Writing ${state.percent}%. Keep the phone still.")
            }
            WriteState.Done -> {
                Text("Done. The tag keeps this image without power.")
                Button(onClick = onWrite) { Text("Write another tag") }
            }
            is WriteState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onWrite) { Text("Retry") }
            }
        }
    }
}
