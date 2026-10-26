package io.github.cacack.keginker

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
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
import androidx.core.content.FileProvider
import io.github.cacack.keginker.diagnostics.IssueLink
import io.github.cacack.keginker.diagnostics.REPORT_FILE_NAME
import java.io.File
import kotlinx.coroutines.launch

/**
 * A diagnostics or display [report], shown in full before the user shares or
 * copies it. Nothing is sent anywhere unless they choose to. With an
 * [issueLink], it can also open a pre-filled GitHub issue in the browser; if
 * the report is too long for the link, Share sends it as a `.txt` file.
 */
@Composable
fun ReportScreen(
    report: String,
    onBack: () -> Unit,
    title: String = "Diagnostics report",
    issueLink: IssueLink? = null,
    /** Something more to do before sharing, shown above Share. */
    extra: (@Composable () -> Unit)? = null,
) {
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
        extra?.invoke()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val subject = "KegInker ${title.lowercase()}"
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    if (issueLink?.sharesReportAsFile == true) {
                        putExtra(Intent.EXTRA_STREAM, reportFile(context, report))
                        putExtra(Intent.EXTRA_TEXT, "$subject attached as a text file.")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
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

/**
 * Writes [report] to the cache as [REPORT_FILE_NAME], alone in its folder,
 * and returns a content URI the share target can read, through the app's
 * `.reports` FileProvider (see the manifest). Each share replaces the last
 * one's file.
 */
private fun reportFile(context: Context, report: String) =
    File(context.cacheDir, "reports").run {
        listFiles()?.forEach { it.delete() }
        mkdirs()
        val file = File(this, REPORT_FILE_NAME).apply { writeText(report) }
        FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
    }
