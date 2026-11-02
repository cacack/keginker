package io.github.cacack.keginker.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.cacack.keginker.image.WHITE
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.ImageWriter
import io.github.cacack.keginker.waveshare.WavesharePanel
import kotlinx.coroutines.runBlocking

/**
 * A Waveshare write, classic or Rev2.2, with every reply from the fuzzer.
 * Whatever the tag answers, the write returns a result and never throws.
 */
object WriteFuzzer {
    private val writer = ImageWriter(pause = {})

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val panel = data.pickValue(WavesharePanel.entries)
        val transport = FuzzTransport(data)
        val tag = if (data.consumeBoolean()) FoundTag.Waveshare(transport) else FoundTag.WaveshareIsoDep(transport)
        val view = ByteArray(panel.viewWidth * panel.viewHeight) { WHITE }
        runBlocking { writer.write(tag, panel, view) {} }
    }
}
