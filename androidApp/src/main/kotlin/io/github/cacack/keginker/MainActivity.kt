package io.github.cacack.keginker

import android.content.pm.ApplicationInfo
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import io.github.cacack.keginker.nfc.TagReader

class MainActivity : ComponentActivity() {
    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    private val savedReports by lazy { SavedReports(noBackupFilesDir, lifecycleScope) }
    private val tagReader by lazy { TagReader(NfcAdapter.getDefaultAdapter(this), debug = debuggable) }
    private val nfcProblem = mutableStateOf<String?>(null)
    private val deviceInfo = mutableStateOf(currentDeviceInfo(nfcEnabled = false))
    private val appInfo = AppInfo(
        version = BuildConfig.VERSION_NAME,
        buildType = BuildConfig.BUILD_TYPE,
        commit = BuildConfig.COMMIT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(
                tags = tagReader.tags,
                nfcProblem = nfcProblem.value,
                appInfo = appInfo,
                deviceInfo = deviceInfo.value,
                onSimulateTag = if (debuggable) tagReader::simulateWaveshareTag else null,
                onSimulateFailure = if (debuggable) tagReader::simulateFailingTag else null,
                saveReport = if (debuggable) { title, report -> savedReports.save(title, report) } else null,
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
        deviceInfo.value = currentDeviceInfo(nfcEnabled = tagReader.isEnabled)
        tagReader.start(this)
    }

    override fun onPause() {
        tagReader.stop(this)
        super.onPause()
    }

    private fun currentDeviceInfo(nfcEnabled: Boolean) = DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        nfcEnabled = nfcEnabled,
    )
}
