package io.github.cacack.keginker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Diagnostics most people never need, kept off the write screen. Android has
 * no CRC framing option: the NFC hardware adds the CRC itself.
 *
 * [offerReports] is whether a successful write offers its diagnostics report.
 */
@Composable
fun AdvancedScreen(offerReports: Boolean, onOfferReportsChange: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Advanced", style = MaterialTheme.typography.headlineSmall)

        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Offer a report after every write", Modifier.weight(1f))
            Switch(checked = offerReports, onCheckedChange = onOfferReportsChange)
        }
        Text(
            "Failed writes always offer one. Turn this on when a write finishes but the tag doesn't look right.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Unsupported displays", style = MaterialTheme.typography.titleMedium)
        Text("Report a display", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Text(
            "Coming soon: read what an unsupported tag reports about itself, so you can share it with the KegInker project.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
