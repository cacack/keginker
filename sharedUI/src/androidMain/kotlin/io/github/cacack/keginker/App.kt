package io.github.cacack.keginker

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import io.github.cacack.keginker.diagnostics.formatDiagnosticReport
import io.github.cacack.keginker.image.Dither
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.waveshare.DONE_MESSAGE
import io.github.cacack.keginker.waveshare.writeProgressMessage
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

/**
 * The write screen: choose a display and an image, preview it as the
 * display will show it, then write it to a tag. The display is remembered.
 *
 * [nfcProblem] explains why tags can't be read (no NFC, NFC off), if so.
 * [appInfo] and [deviceInfo] go into the diagnostics report offered after a
 * failure, and after a success when turned on under Advanced.
 * [onSimulateTag] and [onSimulateFailure] are offered in debug builds to
 * exercise the flow without a tag, and [saveReport] saves each report shown
 * (see [ReportScreen]).
 */
@Composable
fun App(
    tags: Flow<FoundTag>,
    appInfo: AppInfo,
    deviceInfo: DeviceInfo,
    nfcProblem: String? = null,
    onSimulateTag: (() -> Unit)? = null,
    onSimulateFailure: (() -> Unit)? = null,
    saveReport: ((title: String, report: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val displaySetting = remember { DisplaySetting(context) }
    val model: WriteViewModel = viewModel { WriteViewModel(displaySetting.display) }
    val state by model.controller.state.collectAsState()
    val dither by model.dither.collectAsState()
    val color by model.color.collectAsState()
    val letterbox by model.letterbox.collectAsState()
    val view by model.view.collectAsState()
    val loadError by model.loadError.collectAsState()
    val display by model.display.collectAsState()
    // Saved from the model, which ignores a choice made while a write runs.
    LaunchedEffect(display) { displaySetting.display = display }
    // The session sets the display before rendering its view, so a new view's display is current.
    val preview = remember(view) { previewBitmap(model.display.value, view).asImageBitmap() }
    val busy = state is WriteState.Writing
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val probeState by model.probe.state.collectAsState()
    val testWrites by model.testWrites.collectAsState()
    var showProbe by rememberSaveable { mutableStateOf(false) }
    // Off each time "Report a display" opens; saved so it matches the armed probe after recreation.
    var vendorQueries by rememberSaveable { mutableStateOf(false) }
    val reportSetting = remember { ReportSetting(context) }
    var offerReports by remember { mutableStateOf(reportSetting.offerAfterSuccess) }
    // Saved so the report stays open across rotation.
    var showReport by rememberSaveable { mutableStateOf(false) }
    val offeredReport = when (val current = state) {
        is WriteState.Failed -> current.report
        is WriteState.Done -> current.report.takeIf { offerReports }
        else -> null
    }
    val shownReport = offeredReport?.takeIf { showReport }
    // The report can go away under the open screen (an image finishing loading
    // resets the state, or the process restarts); don't reopen it for the next one.
    LaunchedEffect(offeredReport) { if (offeredReport == null) showReport = false }

    // Collect here, not in a screen, so tags keep arriving whichever screen shows.
    LaunchedEffect(tags) { tags.collect(model::onTag) }
    BackHandler(enabled = showAdvanced) { showAdvanced = false }
    val closeProbe = {
        model.closeProbe()
        showProbe = false
    }
    BackHandler(enabled = showProbe, onBack = closeProbe)
    // After the process restarts, the screen is restored but the probe isn't.
    LaunchedEffect(showProbe) { if (showProbe && probeState == ProbeState.Idle) model.reportDisplay(vendorQueries) }
    BackHandler(enabled = shownReport != null) { showReport = false }

    val pickPhoto = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) model.load(context, uri)
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (showProbe) {
                DisplayProbeScreen(
                    probeState,
                    testWrites,
                    appInfo,
                    deviceInfo,
                    nfcProblem,
                    vendorQueries = vendorQueries,
                    onVendorQueriesChange = {
                        vendorQueries = it
                        model.reportDisplay(it)
                    },
                    onOfferTestWrites = model::offerTestWrites,
                    onLeaveTestWrites = model::leaveTestWrites,
                    onBack = closeProbe,
                    saveReport = saveReport,
                )
                return@Surface
            }
            if (showAdvanced) {
                AdvancedScreen(
                    offerReports = offerReports,
                    onOfferReportsChange = {
                        offerReports = it
                        reportSetting.offerAfterSuccess = it
                    },
                    onReportDisplay = {
                        vendorQueries = false
                        model.reportDisplay(vendorQueries)
                        showProbe = true
                    },
                    onBack = { showAdvanced = false },
                )
                return@Surface
            }
            shownReport?.let { report ->
                val text = remember(report) { formatDiagnosticReport(appInfo, deviceInfo, report) }
                ReportScreen(report = text, onBack = { showReport = false }, saveReport = saveReport)
                return@Surface
            }
            Column(
                modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = { showAdvanced = true }) { Text("Advanced") }
                }
                DisplayPicker(display = display, enabled = !busy, onChoose = model::choose)
                Image(
                    bitmap = preview,
                    contentDescription = "Preview of the tag",
                    filterQuality = FilterQuality.None,
                    // Fits the tallest label (EZ Sign 2.9") without cropping; Waveshare 2.7" fills it at 220 × 330.
                    modifier = Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 330.dp)
                        .aspectRatio(preview.width / preview.height.toFloat())
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
                        onClick = model::toggleDither,
                        label = { Text("Dither") },
                    )
                    FilterChip(
                        selected = letterbox,
                        enabled = !busy,
                        onClick = model::toggleLetterbox,
                        label = { Text("Fit") },
                    )
                    if (display.palette.hasColor) {
                        FilterChip(
                            selected = color,
                            enabled = !busy,
                            onClick = model::toggleColor,
                            label = { Text("Color") },
                        )
                    }
                }
                (dither as? Dither.Threshold)?.let { threshold ->
                    ThresholdControl(level = threshold.level, enabled = !busy, onChange = model::setThreshold)
                }
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                nfcProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                WriteStatus(
                    state = state,
                    canWrite = nfcProblem == null || onSimulateTag != null,
                    onWrite = model::write,
                    onCancel = model.controller::cancel,
                    onSimulateTag = onSimulateTag,
                    onSimulateFailure = onSimulateFailure,
                    onShareDetails = { showReport = true }.takeIf { offeredReport != null },
                )
            }
        }
    }
}

