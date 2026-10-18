package io.github.cacack.tagpaper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Diagnostics most people never need, kept off the write screen. Android has
 * no CRC framing option: the NFC hardware adds the CRC itself.
 */
@Composable
fun AdvancedScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Advanced", style = MaterialTheme.typography.headlineSmall)

        Text("Unsupported displays", style = MaterialTheme.typography.titleMedium)
        Text("Report a display", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Text(
            "Coming soon: read what an unsupported tag reports about itself, so you can share it with the tagpaper project.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
