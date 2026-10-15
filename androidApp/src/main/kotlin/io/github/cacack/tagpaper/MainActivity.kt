package io.github.cacack.tagpaper

import android.content.pm.ApplicationInfo
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.cacack.tagpaper.nfc.TagReader

class MainActivity : ComponentActivity() {
    private val tagReader by lazy { TagReader(NfcAdapter.getDefaultAdapter(this)) }
    private val nfcProblem = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            App(
                tags = tagReader.tags,
                nfcProblem = nfcProblem.value,
                onSimulateTag = if (debuggable) tagReader::simulateWaveshareTag else null,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        nfcProblem.value = when {
            !tagReader.isSupported -> "This phone doesn't have NFC."
            !tagReader.isEnabled -> "NFC is off. Turn it on in Settings to write tags."
            else -> null
        }
        tagReader.start(this)
    }

    override fun onPause() {
        tagReader.stop(this)
        super.onPause()
    }
}
