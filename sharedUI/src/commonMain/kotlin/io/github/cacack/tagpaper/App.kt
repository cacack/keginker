package io.github.cacack.tagpaper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cacack.tagpaper.nfc.FoundTag
import kotlinx.coroutines.flow.Flow

@Composable
fun App(tags: Flow<FoundTag>) {
    var status by remember { mutableStateOf("Hold a tag to the back of the phone.") }
    LaunchedEffect(tags) {
        tags.collect { tag ->
            status = when (tag) {
                is FoundTag.Waveshare -> "Found a Waveshare tag.".also { tag.transport.close() }
                is FoundTag.Unsupported -> "Unsupported tag (UID ${tag.uid.toHex()})."
            }
        }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeContentPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(status, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun ByteArray.toHex() = joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
