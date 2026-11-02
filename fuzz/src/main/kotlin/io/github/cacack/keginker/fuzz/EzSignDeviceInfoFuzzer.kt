package io.github.cacack.keginker.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.cacack.keginker.ezsign.parseEzSignDeviceInfo

/** A reply to the EZ Sign device information query: decoding it never throws. */
object EzSignDeviceInfoFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        parseEzSignDeviceInfo(data.consumeRemainingAsBytes())
    }
}
