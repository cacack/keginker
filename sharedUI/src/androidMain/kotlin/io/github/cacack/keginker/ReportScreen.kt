package io.github.cacack.keginker

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.cacack.keginker.diagnostics.IssueLink
import kotlinx.coroutines.launch

/**
 * A diagnostics or display [report], shown in full before the user shares or
 * copies it. Nothing is sent anywhere unless they choose to. With an
 * [issueLink], it can also open a pre-filled GitHub issue in the browser.
 */
@Composable
fun ReportScreen(report: String, onBack: () -> Unit, title: String = "Diagnostics report", issueLink: IssueLink? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var noBrowser by remember { mutableStateOf(false) }
    val copy = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("KegInker ${title.lowercase()}", report)))
            copied = true
        }
    }

    Column(
        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("This is everything the report contains. Nothing leaves your phone unless you share it.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "KegInker ${title.lowercase()}")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, "Share ${title.lowercase()}"))
            }) { Text("Share") }
            OutlinedButton(onClick = { copy() }) { Text("Copy") }
            if (copied) Text("Copied", style = MaterialTheme.typography.bodySmall)
        }
        issueLink?.let { link ->
            OutlinedButton(onClick = {
                // Too long to fill in: copy it so the user can paste it into the form.
                if (!link.includesReport) copy()
                noBrowser = try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                    false
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }) { Text("Open a GitHub issue") }
            if (noBrowser) Text("No browser is installed to open it. Share or copy the report instead.", color = MaterialTheme.colorScheme.error)
            Text(link.caption, style = MaterialTheme.typography.bodySmall)
        }
        SelectionContainer {
            Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
