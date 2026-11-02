package io.github.cacack.keginker.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.cacack.keginker.diagnostics.AppInfo
import io.github.cacack.keginker.diagnostics.DeviceInfo
import io.github.cacack.keginker.diagnostics.formatDisplayReport
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.TagKind
import io.github.cacack.keginker.probe.probeDisplay
import kotlinx.coroutines.runBlocking

/**
 * The display probe against any kind of tag it sends commands to, with every
 * reply from the fuzzer. The probe never throws, and neither does writing
 * the report from what it read.
 */
object DisplayProbeFuzzer {
    private val app = AppInfo(version = "0.0.0-fuzz", buildType = "debug", commit = "fuzz")
    private val device = DeviceInfo(manufacturer = "Fuzz", model = "Fuzz", os = "Fuzz", nfcEnabled = true)
    private val kinds = listOf(TagKind.ISO_DEP, TagKind.NFC_A, TagKind.NFC_V)

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val kind = data.pickValue(kinds)
        val vendorQueries = data.consumeBoolean()
        val info = TagInfo(technologies = listOf(kind.name), uid = data.consumeBytes(10))
        val tag = FoundTag.Unsupported(info, kind) { FuzzTransport(data) }
        val result = runBlocking { probeDisplay(tag, vendorQueries) }
        formatDisplayReport(app, device, result)
    }
}
