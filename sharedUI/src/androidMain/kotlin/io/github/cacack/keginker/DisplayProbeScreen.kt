package io.github.cacack.keginker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import io.github.cacack.keginker.diagnostics.displayReportIssueLink
import io.github.cacack.keginker.diagnostics.formatDisplayReport

/**
 * "Report a display": waits for a tag, reads what it says about itself
 * without writing to it, and shows the report. [nfcProblem] explains why tags
 * can't be read, if so. [vendorQueries] is the "Also try vendor commands"
 * switch, which can't change while a tag is being read.
 */
@Composable
fun DisplayProbeScreen(
    state: ProbeState,
    appInfo: AppInfo,
    deviceInfo: DeviceInfo,
    nfcProblem: String?,
    vendorQueries: Boolean,
    onVendorQueriesChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    if (state is ProbeState.Done) {
        val text = remember(state) { formatDisplayReport(appInfo, deviceInfo, state.result) }
        ReportScreen(report = text, onBack = onBack, title = "Display report", issueLink = remember(text) { displayReportIssueLink(text) })
        return
    }
    Column(
        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Report a display", style = MaterialTheme.typography.headlineSmall)
        Text(
            "KegInker reads what the tag says about itself and writes nothing to it. You see the report in full, then choose whether to share it.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Also try vendor commands", modifier = Modifier.weight(1f))
            Switch(checked = vendorQueries, onCheckedChange = onVendorQueriesChange, enabled = state != ProbeState.Reading)
        }
        Text(
            "These commands aren't documented. They could change what the display shows.",
            style = MaterialTheme.typography.bodySmall,
        )
        nfcProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state == ProbeState.Reading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Reading the tag. Keep the phone still.")
        } else {
            Text("Hold the back of the phone against the tag and keep it still. If it's already there, lift it away and back.")
        }
    }
}