/** Which display labels are written to; the preview is rendered for it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayPicker(display: LabelDisplay, enabled: Boolean, onChoose: (LabelDisplay) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { expanded = it },
        modifier = Modifier.width(260.dp),
    ) {
        OutlinedTextField(
            value = display.title,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text("Display") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            LabelDisplay.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    onClick = {
                        onChoose(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Where gray becomes black when dithering is off; higher keeps light grays black. */
@Composable
private fun ThresholdControl(level: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Black below gray level $level", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = level.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            enabled = enabled,
        )
    }
}

@Composable
private fun WriteStatus(
    state: WriteState,
    canWrite: Boolean,
    onWrite: () -> Unit,
    onCancel: () -> Unit,
    onSimulateTag: (() -> Unit)?,
    onSimulateFailure: (() -> Unit)?,
    /** Null when there's no report to offer. */
    onShareDetails: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            WriteState.Idle -> Button(enabled = canWrite, onClick = onWrite) { Text("Write to tag") }
            WriteState.Waiting -> {
                Text("Hold the back of the phone against the tag and keep it still. If it's already there, lift it away and back.")
                Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    onSimulateTag?.let { TextButton(onClick = it) { Text("Simulate a tag") } }
                    onSimulateFailure?.let { TextButton(onClick = it) { Text("Simulate a failure") } }
                }
            }
            is WriteState.Writing -> {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(writeProgressMessage(state.percent, phone = "phone"))
            }
            is WriteState.Done -> {
                Text(DONE_MESSAGE)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onWrite) { Text("Write another tag") }
                    onShareDetails?.let { OutlinedButton(onClick = it) { Text("Share details") } }
                }
            }
            is WriteState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onWrite) { Text("Retry") }
                    onShareDetails?.let { OutlinedButton(onClick = it) { Text("Share details") } }
                }
            }
        }
    }
}
