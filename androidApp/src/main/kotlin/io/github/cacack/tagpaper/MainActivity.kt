package io.github.cacack.tagpaper

import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.cacack.tagpaper.nfc.TagReader

class MainActivity : ComponentActivity() {
    private val tagReader by lazy { TagReader(NfcAdapter.getDefaultAdapter(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(tagReader.tags)
        }
    }

    override fun onResume() {
        super.onResume()
        tagReader.start(this)
    }

    override fun onPause() {
        tagReader.stop(this)
        super.onPause()
    }
}
