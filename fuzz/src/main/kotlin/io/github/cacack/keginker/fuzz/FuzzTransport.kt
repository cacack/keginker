package io.github.cacack.keginker.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.cacack.keginker.nfc.Transport
import java.io.IOException

/**
 * A tag whose every reply comes from the fuzzer, up to [MAX_REPLY] bytes
 * each. When the input runs out, the tag leaves the field: [transceive]
 * throws, as [Transport] says a lost tag does.
 */
internal class FuzzTransport(private val data: FuzzedDataProvider) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (data.remainingBytes() == 0) throw IOException("Tag lost")
        return data.consumeBytes(data.consumeInt(0, MAX_REPLY))
    }

    private companion object {
        /** Longer than any reply the parsers expect: an ISO-DEP read of 256 bytes, plus its status word. */
        const val MAX_REPLY = 300
    }
}
