package io.github.cacack.keginker.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.cacack.keginker.probe.describeNdefRecord
import io.github.cacack.keginker.probe.parseNdefMessage

/** An NDEF message as a tag's memory holds it: parsing and describing it never throws. */
object NdefFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        parseNdefMessage(data.consumeRemainingAsBytes()).records.forEach { describeNdefRecord(it) }
    }
}
